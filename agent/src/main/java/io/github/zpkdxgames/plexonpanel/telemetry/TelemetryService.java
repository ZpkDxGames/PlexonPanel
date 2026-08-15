package io.github.zpkdxgames.plexonpanel.telemetry;

import io.github.zpkdxgames.plexonpanel.config.PanelSettings;
import io.github.zpkdxgames.plexonpanel.protocol.MessagePriority;
import io.github.zpkdxgames.plexonpanel.protocol.MessageSink;
import io.github.zpkdxgames.plexonpanel.util.NamedThreadFactory;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

public final class TelemetryService implements Listener, AutoCloseable {
    private final JavaPlugin plugin;
    private final PanelSettings.Telemetry settings;
    private final MessageSink sink;
    private final PaperSnapshotCollector paperCollector;
    private final SystemMetricsCollector systemCollector;
    private final ScheduledExecutorService systemExecutor;
    private BukkitTask serverTask;
    private BukkitTask pluginTask;
    private ScheduledFuture<?> systemTask;

    public TelemetryService(
        JavaPlugin plugin,
        PanelSettings.Telemetry settings,
        MessageSink sink,
        Path serverRoot
    ) {
        this.plugin = plugin;
        this.settings = settings;
        this.sink = sink;
        this.paperCollector = new PaperSnapshotCollector(plugin.getServer(), settings);
        this.systemCollector = new SystemMetricsCollector(serverRoot);
        this.systemExecutor = Executors.newSingleThreadScheduledExecutor(new NamedThreadFactory("plexonpanel-system"));
    }

    public void start() {
        if (!settings.enabled()) {
            return;
        }
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        serverTask = Bukkit.getScheduler().runTaskTimer(plugin, this::sendServerAndPlayers,
            1L, settings.serverIntervalTicks());
        long pluginPeriodTicks = Math.multiplyExact(settings.pluginIntervalSeconds(), 20L);
        pluginTask = Bukkit.getScheduler().runTaskTimer(plugin, this::sendPlugins, 20L, pluginPeriodTicks);
        systemTask = systemExecutor.scheduleAtFixedRate(this::sendSystem, 0L,
            settings.systemIntervalSeconds(), TimeUnit.SECONDS);
    }

    public void sendInitialSnapshots() {
        if (!settings.enabled()) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            sendServerAndPlayers();
            sendPlugins();
        });
        systemExecutor.execute(this::sendSystem);
    }

    private void sendServerAndPlayers() {
        sink.send("telemetry.server", paperCollector.serverSnapshot(), MessagePriority.TELEMETRY);
        sink.send("inventory.players", Map.of(
            "capturedAt", java.time.Instant.now().toString(),
            "players", paperCollector.playerSnapshots()
        ), MessagePriority.TELEMETRY);
    }

    private void sendPlugins() {
        sink.send("inventory.plugins", Map.of(
            "capturedAt", java.time.Instant.now().toString(),
            "plugins", paperCollector.pluginSnapshots()
        ), MessagePriority.TELEMETRY);
    }

    private void sendSystem() {
        try {
            sink.send("telemetry.system", systemCollector.collect(), MessagePriority.TELEMETRY);
        } catch (Exception error) {
            plugin.getLogger().log(Level.FINE, "Unable to collect system telemetry", error);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent ignored) {
        schedulePlayerRefresh();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent ignored) {
        schedulePlayerRefresh();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPluginEnable(PluginEnableEvent event) {
        if (event.getPlugin() != plugin) {
            Bukkit.getScheduler().runTask(plugin, this::sendPlugins);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPluginDisable(PluginDisableEvent event) {
        if (event.getPlugin() != plugin) {
            Bukkit.getScheduler().runTask(plugin, this::sendPlugins);
        }
    }

    private void schedulePlayerRefresh() {
        Bukkit.getScheduler().runTask(plugin, this::sendServerAndPlayers);
    }

    @Override
    public void close() {
        HandlerList.unregisterAll(this);
        if (serverTask != null) {
            serverTask.cancel();
        }
        if (pluginTask != null) {
            pluginTask.cancel();
        }
        if (systemTask != null) {
            systemTask.cancel(false);
        }
        systemExecutor.shutdownNow();
    }
}
