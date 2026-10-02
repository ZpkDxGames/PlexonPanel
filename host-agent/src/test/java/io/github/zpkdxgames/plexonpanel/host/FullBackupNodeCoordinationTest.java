package io.github.zpkdxgames.plexonpanel.host;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FullBackupNodeCoordinationTest {
  @TempDir Path temporary;

  @Test
  void fullArchiveWaitsForNodeLeaseBeforeInspectingServiceOrWritingArchive() throws Exception {
    Path lock = Files.createFile(temporary.resolve("node.lock"));
    var blocker = new NodeBackupCoordinator(lock);
    var shared = new NodeBackupCoordinator(lock);
    Path root = Files.createDirectory(temporary.resolve("server"));
    Files.createDirectory(root.resolve("world"));
    Files.writeString(root.resolve("world/level.dat"), "synthetic world fixture");
    Path data = Files.createDirectory(temporary.resolve("data"));
    Path output = temporary.resolve("backups");
    var config = new HostConfig(UUID.randomUUID().toString(), "Instance A", "", "",
        root.toString(), data.toString(), data.resolve("access.json").toString(),
        "minecraft@instance-a.service", Map.of(),
        new HostConfig.BackupConfig(true, output.toString(), List.of("world"), 2, 0,
            1048576, false, "/usr/bin/rclone", "", ""), HostConfig.ConsoleConfig.defaults());
    AtomicInteger serviceChecks = new AtomicInteger();
    var service = new SystemdService(config.serviceName(), (arguments, timeout) -> {
      serviceChecks.incrementAndGet();
      assertEquals(config.serviceName(), arguments.getLast());
      assertEquals("show", arguments.get(1));
      return "ActiveState=inactive\nSubState=dead\nMainPID=0\n";
    });
    CountDownLatch queued = new CountDownLatch(1);
    var manager = new FullRestorePointManager(config, service, () -> false, new ReentrantLock(),
        event -> { if ("WAITING_FOR_NODE".equals(event.get("phase"))) queued.countDown(); }, shared);
    var worker = Executors.newSingleThreadExecutor();
    try {
      Future<FullRestorePointManager.Metadata> result;
      try (var lease = blocker.acquire(Duration.ZERO, null)) {
        result = worker.submit(() -> manager.create(UUID.randomUUID().toString(), "test", false,
            false, MaintenanceSettings.migratedDefaults().fullRestorePoint(), false));
        assertTrue(queued.await(3, TimeUnit.SECONDS));
        assertEquals(0, serviceChecks.get());
        assertTrue((Boolean) manager.nodeBackupStatus().get("waiting"));
        try (var files = Files.list(output.resolve("restore-points"))) { assertEquals(0, files.count()); }
      }
      var backup = result.get(10, TimeUnit.SECONDS);
      assertTrue(backup.local());
      assertEquals("VERIFIED_LOCAL", backup.verification());
      assertTrue(serviceChecks.get() > 0);
      assertFalse(shared.active());
      assertFalse(shared.queued());
      assertEquals(backup.sha256(), manager.verify(backup.backupId()).sha256());
    } finally { worker.shutdownNow(); }
  }

  @Test
  void aLeaseHeldByAnotherThreadCannotAuthorizeDirectArchiveCreation() throws Exception {
    Path lock = Files.createFile(temporary.resolve("node.lock"));
    var shared = new NodeBackupCoordinator(lock);
    Path root = Files.createDirectory(temporary.resolve("server"));
    var config = new HostConfig(UUID.randomUUID().toString(), "A", "", "", root.toString(),
        temporary.resolve("data").toString(), temporary.resolve("access.json").toString(),
        "minecraft@instance-a.service", Map.of(),
        new HostConfig.BackupConfig(true, temporary.resolve("backups").toString(), List.of("world"),
            1, 0, 1048576, false, "/usr/bin/rclone", "", ""), HostConfig.ConsoleConfig.defaults());
    var manager = new FullRestorePointManager(config, new SystemdService(config.serviceName()),
        () -> false, new ReentrantLock(), event -> {}, shared);
    var worker = Executors.newSingleThreadExecutor();
    try (var lease = shared.acquire(Duration.ZERO, null)) {
      var rejected = worker.submit(() -> assertThrows(IllegalStateException.class,
          () -> manager.createLocked(UUID.randomUUID().toString(), "test", false, false,
              MaintenanceSettings.migratedDefaults().fullRestorePoint(), false)));
      assertEquals("NODE_BACKUP_LEASE_REQUIRED", rejected.get(3, TimeUnit.SECONDS).getMessage());
    } finally { worker.shutdownNow(); }
  }
}
