package io.github.zpkdxgames.plexonpanel.host;

import com.google.gson.*;
import io.github.zpkdxgames.plexonpanel.util.AtomicFiles;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.*;
import java.util.zip.*;

/** Cold full-server restore points. Archive creation always requires a proven-stopped service. */
public final class FullRestorePointManager {
  public record Metadata(
      String backupId,
      String jobId,
      String type,
      String timestamp,
      String startedAt,
      String completedAt,
      long durationMillis,
      String serverName,
      String serverVersion,
      String paperVersion,
      String plexonPanelVersion,
      long archiveBytes,
      long sourceBytes,
      int entryCount,
      String sha256,
      boolean local,
      boolean offsite,
      String remoteProvider,
      String remotePath,
      String verification,
      String initiatedBy,
      boolean automatic,
      boolean emergency,
      boolean restartPerformed,
      String result,
      String errorCode) {}

  public record RestoreGrant(String token, String backupId, String deviceId, long expiresAt) {}

  private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
  private final HostConfig config;
  private final Path root, base, restorePoints, metadataDirectory, staging;
  private final SystemdService service;
  private final BooleanSupplier paperConnected;
  private final ReentrantLock operationLock;
  private final RcloneBackupProvider provider;
  private final Consumer<Map<String, Object>> progress;
  private final Map<String, RestoreGrant> restoreGrants = new HashMap<>();
  private volatile long progressAt;

  public FullRestorePointManager(
      HostConfig config,
      SystemdService service,
      BooleanSupplier paperConnected,
      ReentrantLock operationLock,
      Consumer<Map<String, Object>> progress)
      throws IOException {
    this.config = Objects.requireNonNull(config);
    this.root = Path.of(config.serverRoot()).toRealPath();
    this.base = Path.of(config.backups().directory()).toAbsolutePath().normalize();
    this.restorePoints = base.resolve("restore-points");
    this.metadataDirectory = base.resolve("metadata");
    this.staging = base.resolve("staging");
    this.service = Objects.requireNonNull(service);
    this.paperConnected = Objects.requireNonNull(paperConnected);
    this.operationLock = Objects.requireNonNull(operationLock);
    this.provider = new RcloneBackupProvider(config.backups());
    this.progress = Objects.requireNonNull(progress);
    if (base.startsWith(root) || Files.isSymbolicLink(base))
      throw new IOException("Backups must remain outside the Minecraft server root");
    Files.createDirectories(restorePoints);
    Files.createDirectories(metadataDirectory);
    Files.createDirectories(staging);
    for (Path path : List.of(base, restorePoints, metadataDirectory, staging))
      if (Files.isSymbolicLink(path)) throw new IOException("Backup storage may not be a symlink");
    PartialBackupRecovery.clean(staging);
  }

  public List<Metadata> list() throws IOException {
    List<Metadata> result = new ArrayList<>();
    try (var files = Files.list(metadataDirectory)) {
      for (Path file : files.filter(p -> p.getFileName().toString().matches("[0-9a-f-]{36}\\.json")).limit(1001).toList()) {
        if (Files.isSymbolicLink(file) || Files.size(file) > 32_768) continue;
        Metadata value = GSON.fromJson(Files.readString(file), Metadata.class);
        if (value != null && "FULL_RESTORE_POINT".equals(value.type)) {
          Path local = restorePoints.resolve(value.backupId + ".zip");
          boolean localAvailable =
              Files.isRegularFile(local, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(local);
          result.add(withLocalState(value, localAvailable, value.verification));
        }
      }
    }
    result.sort(Comparator.comparing(Metadata::timestamp).reversed());
    return result;
  }

  public Metadata metadata(String backupId) throws IOException {
    UUID.fromString(backupId);
    Path file = metadataDirectory.resolve(backupId + ".json");
    if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
        || Files.isSymbolicLink(file)
        || Files.size(file) > 32_768) throw new IOException("Backup metadata unavailable");
    Metadata value = GSON.fromJson(Files.readString(file), Metadata.class);
    if (value == null || !backupId.equals(value.backupId) || !"FULL_RESTORE_POINT".equals(value.type))
      throw new IOException("Invalid full restore-point metadata");
    return value;
  }

