package io.github.zpkdxgames.plexonpanel.config;

import org.bukkit.configuration.file.FileConfiguration;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

public record PanelSettings(
    Gateway gateway,
    Telemetry telemetry,
    Console console,
    Chat chat,
    RemoteActions remoteActions,
    Audit audit
) {
    public static PanelSettings load(FileConfiguration config) {
        Objects.requireNonNull(config, "config");

        Gateway gateway = new Gateway(
            config.getBoolean("gateway.enabled", false),
            validateGatewayUri(config.getString("gateway.url", "")),
            config.getString("gateway.public-key", "").strip(),
            config.getBoolean("gateway.require-signed-messages", true),
            bounded(config.getInt("gateway.connect-timeout-seconds", 10), 2, 60, "gateway.connect-timeout-seconds"),
            bounded(config.getInt("gateway.heartbeat-seconds", 15), 5, 120, "gateway.heartbeat-seconds"),
            bounded(config.getInt("gateway.reconnect.initial-delay-seconds", 2), 1, 60, "gateway.reconnect.initial-delay-seconds"),
            bounded(config.getInt("gateway.reconnect.maximum-delay-seconds", 60), 5, 900, "gateway.reconnect.maximum-delay-seconds"),
            bounded(config.getInt("gateway.queue-capacity", 2048), 64, 65_536, "gateway.queue-capacity")
        );
        if (gateway.initialReconnectDelaySeconds() > gateway.maximumReconnectDelaySeconds()) {
            throw new IllegalArgumentException("gateway reconnect initial delay cannot exceed maximum delay");
        }

        Telemetry telemetry = new Telemetry(
            config.getBoolean("telemetry.enabled", true),
            bounded(config.getLong("telemetry.server-interval-ticks", 40), 20, 72_000, "telemetry.server-interval-ticks"),
            bounded(config.getLong("telemetry.system-interval-seconds", 5), 1, 3600, "telemetry.system-interval-seconds"),
            bounded(config.getLong("telemetry.plugin-interval-seconds", 60), 10, 86_400, "telemetry.plugin-interval-seconds"),
            config.getBoolean("telemetry.include-player-location", false),
            config.getBoolean("telemetry.include-player-address", false)
        );

        Console console = new Console(
            config.getBoolean("console.stream-enabled", false),
            config.getBoolean("console.errors-enabled", true),
            bounded(config.getLong("console.poll-interval-millis", 250), 100, 5000, "console.poll-interval-millis"),
            bounded(config.getLong("console.batch-interval-millis", 300), 100, 5000, "console.batch-interval-millis"),
            bounded(config.getInt("console.batch-size", 100), 1, 1000, "console.batch-size"),
            bounded(config.getInt("console.ring-buffer-lines", 1000), 100, 100_000, "console.ring-buffer-lines"),
            bounded(config.getInt("console.maximum-line-bytes", 16_384), 512, 1_048_576, "console.maximum-line-bytes"),
            List.copyOf(config.getStringList("console.redact-patterns"))
        );

        Chat chat = new Chat(
            config.getBoolean("chat.stream-enabled", false),
            config.getBoolean("chat.capture-vanilla-global", true),
            config.getBoolean("chat.capture-plexonchats-global", true),
            config.getBoolean("chat.allow-dashboard-send", false),
            config.getBoolean("chat.allow-minimessage-from-dashboard", false),
            config.getString("chat.dashboard-prefix", "<gray>[PlexonPanel] <actor>: </gray>")
        );

        RemoteActions remoteActions = new RemoteActions(
            config.getBoolean("remote-actions.enabled", false),
            Duration.ofSeconds(bounded(config.getLong("remote-actions.maximum-clock-skew-seconds", 30), 5, 300,
                "remote-actions.maximum-clock-skew-seconds")),
            new RemoteConsole(
                config.getBoolean("remote-actions.console.enabled", false),
                List.copyOf(config.getStringList("remote-actions.console.allow")),
                List.copyOf(config.getStringList("remote-actions.console.deny"))
            ),
            new RemotePlayers(
                config.getBoolean("remote-actions.players.message", true),
                config.getBoolean("remote-actions.players.kick", true),
                config.getBoolean("remote-actions.players.ban", false),
                config.getBoolean("remote-actions.players.unban", false),
                config.getBoolean("remote-actions.players.whitelist", false)
            )
        );

        Audit audit = new Audit(
            config.getBoolean("audit.local-jsonl-enabled", true),
            bounded(config.getInt("audit.retention-days", 30), 1, 3650, "audit.retention-days")
        );

        return new PanelSettings(gateway, telemetry, console, chat, remoteActions, audit);
    }

    private static URI validateGatewayUri(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        URI uri = URI.create(value.strip());
        if (!"wss".equalsIgnoreCase(uri.getScheme()) && !isLoopbackDevelopmentUri(uri)) {
            throw new IllegalArgumentException("gateway.url must use wss:// (ws:// is allowed only for loopback development)");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("gateway.url must include a host");
        }
        return uri;
    }

    private static boolean isLoopbackDevelopmentUri(URI uri) {
        if (!"ws".equalsIgnoreCase(uri.getScheme())) {
            return false;
        }
        String host = uri.getHost();
        return "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "::1".equals(host);
    }

    private static int bounded(int value, int minimum, int maximum, String path) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(path + " must be between " + minimum + " and " + maximum);
        }
        return value;
    }

    private static long bounded(long value, long minimum, long maximum, String path) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(path + " must be between " + minimum + " and " + maximum);
        }
        return value;
    }

    public record Gateway(
        boolean enabled,
        URI url,
        String publicKeyBase64,
        boolean requireSignedMessages,
        int connectTimeoutSeconds,
        int heartbeatSeconds,
        int initialReconnectDelaySeconds,
        int maximumReconnectDelaySeconds,
        int queueCapacity
    ) {
    }

    public record Telemetry(
        boolean enabled,
        long serverIntervalTicks,
        long systemIntervalSeconds,
        long pluginIntervalSeconds,
        boolean includePlayerLocation,
        boolean includePlayerAddress
    ) {
    }

    public record Console(
        boolean streamEnabled,
        boolean errorsEnabled,
        long pollIntervalMillis,
        long batchIntervalMillis,
        int batchSize,
        int ringBufferLines,
        int maximumLineBytes,
        List<String> redactPatterns
    ) {
    }

    public record Chat(
        boolean streamEnabled,
        boolean captureVanillaGlobal,
        boolean capturePlexonChatsGlobal,
        boolean allowDashboardSend,
        boolean allowMiniMessageFromDashboard,
        String dashboardPrefix
    ) {
    }

    public record RemoteActions(
        boolean enabled,
        Duration maximumClockSkew,
        RemoteConsole console,
        RemotePlayers players
    ) {
    }

    public record RemoteConsole(boolean enabled, List<String> allowPatterns, List<String> denyPatterns) {
    }

    public record RemotePlayers(boolean message, boolean kick, boolean ban, boolean unban, boolean whitelist) {
    }

    public record Audit(boolean localJsonlEnabled, int retentionDays) {
    }
}
