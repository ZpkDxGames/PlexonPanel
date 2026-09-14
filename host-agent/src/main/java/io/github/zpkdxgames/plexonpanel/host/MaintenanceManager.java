package io.github.zpkdxgames.plexonpanel.host;

import com.google.gson.JsonElement;
import io.github.zpkdxgames.plexonpanel.audit.LocalAudit;
import io.github.zpkdxgames.plexonpanel.control.OperationFailure;
import io.github.zpkdxgames.plexonpanel.protocol.*;
import io.github.zpkdxgames.plexonpanel.security.DeviceRegistry;
import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.*;

/** Host-authoritative calendar scheduler and destructive-operation orchestrator. */
public final class MaintenanceManager implements AutoCloseable {
  private final HostConfig config;
  private final Path settingsPath;
  private final MaintenanceStateStore state;
  private final MaintenanceCountdownStore countdownState;
  private final MaintenanceCountdown backupCountdown = new MaintenanceCountdown();
  private final FullRestorePointManager fullBackups;
  private final FullBackupPreflight fullBackupPreflight;
  private final SystemdService service;
  private final PaperMaintenanceLink paperLink;
  private final BooleanSupplier paperConnected;
  private final LongSupplier paperRevision;
  private final RconCommandChannel commandChannel;
  private final ReentrantLock operationLock;
  private final LocalAudit audit;
  private final MessageSink events;
  private final ScheduledExecutorService clock = Executors.newSingleThreadScheduledExecutor();
  private final ThreadPoolExecutor worker =
      new ThreadPoolExecutor(
          1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(2), new ThreadPoolExecutor.AbortPolicy());
  private final AtomicBoolean closed = new AtomicBoolean();
  private volatile MaintenanceSettings settings;

  public MaintenanceManager(
      HostConfig config,
      SystemdService service,
      PaperMaintenanceLink paperLink,
      BooleanSupplier paperConnected,
      LongSupplier paperRevision,
      ReentrantLock operationLock,
      LocalAudit audit,
      MessageSink events,
      FullRestorePointManager fullBackups)
      throws IOException {
    this.config = config;
    this.service = service;
    this.paperLink = paperLink;
    this.paperConnected = paperConnected;
    this.paperRevision = paperRevision;
    this.commandChannel = new RconCommandChannel(config.commandChannel());
    this.operationLock = operationLock;
    this.audit = audit;
    this.events = events;
    this.fullBackups = fullBackups;
    this.fullBackupPreflight = new FullBackupPreflight(config, fullBackups);
    Path data = Path.of(config.dataDirectory()).toAbsolutePath().normalize();
    this.settingsPath = data.resolve("maintenance-settings.json");
    this.state = new MaintenanceStateStore(data);
    this.countdownState = new MaintenanceCountdownStore(data);
    this.settings = MaintenanceSettings.load(settingsPath);
    if (!Files.exists(settingsPath)) this.settings.save(settingsPath);
  }

  public void start() {
    try {
      MaintenanceStateStore.Job active = state.active();
      if (resumableBackupCountdown(active)) {
        publish(
            "maintenance.countdown.resuming",
            Map.of("jobId", active.jobId(), "kind", active.kind(), "phase", active.phase()));
        worker.execute(() -> runFull(active, null, active.automatic(), false));
      } else {
        MaintenanceStateStore.Job recovered = state.recoverInterrupted();
        if (recovered != null && "RECOVERY_REQUIRED".equals(recovered.phase()))
          publish(
              "maintenance.recovery.required",
              Map.of(
                  "jobId", recovered.jobId(),
                  "kind", recovered.kind(),
                  "phase", recovered.phase(),
                  "errorCode", recovered.errorCode()));
      }
    } catch (IOException | RejectedExecutionException error) {
      throw new IllegalStateException("MAINTENANCE_STATE_RECOVERY_FAILED", error);
    }
    // Legacy scheduled maintenance remains temporarily for migration compatibility. Step 5 removes
    // recurring full-backup coordination; manual jobs already use the Host-owned durable state below.
    clock.scheduleWithFixedDelay(this::tickSafely, 2, 15, TimeUnit.SECONDS);
  }

  public MaintenanceSettings settings() {
    return settings;
  }

