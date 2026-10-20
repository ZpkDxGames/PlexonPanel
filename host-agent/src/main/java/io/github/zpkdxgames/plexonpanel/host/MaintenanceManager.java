package io.github.zpkdxgames.plexonpanel.host;

import com.google.gson.JsonElement;
import io.github.zpkdxgames.plexonpanel.audit.LocalAudit;
import io.github.zpkdxgames.plexonpanel.control.OperationFailure;
import io.github.zpkdxgames.plexonpanel.protocol.*;
import io.github.zpkdxgames.plexonpanel.security.DeviceRegistry;
import io.github.zpkdxgames.plexonpanel.util.ExecutorDrain;
import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/** Host-authoritative restart scheduler and manual-only cold full-backup orchestrator. */
public final class MaintenanceManager implements AutoCloseable {
  private final HostConfig config;
  private final Path settingsPath;
  private final MaintenanceStateStore state;
  private final FullRestorePointManager fullBackups;
  private final FullBackupPreflight fullBackupPreflight;
  private final SystemdService service;
  private final MinecraftCommandChannel commandChannel;
  private final ReentrantLock operationLock;
  private final LocalAudit audit;
  private final MessageSink events;
  private final Clock timeSource;
  private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
  private final ThreadPoolExecutor worker =
      new ThreadPoolExecutor(
          1,
          1,
          0L,
          TimeUnit.MILLISECONDS,
          new ArrayBlockingQueue<>(2),
          new ThreadPoolExecutor.AbortPolicy());
  private final AtomicBoolean closed = new AtomicBoolean();
  private final AtomicBoolean commandChannelClosed = new AtomicBoolean();
  private volatile MaintenanceSettings settings;

  public MaintenanceManager(
      HostConfig config,
      SystemdService service,
      MinecraftCommandChannel commandChannel,
      ReentrantLock operationLock,
      LocalAudit audit,
      MessageSink events,
      FullRestorePointManager fullBackups)
      throws IOException {
    this(
        config,
        service,
        commandChannel,
        operationLock,
        audit,
        events,
        fullBackups,
        Clock.systemUTC());
  }

  MaintenanceManager(
      HostConfig config,
      SystemdService service,
      MinecraftCommandChannel commandChannel,
      ReentrantLock operationLock,
      LocalAudit audit,
      MessageSink events,
      FullRestorePointManager fullBackups,
      Clock timeSource)
      throws IOException {
    this(config,service,commandChannel,operationLock,audit,events,fullBackups,timeSource,null);
  }

  MaintenanceManager(HostConfig config, SystemdService service, MinecraftCommandChannel commandChannel,
      ReentrantLock operationLock, LocalAudit audit, MessageSink events, FullRestorePointManager fullBackups,
      Clock timeSource, FullBackupPreflight preflight) throws IOException {
    this.config = config;
    this.service = service;
    this.commandChannel = Objects.requireNonNull(commandChannel, "commandChannel");
    this.operationLock = operationLock;
    this.audit = audit;
    this.events = events;
    this.fullBackups = fullBackups;
    this.fullBackupPreflight = preflight == null ? new FullBackupPreflight(config, fullBackups) : preflight;
    this.timeSource = Objects.requireNonNull(timeSource, "timeSource");
    Path data = Path.of(config.dataDirectory()).toAbsolutePath().normalize();
    this.settingsPath = data.resolve("maintenance-settings.json");
    this.state = new MaintenanceStateStore(data);
    this.settings = MaintenanceSettings.load(settingsPath);
    if (!Files.exists(settingsPath)) this.settings.save(settingsPath);
  }

  public void start() {
    try {
      MaintenanceStateStore.Job active = state.active();
      boolean resumedCountdown = false;
      if (active != null
          && "COUNTDOWN".equals(active.phase())
          && (!active.automatic()
              || !"FULL_RESTORE_POINT".equals(active.kind())
              || settings.fullRestorePoint().schedule().enabled())) {
        try {
          state.countdown(active);
          state.recordCountdownRecovery(active, "COUNTDOWN_RESUMED", 0);
          MaintenanceStateStore.Job resumed = active;
          worker.execute(() -> run(resumed, null, resumed.automatic(), false, true, null));
          resumedCountdown = true;
          publishPhase(resumed, Map.of("resumed", true));
        } catch (IOException invalidCountdown) {
          // A legacy or corrupt COUNTDOWN has no durable deadline. Fall through to the existing
          // pre-destructive recovery classifier rather than inventing a new deadline.
        }
      }
      if (!resumedCountdown) {
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
    } catch (IOException error) {
      throw new IllegalStateException("MAINTENANCE_STATE_RECOVERY_FAILED", error);
    } catch (RejectedExecutionException busy) {
      throw new IllegalStateException("MAINTENANCE_COUNTDOWN_RECOVERY_BUSY", busy);
    }
    // Both modes share the existing locks, durable occurrence ledger and cold-backup worker.
    scheduler.scheduleWithFixedDelay(this::tickSafely, 2, 15, TimeUnit.SECONDS);
  }

  public MaintenanceSettings settings() {
    return settings;
  }

  /** True when the durable maintenance journal blocks another destructive operation. */
  public boolean recoveryRequired() throws IOException {
    return state.recoveryRequired() != null;
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
        Map.of("timezone", next.timezone(), "fullBackupMode", "MANUAL_AND_PERIODIC"));
    publish(
        "maintenance.settings.updated",
        Map.of("timezone", next.timezone(), "fullBackupMode", "MANUAL_AND_PERIODIC"));
    return next;
  }

