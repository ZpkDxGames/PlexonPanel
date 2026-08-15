package io.github.zpkdxgames.plexonpanel.model;

public record PlayerSnapshot(
    String uuid,
    String name,
    String displayName,
    String world,
    String gameMode,
    int pingMillis,
    double health,
    double maximumHealth,
    int experienceLevel,
    boolean op,
    boolean whitelisted,
    Position position,
    String address
) {
    public record Position(double x, double y, double z, float yaw, float pitch) {
    }
}
