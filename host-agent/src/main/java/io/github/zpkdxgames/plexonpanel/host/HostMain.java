package io.github.zpkdxgames.plexonpanel.host;

import static io.github.zpkdxgames.plexonpanel.control.JsonFields.*;

import io.github.zpkdxgames.plexonpanel.audit.LocalAudit;
import io.github.zpkdxgames.plexonpanel.control.*;
import io.github.zpkdxgames.plexonpanel.files.*;
import io.github.zpkdxgames.plexonpanel.identity.*;
import io.github.zpkdxgames.plexonpanel.protocol.*;
import io.github.zpkdxgames.plexonpanel.security.*;
import io.github.zpkdxgames.plexonpanel.telemetry.SystemMetrics;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.ReentrantLock;

public final class HostMain {
  private static final long FAST_TELEMETRY_MILLIS = 250L;
  private static final long SERVICE_STATUS_SECONDS = 5L;

  private HostMain() {}

  public static void main(String[] args) throws Exception {
    if (args.length == 2 && args[0].equals("--init")) {
      DeviceIdentity identity = new IdentityStore(Path.of(args[1])).loadOrCreate();
      System.out.println("Host public key: " + identity.publicKeyBase64());
      System.out.println("Host fingerprint: " + identity.fingerprint());
      return;
    }
    if (args.length < 1 || args.length > 2)
      throw new IllegalArgumentException(
          "Usage: java -jar plexonpanel-host.jar <host-config.json> [--recover-restore]");
    if (!System.getProperty("os.name").equals("Linux")
        || ProcessHandle.current().info().user().orElse("root").equals("root"))
      throw new SecurityException("Run the host companion as a dedicated non-root Linux user");
    Path configPath = Path.of(args[0]).toAbsolutePath().normalize();
    Instant hostStartedAt = Instant.now();
    long configLoadedMtime = Files.getLastModifiedTime(configPath, LinkOption.NOFOLLOW_LINKS).toMillis();
    HostConfig config = HostConfig.load(configPath);
    Path data = Path.of(config.dataDirectory());
    Files.createDirectories(data);
    DeviceIdentity stored = new IdentityStore(data).loadOrCreate(),
        identity =
            new DeviceIdentity(
                UUID.fromString(config.serverId()), stored.createdAt(), stored.keyPair());
    Path legacyAccessRegistry = Path.of(config.accessRegistry()).toAbsolutePath().normalize();
    HostAuthorizationMirror authorization =
        new HostAuthorizationMirror(data.resolve("access"), config.serverId());
    authorization.bootstrapLegacy(legacyAccessRegistry);
    DeviceRegistry devices = authorization.registry();
    LocalAudit audit = new LocalAudit(data.resolve("audit"), 30);
    audit.clean();
    HostConnection connection = new HostConnection(config, identity);
    SystemdService service = new SystemdService(config.serviceName());
    PaperSaveLease leases = new PaperSaveLease(connection, devices);
    PaperMaintenanceLink maintenanceLink = new PaperMaintenanceLink(connection, devices);
    AtomicBoolean paper = new AtomicBoolean();
    AtomicLong paperRevision = new AtomicLong();
    ReentrantLock operationLock = new ReentrantLock();
    BackupManager backups =
        new BackupManager(
            config,
            service,
            leases,
            paper::get,
            progress -> connection.send("backup.progress", progress, MessagePriority.EVENT),
            operationLock);
    FullRestorePointManager fullBackups =
        new FullRestorePointManager(
            config,
            service,
            paper::get,
            operationLock,
            progress -> connection.send("backup.progress", progress, MessagePriority.EVENT));
    MaintenanceManager maintenance =
        new MaintenanceManager(
            config,
            service,
            maintenanceLink,
            paper::get,
            paperRevision::get,
            operationLock,
            audit,
            connection,
            fullBackups);
    BackupTransfers transfers = new BackupTransfers();
    if (args.length == 2) {
      if (!args[1].equals("--recover-restore"))
        throw new IllegalArgumentException("Unknown local operation");
      backups.recover();
      fullBackups.recoverRestore();
      maintenance.close();
      maintenanceLink.close();
      leases.close();
      connection.close();
      return;
    }
    var caps = config.effectiveCapabilities();
    Path root = Path.of(config.serverRoot());
    SafeFiles files =
        new SafeFiles(
            new PathPolicy(
                Map.of("server", root),
                List.of(data, legacyAccessRegistry.getParent())),
            Set.of("server"));
    SystemMetrics metrics = new SystemMetrics(root);
    ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    ScheduledExecutorService telemetryScheduler = Executors.newSingleThreadScheduledExecutor();
    ThreadPoolExecutor scheduledBackups =
        new ThreadPoolExecutor(
            1,
            1,
            0,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(1),
            new ThreadPoolExecutor.AbortPolicy());
    ControlEngine engine =
        new ControlEngine(
            devices,
            caps,
            audit,
            files,
            (action, p, device) -> {
              switch (action) {
                case "backup.list" -> {
                  var all = backups.list();
                  int page = (int) integer(p, "page", 0, 0, 100);
                  int first = Math.min(all.size(), page * 50),
                      last = Math.min(all.size(), first + 50);
                  return Map.of(
                      "backups",
                      all.subList(first, last),
                      "page",
                      page,
                      "hasMore",
                      last < all.size(),
                      "provider",
                      fullBackups.providerStatus().getOrDefault("provider", "UNKNOWN"),
                      "recoveryRequired",
                      backups.recoveryRequired());
                }
                case "backup.preflight" -> {
                  var result = new LinkedHashMap<>(backups.preflight(connection::authenticated));
                  result.putAll(fullBackups.providerStatus());
                  result.put("hostStartedAt", hostStartedAt.toString());
                  result.put("hostConfigLoadedAt", Instant.ofEpochMilli(configLoadedMtime).toString());
                  result.put("hostConfigRestartRequired", configChanged(configPath, configLoadedMtime));
                  return Map.copyOf(result);
                }
                case "backup.create" -> {
                  var result = backups.create(device, false, false);
                  return Map.of(
                      "backup",
                      result,
                      "backupId",
                      result.backupId(),
                      "sha256",
                      result.sha256(),
                      "bytes",
                      result.bytes());
                }
                case "backup.delete" -> {
                  backups.delete(text(p, "backupId", 36));
                  return Map.of();
                }
                case "backup.restore.prepare" -> {
                  return backups.prepareRestore(text(p, "backupId", 36), device);
                }
                case "backup.restore" -> {
                  return backups.restore(
                      text(p, "backupId", 36),
                      text(p, "confirmationToken", 36),
                      text(p, "serverName", 64),
                      device);
                }
                case "backup.download" -> {
                  String id = text(p, "backupId", 36);
                  return transfers.start(backups.archive(id), backups.metadata(id), device);
                }
                case "backup.download.chunk" -> {
                  return transfers.chunk(
                      text(p, "transferId", 36),
                      (int) integer(p, "sequence", -1, 0, 4096),
                      device.deviceId());
                }
                case "backup.download.cancel" -> {
                  transfers.cancel(text(p, "transferId", 36), device.deviceId());
                  return Map.of();
                }
                case "backup.full.list" -> {
                  var all = fullBackups.list();
                  int page = (int) integer(p, "page", 0, 0, 100);
                  int first = Math.min(all.size(), page * 50),
                      last = Math.min(all.size(), first + 50);
                  return Map.of(
                      "backups", all.subList(first, last),
                      "page", page,
                      "hasMore", last < all.size(),
                      "recoveryRequired", fullBackups.recoveryRequired());
                }
                case "backup.full.verify" -> {
                  var result = fullBackups.verify(text(p, "backupId", 36));
                  return Map.of("backupId", result.backupId(), "sha256", result.sha256(), "verification", "VERIFIED");
                }
                case "backup.full.retry-upload" -> {
                  var result =
                      fullBackups.retryUpload(
                          text(p, "backupId", 36),
                          UUID.randomUUID().toString(),
                          maintenance.settings().fullRestorePoint());
                  return Map.of("backup", result);
                }
                case "backup.full.delete" -> {
                  fullBackups.delete(text(p, "backupId", 36));
                  return Map.of();
                }
                case "backup.full.restore.prepare" -> {
                  requireOwner(device);
                  String id = text(p, "backupId", 36);
                  var grant = fullBackups.prepareRestore(id, device.deviceId());
                  return Map.of(
                      "confirmationToken", grant.token(),
                      "serverName", config.serverName(),
                      "expiresInSeconds", 60,
                      "message", "A verified emergency full restore point will be created before replacement.");
                }
                case "backup.full.restore" -> {
                  requireOwner(device);
                  return fullBackups.restore(
                      text(p, "backupId", 36),
                      text(p, "confirmationToken", 36),
                      text(p, "serverName", 64),
                      device.deviceId(),
                      device.name(),
                      maintenance.settings().fullRestorePoint(),
                      !p.has("startAfter") || p.get("startAfter").getAsBoolean());
                }
                case "maintenance.status" -> {
                  return maintenance.status();
                }
                case "maintenance.settings.get" -> {
                  return Map.of("settings", maintenance.settings());
                }
                case "maintenance.settings.update" -> {
                  if (!p.has("settings") || !p.get("settings").isJsonObject())
                    throw new IllegalArgumentException("SCHEDULE_INVALID");
                  return Map.of("settings", maintenance.updateSettings(p.get("settings")));
                }
                case "maintenance.restart.now" -> {
                  boolean skip = p.has("skipCountdown") && p.get("skipCountdown").getAsBoolean();
                  return Map.of("jobId", maintenance.restartNow(device, skip), "state", "QUEUED");
                }
                case "maintenance.full-backup.create" -> {
                  boolean skip = p.has("skipCountdown") && p.get("skipCountdown").getAsBoolean();
                  return Map.of("jobId", maintenance.fullRestorePointNow(device, skip), "state", "QUEUED");
                }
                case "provider.status" -> {
                  var result = new LinkedHashMap<>(fullBackups.providerStatus());
                  result.put("hostStartedAt", hostStartedAt.toString());
                  result.put("hostConfigLoadedAt", Instant.ofEpochMilli(configLoadedMtime).toString());
                  result.put("hostConfigRestartRequired", configChanged(configPath, configLoadedMtime));
                  return Map.copyOf(result);
                }
                case "provider.test" -> {
                  return fullBackups.testProvider(30);
                }
                case "server.status" -> {
                  var status = new HashMap<>(service.status());
                  status.put("paperConnected", paper.get());
            status.put("authorizationMirror", authorization.status());
                  status.put("recoveryRequired", backups.recoveryRequired() || fullBackups.recoveryRequired());
                  return status;
                }
                case "server.start", "server.stop", "server.restart" -> {
                  if (!operationLock.tryLock()) throw new SecurityException("BUSY");
                  try {
                    if (backups.recoveryRequired() || fullBackups.recoveryRequired())
                      throw new SecurityException("RESTORE_RECOVERY_REQUIRED");
                    long revision = paperRevision.get();
                    if (action.equals("server.restart") || action.equals("server.stop")) {
                      service.action("stop");
                      if (!service.stopped())
                        throw new IllegalStateException("Service did not stop");
                    }
                    if (!action.equals("server.stop")) {
                      if (action.equals("server.start") && !service.stopped())
                        throw new IllegalStateException("Service is already running");
                      service.action("start");
                      long until = System.nanoTime() + TimeUnit.MINUTES.toNanos(3);
                      while ((!paper.get() || paperRevision.get() <= revision)
                          && System.nanoTime() < until) Thread.sleep(250);
                      if (!paper.get() || paperRevision.get() <= revision)
                        throw new IllegalStateException(
                            "Service command accepted, but authenticated Paper reconnection was not observed");
                    }
                    return Map.of(
                        "state",
                        action.equals("server.stop") ? "stopped" : "running",
                        "authenticatedPaperObserved",
                        !action.equals("server.stop"));
                  } finally {
                    operationLock.unlock();
                  }
                }
                default -> throw new SecurityException("UNKNOWN_ACTION");
              }
            },
            connection,
            connection::authenticated,
            config.serverId());
    Runnable fastSnapshot =
        () -> {
          if (!connection.authenticated()) return;
          try {
            var sample = new LinkedHashMap<>(metrics.collect());
            sample.put("sourceIntervalMillis", FAST_TELEMETRY_MILLIS);
            connection.send("telemetry.system", sample, MessagePriority.TELEMETRY);
          } catch (Exception e) {
            System.err.println("Host telemetry unavailable: " + e.getClass().getSimpleName());
          }
        };
    Runnable serviceSnapshot =
        () -> {
          if (!connection.authenticated()) return;
          try {
            var status = new HashMap<>(service.status());
            status.put("paperConnected", paper.get());
            status.put("authorizationMirror", authorization.status());
            status.put("recoveryRequired", backups.recoveryRequired() || fullBackups.recoveryRequired());
            connection.send("service.status", status, MessagePriority.TELEMETRY);
          } catch (Exception e) {
            System.err.println("Host service snapshot unavailable: " + e.getClass().getSimpleName());
          }
        };
    connection.handlers(
        m -> {
          switch (m.envelope().type()) {
            case "paper.connection" -> {
              boolean online = bool(m.body(), "connected");
              paper.set(online);
              if (online) paperRevision.incrementAndGet();
            }
            case "access.authority.sync" -> {
              try {
                authorization.apply(m.body());
              } catch (Exception rejected) {
                System.err.println(
                    "PlexonPanel Host access mirror rejected snapshot: "
                        + rejected.getClass().getSimpleName()
                        + ": "
                        + rejected.getMessage());
              }
            }
            case "backup.coordination.result" -> leases.accept(m);
            case "maintenance.coordination.result" -> maintenanceLink.accept(m);
            default -> engine.accept(m);
          }
        },
        () -> {
          connection.send("access.authority.request", Map.of(), MessagePriority.CRITICAL);
          telemetryScheduler.execute(fastSnapshot);
          scheduler.execute(serviceSnapshot);
        });
    telemetryScheduler.scheduleWithFixedDelay(
        fastSnapshot, FAST_TELEMETRY_MILLIS, FAST_TELEMETRY_MILLIS, TimeUnit.MILLISECONDS);
    scheduler.scheduleWithFixedDelay(
        serviceSnapshot, SERVICE_STATUS_SECONDS, SERVICE_STATUS_SECONDS, TimeUnit.SECONDS);
    if (config.backups().enabled() && config.backups().intervalMinutes() > 0) {
      scheduler.scheduleWithFixedDelay(
          () -> {
            try {
              scheduledBackups.execute(
                  () -> {
                    String id = UUID.randomUUID().toString();
                    try {
                      audit.append(
                          Map.of(
                              "timestamp", Instant.now().toString(),
                              "requestId", id,
                              "serverId", config.serverId(),
                              "deviceId", "local-schedule",
                              "role", "Local",
                              "actorLabel", "Host schedule",
                              "actionType", "backup.create",
                              "outcome", "STARTED"));
                      var result = backups.create(null, true, false);
                      audit.append(
                          Map.of(
                              "timestamp", Instant.now().toString(),
                              "requestId", id,
                              "serverId", config.serverId(),
                              "deviceId", "local-schedule",
                              "role", "Local",
                              "actorLabel", "Host schedule",
                              "actionType", "backup.create",
                              "outcome", "SUCCESS",
                              "metadata", Map.of("backupId", result.backupId(), "sha256", result.sha256())));
                    } catch (Exception e) {
                      try {
                        audit.append(
                            Map.of(
                                "timestamp", Instant.now().toString(),
                                "requestId", id,
                                "serverId", config.serverId(),
                                "deviceId", "local-schedule",
                                "actorLabel", "Host schedule",
                                "actionType", "backup.create",
                                "outcome", "FAILED",
                                "code", e instanceof OperationFailure failure ? failure.code() : "BACKUP_FAILED",
                                "metadata",
                                    e instanceof OperationFailure failure
                                        ? failure.safeData()
                                        : Map.of()));
                      } catch (Exception ignored) {
                      }
                      System.err.println("Scheduled live snapshot failed: " + e.getClass().getSimpleName());
                    }
                  });
            } catch (RejectedExecutionException busy) {
              System.err.println("Scheduled live snapshot skipped: previous job still running");
            }
          },
          config.backups().intervalMinutes(),
          config.backups().intervalMinutes(),
          TimeUnit.MINUTES);
    }
    maintenance.start();
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  maintenance.close();
                  maintenanceLink.close();
                  scheduledBackups.shutdownNow();
                  engine.close();
                  leases.close();
                  transfers.close();
                  connection.close();
                  telemetryScheduler.shutdownNow();
                  scheduler.shutdownNow();
                }));
    connection.start();
    new CountDownLatch(1).await();
  }

  private static boolean configChanged(Path path, long loadedMtime) {
    try {
      return Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toMillis() != loadedMtime;
    } catch (Exception ignored) {
      return false;
    }
  }

  private static void requireOwner(DeviceRegistry.Device device) {
    if (device == null || !"Owner".equals(device.role())) throw new SecurityException("OWNER_REQUIRED");
  }
}