  public Map<String, Object> status() throws Exception {
    MaintenanceSettings current = settings;
    ZoneId zone = ZoneId.of(current.timezone());
    Instant now = timeSource.instant();
    Instant restart = MaintenanceSchedule.next(current.restart().schedule(), zone, now);
    MaintenanceStateStore.Job active = state.active();
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("timezone", current.timezone());
    result.put("nextRestart", restart == null ? "" : restart.toString());
    result.put("fullBackupMode", "MANUAL_AND_PERIODIC");
    Instant nextBackup = MaintenanceSchedule.next(current.fullRestorePoint().schedule(), zone, now);
    result.put("nextFullRestorePoint", nextBackup == null ? "" : nextBackup.toString());
    result.put(
        "stageDefinitions",
        Arrays.stream(BackupStage.values())
            .map(stage -> Map.of("id", stage.name(), "label", stage.label))
            .toList());
    result.put("stages", BackupStage.definitions());
    result.put("job", state.snapshot(active));
    result.put("jobStateContractVersion", 4);
    result.put("uploadActive", fullBackups.transferBusy());
    result.put("operationBusy", fullBackups.transferBusy() || operationLock.isLocked()
        || worker.getActiveCount()>0 || !worker.getQueue().isEmpty()
        || (active!=null && !MaintenanceStateStore.terminal(active) && !"RECOVERY_REQUIRED".equals(active.phase())));
    result.put("countdownState", "IDLE");
    result.put("countdownKind", "");
    result.put("countdownRemainingSeconds", 0L);
    result.put("countdownEndsAt", "");
    result.put("countdownTotalSeconds", 0);
    result.put("currentOperation", active);
    result.put(
        "uploadRetryable",
        active != null
            && active.phase().equals("DEGRADED")
            && active.localBackupVerified()
            && !active.remoteBackupVerified()
            && !active.restartRecoveryRequired()
            && active.backupId() != null
            && !active.backupId().isBlank()
            && Set.of("REMOTE_UPLOAD_FAILED", "REMOTE_VERIFY_FAILED", "RCLONE_UNAVAILABLE")
                .contains(active.errorCode()));
    result.put("provider", fullBackups.providerStatus());
    result.put("nodeBackup", fullBackups.nodeBackupStatus());
    result.put("restoreRecoveryRequired", fullBackups.recoveryRequired());
    result.put("jobRecoveryRequired", state.recoveryRequired() != null);
    result.put("recoveryRequired", fullBackups.recoveryRequired() || state.recoveryRequired()!=null);
    result.put(
        "commandChannel", Map.of("enabled", commandChannel.enabled(), "type", "HOST_LOCAL_RCON"));
    if (active != null && "COUNTDOWN".equals(active.phase())) {
      try {
        MaintenanceStateStore.Countdown countdown = state.countdown(active);
        Instant deadline = Instant.parse(countdown.deadline());
        long remainingSeconds = Math.max(0L, Duration.between(now, deadline).getSeconds());
        result.put("countdownDeadline", countdown.deadline());
        result.put("countdownEndsAt", countdown.deadline());
        result.put("countdownKind", "FULL_RESTORE_POINT".equals(active.kind()) ? "BACKUP" : "RESTART");
        result.put("countdownRemainingSeconds", remainingSeconds);
        result.put("countdownWarningsSent", countdown.consumedWarnings());
        List<Integer> warningSeconds = durableWarnings(active, countdown);
        result.put("countdownInitialSeconds", MaintenanceCountdown.durationSeconds(warningSeconds));
        result.put("countdownTotalSeconds", MaintenanceCountdown.durationSeconds(warningSeconds));
        result.put("countdownWarningSeconds", warningSeconds);
        result.put("countdownState", "ACTIVE");
      } catch (IOException | DateTimeException invalidCountdown) {
        // Never invent a deadline in the public status contract. Recovery logic will classify an
        // invalid durable countdown independently; the Dashboard must show it as unavailable.
        result.put("countdownState", "UNAVAILABLE");
      }
    }
    return result;
  }

