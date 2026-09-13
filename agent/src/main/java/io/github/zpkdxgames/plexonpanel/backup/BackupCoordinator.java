package io.github.zpkdxgames.plexonpanel.backup;

import static io.github.zpkdxgames.plexonpanel.control.JsonFields.*;

import io.github.zpkdxgames.plexonpanel.config.ControlPolicy;
import io.github.zpkdxgames.plexonpanel.protocol.*;
import io.github.zpkdxgames.plexonpanel.security.DeviceRegistry;
import java.util.*;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;

/** A renewable save lease always restores original per-world autosave state, including on disable. */
public final class BackupCoordinator implements AutoCloseable {
  private final JavaPlugin plugin;
  private final DeviceRegistry devices;
  private final ControlPolicy policy;
  private final MessageSink sink;
  private final Map<UUID, Boolean> previous = new HashMap<>();
  private String lease;
  private long expiry;
  private final org.bukkit.scheduler.BukkitTask watchdog;

  public BackupCoordinator(
      JavaPlugin plugin, DeviceRegistry devices, ControlPolicy policy, MessageSink sink) {
    this.plugin = plugin;
    this.devices = devices;
    this.policy = policy;
    this.sink = sink;
    watchdog =
        plugin
            .getServer()
            .getScheduler()
            .runTaskTimer(
                plugin,
                () -> {
                  if (lease != null && System.currentTimeMillis() > expiry) resume();
                },
                20,
                20);
  }

  public void accept(DecodedMessage m) {
    String id = text(m.body(), "requestId", 36),
        requestedLease = text(m.body(), "leaseId", 36),
        operation = text(m.body(), "operation", 16);
    UUID.fromString(id);
    UUID.fromString(requestedLease);
    try {
      authorize(m, operation);
      plugin
          .getServer()
          .getScheduler()
          .runTask(plugin, () -> execute(id, requestedLease, operation));
    } catch (SecurityException e) {
      result(
          id,
          requestedLease,
          false,
          "PAPER_COORDINATION_DENIED",
          "AUTHORIZE",
          "Paper policy denied the save coordination request.");
    } catch (Exception e) {
      result(
          id,
          requestedLease,
          false,
          "PAPER_COORDINATION_UNAVAILABLE",
          "COORDINATING_PAPER",
          "Paper could not schedule the save coordination request.");
    }
  }

  private void authorize(DecodedMessage m, String operation) throws Exception {
    if (operation.equals("resume")) return;
    if (bool(m.body(), "automatic")) {
      if (!plugin.getConfig().getBoolean("backups.enabled", false)
          || !plugin.getConfig().getBoolean("backups.allow-host-schedule", false)
          || policy.hostPublicKey().isBlank()) throw new SecurityException("Host schedule denied");
      return;
    }
    devices.authorize(
        text(m.body(), "deviceId", 36),
        integer(m.body(), "generation", -1, 1, Long.MAX_VALUE),
        "backup.create",
        policy.capabilities());
  }

  private void execute(String id, String requestedLease, String operation) {
    String code = "OK", phase = operation.equals("prepare") ? "PREPARE" : operation.toUpperCase(Locale.ROOT);
    String message = "Paper save coordination completed.";
    boolean ok = false;
    try {
      switch (operation) {
        case "prepare" -> {
          if (lease != null) {
            code = "SAVE_LEASE_BUSY";
            message = "Another Paper save lease is already active.";
            break;
          }
          lease = requestedLease;
          expiry = System.currentTimeMillis() + 60000;
          for (World world : plugin.getServer().getWorlds()) {
            previous.put(world.getUID(), world.isAutoSave());
            world.setAutoSave(false);
          }
          if (!plugin
              .getServer()
              .dispatchCommand(plugin.getServer().getConsoleSender(), "save-all flush")) {
            code = "SAVE_FLUSH_FAILED";
            message = "Paper could not complete the requested save flush.";
            break;
          }
          ok = true;
        }
        case "renew" -> {
          if (!requestedLease.equals(lease)) {
            code = "SAVE_LEASE_LOST";
            message = "The requested Paper save lease is no longer active.";
            break;
          }
          expiry = System.currentTimeMillis() + 60000;
          ok = true;
        }
        case "resume" -> {
          if (requestedLease.equals(lease)) resume();
          ok = true;
        }
        default -> {
          code = "PAPER_COORDINATION_DENIED";
          message = "Paper rejected an unknown save coordination operation.";
        }
      }
    } catch (Exception e) {
      code = operation.equals("prepare") ? "SAVE_FLUSH_FAILED" : "PAPER_COORDINATION_UNAVAILABLE";
      message =
          operation.equals("prepare")
              ? "Paper could not complete save preparation."
              : "Paper could not complete save coordination.";
    } finally {
      if (!ok && operation.equals("prepare") && requestedLease.equals(lease)) resume();
    }
    result(id, requestedLease, ok, code, phase, message);
  }

  private void result(
      String requestId,
      String requestedLease,
      boolean success,
      String code,
      String phase,
      String message) {
    sink.send(
        "backup.coordination.result",
        Map.of(
            "requestId", requestId,
            "leaseId", requestedLease,
            "success", success,
            "code", code,
            "phase", phase,
            "message", message),
        MessagePriority.CRITICAL);
  }

  private void resume() {
    for (var entry : previous.entrySet()) {
      World world = plugin.getServer().getWorld(entry.getKey());
      if (world != null) world.setAutoSave(entry.getValue());
    }
    previous.clear();
    lease = null;
    expiry = 0;
  }

  public void close() {
    watchdog.cancel();
    resume();
  }
}