  public Path archive(String backupId) throws IOException {
    UUID.fromString(backupId);
    Path value = restorePoints.resolve(backupId + ".zip");
    if (!Files.isRegularFile(value, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(value))
      throw new IOException("Full restore-point archive unavailable");
    return value;
  }

  public Metadata create(
      String jobId,
      String initiatedBy,
      boolean automatic,
      boolean emergency,
      MaintenanceSettings.FullRestorePoint settings,
      boolean uploadOffsite)
      throws Exception {
    if (!operationLock.tryLock()) throw new SecurityException("BUSY");
    try {
      return createLocked(jobId, initiatedBy, automatic, emergency, settings, uploadOffsite);
    } finally {
      operationLock.unlock();
    }
  }

  Metadata createLocked(
      String jobId,
      String initiatedBy,
      boolean automatic,
      boolean emergency,
      MaintenanceSettings.FullRestorePoint settings,
      boolean uploadOffsite)
      throws Exception {
    UUID.fromString(jobId);
    if (!service.stopped()) throw new SecurityException("SERVER_MUST_BE_STOPPED");
    long startedNanos = System.nanoTime();
    String backupId = UUID.randomUUID().toString(), startedAt = Instant.now().toString();
    Path partial = staging.resolve(backupId + ".partial"),
        finalArchive = restorePoints.resolve(backupId + ".zip"),
        metadataFile = metadataDirectory.resolve(backupId + ".json");
    Files.deleteIfExists(partial);
    emit(jobId, backupId, "PREPARING", 0, 0, 0);

    Scan scan = scan(settings);
    long usable = Files.getFileStore(base).getUsableSpace();
    long required = Math.min(Long.MAX_VALUE / 2, scan.bytes + Math.min(scan.bytes, 2L * 1024 * 1024 * 1024));
    if (usable < required) throw new IOException("BACKUP_DISK_SPACE_INSUFFICIENT");
    if (scan.bytes > settings.maximumBytes()) throw new IOException("BACKUP_SIZE_LIMIT");

    long[] bytes = {0};
    int[] entries = {0};
    try {
      emit(jobId, backupId, "ARCHIVING", 0, scan.bytes, 0);
      try (OutputStream stream =
              Files.newOutputStream(partial, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
          ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(stream, 128 * 1024))) {
        zip.setLevel(Deflater.BEST_SPEED);
        Files.walkFileTree(
            root,
            EnumSet.noneOf(FileVisitOption.class),
            96,
            new SimpleFileVisitor<>() {
              @Override
              public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                  throws IOException {
                checkStopped();
                Path relative = root.relativize(dir);
                if (!relative.toString().isEmpty() && excluded(relative, settings))
                  return FileVisitResult.SKIP_SUBTREE;
                if (Files.isSymbolicLink(dir)) throw new IOException("BACKUP_SYMLINK_REJECTED");
                return FileVisitResult.CONTINUE;
              }

              @Override
              public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                  throws IOException {
                checkStopped();
                Path relative = root.relativize(file);
                if (excluded(relative, settings)) return FileVisitResult.CONTINUE;
                if (!attrs.isRegularFile() || Files.isSymbolicLink(file))
                  throw new IOException("BACKUP_SYMLINK_REJECTED");
                if (++entries[0] > 500_000) throw new IOException("BACKUP_ENTRY_LIMIT");
                String name = relative.toString().replace(File.separatorChar, '/');
                validateArchiveName(name);
                zip.putNextEntry(new ZipEntry(name));
                try (InputStream input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                  byte[] buffer = new byte[128 * 1024];
                  int count;
                  while ((count = input.read(buffer)) >= 0) {
                    checkStopped();
                    bytes[0] += count;
                    if (bytes[0] > settings.maximumBytes()) throw new IOException("BACKUP_SIZE_LIMIT");
                    zip.write(buffer, 0, count);
                    emit(jobId, backupId, "ARCHIVING", bytes[0], scan.bytes, entries[0]);
                  }
                }
                zip.closeEntry();
                return FileVisitResult.CONTINUE;
              }
            });
      }
      try (FileChannel channel = FileChannel.open(partial, StandardOpenOption.WRITE)) {
        channel.force(true);
      }
      Files.move(partial, finalArchive, StandardCopyOption.ATOMIC_MOVE);
      long archiveBytes = Files.size(finalArchive);
      if (archiveBytes > settings.maximumBytes()) throw new IOException("BACKUP_SIZE_LIMIT");
      emit(jobId, backupId, "HASHING", archiveBytes, scan.bytes, entries[0]);
      String hash = BackupManager.fileHash(finalArchive);
      LocalBackupVerifier.Result verified =
          LocalBackupVerifier.verify(finalArchive, hash, entries[0], settings.maximumBytes());
      if (verified.expandedBytes() != bytes[0])
        throw new IOException("LOCAL_BACKUP_SIZE_MISMATCH");
      Metadata local =
          new Metadata(
              backupId,
              jobId,
              "FULL_RESTORE_POINT",
              Instant.now().toString(),
              startedAt,
              Instant.now().toString(),
              (System.nanoTime() - startedNanos) / 1_000_000L,
              config.serverName(),
              "Minecraft 26.2",
              "Paper 26.2",
              implementationVersion(),
              archiveBytes,
              bytes[0],
              entries[0],
              verified.sha256(),
              true,
              false,
              provider.configured() ? "RCLONE" : "LOCAL",
              "",
              "VERIFIED_LOCAL",
              initiatedBy,
              automatic,
              emergency,
              false,
              "SUCCESS_LOCAL",
              "");
      AtomicFiles.writeUtf8(metadataFile, GSON.toJson(local));

      Metadata result = local;
      if (uploadOffsite && provider.configured() && !emergency) {
        emitRemoteProgress(jobId, backupId, 0, archiveBytes, entries[0], "UPLOADING");
        try {
          String canonical = canonicalFor(settings, backupId);
          Metadata remoteCandidate = withRemote(local, true, provider.status().get("remote") + "/" + canonical, "VERIFYING_REMOTE", "");
          Path transferMetadata = staging.resolve(backupId + ".upload.json");
          AtomicFiles.writeUtf8(transferMetadata, GSON.toJson(remoteCandidate));
          RcloneBackupProvider.Promotion promoted =
              provider.uploadAndPromote(
                  finalArchive,
                  transferMetadata,
                  jobId,
                  canonical,
                  settings.uploadTimeoutSeconds());
          emitRemoteProgress(jobId, backupId, archiveBytes, archiveBytes, entries[0], "VERIFIED_REMOTE");
          Files.deleteIfExists(transferMetadata);
          result = withRemote(local, promoted.verified(), promoted.remotePath(), "VERIFIED", "");
          AtomicFiles.writeUtf8(metadataFile, GSON.toJson(result));
        } catch (Exception remoteFailure) {
          result = withRemote(local, false, "", "LOCAL_ONLY", classify(remoteFailure));
          AtomicFiles.writeUtf8(metadataFile, GSON.toJson(result));
        }
      }
      retention(settings);
      emit(jobId, backupId, "COMPLETE", archiveBytes, scan.bytes, entries[0]);
      return result;
    } catch (Exception failure) {
      Files.deleteIfExists(partial);
      emit(jobId, backupId, "FAILED", bytes[0], scan.bytes, entries[0]);
      throw failure;
    }
  }

  public Metadata retryUpload(String backupId, String jobId, MaintenanceSettings.FullRestorePoint settings)
      throws Exception {
    if (!operationLock.tryLock()) throw new SecurityException("BUSY");
    try {
      Metadata local = verify(backupId);
      if (!provider.configured()) throw new IllegalStateException("RCLONE_UNAVAILABLE");
      Path localArchive = archive(backupId);
      long archiveBytes = Files.size(localArchive);
      emitRemoteProgress(jobId, backupId, 0, archiveBytes, local.entryCount(), "UPLOADING");
      Path transferMetadata = staging.resolve(backupId + ".retry.json");
      String canonical = canonicalFor(settings, backupId);
      Metadata candidate = withRemote(local, true, provider.status().get("remote") + "/" + canonical, "VERIFYING_REMOTE", "");
      AtomicFiles.writeUtf8(transferMetadata, GSON.toJson(candidate));
      RcloneBackupProvider.Promotion promoted =
          provider.uploadAndPromote(localArchive, transferMetadata, jobId, canonical, settings.uploadTimeoutSeconds());
      emitRemoteProgress(jobId, backupId, archiveBytes, archiveBytes, local.entryCount(), "VERIFIED_REMOTE");
      Files.deleteIfExists(transferMetadata);
      Metadata result = withRemote(local, promoted.verified(), promoted.remotePath(), "VERIFIED", "");
      AtomicFiles.writeUtf8(metadataDirectory.resolve(backupId + ".json"), GSON.toJson(result));
      return result;
    } finally {
      operationLock.unlock();
    }
  }

  public Metadata verify(String backupId) throws IOException {
    Metadata value = metadata(backupId);
    LocalBackupVerifier.Result verified =
        LocalBackupVerifier.verify(
            archive(backupId), value.sha256(), value.entryCount(), Math.max(1L, value.sourceBytes()));
    if (verified.expandedBytes() != value.sourceBytes())
      throw new IOException("LOCAL_BACKUP_SIZE_MISMATCH");
    Metadata result = withLocalState(value, true, "VERIFIED_LOCAL");
    if (!result.equals(value))
      AtomicFiles.writeUtf8(metadataDirectory.resolve(backupId + ".json"), GSON.toJson(result));
    return result;
  }

  public synchronized RestoreGrant prepareRestore(String backupId, String deviceId) throws Exception {
    ensureLocalArchive(backupId);
    verify(backupId);
    restoreGrants.entrySet().removeIf(e -> e.getValue().expiresAt < System.currentTimeMillis());
    if (restoreGrants.size() >= 8) throw new SecurityException("BUSY");
    String token = UUID.randomUUID().toString();
    RestoreGrant grant =
        new RestoreGrant(token, backupId, deviceId, System.currentTimeMillis() + 60_000L);
    restoreGrants.put(token, grant);
    return grant;
  }

  public Map<String, Object> restore(
      String backupId,
      String token,
      String serverName,
      String deviceId,
      String initiatedBy,
      MaintenanceSettings.FullRestorePoint settings,
      boolean startAfter)
      throws Exception {
    RestoreGrant grant;
    synchronized (this) {
      grant = restoreGrants.remove(token);
    }
    if (grant == null
        || !grant.backupId.equals(backupId)
        || !grant.deviceId.equals(deviceId)
        || grant.expiresAt < System.currentTimeMillis()
        || !config.serverName().equals(serverName))
      throw new SecurityException("RESTORE_CONFIRMATION_REQUIRED");
    if (!operationLock.tryLock()) throw new SecurityException("BUSY");
    String restoreId = UUID.randomUUID().toString();
    Path extract = staging.resolve("restore-" + restoreId),
        rollback = staging.resolve("rollback-" + restoreId),
        journal = metadataDirectory.resolve("restore-journal.json");
    boolean stoppedByRestore = false;
    try {
      if (Files.exists(journal)) throw new IllegalStateException("RESTORE_RECOVERY_REQUIRED");
      if (!service.stopped() || paperConnected.getAsBoolean()) {
        service.action("stop");
        stoppedByRestore = true;
        waitForStopped(180);
      }
      if (!service.stopped() || paperConnected.getAsBoolean())
        throw new IOException("SERVER_STOP_TIMEOUT");
      ensureLocalArchive(backupId);
      Metadata selected = verify(backupId);
      String emergencyJob = UUID.randomUUID().toString();
      Metadata emergency =
          createLocked(emergencyJob, initiatedBy + " emergency pre-restore", false, true, settings, false);
      Files.createDirectory(extract);
      Files.createDirectory(rollback);
      Set<String> targets = extractArchive(archive(backupId), extract, settings.maximumBytes());
      if (targets.isEmpty()) throw new IOException("RESTORE_FAILED");
      Set<String> existed = new TreeSet<>();
      for (String target : targets)
        if (Files.exists(root.resolve(target), LinkOption.NOFOLLOW_LINKS)) existed.add(target);
      AtomicFiles.writeUtf8(
          journal,
          GSON.toJson(
              Map.of(
                  "restoreId", restoreId,
                  "backupId", backupId,
                  "emergencyBackupId", emergency.backupId,
                  "targets", targets,
                  "existed", existed,
                  "createdAt", Instant.now().toString())));
      try {
        for (String target : targets) {
          checkStopped();
          Path destination = root.resolve(target).normalize(),
              incoming = extract.resolve(target).normalize(),
              previous = rollback.resolve(target).normalize();
          if (!destination.startsWith(root) || !incoming.startsWith(extract) || !previous.startsWith(rollback))
            throw new IOException("RESTORE_FAILED");
          if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectories(previous.getParent());
            Files.move(destination, previous, StandardCopyOption.ATOMIC_MOVE);
          }
          Files.createDirectories(destination.getParent());
          Files.move(incoming, destination, StandardCopyOption.ATOMIC_MOVE);
        }
      } catch (Exception failure) {
        rollback(extract, rollback, targets, existed);
        throw failure;
      }
      Files.deleteIfExists(journal);
      deleteTree(extract);
      deleteTree(rollback);
      String state = "stopped";
      if (startAfter) {
        service.action("start");
        waitForPaper(180);
        state = "running";
      }
      return Map.of(
          "backupId", selected.backupId,
          "emergencyBackupId", emergency.backupId,
          "state", state,
          "integrity", "VERIFIED");
    } catch (Exception failure) {
      if (stoppedByRestore && !Files.exists(journal) && service.stopped()) {
        try {
          service.action("start");
          waitForPaper(180);
        } catch (Exception ignored) {
          // Preserve the restore failure; startup failure is visible from service status/audit.
        }
      }
      throw failure;
    } finally {
      if (!Files.exists(journal)) {
        deleteTree(extract);
        deleteTree(rollback);
      }
      operationLock.unlock();
    }
  }

