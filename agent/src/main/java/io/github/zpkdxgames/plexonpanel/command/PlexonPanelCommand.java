package io.github.zpkdxgames.plexonpanel.command;

import io.github.zpkdxgames.plexonpanel.AgentRuntime;
import io.github.zpkdxgames.plexonpanel.PlexonPanelPlugin;
import io.github.zpkdxgames.plexonpanel.identity.PairingState;
import io.github.zpkdxgames.plexonpanel.integration.core.CoreBridge;
import io.github.zpkdxgames.plexonpanel.protocol.ProtocolCodec;
import io.github.zpkdxgames.plexonpanel.transport.GatewayClient;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.jetbrains.annotations.NotNull;

public final class PlexonPanelCommand implements CommandExecutor, TabCompleter {
  private static final List<String> SUBCOMMANDS =
      List.of(
          "status",
          "gui",
          "pair",
          "devices",
          "revoke",
          "revoke-all",
          "capabilities",
          "audit",
          "unpair",
          "rotate",
          "reload",
          "diagnostics");

  private final PlexonPanelPlugin plugin;

  public PlexonPanelCommand(PlexonPanelPlugin plugin) {
    this.plugin = plugin;
  }

  @Override
  public boolean onCommand(
      @NotNull CommandSender sender,
      @NotNull Command command,
      @NotNull String label,
      @NotNull String[] arguments) {
    String subcommand =
        arguments.length == 0
            ? (sender instanceof Player ? "gui" : "status")
            : arguments[0].toLowerCase(Locale.ROOT);
    if (!SUBCOMMANDS.contains(subcommand)) {
      plugin.messages().send(sender, "help");
      return true;
    }
    if (!sender.hasPermission(
        "plexonpanel." + (subcommand.equals("revoke-all") ? "revoke" : subcommand))) {
      plugin.messages().send(sender, "no-permission");
      return true;
    }

    switch (subcommand) {
      case "status" -> showStatus(sender);
      case "gui" -> {
        if (sender instanceof Player player) plugin.gui().open(player);
        else showStatus(sender);
      }
      case "pair" ->
          requestPairing(
              sender,
              arguments.length > 1 ? arguments[1] : plugin.runtime().policy().defaultRole());
      case "devices" -> listDevices(sender);
      case "revoke" -> {
        if (arguments.length != 2)
          sender.sendMessage(Component.text("Usage: /plexonpanel revoke <device-id>"));
        else revoke(sender, arguments[1]);
      }
      case "revoke-all" -> unpair(sender);
      case "capabilities" ->
          plugin
              .runtime()
              .policy()
              .capabilities()
              .forEach(
                  (scope, enabled) -> sendRow(sender, scope, enabled ? "enabled" : "disabled"));
      case "audit" ->
          sender.sendMessage(
              Component.text(
                  "Authoritative audit: plugins/PlexonPanel/audit. Scoped browsing is available on"
                      + " the dashboard.",
                  NamedTextColor.AQUA));
      case "unpair" -> unpair(sender);
      case "rotate" -> rotate(sender, arguments);
      case "reload" -> reload(sender);
      case "diagnostics" -> showDiagnostics(sender);
      default -> throw new IllegalStateException("Unreachable subcommand: " + subcommand);
    }
    return true;
  }

  private void showStatus(CommandSender sender) {
    AgentRuntime runtime = plugin.runtime();
    CoreBridge core = plugin.coreBridge();
    sender.sendMessage(
        Component.text("PlexonPanel " + plugin.getPluginMeta().getVersion(), NamedTextColor.AQUA));
    sendRow(sender, "Mode", core == null ? "STANDALONE" : core.mode());
    sendRow(sender, "Connection", runtime == null ? "STOPPED" : runtime.gateway().state().name());
    sendRow(sender, "Paired", Boolean.toString(plugin.pairingState().isPaired()));
    sendRow(sender, "Server ID", plugin.identity().serverId().toString());
    sendRow(sender, "Fingerprint", plugin.identity().fingerprint());
    if (runtime != null) {
      sendRow(sender, "Telemetry", enabled(runtime.settings().telemetry().enabled()));
      sendRow(sender, "Console stream", enabled(runtime.settings().console().streamEnabled()));
      sendRow(sender, "Chat stream", enabled(runtime.settings().chat().streamEnabled()));
      sendRow(sender, "Remote actions", enabled(runtime.settings().remoteActions().enabled()));
    }
  }

