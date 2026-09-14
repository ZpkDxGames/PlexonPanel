package io.github.zpkdxgames.plexonpanel.host;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Read-only, bounded diagnostics for live snapshot readiness. */
final class BackupPreflight {
  private static final int EXAMPLE_LIMIT = 10;
  private final HostConfig config;
  private final Path root;
  private final Path directory;
  private final SystemdService service;
  private final BooleanSupplier paperConnected;
  private final BooleanSupplier hostAuthenticated;
  private final BooleanSupplier operationLocked;
  private final BooleanSupplier recoveryRequired;

  BackupPreflight(
      HostConfig config,
      Path root,
      Path directory,
      SystemdService service,
      BooleanSupplier paperConnected,
      BooleanSupplier hostAuthenticated,
      BooleanSupplier operationLocked,
      BooleanSupplier recoveryRequired) {
    this.config = config;
    this.root = root;
    this.directory = directory;
    this.service = service;
    this.paperConnected = paperConnected;
    this.hostAuthenticated = hostAuthenticated;
    this.operationLocked = operationLocked;
    this.recoveryRequired = recoveryRequired;
  }

  Map<String, Object> run() {
    List<String> missing = new ArrayList<>(), unreadable = new ArrayList<>(), symlinks = new ArrayList<>();
    long[] durable = {0}, volatileExcluded = {0}, unreadableCount = {0};
    for (String include : config.backups().include()) {
      Path source = root.resolve(include).normalize();
      if (!source.startsWith(root) || !Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
        missing.add(include);
        continue;
      }
      try {
        Files.walkFileTree(
            source,
            EnumSet.noneOf(FileVisitOption.class),
            64,
            new SimpleFileVisitor<>() {
              @Override
              public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                String relative = relative(dir);
                if (LiveSnapshotPolicy.volatileExcluded(config, relative)) {
                  volatileExcluded[0]++;
                  return FileVisitResult.SKIP_SUBTREE;
                }
                if (!LiveSnapshotPolicy.allowed(config, relative)) return FileVisitResult.SKIP_SUBTREE;
                if (Files.isSymbolicLink(dir)) {
                  addBounded(symlinks, relative);
                  return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
              }

              @Override
              public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                String relative = relative(file);
                if (LiveSnapshotPolicy.volatileExcluded(config, relative)) {
                  volatileExcluded[0]++;
                  return FileVisitResult.CONTINUE;
                }
                if (!LiveSnapshotPolicy.allowed(config, relative)) return FileVisitResult.CONTINUE;
                if (Files.isSymbolicLink(file)) {
                  addBounded(symlinks, relative);
                  return FileVisitResult.CONTINUE;
                }
                if (!attrs.isRegularFile()) return FileVisitResult.CONTINUE;
                durable[0]++;
                try (InputStream input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                  input.readNBytes(1);
                } catch (IOException failure) {
                  unreadableCount[0]++;
                  addBounded(unreadable, relative);
                }
                return FileVisitResult.CONTINUE;
              }

              @Override
              public FileVisitResult visitFileFailed(Path file, IOException failure) {
                String relative = relative(file);
                if (LiveSnapshotPolicy.volatileExcluded(config, relative)) volatileExcluded[0]++;
                else {
                  unreadableCount[0]++;
                  addBounded(unreadable, relative);
                }
                return FileVisitResult.CONTINUE;
              }
            });
      } catch (IOException failure) {
        unreadableCount[0]++;
        addBounded(unreadable, include);
      }
    }

    long usable = -1;
    try {
      usable = Files.getFileStore(directory).getUsableSpace();
    } catch (IOException ignored) {
    }
    String serviceState = "UNKNOWN";
    try {
      serviceState = String.valueOf(service.status().getOrDefault("state", "UNKNOWN"));
    } catch (Exception ignored) {
    }

    boolean providerConfigured =
        config.backups().rcloneRemote() != null && !config.backups().rcloneRemote().isBlank();
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("paperConnected", paperConnected.getAsBoolean());
    result.put("hostAuthenticated", hostAuthenticated.getAsBoolean());
    result.put("serviceState", serviceState);
    result.put("backupRootWritable", Files.isDirectory(directory) && Files.isWritable(directory));
    result.put("backupRootUsableBytes", usable);
    result.put("configuredIncludes", List.copyOf(config.backups().include()));
    result.put("missingIncludes", List.copyOf(missing));
    result.put("durableFileCount", durable[0]);
    result.put("unreadableDurableCount", unreadableCount[0]);
    result.put("unreadableDurableExamples", List.copyOf(unreadable));
    result.put("volatileExcludedCount", volatileExcluded[0]);
    result.put("volatileExclusions", List.copyOf(config.backups().liveSnapshotExcludes()));
    result.put("symlinkIssues", List.copyOf(symlinks));
    result.put("currentBackupLockState", operationLocked.getAsBoolean() ? "BUSY" : "IDLE");
    result.put("recoveryRequired", recoveryRequired.getAsBoolean());
    result.put("providerMode", providerConfigured ? "RCLONE" : "LOCAL");
    result.put("providerConfigured", providerConfigured);
    result.put("legacyIntervalMinutes", config.backups().intervalMinutes());
    result.put("calendarLiveSnapshotEnabled", false);
    result.put("scheduleConflictState", "NONE");
    return Map.copyOf(result);
  }

  private String relative(Path path) {
    try {
      return root.relativize(path.toAbsolutePath().normalize()).toString().replace(File.separatorChar, '/');
    } catch (Exception ignored) {
      return "unavailable";
    }
  }

  private static void addBounded(List<String> values, String value) {
    if (values.size() < EXAMPLE_LIMIT && value != null && value.length() <= 512) values.add(value);
  }
}
