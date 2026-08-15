package io.github.zpkdxgames.plexonpanel.audit;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.github.zpkdxgames.plexonpanel.config.PanelSettings;
import io.github.zpkdxgames.plexonpanel.model.AuditEntry;
import io.github.zpkdxgames.plexonpanel.util.NamedThreadFactory;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

public final class AuditService implements AutoCloseable {
    private static final String PREFIX = "audit-";
    private static final String SUFFIX = ".jsonl";

    private final JavaPlugin plugin;
    private final PanelSettings.Audit settings;
    private final Path auditDirectory;
    private final Clock clock;
    private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();
    private final ThreadPoolExecutor executor;
    private final AtomicBoolean closed = new AtomicBoolean();

    public AuditService(JavaPlugin plugin, PanelSettings.Audit settings, Path dataDirectory) {
        this(plugin, settings, dataDirectory, Clock.systemUTC());
    }

    AuditService(JavaPlugin plugin, PanelSettings.Audit settings, Path dataDirectory, Clock clock) {
        this.plugin = plugin;
        this.settings = settings;
        this.auditDirectory = dataDirectory.resolve("audit");
        this.clock = clock;
        this.executor = new ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(4096),
            new NamedThreadFactory("plexonpanel-audit"),
            new ThreadPoolExecutor.AbortPolicy()
        );
    }

    public void start() {
        if (!settings.localJsonlEnabled()) {
            return;
        }
        submit(this::prepareAndClean);
    }

    public void record(AuditEntry entry) {
        if (!settings.localJsonlEnabled() || closed.get()) {
            return;
        }
        submit(() -> append(entry));
    }

    private void submit(Runnable operation) {
        try {
            executor.execute(operation);
        } catch (RuntimeException rejected) {
            plugin.getLogger().log(Level.SEVERE, "PlexonPanel audit queue is full; an audit operation was rejected", rejected);
        }
    }

    private void prepareAndClean() {
        try {
            Files.createDirectories(auditDirectory);
            LocalDate oldest = LocalDate.now(clock).minusDays(settings.retentionDays() - 1L);
            try (var files = Files.list(auditDirectory)) {
                files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().startsWith(PREFIX))
                    .filter(path -> path.getFileName().toString().endsWith(SUFFIX))
                    .filter(path -> fileDate(path).isBefore(oldest))
                    .forEach(this::deleteExpired);
            }
        } catch (IOException error) {
            plugin.getLogger().log(Level.WARNING, "Unable to prepare PlexonPanel audit storage", error);
        }
    }

    private void append(AuditEntry entry) {
        try {
            Files.createDirectories(auditDirectory);
            LocalDate date = Instant.parse(entry.timestamp()).atZone(ZoneOffset.UTC).toLocalDate();
            Path file = auditDirectory.resolve(PREFIX + date + SUFFIX);
            Files.writeString(
                file,
                gson.toJson(entry) + System.lineSeparator(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.APPEND
            );
        } catch (Exception error) {
            plugin.getLogger().log(Level.SEVERE, "Unable to append PlexonPanel audit entry", error);
        }
    }

    private LocalDate fileDate(Path path) {
        String name = path.getFileName().toString();
        try {
            return LocalDate.parse(name.substring(PREFIX.length(), name.length() - SUFFIX.length()));
        } catch (RuntimeException ignored) {
            return LocalDate.MAX;
        }
    }

    private void deleteExpired(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException error) {
            plugin.getLogger().log(Level.WARNING, "Unable to remove expired audit file " + path.getFileName(), error);
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }
}