  public String restartNow(DeviceRegistry.Device device, boolean skipCountdown) throws Exception {
    return queue("RESTART", null, device, false, skipCountdown, null);
  }

  /** Manual full backups use one validated Host-owned countdown while Minecraft is online. */
  public String fullRestorePointNow(DeviceRegistry.Device device, int countdownSeconds)
      throws Exception {
    try {
      MaintenanceCountdown.fullBackupWarnings(countdownSeconds);
    } catch (IllegalArgumentException invalid) {
      throw new OperationFailure(
          "COUNTDOWN_INVALID",
          "QUEUED",
          "Choose a supported full-backup countdown: 30, 15, 10 or 5 minutes.",
          false,
          Map.of("supportedCountdownSeconds", "1800,900,600,300"));
    }
    return fullRestorePointNow(device, countdownSeconds, DestinationMode.AUTO);
  }

  public String fullRestorePointNow(DeviceRegistry.Device device, int countdownSeconds, DestinationMode mode) throws Exception {
    MaintenanceCountdown.fullBackupWarnings(countdownSeconds);
    return startFullBackup(device,countdownSeconds,mode,false);
  }

  String startFullBackup(DeviceRegistry.Device device, int countdownSeconds, DestinationMode mode, boolean automatic) throws Exception {
    if (countdownSeconds != 0) MaintenanceCountdown.fullBackupWarnings(countdownSeconds);
    return queue("FULL_RESTORE_POINT",null,device,automatic,countdownSeconds==0,countdownSeconds,mode);
  }

  /**
   * Resolves a durable maintenance recovery gate only after the operator has restored Minecraft to
   * a positively verified running state. This never invents backup success; the interrupted job is
   * finalized as FAILED while preserving its original error code.
   */
  public synchronized Map<String,Object> resolveRecovery(DeviceRegistry.Device device) throws Exception {
    return resolveRecovery(device,false);
  }

  public synchronized Map<String,Object> resolveRecovery(DeviceRegistry.Device device, boolean acknowledgeRestore) throws Exception {
    if(closed.get()) throw new IllegalStateException("HOST_OFFLINE");
    var recovery=state.recoveryRequired(); boolean restore=fullBackups.recoveryRequired();
    if(recovery==null && !restore) return Map.of("resolved",false,"reason","NOTHING_TO_RESOLVE","state","NONE");
    if(worker.getActiveCount()>0 || !worker.getQueue().isEmpty() || operationLock.isLocked())
      throw FullBackupPreflight.preflightFailure("RECOVERY_JOB_RUNNING","A maintenance operation is still running.");
    if(!"active".equals(service.status().get("state")))
      throw FullBackupPreflight.preflightFailure("RECOVERY_SERVICE_NOT_ACTIVE","The Minecraft service must be active.");
    if(!commandChannel.enabled() || !commandChannel.readinessProbe().success())
      throw FullBackupPreflight.preflightFailure("RECOVERY_MINECRAFT_NOT_READY","Minecraft must pass the Host readiness probe.");
    if(restore && !acknowledgeRestore)
      throw FullBackupPreflight.preflightFailure("RESTORE_RECOVERY_REQUIRES_REVIEW","A restore was interrupted. Review the world, then confirm explicitly.");
    String actor=actor(device,false), id=recovery==null?"":recovery.jobId();
    if(restore) fullBackups.acknowledgeRestoreRecovery(actor);
    if(recovery!=null) state.markRecovered(recovery,"FAILED",actor);
    publish("maintenance.recovery.resolved",Map.of("jobId",id,"resolvedBy",actor,"result","FAILED",
        "errorCode",recovery==null?"":recovery.errorCode()));
    audit("maintenance.recovery.resolve","SUCCESS",actor,false,id,recovery==null?"":recovery.backupId(),
        Map.of("verifiedServiceState","active","readinessVerified",true,"acknowledgeRestore",acknowledgeRestore));
    publishStatus();
    return Map.of("resolved",true,"jobId",id,"state","FAILED");
  }