  public void recoverRestore() throws Exception {
    Path journal = metadataDirectory.resolve("restore-journal.json");
    if (!Files.exists(journal)) return;
    if (!service.stopped() || paperConnected.getAsBoolean())
      throw new IllegalStateException("Stop Paper before restore recovery");
    JsonObject value = JsonParser.parseString(Files.readString(journal)).getAsJsonObject();
    String restoreId = value.get("restoreId").getAsString();
    UUID.fromString(restoreId);
    Set<String> targets = new TreeSet<>(), existed = new TreeSet<>();
    for (JsonElement item : value.getAsJsonArray("targets")) targets.add(item.getAsString());
    for (JsonElement item : value.getAsJsonArray("existed")) existed.add(item.getAsString());
    rollback(staging.resolve("restore-" + restoreId), staging.resolve("rollback-" + restoreId), targets, existed);
    Files.delete(journal);
  }

  public boolean recoveryRequired() {
    return Files.exists(metadataDirectory.resolve("restore-journal.json"));
  }

  public void delete(String backupId) throws Exception {
    if (!operationLock.tryLock()) throw new SecurityException("BUSY");
    try {
      Metadata value = metadata(backupId);
      if (value.emergency) throw new IOException("Emergency restore points require local review before deletion");
      Files.deleteIfExists(archive(backupId));
      Files.deleteIfExists(metadataDirectory.resolve(backupId + ".json"));
    } finally {
      operationLock.unlock();
    }
  }

