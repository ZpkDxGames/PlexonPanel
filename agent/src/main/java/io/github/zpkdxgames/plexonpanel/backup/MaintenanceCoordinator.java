package io.github.zpkdxgames.plexonpanel.backup;

import static io.github.zpkdxgames.plexonpanel.control.JsonFields.*;

import io.github.zpkdxgames.plexonpanel.config.ControlPolicy;
import io.github.zpkdxgames.plexonpanel.protocol.*;
import io.github.zpkdxgames.plexonpanel.security.DeviceRegistry;
import java.util.*;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.plugin.java.JavaPlugin;

/** Paper-side maintenance UX and final save flush; no host filesystem authority lives here. */
public final class MaintenanceCoordinator {
  private final JavaPlugin plugin;
  private final DeviceRegistry devices;
  private final ControlPolicy policy;
  private final MessageSink sink;
  private final MiniMessage miniMessage = MiniMessage.miniMessage();

  public MaintenanceCoordinator(
      JavaPlugin plugin, DeviceRegistry devices, ControlPolicy policy, MessageSink sink) {
    this.plugin = plugin;
    this.devices = devices;
    this.policy = policy;
    this.sink = sink;
  }

  public void accept(DecodedMessage message) {
    String requestId = text(message.body(), "requestId", 36),
        operation = text(message.body(), "operation", 24);
    UUID.fromString(requestId);
    try {
      boolean automatic = bool(message.body(), "automatic");
      if (automatic) {
        if (!plugin.getConfig().getBoolean("backups.enabled", false)
            || !plugin.getConfig().getBoolean("backups.allow-host-schedule", false)
            || policy.hostPublicKey().isBlank()) throw new SecurityException("Host scheduling disabled");
      } else {
        // maintenance.run is deliberately Host-only. Paper reuses its existing backup.create
        // coordination capability solely for warning/save-flush authorization.
        devices.authorize(
            text(message.body(), "deviceId", 36),
            integer(message.body(), "generation", -1, 1, Long.MAX_VALUE),
            "backup.create",
            policy.capabilities());
      }
      plugin
          .getServer()
          .getScheduler()
          .runTask(
              plugin,
              () -> {
                boolean success = false;
                try {
                  switch (operation) {
                    case "notice" -> {
                      String text = text(message.body(), "message", 512);
                      String title = optionalText(message.body(), "title", 160);
                      var component = miniMessage.deserialize(text);
                      plugin.getServer().broadcast(component);
                      if (!title.isBlank()) {
                        var rendered = miniMessage.deserialize(title);
                        for (var player : plugin.getServer().getOnlinePlayers())
                          player.showTitle(
                              net.kyori.adventure.title.Title.title(
                                  rendered,
                                  component,
                                  net.kyori.adventure.title.Title.Times.times(
                                      java.time.Duration.ofMillis(250),
                                      java.time.Duration.ofSeconds(2),
                                      java.time.Duration.ofMillis(500))));
                      }
                      success = true;
                    }
                    case "flush" ->
                        success =
                            plugin
                                .getServer()
                                .dispatchCommand(plugin.getServer().getConsoleSender(), "save-all flush");
                    default -> throw new IllegalArgumentException("Unknown maintenance operation");
                  }
                } catch (Exception ignored) {
                  success = false;
                }
                sink.send(
                    "maintenance.coordination.result",
                    Map.of("requestId", requestId, "success", success, "operation", operation),
                    MessagePriority.CRITICAL);
              });
    } catch (Exception error) {
      sink.send(
          "maintenance.coordination.result",
          Map.of("requestId", requestId, "success", false, "operation", operation),
          MessagePriority.CRITICAL);
    }
  }

  private static String optionalText(com.google.gson.JsonObject body, String key, int max) {
    if (!body.has(key) || body.get(key).isJsonNull()) return "";
    String value = body.get(key).getAsString();
    if (value.length() > max) throw new IllegalArgumentException("Text too long");
    return value;
  }
}
