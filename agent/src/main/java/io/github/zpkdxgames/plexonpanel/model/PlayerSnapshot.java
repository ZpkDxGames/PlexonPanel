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
    String address,
    int food,
    long onlineDurationMillis,
    String sessionId,
    String sessionStartedAt,
    String firstSeenAt,
    String lastLoginAt,
    String skinTexture) {
  /** Compatibility constructor for producers without applied-skin metadata. */
  public PlayerSnapshot(
      String uuid, String name, String displayName, String world, String gameMode,
      int pingMillis, double health, double maximumHealth, int experienceLevel,
      boolean op, boolean whitelisted, Position position, String address, int food,
      long onlineDurationMillis, String sessionId, String sessionStartedAt,
      String firstSeenAt, String lastLoginAt) {
    this(uuid, name, displayName, world, gameMode, pingMillis, health, maximumHealth,
        experienceLevel, op, whitelisted, position, address, food, onlineDurationMillis,
        sessionId, sessionStartedAt, firstSeenAt, lastLoginAt, null);
  }
  public record Position(double x, double y, double z, float yaw, float pitch) {}
}