  public synchronized MaintenanceSettings updateSettings(JsonElement value) throws IOException {
    MaintenanceSettings next = MaintenanceSettings.fromJson(value);
    next.save(settingsPath);
    settings = next;
    audit(
        "maintenance.settings.update",
        "SUCCESS",
        "dashboard",
        false,
        "",
        "",
        Map.of("timezone", next.timezone()));
    publish("maintenance.settings.updated", Map.of("timezone", next.timezone()));
    return next;
  }

  public Map<String, Object> status() throws Exception {
    MaintenanceSettings current = settings;
    ZoneId zone = ZoneId.of(current.timezone());
    Instant now = Instant.now();
    Instant restart = MaintenanceSchedule.next(current.restart().schedule(), zone, now);
    Instant full = MaintenanceSchedule.next(current.fullRestorePoint().schedule(), zone, now);
    MaintenanceStateStore.Job active = state.active();
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("timezone", current.timezone());
    result.put("nextRestart", restart == null ? "" : restart.toString());
    result.put("nextFullRestorePoint", full == null ? "" : full.toString());
    result.put("fullBackupScheduleDeprecated", true);
    result.put("jobStateContractVersion", 2);
    result.put("currentOperation", active == null ? Map.of() : active);
    result.put("provider", fullBackups.providerStatus());
    result.put("commandChannel", commandChannel.safeStatus());
    result.put("restoreRecoveryRequired", fullBackups.recoveryRequired());
    result.put("jobRecoveryRequired", state.recoveryRequired() != null);
    return result;
  }

  public String restartNow(DeviceRegistry.Device device, boolean skipCountdown) throws Exception {
    return queue("RESTART", null, device, false, skipCountdown);
  }

  public String fullRestorePointNow(DeviceRegistry.Device device, boolean ignoredSkipCountdown)
      throws Exception {
    // The Host owns the manual full-backup countdown. Legacy clients may still send skipCountdown,
    // but that browser hint is intentionally ignored for this operation.
    return queue("FULL_RESTORE_POINT", null, device, false, false);
  }

  private synchronized String queue(
      String kind,
      Instant occurrence,
      DeviceRegistry.Device device,
      boolean automatic,
      boolean skipCountdown)
      throws Exception {
    if (closed.get()) throw new IllegalStateException("HOST_OFFLINE");

    MaintenanceStateStore.Job recovery = state.recoveryRequired();
    if (recovery != null) throw conflict("RECOVERY_REQUIRED", recovery, false);
    if (fullBackups.recoveryRequired())
      throw new OperationFailure(
          "RESTORE_RECOVERY_REQUIRED",
          "RECOVERY_REQUIRED",
          "A previous full-restore operation requires Host recovery before maintenance can continue.",
          false);

    MaintenanceStateStore.Job active = state.blocking();
    if (active != null) throw conflict("BUSY", active, true);
    if (worker.getQueue().remainingCapacity() == 0 || operationLock.isLocked())
      throw new OperationFailure(
          "BUSY", "QUEUED", "Another destructive Host operation is already active.", true);

    MaintenanceStateStore.Job job =
        state.begin(
            kind,
            occurrence,
            automatic,
            "QUEUED",
            requesterDeviceId(device, automatic),
            requesterDeviceName(device, automatic));
    publishPhase(job, Map.of());
    try {
      worker.execute(
          () -> {
            if (kind.equals("RESTART")) runRestart(job, device, automatic, skipCountdown);
            else runFull(job, device, automatic, false);
          });
    } catch (RejectedExecutionException busy) {
      state.finish(job, "FAILED", "BUSY", "The Host maintenance worker queue is full.");
      throw new OperationFailure(
          "BUSY",
          "QUEUED",
          "The Host maintenance worker queue is full.",
          true,
          Map.of("jobId", job.jobId(), "state", "FAILED", "kind", job.kind()));
    }
    return job.jobId();
  }

  private void tickSafely() {
    try {
      tick();
    } catch (Exception error) {
      System.err.println("PlexonPanel Host maintenance scheduler: " + error.getClass().getSimpleName());
    }
  }

