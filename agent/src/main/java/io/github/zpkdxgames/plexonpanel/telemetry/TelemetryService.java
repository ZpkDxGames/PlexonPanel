package io.github.zpkdxgames.plexonpanel.telemetry;

import io.github.zpkdxgames.plexonpanel.config.PanelSettings;
import io.github.zpkdxgames.plexonpanel.presence.ObservedPlayer;
import io.github.zpkdxgames.plexonpanel.presence.PlayerPresenceService;
import io.github.zpkdxgames.plexonpanel.presence.PresenceRecord;
import io.github.zpkdxgames.plexonpanel.presence.PresenceTermination;
import io.github.zpkdxgames.plexonpanel.protocol.MessagePriority;
import io.github.zpkdxgames.plexonpanel.protocol.MessageSink;
import io.github.zpkdxgames.plexonpanel.protocol.SnapshotBatches;
import io.github.zpkdxgames.plexonpanel.util.NamedThreadFactory;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Captures Paper-owned data on the primary thread and performs batching, serialization, and relay
 * writes only on bounded workers. Presence events have a dedicated worker so periodic telemetry
 * cannot delay them.
 */
public final class TelemetryService implements Listener, AutoCloseable {
  public record SnapshotRequest(boolean queued, boolean coalesced) {}

  private static final int TELEMETRY_QUEUE_CAPACITY = 16;
  private static final int EVENT_QUEUE_CAPACITY = 1024;
  private static final long SATURATION_WARNING_MILLIS = 60_000L;

  private final JavaPlugin plugin;
  private final PanelSettings.Telemetry settings;
  private final MessageSink sink;
  private final PlayerPresenceService presence;
  private final PaperSnapshotCollector paperCollector;
  private final SystemMetrics systemCollector;
  private final ScheduledExecutorService systemExecutor;
  private final ThreadPoolExecutor telemetryWorker;
  private final ThreadPoolExecutor eventWorker;
  private final AtomicBoolean started = new AtomicBoolean();
  private final AtomicBoolean closed = new AtomicBoolean();
  private final AtomicBoolean playerSnapshotInFlight = new AtomicBoolean();
  private final AtomicBoolean playerSnapshotPending = new AtomicBoolean();
  private final AtomicBoolean pluginSnapshotInFlight = new AtomicBoolean();
  private final AtomicBoolean pluginSnapshotPending = new AtomicBoolean();
  private final AtomicBoolean pluginForcePending = new AtomicBoolean();
  private final AtomicBoolean worldSnapshotInFlight = new AtomicBoolean();
  private final AtomicBoolean worldSnapshotPending = new AtomicBoolean();
  private final AtomicBoolean worldForcePending = new AtomicBoolean();
  private final AtomicBoolean serverSendInFlight = new AtomicBoolean();
  private final AtomicLong transportEpoch = new AtomicLong(1);
  private final AtomicLong skippedSnapshots = new AtomicLong();
  private final AtomicLong droppedPresenceEvents = new AtomicLong();
  private final AtomicLong nextSaturationWarning = new AtomicLong();
  private final AtomicReference<List<?>> lastPluginInventory = new AtomicReference<>();
  private final AtomicReference<List<?>> lastWorldInventory = new AtomicReference<>();
  private BukkitTask serverTask;
  private BukkitTask playerTask;
  private BukkitTask pluginTask;
  private BukkitTask worldsTask;
  private ScheduledFuture<?> systemTask;

  public TelemetryService(
      JavaPlugin plugin,
      PanelSettings.Telemetry settings,
      MessageSink sink,
      Path serverRoot,
      PlayerPresenceService presence) {
    this.plugin = Objects.requireNonNull(plugin, "plugin");
    this.settings = Objects.requireNonNull(settings, "settings");
    this.sink = Objects.requireNonNull(sink, "sink");
    this.presence = Objects.requireNonNull(presence, "presence");
    this.paperCollector = new PaperSnapshotCollector(plugin.getServer(), settings, presence);
    this.systemCollector = new SystemMetrics(serverRoot);
    this.systemExecutor =
        Executors.newSingleThreadScheduledExecutor(new NamedThreadFactory("plexonpanel-system"));
    this.telemetryWorker =
        new ThreadPoolExecutor(
            1,
            1,
            0,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(TELEMETRY_QUEUE_CAPACITY),
            new NamedThreadFactory("plexonpanel-telemetry"),
            new ThreadPoolExecutor.AbortPolicy());
    this.eventWorker =
        new ThreadPoolExecutor(
            1,
            1,
            0,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(EVENT_QUEUE_CAPACITY),
            new NamedThreadFactory("plexonpanel-presence-events"),
            new ThreadPoolExecutor.AbortPolicy());
  }

