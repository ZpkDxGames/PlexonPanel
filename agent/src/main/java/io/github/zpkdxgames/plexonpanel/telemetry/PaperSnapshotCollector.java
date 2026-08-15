package io.github.zpkdxgames.plexonpanel.telemetry;

import io.github.zpkdxgames.plexonpanel.config.PanelSettings;
import io.github.zpkdxgames.plexonpanel.model.PlayerSnapshot;
import io.github.zpkdxgames.plexonpanel.model.PluginSnapshot;
import io.github.zpkdxgames.plexonpanel.model.ServerSnapshot;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import io.papermc.paper.plugin.configuration.PluginMeta;

import java.net.InetSocketAddress;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

public final class PaperSnapshotCollector {
    private final Server server;
    private final PanelSettings.Telemetry settings;
    private final Clock clock;
    private final PlainTextComponentSerializer plainText = PlainTextComponentSerializer.plainText();

    public PaperSnapshotCollector(Server server, PanelSettings.Telemetry settings) {
        this(server, settings, Clock.systemUTC());
    }

    PaperSnapshotCollector(Server server, PanelSettings.Telemetry settings, Clock clock) {
        this.server = server;
        this.settings = settings;
        this.clock = clock;
    }

    public ServerSnapshot serverSnapshot() {
        double[] rawTps = server.getTPS();
        List<Double> tps = Arrays.stream(rawTps)
            .map(value -> Math.round(value * 100.0) / 100.0)
            .boxed()
            .toList();

        long[] rawTickTimes = server.getTickTimes();
        double[] tickMillis = Arrays.stream(rawTickTimes)
            .mapToDouble(nanos -> nanos / 1_000_000.0)
            .sorted()
            .toArray();
        double p95 = percentile(tickMillis, 0.95);
        double maximum = tickMillis.length == 0 ? 0.0 : tickMillis[tickMillis.length - 1];

        return new ServerSnapshot(
            clock.instant().toString(),
            server.getName(),
            server.getVersion(),
            server.getMinecraftVersion(),
            server.getOnlinePlayers().size(),
            server.getMaxPlayers(),
            tps,
            round(server.getAverageTickTime()),
            round(p95),
            round(maximum),
            server.getCurrentTick()
        );
    }

    public List<PlayerSnapshot> playerSnapshots() {
        List<PlayerSnapshot> result = new ArrayList<>(server.getOnlinePlayers().size());
        for (Player player : server.getOnlinePlayers()) {
            Location location = player.getLocation();
            PlayerSnapshot.Position position = settings.includePlayerLocation()
                ? new PlayerSnapshot.Position(
                    round(location.getX()),
                    round(location.getY()),
                    round(location.getZ()),
                    location.getYaw(),
                    location.getPitch()
                )
                : null;

            String address = null;
            if (settings.includePlayerAddress()) {
                InetSocketAddress socketAddress = player.getAddress();
                if (socketAddress != null && socketAddress.getAddress() != null) {
                    address = socketAddress.getAddress().getHostAddress();
                }
            }

            AttributeInstance maxHealthAttribute = player.getAttribute(Attribute.MAX_HEALTH);
            double maximumHealth = maxHealthAttribute == null ? player.getHealth() : maxHealthAttribute.getValue();
            result.add(new PlayerSnapshot(
                player.getUniqueId().toString(),
                player.getName(),
                plainText.serialize(player.displayName()),
                player.getWorld().getName(),
                player.getGameMode().name(),
                player.getPing(),
                round(player.getHealth()),
                round(maximumHealth),
                player.getLevel(),
                player.isOp(),
                player.isWhitelisted(),
                position,
                address
            ));
        }
        result.sort(Comparator.comparing(PlayerSnapshot::name, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(result);
    }

    public List<PluginSnapshot> pluginSnapshots() {
        List<PluginSnapshot> result = new ArrayList<>();
        for (Plugin plugin : server.getPluginManager().getPlugins()) {
            PluginMeta metadata = plugin.getPluginMeta();
            result.add(new PluginSnapshot(
                metadata.getName(),
                metadata.getVersion(),
                metadata.getMainClass(),
                List.copyOf(metadata.getAuthors()),
                metadata.getWebsite(),
                plugin.isEnabled()
            ));
        }
        result.sort(Comparator.comparing(PluginSnapshot::name, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(result);
    }

    private static double percentile(double[] sorted, double percentile) {
        if (sorted.length == 0) {
            return 0.0;
        }
        int index = (int) Math.ceil(percentile * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(index, sorted.length - 1))];
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
