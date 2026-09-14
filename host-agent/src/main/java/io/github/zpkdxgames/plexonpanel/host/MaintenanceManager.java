package io.github.zpkdxgames.plexonpanel.host;

import com.google.gson.JsonElement;
import io.github.zpkdxgames.plexonpanel.audit.LocalAudit;
import io.github.zpkdxgames.plexonpanel.protocol.*;
import io.github.zpkdxgames.plexonpanel.security.DeviceRegistry;
import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/** Host-authoritative restart scheduler and manual full-backup orchestrator. */
public final class MaintenanceManager implements AutoCloseable {
  private static final List<Integer> FULL_BACKUP_WARNINGS = List.of(1800, 900, 60, 30, 15, 5);

  private final HostConfig config;
  private final Path settingsPath;
  private final MaintenanceStateStore state;
  private final FullRestorePointManager fullBackups;
  private final SystemdService service;
  private final MinecraftCommandChannel commands;
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
      MinecraftCommandChannel commands,
      ReentrantLock operationLock,
      LocalAudit audit,
      MessageSink events,
      FullRestorePointManager fullBackups)
      throws IOException {
    this.config = Objects.requireNonNull(config);
    this.service = Objects.requireNonNull(service);
    this.commands = Objects.requireNonNull(commands);
    this.operationLock = Objects.requireNonNull(operationLock);
    this.audit = Objects.requireNonNull(audit);
    this.events = Objects.requireNonNull(events);
    this.fullBackups = Objects.requireNonNull(fullBackups);
    Path data = Path.of(config.dataDirectory()).toAbsolutePath().normalize();
    this.settingsPath = data.resolve("maintenance-settings.json");
    this.state = new MaintenanceStateStore(data);
    this.settings = manualOnly(MaintenanceSettings.load(settingsPath));
    this.settings.save(settingsPath);
  }

  /** Only restart scheduling remains automatic. Full backups are manual-only. */
  public void start() {
    clock.scheduleWithFixedDelay(this::tickSafely, 2, 15, TimeUnit.SECONDS);
  }

  public MaintenanceSettings settings() {
    return settings;
  }

  public synchronized MaintenanceSettings updateSettings(JsonElement value) throws IOException {
    MaintenanceSettings next = manualOnly(MaintenanceSettings.fromJson(value));
    next.save(settingsPath);
    settings = next;
    audit(
        "maintenance.settings.update",
        "SUCCESS",
        "dashboard",
        false,
        "",
        "",
        Map.of("timezone", next.timezone(), "fullBackupMode", "MANUAL_ONLY"));
    publish(
        "maintenance.settings.updated",
        Map.of("timezone", next.timezone(), "fullBackupMode", "MANUAL_ONLY"));
    return next;
  }

  public Map<String, Object> status() throws Exception {
    MaintenanceSettings current = settings;
    ZoneId zone = ZoneId.of(current.timezone());
    Instant restart = MaintenanceSchedule.next(current.restart().schedule(), zone, Instant.now());
    MaintenanceStateStore.Job active = state.active();
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("timezone", current.timezone());
    result.put("nextRestart", restart == null ? "" : restart.toString());
    result.put("fullBackupMode", "MANUAL_ONLY");
    result.put("currentOperation", active == null ? Map.of() : active);
    result.put("provider", fullBackups.providerStatus());
    result.put("commandChannelConfigured", commands.configured());
    result.put("restoreRecoveryRequired", fullBackups.recoveryRequired());
    result.put("jobRecoveryRequired", state.recoveryRequired() != null);
    return result;
  }

  public String restartNow(DeviceRegistry.Device device, boolean skipCountdown) throws Exception {
    return queue("RESTART", null, device, false, skipCountdown);
  }

  public String fullRestorePointNow(DeviceRegistry.Device device, boolean ignoredSkipCountdown)
      throws Exception {
    // Manual full backups always own the complete 30-minute warning sequence.
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
    if (kind.equals("FULL_RESTORE_POINT") && automatic)
      throw new SecurityException("AUTOMATIC_BACKUP_DISABLED");
    if (state.recoveryRequired() != null || fullBackups.recoveryRequired())
      throw new IllegalStateException("RESTORE_RECOVERY_REQUIRED");
    if (worker.getQueue().remainingCapacity() == 0 || operationLock.isLocked())
      throw new SecurityException("BUSY");
    MaintenanceStateStore.Job job =
        state.begin(kind, occurrence, automatic, skipCountdown ? "PREFLIGHT" : "COUNTDOWN");
    try {
      worker.execute(
          () -> {
            if (kind.equals("RESTART")) runRestart(job, device, automatic, skipCountdown);
            else runFull(job, device);
          });
    } catch (RejectedExecutionException busy) {
      state.finish(job, "FAILED", "BUSY");
      throw new SecurityException("BUSY");
    }
    return job.jobId();
  }

  private void tickSafely() {
    try {
      tick();
    } catch (Exception error) {
      System.err.println("PlexonPanel Host restart scheduler: " + error.getClass().getSimpleName());
    }
  }

  private synchronized void tick() throws Exception {
    if (closed.get() || state.recoveryRequired() != null || fullBackups.recoveryRequired()) return;
    MaintenanceSettings current = settings;
    ZoneId zone = ZoneId.of(current.timezone());
    Instant now = Instant.now(), windowStart = now.minus(Duration.ofMinutes(10));
    Due restart = due("restart", current.restart().schedule(), zone, windowStart, now);
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
        return;
      }
      commands.requireReady();
      audit("maintenance.restart", "STARTED", actor(device, automatic), automatic, job.jobId(), "", Map.of());
      publishPhase(job, "COUNTDOWN", Map.of());
      if (!skipCountdown) countdown(settings.restart().warningSeconds(), false);
      job = state.update(job, "FINAL_SAVE", null);
      publishPhase(job, "FINAL_SAVE", Map.of());
      commands.flush();
      job = state.update(job, "STOPPING_SERVER", null);
      publishPhase(job, "STOPPING_SERVER", Map.of());
      service.action("stop");
      stoppedByUs = true;
      job = state.update(job, "WAITING_FOR_STOP", null);
      waitStopped(settings.restart().stopTimeoutSeconds());
      job = state.update(job, "STARTING_SERVER", null);
      publishPhase(job, "STARTING_SERVER", Map.of());
      service.action("start");
      job = state.update(job, "VERIFYING_STARTUP", null);
      waitStarted(settings.restart().startupTimeoutSeconds());
      state.finish(job, "SUCCESS", "");
      publish("maintenance.completed", Map.of("jobId", job.jobId(), "kind", job.kind(), "result", "SUCCESS"));
      audit("maintenance.restart", "SUCCESS", actor(device, automatic), automatic, job.jobId(), "", Map.of());
    } catch (Exception error) {
      fail(job, device, automatic, "maintenance.restart", error);
      if (stoppedByUs) tryStartAfterFailure();
    } finally {
      if (locked) operationLock.unlock();
    }
  }

  private void runFull(MaintenanceStateStore.Job original, DeviceRegistry.Device device) {
    MaintenanceStateStore.Job job = original;
    boolean locked = false, stoppedByUs = false;
    try {
      if (!operationLock.tryLock()) throw new SecurityException("BUSY");
      locked = true;
      if (fullBackups.recoveryRequired()) throw new IllegalStateException("RESTORE_RECOVERY_REQUIRED");
      boolean startedOnline = !service.stopped();
      audit(
          "backup.full.create",
          "STARTED",
          actor(device, false),
          false,
          job.jobId(),
          "",
          Map.of("initialServerState", startedOnline ? "RUNNING" : "STOPPED"));

      job = state.update(job, "PREFLIGHT", null);
      publishPhase(job, "PREFLIGHT", Map.of());
      commands.requireConfigured();
      Map<String, Object> provider = fullBackups.providerStatus();
      if (!Boolean.TRUE.equals(provider.get("configured")))
        throw new IllegalStateException("PROVIDER_NOT_CONFIGURED");
      fullBackups.testProvider(15);

      if (startedOnline) {
        commands.requireReady();
        job = state.update(job, "COUNTDOWN", null);
        publishPhase(job, "COUNTDOWN", Map.of("warningSeconds", FULL_BACKUP_WARNINGS));
        countdown(FULL_BACKUP_WARNINGS, true);
        job = state.update(job, "FINAL_SAVE", null);
        publishPhase(job, "FINAL_SAVE", Map.of());
        commands.flush();
        job = state.update(job, "STOPPING_SERVER", null);
        publishPhase(job, "STOPPING_SERVER", Map.of());
        service.action("stop");
        stoppedByUs = true;
        job = state.update(job, "WAITING_FOR_STOP", null);
        publishPhase(job, "WAITING_FOR_STOP", Map.of());
        waitStopped(settings.restart().stopTimeoutSeconds());
      }

      job = state.update(job, "ARCHIVING", null);
      publishPhase(job, "ARCHIVING", Map.of());
      FullRestorePointManager.Metadata backup =
          fullBackups.createLocked(
              job.jobId(), actor(device, false), false, false, settings.fullRestorePoint(), true);
      job = state.update(job, "VERIFYING_LOCAL", backup.backupId());
      fullBackups.verify(backup.backupId());

      boolean degraded = !backup.offsite();
      if (startedOnline) {
        job = state.update(job, "STARTING_SERVER", backup.backupId());
        publishPhase(job, "STARTING_SERVER", Map.of("backupId", backup.backupId()));
        service.action("start");
        job = state.update(job, "VERIFYING_STARTUP", backup.backupId());
        waitStarted(settings.restart().startupTimeoutSeconds());
      }

      state.finish(job, degraded ? "DEGRADED" : "SUCCESS", backup.errorCode());
      publish(
          "maintenance.completed",
          Map.of(
              "jobId", job.jobId(),
              "kind", job.kind(),
              "backupId", backup.backupId(),
              "local", true,
              "offsite", backup.offsite(),
              "result", degraded ? "DEGRADED" : "SUCCESS"));
      audit(
          "backup.full.create",
          degraded ? "DEGRADED" : "SUCCESS",
          actor(device, false),
          false,
          job.jobId(),
          backup.backupId(),
          Map.of(
              "sha256", backup.sha256(),
              "archiveBytes", backup.archiveBytes(),
              "offsite", backup.offsite(),
              "serverWasStoppedByMaintenance", stoppedByUs));
    } catch (Exception error) {
      fail(job, device, false, "backup.full.create", error);
      if (stoppedByUs) tryStartAfterFailure();
    } finally {
      if (locked) operationLock.unlock();
    }
  }

  private void countdown(List<Integer> warnings, boolean fullBackup) throws Exception {
    List<Integer> sorted = new ArrayList<>(new TreeSet<>(warnings));
    sorted.sort(Comparator.reverseOrder());
    int previous = sorted.isEmpty() ? 0 : sorted.get(0);
    for (int i = 0; i < sorted.size(); i++) {
      int remaining = sorted.get(i);
      if (i > 0) sleepInterruptibly(previous - remaining);
      if (fullBackup) commands.notice(remaining);
      else commands.restartNotice(remaining);
      previous = remaining;
    }
    if (previous > 0) sleepInterruptibly(previous);
  }

  private void waitStopped(int seconds) throws Exception {
    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
    while (System.nanoTime() < until) {
      Map<String, Object> status = service.status();
      long pid = status.get("pid") instanceof Number n ? n.longValue() : -1L;
      if (service.stopped() && pid == 0L && !commands.reachable()) return;
      Thread.sleep(250);
    }
    throw new IOException("SERVER_STOP_TIMEOUT");
  }

  private void waitStarted(int seconds) throws Exception {
    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
    while (System.nanoTime() < until) {
      Map<String, Object> status = service.status();
      long pid = status.get("pid") instanceof Number n ? n.longValue() : 0L;
      if ("active".equals(status.get("state")) && pid > 0L) {
        try {
          commands.requireReady();
          return;
        } catch (Exception notReadyYet) {
          // Continue until the configured startup deadline.
        }
      }
      Thread.sleep(250);
    }
    throw new IOException("MINECRAFT_READINESS_TIMEOUT");
  }

  private void sleepInterruptibly(int seconds) throws InterruptedException {
    if (seconds > 0) TimeUnit.SECONDS.sleep(seconds);
  }

  private void tryStartAfterFailure() {
    try {
      if (service.stopped()) {
        service.action("start");
        waitStarted(config.commandChannel().readinessTimeoutSeconds());
      }
    } catch (Exception startFailure) {
      System.err.println("PlexonPanel Host could not recover Minecraft after maintenance failure");
    }
  }

  private void fail(
      MaintenanceStateStore.Job job,
      DeviceRegistry.Device device,
      boolean automatic,
      String action,
      Exception error) {
    String code = classify(error);
    try {
      state.finish(job, "FAILED", code);
    } catch (Exception ignored) {
    }
    publish("maintenance.failed", Map.of("jobId", job.jobId(), "kind", job.kind(), "errorCode", code));
    audit(action, "FAILED", actor(device, automatic), automatic, job.jobId(), job.backupId(), Map.of("errorCode", code));
  }

  private void publishPhase(MaintenanceStateStore.Job job, String phase, Map<String, Object> extra) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("jobId", job.jobId());
    body.put("kind", job.kind());
    body.put("phase", phase);
    body.put("automatic", job.automatic());
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

  private static MaintenanceSettings manualOnly(MaintenanceSettings value) {
    MaintenanceSettings.FullRestorePoint full = value.fullRestorePoint();
    MaintenanceSettings.Schedule disabled =
        new MaintenanceSettings.Schedule(false, "WEEKLY", List.of(DayOfWeek.SUNDAY.name()), full.schedule().time());
    return new MaintenanceSettings(
        value.schemaVersion(),
        value.timezone(),
        value.restart(),
        new MaintenanceSettings.FullRestorePoint(
            disabled,
            full.retentionMode(),
            full.retentionCount(),
            true,
            full.canonicalFilename(),
            full.uploadTimeoutSeconds(),
            full.verificationMode(),
            full.maximumBytes(),
            full.excludes()));
  }

  private static String actor(DeviceRegistry.Device device, boolean automatic) {
    return automatic ? "Host restart schedule" : device == null ? "Local host" : device.name();
  }

  private static String classify(Exception error) {
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
