package io.github.zpkdxgames.plexonpanel.host;

import com.google.gson.*;
import io.github.zpkdxgames.plexonpanel.control.OperationFailure;
import io.github.zpkdxgames.plexonpanel.security.DeviceRegistry;
import io.github.zpkdxgames.plexonpanel.util.AtomicFiles;
import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.*;
import java.util.zip.*;

/** Bounded archives of configured Minecraft paths. No remote path or executable controls. */
public final class BackupManager {
  public record Metadata(
      String backupId,
      String timestamp,
      String state,
      long bytes,
      String sha256,
      boolean local,
      boolean offsite,
      String sourceVersion,
      long durationMillis,
      String initiatedBy,
      boolean automatic,
      boolean emergency,
      String error,
      List<String> warnings,
      int skippedTransientCount,
      List<String> missingIncludeWarnings) {}

  private record RestoreGrant(String backupId, String deviceId, long expiry) {}

  private final HostConfig config;
  private final Path root, directory;
  private final SystemdService service;
  private final PaperSaveLease leases;
  private final BooleanSupplier paperConnected;
  private final Consumer<Map<String, Object>> progress;
  private final ReentrantLock operationLock;
  private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
  private final Map<String, RestoreGrant> grants = new HashMap<>();
  private long progressAt;

  public BackupManager(
      HostConfig config,
      SystemdService service,
      PaperSaveLease leases,
      BooleanSupplier paperConnected,
      Consumer<Map<String, Object>> progress,
      ReentrantLock lock)
      throws IOException {
    this.config = config;
    this.root = Path.of(config.serverRoot()).toRealPath();
    this.directory = Path.of(config.backups().directory()).toAbsolutePath().normalize();
    this.service = service;
    this.leases = leases;
    this.paperConnected = paperConnected;
    this.progress = progress;
    this.operationLock = lock;
    if (directory.startsWith(root) || Files.isSymbolicLink(directory))
      throw new IOException("Backups must be outside the server root");
    Files.createDirectories(directory);
    if (!directory.equals(directory.toRealPath()))
      throw new IOException("Backup directory must not contain symlinks");
  }

  public boolean recoveryRequired() {
    return Files.exists(directory.resolve("restore-journal.json"));
  }

  /** Read-only backup readiness diagnostics; does not stop Paper or contact the off-site provider. */
  public Map<String, Object> preflight(BooleanSupplier hostAuthenticated) {
    return new BackupPreflight(
            config,
            root,
            directory,
            service,
            paperConnected,
            hostAuthenticated,
            operationLock::isLocked,
            this::recoveryRequired)
        .run();
  }

  public List<Metadata> list() throws IOException {
    List<Metadata> result = new ArrayList<>();
    try (var paths = Files.list(directory)) {
      for (Path path :
          paths
              .filter(p -> p.getFileName().toString().matches("[0-9a-f-]{36}\\.json"))
              .limit(1001)
              .toList()) {
        if (Files.isSymbolicLink(path) || Files.size(path) > 8192) continue;
        Metadata m = gson.fromJson(Files.readString(path), Metadata.class);
        if (m != null) result.add(m);
      }
    }
    result.sort(Comparator.comparing(Metadata::timestamp).reversed());
    return result;
  }

  public Metadata metadata(String id) throws IOException {
    UUID.fromString(id);
    Path p = directory.resolve(id + ".json");
    if (Files.isSymbolicLink(p) || Files.size(p) > 8192)
      throw new IOException("Invalid backup metadata");
    Metadata m = gson.fromJson(Files.readString(p), Metadata.class);
    if (m == null || !m.backupId.equals(id)) throw new IOException("Invalid backup identity");
    return m;
  }

