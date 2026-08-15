package io.github.zpkdxgames.plexonpanel.model;

import java.util.List;

public record ServerSnapshot(
    String capturedAt,
    String serverName,
    String serverVersion,
    String minecraftVersion,
    int onlinePlayers,
    int maximumPlayers,
    List<Double> tps,
    double averageTickMillis,
    double p95TickMillis,
    double maximumSampleTickMillis,
    long currentTick
) {
}
