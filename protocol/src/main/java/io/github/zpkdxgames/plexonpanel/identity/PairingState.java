package io.github.zpkdxgames.plexonpanel.identity;

import com.google.gson.Gson;
import io.github.zpkdxgames.plexonpanel.util.AtomicFiles;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

public final class PairingState {
  private static final Pattern PAIRING_CODE = Pattern.compile("\\d{6}");

  private final Clock clock;
  private final Path statePath;
  private final Gson gson = new Gson();
  private volatile boolean paired;
  private volatile PairingCode code;

  public PairingState() {
    this(null, Clock.systemUTC());
  }

  PairingState(Clock clock) {
    this(null, clock);
  }

  public PairingState(Path dataDirectory) throws IOException {
    this(dataDirectory.resolve("identity").resolve("pairing.json"), Clock.systemUTC());
    load();
  }

  PairingState(Path statePath, Clock clock) {
    this.statePath = statePath;
    this.clock = clock;
  }

  public boolean isPaired() {
    return paired;
  }

  public synchronized void markPaired() throws IOException {
    paired = true;
    code = null;
    persist();
  }

  public synchronized void clear() throws IOException {
    paired = false;
    code = null;
    if (statePath != null) {
      Files.deleteIfExists(statePath);
    }
  }

  public synchronized void beginCode(String requestId, String value, Instant expiresAt) {
    Objects.requireNonNull(requestId, "requestId");
    Objects.requireNonNull(value, "value");
    Objects.requireNonNull(expiresAt, "expiresAt");
    if (requestId.isBlank()
        || !PAIRING_CODE.matcher(value).matches()
        || !expiresAt.isAfter(clock.instant())) {
      throw new IllegalArgumentException("Invalid pairing code registration");
    }
    code = new PairingCode(requestId, value, expiresAt, null, false);
  }

  public synchronized void markCodeRegistered(
      String requestId, String challengeId, Instant gatewayExpiresAt) {
    Objects.requireNonNull(requestId, "requestId");
    Objects.requireNonNull(challengeId, "challengeId");
    Objects.requireNonNull(gatewayExpiresAt, "gatewayExpiresAt");
    PairingCode current = code;
    if (current == null || !current.requestId().equals(requestId)) {
      throw new IllegalStateException("Pairing registration does not match the active request");
    }
    if (challengeId.isBlank() || !gatewayExpiresAt.isAfter(clock.instant())) {
      throw new IllegalArgumentException("Relay returned an invalid pairing registration");
    }
    Instant effectiveExpiry =
        gatewayExpiresAt.isBefore(current.expiresAt()) ? gatewayExpiresAt : current.expiresAt();
    code =
        new PairingCode(current.requestId(), current.value(), effectiveExpiry, challengeId, true);
  }

  public synchronized void rejectCode(String requestId) {
    PairingCode current = code;
    if (current != null && current.requestId().equals(requestId)) {
      code = null;
    }
  }

  public Optional<PairingCode> activeCode() {
    PairingCode current = code;
    if (current == null || !current.expiresAt().isAfter(clock.instant())) {
      code = null;
      return Optional.empty();
    }
    return current.registered() ? Optional.of(current) : Optional.empty();
  }

  public boolean hasPendingCode() {
    PairingCode current = code;
    return current != null && current.expiresAt().isAfter(clock.instant()) && !current.registered();
  }

  public record PairingCode(
      String requestId, String value, Instant expiresAt, String challengeId, boolean registered) {}

  private void load() throws IOException {
    if (statePath == null || !Files.exists(statePath)) {
      return;
    }
    PersistedState state =
        gson.fromJson(Files.readString(statePath, StandardCharsets.UTF_8), PersistedState.class);
    if (state == null || !state.paired()) {
      Files.deleteIfExists(statePath);
      return;
    }
    paired = true;
  }

  private void persist() throws IOException {
    if (statePath == null) {
      return;
    }
    AtomicFiles.writeUtf8(
        statePath, gson.toJson(new PersistedState(true)) + System.lineSeparator());
  }

  private record PersistedState(boolean paired) {}
}
