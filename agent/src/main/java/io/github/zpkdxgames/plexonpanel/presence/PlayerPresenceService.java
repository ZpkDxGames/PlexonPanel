package io.github.zpkdxgames.plexonpanel.presence;

import com.google.gson.JsonObject;
import io.github.zpkdxgames.plexonpanel.config.PanelSettings;
import io.github.zpkdxgames.plexonpanel.control.JsonFields;
import io.github.zpkdxgames.plexonpanel.util.NamedThreadFactory;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Main-thread-safe facade around the thread-confined presence journal. Bukkit listeners only update
 * the bounded live-session map and enqueue plain records; all disk work stays on one worker.
 */
public final class PlayerPresenceService implements AutoCloseable {
  public enum PersistenceState {
    DISABLED,
    QUEUED,
    DEGRADED
  }

  public record CapturedEvent(PresenceRecord record, PersistenceState persistenceState) {}

  public record SnapshotMetadata(
      String sessionId, String sessionStartedAt, String firstSeenAt, String lastLoginAt) {}

  private record LiveSession(String sessionId, String startedAt, String name) {}

  private static final int WRITER_QUEUE_CAPACITY = 1024;
  private static final Set<String> QUERY_FIELDS =
      Set.of("query", "status", "from", "to", "cursor", "limit");

  private final Path pluginDataDirectory;
  private final PanelSettings.PlayerHistory settings;
  private final Clock clock;
  private final Logger logger;
  private final int writerQueueCapacity;
  private final ThreadPoolExecutor worker;
  private final Map<String, LiveSession> live = new java.util.concurrent.ConcurrentHashMap<>();
  private final Map<String, PlayerPresenceSummary> summaryCache =
      new java.util.concurrent.ConcurrentHashMap<>();
  private final CompletableFuture<Void> initialized = new CompletableFuture<>();
  private final AtomicBoolean started = new AtomicBoolean();
  private final AtomicBoolean closed = new AtomicBoolean();
  private final AtomicLong rejectedWrites = new AtomicLong();
  private final AtomicLong failedWrites = new AtomicLong();
  private final AtomicLong nextQueueWarning = new AtomicLong();
  private final AtomicReference<String> lastError = new AtomicReference<>("");
  private volatile PresenceJournal journal;

  public PlayerPresenceService(
      Path pluginDataDirectory, PanelSettings.PlayerHistory settings, Logger logger) {
    this(pluginDataDirectory, settings, logger, Clock.systemUTC());
  }

  PlayerPresenceService(
      Path pluginDataDirectory,
      PanelSettings.PlayerHistory settings,
      Logger logger,
      Clock clock) {
    this(pluginDataDirectory, settings, logger, clock, WRITER_QUEUE_CAPACITY);
  }

  PlayerPresenceService(
      Path pluginDataDirectory,
      PanelSettings.PlayerHistory settings,
      Logger logger,
      Clock clock,
      int writerQueueCapacity) {
    this.pluginDataDirectory = Objects.requireNonNull(pluginDataDirectory, "pluginDataDirectory");
    this.settings = Objects.requireNonNull(settings, "settings");
    this.logger = Objects.requireNonNull(logger, "logger");
    this.clock = Objects.requireNonNull(clock, "clock");
    if (writerQueueCapacity < 1 || writerQueueCapacity > WRITER_QUEUE_CAPACITY)
      throw new IllegalArgumentException("Invalid presence writer queue capacity");
    this.writerQueueCapacity = writerQueueCapacity;
    this.worker =
        new ThreadPoolExecutor(
            1,
            1,
            0,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(writerQueueCapacity),
            new NamedThreadFactory("plexonpanel-presence"),
            new ThreadPoolExecutor.AbortPolicy());
  }

  public void start(Collection<ObservedPlayer> currentlyOnline) {
    if (closed.get() || !started.compareAndSet(false, true)) return;
    Collection<ObservedPlayer> captured = List.copyOf(currentlyOnline);
    for (ObservedPlayer player : captured) {
      validateObserved(player);
      String sessionId = UUID.randomUUID().toString();
      live.put(player.uuid(), new LiveSession(sessionId, player.sessionStartedAt(), player.name()));
    }
    if (!settings.enabled()) {
      initialized.complete(null);
      return;
    }
    worker.execute(
        () -> {
          try {
            PresenceJournal next = new PresenceJournal(pluginDataDirectory, settings, clock);
            Map<String, PresenceJournal.OpenSession> recovered = next.initialize(captured);
            journal = next;
            summaryCache.clear();
            summaryCache.putAll(next.summaries());
            for (Map.Entry<String, PresenceJournal.OpenSession> entry : recovered.entrySet()) {
              live.computeIfPresent(
                  entry.getKey(),
                  (ignored, current) ->
                      new LiveSession(
                          entry.getValue().sessionId(),
                          entry.getValue().startedAt(),
                          entry.getValue().name()));
            }
            initialized.complete(null);
          } catch (Exception error) {
            recordFailure("Presence journal initialization failed", error);
            initialized.completeExceptionally(error);
          }
        });
  }

