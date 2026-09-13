package io.github.zpkdxgames.plexonpanel.host;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.zpkdxgames.plexonpanel.console.ConsoleClassifier;
import io.github.zpkdxgames.plexonpanel.console.ConsoleLine;
import io.github.zpkdxgames.plexonpanel.console.ConsoleRedactor;
import io.github.zpkdxgames.plexonpanel.protocol.MessagePriority;
import io.github.zpkdxgames.plexonpanel.protocol.SnapshotBatches;
import io.github.zpkdxgames.plexonpanel.util.NamedThreadFactory;
import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Always-on Host console producer. It follows one fixed systemd unit through journalctl without a
 * shell, keeps only bounded in-memory replay, and persists only the last journal cursor.
 */
public final class HostConsoleStreamService implements AutoCloseable {
  private static final long MAXIMUM_RESTART_BACKOFF_MILLIS = 30_000L;

  private final HostConfig config;
  private final HostConfig.ConsoleConfig settings;
  private final HostConnection connection;
  private final ConsoleClassifier classifier = new ConsoleClassifier();
  private final ConsoleRedactor redactor;
  private final ConsoleStateStore stateStore;
  private final ArrayBlockingQueue<ConsoleLine> pending;
  private final ArrayDeque<ConsoleLine> recent = new ArrayDeque<>();
  private final ExecutorService sourceExecutor =
      Executors.newSingleThreadExecutor(new NamedThreadFactory("plexonpanel-host-console-source"));
  private final ScheduledExecutorService batchExecutor =
      Executors.newSingleThreadScheduledExecutor(
          new NamedThreadFactory("plexonpanel-host-console-batch"));
  private final AtomicBoolean running = new AtomicBoolean();
  private final AtomicLong sourceSequence = new AtomicLong();
  private final AtomicLong captured = new AtomicLong();
  private final AtomicLong emitted = new AtomicLong();
  private final AtomicLong dropped = new AtomicLong();
  private final AtomicLong journalRestarts = new AtomicLong();
  private final String streamSession = UUID.randomUUID().toString();

  private volatile Process process;
  private volatile String lastCursor;
  private volatile String invocationId;
  private volatile String sourceState = "DISABLED";
  private volatile boolean healthy;
  private volatile boolean stateDirty;
  private volatile long lastStateWrite;
  private volatile long lastReportedDropped;
  private volatile long lastConsoleEventAt;

  public HostConsoleStreamService(HostConfig config, HostConnection connection) {
    this.config = config;
    this.settings = config.console();
    this.connection = connection;
    this.redactor = new ConsoleRedactor(settings.redactPatterns());
    this.pending = new ArrayBlockingQueue<>(settings.queueCapacity());
    this.stateStore =
        new ConsoleStateStore(Path.of(config.dataDirectory()).resolve("console-state.json"));
    ConsoleStateStore.State state = stateStore.load();
    this.lastCursor = state.cursor();
    this.invocationId = state.invocationId();
    this.sourceSequence.set(state.sourceSequence());
  }

  public void start() {
    if (!settings.enabled() || !running.compareAndSet(false, true)) return;
    sourceState = "STARTING";
    sourceExecutor.execute(this::sourceLoop);
    batchExecutor.scheduleWithFixedDelay(
        this::tickSafely,
        settings.batchIntervalMillis(),
        settings.batchIntervalMillis(),
        TimeUnit.MILLISECONDS);
  }

