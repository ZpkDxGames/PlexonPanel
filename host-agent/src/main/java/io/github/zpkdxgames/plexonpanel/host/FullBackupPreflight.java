package io.github.zpkdxgames.plexonpanel.host;

import io.github.zpkdxgames.plexonpanel.control.OperationFailure;
import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/** Fast, non-destructive checks performed before a full-backup maintenance countdown begins. */
final class FullBackupPreflight {
  private static final int ENTRY_LIMIT = 500_000;
  private static final long EXTRA_SPACE_CAP = 2L * 1024 * 1024 * 1024;
  private static final Set<String> SAFE_FAILURE_CODES =
      Set.of(
          "RCLONE_CONFIG_INVALID",
          "RCLONE_EXECUTABLE_UNAVAILABLE",
          "RCLONE_CONFIG_UNREADABLE",
          "BACKUP_STAGING_UNAVAILABLE",
          "BACKUP_INCLUDE_INVALID",
          "BACKUP_SOURCE_MISSING",
          "BACKUP_SOURCE_UNREADABLE",
          "BACKUP_SOURCE_CHANGED",
          "BACKUP_SYMLINK_REJECTED",
          "BACKUP_ENTRY_LIMIT",
          "BACKUP_SIZE_LIMIT");

  private final HostConfig config;
  private final FullRestorePointManager backups;

  FullBackupPreflight(HostConfig config, FullRestorePointManager backups) {
    this.config = Objects.requireNonNull(config);
    this.backups = Objects.requireNonNull(backups);
  }

  Map<String, Object> check(MaintenanceSettings.FullRestorePoint settings) throws Exception {
    Objects.requireNonNull(settings);
    Map<String, Object> provider = backups.providerStatus();
    if (!Boolean.TRUE.equals(provider.get("configured")))
      throw preflightFailure("RCLONE_UNAVAILABLE", "provider", false);

    HostConfig.BackupConfig backupConfig = config.backups();
    try {
      validateProviderFiles(backupConfig);
    } catch (IOException failure) {
      throw translate(failure, "RCLONE_CONFIG_UNREADABLE", "provider");
    }

    Path root;
    try {
      root = Path.of(config.serverRoot()).toRealPath();
    } catch (IOException failure) {
      throw translate(failure, "BACKUP_SOURCE_UNREADABLE", "source");
    }
    Path base = Path.of(backupConfig.directory()).toAbsolutePath().normalize();
    Path staging = base.resolve("staging");
    if (!Files.isDirectory(base, LinkOption.NOFOLLOW_LINKS)
        || Files.isSymbolicLink(base)
        || !Files.isWritable(base)
        || !Files.isDirectory(staging, LinkOption.NOFOLLOW_LINKS)
        || Files.isSymbolicLink(staging)
        || !Files.isWritable(staging))
      throw preflightFailure("BACKUP_STAGING_UNAVAILABLE", "storage", true);

    Scan scan;
    try {
      scan = scan(root, backupConfig.include(), settings);
    } catch (IOException failure) {
      throw translate(failure, "BACKUP_SOURCE_UNREADABLE", "source");
    }
    long usable;
    try {
      usable = Files.getFileStore(base).getUsableSpace();
    } catch (IOException failure) {
      throw translate(failure, "BACKUP_STORAGE_UNAVAILABLE", "capacity");
    }
    long required = requiredSpace(scan.bytes());
    if (usable < required)
      throw OperationFailure.withSafeDetails(
          "BACKUP_DISK_SPACE_INSUFFICIENT",
          "PREFLIGHT",
          "The backup volume does not have enough free space for this cold backup.",
          true,
          Map.of(
              "stage", "capacity",
              "usableBytes", Long.toString(usable),
              "requiredBytes", Long.toString(required)));

    int providerTimeout = Math.max(5, Math.min(15, settings.uploadTimeoutSeconds()));
    Map<String, Object> connectivity = backups.testProvider(providerTimeout);

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("sourceBytes", scan.bytes());
    result.put("entryCount", scan.entries());
    result.put("usableBytes", usable);
    result.put("requiredBytes", required);
    result.put("provider", connectivity.getOrDefault("provider", "RCLONE"));
    result.put("providerStatus", connectivity.getOrDefault("status", "CONNECTED"));
    result.put("remote", connectivity.getOrDefault("remote", ""));
    return Map.copyOf(result);
  }

