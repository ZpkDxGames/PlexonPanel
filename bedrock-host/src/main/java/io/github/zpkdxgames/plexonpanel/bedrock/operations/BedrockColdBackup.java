package io.github.zpkdxgames.plexonpanel.bedrock.operations;

import java.io.IOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static io.github.zpkdxgames.plexonpanel.bedrock.operations.BedrockContainerControl.Container;
import static io.github.zpkdxgames.plexonpanel.bedrock.operations.BedrockContainerControl.Phase;
import static io.github.zpkdxgames.plexonpanel.bedrock.operations.BedrockContainerControl.Readiness;

/** Local cold backup orchestration. The production broker has no route to this capability. */
public final class BedrockColdBackup {
    public enum Status {
        DISABLED, CONSENT_REQUIRED, BUSY, RECOVERY_REQUIRED, PREFLIGHT_FAILED, FAILED,
        VERIFIED, ALREADY_VERIFIED, VERIFIED_RETENTION_PENDING
    }

    public enum Availability { UNCHANGED, RUNNING_UNVERIFIED, READY, RECOVERY_REQUIRED }
    public record Result(Status status, Availability availability) {}

    private static final String ARCHIVE_NAME = "world.zip";
    private static final String MANIFEST_NAME = "manifest";
    private static final String RECOVERY_NAME = ".recovery";
    private static final String FORMAT = "plexonpanel-bedrock-cold-v1";
    private static final Set<PosixFilePermission> PRIVATE_DIRECTORY = PosixFilePermissions.fromString("rwx------");
    private static final long MAX_BYTES = 1L << 40;

    private final boolean enabled;
    private final BedrockContainerControl control;
    private final BedrockOperationLock operationLock;
    private final Path dataRoot;
    private final Path backupRoot;
    private final long sizeLimit;
    private final int retention;
    private final Duration stopGrace;

    public BedrockColdBackup(boolean enabled, BedrockContainerControl control, BedrockOperationLock operationLock,
                             Path dataRoot, Path backupRoot, long sizeLimit, int retention, Duration stopGrace) {
        this.enabled = enabled;
        this.control = Objects.requireNonNull(control);
        this.operationLock = Objects.requireNonNull(operationLock);
        this.dataRoot = absolutePath(dataRoot);
        this.backupRoot = absolutePath(backupRoot);
        this.sizeLimit = sizeLimit;
        this.retention = retention;
        this.stopGrace = Objects.requireNonNull(stopGrace);
        if (dataRoot.startsWith(backupRoot) || backupRoot.startsWith(dataRoot)
                || sizeLimit <= 0 || sizeLimit > MAX_BYTES || retention < 1 || retention > 100
                || stopGrace.compareTo(Duration.ofSeconds(30)) < 0 || stopGrace.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("Bedrock backup policy is outside staging bounds");
        }
    }

    public Result create(UUID requestId, boolean playerDisconnectionApproved) {
        Objects.requireNonNull(requestId);
        if (!enabled) {
            return new Result(Status.DISABLED, Availability.UNCHANGED);
        }
        if (!operationLock.acquire()) {
            return new Result(Status.BUSY, Availability.UNCHANGED);
        }
        try {
            return createLocked(requestId, playerDisconnectionApproved);
        } finally {
            operationLock.release();
        }
    }

    /** Resume an interrupted restart using local approval and the exact locally recorded target. */
    public Availability recover() {
        if (!enabled || !operationLock.acquire()) {
            return Availability.RECOVERY_REQUIRED;
        }
        try {
            checkPrivateRoot();
            if (Files.notExists(backupRoot.resolve(RECOVERY_NAME), LinkOption.NOFOLLOW_LINKS)) {
                return Availability.UNCHANGED;
            }
            return recoverLocked(readRecovery());
        } catch (IOException | IllegalArgumentException failed) {
            return Availability.RECOVERY_REQUIRED;
        } finally {
            operationLock.release();
        }
    }