  public void requestPairing(CommandSender sender, String requestedRole) {
    AgentRuntime runtime = plugin.runtime();
    if (runtime == null || !runtime.settings().gateway().enabled()) {
      plugin.messages().send(sender, "gateway-disabled");
      return;
    }
    String role;
    try {
      role = runtime.policy().roleName(requestedRole);
    } catch (IllegalArgumentException error) {
      sender.sendMessage(
          Component.text(
              "Unknown role. Available: " + runtime.policy().roles().keySet(), NamedTextColor.RED));
      return;
    }
    sendRow(sender, "Pairing role", role);
    sendRow(
        sender,
        "Granted scopes",
        String.join(", ", new java.util.TreeSet<>(runtime.policy().roles().get(role))));
    if (!role.equals("Observer"))
      sender.sendMessage(
          Component.text(
              "Privileged device: only share this code with the intended operator. Local capability"
                  + " limits still apply.",
              NamedTextColor.GOLD));
    if (!runtime.gateway().requestPairingCode(role)) {
      plugin.messages().send(sender, "gateway-unavailable");
      return;
    }
    plugin.messages().send(sender, "pair-requested");
    waitForPairingCode(sender);
  }

  private void waitForPairingCode(CommandSender sender) {
    new BukkitRunnable() {
      private int attempts;

      @Override
      public void run() {
        if (sender instanceof Player player && !player.isOnline()) {
          cancel();
          return;
        }
        var code = plugin.pairingState().activeCode();
        if (code.isPresent()) {
          PairingState.PairingCode value = code.orElseThrow();
          plugin
              .messages()
              .send(
                  sender,
                  "pair-code",
                  Map.of(
                      "code", value.value(),
                      "expires", value.expiresAt().toString()));
          cancel();
          return;
        }
        if (++attempts >= 10) {
          plugin.messages().send(sender, "pair-timeout");
          cancel();
        }
      }
    }.runTaskTimer(plugin, 20L, 20L);
  }

  private void listDevices(CommandSender sender) {
    plugin
        .getServer()
        .getScheduler()
        .runTaskAsynchronously(
            plugin,
            () -> {
              try {
                var state = plugin.runtime().devices().snapshot();
                plugin
                    .getServer()
                    .getScheduler()
                    .runTask(
                        plugin,
                        () -> {
                          for (var device : state.devices())
                            sendRow(
                                sender, device.name() + " / " + device.role(), device.deviceId());
                        });
              } catch (Exception error) {
                plugin.getLogger().warning("Could not load local devices");
              }
            });
  }

  private void revoke(CommandSender sender, String id) {
    plugin
        .getServer()
        .getScheduler()
        .runTaskAsynchronously(
            plugin,
            () -> {
              try {
                plugin.runtime().gateway().revokeDevice(id);
                plugin
                    .getServer()
                    .getScheduler()
                    .runTask(
                        plugin,
                        () ->
                            sender.sendMessage(
                                Component.text("Device revoked locally.", NamedTextColor.GREEN)));
              } catch (Exception error) {
                plugin
                    .getServer()
                    .getScheduler()
                    .runTask(plugin, () -> fail(sender, "Unable to revoke device", error));
              }
            });
  }

  private void unpair(CommandSender sender) {
    plugin
        .getServer()
        .getScheduler()
        .runTaskAsynchronously(
            plugin,
            () -> {
              try {
                plugin.runtime().gateway().requestUnpair();
                plugin
                    .getServer()
                    .getScheduler()
                    .runTask(plugin, () -> plugin.messages().send(sender, "unpaired"));
              } catch (Exception error) {
                plugin
                    .getServer()
                    .getScheduler()
                    .runTask(plugin, () -> fail(sender, "Unable to revoke all devices", error));
              }
            });
  }

  private void rotate(CommandSender sender, String[] arguments) {
    if (arguments.length < 2 || !"confirm".equalsIgnoreCase(arguments[1])) {
      plugin.messages().send(sender, "rotate-warning");
      return;
    }
    try {
      plugin.rotateIdentity();
      plugin.messages().send(sender, "rotated");
    } catch (Exception error) {
      fail(sender, "Unable to rotate PlexonPanel identity", error);
    }
  }

  private void reload(CommandSender sender) {
    try {
      plugin.reloadAgent();
      plugin.messages().send(sender, "reloaded");
    } catch (Exception error) {
      fail(sender, "Unable to reload PlexonPanel", error);
    }
  }

