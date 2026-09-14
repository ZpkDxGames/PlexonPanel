package io.github.zpkdxgames.plexonpanel.host;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Method;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RemoteUploadProgressTest {
  @TempDir Path temporary;

  @Test
  void remoteProgressUsesUploadedBytesAndSanitizedProviderState() throws Exception {
    Path root = Files.createDirectory(temporary.resolve("server"));
    Path backups = temporary.resolve("backups");
    List<Map<String, Object>> events = new ArrayList<>();
    FullRestorePointManager manager =
        new FullRestorePointManager(
            config(root, backups),
            new SystemdService("plexoncraft-test.service"),
            () -> false,
            new ReentrantLock(),
            event -> events.add(Map.copyOf(event)));

    Method emit =
        FullRestorePointManager.class.getDeclaredMethod(
            "emitRemoteProgress",
            String.class,
            String.class,
            long.class,
            long.class,
            int.class,
            String.class);
    emit.setAccessible(true);

    String jobId = UUID.randomUUID().toString();
    String backupId = UUID.randomUUID().toString();
    emit.invoke(manager, jobId, backupId, 0L, 4096L, 12, "UPLOADING");
    emit.invoke(manager, jobId, backupId, 4096L, 4096L, 12, "VERIFIED_REMOTE");

    assertEquals(2, events.size());
    Map<String, Object> start = events.get(0);
    assertEquals("UPLOADING_REMOTE", start.get("phase"));
    assertEquals(0L, start.get("bytesUploaded"));
    assertEquals(4096L, start.get("totalBytes"));
    assertEquals(0.0d, (double) start.get("progress"), 0.00001d);
    assertEquals("RCLONE", start.get("provider"));
    assertEquals("UPLOADING", start.get("providerState"));

    Map<String, Object> verified = events.get(1);
    assertEquals(4096L, verified.get("bytesUploaded"));
    assertEquals(4096L, verified.get("totalBytes"));
    assertEquals(1.0d, (double) verified.get("progress"), 0.00001d);
    assertEquals("VERIFIED_REMOTE", verified.get("providerState"));

    assertFalse(start.containsKey("remote"));
    assertFalse(start.containsKey("output"));
    assertFalse(start.containsKey("credentials"));
  }

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
            "gdrive:PlexonCraft",
            temporary.resolve("rclone.conf").toString()),
        HostConfig.ConsoleConfig.defaults());
  }
}