  /** Upload is independent of world-source/disk/recovery gates and never owns the maintenance lock. */
  public FullRestorePointManager.Metadata promoteToDrive(DeviceRegistry.Device device,String backupId) throws Exception {
    FullBackupPreflight.assertProvider(fullBackupPreflight.inspect(settings,true).provider());
    AutoCloseable reservation;
    synchronized(this) {
      if (closed.get()) throw new IllegalStateException("HOST_OFFLINE");
      if ((state.blocking()!=null && state.recoveryRequired()==null) || worker.getActiveCount()>0 || operationLock.isLocked())
        throw FullBackupPreflight.preflightFailure("JOB_RUNNING","A maintenance operation is running.");
      reservation=fullBackups.reserveTransfer();
    }
    try(reservation) {
      var result=fullBackups.retryUpload(backupId,UUID.randomUUID().toString(),settings.fullRestorePoint(),true);
      audit("backup.full.retry-upload","SUCCESS",actor(device,false),false,result.jobId(),backupId,Map.of("sha256",result.sha256()));
      return result;
    } finally { publishStatus(); }
  }

  private synchronized String queue(
      String kind,
      Instant occurrence,
      DeviceRegistry.Device device,
      boolean automatic,
      boolean skipCountdown,
      Integer fullBackupCountdownSeconds)
      throws Exception {
    return queue(kind,occurrence,device,automatic,skipCountdown,fullBackupCountdownSeconds,DestinationMode.AUTO);
  }

