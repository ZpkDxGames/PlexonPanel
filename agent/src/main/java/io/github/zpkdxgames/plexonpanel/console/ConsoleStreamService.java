package io.github.zpkdxgames.plexonpanel.console;

import io.github.zpkdxgames.plexonpanel.config.PanelSettings;
import io.github.zpkdxgames.plexonpanel.protocol.MessagePriority;
import io.github.zpkdxgames.plexonpanel.protocol.MessageSink;
import io.github.zpkdxgames.plexonpanel.util.BoundedRingBuffer;
import io.github.zpkdxgames.plexonpanel.util.NamedThreadFactory;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.bukkit.plugin.java.JavaPlugin;

/** Paper-side console fallback. Host journald suppresses output dynamically when healthy. */
public final class ConsoleStreamService implements AutoCloseable {
  private final PanelSettings.Console settings;
  private final MessageSink sink;
  private final BoundedRingBuffer<ConsoleLine> pending;
  private final BoundedRingBuffer<ConsoleLine> recent;
  private final ConsoleTailer tailer;
  private final ScheduledExecutorService batchExecutor;
  private final AtomicBoolean hostAuthority = new AtomicBoolean();
  private final AtomicLong authorityTransitions = new AtomicLong();
  private volatile Instant lastAuthorityTransition = Instant.EPOCH;

  public ConsoleStreamService(
      JavaPlugin plugin, PanelSettings.Console settings, MessageSink sink, Path serverRoot) {
    this.settings = settings;
    this.sink = sink;
    this.pending = new BoundedRingBuffer<>(settings.ringBufferLines());
    this.recent = new BoundedRingBuffer<>(settings.ringBufferLines());
    this.tailer =
        new ConsoleTailer(
            serverRoot.resolve("logs").resolve("latest.log"),
            settings.pollIntervalMillis(),
            settings.maximumLineBytes(),
            settings.streamEnabled(),
            settings.errorsEnabled(),
            new ConsoleRedactor(settings.redactPatterns()),
            this::accept,
            plugin.getLogger());
    this.batchExecutor =
        Executors.newSingleThreadScheduledExecutor(
            new NamedThreadFactory("plexonpanel-console-batch"));
  }

  public void start() {
    if (!settings.streamEnabled() && !settings.errorsEnabled()) return;
    tailer.start();
    batchExecutor.scheduleWithFixedDelay(
        this::flush,
        settings.batchIntervalMillis(),
        settings.batchIntervalMillis(),
        TimeUnit.MILLISECONDS);
  }

  private void accept(ConsoleLine line) {
    if (hostAuthority.get()) return;
    recent.add(line);
    pending.add(line);
  }

  private void flush() {
    if (hostAuthority.get()) {
      pending.clear();
      return;
    }
    List<ConsoleLine> lines = pending.drain(settings.batchSize());
    if (lines.isEmpty()) return;
    for (var batch :
        io.github.zpkdxgames.plexonpanel.protocol.SnapshotBatches.split("lines", lines, 100))
      sink.send("console.lines", batch, MessagePriority.EVENT);
  }

  public void setHostAuthority(boolean authoritative) {
    boolean previous = hostAuthority.getAndSet(authoritative);
    tailer.setOutputEnabled(!authoritative);
    if (previous == authoritative) return;
    authorityTransitions.incrementAndGet();
    lastAuthorityTransition = Instant.now();
    if (authoritative) pending.clear();
  }

  public boolean fallbackActive() {
    return !hostAuthority.get() && (settings.streamEnabled() || settings.errorsEnabled());
  }

  public List<ConsoleLine> recentLines() {
    return recent.snapshot();
  }

  public void sendRecentSnapshot() {
    if (hostAuthority.get()) return;
    List<ConsoleLine> lines = recent.snapshot();
    int first = Math.max(0, lines.size() - 100);
    for (var batch :
        io.github.zpkdxgames.plexonpanel.protocol.SnapshotBatches.split(
            "lines", lines.subList(first, lines.size()), 100))
      sink.send("console.lines", batch, MessagePriority.EVENT);
  }

  public Map<String, Object> diagnostics() {
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("consoleFallbackConfigured", settings.streamEnabled() || settings.errorsEnabled());
    values.put("currentConsoleAuthority", hostAuthority.get() ? "HOST" : "PAPER_FALLBACK");
    values.put("paperFallbackActive", fallbackActive());
    values.put("fallbackRecentBufferSize", recent.snapshot().size());
    values.put("authorityTransitions", authorityTransitions.get());
    values.put("hostAuthorityLastTransition", lastAuthorityTransition.toString());
    return Map.copyOf(values);
  }

  @Override
  public void close() {
    tailer.close();
    batchExecutor.shutdownNow();
    pending.clear();
  }
}
