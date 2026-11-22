package io.github.zpkdxgames.plexonpanel.bedrock.operations;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Private, generation-bound roster evidence. Name-only lists cannot establish an XUID. */
public final class BedrockRoster {
    private static final Duration MAX_AGE = Duration.ofSeconds(30);
    private static final int MAX_PLAYERS = 256;

    public record Player(String gamertag, String xuid) {
        public Player {
            if (!validGamertag(gamertag) || xuid != null && !validXuid(xuid)) {
                throw new IllegalArgumentException("Invalid Bedrock player identity");
            }
        }
    }

    private BedrockContainerControl.Container generation;
    private Instant observedAt;
    private List<Player> players;

    /**
     * Store a complete, positively observed list for one launch. The console transport owns response
     * provenance; individual connect/disconnect log lines cannot establish a complete roster.
     */
    public synchronized void reconcile(BedrockContainerControl.Container source, Instant capturedAt,
                                       List<Player> roster) {
        Objects.requireNonNull(source);
        Objects.requireNonNull(capturedAt);
        Objects.requireNonNull(roster);
        if (source.phase() != BedrockContainerControl.Phase.RUNNING || roster.size() > MAX_PLAYERS
                || capturedAt.isBefore(source.startedAt())) {
            throw new IllegalArgumentException("Invalid Bedrock roster evidence");
        }
        Map<String, Player> names = new HashMap<>();
        Map<String, Player> xuids = new HashMap<>();
        for (Player player : roster) {
            if (names.putIfAbsent(player.gamertag().toLowerCase(java.util.Locale.ROOT), player) != null
                    || player.xuid() != null && xuids.putIfAbsent(player.xuid(), player) != null) {
                throw new IllegalArgumentException("Ambiguous Bedrock roster");
            }
        }
        if (generation != null && (source.startedAt().isBefore(generation.startedAt())
                || generation.sameLaunch(source) && !capturedAt.isAfter(observedAt))) {
            throw new IllegalArgumentException("Outdated Bedrock roster evidence");
        }
        generation = source;
        observedAt = capturedAt;
        players = List.copyOf(roster);
    }

    public synchronized Optional<List<Player>> current(BedrockContainerControl.Container current, Instant now) {
        if (generation == null || current.phase() != BedrockContainerControl.Phase.RUNNING
                || !generation.sameLaunch(current) || now.isBefore(observedAt)
                || !now.isBefore(observedAt.plus(MAX_AGE))) {
            return Optional.empty();
        }
        return Optional.of(players);
    }

    public synchronized void disconnected() {
        generation = null;
        observedAt = null;
        players = null;
    }

    static boolean validGamertag(String name) {
        return name != null && name.matches("[A-Za-z0-9](?:[A-Za-z0-9 ]{0,13}[A-Za-z0-9])?");
    }

    static boolean validXuid(String xuid) {
        if (!xuid.matches("[1-9][0-9]{0,19}")) {
            return false;
        }
        try {
            Long.parseUnsignedLong(xuid);
            return true;
        } catch (NumberFormatException outOfRange) {
            return false;
        }
    }
}