  private void sourceLoop() {
    String resumeCursor = lastCursor;
    long backoff = 1000L;
    while (running.get()) {
      boolean parsed = false;
      boolean cursorFailure = false;
      try {
        List<String> command = new ArrayList<>();
        command.add(settings.journalExecutable());
        command.add("--unit");
        command.add(config.serviceName());
        command.add("--output=json");
        command.add("--no-pager");
        command.add("--follow");
        if (resumeCursor != null && !resumeCursor.isBlank()) {
          command.add("--after-cursor=" + resumeCursor);
        } else {
          command.add("--lines=" + settings.initialReplayLines());
        }
        Process started = new ProcessBuilder(command).redirectErrorStream(true).start();
        process = started;
        setSourceState("FOLLOWING", true);
        try (BufferedReader reader = started.inputReader(StandardCharsets.UTF_8)) {
          String line;
          while (running.get() && (line = reader.readLine()) != null) {
            if (line.startsWith("{")) {
              if (acceptJournalJson(line)) {
                parsed = true;
                resumeCursor = lastCursor;
                backoff = 1000L;
              }
            } else {
              String lower = line.toLowerCase(java.util.Locale.ROOT);
              if (lower.contains("permission denied") || lower.contains("not permitted"))
                setSourceState("JOURNAL_PERMISSION_DENIED", false);
              if (lower.contains("cursor") && (lower.contains("failed") || lower.contains("invalid")))
                cursorFailure = true;
            }
          }
        }
        if (running.get()) {
          int exit = started.waitFor();
          process = null;
          journalRestarts.incrementAndGet();
          if (cursorFailure || !parsed && exit != 0 && resumeCursor != null) {
            resumeCursor = null;
            lastCursor = null;
            stateDirty = true;
            emitGap("Journal cursor could not be resumed; recovered with a bounded recent replay.");
            setSourceState("RECOVERING", false);
          } else if (!"JOURNAL_PERMISSION_DENIED".equals(sourceState)) {
            setSourceState("RESTARTING", false);
          }
        }
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        return;
      } catch (Exception error) {
        process = null;
        if (running.get()) {
          journalRestarts.incrementAndGet();
          setSourceState("JOURNAL_UNAVAILABLE", false);
        }
      }
      if (!running.get()) break;
      try {
        Thread.sleep(backoff);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        return;
      }
      backoff = Math.min(MAXIMUM_RESTART_BACKOFF_MILLIS, backoff * 2L);
    }
  }

  private boolean acceptJournalJson(String json) {
    try {
      JsonObject object = JsonParser.parseString(json).getAsJsonObject();
      String content = text(object, "MESSAGE");
      if (content == null || content.isBlank()) return false;
      String unit = text(object, "_SYSTEMD_UNIT");
      if (unit != null && !config.serviceName().equals(unit)) return false;
      String cursor = text(object, "__CURSOR");
      String nextInvocation = text(object, "_SYSTEMD_INVOCATION_ID");
      String pid = text(object, "_PID");
      Integer priority = integer(object, "PRIORITY");
      boolean truncated = content.getBytes(StandardCharsets.UTF_8).length > settings.maximumLineBytes();
      content = truncateUtf8(content, settings.maximumLineBytes());
      content = redactor.redact(content);
      ConsoleClassifier.Classification classification = classifier.classify(content, priority);
      long sequence = sourceSequence.incrementAndGet();
      ConsoleLine line =
          new ConsoleLine(
              journalInstant(object),
              classification.level(),
              content,
              classification.fingerprint(),
              truncated,
              "HOST_JOURNAL",
              cursor,
              nextInvocation,
              config.serviceName(),
              pid,
              streamSession,
              sequence);
      if (cursor != null) lastCursor = cursor;
      if (nextInvocation != null) invocationId = nextInvocation;
      stateDirty = true;
      captured.incrementAndGet();
      lastConsoleEventAt = System.currentTimeMillis();
      addRecent(line);
      enqueue(line);
      if (!healthy) setSourceState("FOLLOWING", true);
      return true;
    } catch (RuntimeException malformed) {
      return false;
    }
  }

  private void enqueue(ConsoleLine line) {
    if (pending.offer(line)) return;
    pending.poll();
    dropped.incrementAndGet();
    if (!pending.offer(line)) dropped.incrementAndGet();
  }

  private synchronized void addRecent(ConsoleLine line) {
    recent.addLast(line);
    while (recent.size() > settings.recentLines()) recent.removeFirst();
  }

  private void emitGap(String message) {
    long sequence = sourceSequence.incrementAndGet();
    ConsoleLine marker =
        new ConsoleLine(
            Instant.now().toString(),
            "WARN",
            message,
            null,
            false,
            "HOST_JOURNAL",
            null,
            invocationId,
            config.serviceName(),
            null,
            streamSession,
            sequence);
    stateDirty = true;
    addRecent(marker);
    enqueue(marker);
  }

  private void tickSafely() {
    try {
      flush();
      persistIfDue(false);
    } catch (Exception error) {
      // Console failures must never terminate the scheduler or the Host Companion.
    }
  }

  private void flush() {
    if (!settings.enabled() || !connection.authenticated()) return;
    List<ConsoleLine> lines = new ArrayList<>(settings.batchSize() + 1);
    long totalDropped = dropped.get();
    long unreported = totalDropped - lastReportedDropped;
    if (unreported > 0) {
      lines.add(
          new ConsoleLine(
              Instant.now().toString(),
              "WARN",
              unreported + " console lines were dropped while the Host console queue was saturated.",
              null,
              false,
              "HOST_JOURNAL",
              null,
              invocationId,
              config.serviceName(),
              null,
              streamSession,
              sourceSequence.incrementAndGet()));
      lastReportedDropped = totalDropped;
      stateDirty = true;
    }
    pending.drainTo(lines, settings.batchSize());
    if (lines.isEmpty()) return;
    for (Map<String, Object> batch : SnapshotBatches.split("lines", lines, 100)) {
      if (connection.send("console.lines", batch, MessagePriority.EVENT)) {
        Object value = batch.get("lines");
        if (value instanceof List<?> sent) emitted.addAndGet(sent.size());
      }
    }
  }

