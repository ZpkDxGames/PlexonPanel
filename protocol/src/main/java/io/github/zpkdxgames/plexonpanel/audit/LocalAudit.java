package io.github.zpkdxgames.plexonpanel.audit;

import com.google.gson.*;
import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/**
 * Synchronous durable audit, called only on the bounded operations worker. Failures deny privileged
 * work.
 */
public final class LocalAudit {
  private final Path directory;
  private final int retentionDays;
  private boolean replayLoaded;
  private final Map<String, Long> recentRequests = new HashMap<>();
  private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();

  public LocalAudit(Path directory, int retentionDays) throws IOException {
    this.directory = directory.toAbsolutePath().normalize();
    this.retentionDays = Math.max(1, Math.min(retentionDays, 365));
    if (Files.isSymbolicLink(directory)) throw new IOException("Unsafe audit directory");
    Files.createDirectories(directory);
    if (!this.directory.equals(this.directory.toRealPath()))
      throw new IOException("Audit directory must not contain symlinks");
    try {
      Files.setPosixFilePermissions(
          directory, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
    } catch (UnsupportedOperationException ignored) {
    }
  }

  public synchronized void begin(Map<String, Object> entry) throws IOException {
    long now = System.currentTimeMillis();
    if (!replayLoaded) {
      long budget = 32 * 1024 * 1024;
      try (var paths = Files.list(directory)) {
        for (Path path :
            paths
                .filter(
                    p ->
                        p.getFileName().toString().matches("audit-[0-9-]+\\.jsonl")
                            && Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                .sorted(Comparator.reverseOrder())
                .limit(8)
                .toList()) {
          if (Files.size(path) > budget) break;
          budget -= Files.size(path);
          try (var lines = Files.newBufferedReader(path)) {
            String line;
            while ((line = lines.readLine()) != null) {
              if (line.length() > 8192) continue;
              try {
                var e = JsonParser.parseString(line).getAsJsonObject();
                long expiry = Instant.parse(value(e, "timestamp")).toEpochMilli() + 120000;
                if (value(e, "outcome").equals("STARTED") && expiry > now) {
                  if (recentRequests.size() >= 4096)
                    throw new IOException("Recent request ledger capacity exceeded");
                  recentRequests.put(value(e, "requestId"), expiry);
                }
              } catch (RuntimeException invalid) {
                /* Ignore incomplete log lines; complete intent entries are durable. */
              }
            }
          }
        }
      }
      replayLoaded = true;
    }
    recentRequests.entrySet().removeIf(e -> e.getValue() <= now);
    String id = String.valueOf(entry.get("requestId"));
    if (recentRequests.containsKey(id)) throw new SecurityException("DUPLICATE_REQUEST");
    if (recentRequests.size() >= 4096) throw new SecurityException("BUSY");
    append(entry);
    recentRequests.put(id, now + 120000);
  }

  public synchronized void append(Map<String, Object> entry) throws IOException {
    String day = LocalDate.now(ZoneOffset.UTC).toString();
    Path path = directory.resolve("audit-" + day + ".jsonl");
    if (Files.exists(path) && Files.size(path) > 8 * 1024 * 1024) {
      Files.move(
          path, directory.resolve("audit-" + day + "-" + System.currentTimeMillis() + ".jsonl"));
    }
    byte[] bytes = (gson.toJson(entry) + "\n").getBytes(StandardCharsets.UTF_8);
    if (bytes.length > 8192) throw new IOException("Audit entry exceeds bound");
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
      try {
        Files.createFile(
            path,
            java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")));
      } catch (FileAlreadyExistsException ignored) {
      }
    }
    try (var file =
        FileChannel.open(
            path,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            StandardOpenOption.APPEND,
            LinkOption.NOFOLLOW_LINKS)) {
      ByteBuffer buffer = ByteBuffer.wrap(bytes);
      while (buffer.hasRemaining()) file.write(buffer);
      file.force(true);
    }
  }

  public synchronized void clean() throws IOException {
    Instant oldest = Instant.now().minusSeconds(retentionDays * 86400L);
    try (var paths = Files.list(directory)) {
      for (Path p : paths.limit(10000).toList()) {
        if (p.getFileName().toString().matches("audit-[0-9-]+\\.jsonl")
            && Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)
            && Files.getLastModifiedTime(p).toInstant().isBefore(oldest)) Files.delete(p);
      }
    }
  }

  public Map<String, Object> list(String deviceId, boolean selfOnly, JsonObject filters)
      throws IOException {
    int page =
        (int)
            io.github.zpkdxgames.plexonpanel.control.JsonFields.integer(filters, "page", 0, 0, 99);
    String actor =
        io.github.zpkdxgames.plexonpanel.control.JsonFields.optional(filters, "actor", "", 64);
    String action =
        io.github.zpkdxgames.plexonpanel.control.JsonFields.optional(filters, "action", "", 64);
    String target =
        io.github.zpkdxgames.plexonpanel.control.JsonFields.optional(filters, "target", "", 128);
    String outcome =
        io.github.zpkdxgames.plexonpanel.control.JsonFields.optional(filters, "outcome", "", 32);
    String from =
        io.github.zpkdxgames.plexonpanel.control.JsonFields.optional(filters, "from", "", 40);
    String to = io.github.zpkdxgames.plexonpanel.control.JsonFields.optional(filters, "to", "", 40);
    if (!from.isEmpty()) Instant.parse(from);
    if (!to.isEmpty()) Instant.parse(to);
    PriorityQueue<JsonObject> newest =
        new PriorityQueue<>(Comparator.comparing(e -> value(e, "timestamp")));
    long budget = 32 * 1024 * 1024;
    boolean bounded = false;
    List<Path> paths;
    try (var stream = Files.list(directory)) {
      paths =
          stream
              .filter(
                  p ->
                      p.getFileName().toString().matches("audit-[0-9-]+\\.jsonl")
                          && Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
              .sorted(Comparator.reverseOrder())
              .limit(8)
              .toList();
    }
    for (Path path : paths) {
      if (Files.size(path) > budget) {
        bounded = true;
        break;
      }
      budget -= Files.size(path);
      try (var lines = Files.newBufferedReader(path)) {
        String line;
        while ((line = lines.readLine()) != null) {
          if (line.length() > 8192) continue;
          JsonObject e;
          try {
            e = JsonParser.parseString(line).getAsJsonObject();
          } catch (RuntimeException ignored) {
            continue;
          }
          if (selfOnly && !value(e, "deviceId").equals(deviceId)
              || !value(e, "actorLabel").contains(actor)
              || !value(e, "actionType").contains(action)
              || !value(e, "target").contains(target)
              || !value(e, "outcome").contains(outcome)
              || !from.isEmpty() && value(e, "timestamp").compareTo(from) < 0
              || !to.isEmpty() && value(e, "timestamp").compareTo(to) > 0) continue;
          newest.add(e);
          if (newest.size() > 10000) {
            newest.remove();
            bounded = true;
          }
        }
      }
    }
    List<JsonObject> entries = new ArrayList<>(newest);
    entries.sort(Comparator.comparing((JsonObject e) -> value(e, "timestamp")).reversed());
    int start = Math.min(entries.size(), page * 50), end = Math.min(entries.size(), start + 50);
    return Map.of(
        "entries",
        entries.subList(start, end),
        "page",
        page,
        "hasMore",
        end < entries.size(),
        "boundedWindow",
        bounded);
  }

  private static String value(JsonObject e, String key) {
    return e.has(key) && e.get(key).isJsonPrimitive() ? e.get(key).getAsString() : "";
  }
}