  public Map<String, Object> providerStatus() {
    return provider.status();
  }

  public Map<String, Object> testProvider(int timeoutSeconds) throws Exception {
    return provider.test(timeoutSeconds);
  }

  private void ensureLocalArchive(String backupId) throws Exception {
    UUID.fromString(backupId);
    Path target = restorePoints.resolve(backupId + ".zip");
    if (Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(target)) return;
    Metadata value = metadata(backupId);
    if (!value.offsite || !provider.configured() || value.remotePath == null || value.remotePath.isBlank())
      throw new IOException("Full restore-point archive unavailable");
    MaintenanceSettings maintenance =
        MaintenanceSettings.load(Path.of(config.dataDirectory()).resolve("maintenance-settings.json"));
    String filename = value.remotePath.substring(value.remotePath.lastIndexOf('/') + 1);
    if (!filename.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}\\.zip"))
      throw new IOException("REMOTE_UNAVAILABLE");
    if (maintenance.fullRestorePoint().retentionMode().equals("SINGLE_CURRENT")
        && !filename.equals(maintenance.fullRestorePoint().canonicalFilename()))
      throw new IOException("REMOTE_UNAVAILABLE");
    Path downloaded =
        provider.fetchCanonical(
            staging, filename, maintenance.fullRestorePoint().uploadTimeoutSeconds());
    try {
      if (Files.size(downloaded) != value.archiveBytes)
        throw new IOException("REMOTE_VERIFY_FAILED");
      LocalBackupVerifier.Result verified =
          LocalBackupVerifier.verify(
              downloaded, value.sha256(), value.entryCount(), Math.max(1L, value.sourceBytes()));
      if (verified.expandedBytes() != value.sourceBytes())
        throw new IOException("LOCAL_BACKUP_SIZE_MISMATCH");
      try (FileChannel channel = FileChannel.open(downloaded, StandardOpenOption.WRITE)) {
        channel.force(true);
      }
      Files.move(downloaded, target, StandardCopyOption.ATOMIC_MOVE);
      Metadata local = withLocalState(value, true, "REMOTE_FETCH_VERIFIED");
      AtomicFiles.writeUtf8(metadataDirectory.resolve(backupId + ".json"), GSON.toJson(local));
    } finally {
      Files.deleteIfExists(downloaded);
    }
  }

