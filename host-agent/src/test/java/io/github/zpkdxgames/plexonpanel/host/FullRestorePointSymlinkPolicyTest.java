package io.github.zpkdxgames.plexonpanel.host;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FullRestorePointSymlinkPolicyTest {
  @TempDir Path temporary;

  @Test
  void coldScanRejectsSymlinksInsideServerRoot() throws Exception {
    Path root = Files.createDirectory(temporary.resolve("server"));
    Path backups = temporary.resolve("backups");
    Path world = Files.createDirectories(root.resolve("world"));
    Path target = temporary.resolve("outside.dat");
    Files.writeString(target, "must-not-be-followed");
    Path link = world.resolve("linked.dat");
    try {
      Files.createSymbolicLink(link, target);
    } catch (UnsupportedOperationException | FileSystemException unsupported) {
      return;
    }

    FullRestorePointManager manager = manager(root, backups);
    Method scan =
        FullRestorePointManager.class.getDeclaredMethod(
            "scan", MaintenanceSettings.FullRestorePoint.class);
    scan.setAccessible(true);

    InvocationTargetException failure =
        assertThrows(
            InvocationTargetException.class,
            () -> scan.invoke(manager, MaintenanceSettings.migratedDefaults().fullRestorePoint()));
    assertEquals("BACKUP_SYMLINK_REJECTED", failure.getCause().getMessage());
    assertTrue(Files.exists(target));
  }

  private FullRestorePointManager manager(Path root, Path backups) throws Exception {
    HostConfig config =
        new HostConfig(
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
    return new FullRestorePointManager(
        config,
        new SystemdService("plexoncraft-test.service"),
        () -> false,
        new ReentrantLock(),
        ignored -> {});
  }
}