  private synchronized String queue(String kind, Instant occurrence, DeviceRegistry.Device device,
      boolean automatic, boolean skipCountdown, Integer fullBackupCountdownSeconds, DestinationMode requested) throws Exception {
    if (closed.get()) throw new IllegalStateException("HOST_OFFLINE");
    MaintenanceStateStore.Job recovery = state.recoveryRequired();
    if (recovery != null) throw conflict("RECOVERY_REQUIRED", recovery, false);
    if (fullBackups.recoveryRequired())
      throw new OperationFailure(
          "RESTORE_RECOVERY_REQUIRED",
          "RECOVERY_REQUIRED",
          "A previous full-restore operation requires Host recovery before maintenance can"
              + " continue.",
          false);

    MaintenanceStateStore.Job active = state.blocking();
    if (active != null) throw conflict("BUSY", active, true);
    if (worker.getQueue().remainingCapacity() == 0 || operationLock.isLocked() || fullBackups.transferBusy())
      throw new OperationFailure(
          "BUSY", "QUEUED", "Another destructive Host operation is already active.", true);

    DestinationMode.Plan plan=null;
    if ("FULL_RESTORE_POINT".equals(kind)) {
      var report=fullBackupPreflight.inspect(settings,requested!=DestinationMode.LOCAL_ONLY);
      plan=requested.plan(report.provider().state(),settings.fullRestorePoint().allowLocalFallback());
      FullBackupPreflight.assertReady(report,plan.requireRemote());
    }
    MaintenanceStateStore.Job job =
        state.begin(
            kind,
            occurrence,
            automatic,
            "QUEUED",
            requesterDeviceId(device, automatic),
            requesterDeviceName(device, automatic));
    if(plan!=null) {
      var detail=new LinkedHashMap<String,Object>();
      detail.put("destinationMode",requested.name()); detail.put("effectiveDestination",plan.effective());
      detail.put("fallbackReason",plan.fallbackReason()==null?"":plan.fallbackReason());
      detail.put("skippedStages",plan.uploadOffsite()?List.of():BackupStage.offsiteStageOrdinals());
      state.annotate(job,detail);
    }
    publishPhase(job, Map.of());
    try {
      worker.execute(
          () -> run(job, device, automatic, skipCountdown, false, fullBackupCountdownSeconds));
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

  private void run(
      MaintenanceStateStore.Job job,
      DeviceRegistry.Device device,
      boolean automatic,
      boolean skipCountdown,
      boolean recoveringCountdown,
      Integer fullBackupCountdownSeconds) {
    if ("RESTART".equals(job.kind()))
      runRestart(job, device, automatic, skipCountdown, recoveringCountdown);
    else if ("FULL_RESTORE_POINT".equals(job.kind()))
      runFull(
          job, device, automatic, skipCountdown, recoveringCountdown, fullBackupCountdownSeconds);
    else
      fail(
          job,
          device,
          automatic,
          "maintenance.unknown",
          new IllegalStateException("MAINTENANCE_KIND_INVALID"),
          false);
  }

  private void tickSafely() {
    try {
      tick();
    } catch (Exception error) {
      System.err.println("PlexonPanel Host restart scheduler: " + error.getClass().getSimpleName());
    }
  }

  private synchronized void tick() throws Exception {
    if (closed.get() || state.blocking() != null || fullBackups.recoveryRequired() || fullBackups.transferBusy()) return;
    MaintenanceSettings current = settings;
    ZoneId zone = ZoneId.of(current.timezone());
    Instant now = timeSource.instant(), windowStart = now.minus(Duration.ofMinutes(10));
    Due backup = due("full-backup", current.fullRestorePoint().schedule(), zone, windowStart, now);
    if (backup != null && state.claim(backup.scheduleId, backup.occurrence)) {
      queue("FULL_RESTORE_POINT", backup.occurrence, null, true, false, 1800);
      return;
    }
    Due restart = due("restart", current.restart().schedule(), zone, windowStart, now);
    if (restart != null && state.claim(restart.scheduleId, restart.occurrence))
      queue("RESTART", restart.occurrence, null, true, false, null);
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
      boolean skipCountdown,
      boolean recoveringCountdown) {
    MaintenanceStateStore.Job job = original;
    boolean locked = false, stoppedByUs = false;
    AtomicBoolean stopBoundaryEntered = new AtomicBoolean();
    try {
      if (!operationLock.tryLock()) throw new SecurityException("BUSY");
      locked = true;
      if (fullBackups.recoveryRequired())
        throw new IllegalStateException("RESTORE_RECOVERY_REQUIRED");
      if (!recoveringCountdown)
        job = transition(job, "PREFLIGHT", null, 5, null, null, null, null, Map.of());
      if (service.stopped()) {
        if (!automatic) throw new IllegalStateException("SERVER_ALREADY_STOPPED");
        state.finish(job, "SKIPPED", "SERVER_ALREADY_STOPPED");
        publish(
            "maintenance.completed",
            Map.of(
                "jobId",
                job.jobId(),
                "kind",
                job.kind(),
                "result",
                "SKIPPED",
                "errorCode",
                "SERVER_ALREADY_STOPPED"));
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
      requireCommandChannel();
      audit(
          "maintenance.restart",
          "STARTED",
          actor(device, automatic),
          automatic,
          job.jobId(),
          "",
          recoveringCountdown ? Map.of("countdownResumed", true) : Map.of());
      if (!skipCountdown) {
        List<Integer> warningSeconds = settings.restart().warningSeconds();
        if (!recoveringCountdown) {
          job = transition(job, "COUNTDOWN", null, 10, null, null, null, null, Map.of());
          state.beginCountdown(job, warningSeconds, timeSource.instant());
          publishStatus();
        }
        countdown(job, MinecraftCommandChannel.MaintenanceOperation.RESTART, warningSeconds);
      }
      job = transition(job, "FINAL_SAVE", null, 25, null, null, null, null, Map.of());
      MaintenanceStateStore.Job beforeStop = job;
      job =
          MaintenanceSafetyGate.afterSuccessfulFlush(
              commandChannel,
              () -> {
                MaintenanceStateStore.Job stopping =
                    transition(
                        beforeStop, "STOPPING_SERVER", null, 35, null, null, null, null, Map.of());
                stopBoundaryEntered.set(true);
                service.action("stop");
                return stopping;
              });
      stoppedByUs = true;
      job = transition(job, "WAITING_FOR_STOP", null, 40, true, null, null, true, Map.of());
      waitStopped(settings.restart().stopTimeoutSeconds());
      job = transition(job, "STARTING_SERVER", null, 70, true, null, null, true, Map.of());
      service.action("start");
      job = transition(job, "VERIFYING_STARTUP", null, 85, true, null, null, true, Map.of());
      waitStarted(settings.restart().startupTimeoutSeconds());
      job = transition(job, "VERIFYING_STARTUP", null, 100, true, null, null, false, Map.of());
      state.finish(job, "SUCCESS", "");
      publish(
          "maintenance.completed",
          Map.of("jobId", job.jobId(), "kind", job.kind(), "result", "SUCCESS"));
      audit(
          "maintenance.restart",
          "SUCCESS",
          actor(device, automatic),
          automatic,
          job.jobId(),
          "",
          Map.of());
    } catch (Exception error) {
      if (closed.get()
          && error instanceof InterruptedException
          && "COUNTDOWN".equals(job.phase())) {
        Thread.currentThread().interrupt();
        return;
      }
      boolean destructiveBoundary = stoppedByUs || stopBoundaryEntered.get();
      fail(job, device, automatic, "maintenance.restart", error, destructiveBoundary);
      if (destructiveBoundary) tryStartAfterFailure();
    } finally {
      if (locked) operationLock.unlock();
    }
  }

  private void runFull(
      MaintenanceStateStore.Job original,
      DeviceRegistry.Device device,
      boolean automatic,
      boolean skipCountdown,
      boolean recoveringCountdown,
      Integer fullBackupCountdownSeconds) {
    MaintenanceStateStore.Job job = original;
    boolean locked = false, stoppedByUs = false;
    AtomicBoolean stopBoundaryEntered = new AtomicBoolean();
    NodeBackupCoordinator.Lease nodeLease = null;
    try {

      if (!operationLock.tryLock()) throw new SecurityException("BUSY");
      locked = true;
      if (fullBackups.recoveryRequired())
        throw new IllegalStateException("RESTORE_RECOVERY_REQUIRED");
      MaintenanceSettings current = settings;
      String waitingJobId = job.jobId();
      nodeLease =
          fullBackups.acquireNodeBackupLease(
              () ->
                  publish(
                      "maintenance.node.queued",
                      Map.of("jobId", waitingJobId, "kind", "FULL_RESTORE_POINT")));

      if (!recoveringCountdown) {
        job = transition(job, "PREFLIGHT", null, 5, null, null, null, null, Map.of());
        Map<String, Object> preflight = inspectPlan(job,current).toMap();
        job = transition(job, "PREFLIGHT", null, 7, null, null, null, null, preflight);
      } else {
        inspectPlan(job,current);
      }
      boolean startedOnline = !service.stopped();
      audit(
          "backup.full.create",
          "STARTED",
          actor(device, automatic),
          automatic,
          job.jobId(),
          "",
          Map.of(
              "initialServerState",
              startedOnline ? "RUNNING" : "STOPPED",
              "countdownResumed",
              recoveringCountdown));
      if (startedOnline) {
        requireCommandChannel();
        List<Integer> warningSeconds =
            MaintenanceCountdown.fullBackupWarnings(
                fullBackupCountdownSeconds == null || fullBackupCountdownSeconds == 0
                    ? MaintenanceCountdown.DEFAULT_FULL_BACKUP_COUNTDOWN_SECONDS
                    : fullBackupCountdownSeconds);
        if (!skipCountdown) {
          if (!recoveringCountdown) {
            job =
                transition(
                    job,
                    "COUNTDOWN",
                    null,
                    10,
                    null,
                    null,
                    null,
                    null,
                    Map.of(
                        "countdownInitialSeconds",
                        MaintenanceCountdown.durationSeconds(warningSeconds)));
            state.beginCountdown(job, warningSeconds, timeSource.instant());
          publishStatus();
          }
          countdown(job, MinecraftCommandChannel.MaintenanceOperation.FULL_BACKUP, warningSeconds);
        }
        job = transition(job, "FINAL_SAVE", null, 25, null, null, null, null, Map.of());
        MaintenanceStateStore.Job beforeStop = job;
        job =
            MaintenanceSafetyGate.afterSuccessfulFlush(
                commandChannel,
                () -> {
                  inspectPlan(beforeStop,current);
                  MaintenanceStateStore.Job stopping =
                      transition(
                          beforeStop,
                          "STOPPING_SERVER",
                          null,
                          35,
                          null,
                          null,
                          null,
                          null,
                          Map.of());
                  stopBoundaryEntered.set(true);
                  service.action("stop");
                  return stopping;
                });
        stoppedByUs = true;
        job = transition(job, "WAITING_FOR_STOP", null, 40, true, null, null, true, Map.of());
        waitStopped(current.restart().stopTimeoutSeconds());
      } else if (recoveringCountdown) {
        throw new IOException("SERVER_ALREADY_STOPPED");
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
      if (!startedOnline) inspectPlan(job,current);
      boolean uploadOffsite="DRIVE_AND_LOCAL".equals(state.details(job).get("effectiveDestination"));
      job = transition(job, "ARCHIVING", null, 50, null, null, null, null, Map.of());
      FullRestorePointManager.Metadata backup =
          fullBackups.createLocked(
              job.jobId(),
              actor(device, automatic),
              automatic,
              false,
              current.fullRestorePoint(),
              uploadOffsite,true);
      boolean providerConfigured = uploadOffsite;
      boolean localVerified =
          backup.sha256() != null
              && !backup.sha256().isBlank()
              && (backup.local() || backup.offsite());
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
                    "offsite",
                    backup.offsite(),
                    "errorCode",
                    errorCode,
                    "retryUploadAvailable",
                    degraded && backup.local()));
      }
      // Manual full-backup service recovery is mandatory. The serialized restartAfter field is
      // compatibility-only and cannot preserve a stopped service after this workflow stopped it.
      boolean safetyRestart = stoppedByUs && degraded;
      if (stoppedByUs) {
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
        waitStarted(current.restart().startupTimeoutSeconds());
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
      }
      String finalResult = degraded ? "DEGRADED" : "SUCCESS";
      state.finish(job, finalResult, errorCode);
      publishStatus();
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
          actor(device, automatic),
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
      if (closed.get()
          && error instanceof InterruptedException
          && "COUNTDOWN".equals(job.phase())) {
        Thread.currentThread().interrupt();
        return;
      }
      boolean destructiveBoundary = stoppedByUs || stopBoundaryEntered.get();
      fail(job, device, automatic, "backup.full.create", error, destructiveBoundary);
      if (shouldStartAfterFullBackupFailure(destructiveBoundary, job.localBackupVerified()))
        tryStartAfterFailure();
    } finally {
      try {
        if (nodeLease != null) nodeLease.close();
      } catch (IOException closeFailure) {
        publish("maintenance.node.release.failed", Map.of("errorCode", "NODE_LOCK_RELEASE_FAILED"));
      } finally {
        if (locked) operationLock.unlock();
      }
    }
  }

  private FullBackupPreflight.PreflightReport inspectPlan(MaintenanceStateStore.Job job, MaintenanceSettings current) throws IOException {
    var details=state.details(job);
    var requested=DestinationMode.parse(details.get("destinationMode"));
    boolean wasLocal="LOCAL_ONLY".equals(details.get("effectiveDestination"));
    var report=fullBackupPreflight.inspect(current,!wasLocal && requested!=DestinationMode.LOCAL_ONLY);
    var plan=requested.plan(report.provider().state(),current.fullRestorePoint().allowLocalFallback());
    if(wasLocal) plan=new DestinationMode.Plan(requested,false,false,String.valueOf(details.getOrDefault("fallbackReason","")));
    FullBackupPreflight.assertReady(report,plan.requireRemote());
    state.annotate(job,Map.of("effectiveDestination",plan.effective(),"fallbackReason",plan.fallbackReason()==null?"":plan.fallbackReason(),
        "skippedStages",plan.uploadOffsite()?List.of():BackupStage.offsiteStageOrdinals()));
    return report;
  }

  private void publishStatus() {
    try { publish("maintenance.status",status()); }
    catch(Exception error) { publish("maintenance.status",Map.of("recoveryRequired",true,"countdownState","UNAVAILABLE")); }
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

  private void countdown(
      MaintenanceStateStore.Job job,
      MinecraftCommandChannel.MaintenanceOperation operation,
      List<Integer> warnings)
      throws Exception {
    MaintenanceCountdown planner = new MaintenanceCountdown(timeSource);
    while (!closed.get()) {
      MaintenanceStateStore.Countdown countdown = state.countdown(job);
      Set<Integer> consumed = new LinkedHashSet<>(countdown.consumedWarnings());
      List<Integer> durableWarnings =
          countdown.warningSeconds().isEmpty()
              ? MaintenanceCountdown.normalized(warnings)
              : MaintenanceCountdown.normalized(countdown.warningSeconds());
      MaintenanceCountdown.Decision decision =
          planner.next(Instant.parse(countdown.deadline()), durableWarnings, consumed);
      switch (decision.action()) {
        case DONE -> {
          return;
        }
        case WAIT -> sleepUntil(decision.target());
        case SKIP -> {
          state.consumeWarning(job, decision.warningSeconds());
          state.recordCountdownRecovery(job, "WARNING_BOUNDARY_MISSED", decision.warningSeconds());
          publish(
              "maintenance.warning.skipped",
              Map.of(
                  "jobId", job.jobId(),
                  "kind", job.kind(),
                  "warningSeconds", decision.warningSeconds(),
                  "reason", "HOST_RECOVERY_MISSED_BOUNDARY"));
        }
        case SEND -> {
          // Persist before transport for at-most-once warning delivery across a Host crash.
          state.consumeWarning(job, decision.warningSeconds());
          requireSuccess(commandChannel.maintenanceNotice(operation, decision.warningSeconds()));
          publish(
              "maintenance.warning",
              Map.of(
                  "jobId", job.jobId(),
                  "kind", job.kind(),
                  "warningSeconds", decision.warningSeconds(),
                  "result", "SENT"));
        }
      }
    }
    throw new InterruptedException("Host closing");
  }

  private void sleepUntil(Instant target) throws InterruptedException {
    while (!closed.get()) {
      long millis = Duration.between(timeSource.instant(), target).toMillis();
      if (millis <= 0) return;
      Thread.sleep(Math.max(1L, Math.min(millis, 15_000L)));
    }
    throw new InterruptedException("Host closing");
  }

  private List<Integer> durableWarnings(
      MaintenanceStateStore.Job job, MaintenanceStateStore.Countdown countdown) {
    if (!countdown.warningSeconds().isEmpty())
      return MaintenanceCountdown.normalized(countdown.warningSeconds());
    if ("FULL_RESTORE_POINT".equals(job.kind()))
      return MaintenanceCountdown.fullBackupWarnings(
          MaintenanceCountdown.DEFAULT_FULL_BACKUP_COUNTDOWN_SECONDS);
    return MaintenanceCountdown.normalized(settings.restart().warningSeconds());
  }

  private void waitStopped(int seconds) throws Exception {
    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
    while (System.nanoTime() < until) {
      if (service.stopped()) return;
      Thread.sleep(250);
    }
    throw new IOException("SERVER_STOP_TIMEOUT");
  }

  private void waitStarted(int configuredSeconds) throws Exception {
    int seconds = Math.min(configuredSeconds, config.commandChannel().readinessTimeoutSeconds());
    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
    while (System.nanoTime() < until) {
      Map<String, Object> status = service.status();
      if ("active".equals(status.get("state")) && commandChannel.readinessProbe().success()) return;
      Thread.sleep(1000);
    }
    throw new IOException("SERVER_READINESS_TIMEOUT");
  }

  private void requireCommandChannel() throws IOException {
    if (!commandChannel.enabled()) throw new IOException("COMMAND_CHANNEL_DISABLED");
  }

  private static void requireSuccess(MinecraftCommandChannel.Result result) throws IOException {
    if (!result.success()) throw new IOException(result.code());
  }

  static boolean shouldStartAfterFullBackupFailure(
      boolean destructiveBoundary, boolean localBackupVerified) {
    return destructiveBoundary && localBackupVerified;
  }

  private void tryStartAfterFailure() {
    try {
      if (service.stopped()) service.action("start");
    } catch (Exception startFailure) {
      System.err.println(
          "PlexonPanel Host could not recover server start after maintenance failure");
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
      MaintenanceStateStore.Job stored = state.active();
      MaintenanceStateStore.Job latest = stored!=null && stored.jobId().equals(job.jobId()) ? stored : job;
      if (stoppedByUs && !latest.restartRecoveryRequired())
        latest =
            state.transition(
                latest, latest.phase(), latest.backupId(), null, true, null, null, true);
      state.finish(
          latest,
          outcome,
          code,
          outcome.equals("RECOVERY_REQUIRED")
              ? "Maintenance failed after the Host may have stopped Minecraft; verify service state"
                    + " before retrying."
              : "Maintenance failed before the destructive stop boundary.");
    } catch (Exception ignored) {
    }
    publishStatus();
    publish(
        outcome.equals("RECOVERY_REQUIRED")
            ? "maintenance.recovery.required"
            : "maintenance.failed",
        Map.of("jobId", job.jobId(), "kind", job.kind(), "errorCode", code));
    audit(
        action,
        outcome,
        actor(device, automatic),
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
            ? "A previous Host maintenance job requires recovery before another destructive"
                  + " operation can start."
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
    body.put("capturedAt", timeSource.instant().toString());
    try { body.putAll(state.snapshot(job)); } catch(IOException ignored) { }
    body.putAll(extra);
    publish("maintenance.phase", body);
    publishStatus();
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
      entry.put("timestamp", timeSource.instant().toString());
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

  private static String requesterDeviceId(DeviceRegistry.Device device, boolean automatic) {
    return automatic ? "local-schedule" : device == null ? "local-host" : device.deviceId();
  }

  private static String requesterDeviceName(DeviceRegistry.Device device, boolean automatic) {
    return automatic ? "Host schedule" : device == null ? "Local host" : device.name();
  }

  private static String actor(DeviceRegistry.Device device, boolean automatic) {
    return requesterDeviceName(device, automatic);
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
    beginClose();
    if (!awaitClosed(Duration.ofSeconds(30))) System.err.println("MAINTENANCE_DRAIN_TIMEOUT");
  }

  public void beginClose() {
    if (!closed.compareAndSet(false, true)) return;
    scheduler.shutdownNow();
    worker.shutdownNow();
  }

  public boolean awaitClosed(Duration timeout) {
    long started = System.nanoTime();
    boolean workerDrained = ExecutorDrain.await(worker, timeout);
    boolean schedulerDrained =
        ExecutorDrain.await(
            scheduler,
            Duration.ofNanos(Math.max(0, timeout.toNanos() - (System.nanoTime() - started))));
    if (workerDrained && schedulerDrained && commandChannelClosed.compareAndSet(false, true))
      commandChannel.close();
    return workerDrained && schedulerDrained;
  }

  private record Due(String scheduleId, Instant occurrence) {}
}
