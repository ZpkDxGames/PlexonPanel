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
    if (!Files.isRegularFile(Path.of(config.accessRegistry()), LinkOption.NOFOLLOW_LINKS))
      throw new IllegalStateException("Paper must create its local access registry first");
    DeviceRegistry devices = new DeviceRegistry(Path.of(config.accessRegistry()), config.serverId());
    LocalAudit audit = new LocalAudit(data.resolve("audit"), 30);
    audit.clean();

    HostConnection connection = new HostConnection(config, identity);
    HostConsoleHistory consoleHistory = new HostConsoleHistory(config.serviceName(), config.console());
    SystemdService service = new SystemdService(config.serviceName());
    AtomicBoolean paper = new AtomicBoolean();
    AtomicLong paperRevision = new AtomicLong();
    ReentrantLock operationLock = new ReentrantLock();

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
            new RconMinecraftCommandChannel(config.commandChannel()),
            operationLock,
            audit,
            connection,
            fullBackups);

    if (args.length == 2) {
      if (!args[1].equals("--recover-restore"))
        throw new IllegalArgumentException("Unknown local operation");
      fullBackups.recoverRestore();
      maintenance.close();
      connection.close();
      return;
    }

    var caps = config.effectiveCapabilities();
    Path root = Path.of(config.serverRoot());
    SafeFiles files =
        new SafeFiles(
            new PathPolicy(
                Map.of("server", root),
                List.of(data, Path.of(config.accessRegistry()).getParent())),
            Set.of("server"));
    SystemMetrics metrics = new SystemMetrics(root);
    ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    ScheduledExecutorService telemetryScheduler = Executors.newSingleThreadScheduledExecutor();

    ControlEngine engine =
        new ControlEngine(
            devices,
            caps,
            audit,
            files,
            (action, p, device) -> {
              switch (action) {
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
                  return Map.of(
                      "backupId", result.backupId(),
                      "sha256", result.sha256(),
                      "verification", "VERIFIED");
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
                      "message",
                      "A verified emergency full restore point will be created before replacement.");
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
                  // Manual full backups always use the Host-owned mandatory warning schedule.
                  return Map.of(
                      "jobId", maintenance.fullRestorePointNow(device, false), "state", "QUEUED");
                }
                case "provider.status" -> {
                  var result = new LinkedHashMap<>(fullBackups.providerStatus());
                  result.put("hostStartedAt", hostStartedAt.toString());
                  result.put(
                      "hostConfigLoadedAt", Instant.ofEpochMilli(configLoadedMtime).toString());
                  result.put(
                      "hostConfigRestartRequired", configChanged(configPath, configLoadedMtime));
                  return Map.copyOf(result);
                }
                case "provider.test" -> {
                  return fullBackups.testProvider(30);
                }
                case "console.history" -> {
                  return consoleHistory.query(p, false);
                }
                case "console.history.errors" -> {
                  return consoleHistory.query(p, true);
                }
                case "server.status" -> {
                  var status = new HashMap<>(service.status());
                  status.put("paperConnected", paper.get());
                  status.put("recoveryRequired", fullBackups.recoveryRequired());
                  return status;
                }
                case "server.start", "server.stop", "server.restart" -> {
                  if (!operationLock.tryLock()) throw new SecurityException("BUSY");
                  try {
                    if (fullBackups.recoveryRequired())
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
            status.put("recoveryRequired", fullBackups.recoveryRequired());
            connection.send("service.status", status, MessagePriority.TELEMETRY);
          } catch (Exception e) {
            System.err.println("Host service snapshot unavailable: " + e.getClass().getSimpleName());
          }
        };

    connection.handlers(
        message -> {
          if (message.envelope().type().equals("paper.connection")) {
            boolean online = bool(message.body(), "connected");
            paper.set(online);
            if (online) paperRevision.incrementAndGet();
            return;
          }
          engine.accept(message);
        },
        () -> {
          telemetryScheduler.execute(fastSnapshot);
          scheduler.execute(serviceSnapshot);
        });
    telemetryScheduler.scheduleWithFixedDelay(
        fastSnapshot, FAST_TELEMETRY_MILLIS, FAST_TELEMETRY_MILLIS, TimeUnit.MILLISECONDS);
    scheduler.scheduleWithFixedDelay(
        serviceSnapshot, SERVICE_STATUS_SECONDS, SERVICE_STATUS_SECONDS, TimeUnit.SECONDS);

    // Step 5 intentionally has no backup scheduler. Only MaintenanceManager may schedule restart-only
    // maintenance; every full restore point starts from an explicit authorized manual action.
    maintenance.start();
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  maintenance.close();
                  engine.close();
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