  private void showDiagnostics(CommandSender sender) {
    plugin.messages().send(sender, "diagnostics-header");
    CoreBridge core = plugin.coreBridge();
    sendRow(sender, "Plugin", plugin.getPluginMeta().getVersion());
    sendRow(sender, "Mode", core == null ? "STANDALONE" : core.mode());
    sendRow(
        sender,
        "Core plugin/API",
        core == null ? "- / -" : core.pluginVersion() + " / " + core.apiVersion());
    sendRow(sender, "Supported Core", CoreBridge.SUPPORTED_API_RANGE);
    sendRow(sender, "Module", core == null ? "NOT_REGISTERED" : core.registrationState());
    sendRow(sender, "Public Panel API", plugin.panelApiRegistered() ? "REGISTERED" : "NOT_REGISTERED");
    sendRow(sender, "Protocol", Integer.toString(ProtocolCodec.VERSION));

    AgentRuntime runtime = plugin.runtime();
    if (runtime == null) {
      sendRow(sender, "Runtime", "stopped");
      return;
    }
    GatewayClient gateway = runtime.gateway();
    sendRow(sender, "Relay state", gateway.state().name());
    sendRow(
        sender,
        "Relay signature key",
        gateway.hasGatewayVerificationKey() ? "configured" : "missing");
    sendRow(sender, "Relay authenticated", Boolean.toString(gateway.isAuthenticated()));
    sendRow(sender, "Dropped messages", Long.toString(gateway.droppedMessages()));
    Map<String, Object> telemetry = runtime.telemetry().diagnostics();
    sendRow(sender, "Skipped snapshots", telemetry.get("skippedSnapshots").toString());
    sendRow(
        sender,
        "Dropped presence events",
        telemetry.get("droppedPresenceEvents").toString());
    Map<String, Object> presence = runtime.presence().diagnostics();
    sendRow(sender, "Presence history", presence.get("state").toString());
    sendRow(sender, "Presence writer queue", presence.get("queueDepth").toString());
    sendRow(sender, "Last connected", formatInstant(gateway.lastConnectedAt()));
    sendRow(sender, "Last authenticated", formatInstant(gateway.lastAuthenticatedAt()));
    sendRow(sender, "Session age", formatSessionAge(gateway.lastAuthenticatedAt()));
    sendRow(sender, "Last relay message", formatInstant(gateway.lastMessageAt()));
    sendRow(sender, "Reconnect attempts", Integer.toString(gateway.reconnectAttempts()));
    sendRow(sender, "Current backoff", formatDuration(gateway.currentBackoffMillis()));
    sendRow(sender, "Next retry", formatInstant(gateway.nextRetryAt()));
    sendRow(sender, "Session nonce prefix", gateway.currentSessionNoncePrefix());
    sendRow(
        sender,
        "Pending critical messages",
        Long.toString(gateway.pendingCriticalMessages()));
    if (!gateway.lastAcceptedRelayMessageType().isBlank())
      sendRow(sender, "Last accepted relay message", gateway.lastAcceptedRelayMessageType());
    if (!gateway.lastProtocolRejectionCode().isBlank())
      sendRow(sender, "Last protocol rejection", gateway.lastProtocolRejectionCode());
    sendRow(
        sender, "Recent console lines", Integer.toString(runtime.console().recentLines().size()));
    if (!gateway.lastError().isBlank()) {
      sendRow(sender, "Last error", gateway.lastError());
    }
  }

  private void fail(CommandSender sender, String logMessage, Exception error) {
    plugin.getLogger().log(Level.WARNING, logMessage, error);
    plugin.messages().send(sender, "operation-failed");
  }

  private static void sendRow(CommandSender sender, String label, String value) {
    sender.sendMessage(
        Component.text(" • " + label + ": ", NamedTextColor.GRAY)
            .append(Component.text(value, NamedTextColor.WHITE)));
  }

  private static String enabled(boolean value) {
    return value ? "enabled" : "disabled";
  }

  private static String formatInstant(Instant instant) {
    return instant == null ? "never" : instant.toString();
  }

  private static String formatSessionAge(Instant authenticatedAt) {
    if (authenticatedAt == null) return "not authenticated";
    long millis = Math.max(0L, Duration.between(authenticatedAt, Instant.now()).toMillis());
    return formatDuration(millis);
  }

  private static String formatDuration(long millis) {
    if (millis <= 0L) return "0s";
    long seconds = Math.max(1L, millis / 1000L);
    long minutes = seconds / 60L;
    long hours = minutes / 60L;
    if (hours > 0L) return hours + "h " + (minutes % 60L) + "m";
    if (minutes > 0L) return minutes + "m " + (seconds % 60L) + "s";
    return seconds + "s";
  }

  @Override
  public List<String> onTabComplete(
      @NotNull CommandSender sender,
      @NotNull Command command,
      @NotNull String alias,
      @NotNull String[] arguments) {
    if (arguments.length == 1) {
      String prefix = arguments[0].toLowerCase(Locale.ROOT);
      List<String> matches = new ArrayList<>();
      for (String subcommand : SUBCOMMANDS) {
        if (subcommand.startsWith(prefix)
            && sender.hasPermission(
                "plexonpanel." + (subcommand.equals("revoke-all") ? "revoke" : subcommand))) {
          matches.add(subcommand);
        }
      }
      return matches;
    }
    if (arguments.length == 2 && "pair".equalsIgnoreCase(arguments[0]) && plugin.runtime() != null)
      return plugin.runtime().policy().roles().keySet().stream()
          .filter(
              name ->
                  name.toLowerCase(Locale.ROOT).startsWith(arguments[1].toLowerCase(Locale.ROOT)))
          .toList();
    if (arguments.length == 2
        && "rotate".equalsIgnoreCase(arguments[0])
        && "confirm".startsWith(arguments[1].toLowerCase(Locale.ROOT))) {
      return List.of("confirm");
    }
    return List.of();
  }
}
