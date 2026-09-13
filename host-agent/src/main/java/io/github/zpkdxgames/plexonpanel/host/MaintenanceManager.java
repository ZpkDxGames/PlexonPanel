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
import java.util.function.*;

/** Host-authoritative calendar scheduler and destructive-operation orchestrator. */
public final class MaintenanceManager implements AutoCloseable {
  private final HostConfig config;
  private final Path settingsPath;
  private final MaintenanceStateStore state;
  private final FullRestorePointManager fullBackups;
  private final SystemdService service;
  private final PaperMaintenanceLink paperLink;
  private final BooleanSupplier paperConnected;
  private final LongSupplier paperRevision;
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
    this.operationLock = operationLock;
    this.audit = audit;
    this.events = events;
    this.fullBackups = fullBackups;
    Path data = Path.of(config.dataDirectory()).toAbsolutePath().normalize();
    this.settingsPath = data.resolve("maintenance-settings.json");
    this.state = new MaintenanceStateStore(data);
    this.settings = MaintenanceSettings.load(settingsPath);
    if (!Files.exists(settingsPath)) this.settings.save(settingsPath);
  }

  public void start() {
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
    result.put("currentOperation", active == null ? Map.of() : active);
    result.put("provider", fullBackups.providerStatus());
    result.put("restoreRecoveryRequired", fullBackups.recoveryRequired());
    result.put("jobRecoveryRequired", state.recoveryRequired() != null);
    return result;
  }

  public String restartNow(DeviceRegistry.Device device, boolean skipCountdown) throws Exception {
    return queue("RESTART", null, device, false, skipCountdown);
  }

  public String fullRestorePointNow(DeviceRegistry.Device device, boolean skipCountdown) throws Exception {
    return queue("FULL_RESTORE_POINT", null, device, false, skipCountdown);
  }

  private synchronized String queue(
      String kind,
      Instant occurrence,
      DeviceRegistry.Device device,
      boolean automatic,
      boolean skipCountdown)
      throws Exception {
    if (closed.get()) throw new IllegalStateException("HOST_OFFLINE");
    if (state.recoveryRequired() != null || fullBackups.recoveryRequired())
      throw new IllegalStateException("RESTORE_RECOVERY_REQUIRED");
    if (worker.getQueue().remainingCapacity() == 0 || operationLock.isLocked())
      throw new SecurityException("BUSY");
    MaintenanceStateStore.Job job =
        state.begin(kind, occurrence, automatic, skipCountdown ? "PREPARING" : "COUNTDOWN");
    try {
      worker.execute(
          () -> {
            if (kind.equals("RESTART")) runRestart(job, device, automatic, skipCountdown);
            else runFull(job, device, automatic, skipCountdown);
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
      System.err.println("PlexonPanel Host maintenance scheduler: " + error.getClass().getSimpleName());
    }
  }

  private synchronized void tick() throws Exception {
    if (closed.get() || state.recoveryRequired() != null || fullBackups.recoveryRequired()) return;
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
      publishPhase(job, "COUNTDOWN", Map.of());
      if (!skipCountdown) countdown(device, automatic, settings.restart().warningSeconds(), "restart");
      job = state.update(job, "PREPARING", null);
      publishPhase(job, "PREPARING", Map.of());
      if (!paperLink.flush(device, automatic)) throw new IOException("PAPER_FLUSH_FAILED");
      long revision = paperRevision.getAsLong();
      job = state.update(job, "STOPPING_SERVER", null);
      publishPhase(job, "STOPPING_SERVER", Map.of());
      service.action("stop");
      stoppedByUs = true;
      waitStopped(settings.restart().stopTimeoutSeconds());
      job = state.update(job, "STARTING_SERVER", null);
      publishPhase(job, "STARTING_SERVER", Map.of());
      service.action("start");
      waitStarted(revision, settings.restart().startupTimeoutSeconds());
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

  private void runFull(
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
      boolean startedOnline = !service.stopped();
      long revision = paperRevision.getAsLong();
      audit(
          "backup.full.create",
          "STARTED",
          actor(device, automatic),
          automatic,
          job.jobId(),
          "",
          Map.of("initialServerState", startedOnline ? "RUNNING" : "STOPPED"));
      if (startedOnline) {
        if (!paperConnected.getAsBoolean()) throw new IllegalStateException("PAPER_OFFLINE");
        publishPhase(job, "COUNTDOWN", Map.of());
        if (!skipCountdown)
          countdown(device, automatic, settings.restart().warningSeconds(), "weekly restore point");
        job = state.update(job, "PREPARING", null);
        publishPhase(job, "PREPARING", Map.of());
        if (!paperLink.flush(device, automatic)) throw new IOException("PAPER_FLUSH_FAILED");
        job = state.update(job, "STOPPING_SERVER", null);
        publishPhase(job, "STOPPING_SERVER", Map.of());
        service.action("stop");
        stoppedByUs = true;
        waitStopped(settings.restart().stopTimeoutSeconds());
      } else {
        job = state.update(job, "PREPARING", null);
        publishPhase(job, "PREPARING", Map.of("serverAlreadyStopped", true));
      }
      job = state.update(job, "ARCHIVING", null);
      publishPhase(job, "ARCHIVING", Map.of());
      FullRestorePointManager.Metadata backup =
          fullBackups.createLocked(
              job.jobId(), actor(device, automatic), automatic, false, settings.fullRestorePoint(), true);
      boolean startAfter = stoppedByUs && settings.fullRestorePoint().restartAfter();
      job = state.update(job, startAfter ? "STARTING_SERVER" : "FINALIZING", backup.backupId());
      publishPhase(
          job,
          startAfter ? "STARTING_SERVER" : "FINALIZING",
          Map.of(
              "backupId", backup.backupId(),
              "local", backup.local(),
              "offsite", backup.offsite(),
              "verification", backup.verification(),
              "preservedStoppedState", !stoppedByUs));
      if (startAfter) {
        service.action("start");
        waitStarted(revision, settings.restart().startupTimeoutSeconds());
      }
      state.finish(job, "SUCCESS", backup.errorCode());
      publish(
          "maintenance.completed",
          Map.of(
              "jobId", job.jobId(),
              "kind", job.kind(),
              "backupId", backup.backupId(),
              "local", true,
              "offsite", backup.offsite(),
              "result", backup.offsite() || !fullBackups.providerStatus().get("configured").equals(Boolean.TRUE)
                  ? "SUCCESS"
                  : "DEGRADED"));
      audit(
          "backup.full.create",
          backup.offsite() || !Boolean.TRUE.equals(fullBackups.providerStatus().get("configured")) ? "SUCCESS" : "DEGRADED",
          actor(device, automatic),
          automatic,
          job.jobId(),
          backup.backupId(),
          Map.of(
              "sha256", backup.sha256(),
              "archiveBytes", backup.archiveBytes(),
              "offsite", backup.offsite(),
              "serverWasStoppedByMaintenance", stoppedByUs));
    } catch (Exception error) {
      fail(job, device, automatic, "backup.full.create", error);
      if (stoppedByUs) tryStartAfterFailure();
    } finally {
      if (locked) operationLock.unlock();
    }
  }

  private void countdown(
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

  private void waitStarted(long priorRevision, int seconds) throws Exception {
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
      Exception error) {
    String code = classify(error);
    try {
      state.finish(job, "FAILED", code);
    } catch (Exception ignored) {
    }
    publish(
        "maintenance.failed",
        Map.of("jobId", job.jobId(), "kind", job.kind(), "errorCode", code));
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

  private static String actor(DeviceRegistry.Device device, boolean automatic) {
    return automatic ? "Host schedule" : device == null ? "Local host" : device.name();
  }

  private static String humanDuration(int seconds) {
    if (seconds >= 60 && seconds % 60 == 0) {
      int minutes = seconds / 60;
      return minutes + (minutes == 1 ? " minute" : " minutes");
    }
    return seconds + (seconds == 1 ? " second" : " seconds");
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