  public void start() {
    if (closed.get() || !started.compareAndSet(false, true)) return;
    requirePrimaryThread();
    presence.start(captureObservedPlayers());
    if (settings.enabled() || presence.enabled())
      plugin.getServer().getPluginManager().registerEvents(this, plugin);
    if (!settings.enabled()) return;

    serverTask =
        Bukkit.getScheduler()
            .runTaskTimer(plugin, this::captureServer, 1L, settings.serverIntervalTicks());
    playerTask =
        Bukkit.getScheduler()
            .runTaskTimer(
                plugin,
                () -> requestPlayerSnapshot(false),
                2L,
                Math.multiplyExact(settings.playerSnapshotIntervalSeconds(), 20L));
    pluginTask =
        Bukkit.getScheduler()
            .runTaskTimer(
                plugin,
                () -> requestPluginSnapshot(false),
                20L,
                Math.multiplyExact(settings.pluginIntervalSeconds(), 20L));
    worldsTask =
        Bukkit.getScheduler()
            .runTaskTimer(
                plugin,
                () -> requestWorldSnapshot(false),
                40L,
                Math.multiplyExact(settings.worldIntervalSeconds(), 20L));
    systemTask =
        systemExecutor.scheduleWithFixedDelay(
            this::sendSystem, 0L, settings.systemIntervalSeconds(), TimeUnit.SECONDS);
  }

  /** Called after each authenticated relay session; prior queued captures are invalidated. */
  public void sendInitialSnapshots() {
    if (!settings.enabled() || closed.get()) return;
    transportEpoch.incrementAndGet();
    scheduleMain(
        () -> {
          captureServer();
          requestPlayerSnapshot(true);
          requestPluginSnapshot(true);
          requestWorldSnapshot(true);
        });
    systemExecutor.execute(this::sendSystem);
  }

  /** A Paper-authorized manual refresh. Concurrent requests collapse into one pending capture. */
  public SnapshotRequest requestPlayerSnapshot() {
    if (!settings.enabled() || closed.get())
      throw new SecurityException("CAPABILITY_DISABLED");
    return requestPlayerSnapshot(true);
  }

  private SnapshotRequest requestPlayerSnapshot(boolean demand) {
    if (closed.get() || !settings.enabled()) return new SnapshotRequest(false, false);
    if (!playerSnapshotInFlight.compareAndSet(false, true)) {
      if (demand) playerSnapshotPending.set(true);
      skippedSnapshots.incrementAndGet();
      return new SnapshotRequest(true, true);
    }
    long epoch = transportEpoch.get();
    if (!scheduleMain(() -> capturePlayers(epoch))) {
      playerSnapshotInFlight.set(false);
      return new SnapshotRequest(false, false);
    }
    return new SnapshotRequest(true, false);
  }

  private void capturePlayers(long epoch) {
    requirePrimaryThread();
    if (closed.get() || epoch != transportEpoch.get()) {
      finishPlayerSnapshot();
      return;
    }
    List<?> players = paperCollector.playerSnapshots();
    String capturedAt = Instant.now().toString();
    if (!submitTelemetry(
        () -> {
          try {
            if (epoch != transportEpoch.get() || closed.get()) return;
            for (Map<String, Object> batch :
                SnapshotBatches.split("players", players, 512, capturedAt))
              sink.send("inventory.players", batch, MessagePriority.TELEMETRY);
          } finally {
            finishPlayerSnapshot();
          }
        })) finishPlayerSnapshot();
  }

  private void finishPlayerSnapshot() {
    playerSnapshotInFlight.set(false);
    if (playerSnapshotPending.getAndSet(false) && !closed.get()) requestPlayerSnapshot(true);
  }

  private void captureServer() {
    requirePrimaryThread();
    if (closed.get() || !settings.enabled()) return;
    if (!serverSendInFlight.compareAndSet(false, true)) {
      skippedSnapshots.incrementAndGet();
      return;
    }
    long epoch = transportEpoch.get();
    Object snapshot = paperCollector.serverSnapshot();
    if (!submitTelemetry(
        () -> {
          try {
            if (epoch == transportEpoch.get() && !closed.get())
              sink.send("telemetry.server", snapshot, MessagePriority.TELEMETRY);
          } finally {
            serverSendInFlight.set(false);
          }
        })) serverSendInFlight.set(false);
  }

  private void requestPluginSnapshot(boolean force) {
    if (closed.get() || !settings.enabled()) return;
    if (!pluginSnapshotInFlight.compareAndSet(false, true)) {
      pluginSnapshotPending.set(true);
      if (force) pluginForcePending.set(true);
      skippedSnapshots.incrementAndGet();
      return;
    }
    long epoch = transportEpoch.get();
    if (!scheduleMain(() -> capturePlugins(epoch, force))) pluginSnapshotInFlight.set(false);
  }