  static OperationFailure translate(IOException failure, String fallbackCode, String stage) {
    Objects.requireNonNull(failure);
    String message = failure.getMessage();
    String code = SAFE_FAILURE_CODES.contains(message) ? message : fallbackCode;
    return preflightFailure(code, stage, !code.endsWith("INVALID"));
  }

  private static OperationFailure preflightFailure(
      String code, String stage, boolean retryable) {
    String message =
        switch (code) {
          case "RCLONE_UNAVAILABLE" ->
              "No off-site rclone provider is configured on the running Host.";
          case "RCLONE_CONFIG_INVALID" ->
              "The Host rclone provider configuration is invalid.";
          case "RCLONE_EXECUTABLE_UNAVAILABLE" ->
              "The fixed /usr/bin/rclone executable is unavailable or unsafe.";
          case "RCLONE_CONFIG_UNREADABLE" ->
              "The Host cannot read the configured rclone configuration file.";
          case "BACKUP_STAGING_UNAVAILABLE" ->
              "The backup repository or staging directory is unavailable or not writable.";
          case "BACKUP_INCLUDE_INVALID" ->
              "The active backup include list is empty, duplicated or invalid.";
          case "BACKUP_SOURCE_MISSING" ->
              "A configured top-level backup source is missing from the server root.";
          case "BACKUP_SOURCE_UNREADABLE" ->
              "A configured backup source contains data the Host cannot read.";
          case "BACKUP_SOURCE_CHANGED" ->
              "A configured backup source changed while preflight was scanning it.";
          case "BACKUP_SYMLINK_REJECTED" ->
              "A configured backup source contains a symlink or unsupported entry.";
          case "BACKUP_ENTRY_LIMIT" ->
              "The configured backup sources exceed the safe entry-count limit.";
          case "BACKUP_SIZE_LIMIT" ->
              "The configured backup sources exceed the maximum backup size.";
          case "BACKUP_STORAGE_UNAVAILABLE" ->
              "The Host could not inspect free space on the backup volume.";
          default -> "The Host backup preflight failed its local safety contract.";
        };
    return OperationFailure.withSafeDetails(
        code, "PREFLIGHT", message, retryable, Map.of("stage", stage));
  }

  static long requiredSpace(long sourceBytes) {
    long overhead = Math.min(sourceBytes, EXTRA_SPACE_CAP);
    try {
      return Math.addExact(sourceBytes, overhead);
    } catch (ArithmeticException overflow) {
      return Long.MAX_VALUE;
    }
  }

  private static void validateProviderFiles(HostConfig.BackupConfig config) throws IOException {
    if (!"/usr/bin/rclone".equals(config.rcloneExecutable())) throw new IOException("RCLONE_CONFIG_INVALID");
    Path executable = Path.of(config.rcloneExecutable());
    if (!Files.isRegularFile(executable, LinkOption.NOFOLLOW_LINKS)
        || Files.isSymbolicLink(executable)
        || !Files.isExecutable(executable)) throw new IOException("RCLONE_EXECUTABLE_UNAVAILABLE");

    if (config.rcloneConfig() == null || !Path.of(config.rcloneConfig()).isAbsolute())
      throw new IOException("RCLONE_CONFIG_INVALID");
    Path providerConfig = Path.of(config.rcloneConfig()).normalize();
    if (!Files.isRegularFile(providerConfig, LinkOption.NOFOLLOW_LINKS)
        || Files.isSymbolicLink(providerConfig)
        || !Files.isReadable(providerConfig)) throw new IOException("RCLONE_CONFIG_UNREADABLE");
  }

  static Scan scan(
      Path root, List<String> includes, MaintenanceSettings.FullRestorePoint settings)
      throws IOException {
    long[] bytes = {0};
    int[] entries = {0};
    FullBackupSource.walk(
        root,
        includes,
        settings,
        new FullBackupSource.Visitor() {
          @Override
          public void visitFile(Path file, Path relative, BasicFileAttributes attributes)
              throws IOException {
            if (++entries[0] > ENTRY_LIMIT) throw new IOException("BACKUP_ENTRY_LIMIT");
            try {
              bytes[0] = Math.addExact(bytes[0], attributes.size());
            } catch (ArithmeticException overflow) {
              throw new IOException("BACKUP_SIZE_LIMIT", overflow);
            }
            if (bytes[0] > settings.maximumBytes()) throw new IOException("BACKUP_SIZE_LIMIT");
          }
        });
    return new Scan(bytes[0], entries[0]);
  }

  record Scan(long bytes, int entries) {}
}