  private synchronized void tick() throws Exception {
    if (closed.get() || state.blocking() != null || fullBackups.recoveryRequired()) return;
    MaintenanceSettings current = settings;
    ZoneId zone = ZoneId.of(current.timezone());
    Instant now = Instant.now(), windowStart = now.minus(Duration.ofMinutes(10));
    Due full = due("full-restore-point", current.fullRestorePoint().schedule(), zone, windowStart, now);
    Due restart = due("restart", current.restart().schedule(), zone, windowStart, now);
    if (full != null && restart != null && full.occurrence.equals(restart.occurrence)) {
      if (!state.claim(full.scheduleId, full.occurrence)) return;
      state.claim(restart.scheduleId, restart.occurrence);
      queue("FULL_RESTORE_POINT", full.occurrence, null, true, false);
      return;
    }
    if (full != null && state.claim(full.scheduleId, full.occurrence)) {
      queue("FULL_RESTORE_POINT", full.occurrence, null, true, false);
      return;
    }
    if (restart != null && state.claim(restart.scheduleId, restart.occurrence))
      queue("RESTART", restart.occurrence, null, true, false);
  }

  private Due due(
      String id,
      MaintenanceSettings.Schedule schedule,
      ZoneId zone,
      Instant windowStart,
      Instant now) {
    if (!schedule.enabled()) return null;
    Instant occurrence = MaintenanceSchedule.next(schedule, zone, windowStart);
    if (occurrence != null && !occurrence.isAfter(now)) return new Due(id, occurrence);
    return null;
  }

  private void runRestart(
      MaintenanceStateStore.Job original,
      DeviceRegistry.Device device,
      boolean automatic,
      boolean skipCountdown) {
    MaintenanceStateStore.Job job = original;
    boolean locked = false, stoppedByUs = false;
    try {
      if (!operationLock.tryLock()) throw new SecurityException("BUSY");
      locked = true;
      if (fullBackups.recoveryRequired()) throw new IllegalStateException("RESTORE_RECOVERY_REQUIRED");
      job = transition(job, "PREFLIGHT", null, 5, null, null, null, null, Map.of());
      if (service.stopped()) {
        if (!automatic) throw new IllegalStateException("SERVER_ALREADY_STOPPED");
        state.finish(job, "SKIPPED", "SERVER_ALREADY_STOPPED");
        publish(
            "maintenance.completed",
            Map.of(
                "jobId", job.jobId(),
                "kind", job.kind(),
                "result", "SKIPPED",
                "errorCode", "SERVER_ALREADY_STOPPED"));
        audit(
            "maintenance.restart",
            "SKIPPED",
            actor(device, true),
            true,
            job.jobId(),
            "",
            Map.of("reason", "SERVER_ALREADY_STOPPED"));
        return;
      }
      if (!paperConnected.getAsBoolean()) throw new IllegalStateException("PAPER_OFFLINE");
      audit("maintenance.restart", "STARTED", actor(device, automatic), automatic, job.jobId(), "", Map.of());
      if (!skipCountdown) {
        job = transition(job, "COUNTDOWN", null, 10, null, null, null, null, Map.of());
        countdownViaPaper(device, automatic, settings.restart().warningSeconds(), "restart");
      }
      job = transition(job, "FINAL_SAVE", null, 25, null, null, null, null, Map.of());
      if (!paperLink.flush(device, automatic)) throw new IOException("PAPER_FLUSH_FAILED");
      long revision = paperRevision.getAsLong();
      job = transition(job, "STOPPING_SERVER", null, 35, null, null, null, null, Map.of());
      service.action("stop");
      stoppedByUs = true;
      job = transition(job, "WAITING_FOR_STOP", null, 40, true, null, null, true, Map.of());
      waitStopped(settings.restart().stopTimeoutSeconds());
      job = transition(job, "STARTING_SERVER", null, 70, true, null, null, true, Map.of());
      service.action("start");
      job = transition(job, "VERIFYING_STARTUP", null, 85, true, null, null, true, Map.of());
      waitStartedViaPaper(revision, settings.restart().startupTimeoutSeconds());
      job = transition(job, "VERIFYING_STARTUP", null, 100, true, null, null, false, Map.of());
      state.finish(job, "SUCCESS", "");
      publish("maintenance.completed", Map.of("jobId", job.jobId(), "kind", job.kind(), "result", "SUCCESS"));
      audit("maintenance.restart", "SUCCESS", actor(device, automatic), automatic, job.jobId(), "", Map.of());
    } catch (Exception error) {
      fail(job, device, automatic, "maintenance.restart", error, stoppedByUs);
      if (stoppedByUs) tryStartAfterFailure();
    } finally {
      if (locked) operationLock.unlock();
    }
  }

