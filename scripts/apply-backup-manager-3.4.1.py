from pathlib import Path
import re

path = Path('host-agent/src/main/java/io/github/zpkdxgames/plexonpanel/host/BackupManager.java')
text = path.read_text()

text = text.replace(
    'import com.google.gson.*;\nimport io.github.zpkdxgames.plexonpanel.security.DeviceRegistry;',
    'import com.google.gson.*;\nimport io.github.zpkdxgames.plexonpanel.control.OperationFailure;\nimport io.github.zpkdxgames.plexonpanel.security.DeviceRegistry;'
)

old_record = '''  public record Metadata(
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
      String error) {}'''
new_record = '''  public record Metadata(
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
      List<String> missingIncludeWarnings) {}'''
assert old_record in text
text = text.replace(old_record, new_record)

anchor = '''  public boolean recoveryRequired() {
    return Files.exists(directory.resolve("restore-journal.json"));
  }
'''
addition = anchor + '''
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
'''
assert text.count(anchor) == 1
text = text.replace(anchor, addition)

start = text.index('  public Metadata create(DeviceRegistry.Device device, boolean automatic, boolean emergency)')
end = text.index('  private void upload(Path archive) throws Exception {', start)
replacement = r'''  public Metadata create(DeviceRegistry.Device device, boolean automatic, boolean emergency)
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

'''
text = text[:start] + replacement + text[end:]

old_allowed = '''  private boolean allowed(String name) {
    String normalized = name.replace('\\\\', '/');
    String[] parts = normalized.split("/", -1);
    if (parts.length == 0 || !config.backups().include().contains(parts[0])) return false;
    for (String part : parts) {
      String low = part.toLowerCase(Locale.ROOT);
      if (low.equals("..")
          || low.equals(".")
          || low.startsWith(".")
          || low.equals("plexonpanel")
          || low.equals("plexonpanel-host")
          || low.equals("logs")
          || low.contains("rclone")
          || low.endsWith(".key")
          || low.endsWith(".pem")
          || low.endsWith(".db")
          || low.endsWith(".sqlite")
          || low.endsWith(".mv.db")) return false;
    }
    return true;
  }'''
new_allowed = '''  private boolean allowed(String name) {
    return LiveSnapshotPolicy.allowed(config, name);
  }'''
assert old_allowed in text
text = text.replace(old_allowed, new_allowed)

path.write_text(text)
print('BackupManager 3.4.1 reliability patch applied')
