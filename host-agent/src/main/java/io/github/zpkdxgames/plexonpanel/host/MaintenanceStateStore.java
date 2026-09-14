package io.github.zpkdxgames.plexonpanel.host;

import com.google.gson.*;
import io.github.zpkdxgames.plexonpanel.util.AtomicFiles;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Durable scheduler claims and Host-authoritative destructive-job state. */
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

  /** Durable countdown state kept separately so the public job contract remains stable. */
  public record Countdown(String jobId, String deadline, List<Integer> consumedWarnings) {
    public Countdown {
      consumedWarnings =
          consumedWarnings == null ? List.of() : List.copyOf(consumedWarnings);
    }
  }

  private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
  private static final Set<String> TERMINAL_PHASES = Set.of("COMPLETED", "DEGRADED", "FAILED");
  private static final Set<String> PRE_DESTRUCTIVE_PHASES =
      Set.of("QUEUED", "PREFLIGHT", "COUNTDOWN", "FINAL_SAVE");
  private static final Set<String> AMBIGUOUS_DESTRUCTIVE_PHASES =
      Set.of(
          "STOPPING_SERVER",
          "WAITING_FOR_STOP",
          "ARCHIVING",
          "VERIFYING_LOCAL",
          "UPLOADING_REMOTE",
          "VERIFYING_REMOTE",
          "STARTING_SERVER",
          "VERIFYING_STARTUP");

  private final Path directory, claimsFile, activeFile, historyFile, countdownFile, recoveryFile;

  public MaintenanceStateStore(Path dataDirectory) throws IOException {
    directory = dataDirectory.resolve("maintenance").toAbsolutePath().normalize();
    claimsFile = directory.resolve("claims.json");
    activeFile = directory.resolve("active-job.json");
    historyFile = directory.resolve("history.jsonl");
    countdownFile = directory.resolve("countdown.json");
    recoveryFile = directory.resolve("countdown-recovery.jsonl");
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
    return begin(kind, occurrence, automatic, initialPhase, "", automatic ? "Host schedule" : "Local host");
  }

  public synchronized Job begin(
      String kind,
      Instant occurrence,
      boolean automatic,
      String initialPhase,
      String requesterDeviceId,
      String requesterDeviceName)
      throws IOException {
    Job existing = blocking();
    if (existing != null)
      throw new IllegalStateException(
          existing.restartRecoveryRequired() || "RECOVERY_REQUIRED".equals(existing.phase())
              ? "RECOVERY_REQUIRED"
              : "BUSY");
    String now = Instant.now().toString();
    Job job =
        new Job(
            UUID.randomUUID().toString(),
            requireToken(kind, "kind"),
            safe(requesterDeviceId),
            safe(requesterDeviceName),
            normalizePhase(initialPhase),
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
    Files.deleteIfExists(countdownFile);
    return job;
  }

  public synchronized Countdown beginCountdown(Job job, int durationSeconds, Instant now)
      throws IOException {
    if (durationSeconds < 0 || durationSeconds > 86_400)
      throw new IllegalArgumentException("Invalid countdown duration");
    requireCurrent(job);
    if (!"COUNTDOWN".equals(job.phase())) throw new IOException("COUNTDOWN_STATE_INVALID");
    Countdown countdown =
        new Countdown(job.jobId(), now.plusSeconds(durationSeconds).toString(), List.of());
    AtomicFiles.writeUtf8(countdownFile, GSON.toJson(countdown));
    return countdown;
  }

  public synchronized Countdown countdown(Job job) throws IOException {
    requireCurrent(job);
    if (!"COUNTDOWN".equals(job.phase())) throw new IOException("COUNTDOWN_STATE_INVALID");
    if (!Files.isRegularFile(countdownFile, LinkOption.NOFOLLOW_LINKS)
        || Files.isSymbolicLink(countdownFile)
        || Files.size(countdownFile) > 32_768)
      throw new IOException("COUNTDOWN_STATE_MISSING");
    Countdown countdown = GSON.fromJson(Files.readString(countdownFile), Countdown.class);
    if (countdown == null || !job.jobId().equals(countdown.jobId()))
      throw new IOException("COUNTDOWN_STATE_INVALID");
    Instant.parse(countdown.deadline());
    for (Integer warning : countdown.consumedWarnings())
      if (warning == null || warning < 0 || warning > 86_400)
        throw new IOException("COUNTDOWN_STATE_INVALID");
    return countdown;
  }

  public synchronized Countdown consumeWarning(Job job, int warningSeconds) throws IOException {
    Countdown current = countdown(job);
    LinkedHashSet<Integer> consumed = new LinkedHashSet<>(current.consumedWarnings());
    consumed.add(warningSeconds);
    Countdown next = new Countdown(job.jobId(), current.deadline(), List.copyOf(consumed));
    AtomicFiles.writeUtf8(countdownFile, GSON.toJson(next));
    return next;
  }

  public synchronized void recordCountdownRecovery(Job job, String code, int warningSeconds)
      throws IOException {
    Map<String, Object> entry = new LinkedHashMap<>();
    entry.put("timestamp", Instant.now().toString());
    entry.put("jobId", job.jobId());
    entry.put("kind", job.kind());
    entry.put("code", requireToken(code, "recovery code"));
    if (warningSeconds > 0) entry.put("warningSeconds", warningSeconds);
    Files.writeString(
        recoveryFile,
        GSON.toJson(entry) + System.lineSeparator(),
        StandardOpenOption.CREATE,
        StandardOpenOption.APPEND,
        StandardOpenOption.WRITE);
    if (Files.size(recoveryFile) > 1_048_576L) rotateRecovery();
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
    String normalizedPhase = normalizePhase(phase);
    String now = Instant.now().toString();
    Job next =
        new Job(
            job.jobId(),
            job.kind(),
            safe(job.requesterDeviceId()),
            safe(job.requesterDeviceName()),
            normalizedPhase,
            normalizedPhase.equals(job.phase()) ? safe(job.phaseTimestamp()) : now,
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
    if (!"COUNTDOWN".equals(normalizedPhase)) Files.deleteIfExists(countdownFile);
    return next;
  }

  public synchronized Job finish(Job job, String result, String errorCode) throws IOException {
    return finish(job, result, errorCode, safeErrorMessage(errorCode));
  }

  public synchronized Job finish(Job job, String result, String errorCode, String errorMessage)
      throws IOException {
    String finalPhase =
        switch (safe(result)) {
          case "SUCCESS", "SKIPPED" -> "COMPLETED";
          case "DEGRADED" -> "DEGRADED";
          case "RECOVERY_REQUIRED" -> "RECOVERY_REQUIRED";
          default -> "FAILED";
        };
    return finishAs(job, finalPhase, result, errorCode, errorMessage);
  }

  private Job finishAs(
      Job job, String finalPhase, String result, String errorCode, String errorMessage)
      throws IOException {
    Objects.requireNonNull(job, "job");
    String now = Instant.now().toString();
    String normalizedPhase = normalizePhase(finalPhase);
    Job finished =
        new Job(
            job.jobId(),
            job.kind(),
            safe(job.requesterDeviceId()),
            safe(job.requesterDeviceName()),
            normalizedPhase,
            now,
            safe(job.scheduledOccurrence()),
            safe(job.startedAt()),
            now,
            "RECOVERY_REQUIRED".equals(normalizedPhase) ? "" : now,
            safe(result),
            safe(errorCode),
            boundedMessage(errorMessage),
            safe(job.backupId()),
            terminalProgress(normalizedPhase, job.progressPercent()),
            job.automatic(),
            job.hostStoppedServer(),
            job.localBackupVerified(),
            job.remoteBackupVerified(),
            "RECOVERY_REQUIRED".equals(normalizedPhase) || job.restartRecoveryRequired());
    write(finished);
    Files.deleteIfExists(countdownFile);
    if (terminal(finished)) appendHistory(finished);
    return finished;
  }

  /**
   * Classifies a job left non-terminal by a Host process restart. Before the stop boundary the job
   * is safely failed. At or after that boundary, the Host preserves an explicit recovery gate rather
   * than guessing whether Minecraft is safe to restart or whether archive mutation completed.
   */
  public synchronized Job recoverInterrupted() throws IOException {
    Job job = active();
    if (job == null || terminal(job) || "RECOVERY_REQUIRED".equals(job.phase())) return job;

    if (PRE_DESTRUCTIVE_PHASES.contains(job.phase()))
      return finishAs(
          job,
          "FAILED",
          "FAILED",
          "HOST_RESTART_INTERRUPTED",
          "The Host restarted before the destructive maintenance phase began.");

    if (job.localBackupVerified()
        && !job.remoteBackupVerified()
        && !job.hostStoppedServer()
        && !job.restartRecoveryRequired())
      return finishAs(
          job,
          "DEGRADED",
          "DEGRADED",
          "REMOTE_VERIFICATION_PENDING",
          "A verified local backup exists; off-site verification is incomplete.");

    if (job.hostStoppedServer()
        || job.restartRecoveryRequired()
        || AMBIGUOUS_DESTRUCTIVE_PHASES.contains(job.phase()))
      return finishAs(
          transition(job, job.phase(), job.backupId(), null, null, null, null, true),
          "RECOVERY_REQUIRED",
          "RECOVERY_REQUIRED",
          "HOST_RESTART_RECOVERY_REQUIRED",
          "The Host restarted after destructive maintenance may have begun; verify server state before retrying.");

    return finishAs(
        job,
        "FAILED",
        "FAILED",
        "HOST_RESTART_INTERRUPTED",
        "The Host restarted before the maintenance operation reached a recognized destructive phase.");
  }

  /** Returns the active job that must serialize any new destructive maintenance operation. */
  public synchronized Job blocking() throws IOException {
    Job job = active();
    return job != null
            && (!terminal(job)
                || job.restartRecoveryRequired()
                || "RECOVERY_REQUIRED".equals(job.phase()))
        ? job
        : null;
  }

  public synchronized Job recoveryRequired() throws IOException {
    Job job = active();
    return job != null
            && (job.restartRecoveryRequired() || "RECOVERY_REQUIRED".equals(job.phase()))
        ? job
        : null;
  }

  public synchronized Job active() throws IOException {
    if (!Files.exists(activeFile)) return null;
    if (!Files.isRegularFile(activeFile, LinkOption.NOFOLLOW_LINKS)
        || Files.isSymbolicLink(activeFile)
        || Files.size(activeFile) > 65_536)
      throw new IOException("Invalid maintenance job journal");
    Job job = GSON.fromJson(Files.readString(activeFile), Job.class);
    if (job == null || job.jobId() == null) throw new IOException("Invalid maintenance job state");
    UUID.fromString(job.jobId());
    Job normalized = normalize(job);
    if (!normalized.equals(job)) write(normalized);
    return normalized;
  }

  public synchronized void markRecovered(Job job, String result) throws IOException {
    Job cleared =
        transition(
            job,
            "RECOVERY_REQUIRED",
            job.backupId(),
            null,
            null,
            null,
            null,
            false);
    finish(cleared, result, "SUCCESS".equals(result) ? "" : "RECOVERY_REQUIRED");
  }

  static boolean terminal(Job job) {
    return job != null && TERMINAL_PHASES.contains(safe(job.phase()));
  }

  private void requireCurrent(Job job) throws IOException {
    Job current = active();
    if (current == null || !current.jobId().equals(job.jobId()))
      throw new IOException("COUNTDOWN_STATE_INVALID");
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
    String started = safe(job.startedAt());
    String phaseTimestamp = safe(job.phaseTimestamp());
    if (phaseTimestamp.isEmpty()) phaseTimestamp = updated.isEmpty() ? started : updated;
    String phase = normalizePhase(job.phase());
    return new Job(
        job.jobId(),
        safe(job.kind()),
        safe(job.requesterDeviceId()),
        safe(job.requesterDeviceName()),
        phase,
        phaseTimestamp,
        safe(job.scheduledOccurrence()),
        started,
        updated,
        safe(job.completedAt()),
        safe(job.result()),
        safe(job.errorCode()),
        boundedMessage(job.errorMessage()),
        safe(job.backupId()),
        clampProgress(job.progressPercent()),
        job.automatic(),
        job.hostStoppedServer(),
        job.localBackupVerified(),
        job.remoteBackupVerified(),
        job.restartRecoveryRequired());
  }

  private static String normalizePhase(String value) {
    return switch (safe(value)) {
      case "" -> "QUEUED";
      case "COMPLETE" -> "COMPLETED";
      case "PREPARING" -> "FINAL_SAVE";
      case "STOPPING" -> "STOPPING_SERVER";
      case "UPLOADING" -> "UPLOADING_REMOTE";
      case "STARTING" -> "STARTING_SERVER";
      case "FINALIZING" -> "VERIFYING_REMOTE";
      default -> requireToken(value, "phase");
    };
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

  private static String boundedMessage(String value) {
    String message = safe(value).replace('\n', ' ').replace('\r', ' ').trim();
    return message.length() <= 320 ? message : message.substring(0, 320);
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

  private void rotateRecovery() throws IOException {
    Path old = directory.resolve("countdown-recovery.previous.jsonl");
    Files.deleteIfExists(old);
    try {
      Files.move(recoveryFile, old, StandardCopyOption.ATOMIC_MOVE);
    } catch (AtomicMoveNotSupportedException unsupported) {
      Files.move(recoveryFile, old, StandardCopyOption.REPLACE_EXISTING);
    }
  }
}