  private void runFull(
      MaintenanceStateStore.Job original,
      DeviceRegistry.Device device,
      boolean automatic,
      boolean ignoredSkipCountdown) {
    MaintenanceStateStore.Job job = original;
    boolean locked = false, stoppedByUs = false;
    boolean resumedCountdown = resumableBackupCountdown(original);
    try {
      if (!operationLock.tryLock()) throw new SecurityException("BUSY");
      locked = true;
      if (fullBackups.recoveryRequired()) throw new IllegalStateException("RESTORE_RECOVERY_REQUIRED");
      MaintenanceSettings current = settings;
      Map<String, Object> preflight;
      if (resumedCountdown) {
        preflight = fullBackupPreflight.check(current.fullRestorePoint());
        Map<String, Object> recoveredPreflight = new LinkedHashMap<>(preflight);
        recoveredPreflight.put("jobId", job.jobId());
        recoveredPreflight.put("phase", job.phase());
        publish("maintenance.preflight.rechecked", recoveredPreflight);
      } else {
        job = transition(job, "PREFLIGHT", null, 5, null, null, null, null, Map.of());
        preflight = fullBackupPreflight.check(current.fullRestorePoint());
        job = transition(job, "PREFLIGHT", null, 7, null, null, null, null, preflight);
      }

      boolean startedOnline = !service.stopped();
      String jobActor = actorForJob(job, device, automatic);
      audit(
          "backup.full.create",
          resumedCountdown ? "RESUMED" : "STARTED",
          jobActor,
          automatic,
          job.jobId(),
          "",
          Map.of("initialServerState", startedOnline ? "RUNNING" : "STOPPED"));

      if (resumedCountdown && !startedOnline)
        throw new OperationFailure(
            "COUNTDOWN_RESUME_SERVER_STOPPED",
            "COUNTDOWN",
            "The Host cannot resume a pre-shutdown countdown while Minecraft is stopped.",
            false);

      if (startedOnline) {
        if (!commandChannel.enabled())
          throw new OperationFailure(
              "COMMAND_CHANNEL_DISABLED",
              resumedCountdown ? "COUNTDOWN" : "PREFLIGHT",
              "Manual full backup requires the Host-local Minecraft command channel.",
              false);
        if (!resumedCountdown)
          job = transition(job, "COUNTDOWN", null, 10, null, null, null, null, Map.of());
        runBackupCountdown(job, resumedCountdown);
        job = transition(job, "FINAL_SAVE", null, 25, null, null, null, null, Map.of());
        commandChannel.saveAllFlush();
        job = transition(job, "STOPPING_SERVER", null, 35, null, null, null, null, Map.of());
        service.action("stop");
        stoppedByUs = true;
        job = transition(job, "WAITING_FOR_STOP", null, 40, true, null, null, true, Map.of());
        waitStopped(current.restart().stopTimeoutSeconds());
      } else {
        job =
            transition(
                job,
                "PREFLIGHT",
                null,
                40,
                false,
                null,
                null,
                false,
                Map.of("serverAlreadyStopped", true));
      }

      job = transition(job, "ARCHIVING", null, 50, null, null, null, null, Map.of());
      FullRestorePointManager.Metadata backup =
          fullBackups.createLocked(
              job.jobId(), jobActor, automatic, false, current.fullRestorePoint(), true);
      boolean providerConfigured = Boolean.TRUE.equals(fullBackups.providerStatus().get("configured"));
      boolean localVerified = backup.local() && backup.sha256() != null && !backup.sha256().isBlank();
      boolean remoteVerified = backup.offsite();
      boolean degraded = providerConfigured && !remoteVerified;
      String errorCode = degraded ? retryableOffsiteCode(backup.errorCode()) : "";
      job =
          transition(
              job,
              "VERIFYING_LOCAL",
              backup.backupId(),
              70,
              null,
              localVerified,
              remoteVerified,
              stoppedByUs,
              Map.of("verification", backup.verification()));
      if (!localVerified) throw new IOException("LOCAL_VERIFICATION_FAILED");
      if (providerConfigured) {
        job =
            transition(
                job,
                "VERIFYING_REMOTE",
                backup.backupId(),
                80,
                null,
                true,
                remoteVerified,
                stoppedByUs,
                Map.of(
                    "offsite", backup.offsite(),
                    "errorCode", errorCode,
                    "retryUploadAvailable", degraded && backup.local()));
      }

      boolean safetyRestart = stoppedByUs && degraded && !current.fullRestorePoint().restartAfter();
      boolean startAfter = stoppedByUs && (degraded || current.fullRestorePoint().restartAfter());
      if (startAfter) {
        job =
            transition(
                job,
                "STARTING_SERVER",
                backup.backupId(),
                85,
                true,
                true,
                remoteVerified,
                true,
                Map.of("safetyRestart", safetyRestart));
        service.action("start");
        job =
            transition(
                job,
                "VERIFYING_STARTUP",
                backup.backupId(),
                95,
                true,
                true,
                remoteVerified,
                true,
                Map.of("safetyRestart", safetyRestart));
        waitStartedViaRcon(current.restart().startupTimeoutSeconds());
        job =
            transition(
                job,
                "VERIFYING_STARTUP",
                backup.backupId(),
                100,
                true,
                true,
                remoteVerified,
                false,
                Map.of("safetyRestart", safetyRestart));
      } else if (stoppedByUs) {
        // A successful off-site result may intentionally preserve the stopped state. Degraded
        // results never take this branch because availability recovery overrides restartAfter=false.
        job =
            transition(
                job,
                job.phase(),
                backup.backupId(),
                100,
                true,
                true,
                remoteVerified,
                false,
                Map.of("preservedStoppedState", true));
      }

      String finalResult = degraded ? "DEGRADED" : "SUCCESS";
      state.finish(job, finalResult, errorCode);
      publish(
          "maintenance.completed",
          Map.of(
              "jobId", job.jobId(),
              "kind", job.kind(),
              "backupId", backup.backupId(),
              "local", backup.local(),
              "offsite", backup.offsite(),
              "result", finalResult,
              "errorCode", errorCode,
              "retryUploadAvailable", degraded && backup.local(),
              "safetyRestart", safetyRestart));
      audit(
          "backup.full.create",
          finalResult,
          jobActor,
          automatic,
          job.jobId(),
          backup.backupId(),
          Map.of(
              "sha256", backup.sha256(),
              "archiveBytes", backup.archiveBytes(),
              "offsite", backup.offsite(),
              "localVerified", localVerified,
              "remoteVerified", remoteVerified,
              "errorCode", errorCode,
              "retryUploadAvailable", degraded && backup.local(),
              "safetyRestart", safetyRestart,
              "serverWasStoppedByMaintenance", stoppedByUs));
    } catch (Exception error) {
      fail(job, device, automatic, "backup.full.create", error, stoppedByUs);
      if (stoppedByUs) tryStartAfterFailure();
    } finally {
      if (locked) operationLock.unlock();
    }
  }