  public void sendRecentSnapshot() {
    if (!settings.enabled() || !connection.authenticated()) return;
    List<ConsoleLine> copy;
    synchronized (this) {
      copy = List.copyOf(recent);
    }
    for (Map<String, Object> batch : SnapshotBatches.split("lines", copy, 100))
      connection.send("console.lines", batch, MessagePriority.EVENT);
    announceStatus();
  }

  public void announceStatus() {
    if (!connection.authenticated()) return;
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("available", settings.enabled() && healthy);
    body.put("configured", settings.enabled());
    body.put("source", "HOST_JOURNAL");
    body.put("state", sourceState);
    body.put("service", config.serviceName());
    connection.send("console.source", body, MessagePriority.CRITICAL);
  }

  private void setSourceState(String state, boolean available) {
    boolean changed = !state.equals(sourceState) || healthy != available;
    sourceState = state;
    healthy = available;
    if (changed) announceStatus();
  }

  private void persistIfDue(boolean force) {
    if (!stateDirty) return;
    long now = System.currentTimeMillis();
    if (!force && now - lastStateWrite < settings.cursorPersistenceMillis()) return;
    try {
      stateStore.save(
          new ConsoleStateStore.State(
              ConsoleStateStore.FORMAT_VERSION,
              lastCursor,
              invocationId,
              sourceSequence.get()));
      lastStateWrite = now;
      stateDirty = false;
    } catch (Exception ignored) {
      // Cursor persistence failure degrades replay only; console capture continues.
    }
  }

  public Map<String, Object> diagnostics() {
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("consoleEnabled", settings.enabled());
    values.put("journalExecutable", settings.journalExecutable());
    values.put("journalPermissionState", sourceState);
    values.put("journalProcessAlive", process != null && process.isAlive());
    values.put("serviceUnit", config.serviceName());
    values.put("currentInvocationId", invocationId == null ? "" : invocationId);
    values.put("lastCursor", lastCursor == null ? "" : lastCursor);
    values.put("lastConsoleEventMillis", lastConsoleEventAt);
    values.put("consoleQueueDepth", pending.size());
    values.put("consoleQueueCapacity", settings.queueCapacity());
    values.put("capturedLines", captured.get());
    values.put("emittedLines", emitted.get());
    values.put("droppedLines", dropped.get());
    values.put("journalProcessRestarts", journalRestarts.get());
    values.put("sourceState", sourceState);
    return Map.copyOf(values);
  }

  private static String journalInstant(JsonObject object) {
    String micros = text(object, "__REALTIME_TIMESTAMP");
    if (micros != null) {
      try {
        long value = Long.parseLong(micros);
        return Instant.ofEpochSecond(value / 1_000_000L, value % 1_000_000L * 1000L).toString();
      } catch (RuntimeException ignored) {
      }
    }
    return Instant.now().toString();
  }

  private static String text(JsonObject object, String key) {
    JsonElement value = object.get(key);
    if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) return null;
    try {
      return value.getAsString();
    } catch (RuntimeException ignored) {
      return null;
    }
  }

  private static Integer integer(JsonObject object, String key) {
    String value = text(object, key);
    if (value == null) return null;
    try {
      int parsed = Integer.parseInt(value);
      return parsed >= 0 && parsed <= 7 ? parsed : null;
    } catch (NumberFormatException ignored) {
      return null;
    }
  }

  private static String truncateUtf8(String value, int maximumBytes) {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    if (bytes.length <= maximumBytes) return value;
    int length = maximumBytes;
    while (length > 0 && (bytes[length] & 0xC0) == 0x80) length--;
    return new String(bytes, 0, Math.max(0, length), StandardCharsets.UTF_8) + "…";
  }

  @Override
  public void close() {
    if (!running.compareAndSet(true, false)) return;
    healthy = false;
    sourceState = "STOPPED";
    Process current = process;
    if (current != null) current.destroyForcibly();
    sourceExecutor.shutdownNow();
    batchExecutor.shutdownNow();
    persistIfDue(true);
    pending.clear();
  }
}
