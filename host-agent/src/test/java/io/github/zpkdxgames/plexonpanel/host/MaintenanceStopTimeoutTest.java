package io.github.zpkdxgames.plexonpanel.host;

import static org.junit.jupiter.api.Assertions.*;

import io.github.zpkdxgames.plexonpanel.audit.LocalAudit;
import java.io.IOException;
import java.lang.reflect.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MaintenanceStopTimeoutTest {
  @TempDir Path temporary;

  @Test
  void zeroDeadlineFailsClosedAsServerStopTimeout() throws Exception {
    Path root = Files.createDirectory(temporary.resolve("server"));
    Path backups = temporary.resolve("backups");
    Path data = Files.createDirectories(temporary.resolve("host-data"));
    HostConfig config = config(root, backups, data);
    SystemdService service = new SystemdService("plexoncraft-test.service");
    MinecraftCommandChannel commands = disabledCommands();
    ReentrantLock lock = new ReentrantLock();
    FullRestorePointManager fullBackups =
        new FullRestorePointManager(config, service, () -> false, lock, ignored -> {});
    LocalAudit audit = new LocalAudit(data.resolve("audit"), 30);
    MaintenanceManager maintenance =
        new MaintenanceManager(
            config,
            service,
            commands,
            lock,
            audit,
            (type, body, priority) -> true,
            fullBackups);
    try {
      Method waitStopped = MaintenanceManager.class.getDeclaredMethod("waitStopped", int.class);
      waitStopped.setAccessible(true);
      InvocationTargetException failure =
          assertThrows(InvocationTargetException.class, () -> waitStopped.invoke(maintenance, 0));
      assertInstanceOf(IOException.class, failure.getCause());
      assertEquals("SERVER_STOP_TIMEOUT", failure.getCause().getMessage());
    } finally {
      maintenance.close();
    }
  }

  @Test
  void statusPublishesRemainingTimeFromDurableCountdownState() throws Exception {
    Path root = Files.createDirectory(temporary.resolve("countdown-server"));
    Path backups = temporary.resolve("countdown-backups");
    Path data = Files.createDirectories(temporary.resolve("countdown-host-data"));
    HostConfig config = config(root, backups, data);
    SystemdService service = new SystemdService("plexoncraft-test.service");
    MinecraftCommandChannel commands = disabledCommands();
    ReentrantLock lock = new ReentrantLock();
    FullRestorePointManager fullBackups =
        new FullRestorePointManager(config, service, () -> false, lock, ignored -> {});
    LocalAudit audit = new LocalAudit(data.resolve("audit"), 30);
    MaintenanceManager maintenance =
        new MaintenanceManager(
            config,
            service,
            commands,
            lock,
            audit,
            (type, body, priority) -> true,
            fullBackups);
    try {
      Field stateField = MaintenanceManager.class.getDeclaredField("state");
      stateField.setAccessible(true);
      MaintenanceStateStore state = (MaintenanceStateStore) stateField.get(maintenance);
      MaintenanceStateStore.Job job =
          state.begin("FULL_RESTORE_POINT", null, false, "COUNTDOWN", "device", "Operator");
      state.beginCountdown(job, 1800, Instant.now());

      Map<String, Object> status = maintenance.status();
      assertEquals(3, status.get("jobStateContractVersion"));
      assertEquals("ACTIVE", status.get("countdownState"));
      assertNotNull(status.get("countdownDeadline"));
      long remaining = ((Number) status.get("countdownRemainingSeconds")).longValue();
      assertTrue(remaining >= 1790 && remaining <= 1800, "remaining=" + remaining);
      assertEquals(List.of(), status.get("countdownWarningsSent"));
      assertEquals(job.jobId(), ((MaintenanceStateStore.Job) status.get("currentOperation")).jobId());
    } finally {
      maintenance.close();
    }
  }

  private HostConfig config(Path root, Path backups, Path data) {
    return new HostConfig(
        UUID.randomUUID().toString(),
        "PlexonCraft-Test",
        "wss://relay.example/v1/agent",
        "unused",
        root.toString(),
        data.toString(),
        temporary.resolve("access.json").toString(),
        "plexoncraft-test.service",
        Map.of(),
        new HostConfig.BackupConfig(
            true,
            backups.toString(),
            List.of("world", "plugins", "config"),
            3,
            0,
            20L * 1024 * 1024 * 1024,
            true,
            "/usr/bin/rclone",
            "",
            temporary.resolve("rclone.conf").toString()),
        HostConfig.ConsoleConfig.defaults());
  }

  private static MinecraftCommandChannel disabledCommands() {
    return new MinecraftCommandChannel() {
      @Override
      public boolean enabled() {
        return false;
      }

      @Override
      public Result maintenanceNotice(MaintenanceOperation operation, int remainingSeconds) {
        return Result.ok();
      }

      @Override
      public Result saveAllFlush() {
        return Result.ok();
      }

      @Override
      public Result readinessProbe() {
        return Result.failed("COMMAND_CHANNEL_DISABLED");
      }
    };
  }
}