  private Scan scan(MaintenanceSettings.FullRestorePoint settings) throws IOException {
    long[] bytes = {0};
    int[] entries = {0};
    Files.walkFileTree(
        root,
        EnumSet.noneOf(FileVisitOption.class),
        96,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
            Path relative = root.relativize(dir);
            if (!relative.toString().isEmpty() && excluded(relative, settings))
              return FileVisitResult.SKIP_SUBTREE;
            if (Files.isSymbolicLink(dir)) throw new IOException("BACKUP_SYMLINK_REJECTED");
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
            Path relative = root.relativize(file);
            if (excluded(relative, settings)) return FileVisitResult.CONTINUE;
            if (!attrs.isRegularFile() || Files.isSymbolicLink(file))
              throw new IOException("BACKUP_SYMLINK_REJECTED");
            entries[0]++;
            if (entries[0] > 500_000) throw new IOException("BACKUP_ENTRY_LIMIT");
            bytes[0] = Math.addExact(bytes[0], attrs.size());
            if (bytes[0] > settings.maximumBytes()) throw new IOException("BACKUP_SIZE_LIMIT");
            return FileVisitResult.CONTINUE;
          }
        });
    return new Scan(bytes[0], entries[0]);
  }

  private boolean excluded(Path relative, MaintenanceSettings.FullRestorePoint settings) {
    String name = relative.toString().replace(File.separatorChar, '/');
    if (name.isEmpty()) return false;
    String lower = name.toLowerCase(Locale.ROOT);
    if (lower.startsWith(".plexonpanel-restore-")
        || lower.startsWith(".plexonpanel-rollback-")
        || lower.endsWith(".partial")) return true;
    for (String configured : settings.excludes()) {
      String normalized = configured.replace('\\', '/');
      if (name.equals(normalized) || name.startsWith(normalized + "/")) return true;
    }
    return false;
  }

  private Set<String> extractArchive(Path archive, Path destination, long maximumBytes) throws IOException {
    Set<String> names = new HashSet<>(), top = new TreeSet<>();
    long bytes = 0;
    int count = 0;
    try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(Files.newInputStream(archive)))) {
      ZipEntry entry;
      while ((entry = zip.getNextEntry()) != null) {
        String name = entry.getName();
        validateArchiveName(name);
        if (!names.add(name) || ++count > 500_000) throw new IOException("Unsafe duplicate archive entry");
        Path relative = Path.of(name);
        if (relative.isAbsolute() || !relative.normalize().equals(relative))
          throw new IOException("ZIP-slip rejected");
        Path output = destination.resolve(relative).normalize();
        if (!output.startsWith(destination)) throw new IOException("ZIP-slip rejected");
        top.add(relative.getName(0).toString());
        if (entry.isDirectory()) {
          Files.createDirectories(output);
          continue;
        }
        Files.createDirectories(output.getParent());
        try (OutputStream stream = Files.newOutputStream(output, StandardOpenOption.CREATE_NEW)) {
          byte[] buffer = new byte[128 * 1024];
          int n;
          while ((n = zip.read(buffer)) >= 0) {
            bytes += n;
            if (bytes > maximumBytes) throw new IOException("Expanded archive exceeds limit");
            stream.write(buffer, 0, n);
          }
        }
      }
    }
    return top;
  }

  private void rollback(Path extract, Path rollback, Set<String> targets, Set<String> existed)
      throws IOException {
    for (String target : targets) {
      Path destination = root.resolve(target).normalize(), previous = rollback.resolve(target).normalize();
      if (Files.exists(previous, LinkOption.NOFOLLOW_LINKS)) {
        deleteTree(destination);
        Files.createDirectories(destination.getParent());
        Files.move(previous, destination, StandardCopyOption.ATOMIC_MOVE);
      } else if (!existed.contains(target)) {
        deleteTree(destination);
      }
    }
    deleteTree(extract);
    deleteTree(rollback);
  }

  private void retention(MaintenanceSettings.FullRestorePoint settings) throws IOException {
    int keep = settings.retentionMode().equals("SINGLE_CURRENT") ? 1 : settings.retentionCount();
    int seen = 0;
    for (Metadata value : list()) {
      if (value.emergency) continue;
      if (++seen <= keep) continue;
      Files.deleteIfExists(restorePoints.resolve(value.backupId + ".zip"));
      Files.deleteIfExists(metadataDirectory.resolve(value.backupId + ".json"));
    }
  }

  private String canonicalFor(MaintenanceSettings.FullRestorePoint settings, String backupId) {
    if (settings.retentionMode().equals("SINGLE_CURRENT")) return settings.canonicalFilename();
    String baseName = settings.canonicalFilename().substring(0, settings.canonicalFilename().length() - 4);
    return baseName + "-" + backupId + ".zip";
  }

  private Metadata withRemote(
      Metadata value, boolean offsite, String remotePath, String verification, String errorCode) {
    return new Metadata(
        value.backupId, value.jobId, value.type, value.timestamp, value.startedAt,
        Instant.now().toString(), value.durationMillis, value.serverName, value.serverVersion,
        value.paperVersion, value.plexonPanelVersion, value.archiveBytes, value.sourceBytes,
        value.entryCount, value.sha256, value.local, offsite, provider.configured() ? "RCLONE" : "LOCAL",
        remotePath == null ? "" : remotePath, verification, value.initiatedBy, value.automatic,
        value.emergency, value.restartPerformed, offsite ? "SUCCESS" : "SUCCESS_LOCAL",
        errorCode == null ? "" : errorCode);
  }

  private Metadata withLocalState(Metadata value, boolean local, String verification) {
    return new Metadata(
        value.backupId, value.jobId, value.type, value.timestamp, value.startedAt,
        value.completedAt, value.durationMillis, value.serverName, value.serverVersion,
        value.paperVersion, value.plexonPanelVersion, value.archiveBytes, value.sourceBytes,
        value.entryCount, value.sha256, local, value.offsite, value.remoteProvider,
        value.remotePath, verification, value.initiatedBy, value.automatic, value.emergency,
        value.restartPerformed, value.result, value.errorCode);
  }

  private void waitForStopped(int seconds) throws Exception {
    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
    while (System.nanoTime() < until) {
      if (service.stopped()) return;
      Thread.sleep(250);
    }
    throw new IOException("SERVER_STOP_TIMEOUT");
  }

  private void waitForPaper(int seconds) throws Exception {
    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
    while (System.nanoTime() < until) {
      Map<String, Object> status = service.status();
      if ("active".equals(status.get("state")) && paperConnected.getAsBoolean()) return;
      Thread.sleep(250);
    }
    throw new IOException("PAPER_RECONNECT_TIMEOUT");
  }

  private void checkStopped() throws IOException {
    try {
      if (!service.stopped()) throw new IOException("SERVER_STATE_CHANGED_DURING_BACKUP");
      if (Thread.currentThread().isInterrupted()) throw new IOException("BACKUP_CANCELLED");
    } catch (IOException e) {
      throw e;
    } catch (Exception e) {
      throw new IOException("Unable to verify stopped server state", e);
    }
  }

  private void emit(String jobId, String backupId, String phase, long bytes, long total, int entries) {
    long now = System.currentTimeMillis();
    if (now - progressAt < 750 && !Set.of("COMPLETE", "FAILED").contains(phase)) return;
    progressAt = now;
    Map<String, Object> event = new LinkedHashMap<>();
    event.put("jobId", jobId);
    event.put("backupId", backupId);
    event.put("type", "FULL_RESTORE_POINT");
    event.put("phase", phase);
    event.put("bytes", bytes);
    event.put("totalBytes", total);
    event.put("entryCount", entries);
    event.put("capturedAt", Instant.now().toString());
    if (total > 0) event.put("progress", Math.min(1.0d, (double) bytes / (double) total));
    progress.accept(event);
  }

  private void emitRemoteProgress(
      String jobId,
      String backupId,
      long bytesUploaded,
      long totalBytes,
      int entries,
      String providerState) {
    Map<String, Object> event = new LinkedHashMap<>();
    event.put("jobId", jobId);
    event.put("backupId", backupId);
    event.put("type", "FULL_RESTORE_POINT");
    event.put("phase", "UPLOADING_REMOTE");
    event.put("bytesUploaded", bytesUploaded);
    event.put("totalBytes", totalBytes);
    event.put("entryCount", entries);
    event.put("provider", "RCLONE");
    event.put("providerState", providerState);
    event.put("capturedAt", Instant.now().toString());
    if (totalBytes > 0)
      event.put("progress", Math.min(1.0d, (double) bytesUploaded / (double) totalBytes));
    progress.accept(event);
  }

  private static void validateArchiveName(String name) throws IOException {
    if (name == null
        || name.isBlank()
        || name.length() > 4096
        || name.startsWith("/")
        || name.indexOf('\\') >= 0
        || name.contains("//")
        || name.chars().anyMatch(Character::isISOControl))
      throw new IOException("Unsafe archive entry");
    Path relative = Path.of(name);
    if (relative.isAbsolute() || !relative.normalize().equals(relative) || relative.getNameCount() < 1)
      throw new IOException("Unsafe archive entry");
    for (Path part : relative)
      if (part.toString().equals(".") || part.toString().equals("..") || part.toString().contains(":"))
        throw new IOException("Archive traversal rejected");
  }

  private static String classify(Exception error) {
    String message = error.getMessage();
    if (message != null && message.matches("[A-Z0-9_]{3,64}")) return message;
    return "REMOTE_UPLOAD_FAILED";
  }

  private static String implementationVersion() {
    String value = FullRestorePointManager.class.getPackage().getImplementationVersion();
    return value == null || value.isBlank() ? "development" : value;
  }

  private static void deleteTree(Path path) throws IOException {
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return;
    Files.walkFileTree(
        path,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
            Files.delete(file);
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult postVisitDirectory(Path dir, IOException error) throws IOException {
            if (error != null) throw error;
            Files.delete(dir);
            return FileVisitResult.CONTINUE;
          }
        });
  }

  private record Scan(long bytes, int entries) {}
}
