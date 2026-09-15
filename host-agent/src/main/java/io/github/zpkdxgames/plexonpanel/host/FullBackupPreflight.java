package io.github.zpkdxgames.plexonpanel.host;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/** Fast, non-destructive checks performed before a full-backup maintenance countdown begins. */
final class FullBackupPreflight {
  private static final int ENTRY_LIMIT = 500_000;
  private static final long EXTRA_SPACE_CAP = 2L * 1024 * 1024 * 1024;

  private final HostConfig config;
  private final FullRestorePointManager backups;

  FullBackupPreflight(HostConfig config, FullRestorePointManager backups) {
    this.config = Objects.requireNonNull(config);
    this.backups = Objects.requireNonNull(backups);
  }

  Map<String, Object> check(MaintenanceSettings.FullRestorePoint settings) throws Exception {
    Objects.requireNonNull(settings);
    Map<String, Object> provider = backups.providerStatus();
    if (!Boolean.TRUE.equals(provider.get("configured"))) throw new IOException("RCLONE_UNAVAILABLE");

    HostConfig.BackupConfig backupConfig = config.backups();
    validateProviderFiles(backupConfig);

    Path root = Path.of(config.serverRoot()).toRealPath();
    Path base = Path.of(backupConfig.directory()).toAbsolutePath().normalize();
    Path staging = base.resolve("staging");
    if (!Files.isDirectory(base, LinkOption.NOFOLLOW_LINKS)
        || Files.isSymbolicLink(base)
        || !Files.isWritable(base)
        || !Files.isDirectory(staging, LinkOption.NOFOLLOW_LINKS)
        || Files.isSymbolicLink(staging)
        || !Files.isWritable(staging)) throw new IOException("BACKUP_STAGING_UNAVAILABLE");

    Scan scan = scan(root, settings);
    long usable = Files.getFileStore(base).getUsableSpace();
    long required = requiredSpace(scan.bytes());
    if (usable < required) throw new IOException("BACKUP_DISK_SPACE_INSUFFICIENT");

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

  private static Scan scan(Path root, MaintenanceSettings.FullRestorePoint settings) throws IOException {
    long[] bytes = {0};
    int[] entries = {0};
    Files.walkFileTree(
        root,
        EnumSet.noneOf(FileVisitOption.class),
        96,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
            Path relative = root.relativize(dir);
            if (!relative.toString().isEmpty() && excluded(relative, settings))
              return FileVisitResult.SKIP_SUBTREE;
            if (Files.isSymbolicLink(dir)) throw new IOException("BACKUP_SYMLINK_REJECTED");
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
            Path relative = root.relativize(file);
            if (excluded(relative, settings)) return FileVisitResult.CONTINUE;
            if (!attrs.isRegularFile() || Files.isSymbolicLink(file))
              throw new IOException("BACKUP_SYMLINK_REJECTED");
            if (++entries[0] > ENTRY_LIMIT) throw new IOException("BACKUP_ENTRY_LIMIT");
            try {
              bytes[0] = Math.addExact(bytes[0], attrs.size());
            } catch (ArithmeticException overflow) {
              throw new IOException("BACKUP_SIZE_LIMIT", overflow);
            }
            if (bytes[0] > settings.maximumBytes()) throw new IOException("BACKUP_SIZE_LIMIT");
            return FileVisitResult.CONTINUE;
          }
        });
    return new Scan(bytes[0], entries[0]);
  }

  private static boolean excluded(Path relative, MaintenanceSettings.FullRestorePoint settings) {
    String name = relative.toString().replace(File.separatorChar, '/');
    if (name.isEmpty()) return false;
    String lower = name.toLowerCase(Locale.ROOT);
    if (lower.startsWith(".plexonpanel-restore-")
        || lower.startsWith(".plexonpanel-rollback-")
        || lower.endsWith(".partial")) return true;
    for (String configured : settings.excludes()) {
      String normalized = configured.replace('\\', '/');
      if (name.equals(normalized) || name.startsWith(normalized + "/")) return true;
    }
    return false;
  }

  private record Scan(long bytes, int entries) {}
}
