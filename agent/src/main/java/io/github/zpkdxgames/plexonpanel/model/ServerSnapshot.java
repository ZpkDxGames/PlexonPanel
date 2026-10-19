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
    double minimumSampleTickMillis,
    double p95TickMillis,
    double p99TickMillis,
    double maximumSampleTickMillis,
    long currentTick,
    long sourceIntervalMillis,
    Boolean onlineMode) {
  /** Compatibility constructor for older snapshot producers. */
  public ServerSnapshot(
      String capturedAt, String serverName, String serverVersion, String minecraftVersion,
      int onlinePlayers, int maximumPlayers, List<Double> tps, double averageTickMillis,
      double minimumSampleTickMillis, double p95TickMillis, double p99TickMillis,
      double maximumSampleTickMillis, long currentTick, long sourceIntervalMillis) {
    this(capturedAt, serverName, serverVersion, minecraftVersion, onlinePlayers,
        maximumPlayers, tps, averageTickMillis, minimumSampleTickMillis, p95TickMillis,
        p99TickMillis, maximumSampleTickMillis, currentTick, sourceIntervalMillis, null);
  }
}