    private Result createLocked(UUID requestId, boolean playerDisconnectionApproved) {
        Path bundle = backupRoot.resolve(requestId.toString());
        Container before;
        String dataKey;
        try {
            checkPrivateRoot();
            if (!Files.notExists(backupRoot.resolve(RECOVERY_NAME), LinkOption.NOFOLLOW_LINKS)) {
                return new Result(Status.RECOVERY_REQUIRED, Availability.RECOVERY_REQUIRED);
            }
            if (!Files.notExists(bundle, LinkOption.NOFOLLOW_LINKS)) {
                return new Result(verifyExisting(bundle, requestId) ? Status.ALREADY_VERIFIED : Status.FAILED,
                        Availability.UNCHANGED);
            }
            before = inspectPinned();
            if (before.phase() != Phase.RUNNING && !before.cleanExit()) {
                return new Result(Status.PREFLIGHT_FAILED, Availability.UNCHANGED);
            }
            if (before.phase() == Phase.RUNNING && !playerDisconnectionApproved) {
                return new Result(Status.CONSENT_REQUIRED, Availability.UNCHANGED);
            }
            dataKey = BedrockBackupFiles.preflight(dataRoot, backupRoot, sizeLimit);
        } catch (IOException failed) {
            return new Result(Status.PREFLIGHT_FAILED, Availability.UNCHANGED);
        }

        boolean wasRunning = before.phase() == Phase.RUNNING;
        Recovery recovery = wasRunning ? new Recovery(before.id(), before.startedAt(), dataKey) : null;
        Availability availability = Availability.UNCHANGED;
        Status status = Status.FAILED;
        try {
            if (wasRunning) {
                writeRecovery(recovery);
                control.stop(before, stopGrace);
            }
            verifyDown(before);
            if (!dataKey.equals(BedrockBackupFiles.preflight(dataRoot, backupRoot, sizeLimit))) {
                throw new IOException("Bedrock data root changed before backup");
            }
            archive(bundle, requestId, before, dataKey);
            status = Status.VERIFIED;
        } catch (IOException failed) {
            // The failure classification and restore intent are sufficient for callers and avoid path leakage.
        } finally {
            if (wasRunning) {
                availability = recoverLocked(recovery);
            }
        }
        if (status == Status.VERIFIED) {
            try {
                prune(bundle);
            } catch (IOException failed) {
                status = Status.VERIFIED_RETENTION_PENDING;
            }
        }
        return new Result(status, availability);
    }

    private void verifyDown(Container before) throws IOException {
        Container stopped = inspectPinned();
        if (!before.sameLaunch(stopped) || !stopped.cleanExit()) {
            throw new IOException("Graceful Bedrock shutdown was not verified");
        }
    }

    private void archive(Path bundle, UUID id, Container before, String dataKey) throws IOException {
        Path staging = Files.createTempDirectory(backupRoot, ".stage-", PosixFilePermissions.asFileAttribute(PRIVATE_DIRECTORY));
        try {
            String hash = BedrockBackupFiles.archive(dataRoot, staging.resolve(ARCHIVE_NAME), sizeLimit, dataKey);
            verifyDown(before);
            Path manifest = staging.resolve(MANIFEST_NAME);
            Files.writeString(manifest, FORMAT + "\n" + id + "\n" + hash + "\n",
                    StandardOpenOption.CREATE_NEW);
            BedrockBackupFiles.force(manifest);
            BedrockBackupFiles.force(staging);
            Files.move(staging, bundle, StandardCopyOption.ATOMIC_MOVE);
            BedrockBackupFiles.force(backupRoot);
        } finally {
            deleteBundle(staging);
        }
    }

    private Availability recoverLocked(Recovery recovery) {
        Path marker = backupRoot.resolve(RECOVERY_NAME);
        try {
            if (Files.notExists(marker, LinkOption.NOFOLLOW_LINKS)) {
                return Availability.UNCHANGED;
            }
            if (!readRecovery().equals(recovery) || !recovery.dataKey().equals(BedrockBackupFiles.rootIdentity(dataRoot))) {
                return Availability.RECOVERY_REQUIRED;
            }
            Container current = inspectPinned();
            if (!current.id().equals(recovery.containerId())) {
                return Availability.RECOVERY_REQUIRED;
            }
            if (current.phase() == Phase.EXITED && recovery.startedAt().equals(current.startedAt())) {
                control.start(current);
                current = inspectPinned();
            }
            if (!current.id().equals(recovery.containerId()) || current.phase() != Phase.RUNNING
                    || !current.startedAt().isAfter(recovery.startedAt())) {
                return Availability.RECOVERY_REQUIRED;
            }
            Files.delete(marker);
            BedrockBackupFiles.force(backupRoot);
            return current.readiness() == Readiness.VERIFIED ? Availability.READY : Availability.RUNNING_UNVERIFIED;
        } catch (IOException | IllegalArgumentException failed) {
            return Availability.RECOVERY_REQUIRED;
        }
    }