  public CapturedEvent joined(
      String uuid,
      String name,
      Instant observedAt,
      Instant firstSeenAt,
      Long totalPlayTimeMillis) {
    requireOpen();
    UUID.fromString(uuid);
    validateName(name);
    Objects.requireNonNull(observedAt, "observedAt");
    Objects.requireNonNull(firstSeenAt, "firstSeenAt");
    if (firstSeenAt.isAfter(observedAt.plusSeconds(5)))
      throw new IllegalArgumentException("Invalid first seen time");
    LiveSession session =
        new LiveSession(UUID.randomUUID().toString(), observedAt.toString(), name);
    if (live.putIfAbsent(uuid, session) != null) return null;
    PresenceRecord record =
        new PresenceRecord(
            UUID.randomUUID().toString(),
            session.sessionId(),
            uuid,
            name,
            PresenceRecord.State.JOINED,
            observedAt.toString(),
            session.startedAt(),
            null,
            null,
            PresenceTermination.OPEN);
    return new CapturedEvent(
        record, enqueue(record, totalPlayTimeMillis, firstSeenAt.toString()));
  }

  public CapturedEvent left(
      String uuid,
      String name,
      Instant observedAt,
      PresenceTermination termination,
      Long totalPlayTimeMillis) {
    requireOpen();
    validateName(name);
    Objects.requireNonNull(observedAt, "observedAt");
    if (termination != PresenceTermination.QUIT && termination != PresenceTermination.KICK)
      throw new IllegalArgumentException("A live close must be QUIT or KICK");
    LiveSession session = live.remove(UUID.fromString(uuid).toString());
    if (session == null) return null;
    Instant startedAt = Instant.parse(session.startedAt());
    Instant endedAt = observedAt.isBefore(startedAt) ? startedAt : observedAt;
    PresenceRecord record =
        new PresenceRecord(
            UUID.randomUUID().toString(),
            session.sessionId(),
            uuid,
            name,
            PresenceRecord.State.LEFT,
            endedAt.toString(),
            session.startedAt(),
            endedAt.toString(),
            endedAt.toEpochMilli() - startedAt.toEpochMilli(),
            termination);
    return new CapturedEvent(record, enqueue(record, totalPlayTimeMillis, session.startedAt()));
  }

  public SnapshotMetadata snapshotMetadata(String uuid) {
    LiveSession session = live.get(uuid);
    PlayerPresenceSummary summary = summaryCache.get(uuid);
    return new SnapshotMetadata(
        session == null ? null : session.sessionId(),
        session == null ? null : session.startedAt(),
        settings.enabled() && summary != null ? summary.firstSeenAt() : null,
        settings.enabled() && summary != null ? summary.lastLoginAt() : null);
  }

  public Map<String, Object> query(JsonObject parameters) throws Exception {
    Objects.requireNonNull(parameters, "parameters");
    if (!QUERY_FIELDS.containsAll(parameters.keySet()))
      throw new IllegalArgumentException("Unknown players.history.list parameter");
    String text = JsonFields.optional(parameters, "query", "", 64).strip();
    String statusText = JsonFields.optional(parameters, "status", "ALL", 16);
    PresenceJournal.Status status;
    try {
      status = PresenceJournal.Status.valueOf(statusText);
    } catch (IllegalArgumentException invalid) {
      throw new IllegalArgumentException("Invalid status", invalid);
    }
    Instant from = parseOptionalInstant(parameters, "from");
    Instant to = parseOptionalInstant(parameters, "to");
    if (from != null && to != null && to.isBefore(from))
      throw new IllegalArgumentException("Invalid time range");
    String cursor = JsonFields.optional(parameters, "cursor", "", 512);
    int limit =
        (int)
            JsonFields.integer(
                parameters,
                "limit",
                settings.defaultPageSize(),
                1,
                settings.maximumPageSize());
    if (!settings.enabled())
      return Map.of(
          "entries", List.of(),
          "nextCursor", "",
          "hasMore", false,
          "boundedWindow", false,
          "capturedAt", clock.instant().toString(),
          "historyEnabled", false);
    awaitInitialization();
    PresenceJournal.Page page =
        call(
            () ->
                requireJournal()
                    .query(
                        new PresenceJournal.Query(
                            text, status, from, to, cursor.isBlank() ? null : cursor, limit)));
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("entries", page.entries());
    result.put("nextCursor", page.nextCursor());
    result.put("hasMore", page.hasMore());
    result.put("boundedWindow", page.boundedWindow());
    result.put("capturedAt", page.capturedAt());
    result.put("historyEnabled", page.historyEnabled());
    return Collections.unmodifiableMap(result);
  }

  public Map<String, Object> diagnostics() {
    String state =
        !settings.enabled()
            ? "DISABLED"
            : closed.get()
                ? "CLOSED"
                : failedWrites.get() > 0 || rejectedWrites.get() > 0 || initialized.isCompletedExceptionally()
                    ? "DEGRADED"
                    : initialized.isDone() ? "READY" : "STARTING";
    Map<String, Object> result = new HashMap<>();
    result.put("enabled", settings.enabled());
    result.put("state", state);
    result.put("queueDepth", worker.getQueue().size());
    result.put("queueCapacity", writerQueueCapacity);
    result.put("rejectedWrites", rejectedWrites.get());
    result.put("failedWrites", failedWrites.get());
    result.put("playerSummaries", summaryCache.size());
    if (!lastError.get().isBlank()) result.put("lastError", lastError.get());
    return Map.copyOf(result);
  }

