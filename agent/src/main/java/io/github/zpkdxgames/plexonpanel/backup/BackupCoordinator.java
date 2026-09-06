package io.github.zpkdxgames.plexonpanel.backup;

import static io.github.zpkdxgames.plexonpanel.control.JsonFields.*;

import io.github.zpkdxgames.plexonpanel.config.ControlPolicy;
import io.github.zpkdxgames.plexonpanel.protocol.*;
import io.github.zpkdxgames.plexonpanel.security.DeviceRegistry;
import java.util.*;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * A renewable save lease always restores original per-world autosave state, including on disable.
 */
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
      if (!operation.equals("resume")) {
        if (bool(m.body(), "automatic")) {
          if (!plugin.getConfig().getBoolean("backups.enabled", false)
              || !plugin.getConfig().getBoolean("backups.allow-host-schedule", false)
              || policy.hostPublicKey().isBlank()) throw new SecurityException();
        } else
          devices.authorize(
              text(m.body(), "deviceId", 36),
              integer(m.body(), "generation", -1, 1, Long.MAX_VALUE),
              "backup.create",
              policy.capabilities());
      }
      plugin
          .getServer()
          .getScheduler()
          .runTask(
              plugin,
              () -> {
                boolean ok = false;
                try {
                  switch (operation) {
                    case "prepare" -> {
                      if (lease != null) throw new IllegalStateException();
                      lease = requestedLease;
                      expiry = System.currentTimeMillis() + 60000;
                      for (World world : plugin.getServer().getWorlds()) {
                        previous.put(world.getUID(), world.isAutoSave());
                        world.setAutoSave(false);
                      }
                      if (!plugin
                          .getServer()
                          .dispatchCommand(plugin.getServer().getConsoleSender(), "save-all flush"))
                        throw new IllegalStateException();
                      ok = true;
                    }
                    case "renew" -> {
                      if (!requestedLease.equals(lease)) throw new IllegalStateException();
                      expiry = System.currentTimeMillis() + 60000;
                      ok = true;
                    }
                    case "resume" -> {
                      if (requestedLease.equals(lease)) resume();
                      ok = true;
                    }
                    default -> throw new IllegalArgumentException();
                  }
                } catch (Exception e) {
                  if (operation.equals("prepare") && requestedLease.equals(lease)) resume();
                }
                sink.send(
                    "backup.coordination.result",
                    Map.of("requestId", id, "leaseId", requestedLease, "success", ok),
                    MessagePriority.CRITICAL);
              });
    } catch (Exception e) {
      sink.send(
          "backup.coordination.result",
          Map.of("requestId", id, "leaseId", requestedLease, "success", false),
          MessagePriority.CRITICAL);
    }
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