  private void runBackupCountdown(MaintenanceStateStore.Job job, boolean resumed) throws Exception {
    MaintenanceCountdownStore.State countdown = countdownState.load(job.jobId());
    if (countdown == null) {
      Instant startedAt;
      try {
        startedAt = Instant.parse(job.phaseTimestamp());
      } catch (RuntimeException invalidTimestamp) {
        startedAt = Instant.now();
      }
      countdown = countdownState.start(job.jobId(), startedAt);
    }
    MaintenanceCountdown.Result result =
        backupCountdown.run(countdownState, countdown, resumed, commandChannel::broadcastBackupWarning);
    if (!result.newlyMissed().isEmpty()) {
      publish(
          "maintenance.countdown.recovered",
          Map.of(
              "jobId", job.jobId(),
              "missedBoundariesSeconds", result.newlyMissed(),
              "hostRestartCount", result.state().hostRestartCount()));
      audit(
          "backup.full.countdown.recover",
          "SUCCESS",
          actorForJob(job, null, job.automatic()),
          job.automatic(),
          job.jobId(),
          "",
          Map.of("missedBoundariesSeconds", result.newlyMissed()));
    }
    countdownState.clear(job.jobId());
  }

  private MaintenanceStateStore.Job transition(
      MaintenanceStateStore.Job job,
      String phase,
      String backupId,
      Integer progress,
      Boolean hostStoppedServer,
      Boolean localVerified,
      Boolean remoteVerified,
      Boolean restartRecoveryRequired,
      Map<String, Object> extra)
      throws IOException {
    MaintenanceStateStore.Job next =
        state.transition(
            job,
            phase,
            backupId,
            progress,
            hostStoppedServer,
            localVerified,
            remoteVerified,
            restartRecoveryRequired);
    publishPhase(next, extra);
    return next;
  }

