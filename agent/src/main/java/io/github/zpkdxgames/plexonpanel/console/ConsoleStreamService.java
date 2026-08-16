package io.github.zpkdxgames.plexonpanel.console;

import io.github.zpkdxgames.plexonpanel.config.PanelSettings;
import io.github.zpkdxgames.plexonpanel.model.ConsoleLine;
import io.github.zpkdxgames.plexonpanel.protocol.MessagePriority;
import io.github.zpkdxgames.plexonpanel.protocol.MessageSink;
import io.github.zpkdxgames.plexonpanel.util.BoundedRingBuffer;
import io.github.zpkdxgames.plexonpanel.util.NamedThreadFactory;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class ConsoleStreamService implements AutoCloseable {
    private final PanelSettings.Console settings;
    private final MessageSink sink;
    private final BoundedRingBuffer<ConsoleLine> pending;
    private final BoundedRingBuffer<ConsoleLine> recent;
    private final ConsoleTailer tailer;
    private final ScheduledExecutorService batchExecutor;

    public ConsoleStreamService(JavaPlugin plugin, PanelSettings.Console settings, MessageSink sink, Path serverRoot) {
        this.settings = settings;
        this.sink = sink;
        this.pending = new BoundedRingBuffer<>(settings.ringBufferLines());
        this.recent = new BoundedRingBuffer<>(settings.ringBufferLines());
        this.tailer = new ConsoleTailer(
            serverRoot.resolve("logs").resolve("latest.log"),
            settings.pollIntervalMillis(),
            settings.maximumLineBytes(),
            settings.streamEnabled(),
            settings.errorsEnabled(),
            new LogRedactor(settings.redactPatterns()),
            this::accept,
            plugin.getLogger()
        );
        this.batchExecutor = Executors.newSingleThreadScheduledExecutor(new NamedThreadFactory("plexonpanel-console-batch"));
    }

    public void start() {
        if (!settings.streamEnabled() && !settings.errorsEnabled()) {
            return;
        }
        tailer.start();
        batchExecutor.scheduleWithFixedDelay(this::flush,
            settings.batchIntervalMillis(), settings.batchIntervalMillis(), TimeUnit.MILLISECONDS);
    }

    private void accept(ConsoleLine line) {
        recent.add(line);
        pending.add(line);
    }

    private void flush() {
        List<ConsoleLine> lines = pending.drain(settings.batchSize());
        if (lines.isEmpty()) {
            return;
        }
        boolean sent = sink.send("console.lines", Map.of(
            "capturedAt", Instant.now().toString(),
            "lines", lines
        ), MessagePriority.EVENT);
        if (!sent) {
            // Console history is deliberately not queued while offline. This avoids stale,
            // sensitive data accumulating indefinitely on busy servers.
        }
    }

    public List<ConsoleLine> recentLines() {
        return recent.snapshot();
    }

    public void sendRecentSnapshot() {
        List<ConsoleLine> lines = recent.snapshot();
        int first = Math.max(0, lines.size() - 100);
        for (int start = first; start < lines.size(); start += 25) {
            int end = Math.min(lines.size(), start + 25);
            sink.send("console.lines", Map.of(
                "capturedAt", Instant.now().toString(),
                "lines", List.copyOf(lines.subList(start, end))
            ), MessagePriority.EVENT);
        }
    }

    @Override
    public void close() {
        tailer.close();
        batchExecutor.shutdownNow();
        pending.clear();
    }
}