  public boolean enabled() {
    return settings.enabled();
  }

  private PersistenceState enqueue(
      PresenceRecord record, Long totalPlayTimeMillis, String firstSeenAt) {
    if (!settings.enabled()) return PersistenceState.DISABLED;
    try {
      worker.execute(
          () -> {
            try {
              awaitInitialization();
              PresenceJournal active = requireJournal();
              active.appendAndApply(record, totalPlayTimeMillis, firstSeenAt);
              PlayerPresenceSummary summary = active.summary(record.uuid());
              if (summary != null) summaryCache.put(record.uuid(), summary);
            } catch (Exception error) {
              recordFailure("Presence record could not be persisted", error);
            }
          });
      return PersistenceState.QUEUED;
    } catch (RejectedExecutionException busy) {
      rejectedWrites.incrementAndGet();
      lastError.set("Presence writer queue is full");
      long now = System.currentTimeMillis();
      long next = nextQueueWarning.get();
      if (now >= next && nextQueueWarning.compareAndSet(next, now + 60_000L))
        logger.warning("PlexonPanel presence writer queue is full; events are not being persisted");
      return PersistenceState.DEGRADED;
    }
  }

  private <T> T call(java.util.concurrent.Callable<T> operation) throws Exception {
    CompletableFuture<T> result = new CompletableFuture<>();
    try {
      worker.execute(
          () -> {
            try {
              result.complete(operation.call());
            } catch (Exception error) {
              result.completeExceptionally(error);
            }
          });
    } catch (RejectedExecutionException busy) {
      throw new SecurityException("BUSY");
    }
    try {
      return result.get(15, TimeUnit.SECONDS);
    } catch (ExecutionException error) {
      if (error.getCause() instanceof Exception cause) throw cause;
      throw error;
    } catch (TimeoutException timeout) {
      throw new IOException("Presence query exceeded its execution budget", timeout);
    }
  }

  private void awaitInitialization() throws Exception {
    try {
      initialized.get(15, TimeUnit.SECONDS);
    } catch (ExecutionException error) {
      if (error.getCause() instanceof Exception cause) throw cause;
      throw error;
    } catch (TimeoutException timeout) {
      throw new IOException("Presence journal is not ready", timeout);
    }
  }

  private PresenceJournal requireJournal() throws IOException {
    PresenceJournal value = journal;
    if (value == null) throw new IOException("Presence journal is unavailable");
    return value;
  }

  private static Instant parseOptionalInstant(JsonObject parameters, String key) {
    String value = JsonFields.optional(parameters, key, "", 40);
    if (value.isBlank()) return null;
    try {
      return Instant.parse(value);
    } catch (RuntimeException invalid) {
      throw new IllegalArgumentException("Invalid " + key, invalid);
    }
  }

  private static void validateObserved(ObservedPlayer player) {
    Objects.requireNonNull(player, "player");
    UUID.fromString(player.uuid());
    validateName(player.name());
    Instant started = Instant.parse(player.sessionStartedAt());
    Instant firstSeen = Instant.parse(player.firstSeenAt());
    if (firstSeen.isAfter(started.plusSeconds(5)))
      throw new IllegalArgumentException("Invalid first seen time");
    if (player.totalPlayTimeMillis() != null && player.totalPlayTimeMillis() < 0)
      throw new IllegalArgumentException("Invalid play time");
  }

  private static void validateName(String name) {
    if (name == null || name.isBlank() || name.length() > 64 || name.indexOf('\0') >= 0)
      throw new IllegalArgumentException("Invalid player name");
    for (int i = 0; i < name.length(); i++)
      if (Character.isISOControl(name.charAt(i)))
        throw new IllegalArgumentException("Invalid player name");
  }

  private void recordFailure(String message, Exception error) {
    failedWrites.incrementAndGet();
    String safe = error.getClass().getSimpleName() + ": " + String.valueOf(error.getMessage());
    lastError.set(safe.substring(0, Math.min(256, safe.length())));
    logger.log(Level.WARNING, message, error);
  }

  private void requireOpen() {
    if (closed.get()) throw new IllegalStateException("Presence service is closed");
  }

  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) return;
    worker.shutdown();
    try {
      if (!worker.awaitTermination(settings.shutdownFlushSeconds(), TimeUnit.SECONDS)) {
        int abandoned = worker.shutdownNow().size();
        if (abandoned > 0) {
          rejectedWrites.addAndGet(abandoned);
          logger.warning("PlexonPanel presence shutdown timed out; " + abandoned + " writes were not persisted");
        }
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      int abandoned = worker.shutdownNow().size();
      rejectedWrites.addAndGet(abandoned);
    }
    live.clear();
  }

}
