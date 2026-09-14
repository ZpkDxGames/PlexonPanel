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
import java.util.concurrent.atomic.AtomicBoolean;
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
    HostConsoleHistory consoleHistory = new HostConsoleHistory(config.serviceName(), config.console());
    SystemdService service = new SystemdService(config.serviceName());
    MinecraftCommandChannel commands = new RconMinecraftCommandChannel(config.commandChannel());
    AtomicBoolean paper = new AtomicBoolean();
    ReentrantLock operationLock = new ReentrantLock();

    FullRestorePointManager fullBackups =
        new FullRestorePointManager(
            config,
            service,
            () -> commands.readinessProbe().success(),
            operationLock,
            progress -> connection.send("backup.progress", progress, MessagePriority.EVENT));
    FullBackupPreflight fullPreflight = new FullBackupPreflight(config, fullBackups);
    MaintenanceManager maintenance =
        new MaintenanceManager(
            config, service, commands, operationLock, audit, connection, fullBackups);
    BackupTransfers transfers = new BackupTransfers();

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
                List.of(data, legacyAccessRegistry.getParent())),
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
                case "backup.list", "backup.full.list" -> {
                  var all = fullBackups.list();
                  int page = (int) integer(p, "page", 0, 0, 100);
                  int first = Math.min(all.size(), page * 50), last = Math.min(all.size(), first + 50);
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
                      fullBackups.recoveryRequired());
                }
                case "backup.preflight" -> {
                  Map<String, Object> result =
                      new LinkedHashMap<>(
                          fullPreflight.check(maintenance.settings().fullRestorePoint()));
                  Path directory = Path.of(config.backups().directory()).toAbsolutePath().normalize();
                  result.put("backupMode", "MANUAL_FULL_ONLY");
                  result.put("hostAuthenticated", connection.authenticated());
                  result.put("operationBusy", operationLock.isLocked());
                  result.put("recoveryRequired", fullBackups.recoveryRequired());
                  result.put(
                      "backupRootWritable", Files.isDirectory(directory) && Files.isWritable(directory));
                  result.put("commandChannelConfigured", commands.enabled());
                  result.put("hostStartedAt", hostStartedAt.toString());
                  result.put(
                      "hostConfigLoadedAt", Instant.ofEpochMilli(configLoadedMtime).toString());
                  result.put(
                      "hostConfigRestartRequired", configChanged(configPath, configLoadedMtime));
                  return Map.copyOf(result);
                }
                case "backup.create", "maintenance.full-backup.create" -> {
                  return Map.of(
                      "jobId", maintenance.fullRestorePointNow(device, false), "state", "QUEUED");
                }
                case "backup.delete", "backup.full.delete" -> {
                  fullBackups.delete(text(p, "backupId", 36));
                  return Map.of();
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
                case "backup.restore.prepare", "backup.full.restore.prepare" -> {
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
                case "backup.restore", "backup.full.restore" -> {
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
                case "backup.download" -> {
                  String id = text(p, "backupId", 36);
                  var metadata = fullBackups.metadata(id);
                  return transfers.start(
                      fullBackups.archive(id),
                      metadata.archiveBytes(),
                      metadata.sha256(),
                      device);
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
                  status.put("authorizationMirror", authorization.status());
                  status.put("commandChannelConfigured", commands.enabled());
                  status.put("minecraftReady", commands.readinessProbe().success());
                  status.put("recoveryRequired", fullBackups.recoveryRequired());
                  return status;
                }
                case "server.start", "server.stop", "server.restart" -> {
                  if (!operationLock.tryLock()) throw new SecurityException("BUSY");
                  try {
                    if (fullBackups.recoveryRequired())
                      throw new SecurityException("RESTORE_RECOVERY_REQUIRED");
                    if (action.equals("server.restart") || action.equals("server.stop")) {
                      service.action("stop");
                      waitStopped(service, commands, 180);
                    }
                    if (!action.equals("server.stop")) {
                      if (action.equals("server.start") && !service.stopped())
                        throw new IllegalStateException("Service is already running");
                      service.action("start");
                      waitStarted(
                          service, commands, config.commandChannel().readinessTimeoutSeconds());
                    }
                    return Map.of(
                        "state", action.equals("server.stop") ? "stopped" : "running",
                        "minecraftReadinessVerified", !action.equals("server.stop"));
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
            status.put("minecraftReady", commands.readinessProbe().success());
            status.put("recoveryRequired", fullBackups.recoveryRequired());
            connection.send("service.status", status, MessagePriority.TELEMETRY);
          } catch (Exception e) {
            System.err.println("Host service snapshot unavailable: " + e.getClass().getSimpleName());
          }
        };
    connection.handlers(
        m -> {
          switch (m.envelope().type()) {
            case "paper.connection" -> paper.set(bool(m.body(), "connected"));
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
            default -> engine.accept(m);
          }
        },
        () -> {
          connection.send("access.authority.request", Map.of(), MessagePriority.CRITICAL);
          telemetryScheduler.execute(fastSnapshot);
          scheduler.execute(serviceSnapshot);
        });
    telemetryScheduler.scheduleWithFixedDelay(
        fastSnapshot,
        FAST_TELEMETRY_MILLIS,
        FAST_TELEMETRY_MILLIS,
        TimeUnit.MILLISECONDS);
    scheduler.scheduleWithFixedDelay(
        serviceSnapshot, SERVICE_STATUS_SECONDS, SERVICE_STATUS_SECONDS, TimeUnit.SECONDS);
    maintenance.start();

    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  maintenance.close();
                  engine.close();
                  transfers.close();
                  connection.close();
                  telemetryScheduler.shutdownNow();
                  scheduler.shutdownNow();
                }));
    connection.start();
    new CountDownLatch(1).await();
  }

  private static void waitStopped(
      SystemdService service, MinecraftCommandChannel commands, int seconds) throws Exception {
    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
    while (System.nanoTime() < until) {
      Map<String, Object> status = service.status();
      long pid = status.get("pid") instanceof Number n ? n.longValue() : -1L;
      if (service.stopped() && pid == 0L && !commands.readinessProbe().success()) return;
      Thread.sleep(250);
    }
    throw new IllegalStateException("SERVER_STOP_TIMEOUT");
  }

  private static void waitStarted(
      SystemdService service, MinecraftCommandChannel commands, int seconds) throws Exception {
    if (!commands.enabled()) throw new IllegalStateException("COMMAND_CHANNEL_DISABLED");
    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
    while (System.nanoTime() < until) {
      Map<String, Object> status = service.status();
      long pid = status.get("pid") instanceof Number n ? n.longValue() : 0L;
      if ("active".equals(status.get("state"))
          && pid > 0L
          && commands.readinessProbe().success()) return;
      Thread.sleep(250);
    }
    throw new IllegalStateException("MINECRAFT_READINESS_TIMEOUT");
  }

  private static boolean configChanged(Path path, long loadedMtime) {
    try {
      return Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toMillis() != loadedMtime;
    } catch (Exception ignored) {
      return false;
    }
  }

  private static void requireOwner(DeviceRegistry.Device device) {
    if (device == null || !"Owner".equals(device.role()))
      throw new SecurityException("OWNER_REQUIRED");
  }
}
