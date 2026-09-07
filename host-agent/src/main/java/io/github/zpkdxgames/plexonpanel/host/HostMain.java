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
    HostConfig config = HostConfig.load(Path.of(args[0]));
    Path data = Path.of(config.dataDirectory());
    Files.createDirectories(data);
    DeviceIdentity stored = new IdentityStore(data).loadOrCreate(),
        identity =
            new DeviceIdentity(
                UUID.fromString(config.serverId()), stored.createdAt(), stored.keyPair());
    if (!Files.isRegularFile(Path.of(config.accessRegistry()), LinkOption.NOFOLLOW_LINKS))
      throw new IllegalStateException("Paper must create its local access registry first");
    DeviceRegistry devices =
        new DeviceRegistry(Path.of(config.accessRegistry()), config.serverId());
    LocalAudit audit = new LocalAudit(data.resolve("audit"), 30);
    audit.clean();
    HostConnection connection = new HostConnection(config, identity);
    SystemdService service = new SystemdService(config.serviceName());
    PaperSaveLease leases = new PaperSaveLease(connection, devices);
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
    BackupTransfers transfers = new BackupTransfers();
    if (args.length == 2) {
      if (!args[1].equals("--recover-restore"))
        throw new IllegalArgumentException("Unknown local operation");
      backups.recover();
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
                List.of(data, Path.of(config.accessRegistry()).getParent())),
            Set.of("server"));
    SystemMetrics metrics = new SystemMetrics(root);
    ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
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
                      config.backups().rcloneRemote() == null
                              || config.backups().rcloneRemote().isBlank()
                          ? "LOCAL"
                          : "RCLONE",
                      "recoveryRequired",
                      backups.recoveryRequired());
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
                case "server.status" -> {
                  var status = new HashMap<>(service.status());
                  status.put("paperConnected", paper.get());
                  status.put("recoveryRequired", backups.recoveryRequired());
                  return status;
                }
                case "server.start", "server.stop", "server.restart" -> {
                  if (!operationLock.tryLock()) throw new SecurityException("BUSY");
                  try {
                    if (backups.recoveryRequired())
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
                            "Service command accepted, but authenticated Paper reconnection was not"
                                + " observed");
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
    AtomicLong sentRevision = new AtomicLong(-1);
    Runnable snapshot =
        () -> {
          if (!connection.authenticated()) return;
          try {
            connection.send("telemetry.system", metrics.collect(), MessagePriority.TELEMETRY);
            var status = new HashMap<>(service.status());
            status.put("paperConnected", paper.get());
            status.put("recoveryRequired", backups.recoveryRequired());
            connection.send("service.status", status, MessagePriority.TELEMETRY);
            var state = devices.snapshot();
            if (sentRevision.getAndSet(state.revision()) != state.revision()) engine.syncAccess();
          } catch (Exception e) {
            System.err.println("Host snapshot unavailable: " + e.getClass().getSimpleName());
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
            case "backup.coordination.result" -> leases.accept(m);
            default -> engine.accept(m);
          }
        },
        () -> {
          sentRevision.set(-1);
          scheduler.execute(snapshot);
        });
    scheduler.scheduleWithFixedDelay(snapshot, 5, 5, TimeUnit.SECONDS);
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
                              "timestamp",
                              Instant.now().toString(),
                              "requestId",
                              id,
                              "serverId",
                              config.serverId(),
                              "deviceId",
                              "local-schedule",
                              "role",
                              "Local",
                              "actorLabel",
                              "Host schedule",
                              "actionType",
                              "backup.create",
                              "outcome",
                              "STARTED"));
                      var result = backups.create(null, true, false);
                      audit.append(
                          Map.of(
                              "timestamp",
                              Instant.now().toString(),
                              "requestId",
                              id,
                              "serverId",
                              config.serverId(),
                              "deviceId",
                              "local-schedule",
                              "role",
                              "Local",
                              "actorLabel",
                              "Host schedule",
                              "actionType",
                              "backup.create",
                              "outcome",
                              "SUCCESS",
                              "metadata",
                              Map.of("backupId", result.backupId(), "sha256", result.sha256())));
                    } catch (Exception e) {
                      try {
                        audit.append(
                            Map.of(
                                "timestamp",
                                Instant.now().toString(),
                                "requestId",
                                id,
                                "serverId",
                                config.serverId(),
                                "deviceId",
                                "local-schedule",
                                "actorLabel",
                                "Host schedule",
                                "actionType",
                                "backup.create",
                                "outcome",
                                "FAILED",
                                "code",
                                "BACKUP_FAILED"));
                      } catch (Exception ignored) {
                      }
                      System.err.println(
                          "Scheduled backup failed: " + e.getClass().getSimpleName());
                    }
                  });
            } catch (RejectedExecutionException busy) {
              System.err.println("Scheduled backup skipped: previous job still running");
            }
          },
          config.backups().intervalMinutes(),
          config.backups().intervalMinutes(),
          TimeUnit.MINUTES);
    }
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  scheduledBackups.shutdownNow();
                  engine.close();
                  leases.close();
                  transfers.close();
                  connection.close();
                  scheduler.shutdownNow();
                }));
    connection.start();
    new CountDownLatch(1).await();
  }
}
