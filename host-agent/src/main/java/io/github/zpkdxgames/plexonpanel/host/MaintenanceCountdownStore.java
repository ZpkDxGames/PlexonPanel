package io.github.zpkdxgames.plexonpanel.host;

import com.google.gson.*;
import io.github.zpkdxgames.plexonpanel.util.AtomicFiles;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Durable companion journal for the manual full-backup warning countdown. */
public final class MaintenanceCountdownStore {
  public record State(
      String jobId,
      String startedAt,
      List<Integer> handledSeconds,
      List<Integer> missedSeconds,
      int hostRestartCount,
      String lastRecoveryAt,
      String updatedAt) {}

  private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
  private static final Set<Integer> ALLOWED = Set.of(1800, 900, 60, 30, 15, 5);
  private final Path file;

  public MaintenanceCountdownStore(Path dataDirectory) throws IOException {
    Path directory = dataDirectory.resolve("maintenance").toAbsolutePath().normalize();
    Files.createDirectories(directory);
    this.file = directory.resolve("countdown.json");
  }

  public synchronized State start(String jobId, Instant startedAt) throws IOException {
    UUID.fromString(jobId);
    Objects.requireNonNull(startedAt, "startedAt");
    State state =
        new State(
            jobId,
            startedAt.toString(),
            List.of(),
            List.of(),
            0,
            "",
            Instant.now().toString());
    write(state);
    return state;
  }

  public synchronized State load(String jobId) throws IOException {
    UUID.fromString(jobId);
    if (!Files.exists(file)) return null;
    if (Files.isSymbolicLink(file)
        || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
        || Files.size(file) > 32_768)
      throw new IOException("Invalid maintenance countdown journal");
    State raw = GSON.fromJson(Files.readString(file), State.class);
    if (raw == null || !jobId.equals(raw.jobId())) return null;
    Instant.parse(raw.startedAt());
    validateBoundaries(raw.handledSeconds());
    validateBoundaries(raw.missedSeconds());
    if (raw.hostRestartCount() < 0 || raw.hostRestartCount() > 100)
      throw new IOException("Invalid countdown recovery count");
    return normalize(raw);
  }

  public synchronized State markHandled(State state, int seconds) throws IOException {
    requireAllowed(seconds);
    LinkedHashSet<Integer> handled = new LinkedHashSet<>(state.handledSeconds());
    handled.add(seconds);
    State next =
        new State(
            state.jobId(),
            state.startedAt(),
            List.copyOf(handled),
            state.missedSeconds(),
            state.hostRestartCount(),
            state.lastRecoveryAt(),
            Instant.now().toString());
    write(next);
    return next;
  }

  public synchronized State recordRecovery(State state, Collection<Integer> newlyMissed)
      throws IOException {
    LinkedHashSet<Integer> missed = new LinkedHashSet<>(state.missedSeconds());
    LinkedHashSet<Integer> handled = new LinkedHashSet<>(state.handledSeconds());
    for (int seconds : newlyMissed) {
      requireAllowed(seconds);
      missed.add(seconds);
      handled.add(seconds);
    }
    String now = Instant.now().toString();
    State next =
        new State(
            state.jobId(),
            state.startedAt(),
            List.copyOf(handled),
            List.copyOf(missed),
            Math.min(100, state.hostRestartCount() + 1),
            now,
            now);
    write(next);
    return next;
  }

  public synchronized void clear(String jobId) throws IOException {
    State current = load(jobId);
    if (current != null) Files.deleteIfExists(file);
  }

  private void write(State state) throws IOException {
    AtomicFiles.writeUtf8(file, GSON.toJson(state));
  }

  private static State normalize(State state) {
    List<Integer> handled = uniqueOrdered(state.handledSeconds());
    List<Integer> missed = uniqueOrdered(state.missedSeconds());
    return new State(
        state.jobId(),
        state.startedAt(),
        handled,
        missed,
        state.hostRestartCount(),
        safe(state.lastRecoveryAt()),
        safe(state.updatedAt()));
  }

  private static List<Integer> uniqueOrdered(List<Integer> values) {
    if (values == null) return List.of();
    LinkedHashSet<Integer> set = new LinkedHashSet<>(values);
    return List.copyOf(set);
  }

  private static void validateBoundaries(List<Integer> values) throws IOException {
    if (values == null || values.size() > ALLOWED.size()) throw new IOException("Invalid countdown boundaries");
    for (Integer value : values)
      if (value == null || !ALLOWED.contains(value)) throw new IOException("Invalid countdown boundary");
  }

  private static void requireAllowed(int seconds) {
    if (!ALLOWED.contains(seconds)) throw new IllegalArgumentException("Unsupported countdown boundary");
  }

  private static String safe(String value) {
    return value == null ? "" : value;
  }
}
