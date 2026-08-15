package io.github.zpkdxgames.plexonpanel.command;

import io.github.zpkdxgames.plexonpanel.AgentRuntime;
import io.github.zpkdxgames.plexonpanel.PlexonPanelPlugin;
import io.github.zpkdxgames.plexonpanel.identity.PairingState;
import io.github.zpkdxgames.plexonpanel.transport.GatewayClient;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;

public final class PlexonPanelCommand implements CommandExecutor, TabCompleter {
    private static final List<String> SUBCOMMANDS = List.of("status", "pair", "unpair", "rotate", "reload", "diagnostics");

    private final PlexonPanelPlugin plugin;

    public PlexonPanelCommand(PlexonPanelPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(
        @NotNull CommandSender sender,
        @NotNull Command command,
        @NotNull String label,
        @NotNull String[] arguments
    ) {
        String subcommand = arguments.length == 0 ? "status" : arguments[0].toLowerCase(Locale.ROOT);
        if (!SUBCOMMANDS.contains(subcommand)) {
            plugin.messages().send(sender, "help");
            return true;
        }
        if (!sender.hasPermission("plexonpanel." + subcommand)) {
            plugin.messages().send(sender, "no-permission");
            return true;
        }

        switch (subcommand) {
            case "status" -> showStatus(sender);
            case "pair" -> requestPairing(sender);
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
        sender.sendMessage(Component.text("PlexonPanel " + plugin.getPluginMeta().getVersion(), NamedTextColor.AQUA));
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

    private void requestPairing(CommandSender sender) {
        AgentRuntime runtime = plugin.runtime();
        if (runtime == null || !runtime.settings().gateway().enabled()) {
            plugin.messages().send(sender, "gateway-disabled");
            return;
        }
        if (plugin.pairingState().isPaired()) {
            plugin.messages().send(sender, "already-paired");
            return;
        }
        if (!runtime.gateway().requestPairingCode()) {
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
                if (plugin.pairingState().isPaired()) {
                    plugin.messages().send(sender, "pair-complete");
                    cancel();
                    return;
                }
                var code = plugin.pairingState().activeCode();
                if (code.isPresent()) {
                    PairingState.PairingCode value = code.orElseThrow();
                    plugin.messages().send(sender, "pair-code", Map.of(
                        "code", value.value(),
                        "expires", value.expiresAt().toString()
                    ));
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

    private void unpair(CommandSender sender) {
        AgentRuntime runtime = plugin.runtime();
        try {
            if (runtime == null) {
                throw new IllegalStateException("The gateway runtime is unavailable");
            }
            runtime.gateway().requestUnpair();
            plugin.messages().send(sender, "unpaired");
        } catch (Exception error) {
            fail(sender, "Unable to clear pairing state", error);
        }
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
        AgentRuntime runtime = plugin.runtime();
        if (runtime == null) {
            sendRow(sender, "Runtime", "stopped");
            return;
        }
        GatewayClient gateway = runtime.gateway();
        sendRow(sender, "Gateway state", gateway.state().name());
        sendRow(sender, "Gateway signature key", gateway.hasGatewayVerificationKey() ? "configured" : "missing");
        sendRow(sender, "Gateway authenticated", Boolean.toString(gateway.isAuthenticated()));
        sendRow(sender, "Dropped messages", Long.toString(gateway.droppedMessages()));
        sendRow(sender, "Last connected", formatInstant(gateway.lastConnectedAt()));
        sendRow(sender, "Last gateway message", formatInstant(gateway.lastMessageAt()));
        sendRow(sender, "Recent console lines", Integer.toString(runtime.console().recentLines().size()));
        if (!gateway.lastError().isBlank()) {
            sendRow(sender, "Last error", gateway.lastError());
        }
    }

    private void fail(CommandSender sender, String logMessage, Exception error) {
        plugin.getLogger().log(Level.WARNING, logMessage, error);
        plugin.messages().send(sender, "operation-failed");
    }

    private static void sendRow(CommandSender sender, String label, String value) {
        sender.sendMessage(Component.text(" • " + label + ": ", NamedTextColor.GRAY)
            .append(Component.text(value, NamedTextColor.WHITE)));
    }

    private static String enabled(boolean value) {
        return value ? "enabled" : "disabled";
    }

    private static String formatInstant(Instant instant) {
        return instant == null ? "never" : instant.toString();
    }

    @Override
    public List<String> onTabComplete(
        @NotNull CommandSender sender,
        @NotNull Command command,
        @NotNull String alias,
        @NotNull String[] arguments
    ) {
        if (arguments.length == 1) {
            String prefix = arguments[0].toLowerCase(Locale.ROOT);
            List<String> matches = new ArrayList<>();
            for (String subcommand : SUBCOMMANDS) {
                if (subcommand.startsWith(prefix) && sender.hasPermission("plexonpanel." + subcommand)) {
                    matches.add(subcommand);
                }
            }
            return matches;
        }
        if (arguments.length == 2 && "rotate".equalsIgnoreCase(arguments[0])
            && "confirm".startsWith(arguments[1].toLowerCase(Locale.ROOT))) {
            return List.of("confirm");
        }
        return List.of();
    }
}