  private void capturePlugins(long epoch, boolean force) {
    requirePrimaryThread();
    if (closed.get() || epoch != transportEpoch.get()) {
      finishPluginSnapshot();
      return;
    }
    List<?> plugins = paperCollector.pluginSnapshots();
    String capturedAt = Instant.now().toString();
    if (!submitTelemetry(
        () -> {
          try {
            List<?> previous = lastPluginInventory.getAndSet(plugins);
            if (epoch != transportEpoch.get() || closed.get()) return;
            if (!force && settings.suppressUnchangedInventories() && plugins.equals(previous)) return;
            for (Map<String, Object> batch :
                SnapshotBatches.split("plugins", plugins, 256, capturedAt))
              sink.send("inventory.plugins", batch, MessagePriority.TELEMETRY);
          } finally {
            finishPluginSnapshot();
          }
        })) finishPluginSnapshot();
  }

  private void finishPluginSnapshot() {
    pluginSnapshotInFlight.set(false);
    if (pluginSnapshotPending.getAndSet(false) && !closed.get())
      requestPluginSnapshot(pluginForcePending.getAndSet(false));
  }

  private void requestWorldSnapshot(boolean force) {
    if (closed.get() || !settings.enabled()) return;
    if (!worldSnapshotInFlight.compareAndSet(false, true)) {
      worldSnapshotPending.set(true);
      if (force) worldForcePending.set(true);
      skippedSnapshots.incrementAndGet();
      return;
    }
    long epoch = transportEpoch.get();
    if (!scheduleMain(() -> captureWorlds(epoch, force))) worldSnapshotInFlight.set(false);
  }

  private void captureWorlds(long epoch, boolean force) {
    requirePrimaryThread();
    if (closed.get() || epoch != transportEpoch.get()) {
      finishWorldSnapshot();
      return;
    }
    List<?> worlds = paperCollector.worldSnapshots();
    String capturedAt = Instant.now().toString();
    if (!submitTelemetry(
        () -> {
          try {
            List<?> previous = lastWorldInventory.getAndSet(worlds);
            if (epoch != transportEpoch.get() || closed.get()) return;
            if (!force && settings.suppressUnchangedInventories() && worlds.equals(previous)) return;
            sink.send(
                "telemetry.worlds",
                Map.of("capturedAt", capturedAt, "worlds", worlds),
                MessagePriority.TELEMETRY);
          } finally {
            finishWorldSnapshot();
          }
        })) finishWorldSnapshot();
  }

  private void finishWorldSnapshot() {
    worldSnapshotInFlight.set(false);
    if (worldSnapshotPending.getAndSet(false) && !closed.get())
      requestWorldSnapshot(worldForcePending.getAndSet(false));
  }

