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
    double maximumSampleTickMillis,
    long currentTick,
    long sourceIntervalMillis,
    double msptMin,
    double msptP95,
    Double msptP99,
    double msptMax,
    int tickSampleCount,
    Integer loadedChunks,
    Integer entities) {
  public ServerSnapshot(
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
      double maximumSampleTickMillis,
      long currentTick,
      long sourceIntervalMillis) {
    this(
        capturedAt,
        serverName,
        serverVersion,
        minecraftVersion,
        onlinePlayers,
        maximumPlayers,
        tps,
        averageTickMillis,
        minimumSampleTickMillis,
        p95TickMillis,
        maximumSampleTickMillis,
        currentTick,
        sourceIntervalMillis,
        minimumSampleTickMillis,
        p95TickMillis,
        null,
        maximumSampleTickMillis,
        0,
        null,
        null);
  }
}