    private void writeRecovery(Recovery recovery) throws IOException {
        Path staging = Files.createTempFile(backupRoot, ".recovery-", ".tmp",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        try {
            Files.writeString(staging, recovery.containerId() + "\n" + recovery.startedAt() + "\n" + recovery.dataKey() + "\n");
            BedrockBackupFiles.force(staging);
            Path marker = backupRoot.resolve(RECOVERY_NAME);
            if (!Files.notExists(marker, LinkOption.NOFOLLOW_LINKS)) {
                throw new FileAlreadyExistsException(RECOVERY_NAME);
            }
            Files.move(staging, marker, StandardCopyOption.ATOMIC_MOVE);
            BedrockBackupFiles.force(backupRoot);
        } finally {
            Files.deleteIfExists(staging);
        }
    }

    private Recovery readRecovery() throws IOException {
        List<String> value = smallRecord(backupRoot.resolve(RECOVERY_NAME));
        if (value.size() != 3 || !value.get(0).matches("[0-9a-f]{64}")
                || !BedrockBackupFiles.validIdentity(value.get(2))) {
            throw new IOException("Invalid Bedrock backup recovery record");
        }
        try {
            return new Recovery(value.get(0), Instant.parse(value.get(1)), value.get(2));
        } catch (java.time.format.DateTimeParseException invalid) {
            throw new IOException("Invalid Bedrock backup recovery timestamp", invalid);
        }
    }

    private Container inspectPinned() throws IOException {
        Container container = control.inspect();
        if (!dataRoot.equals(container.dataMount())) {
            throw new IOException("Bedrock data mount changed");
        }
        return container;
    }

    private void checkPrivateRoot() throws IOException {
        if (!backupRoot.equals(backupRoot.toRealPath()) || !Files.isDirectory(backupRoot, LinkOption.NOFOLLOW_LINKS)
                || !PRIVATE_DIRECTORY.equals(Files.getPosixFilePermissions(backupRoot, LinkOption.NOFOLLOW_LINKS))) {
            throw new IOException("Bedrock backup directory must be private and canonical");
        }
    }

    private boolean verifyExisting(Path bundle, UUID id) throws IOException {
        if (!Files.isDirectory(bundle, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        List<String> manifest = smallRecord(bundle.resolve(MANIFEST_NAME));
        Path archive = bundle.resolve(ARCHIVE_NAME);
        return manifest.size() == 3 && FORMAT.equals(manifest.get(0)) && id.toString().equals(manifest.get(1))
                && manifest.get(2).matches("[0-9a-f]{64}") && Files.isRegularFile(archive, LinkOption.NOFOLLOW_LINKS)
                && manifest.get(2).equals(BedrockBackupFiles.hash(archive));
    }

    private void prune(Path newest) throws IOException {
        List<Retained> older = new ArrayList<>();
        try (var children = Files.newDirectoryStream(backupRoot)) {
            for (Path child : children) {
                if (!child.equals(newest) && isManagedBundle(child)) {
                    older.add(new Retained(child, Files.getLastModifiedTime(child, LinkOption.NOFOLLOW_LINKS).toInstant()));
                }
            }
        } catch (DirectoryIteratorException failed) {
            throw failed.getCause();
        }
        older.sort(Comparator.comparing(Retained::modified).reversed().thenComparing(Retained::path));
        for (Retained expired : older.subList(Math.min(older.size(), retention - 1), older.size())) {
            deleteBundle(expired.path());
        }
        BedrockBackupFiles.force(backupRoot);
    }

    private static boolean isManagedBundle(Path child) throws IOException {
        String name = child.getFileName().toString();
        Path manifest = child.resolve(MANIFEST_NAME);
        if (!name.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                || !Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)
                || !Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS)
                || !Files.isRegularFile(child.resolve(ARCHIVE_NAME), LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        List<String> value = smallRecord(manifest);
        return value.size() == 3 && FORMAT.equals(value.get(0)) && name.equals(value.get(1))
                && value.get(2).matches("[0-9a-f]{64}");
    }

    private static void deleteBundle(Path directory) throws IOException {
        Files.deleteIfExists(directory.resolve(ARCHIVE_NAME));
        Files.deleteIfExists(directory.resolve(MANIFEST_NAME));
        Files.deleteIfExists(directory);
    }

    private static List<String> smallRecord(Path record) throws IOException {
        if (!Files.isRegularFile(record, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Invalid Bedrock backup record");
        }
        try (var input = Files.newInputStream(record, LinkOption.NOFOLLOW_LINKS)) {
            byte[] value = input.readNBytes(513);
            if (value.length > 512) {
                throw new IOException("Bedrock backup record too large");
            }
            return new String(value, java.nio.charset.StandardCharsets.UTF_8).lines().toList();
        }
    }

    private static Path absolutePath(Path path) {
        if (!path.isAbsolute() || !path.normalize().equals(path)) {
            throw new IllegalArgumentException("Bedrock backup paths must be absolute and normalized");
        }
        return path;
    }

    private record Recovery(String containerId, Instant startedAt, String dataKey) {}
    private record Retained(Path path, Instant modified) {}
}
