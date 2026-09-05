package io.github.zpkdxgames.plexonpanel.telemetry;

import io.github.zpkdxgames.plexonpanel.config.PanelSettings;
import io.github.zpkdxgames.plexonpanel.protocol.MessagePriority;
import io.github.zpkdxgames.plexonpanel.protocol.MessageSink;
import io.github.zpkdxgames.plexonpanel.util.NamedThreadFactory;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
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

public final class TelemetryService implements Listener, AutoCloseable {
  private final JavaPlugin plugin;
  private final PanelSettings.Telemetry settings;
  private final MessageSink sink;
  private final PaperSnapshotCollector paperCollector;
  private final SystemMetrics systemCollector;
  private final ScheduledExecutorService systemExecutor;
  private final java.util.concurrent.ThreadPoolExecutor snapshotWorker =
      new java.util.concurrent.ThreadPoolExecutor(
          1,
          1,
          0,
          TimeUnit.SECONDS,
          new java.util.concurrent.ArrayBlockingQueue<>(8),
          new NamedThreadFactory("plexonpanel-inventory"),
          new java.util.concurrent.ThreadPoolExecutor.DiscardPolicy());
  private BukkitTask serverTask;
  private BukkitTask pluginTask;
  private BukkitTask worldsTask;
  private ScheduledFuture<?> systemTask;

  public TelemetryService(
      JavaPlugin plugin, PanelSettings.Telemetry settings, MessageSink sink, Path serverRoot) {
    this.plugin = plugin;
    this.settings = settings;
    this.sink = sink;
    this.paperCollector = new PaperSnapshotCollector(plugin.getServer(), settings);
    this.systemCollector = new SystemMetrics(serverRoot);
    this.systemExecutor =
        Executors.newSingleThreadScheduledExecutor(new NamedThreadFactory("plexonpanel-system"));
  }

  public void start() {
    if (!settings.enabled()) {
      return;
    }
    plugin.getServer().getPluginManager().registerEvents(this, plugin);
    serverTask =
        Bukkit.getScheduler()
            .runTaskTimer(plugin, this::sendServerAndPlayers, 1L, settings.serverIntervalTicks());
    long pluginPeriodTicks = Math.multiplyExact(settings.pluginIntervalSeconds(), 20L);
    pluginTask =
        Bukkit.getScheduler().runTaskTimer(plugin, this::sendPlugins, 20L, pluginPeriodTicks);
    worldsTask =
        Bukkit.getScheduler()
            .runTaskTimer(
                plugin,
                () ->
                    sink.send(
                        "telemetry.worlds",
                        Map.of(
                            "capturedAt",
                            java.time.Instant.now().toString(),
                            "worlds",
                            paperCollector.worldSnapshots()),
                        MessagePriority.TELEMETRY),
                40L,
                200L);
    systemTask =
        systemExecutor.scheduleAtFixedRate(
            this::sendSystem, 0L, settings.systemIntervalSeconds(), TimeUnit.SECONDS);
  }

  public void sendInitialSnapshots() {
    if (!settings.enabled()) {
      return;
    }
    Bukkit.getScheduler()
        .runTask(
            plugin,
            () -> {
              sendServerAndPlayers();
              sendPlugins();
            });
    systemExecutor.execute(this::sendSystem);
  }

  private void sendServerAndPlayers() {
    sink.send("telemetry.server", paperCollector.serverSnapshot(), MessagePriority.TELEMETRY);
    var players = paperCollector.playerSnapshots();
    snapshotWorker.execute(
        () -> {
          for (var batch :
              io.github.zpkdxgames.plexonpanel.protocol.SnapshotBatches.split(
                  "players", players, 512))
            sink.send("inventory.players", batch, MessagePriority.TELEMETRY);
        });
  }

  private void sendPlugins() {
    var plugins = paperCollector.pluginSnapshots();
    snapshotWorker.execute(
        () -> {
          for (var batch :
              io.github.zpkdxgames.plexonpanel.protocol.SnapshotBatches.split(
                  "plugins", plugins, 256))
            sink.send("inventory.plugins", batch, MessagePriority.TELEMETRY);
        });
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
    if (worldsTask != null) worldsTask.cancel();
    if (pluginTask != null) {
      pluginTask.cancel();
    }
    if (systemTask != null) {
      systemTask.cancel(false);
    }
    snapshotWorker.shutdownNow();
    systemExecutor.shutdownNow();
  }
}