  private void sendSystem() {
    if (closed.get() || !settings.enabled()) return;
    long epoch = transportEpoch.get();
    try {
      Object snapshot = systemCollector.collect();
      if (epoch == transportEpoch.get() && !closed.get())
        sink.send("telemetry.system", snapshot, MessagePriority.TELEMETRY);
    } catch (Exception error) {
      plugin.getLogger().log(Level.FINE, "Unable to collect system telemetry", error);
    }
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void onJoin(PlayerJoinEvent event) {
    Player player = event.getPlayer();
    Instant now = Instant.now();
    PlayerPresenceService.CapturedEvent captured =
        presence.joined(
            player.getUniqueId().toString(),
            player.getName(),
            now,
            firstSeen(player, now),
            totalPlayTime(player));
    emitPresence(captured);
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void onQuit(PlayerQuitEvent event) {
    closeSession(event.getPlayer(), PresenceTermination.QUIT);
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onKick(PlayerKickEvent event) {
    closeSession(event.getPlayer(), PresenceTermination.KICK);
  }

  private void closeSession(Player player, PresenceTermination termination) {
    PlayerPresenceService.CapturedEvent captured =
        presence.left(
            player.getUniqueId().toString(),
            player.getName(),
            Instant.now(),
            termination,
            totalPlayTime(player));
    emitPresence(captured);
  }

  private void emitPresence(PlayerPresenceService.CapturedEvent captured) {
    if (captured == null || !settings.enabled() || !settings.sendPlayerPresenceEvents()) return;
    long epoch = transportEpoch.get();
    try {
      eventWorker.execute(
          () -> {
            if (closed.get() || epoch != transportEpoch.get()) return;
            PresenceRecord record = captured.record();
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("eventId", record.eventId());
            body.put("sessionId", record.sessionId());
            body.put("uuid", record.uuid());
            body.put("name", record.name());
            body.put("state", record.state().name());
            body.put("observedAt", record.observedAt());
            body.put("sessionStartedAt", record.sessionStartedAt());
            body.put("sessionEndedAt", record.sessionEndedAt());
            body.put("sessionDurationMillis", record.sessionDurationMillis());
            body.put("termination", record.termination().name());
            body.put("persistenceState", captured.persistenceState().name());
            if (!sink.send("players.presence", body, MessagePriority.EVENT))
              droppedPresenceEvents.incrementAndGet();
          });
    } catch (RejectedExecutionException busy) {
      droppedPresenceEvents.incrementAndGet();
      warnSaturation("Presence event transport queue is full");
    }
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void onPluginEnable(PluginEnableEvent event) {
    if (event.getPlugin() != plugin && settings.enabled()) requestPluginSnapshot(false);
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void onPluginDisable(PluginDisableEvent event) {
    if (event.getPlugin() != plugin && settings.enabled()) requestPluginSnapshot(false);
  }

  public Map<String, Object> diagnostics() {
    return Map.of(
        "telemetryQueueDepth", telemetryWorker.getQueue().size(),
        "telemetryQueueCapacity", TELEMETRY_QUEUE_CAPACITY,
        "presenceEventQueueDepth", eventWorker.getQueue().size(),
        "presenceEventQueueCapacity", EVENT_QUEUE_CAPACITY,
        "skippedSnapshots", skippedSnapshots.get(),
        "droppedPresenceEvents", droppedPresenceEvents.get(),
        "playerSnapshotInFlight", playerSnapshotInFlight.get());
  }

  private List<ObservedPlayer> captureObservedPlayers() {
    requirePrimaryThread();
    List<ObservedPlayer> players = new ArrayList<>();
    Instant now = Instant.now();
    for (Player player : plugin.getServer().getOnlinePlayers()) {
      if (players.size() >= 512) break;
      Instant started = epochMillisOr(player.getLastLogin(), now);
      Instant firstSeen = firstSeen(player, started);
      players.add(
          new ObservedPlayer(
              player.getUniqueId().toString(),
              player.getName(),
              started.toString(),
              firstSeen.toString(),
              totalPlayTime(player)));
    }
    return List.copyOf(players);
  }

  private static Instant firstSeen(Player player, Instant fallback) {
    Instant first = epochMillisOr(player.getFirstPlayed(), fallback);
    return first.isAfter(fallback) ? fallback : first;
  }

  private static Instant epochMillisOr(long millis, Instant fallback) {
    if (millis <= 0) return fallback;
    try {
      Instant value = Instant.ofEpochMilli(millis);
      return value.isAfter(Instant.now().plusSeconds(300)) ? fallback : value;
    } catch (RuntimeException invalid) {
      return fallback;
    }
  }

  /** Paper's PLAY_ONE_MINUTE statistic is measured in game ticks; one tick is 50 ms. */
  private static Long totalPlayTime(Player player) {
    try {
      return Math.multiplyExact(
          (long) Math.max(0, player.getStatistic(Statistic.PLAY_ONE_MINUTE)), 50L);
    } catch (RuntimeException unavailable) {
      return null;
    }
  }

  private boolean scheduleMain(Runnable operation) {
    if (closed.get()) return false;
    try {
      Bukkit.getScheduler().runTask(plugin, operation);
      return true;
    } catch (RuntimeException stopped) {
      skippedSnapshots.incrementAndGet();
      return false;
    }
  }

  private boolean submitTelemetry(Runnable operation) {
    try {
      telemetryWorker.execute(operation);
      return true;
    } catch (RejectedExecutionException busy) {
      skippedSnapshots.incrementAndGet();
      warnSaturation("Telemetry worker queue is full");
      return false;
    }
  }

  private void warnSaturation(String message) {
    long now = System.currentTimeMillis();
    long next = nextSaturationWarning.get();
    if (now >= next && nextSaturationWarning.compareAndSet(next, now + SATURATION_WARNING_MILLIS))
      plugin.getLogger().warning("PlexonPanel: " + message);
  }

  private static void requirePrimaryThread() {
    if (!Bukkit.isPrimaryThread())
      throw new IllegalStateException("Paper data must be captured on the primary thread");
  }

  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) return;
    transportEpoch.incrementAndGet();
    HandlerList.unregisterAll(this);
    for (BukkitTask task : new BukkitTask[] {serverTask, playerTask, pluginTask, worldsTask})
      if (task != null) task.cancel();
    if (systemTask != null) systemTask.cancel(false);
    telemetryWorker.shutdownNow();
    eventWorker.shutdownNow();
    systemExecutor.shutdownNow();
  }
}
