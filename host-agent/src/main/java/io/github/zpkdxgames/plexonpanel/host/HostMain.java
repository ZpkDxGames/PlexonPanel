package io.github.zpkdxgames.plexonpanel.host;

import static io.github.zpkdxgames.plexonpanel.control.JsonFields.*;

import io.github.zpkdxgames.plexonpanel.audit.LocalAudit;
import io.github.zpkdxgames.plexonpanel.control.*;
import io.github.zpkdxgames.plexonpanel.files.*;
import io.github.zpkdxgames.plexonpanel.identity.*;
import io.github.zpkdxgames.plexonpanel.protocol.*;
import io.github.zpkdxgames.plexonpanel.security.*;
import io.github.zpkdxgames.plexonpanel.telemetry.SystemMetrics;
import io.github.zpkdxgames.plexonpanel.util.ExecutorDrain;
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
    try { run(args); }
    catch (Exception failure) {
      String code = failure.getMessage();
      System.err.println(code != null && code.matches("[A-Z][A-Z0-9_]{2,63}")
          ? code : "HOST_STARTUP_OR_LOCAL_OPERATION_FAILED " + failure.getClass().getSimpleName());
      System.exit(1);
    }
  }

  static void run(String[] args) throws Exception {
    if (args.length == 2 && args[0].equals("--validate-fleet")) {
      var layout = new InstanceLayout(args[1]);
      HostConfig config = HostConfig.load(layout.hostConfig());
      if (HostConfigMigration.schemaVersion(layout.hostConfig()) != 5) throw new IllegalArgumentException("FLEET_SCHEMA_REQUIRED");
      if (!layout.instanceKey().equals(config.fleet().instanceKey())) throw new IllegalArgumentException("INSTANCE_KEY_MISMATCH");
      var entry = NodeInstanceRegistry.readDefault().require(config.fleetIdentity());
      Integer port = config.commandChannel().enabled() ? config.commandChannel().port() : null;
      if (!Objects.equals(port, entry.rconPort())) throw new IllegalArgumentException("REGISTERED_RCON_PORT_MISMATCH");
      System.out.println(new com.google.gson.Gson().toJson(entry));
      return;
    }
    if (args.length == 3 && args[0].equals("--register-fleet")) {
      if (!System.getProperty("os.name").equals("Linux") || !ProcessHandle.current().info().user().orElse("").equals("root"))
        throw new SecurityException("LOCAL_ADMINISTRATOR_REQUIRED");
      var layout = new InstanceLayout(args[1]);
      HostConfig config = HostConfig.load(layout.hostConfig());
      if (HostConfigMigration.schemaVersion(layout.hostConfig()) != 5) throw new IllegalArgumentException("FLEET_SCHEMA_REQUIRED");
      if (!layout.instanceKey().equals(config.fleet().instanceKey())) throw new IllegalArgumentException("INSTANCE_KEY_MISMATCH");
      int minecraftPort;
      try { minecraftPort = Integer.parseInt(args[2]); }
      catch (NumberFormatException invalid) { throw new IllegalArgumentException("REGISTRY_PORT_INVALID"); }
      var entry = NodeInstanceRegistry.registerDefault(config.fleetIdentity(), minecraftPort,
          config.commandChannel().enabled() ? config.commandChannel().port() : null);
      System.out.println(new com.google.gson.Gson().toJson(entry));
      return;
    }
    if (args.length == 1 && args[0].equals("--init-node")) {
      if (!System.getProperty("os.name").equals("Linux")
          || !ProcessHandle.current().info().user().orElse("").equals("root"))
        throw new SecurityException("Node provisioning requires the local Linux administrator");
      UUID nodeId = NodeIdentity.initialize(NodeIdentity.DEFAULT_PATH);
      if (!nodeId.equals(NodeIdentity.readDefault())) throw new SecurityException("NODE_IDENTITY_MISMATCH");
      System.out.println("Initialized public node identity: " + nodeId);
      return;
    }
    if (args.length == 2 && args[0].equals("--init")) {
      DeviceIdentity identity = new IdentityStore(Path.of(args[1])).loadOrCreate();
      System.out.println("Host public key: " + identity.publicKeyBase64());
      System.out.println("Host fingerprint: " + identity.fingerprint());
      return;
    }
    if (args.length == 3 && (args[1].equals("--plan-fleet") || args[1].equals("--migrate-fleet"))) {
      Path configPath = Path.of(args[0]).toAbsolutePath().normalize();
      UUID nodeId = NodeIdentity.readDefault();
      var plan = HostConfigMigration.planFleet(configPath, nodeId, args[2]);
      if (args[1].equals("--migrate-fleet")) {
        if (!System.getProperty("os.name").equals("Linux") || !ProcessHandle.current().info().user().orElse("").equals("root"))
          throw new SecurityException("LOCAL_ADMINISTRATOR_REQUIRED");
        HostConfigMigration.migrateFleet(configPath, nodeId, args[2]);
      }
      System.out.println(new com.google.gson.Gson().toJson(plan));
      return;
    }
    if (args.length < 1 || args.length > 2)
      throw new IllegalArgumentException(
          "Usage: java -jar plexonpanel-host.jar <host-config.json> [--recover-restore|--migrate-config]");

    Path configPath = Path.of(args[0]).toAbsolutePath().normalize();
    if (args.length == 2 && args[1].equals("--migrate-config")) {
      HostConfigMigration.migrate(configPath);
      System.out.println("Host configuration migration completed; existing rollback backup retained.");
      return;
    }
    if (!System.getProperty("os.name").equals("Linux")
        || ProcessHandle.current().info().user().orElse("root").equals("root"))
      throw new SecurityException("Run the host companion as a dedicated non-root Linux user");

    Instant hostStartedAt = Instant.now();
    if (HostConfigMigration.needsMigration(configPath))
      System.err.println(
          "Legacy Host config accepted read-only; run the local --migrate-config operation before release certification.");
    long configLoadedMtime = Files.getLastModifiedTime(configPath, LinkOption.NOFOLLOW_LINKS).toMillis();
    HostConfig config = HostConfig.load(configPath);
    boolean fleetSchema = HostConfigMigration.schemaVersion(configPath) == 5;
    String journalNamespace = fleetSchema ? new InstanceLayout(config.fleet().instanceKey()).journalNamespace() : null;
    if (fleetSchema) {
      var layout = new InstanceLayout(config.fleet().instanceKey());
      InstanceLayout.requirePath(configPath.toString(), layout.hostConfig());
      if (!layout.hostUser().equals(ProcessHandle.current().info().user().orElse("")))
        throw new SecurityException("HOST_INSTANCE_ACCOUNT_MISMATCH");
      for (Path path : List.of(layout.serverRoot(), layout.stateDirectory(), layout.backupsDirectory(), layout.rconSecret(), layout.rcloneConfig()))
        InstanceLayout.rejectSymlinks(path);
      var registered = NodeInstanceRegistry.readDefault().require(config.fleetIdentity());
      if (!Objects.equals(registered.rconPort(), config.commandChannel().enabled() ? config.commandChannel().port() : null))
        throw new SecurityException("REGISTERED_RCON_PORT_MISMATCH");
    }
    if (config.fleetIdentity() != null && !config.fleetIdentity().nodeId().equals(NodeIdentity.readDefault()))
      throw new SecurityException("HOST_NODE_IDENTITY_MISMATCH");
    Path data = Path.of(config.dataDirectory());
    Files.createDirectories(data);
    DeviceIdentity stored = new IdentityStore(data).loadOrCreate(),
        identity =
            new DeviceIdentity(
                UUID.fromString(config.serverId()), stored.createdAt(), stored.keyPair());

    FleetBindingStore.Lease fleetLease = null;
    if (config.fleetIdentity() != null) {
      fleetLease = new FleetBindingStore(data, Path.of(config.serverRoot()))
          .claim(config.fleetIdentity(), identity.fingerprint());
    }
    final FleetBindingStore.Lease activeFleetLease = fleetLease;

    Path legacyAccessRegistry = Path.of(config.accessRegistry()).toAbsolutePath().normalize();
    HostAuthorizationMirror authorization =
        new HostAuthorizationMirror(data.resolve("access"), config.serverId());
    authorization.bootstrapLegacy(legacyAccessRegistry);
    DeviceRegistry devices = authorization.registry();
    LocalAudit audit = new LocalAudit(data.resolve("audit"), 30);
    audit.clean();
    HostConnection connection = new HostConnection(config, identity, journalNamespace);
    HostConsoleHistory consoleHistory = new HostConsoleHistory(config.serviceName(), config.console(), journalNamespace);
    SystemdService service = new SystemdService(config.serviceName());
    MinecraftCommandChannel commands = new RconMinecraftCommandChannel(config.commandChannel());
    AtomicBoolean paper = new AtomicBoolean();
    MinecraftReadinessCache minecraftReadiness = new MinecraftReadinessCache(commands);
    ReentrantLock operationLock = new ReentrantLock();

    FullRestorePointManager fullBackups =
        new FullRestorePointManager(
            config,
            service,
            minecraftReadiness::probeNow,
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
      if (activeFleetLease != null) activeFleetLease.close();
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
                  Map<String, Object> serviceStatus = service.status();
                  result.put(
                      "minecraftReady",
                      minecraftReadiness.refresh(
                          "active".equals(serviceStatus.get("state"))));
                  result.put("hostStartedAt", hostStartedAt.toString());
                  result.put(
                      "hostConfigLoadedAt", Instant.ofEpochMilli(configLoadedMtime).toString());
                  result.put(
                      "hostConfigRestartRequired", configChanged(configPath, configLoadedMtime));
                  return Map.copyOf(result);
                }
                case "backup.create", "maintenance.full-backup.create" -> {
                  int countdownSeconds =
                      p.has("countdownSeconds")
                          ? (int) integer(p, "countdownSeconds", -1, 300, 1800)
                          : MaintenanceCountdown.DEFAULT_FULL_BACKUP_COUNTDOWN_SECONDS;
                  return Map.of(
                      "jobId",
                      maintenance.fullRestorePointNow(device, countdownSeconds),
                      "state",
                      "QUEUED",
                      "countdownSeconds",
                      countdownSeconds);
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
                case "maintenance.recovery.resolve" -> {
                  return maintenance.resolveRecovery(device);
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
                  status.put(
                      "minecraftReady",
                      minecraftReadiness.observeServiceState(
                          "active".equals(status.get("state"))));
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
                      minecraftReadiness.markStopped();
                    }
                    if (!action.equals("server.stop")) {
                      if (action.equals("server.start") && !service.stopped())
                        throw new IllegalStateException("Service is already running");
                      service.action("start");
                      waitStarted(
                          service, commands, config.commandChannel().readinessTimeoutSeconds());
                      minecraftReadiness.markStarted();
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
            if (config.fleetIdentity() != null) {
              sample.put("nodeId", config.fleetIdentity().nodeId().toString());
              sample.put("metricScope", "NODE");
              sample.put("processRole", "HOST");
            }
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
            if (config.fleetIdentity() != null) status.put("nodeId", config.fleetIdentity().nodeId().toString());
            status.put("paperConnected", paper.get());
            status.put("authorizationMirror", authorization.status());
            status.put(
                      "minecraftReady",
                      minecraftReadiness.observeServiceState(
                          "active".equals(status.get("state"))));
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
                  long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
                  telemetryScheduler.shutdownNow();
                  scheduler.shutdownNow();
                  engine.beginClose();
                  maintenance.beginClose();
                  boolean controlDrained = engine.awaitClosed(remaining(deadline));
                  boolean maintenanceDrained = maintenance.awaitClosed(remaining(deadline));
                  boolean telemetryDrained = ExecutorDrain.await(telemetryScheduler, remaining(deadline));
                  boolean schedulerDrained = ExecutorDrain.await(scheduler, remaining(deadline));
                  transfers.close();
                  connection.close();
                  boolean connectionDrained = connection.awaitClosed(remaining(deadline));
                  boolean drained = controlDrained && maintenanceDrained && telemetryDrained && schedulerDrained && connectionDrained;
                  if (!drained) System.err.println("HOST_DRAIN_TIMEOUT_IDENTITY_LEASE_RETAINED");
                  if (drained && activeFleetLease != null) {
                    try { activeFleetLease.close(); }
                    catch (java.io.IOException failure) { System.err.println("FLEET_IDENTITY_LEASE_RELEASE_FAILED"); }
                  }
                }));
    connection.start();
    new CountDownLatch(1).await();
  }

  private static Duration remaining(long deadline) {
    return Duration.ofNanos(Math.max(0, deadline - System.nanoTime()));
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