  public Metadata create(DeviceRegistry.Device device, boolean automatic, boolean emergency)
      throws Exception {
    if (!config.backups().enabled()) throw new SecurityException("CAPABILITY_DISABLED");
    if (!operationLock.tryLock()) throw new SecurityException("BUSY");
    String id = UUID.randomUUID().toString();
    long started = System.nanoTime();
    Path partial = directory.resolve(id + ".partial"), archive = directory.resolve(id + ".zip");
    PaperSaveLease.Lease lease = null;
    List<String> warnings = new ArrayList<>(), missingIncludes = new ArrayList<>();
    int[] skippedTransient = {0};
    try {
      emit(id, "PREPARING", 0);
      if (recoveryRequired())
        throw new OperationFailure(
            "RESTORE_RECOVERY_REQUIRED",
            "PREPARING",
            "Restore recovery must finish before another backup can start.",
            false);
      if (!service.stopped()) {
        if (!paperConnected.getAsBoolean())
          throw new OperationFailure(
              "PAPER_OFFLINE",
              "COORDINATING_PAPER",
              "An online snapshot requires an authenticated Paper agent.",
              true);
        emit(id, "COORDINATING_PAPER", 0);
        lease = leases.prepare(device, automatic);
      }

      emit(id, "PREFLIGHT", 0);
      Map<String, Object> preflight = preflight(() -> true);
      if (!Boolean.TRUE.equals(preflight.get("backupRootWritable")))
        throw new OperationFailure(
            "BACKUP_STORAGE_UNWRITABLE",
            "PREFLIGHT",
            "The configured backup directory is not writable by the Host.",
            false);
      Object unreadable = preflight.get("unreadableDurableCount");
      if (unreadable instanceof Number n && n.longValue() > 0) {
        String example = null;
        Object examples = preflight.get("unreadableDurableExamples");
        if (examples instanceof List<?> list && !list.isEmpty()) example = String.valueOf(list.get(0));
        throw new OperationFailure(
            "BACKUP_SOURCE_UNREADABLE",
            "PREFLIGHT",
            "The Host cannot read a required server file after Paper save preparation.",
            false,
            example);
      }
      Object symlinkValue = preflight.get("symlinkIssues");
      if (symlinkValue instanceof List<?> symlinks && !symlinks.isEmpty())
        throw new OperationFailure(
            "BACKUP_SYMLINK_REJECTED",
            "PREFLIGHT",
            "A configured backup path contains a symbolic link that is not allowed.",
            false,
            String.valueOf(symlinks.get(0)));
      Object missingValue = preflight.get("missingIncludes");
      if (missingValue instanceof List<?> list)
        for (Object item : list) {
          String value = String.valueOf(item);
          if (missingIncludes.size() < 16) missingIncludes.add(value);
        }
      if (!missingIncludes.isEmpty())
        warnings.add("Configured include roots are missing: " + String.join(", ", missingIncludes));

      PaperSaveLease.Lease active = lease;
      long[] sourceBytes = {0};
      int[] entries = {0};
      emit(id, "ARCHIVING", 0);
      try (var stream =
              Files.newOutputStream(
                  partial, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
          var zip = new ZipOutputStream(new BufferedOutputStream(stream))) {
        zip.setLevel(Deflater.BEST_SPEED);
        for (String include : config.backups().include()) {
          Path source = root.resolve(include);
          if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) continue;
          Files.walkFileTree(
              source,
              EnumSet.noneOf(FileVisitOption.class),
              64,
              new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                    throws IOException {
                  check();
                  String relative = relative(dir);
                  if (LiveSnapshotPolicy.volatileExcluded(config, relative)) {
                    skippedTransient[0]++;
                    return FileVisitResult.SKIP_SUBTREE;
                  }
                  if (Files.isSymbolicLink(dir))
                    throw new OperationFailure(
                        "BACKUP_SYMLINK_REJECTED",
                        "ARCHIVING",
                        "A symbolic link was encountered in durable snapshot data.",
                        false,
                        relative);
                  return allowed(relative) ? FileVisitResult.CONTINUE : FileVisitResult.SKIP_SUBTREE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                  check();
                  String name = relative(file);
                  if (LiveSnapshotPolicy.volatileExcluded(config, name)) {
                    skippedTransient[0]++;
                    return FileVisitResult.CONTINUE;
                  }
                  if (!allowed(name)) return FileVisitResult.CONTINUE;
                  if (Files.isSymbolicLink(file))
                    throw new OperationFailure(
                        "BACKUP_SYMLINK_REJECTED",
                        "ARCHIVING",
                        "A symbolic link was encountered in durable snapshot data.",
                        false,
                        name);
                  if (!attrs.isRegularFile()) return FileVisitResult.CONTINUE;
                  if (++entries[0] > 100000)
                    throw new OperationFailure(
                        "BACKUP_ENTRY_LIMIT",
                        "ARCHIVING",
                        "The live snapshot exceeded the configured entry safety limit.",
                        false);

                  InputStream opened = openDurable(file, name);
                  try (var in = opened) {
                    zip.putNextEntry(new ZipEntry(name));
                    byte[] buffer = new byte[65536];
                    int n;
                    try {
                      while ((n = in.read(buffer)) >= 0) {
                        check();
                        sourceBytes[0] += n;
                        if (sourceBytes[0] > config.backups().maximumBytes())
                          throw new OperationFailure(
                              "BACKUP_SIZE_LIMIT",
                              "ARCHIVING",
                              "The live snapshot exceeded the configured size safety limit.",
                              false);
                        zip.write(buffer, 0, n);
                        emit(id, "ARCHIVING", sourceBytes[0]);
                      }
                    } catch (NoSuchFileException failure) {
                      throw new OperationFailure(
                          "BACKUP_SOURCE_DISAPPEARED",
                          "ARCHIVING",
                          "A required server file disappeared while it was being archived.",
                          true,
                          name,
                          failure);
                    } catch (AccessDeniedException failure) {
                      throw new OperationFailure(
                          "BACKUP_SOURCE_UNREADABLE",
                          "ARCHIVING",
                          "The Host lost read access to a required server file while archiving it.",
                          false,
                          name,
                          failure);
                    }
                    zip.closeEntry();
                  }
                  return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException failure) {
                  String name = relative(file);
                  if (LiveSnapshotPolicy.volatileExcluded(config, name)) {
                    skippedTransient[0]++;
                    return FileVisitResult.CONTINUE;
                  }
                  throw new OperationFailure(
                      failure instanceof NoSuchFileException
                          ? "BACKUP_SOURCE_DISAPPEARED"
                          : "BACKUP_SOURCE_UNREADABLE",
                      "ARCHIVING",
                      failure instanceof NoSuchFileException
                          ? "A required server file disappeared during the live snapshot."
                          : "The Host cannot read a required server path during the live snapshot.",
                      failure instanceof NoSuchFileException,
                      name,
                      failure);
                }

                private String relative(Path path) {
                  return root.relativize(path).toString().replace(File.separatorChar, '/');
                }

                private void check() throws IOException {
                  if (Thread.currentThread().isInterrupted()) throw new IOException("Backup cancelled");
                  if (active != null) active.check();
                }
              });
        }
      } finally {
        if (lease != null) {
          lease.close();
          lease = null;
        }
      }

      try (var channel = FileChannel.open(partial, StandardOpenOption.WRITE)) {
        channel.force(true);
      }
      Files.move(partial, archive, StandardCopyOption.ATOMIC_MOVE);
      emit(id, "HASHING", Files.size(archive));
      String hash = fileHash(archive);
      boolean offsite = false;
      String error = "";
      if (config.backups().rcloneRemote() != null && !config.backups().rcloneRemote().isBlank()) {
        emit(id, "UPLOADING", Files.size(archive));
        try {
          upload(archive);
          offsite = true;
        } catch (Exception e) {
          error = "Off-site upload failed; local archive is intact.";
          warnings.add(error);
        }
      }
      emit(id, "FINALIZING", Files.size(archive));
      Metadata metadata =
          new Metadata(
              id,
              Instant.now().toString(),
              "COMPLETE",
              Files.size(archive),
              hash,
              true,
              offsite,
              implementationVersion(),
              (System.nanoTime() - started) / 1000000,
              device == null ? "Local schedule" : device.name(),
              automatic,
              emergency,
              error,
              List.copyOf(warnings.subList(0, Math.min(warnings.size(), 16))),
              skippedTransient[0],
              List.copyOf(missingIncludes));
      AtomicFiles.writeUtf8(directory.resolve(id + ".json"), gson.toJson(metadata));
      retention();
      emit(id, "COMPLETE", metadata.bytes);
      return metadata;
    } catch (Exception e) {
      try {
        Files.deleteIfExists(partial);
      } catch (IOException cleanup) {
        e.addSuppressed(cleanup);
      }
      emit(id, "FAILED", 0);
      throw e;
    } finally {
      if (lease != null) lease.close();
      operationLock.unlock();
    }
  }

  private InputStream openDurable(Path file, String relative) {
    for (int attempt = 0; attempt < 2; attempt++) {
      try {
        return Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS);
      } catch (NoSuchFileException failure) {
        if (attempt == 0) {
          try {
            Thread.sleep(25L);
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
          }
          continue;
        }
        throw new OperationFailure(
            "BACKUP_SOURCE_DISAPPEARED",
            "ARCHIVING",
            "A required server file disappeared before it could be archived.",
            true,
            relative,
            failure);
      } catch (AccessDeniedException failure) {
        throw new OperationFailure(
            "BACKUP_SOURCE_UNREADABLE",
            "ARCHIVING",
            "The Host cannot read a required server file.",
            false,
            relative,
            failure);
      } catch (IOException failure) {
        throw new OperationFailure(
            "BACKUP_SOURCE_CHANGED",
            "ARCHIVING",
            "A required server file changed in a way that prevented a consistent snapshot.",
            true,
            relative,
            failure);
      }
    }
    throw new OperationFailure(
        "BACKUP_SOURCE_DISAPPEARED",
        "ARCHIVING",
        "A required server file disappeared before it could be archived.",
        true,
        relative);
  }

  private static String implementationVersion() {
    String version = BackupManager.class.getPackage().getImplementationVersion();
    return version == null || version.isBlank() ? "development" : "PlexonPanel " + version;
  }

  private void upload(Path archive) throws Exception {
    var b = config.backups();
    Process p =
        new ProcessBuilder(
                b.rcloneExecutable(),
                "copyto",
                archive.toString(),
                b.rcloneRemote().replaceAll("/+$", "") + "/" + archive.getFileName(),
                "--config",
                b.rcloneConfig(),
                "--transfers",
                "1",
                "--checkers",
                "1",
                "--log-level",
                "ERROR")
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start();
    try {
      if (!p.waitFor(10, java.util.concurrent.TimeUnit.MINUTES) || p.exitValue() != 0)
        throw new IOException("Off-site provider failed");
    } finally {
      if (p.isAlive()) p.destroyForcibly();
    }
  }

  public synchronized Map<String, Object> prepareRestore(String id, DeviceRegistry.Device device)
      throws Exception {
    if (!config.backups().restoreEnabled() || !device.role().equals("Owner"))
      throw new SecurityException("OWNER_REQUIRED");
    metadata(id);
    if (!service.stopped()) throw new SecurityException("SERVER_MUST_BE_STOPPED");
    grants.entrySet().removeIf(e -> e.getValue().expiry < System.currentTimeMillis());
    if (grants.size() > 8) throw new SecurityException("BUSY");
    String token = UUID.randomUUID().toString();
    grants.put(token, new RestoreGrant(id, device.deviceId(), System.currentTimeMillis() + 60000));
    return Map.of(
        "confirmationToken",
        token,
        "serverName",
        config.serverName(),
        "expiresInSeconds",
        60,
        "message",
        "An emergency backup will be made before replacing any stopped-server files.");
  }

  public Map<String, Object> restore(
      String id, String token, String serverName, DeviceRegistry.Device device) throws Exception {
    RestoreGrant grant;
    synchronized (this) {
      grant = grants.remove(token);
    }
    if (!config.backups().restoreEnabled()
        || !device.role().equals("Owner")
        || grant == null
        || !grant.backupId.equals(id)
        || !grant.deviceId.equals(device.deviceId())
        || grant.expiry < System.currentTimeMillis()
        || !config.serverName().equals(serverName))
      throw new SecurityException("CONFIRMATION_REQUIRED");
    if (!operationLock.tryLock()) throw new SecurityException("BUSY");
    String restoreId = UUID.randomUUID().toString();
    Path stage = root.resolve(".plexonpanel-restore-" + restoreId),
        rollback = root.resolve(".plexonpanel-rollback-" + restoreId),
        journal = directory.resolve("restore-journal.json");
    try {
      if (!service.stopped() || paperConnected.getAsBoolean())
        throw new SecurityException("SERVER_MUST_BE_STOPPED");
      if (recoveryRequired()) throw new IllegalStateException("Restore recovery required");
      Metadata selected = metadata(id);
      Path archive = archive(id);
      if (!fileHash(archive).equals(selected.sha256))
        throw new IOException("Archive SHA-256 mismatch");
      Metadata emergency = create(device, false, true);
      emit(id, "extracting", 0);
      Files.createDirectory(stage);
      Files.createDirectory(rollback);
      List<String> targets = extract(archive, stage);
      if (targets.isEmpty()) throw new IOException("Empty restore archive");
      List<String> replaced = new ArrayList<>();
      Set<String> existed = new HashSet<>();
      for (String target : targets)
        if (Files.exists(checkedDestination(target), LinkOption.NOFOLLOW_LINKS))
          existed.add(target);
      // Persist original existence before any rename. Recovery is idempotent even if rollback
      // itself crashes.
      AtomicFiles.writeUtf8(
          journal,
          gson.toJson(
              Map.of(
                  "restoreId",
                  restoreId,
                  "targets",
                  targets,
                  "existed",
                  existed,
                  "emergencyBackupId",
                  emergency.backupId)));
      try {
        for (String target : targets) {
          if (!service.stopped() || paperConnected.getAsBoolean())
            throw new IOException("Server started during restore");
          Path destination = checkedDestination(target),
              saved = rollback.resolve(target),
              incoming = stage.resolve(target);
          Files.createDirectories(saved.getParent());
          Files.createDirectories(destination.getParent());
          if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS))
            Files.move(destination, saved, StandardCopyOption.ATOMIC_MOVE);
          Files.move(incoming, destination, StandardCopyOption.ATOMIC_MOVE);
          replaced.add(target);
          emit(id, "restoring", replaced.size());
        }
      } catch (Exception error) {
        rollback(stage, rollback, targets, existed);
        Files.deleteIfExists(journal);
        throw error;
      }
      Files.delete(journal);
      deleteTree(rollback);
      deleteTree(stage);
      emit(id, "restored", selected.bytes);
      return Map.of(
          "backupId",
          id,
          "emergencyBackupId",
          emergency.backupId,
          "state",
          "stopped",
          "message",
          "Restore complete. Start the server after reviewing the result.");
    } finally {
      try {
        if (!Files.exists(journal)) {
          deleteTree(stage);
          deleteTree(rollback);
        }
      } finally {
        operationLock.unlock();
      }
    }
  }

  /** Local CLI recovery after a host crash; never exposes a shell to the dashboard. */
  public void recover() throws Exception {
    if (!service.stopped()) throw new IllegalStateException("Stop Paper before recovery");
    Path journal = directory.resolve("restore-journal.json");
    if (!Files.exists(journal)) return;
    JsonObject j = JsonParser.parseString(Files.readString(journal)).getAsJsonObject();
    String id = j.get("restoreId").getAsString();
    UUID.fromString(id);
    Path stage = root.resolve(".plexonpanel-restore-" + id),
        saved = root.resolve(".plexonpanel-rollback-" + id);
    List<String> targets = new ArrayList<>();
    for (var v : j.getAsJsonArray("targets")) targets.add(v.getAsString());
    if (!j.has("existed") || !j.get("existed").isJsonArray())
      throw new IOException("Recovery journal lacks original path state; local review required");
    Set<String> existed = new HashSet<>();
    for (var v : j.getAsJsonArray("existed")) existed.add(v.getAsString());
    rollback(stage, saved, targets, existed);
    Files.delete(journal);
  }

  void rollback(Path stage, Path saved, List<String> targets, Set<String> existed)
      throws IOException {
    for (String target : targets) {
      Path destination = checkedDestination(target),
          previous = saved.resolve(target),
          incoming = stage.resolve(target);
      if (Files.exists(previous, LinkOption.NOFOLLOW_LINKS)) {
        deleteTree(destination);
        Files.createDirectories(destination.getParent());
        Files.move(previous, destination, StandardCopyOption.ATOMIC_MOVE);
      } else if (!existed.contains(target) && !Files.exists(incoming, LinkOption.NOFOLLOW_LINKS))
        deleteTree(destination);
    }
    deleteTree(stage);
    deleteTree(saved);
  }

  public List<String> extract(Path archive, Path stage) throws IOException {
    Set<String> targets = new TreeSet<>(), names = new HashSet<>();
    long bytes = 0;
    int count = 0;
    try (var zip =
        new ZipInputStream(
            new BufferedInputStream(Files.newInputStream(archive, LinkOption.NOFOLLOW_LINKS)))) {
      ZipEntry entry;
      while ((entry = zip.getNextEntry()) != null) {
        String name = entry.getName();
        if (!allowed(name)
            || name.chars().anyMatch(Character::isISOControl)
            || name.contains("//")
            || name.startsWith("/")
            || name.indexOf('\\') >= 0
            || name.indexOf('%') >= 0
            || ++count > 100000
            || !names.add(name)) throw new IOException("Unsafe archive entry");
        Path relative = Path.of(name);
        if (relative.isAbsolute()
            || !relative.normalize().equals(relative)
            || relative.getNameCount() < 1) throw new IOException("Unsafe archive path");
        for (Path part : relative)
          if (part.toString().equals("..")
              || part.toString().equals(".")
              || part.toString().contains(":")) throw new IOException("Archive traversal rejected");
        Path destination = stage.resolve(relative).normalize();
        if (!destination.startsWith(stage)) throw new IOException("ZIP-slip rejected");
        if (entry.isDirectory()) {
          Files.createDirectories(destination);
          continue;
        }
        Files.createDirectories(destination.getParent());
        try (var out =
            Files.newOutputStream(
                destination, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
          byte[] buffer = new byte[65536];
          int n;
          while ((n = zip.read(buffer)) >= 0) {
            bytes += n;
            if (bytes > config.backups().maximumBytes())
              throw new IOException("Expanded archive exceeds limit");
            out.write(buffer, 0, n);
          }
        }
        String top = relative.getName(0).toString();
        if (top.equals("plugins")) {
          if (relative.getNameCount() < 2) throw new IOException("Plugin root cannot be replaced");
          targets.add("plugins/" + relative.getName(1));
        } else targets.add(top);
      }
    }
    return new ArrayList<>(targets);
  }

  private Path checkedDestination(String name) throws IOException {
    if (!allowed(name)) throw new IOException("Restore target denied");
    Path relative = Path.of(name);
    if (relative.isAbsolute() || !relative.normalize().equals(relative))
      throw new IOException("Restore path denied");
    Path current = root;
    for (Path part : relative) {
      current = current.resolve(part);
      if (Files.isSymbolicLink(current)) throw new IOException("Symlink restore target denied");
    }
    if (!current.startsWith(root)) throw new IOException("Restore traversal denied");
    return current;
  }

  private boolean allowed(String name) {
    return LiveSnapshotPolicy.allowed(config, name);
  }

  public Path archive(String id) throws IOException {
    UUID.fromString(id);
    Path p = directory.resolve(id + ".zip");
    if (!Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(p))
      throw new IOException("Backup archive unavailable");
    return p;
  }

  public void delete(String id) throws IOException {
    if (!operationLock.tryLock()) throw new SecurityException("BUSY");
    try {
      if (metadata(id).emergency)
        throw new IOException("Emergency backups require local removal after verification");
      Files.deleteIfExists(archive(id));
      Files.deleteIfExists(directory.resolve(id + ".json"));
    } finally {
      operationLock.unlock();
    }
  }

  private void retention() throws IOException {
    int kept = 0;
    for (Metadata m : list()) {
      if (m.emergency) continue;
      if (++kept > config.backups().retentionCount()) {
        Files.deleteIfExists(directory.resolve(m.backupId + ".zip"));
        Files.deleteIfExists(directory.resolve(m.backupId + ".json"));
      }
    }
  }

  private void emit(String id, String phase, long bytes) {
    long now = System.currentTimeMillis();
    if (now - progressAt < 1000 && !Set.of("complete", "failed", "restored").contains(phase))
      return;
    progressAt = now;
    progress.accept(
        Map.of(
            "backupId",
            id,
            "phase",
            phase,
            "bytes",
            bytes,
            "capturedAt",
            Instant.now().toString()));
  }

  public static String fileHash(Path path) throws IOException {
    try {
      var digest = MessageDigest.getInstance("SHA-256");
      try (var in = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
        byte[] buffer = new byte[65536];
        int n;
        while ((n = in.read(buffer)) >= 0) digest.update(buffer, 0, n);
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static void deleteTree(Path path) throws IOException {
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return;
    Files.walkFileTree(
        path,
        new SimpleFileVisitor<>() {
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
              throws IOException {
            Files.delete(file);
            return FileVisitResult.CONTINUE;
          }

          public FileVisitResult postVisitDirectory(Path dir, IOException error)
              throws IOException {
            if (error != null) throw error;
            Files.delete(dir);
            return FileVisitResult.CONTINUE;
          }
        });
  }
}
