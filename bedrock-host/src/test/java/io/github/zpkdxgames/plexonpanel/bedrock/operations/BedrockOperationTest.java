package io.github.zpkdxgames.plexonpanel.bedrock.operations;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.ZipFile;

import static io.github.zpkdxgames.plexonpanel.bedrock.operations.BedrockContainerControl.Phase.EXITED;
import static io.github.zpkdxgames.plexonpanel.bedrock.operations.BedrockContainerControl.Phase.PAUSED;
import static io.github.zpkdxgames.plexonpanel.bedrock.operations.BedrockContainerControl.Phase.RUNNING;
import static io.github.zpkdxgames.plexonpanel.bedrock.operations.BedrockContainerControl.Readiness.UNKNOWN;
import static io.github.zpkdxgames.plexonpanel.bedrock.operations.BedrockContainerControl.Readiness.VERIFIED;
import static org.junit.jupiter.api.Assertions.*;

class BedrockOperationTest {
    private static final Duration GRACE = Duration.ofSeconds(60);
    private static final Instant FIRST_LAUNCH = Instant.parse("2026-01-01T00:00:00Z");

    @TempDir Path root;

    @Test
    void lifecycleRequiresEnablementAndReplaysOnlyMatchingRequest() {
        Engine engine = new Engine(root, RUNNING);
        BedrockOperationLock lock = new BedrockOperationLock();
        UUID request = UUID.randomUUID();
        assertEquals(BedrockLifecycle.Outcome.DISABLED,
                new BedrockLifecycle(false, engine, lock, GRACE).execute(request, BedrockLifecycle.Action.STOP));
        assertEquals(0, engine.stopCalls);

        AtomicLong nanos = new AtomicLong();
        BedrockLifecycle lifecycle = new BedrockLifecycle(true, engine, lock, GRACE, nanos::get);
        assertEquals(BedrockLifecycle.Outcome.STOPPED, lifecycle.execute(request, BedrockLifecycle.Action.STOP));
        assertEquals(BedrockLifecycle.Outcome.STOPPED, lifecycle.execute(request, BedrockLifecycle.Action.STOP));
        assertEquals(BedrockLifecycle.Outcome.REQUEST_MISMATCH,
                lifecycle.execute(request, BedrockLifecycle.Action.START));
        assertEquals(BedrockLifecycle.Outcome.COOLDOWN,
                lifecycle.execute(UUID.randomUUID(), BedrockLifecycle.Action.START));
        nanos.addAndGet(Duration.ofSeconds(30).toNanos());
        assertEquals(BedrockLifecycle.Outcome.RUNNING_UNVERIFIED,
                lifecycle.execute(UUID.randomUUID(), BedrockLifecycle.Action.START));
        assertEquals(1, engine.stopCalls);
        assertEquals(1, engine.startCalls);
    }

    @Test
    void lifecycleRejectsPauseAndDoesNotRestartAfterDirtyOrReplacedStop() {
        Engine paused = new Engine(root, PAUSED);
        assertEquals(BedrockLifecycle.Outcome.INVALID_STATE,
                lifecycle(paused).execute(UUID.randomUUID(), BedrockLifecycle.Action.START));

        Engine killed = new Engine(root, RUNNING);
        killed.stopExitCode = 137;
        assertEquals(BedrockLifecycle.Outcome.OUTCOME_UNKNOWN,
                lifecycle(killed).execute(UUID.randomUUID(), BedrockLifecycle.Action.RESTART));
        assertEquals(0, killed.startCalls);

        Engine replaced = new Engine(root, RUNNING);
        replaced.replaceOnStop = true;
        assertEquals(BedrockLifecycle.Outcome.OUTCOME_UNKNOWN,
                lifecycle(replaced).execute(UUID.randomUUID(), BedrockLifecycle.Action.RESTART));
        assertEquals(0, replaced.startCalls);
    }

