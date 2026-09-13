package io.github.zpkdxgames.plexonpanel.host;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FullRestorePointPolicyTest {
  @TempDir Path temporary;

  private HostConfig config(Path root, Path backups) {
    return new HostConfig(
        UUID.randomUUID().toString(),
        "PlexonCraft-Test",
        "wss://relay.example/v1/agent",
        "unused",
        root.toString(),
        temporary.resolve("host-data").toString(),
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

  private FullRestorePointManager manager(Path root, Path backups) throws Exception {
    return new FullRestorePointManager(
        config(root, backups),
        new SystemdService("plexoncraft-test.service"),
        () -> false,
        new ReentrantLock(),
        progress -> {});
  }

  @Test
  void backupDestinationInsideServerRootIsRejected() throws Exception {
    Path root = Files.createDirectory(temporary.resolve("server"));
    Path nested = Files.createDirectories(root.resolve("backups"));
    assertThrows(Exception.class, () -> manager(root, nested));
  }

  @Test
  void coldScanIncludesPluginDatabasesWorldsAndConfigsButHonorsExplicitNoiseExcludes()
      throws Exception {
    Path root = Files.createDirectory(temporary.resolve("server"));
    Path backups = temporary.resolve("backups");
    Files.createDirectories(root.resolve("plugins/PlexonRanks"));
    Files.createDirectories(root.resolve("world/playerdata"));
    Files.createDirectories(root.resolve("config"));
    Files.createDirectories(root.resolve("logs"));
    Files.writeString(root.resolve("plugins/PlexonRanks/database.db"), "sqlite-state");
    Files.writeString(root.resolve("plugins/PlexonRanks/data.sqlite"), "sqlite-state-2");
    Files.writeString(root.resolve("world/level.dat"), "world-state");
    Files.writeString(root.resolve("world/playerdata/player.dat"), "player-state");
    Files.writeString(root.resolve("config/paper-global.yml"), "config-state");
    Files.writeString(root.resolve("logs/latest.log"), "noise");

    var manager = manager(root, backups);
    var settings = MaintenanceSettings.migratedDefaults().fullRestorePoint();
    Method excluded =
        FullRestorePointManager.class.getDeclaredMethod(
            "excluded", Path.class, MaintenanceSettings.FullRestorePoint.class);
    excluded.setAccessible(true);

    assertFalse((boolean) excluded.invoke(manager, Path.of("plugins/PlexonRanks/database.db"), settings));
    assertFalse((boolean) excluded.invoke(manager, Path.of("plugins/PlexonRanks/data.sqlite"), settings));
    assertFalse((boolean) excluded.invoke(manager, Path.of("world/level.dat"), settings));
    assertFalse((boolean) excluded.invoke(manager, Path.of("world/playerdata/player.dat"), settings));
    assertFalse((boolean) excluded.invoke(manager, Path.of("config/paper-global.yml"), settings));
    assertTrue((boolean) excluded.invoke(manager, Path.of("logs/latest.log"), settings));

    Method scan =
        FullRestorePointManager.class.getDeclaredMethod(
            "scan", MaintenanceSettings.FullRestorePoint.class);
    scan.setAccessible(true);
    Object result = scan.invoke(manager, settings);
    Method entries = result.getClass().getDeclaredMethod("entries");
    Method bytes = result.getClass().getDeclaredMethod("bytes");
    entries.setAccessible(true);
    bytes.setAccessible(true);
    assertEquals(5, ((Number) entries.invoke(result)).intValue());
    assertTrue(((Number) bytes.invoke(result)).longValue() > 0);
  }

  @Test
  void coldScanFailsWhenConfiguredMaximumWouldBeExceeded() throws Exception {
    Path root = Files.createDirectory(temporary.resolve("server"));
    Path backups = temporary.resolve("backups");
    Files.createDirectories(root.resolve("world"));
    Files.writeString(root.resolve("world/level.dat"), "x".repeat(128));
    var manager = manager(root, backups);
    var base = MaintenanceSettings.migratedDefaults().fullRestorePoint();
    var tiny =
        new MaintenanceSettings.FullRestorePoint(
            base.schedule(),
            base.retentionMode(),
            base.retentionCount(),
            base.restartAfter(),
            base.canonicalFilename(),
            base.uploadTimeoutSeconds(),
            base.verificationMode(),
            32,
            base.excludes());
    Method scan =
        FullRestorePointManager.class.getDeclaredMethod(
            "scan", MaintenanceSettings.FullRestorePoint.class);
    scan.setAccessible(true);
    InvocationTargetException error =
        assertThrows(InvocationTargetException.class, () -> scan.invoke(manager, tiny));
    assertEquals("BACKUP_SIZE_LIMIT", error.getCause().getMessage());
  }
}
