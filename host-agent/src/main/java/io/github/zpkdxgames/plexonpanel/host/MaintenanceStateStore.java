package io.github.zpkdxgames.plexonpanel.host;

import com.google.gson.*;
import io.github.zpkdxgames.plexonpanel.util.AtomicFiles;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Durable restart claims and destructive-job state. */
public final class MaintenanceStateStore {
  public record Job(
      String jobId,
      String kind,
      String phase,
      String scheduledOccurrence,
      String startedAt,
      String updatedAt,
      String completedAt,
      String result,
      String errorCode,
      String backupId,
      boolean automatic) {}

  private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
  private final Path directory, claimsFile, activeFile, historyFile;

  public MaintenanceStateStore(Path dataDirectory) throws IOException {
    directory = dataDirectory.resolve("maintenance").toAbsolutePath().normalize();
    claimsFile = directory.resolve("claims.json");
    activeFile = directory.resolve("active-job.json");
    historyFile = directory.resolve("history.jsonl");
    Files.createDirectories(directory);
  }

  public synchronized boolean claim(String scheduleId, Instant occurrence) throws IOException {
    String key = MaintenanceSchedule.occurrenceKey(scheduleId, occurrence);
    LinkedHashMap<String, String> claims = readClaims();
    if (claims.containsKey(key)) return false;
    claims.put(key, Instant.now().toString());
    while (claims.size() > 256) claims.remove(claims.keySet().iterator().next());
    AtomicFiles.writeUtf8(claimsFile, GSON.toJson(claims));
    return true;
  }

  public synchronized Job begin(
      String kind, Instant occurrence, boolean automatic, String initialPhase) throws IOException {
    Job existing = active();
    if (existing != null && !terminal(existing))
      throw new IllegalStateException("RECOVERY_REQUIRED");
    String now = Instant.now().toString();
    Job job =
        new Job(
            UUID.randomUUID().toString(),
            kind,
            initialPhase,
            occurrence == null ? "" : occurrence.toString(),
            now,
            now,
            "",
            "",
            "",
            "",
            automatic);
    write(job);
    return job;
  }

  public synchronized Job update(Job job, String phase, String backupId) throws IOException {
    Job next =
        new Job(
            job.jobId,
            job.kind,
            phase,
            job.scheduledOccurrence,
            job.startedAt,
            Instant.now().toString(),
            "",
            "",
            "",
            backupId == null ? job.backupId : backupId,
            job.automatic);
    write(next);
    return next;
  }

  public synchronized Job finish(Job job, String result, String errorCode) throws IOException {
    String now = Instant.now().toString();
    String phase =
        switch (result) {
          case "SUCCESS", "SKIPPED" -> "COMPLETE";
          case "DEGRADED" -> "DEGRADED";
          default -> "FAILED";
        };
    Job finished =
        new Job(
            job.jobId,
            job.kind,
            phase,
            job.scheduledOccurrence,
            job.startedAt,
            now,
            now,
            result,
            errorCode == null ? "" : errorCode,
            job.backupId,
            job.automatic);
    write(finished);
    Files.writeString(
        historyFile,
        GSON.toJson(finished) + System.lineSeparator(),
        StandardOpenOption.CREATE,
        StandardOpenOption.APPEND,
        StandardOpenOption.WRITE);
    if (Files.size(historyFile) > 4 * 1024 * 1024) rotateHistory();
    return finished;
  }

  public synchronized Job recoveryRequired() throws IOException {
    Job job = active();
    return job != null && !terminal(job) ? job : null;
  }

  public synchronized Job active() throws IOException {
    if (!Files.exists(activeFile)) return null;
    if (!Files.isRegularFile(activeFile, LinkOption.NOFOLLOW_LINKS)
        || Files.isSymbolicLink(activeFile)
        || Files.size(activeFile) > 32_768)
      throw new IOException("Invalid maintenance job journal");
    Job job = GSON.fromJson(Files.readString(activeFile), Job.class);
    if (job == null || job.jobId == null) throw new IOException("Invalid maintenance job state");
    UUID.fromString(job.jobId);
    return job;
  }

  public synchronized void markRecovered(Job job, String result) throws IOException {
    finish(job, result, result.equals("SUCCESS") ? "" : "RECOVERY_REQUIRED");
  }

  private void write(Job job) throws IOException {
    AtomicFiles.writeUtf8(activeFile, GSON.toJson(job));
  }

  private LinkedHashMap<String, String> readClaims() throws IOException {
    LinkedHashMap<String, String> result = new LinkedHashMap<>();
    if (!Files.exists(claimsFile)) return result;
    if (Files.isSymbolicLink(claimsFile) || Files.size(claimsFile) > 65_536)
      throw new IOException("Invalid schedule claim journal");
    JsonElement raw = JsonParser.parseString(Files.readString(claimsFile));
    if (!raw.isJsonObject()) throw new IOException("Invalid schedule claim journal");
    for (var entry : raw.getAsJsonObject().entrySet()) {
      if (result.size() >= 256 || !entry.getValue().isJsonPrimitive()) break;
      result.put(entry.getKey(), entry.getValue().getAsString());
    }
    return result;
  }

  private static boolean terminal(Job job) {
    return Set.of("COMPLETE", "DEGRADED", "FAILED").contains(job.phase);
  }

  private void rotateHistory() throws IOException {
    Path old = directory.resolve("history.previous.jsonl");
    Files.deleteIfExists(old);
    Files.move(historyFile, old, StandardCopyOption.ATOMIC_MOVE);
  }
}