    @Test
    void restartRequiresNewGenerationAndSeparateReadinessEvidence() {
        Engine engine = new Engine(root, RUNNING);
        engine.probeReady = true;
        assertEquals(BedrockLifecycle.Outcome.READY,
                lifecycle(engine).execute(UUID.randomUUID(), BedrockLifecycle.Action.RESTART));
        assertTrue(engine.startedAt.isAfter(FIRST_LAUNCH));

        Engine ambiguous = new Engine(root, RUNNING);
        ambiguous.throwAfterStop = true;
        BedrockLifecycle lifecycle = lifecycle(ambiguous);
        UUID request = UUID.randomUUID();
        assertEquals(BedrockLifecycle.Outcome.OUTCOME_UNKNOWN, lifecycle.execute(request, BedrockLifecycle.Action.RESTART));
        assertEquals(BedrockLifecycle.Outcome.OUTCOME_UNKNOWN, lifecycle.execute(request, BedrockLifecycle.Action.RESTART));
        assertEquals(1, ambiguous.stopCalls);
        assertEquals(0, ambiguous.startCalls);
    }

    @Test
    void consoleCompilesOneBoundedLineAndGatesPlayerAdministration() {
        Engine engine = new Engine(root, RUNNING);
        BedrockOperationLock lock = new BedrockOperationLock();
        List<String> lines = new ArrayList<>();
        BedrockConsole.Attach passed = (expected, line) -> lines.add(new String(line, StandardCharsets.UTF_8));
        BedrockConsole consoleOnly = new BedrockConsole(true, false, true, engine, lock, passed);
        assertEquals(BedrockConsole.Outcome.DISABLED, consoleOnly.execute(BedrockConsole.Action.OP, "Player"));
        assertEquals(BedrockConsole.Outcome.DISPATCHED, consoleOnly.execute(BedrockConsole.Action.LIST, null));

        BedrockConsole console = new BedrockConsole(true, true, true, engine, lock, passed);
        assertEquals(BedrockConsole.Outcome.DISPATCHED,
                console.execute(BedrockConsole.Action.ALLOWLIST_ADD, "A Player"));
        for (String unsafe : List.of("user\" @a", "@a", "Foo\nstop", " 日本", " A", "A".repeat(16))) {
            assertEquals(BedrockConsole.Outcome.INVALID_ARGUMENT, console.execute(BedrockConsole.Action.OP, unsafe));
        }
        for (String unsafe : List.of("hello\nstop", "hello\u001b[0m", "hi\u2028op @a", "a".repeat(161))) {
            assertEquals(BedrockConsole.Outcome.INVALID_ARGUMENT, console.execute(BedrockConsole.Action.SAY, unsafe));
        }
        assertEquals(List.of("list\n", "allowlist add \"A Player\"\n"), lines);
    }

    @Test
    void consoleRequiresExclusiveAttachAndDoesNotClaimCommandAcknowledgement() {
        Engine engine = new Engine(root, RUNNING);
        BedrockOperationLock lock = new BedrockOperationLock();
        BedrockConsole.Attach unavailable = (expected, line) -> {
            throw new IOException("fixture attach refused input");
        };
        BedrockConsole disabledAttach = new BedrockConsole(true, true, false, engine, lock, unavailable);
        assertEquals(BedrockConsole.Outcome.ATTACH_NOT_EXCLUSIVE,
                disabledAttach.execute(BedrockConsole.Action.LIST, null));

        BedrockConsole console = new BedrockConsole(true, true, true, engine, lock, unavailable);
        assertEquals(BedrockConsole.Outcome.OUTCOME_UNKNOWN, console.execute(BedrockConsole.Action.LIST, null));
        assertTrue(lock.acquire());
        try {
            assertEquals(BedrockConsole.Outcome.BUSY, console.execute(BedrockConsole.Action.LIST, null));
        } finally {
            lock.release();
        }
    }

