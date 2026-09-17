package io.github.zpkdxgames.plexonpanel.host;

import static org.junit.jupiter.api.Assertions.*;

import io.github.zpkdxgames.plexonpanel.control.OperationFailure;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FullBackupPreflightTest {
  @TempDir Path temporary;

  @Test
  void requiredSpaceIncludesSourceSizedHeadroomForSmallBackups() {
    assertEquals(2_000_000L, FullBackupPreflight.requiredSpace(1_000_000L));
  }

  @Test
  void requiredSpaceCapsHeadroomAtTwoGiB() {
    long source = 10L * 1024 * 1024 * 1024;
    assertEquals(source + 2L * 1024 * 1024 * 1024, FullBackupPreflight.requiredSpace(source));
  }

  @Test
  void requiredSpaceFailsClosedOnOverflow() {
    assertEquals(Long.MAX_VALUE, FullBackupPreflight.requiredSpace(Long.MAX_VALUE - 1));
  }

  @Test
  void scanIsBoundedToConfiguredTopLevelIncludes() throws Exception {
    Path root = Files.createDirectory(temporary.resolve("server"));
    Files.createDirectories(root.resolve("world/region"));
    Files.writeString(root.resolve("world/level.dat"), "world-state");
    Files.writeString(root.resolve("world/region/r.0.0.mca"), "region-state");
    Files.writeString(root.resolve("unconfigured-secret.dat"), "must-not-be-read");

    FullBackupPreflight.Scan scan =
        FullBackupPreflight.scan(
            root,
            List.of("world"),
            MaintenanceSettings.migratedDefaults().fullRestorePoint());

    assertEquals(2, scan.entries());
    assertEquals("world-state".length() + "region-state".length(), scan.bytes());
  }

  @Test
  void missingConfiguredIncludeFailsClosed() throws Exception {
    Path root = Files.createDirectory(temporary.resolve("server"));

    IOException failure =
        assertThrows(
            IOException.class,
            () ->
                FullBackupPreflight.scan(
                    root,
                    List.of("world"),
                    MaintenanceSettings.migratedDefaults().fullRestorePoint()));

    assertEquals("BACKUP_SOURCE_MISSING", failure.getMessage());
  }

  @Test
  void unreadableConfiguredFileFailsClosed() throws Exception {
    Path root = Files.createDirectory(temporary.resolve("server"));
    Path world = Files.createDirectory(root.resolve("world"));
    Path unreadable = Files.writeString(world.resolve("level.dat"), "world-state");
    if (!FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) return;
    Files.setPosixFilePermissions(unreadable, PosixFilePermissions.fromString("-w-------"));

    IOException failure =
        assertThrows(
            IOException.class,
            () ->
                FullBackupPreflight.scan(
                    root,
                    List.of("world"),
                    MaintenanceSettings.migratedDefaults().fullRestorePoint()));

    assertEquals("BACKUP_SOURCE_UNREADABLE", failure.getMessage());
  }

  @Test
  void sourceFailureBecomesTypedBrowserSafePreflightFailure() {
    OperationFailure failure =
        FullBackupPreflight.translate(
            new IOException("BACKUP_SOURCE_MISSING"),
            "BACKUP_SOURCE_UNREADABLE",
            "source");

    assertEquals("BACKUP_SOURCE_MISSING", failure.code());
    assertEquals("PREFLIGHT", failure.phase());
    assertTrue(failure.retryable());
    assertEquals("source", failure.safeData().get("stage"));
    assertFalse(failure.getMessage().contains("/"));
  }

  @Test
  void rawIoFailureUsesTheStageFallbackWithoutLeakingItsMessage() {
    OperationFailure failure =
        FullBackupPreflight.translate(
            new IOException("/private/provider/path: permission denied"),
            "RCLONE_CONFIG_UNREADABLE",
            "provider");

    assertEquals("RCLONE_CONFIG_UNREADABLE", failure.code());
    assertEquals("provider", failure.safeData().get("stage"));
    assertFalse(failure.getMessage().contains("private"));
  }
}
