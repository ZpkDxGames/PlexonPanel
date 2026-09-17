package io.github.zpkdxgames.plexonpanel.host;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.zip.*;
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
    Files.writeString(root.resolve("unconfigured-secret.dat"), "must-not-be-read");

    var manager = manager(root, backups);
    var settings = MaintenanceSettings.migratedDefaults().fullRestorePoint();

    assertFalse(FullBackupSource.excluded(Path.of("plugins/PlexonRanks/database.db"), settings));
    assertFalse(FullBackupSource.excluded(Path.of("plugins/PlexonRanks/data.sqlite"), settings));
    assertFalse(FullBackupSource.excluded(Path.of("world/level.dat"), settings));
    assertFalse(FullBackupSource.excluded(Path.of("world/playerdata/player.dat"), settings));
    assertFalse(FullBackupSource.excluded(Path.of("config/paper-global.yml"), settings));
    assertTrue(FullBackupSource.excluded(Path.of("logs/latest.log"), settings));

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

  @Test
  void corruptStagedArchiveIsNeverPublished() throws Exception {
    Path staging = Files.createDirectory(temporary.resolve("staging"));
    Path restorePoints = Files.createDirectory(temporary.resolve("restore-points"));
    Path partial = staging.resolve("candidate.partial");
    Path published = restorePoints.resolve("candidate.zip");
    Files.writeString(partial, "not-a-zip");

    IOException failure =
        assertThrows(
            IOException.class,
            () ->
                FullRestorePointManager.verifyAndPublish(
                    partial, published, 1, 9, 1024 * 1024));

    assertEquals("LOCAL_BACKUP_STRUCTURE_INVALID", failure.getMessage());
    assertTrue(Files.exists(partial));
    assertFalse(Files.exists(published));
  }

  @Test
  void verifiedStagedArchiveIsPublishedAtomically() throws Exception {
    Path staging = Files.createDirectory(temporary.resolve("staging"));
    Path restorePoints = Files.createDirectory(temporary.resolve("restore-points"));
    Path partial = staging.resolve("candidate.partial");
    Path published = restorePoints.resolve("candidate.zip");
    byte[] payload = "world-state".getBytes(StandardCharsets.UTF_8);
    try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(partial))) {
      zip.putNextEntry(new ZipEntry("world/level.dat"));
      zip.write(payload);
      zip.closeEntry();
    }

    FullRestorePointManager.VerifiedArchive result =
        FullRestorePointManager.verifyAndPublish(
            partial, published, 1, payload.length, 1024 * 1024);

    assertFalse(Files.exists(partial));
    assertTrue(Files.isRegularFile(published));
    assertEquals(Files.size(published), result.archiveBytes());
    assertEquals(1, result.verification().entryCount());
    assertEquals(payload.length, result.verification().expandedBytes());
  }
}
