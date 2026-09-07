package io.github.zpkdxgames.plexonpanel.action;

import static io.github.zpkdxgames.plexonpanel.control.JsonFields.*;

import com.google.gson.JsonObject;
import io.github.zpkdxgames.plexonpanel.chat.ChatStreamService;
import io.github.zpkdxgames.plexonpanel.config.*;
import io.github.zpkdxgames.plexonpanel.console.LogRedactor;
import io.github.zpkdxgames.plexonpanel.presence.PlayerPresenceService;
import io.github.zpkdxgames.plexonpanel.security.DeviceRegistry;
import io.github.zpkdxgames.plexonpanel.telemetry.TelemetryService;
import io.papermc.paper.ban.BanListType;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class PaperActions {
  private final JavaPlugin plugin;
  private final ControlPolicy policy;
  private final ChatStreamService chat;
  private final PlayerPresenceService presence;
  private final TelemetryService telemetry;
  private final CommandPolicy commands;
  private final LogRedactor redactor;
  private final Map<String, Long> snapshotRequests = new HashMap<>();

  public PaperActions(
      JavaPlugin plugin,
      ControlPolicy policy,
      PanelSettings settings,
      ChatStreamService chat,
      PlayerPresenceService presence,
      TelemetryService telemetry) {
    this.plugin = plugin;
    this.policy = policy;
    this.chat = chat;
    this.presence = presence;
    this.telemetry = telemetry;
    commands =
        new CommandPolicy(
            settings.remoteActions().console().allowPatterns(),
            settings.remoteActions().console().denyPatterns());
    redactor = new LogRedactor(settings.console().redactPatterns());
  }

  public Map<String, Object> execute(String action, JsonObject p, DeviceRegistry.Device device)
      throws Exception {
    if (action.equals("players.history.list")) return presence.query(p);
    if (action.equals("players.snapshot.request")) return requestPlayerSnapshot(p, device);
    var future = new CompletableFuture<Map<String, Object>>();
    plugin
        .getServer()
        .getScheduler()
        .runTask(
            plugin,
            () -> {
              if (future.isCancelled()) return;
              try {
                if (action.equals("player.teleport")) {
                  Player player = online(p);
                  World world = plugin.getServer().getWorld(text(p, "world", 128));
                  if (world == null) throw new IllegalArgumentException("Unknown world");
                  double x = coordinate(p, "x", 30000000),
                      y = coordinate(p, "y", world.getMaxHeight()),
                      z = coordinate(p, "z", 30000000);
                  if (y < world.getMinHeight()
                      || y >= world.getMaxHeight()
                      || !world.getWorldBorder().isInside(new Location(world, x, y, z)))
                    throw new IllegalArgumentException("Destination outside world bounds");
                  player
                      .teleportAsync(new Location(world, x, y, z))
                      .whenComplete(
                          (ok, e) -> {
                            if (e != null) future.completeExceptionally(e);
                            else if (Boolean.TRUE.equals(ok))
                              future.complete(Map.of("message", "Player teleported"));
                            else
                              future.completeExceptionally(
                                  new IllegalStateException("Teleport cancelled"));
                          });
                } else future.complete(onMain(action, p, device));
              } catch (Exception e) {
                future.completeExceptionally(e);
              }
            });
    try {
      return future.get(30, TimeUnit.SECONDS);
    } catch (ExecutionException e) {
      if (e.getCause() instanceof Exception cause) throw cause;
      throw e;
    } catch (TimeoutException e) {
      future.cancel(false);
      throw e;
    }
  }

  private synchronized Map<String, Object> requestPlayerSnapshot(
      JsonObject parameters, DeviceRegistry.Device device) {
    if (parameters.size() != 0)
      throw new IllegalArgumentException("players.snapshot.request takes no parameters");
    long now = System.currentTimeMillis();
    snapshotRequests.entrySet().removeIf(entry -> entry.getValue() <= now - 5000L);
    Long previous = snapshotRequests.get(device.deviceId());
    if (previous != null && previous > now - 5000L) throw new SecurityException("RATE_LIMITED");
    snapshotRequests.put(device.deviceId(), now);
    TelemetryService.SnapshotRequest request = telemetry.requestPlayerSnapshot();
    if (!request.queued()) throw new SecurityException("BUSY");
    return Map.of("queued", true, "coalesced", request.coalesced());
  }

  private Map<String, Object> onMain(String action, JsonObject p, DeviceRegistry.Device device)
      throws Exception {
    Server server = plugin.getServer();
    switch (action) {
      case "console.execute" -> {
        return console(text(p, "command", 512), p);
      }
      case "plugin.command.reload" -> {
        String command = policy.pluginReloads().get(text(p, "plugin", 128));
        if (command == null) throw new SecurityException("PLUGIN_RELOAD_DISABLED");
        return console(command, p);
      }
      case "plugin.open-data-folder", "plugin.view-configs" -> {
        var target = server.getPluginManager().getPlugin(text(p, "plugin", 128));
        if (target == null) throw new IllegalArgumentException("Unknown plugin");
        return Map.of("root", "server", "path", "plugins/" + target.getDataFolder().getName());
      }
      case "server.status" -> {
        return Map.of(
            "state",
            "running",
            "paperConnected",
            true,
            "hostRequiredForLifecycle",
            true,
            "pid",
            ProcessHandle.current().pid(),
            "javaVersion",
            System.getProperty("java.version"),
            "os",
            System.getProperty("os.name"),
            "architecture",
            System.getProperty("os.arch"));
      }
      case "player.message" -> online(p).sendMessage(Component.text(text(p, "message", 2000)));
      case "player.kick" ->
          online(p).kick(Component.text(optional(p, "reason", "Removed by a moderator", 2000)));
      case "player.ban" -> {
        UUID id = uuid(p);
        var player = known(id);
        String reason = optional(p, "reason", "Banned by a moderator", 2000);
        player.ban(reason, (Instant) null, "PlexonPanel: " + device.name());
        if (player.getPlayer() != null) player.getPlayer().kick(Component.text(reason));
      }
      case "player.unban" ->
          server.getBanList(BanListType.PROFILE).pardon(server.createProfile(uuid(p)));
      case "player.whitelist", "player.whitelist.add", "player.whitelist.remove" ->
          known(uuid(p))
              .setWhitelisted(
                  action.endsWith(".add")
                      || action.equals("player.whitelist") && bool(p, "whitelisted"));
      case "player.gamemode" -> online(p).setGameMode(GameMode.valueOf(text(p, "gameMode", 16)));
      case "player.heal" -> {
        Player player = online(p);
        var max = player.getAttribute(Attribute.MAX_HEALTH);
        if (max != null) player.setHealth(max.getValue());
      }
      case "player.feed" -> {
        Player player = online(p);
        player.setFoodLevel(20);
        player.setSaturation(20);
      }
      case "player.kill" -> online(p).setHealth(0);
      case "player.op", "player.deop" -> known(uuid(p)).setOp(action.equals("player.op"));
      case "chat.global.send" -> {
        boolean mini = bool(p, "miniMessage");
        if (mini
            && (!device.scopes().contains("chat.send.minimessage")
                || !Boolean.TRUE.equals(policy.capabilities().get("chat.send.minimessage"))))
          throw new SecurityException("SCOPE_DENIED");
        var result =
            chat.publishFromDashboard(
                device.deviceId(), device.name(), text(p, "message", 2000), mini);
        if (!result.success()) throw new SecurityException("CHAT_DISABLED");
      }
      default -> throw new SecurityException("UNKNOWN_ACTION");
    }
    return Map.of();
  }

  private Map<String, Object> console(String command, JsonObject p) {
    if (policy.consoleMode().equals("DISABLED")) throw new SecurityException("CAPABILITY_DISABLED");
    if (policy.consoleMode().equals("ALLOWLIST_WITH_CONFIRMATION") && !bool(p, "confirmed"))
      throw new SecurityException("CONFIRMATION_REQUIRED");
    var decision = commands.evaluate(command);
    if (!decision.allowed()) throw new SecurityException(decision.code());
    List<String> output = Collections.synchronizedList(new ArrayList<>());
    var sender =
        plugin
            .getServer()
            .createCommandSender(
                component -> {
                  synchronized (output) {
                    if (output.size() < 50) {
                      String line =
                          redactor.redact(
                              PlainTextComponentSerializer.plainText().serialize(component));
                      output.add(line.substring(0, Math.min(512, line.length())));
                    }
                  }
                });
    if (!plugin.getServer().dispatchCommand(sender, decision.command()))
      throw new IllegalArgumentException("Unknown command");
    synchronized (output) {
      return Map.of("output", List.copyOf(output));
    }
  }

  private UUID uuid(JsonObject p) {
    return UUID.fromString(text(p, "playerId", 36));
  }

  private OfflinePlayer known(UUID id) {
    OfflinePlayer player = plugin.getServer().getOfflinePlayer(id);
    if (!player.isOnline() && !player.hasPlayedBefore())
      throw new IllegalArgumentException("Player has not joined this server");
    return player;
  }

  private Player online(JsonObject p) {
    Player player = plugin.getServer().getPlayer(uuid(p));
    if (player == null || !player.isOnline())
      throw new IllegalArgumentException("Player is offline");
    return player;
  }

  private static double coordinate(JsonObject p, String key, double limit) {
    var value = p.get(key);
    if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
      throw new IllegalArgumentException("Invalid coordinate");
    double n = value.getAsDouble();
    if (!Double.isFinite(n) || Math.abs(n) > limit)
      throw new IllegalArgumentException("Invalid coordinate");
    return n;
  }
}