  private void countdownViaPaper(
      DeviceRegistry.Device device, boolean automatic, List<Integer> warnings, String operation)
      throws Exception {
    List<Integer> sorted = new ArrayList<>(new TreeSet<>(warnings));
    sorted.sort(Comparator.reverseOrder());
    int previous = sorted.isEmpty() ? 0 : sorted.get(0);
    for (int i = 0; i < sorted.size(); i++) {
      int remaining = sorted.get(i);
      if (i > 0) sleepInterruptibly(previous - remaining);
      if (!paperConnected.getAsBoolean()) throw new IOException("PAPER_OFFLINE");
      String human = humanDuration(remaining);
      paperLink.notice(
          device,
          automatic,
          "<yellow><bold>Server maintenance</bold></yellow> <gray>" + operation + " in <white>" + human + "</white>.</gray>",
          "<yellow>Maintenance</yellow>");
      previous = remaining;
    }
    if (previous > 0) sleepInterruptibly(previous);
  }

  private void waitStopped(int seconds) throws Exception {
    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
    while (System.nanoTime() < until) {
      if (service.stopped() && !paperConnected.getAsBoolean()) return;
      Thread.sleep(250);
    }
    throw new IOException("SERVER_STOP_TIMEOUT");
  }

  private void waitStartedViaPaper(long priorRevision, int seconds) throws Exception {
    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
    while (System.nanoTime() < until) {
      Map<String, Object> status = service.status();
      if ("active".equals(status.get("state"))
          && paperConnected.getAsBoolean()
          && paperRevision.getAsLong() > priorRevision) return;
      Thread.sleep(250);
    }
    throw new IOException("PAPER_RECONNECT_TIMEOUT");
  }

  private void waitStartedViaRcon(int seconds) throws Exception {
    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
    while (System.nanoTime() < until) {
      Map<String, Object> status = service.status();
      if ("active".equals(status.get("state")) && commandChannel.probeReady()) return;
      Thread.sleep(250);
    }
    throw new OperationFailure(
        "RCON_READINESS_TIMEOUT",
        "VERIFYING_STARTUP",
        "Minecraft did not become ready through the Host-local command channel in time.",
        true);
  }

  private void sleepInterruptibly(int seconds) throws InterruptedException {
    if (seconds <= 0) return;
    TimeUnit.SECONDS.sleep(seconds);
  }

  private void tryStartAfterFailure() {
    try {
      if (service.stopped()) service.action("start");
    } catch (Exception startFailure) {
      System.err.println("PlexonPanel Host could not recover server start after maintenance failure");
    }
  }

  private void fail(
      MaintenanceStateStore.Job job,
      DeviceRegistry.Device device,
      boolean automatic,
      String action,
      Exception error,
      boolean stoppedByUs) {
    String code = classify(error);
    String outcome = stoppedByUs || job.restartRecoveryRequired() ? "RECOVERY_REQUIRED" : "FAILED";
    try {
      MaintenanceStateStore.Job latest = job;
      if (stoppedByUs && !latest.restartRecoveryRequired())
        latest =
            state.transition(
                latest,
                latest.phase(),
                latest.backupId(),
                null,
                true,
                null,
                null,
                true);
      state.finish(
          latest,
          outcome,
          code,
          outcome.equals("RECOVERY_REQUIRED")
              ? "Maintenance failed after the Host may have stopped Minecraft; verify service state before retrying."
              : "Maintenance failed before the destructive stop boundary.");
    } catch (Exception ignored) {
    }
    publish(
        outcome.equals("RECOVERY_REQUIRED") ? "maintenance.recovery.required" : "maintenance.failed",
        Map.of("jobId", job.jobId(), "kind", job.kind(), "errorCode", code));
    audit(
        action,
        outcome,
        actorForJob(job, device, automatic),
        automatic,
        job.jobId(),
        job.backupId(),
        Map.of("errorCode", code));
  }

