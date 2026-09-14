package io.github.zpkdxgames.plexonpanel.host;

import com.google.gson.*;
import io.github.zpkdxgames.plexonpanel.util.AtomicFiles;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Durable scheduler claims and destructive-job state. */
public final class MaintenanceStateStore {
  public record Job(
      String jobId,
      String kind,
      String requesterDeviceId,
      String requesterDeviceName,
      String phase,
      String phaseTimestamp,
      String scheduledOccurrence,
      String startedAt,
      String updatedAt,
      String completedAt,
      String result,
      String errorCode,
      String errorMessage,
      String backupId,
      int progressPercent,
      boolean automatic,
      boolean hostStoppedServer,
      boolean localBackupVerified,
      boolean remoteBackupVerified,
      boolean restartRecoveryRequired) {}

  private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
  private static final Set<String> TERMINAL_PHASES =
      Set.of("COMPLETE", "COMPLETED", "DEGRADED", "FAILED");
  private static final Set<String> AMBIGUOUS_DESTRUCTIVE_PHASES =
      Set.of(
          "STOPPING_SERVER",
          "WAITING_FOR_STOP",
          "ARCHIVING",
          "VERIFYING_LOCAL",
          "UPLOADING_REMOTE",
          "VERIFYING_REMOTE",
          "STARTING_SERVER",
          "VERIFYING_STARTUP",
          "FINALIZING");

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
    return begin(kind, occurrence, automatic, initialPhase, "", "");
  }

  public synchronized Job begin(
      String kind,
      Instant occurrence,
      boolean automatic,
      String initialPhase,
      String requesterDeviceId,
      String requesterDeviceName)
      throws IOException {
    Job existing = active();
    if (existing != null && !terminal(existing))
      throw new IllegalStateException("RECOVERY_REQUIRED");
    String now = Instant.now().toString();
    Job job =
        new Job(
            UUID.randomUUID().toString(),
            requireToken(kind, "kind"),
            safe(requesterDeviceId),
            safe(requesterDeviceName),
            requireToken(initialPhase, "phase"),
            now,
            occurrence == null ? "" : occurrence.toString(),
            now,
            now,
            "",
            "",
            "",
            "",
            "",
            0,
            automatic,
            false,
            false,
            false,
            false);
    write(job);
    return job;
  }

  public synchronized Job update(Job job, String phase, String backupId) throws IOException {
    return transition(job, phase, backupId, null, null, null, null, null);
  }

  public synchronized Job transition(
      Job job,
      String phase,
      String backupId,
      Integer progressPercent,
      Boolean hostStoppedServer,
      Boolean localBackupVerified,
      Boolean remoteBackupVerified,
      Boolean restartRecoveryRequired)
      throws IOException {
    Objects.requireNonNull(job, "job");
    String now = Instant.now().toString();
    Job next =
        new Job(
            job.jobId(),
            job.kind(),
            safe(job.requesterDeviceId()),
            safe(job.requesterDeviceName()),
            requireToken(phase, "phase"),
            now,
            safe(job.scheduledOccurrence()),
            safe(job.startedAt()),
            now,
            "",
            "",
            "",
            "",
            backupId == null ? safe(job.backupId()) : safe(backupId),
            progressPercent == null ? job.progressPercent() : clampProgress(progressPercent),
            job.automatic(),
            hostStoppedServer == null ? job.hostStoppedServer() : hostStoppedServer,
            localBackupVerified == null ? job.localBackupVerified() : localBackupVerified,
            remoteBackupVerified == null ? job.remoteBackupVerified() : remoteBackupVerified,
            restartRecoveryRequired == null
                ? job.restartRecoveryRequired()
                : restartRecoveryRequired);
    write(next);
    return next;
  }

  public synchronized Job finish(Job job, String result, String errorCode) throws IOException {
    String finalPhase =
        switch (safe(result)) {
          case "SUCCESS", "SKIPPED" -> "COMPLETED";
          case "DEGRADED" -> "DEGRADED";
          case "RECOVERY_REQUIRED" -> "RECOVERY_REQUIRED";
          default -> "FAILED";
        };
    return finish(job, finalPhase, result, errorCode, safeErrorMessage(errorCode));
  }

  public synchronized Job finish(
      Job job, String finalPhase, String result, String errorCode, String errorMessage)
      throws IOException {
    Objects.requireNonNull(job, "job");
    String now = Instant.now().toString();
    Job finished =
        new Job(
            job.jobId(),
            job.kind(),
            safe(job.requesterDeviceId()),
            safe(job.requesterDeviceName()),
            requireToken(finalPhase, "phase"),
            now,
            safe(job.scheduledOccurrence()),
            safe(job.startedAt()),
            now,
            "RECOVERY_REQUIRED".equals(finalPhase) ? "" : now,
            safe(result),
            safe(errorCode),
            safe(errorMessage),
            safe(job.backupId()),
            terminalProgress(finalPhase, job.progressPercent()),
            job.automatic(),
            job.hostStoppedServer(),
            job.localBackupVerified(),
            job.remoteBackupVerified(),
            "RECOVERY_REQUIRED".equals(finalPhase) || job.restartRecoveryRequired());
    write(finished);
    if (terminal(finished)) appendHistory(finished);
    return finished;
  }

  /** Classifies a job left non-terminal by a Host process restart. */
  public synchronized Job recoverInterrupted() throws IOException {
    Job job = active();
    if (job == null || terminal(job) || "RECOVERY_REQUIRED".equals(job.phase())) return job;

    if (job.localBackupVerified()
        && !job.remoteBackupVerified()
        && !job.hostStoppedServer()
        && !job.restartRecoveryRequired()) {
      return finish(
          job,
          "DEGRADED",
          "DEGRADED",
          "REMOTE_VERIFICATION_PENDING",
          "A verified local backup exists; off-site verification is incomplete.");
    }

    if (job.hostStoppedServer()
        || job.restartRecoveryRequired()
        || AMBIGUOUS_DESTRUCTIVE_PHASES.contains(job.phase())) {
      String now = Instant.now().toString();
      Job recovery =
          new Job(
              job.jobId(),
              job.kind(),
              safe(job.requesterDeviceId()),
              safe(job.requesterDeviceName()),
              "RECOVERY_REQUIRED",
              now,
              safe(job.scheduledOccurrence()),
              safe(job.startedAt()),
              now,
              "",
              "INTERRUPTED",
              "HOST_RESTART_INTERRUPTED",
              "The Host restarted during an ambiguous destructive maintenance phase.",
              safe(job.backupId()),
              job.progressPercent(),
              job.automatic(),
              job.hostStoppedServer(),
              job.localBackupVerified(),
              job.remoteBackupVerified(),
              true);
      write(recovery);
      return recovery;
    }

    return finish(
        job,
        "FAILED",
        "FAILED",
        "HOST_RESTART_INTERRUPTED",
        "The Host restarted before the maintenance operation reached a destructive phase.");
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
    if (job == null || job.jobId() == null) throw new IOException("Invalid maintenance job state");
    UUID.fromString(job.jobId());
    return normalize(job);
  }

  public synchronized void markRecovered(Job job, String result) throws IOException {
    finish(job, result, "SUCCESS".equals(result) ? "" : "RECOVERY_REQUIRED");
  }

  static boolean terminal(Job job) {
    return job != null && TERMINAL_PHASES.contains(safe(job.phase()));
  }

  private void write(Job job) throws IOException {
    AtomicFiles.writeUtf8(activeFile, GSON.toJson(job));
  }

  private void appendHistory(Job job) throws IOException {
    Files.writeString(
        historyFile,
        GSON.toJson(job) + System.lineSeparator(),
        StandardOpenOption.CREATE,
        StandardOpenOption.APPEND,
        StandardOpenOption.WRITE);
    if (Files.size(historyFile) > 4 * 1024 * 1024) rotateHistory();
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

  private static Job normalize(Job job) {
    String updated = safe(job.updatedAt());
    String phaseTimestamp = safe(job.phaseTimestamp());
    if (phaseTimestamp.isEmpty()) phaseTimestamp = updated.isEmpty() ? safe(job.startedAt()) : updated;
    return new Job(
        job.jobId(),
        safe(job.kind()),
        safe(job.requesterDeviceId()),
        safe(job.requesterDeviceName()),
        safe(job.phase()),
        phaseTimestamp,
        safe(job.scheduledOccurrence()),
        safe(job.startedAt()),
        updated,
        safe(job.completedAt()),
        safe(job.result()),
        safe(job.errorCode()),
        safe(job.errorMessage()),
        safe(job.backupId()),
        clampProgress(job.progressPercent()),
        job.automatic(),
        job.hostStoppedServer(),
        job.localBackupVerified(),
        job.remoteBackupVerified(),
        job.restartRecoveryRequired());
  }

  private static int terminalProgress(String phase, int current) {
    return "COMPLETED".equals(phase) ? 100 : clampProgress(current);
  }

  private static int clampProgress(int value) {
    return Math.max(0, Math.min(100, value));
  }

  private static String safeErrorMessage(String code) {
    String safeCode = safe(code);
    return safeCode.isEmpty() ? "" : "Operation did not complete (" + safeCode + ").";
  }

  private static String requireToken(String value, String field) {
    String token = safe(value);
    if (!token.matches("[A-Z0-9_.-]{2,64}"))
      throw new IllegalArgumentException("Invalid maintenance " + field);
    return token;
  }

  private static String safe(String value) {
    return value == null ? "" : value;
  }

  private void rotateHistory() throws IOException {
    Path old = directory.resolve("history.previous.jsonl");
    Files.deleteIfExists(old);
    try {
      Files.move(historyFile, old, StandardCopyOption.ATOMIC_MOVE);
    } catch (AtomicMoveNotSupportedException unsupported) {
      Files.move(historyFile, old, StandardCopyOption.REPLACE_EXISTING);
    }
  }
}