    @Test
    void rosterRequiresCompleteFreshEvidenceForExactLaunch() {
        Engine engine = new Engine(root, RUNNING);
        BedrockRoster roster = new BedrockRoster();
        Instant observed = FIRST_LAUNCH.plusSeconds(6);
        BedrockRoster.Player nameOnly = new BedrockRoster.Player("Test Player", null);
        BedrockRoster.Player identified = new BedrockRoster.Player("Test2", "18446744073709551615");
        assertThrows(IllegalArgumentException.class, () -> new BedrockRoster.Player("Player", "18446744073709551616"));
        assertThrows(IllegalArgumentException.class, () -> new BedrockRoster.Player("Player", UUID.randomUUID().toString()));
        roster.reconcile(engine.inspect(), observed, List.of(nameOnly, identified));
        assertEquals(List.of(nameOnly, identified), roster.current(engine.inspect(), observed.plusSeconds(1)).orElseThrow());
        assertTrue(roster.current(engine.inspect(), observed.minusNanos(1)).isEmpty());
        assertTrue(roster.current(engine.inspect(), observed.plusSeconds(30)).isEmpty());
        engine.startedAt = observed.plusSeconds(1);
        assertTrue(roster.current(engine.inspect(), observed.plusSeconds(2)).isEmpty());
        roster.reconcile(engine.inspect(), observed.plusSeconds(2), List.of());
        assertEquals(List.of(), roster.current(engine.inspect(), observed.plusSeconds(3)).orElseThrow());
        roster.disconnected();
        assertTrue(roster.current(engine.inspect(), observed.plusSeconds(4)).isEmpty());
    }

    @Test
    void coldBackupVerifiesContentsAndRestartsOnlyAfterApprovedStop() throws Exception {
        Paths paths = storage();
        Engine engine = new Engine(paths.data, RUNNING);
        BedrockColdBackup backup = backup(engine, paths, 2);
        UUID id = UUID.randomUUID();
        assertEquals(BedrockColdBackup.Status.CONSENT_REQUIRED, backup.create(id, false).status());
        assertEquals(0, engine.stopCalls);
        assertEquals(new BedrockColdBackup.Result(BedrockColdBackup.Status.VERIFIED,
                BedrockColdBackup.Availability.RUNNING_UNVERIFIED), backup.create(id, true));
        assertEquals(1, engine.stopCalls);
        assertEquals(1, engine.startCalls);
        Path zip = paths.backups.resolve(id.toString()).resolve("world.zip");
        try (ZipFile archive = new ZipFile(zip.toFile())) {
            assertNotNull(archive.getEntry("worlds/My Level/db/CURRENT"));
            assertNotNull(archive.getEntry("allowlist.json"));
            assertNull(archive.getEntry("server.properties"));
            assertNull(archive.getEntry("secrets.txt"));
        }
        assertEquals(BedrockColdBackup.Status.ALREADY_VERIFIED, backup.create(id, true).status());
        assertEquals(1, engine.stopCalls);
        Files.writeString(zip, "corrupt");
        assertEquals(BedrockColdBackup.Status.FAILED, backup.create(id, true).status());
        assertEquals(1, engine.stopCalls);
    }

    @Test
    void coldBackupRejectsSymlinkBeforeStopAndRetainsBoundedCompletedBundles() throws Exception {
        Paths paths = storage();
        Engine engine = new Engine(paths.data, EXITED);
        BedrockColdBackup backup = backup(engine, paths, 1);
        Path link = paths.data.resolve("worlds/My Level/db/link");
        Files.createSymbolicLink(link, paths.data.resolve("secrets.txt"));
        assertEquals(BedrockColdBackup.Status.PREFLIGHT_FAILED, backup.create(UUID.randomUUID(), false).status());
        Files.delete(link);
        UUID older = UUID.randomUUID();
        UUID newer = UUID.randomUUID();
        assertEquals(BedrockColdBackup.Status.VERIFIED, backup.create(older, false).status());
        assertEquals(BedrockColdBackup.Status.VERIFIED, backup.create(newer, false).status());
        assertFalse(Files.exists(paths.backups.resolve(older.toString())));
        assertTrue(Files.isDirectory(paths.backups.resolve(newer.toString())));
        assertEquals(0, engine.startCalls);
        assertEquals(0, engine.stopCalls);
    }