  private static OperationFailure conflict(
      String code, MaintenanceStateStore.Job active, boolean retryable) {
    String phase = active.phase().matches("[A-Z][A-Z0-9_]{0,47}") ? active.phase() : "QUEUED";
    return new OperationFailure(
        code,
        phase,
        code.equals("RECOVERY_REQUIRED")
            ? "A previous Host maintenance job requires recovery before another destructive operation can start."
            : "Another Host maintenance job is already active.",
        retryable,
        Map.of("jobId", active.jobId(), "state", phase, "kind", active.kind()));
  }

  private void publishPhase(MaintenanceStateStore.Job job, Map<String, Object> extra) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("jobId", job.jobId());
    body.put("kind", job.kind());
    body.put("phase", job.phase());
    body.put("phaseTimestamp", job.phaseTimestamp());
    body.put("progressPercent", job.progressPercent());
    body.put("automatic", job.automatic());
    body.put("hostStoppedServer", job.hostStoppedServer());
    body.put("localBackupVerified", job.localBackupVerified());
    body.put("remoteBackupVerified", job.remoteBackupVerified());
    body.put("restartRecoveryRequired", job.restartRecoveryRequired());
    if (job.backupId() != null && !job.backupId().isBlank()) body.put("backupId", job.backupId());
    body.put("capturedAt", Instant.now().toString());
    body.putAll(extra);
    publish("maintenance.phase", body);
  }

  private void publish(String type, Map<String, Object> body) {
    events.send(type, body, MessagePriority.EVENT);
  }

  private void audit(
      String action,
      String outcome,
      String actor,
      boolean automatic,
      String jobId,
      String backupId,
      Map<String, Object> metadata) {
    try {
      Map<String, Object> entry = new LinkedHashMap<>();
      entry.put("timestamp", Instant.now().toString());
      entry.put("requestId", UUID.randomUUID().toString());
      entry.put("serverId", config.serverId());
      entry.put("deviceId", automatic ? "local-schedule" : actor);
      entry.put("role", automatic ? "Local" : "Authorized");
      entry.put("actorLabel", actor);
      entry.put("actionType", action);
      entry.put("automatic", automatic);
      entry.put("outcome", outcome);
      entry.put("jobId", jobId == null ? "" : jobId);
      entry.put("backupId", backupId == null ? "" : backupId);
      if (!metadata.isEmpty()) entry.put("metadata", metadata);
      audit.append(entry);
    } catch (Exception ignored) {
    }
  }

  private static boolean resumableBackupCountdown(MaintenanceStateStore.Job job) {
    return job != null
        && "FULL_RESTORE_POINT".equals(job.kind())
        && "COUNTDOWN".equals(job.phase())
        && !MaintenanceStateStore.terminal(job);
  }

  private static String requesterDeviceId(DeviceRegistry.Device device, boolean automatic) {
    return automatic ? "local-schedule" : device == null ? "local-host" : device.deviceId();
  }

  private static String requesterDeviceName(DeviceRegistry.Device device, boolean automatic) {
    return automatic ? "Host schedule" : device == null ? "Local host" : device.name();
  }

  private static String actor(DeviceRegistry.Device device, boolean automatic) {
    return requesterDeviceName(device, automatic);
  }

  private static String actorForJob(
      MaintenanceStateStore.Job job, DeviceRegistry.Device device, boolean automatic) {
    if (device != null) return device.name();
    if (job != null && job.requesterDeviceName() != null && !job.requesterDeviceName().isBlank())
      return job.requesterDeviceName();
    return actor(device, automatic);
  }

  private static String humanDuration(int seconds) {
    if (seconds >= 60 && seconds % 60 == 0) {
      int minutes = seconds / 60;
      return minutes + (minutes == 1 ? " minute" : " minutes");
    }
    return seconds + (seconds == 1 ? " second" : " seconds");
  }

  private static String retryableOffsiteCode(String errorCode) {
    return errorCode == null || errorCode.isBlank() ? "REMOTE_UPLOAD_FAILED" : errorCode;
  }

  private static String classify(Exception error) {
    if (error instanceof OperationFailure failure) return failure.code();
    String message = error.getMessage();
    if (message != null && message.matches("[A-Z0-9_]{3,64}")) return message;
    if (error instanceof SecurityException) return "BUSY";
    return "MAINTENANCE_FAILED";
  }

  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) return;
    clock.shutdownNow();
    worker.shutdownNow();
  }

  private record Due(String scheduleId, Instant occurrence) {}
}
