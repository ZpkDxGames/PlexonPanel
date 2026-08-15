package io.github.zpkdxgames.plexonpanel.identity;

import com.google.gson.Gson;
import io.github.zpkdxgames.plexonpanel.util.AtomicFiles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

public final class PairingState {
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

    public void offerCode(String value, Instant expiresAt) {
        code = new PairingCode(value, expiresAt);
    }

    public Optional<PairingCode> activeCode() {
        PairingCode current = code;
        if (current == null || !current.expiresAt().isAfter(clock.instant())) {
            code = null;
            return Optional.empty();
        }
        return Optional.of(current);
    }

    public record PairingCode(String value, Instant expiresAt) {
    }

    private void load() throws IOException {
        if (statePath == null || !Files.exists(statePath)) {
            return;
        }
        PersistedState state = gson.fromJson(Files.readString(statePath, StandardCharsets.UTF_8), PersistedState.class);
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
        AtomicFiles.writeUtf8(statePath, gson.toJson(new PersistedState(true)) + System.lineSeparator());
    }

    private record PersistedState(boolean paired) {
    }
}