    @Test
    void coldBackupRestartsAfterFailureAndKeepsRecoveryIntentAcrossWorkerRestart() throws Exception {
        Paths paths = storage();
        Engine engine = new Engine(paths.data, RUNNING);
        engine.stopExitCode = 137;
        BedrockColdBackup backup = backup(engine, paths, 1);
        assertEquals(new BedrockColdBackup.Result(BedrockColdBackup.Status.FAILED,
                BedrockColdBackup.Availability.RUNNING_UNVERIFIED), backup.create(UUID.randomUUID(), true));
        assertEquals(1, engine.startCalls);
        assertFalse(Files.exists(paths.backups.resolve(".recovery")));

        engine.stopExitCode = 0;
        engine.startUnavailable = true;
        assertEquals(new BedrockColdBackup.Result(BedrockColdBackup.Status.VERIFIED,
                BedrockColdBackup.Availability.RECOVERY_REQUIRED), backup.create(UUID.randomUUID(), true));
        assertTrue(Files.isRegularFile(paths.backups.resolve(".recovery")));
        engine.startUnavailable = false;
        BedrockColdBackup replacement = backup(engine, paths, 1);
        assertEquals(BedrockColdBackup.Status.RECOVERY_REQUIRED, replacement.create(UUID.randomUUID(), true).status());
        assertEquals(BedrockColdBackup.Availability.RUNNING_UNVERIFIED, replacement.recover());
        assertFalse(Files.exists(paths.backups.resolve(".recovery")));
    }

    @Test
    void coldBackupRecoveryRefusesDifferentContainerGeneration() throws Exception {
        Paths paths = storage();
        Engine engine = new Engine(paths.data, RUNNING);
        engine.replaceOnStop = true;
        BedrockColdBackup backup = backup(engine, paths, 1);
        assertEquals(new BedrockColdBackup.Result(BedrockColdBackup.Status.FAILED,
                BedrockColdBackup.Availability.RECOVERY_REQUIRED), backup.create(UUID.randomUUID(), true));
        assertEquals(BedrockColdBackup.Availability.RECOVERY_REQUIRED, backup.recover());
        assertEquals(0, engine.startCalls);
    }

    private Paths storage() throws IOException {
        Path data = Files.createDirectory(root.resolve("data"));
        Path db = Files.createDirectories(data.resolve("worlds/My Level/db"));
        Files.writeString(db.resolve("CURRENT"), "MANIFEST-000001\n");
        Files.writeString(data.resolve("allowlist.json"), "[]");
        Files.writeString(data.resolve("server.properties"), "server-name=fixture");
        Files.writeString(data.resolve("secrets.txt"), "fixture-secret");
        Path backups = Files.createDirectory(root.resolve("backups"),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        return new Paths(data, backups);
    }

    private static BedrockColdBackup backup(Engine engine, Paths paths, int retention) {
        return new BedrockColdBackup(true, engine, new BedrockOperationLock(), paths.data, paths.backups, 1024 * 1024,
                retention, GRACE);
    }

    private static BedrockLifecycle lifecycle(Engine engine) {
        return new BedrockLifecycle(true, engine, new BedrockOperationLock(), GRACE);
    }

    private record Paths(Path data, Path backups) {}

    private static final class Engine implements BedrockContainerControl {
        private String id = "a".repeat(64);
        private Instant startedAt = FIRST_LAUNCH;
        private Phase phase;
        private final Path data;
        private int stopCalls;
        private int startCalls;
        private int stopExitCode;
        private boolean replaceOnStop;
        private boolean throwAfterStop;
        private boolean startUnavailable;
        private boolean probeReady;

        Engine(Path data, Phase phase) {
            this.data = data;
            this.phase = phase;
        }

        @Override
        public Container inspect() {
            return new Container(id, startedAt, phase, stopExitCode, false,
                    phase == RUNNING && probeReady ? VERIFIED : UNKNOWN, data);
        }

        @Override
        public void start(Container expected) throws IOException {
            assertTrue(inspect().sameLaunch(expected));
            startCalls++;
            if (startUnavailable) {
                throw new IOException("fixture start refused");
            }
            startedAt = startedAt.plusSeconds(10);
            phase = RUNNING;
        }

        @Override
        public void stop(Container expected, Duration grace) throws IOException {
            assertTrue(inspect().sameLaunch(expected));
            assertEquals(GRACE, grace);
            stopCalls++;
            phase = EXITED;
            if (replaceOnStop) {
                id = "b".repeat(64);
            }
            if (throwAfterStop) {
                throw new IOException("fixture stop response interrupted");
            }
        }
    }
}
