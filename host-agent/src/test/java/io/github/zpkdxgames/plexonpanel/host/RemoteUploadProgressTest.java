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
    assertEquals("VERIFYING_REMOTE", verified.get("phase"));

    assertFalse(start.containsKey("remote"));
    assertFalse(start.containsKey("output"));
    assertFalse(start.containsKey("credentials"));
  }

  @Test
  void verifiedRemotePromotionReleasesTheLocalArchive() throws Exception {
    Path root = Files.createDirectory(temporary.resolve("cleanup-server"));
    Path backups = temporary.resolve("cleanup-backups");
    List<Map<String, Object>> events = new ArrayList<>();
    FullRestorePointManager manager =
        new FullRestorePointManager(
            config(root, backups),
            new SystemdService("plexoncraft-test.service"),
            () -> false,
            new ReentrantLock(),
            event -> events.add(Map.copyOf(event)));
    String jobId = UUID.randomUUID().toString();
    String backupId = UUID.randomUUID().toString();
    Path archive = backups.resolve("restore-points").resolve(backupId + ".zip");
    Path metadata = backups.resolve("metadata").resolve(backupId + ".json");
    Files.write(archive, new byte[4096]);
    FullRestorePointManager.Metadata local = localMetadata(backupId, jobId, 4096L);
    RcloneBackupProvider.Promotion promotion =
        new RcloneBackupProvider.Promotion(
            true,
            true,
            "gdrive:PlexonCraft/PlexonCraft-Latest.zip",
            "2026-09-18T00:02:00Z",
            "verified");
    Method complete =
        FullRestorePointManager.class.getDeclaredMethod(
            "completeRemotePromotion",
            FullRestorePointManager.Metadata.class,
            RcloneBackupProvider.Promotion.class,
            Path.class,
            Path.class,
            String.class,
            String.class,
            long.class,
            int.class);
    complete.setAccessible(true);

    FullRestorePointManager.Metadata result =
        (FullRestorePointManager.Metadata)
            complete.invoke(
                manager,
                local,
                promotion,
                archive,
                metadata,
                jobId,
                backupId,
                4096L,
                12);

    assertFalse(Files.exists(archive));
    assertFalse(result.local());
    assertTrue(result.offsite());
    assertEquals("VERIFIED_REMOTE", result.verification());
    assertTrue(Files.readString(metadata).contains("\"local\": false"));
    assertTrue(
        events.stream()
            .anyMatch(
                event ->
                    "CLEANING_LOCAL".equals(event.get("phase"))
                        && "LOCAL_RELEASED".equals(event.get("providerState"))));
  }

  @Test
  void localCleanupFailureKeepsTheVerifiedRemoteResultAndRetainedState() throws Exception {
    Path root = Files.createDirectory(temporary.resolve("retained-server"));
    Path backups = temporary.resolve("retained-backups");
    List<Map<String, Object>> events = new ArrayList<>();
    FullRestorePointManager manager =
        new FullRestorePointManager(
            config(root, backups),
            new SystemdService("plexoncraft-test.service"),
            () -> false,
            new ReentrantLock(),
            event -> events.add(Map.copyOf(event)));
    String jobId = UUID.randomUUID().toString();
    String backupId = UUID.randomUUID().toString();
    Path undeletable = Files.createDirectory(temporary.resolve("non-empty-archive"));
    Files.writeString(undeletable.resolve("child"), "keep");
    Path metadata = backups.resolve("metadata").resolve(backupId + ".json");
    Method complete =
        FullRestorePointManager.class.getDeclaredMethod(
            "completeRemotePromotion",
            FullRestorePointManager.Metadata.class,
            RcloneBackupProvider.Promotion.class,
            Path.class,
            Path.class,
            String.class,
            String.class,
            long.class,
            int.class);
    complete.setAccessible(true);

    FullRestorePointManager.Metadata result =
        (FullRestorePointManager.Metadata)
            complete.invoke(
                manager,
                localMetadata(backupId, jobId, 4096L),
                verifiedPromotion(),
                undeletable,
                metadata,
                jobId,
                backupId,
                4096L,
                12);

    assertTrue(Files.exists(undeletable));
    assertTrue(result.local());
    assertTrue(result.offsite());
    assertEquals("SUCCESS_WITH_WARNING", result.result());
    assertEquals("LOCAL_CLEANUP_FAILED", result.errorCode());
    assertTrue(
        events.stream()
            .anyMatch(event -> "LOCAL_RETAINED".equals(event.get("providerState"))));
  }

  private FullRestorePointManager.Metadata localMetadata(
      String backupId, String jobId, long archiveBytes) {
    return new FullRestorePointManager.Metadata(
        backupId,
        jobId,
        "FULL_RESTORE_POINT",
        "2026-09-18T00:00:00Z",
        "2026-09-18T00:00:00Z",
        "2026-09-18T00:01:00Z",
        60_000L,
        "PlexonCraft-Test",
        "Minecraft 26.2",
        "Paper 26.2",
        "3.5.1",
        archiveBytes,
        8192L,
        12,
        "abc123",
        true,
        false,
        "RCLONE",
        "",
        "VERIFIED_LOCAL",
        "test",
        false,
        false,
        false,
        "SUCCESS_LOCAL",
        "");
  }

  private RcloneBackupProvider.Promotion verifiedPromotion() {
    return new RcloneBackupProvider.Promotion(
        true,
        true,
        "gdrive:PlexonCraft/PlexonCraft-Latest.zip",
        "2026-09-18T00:02:00Z",
        "verified");
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
