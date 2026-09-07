package io.github.zpkdxgames.plexonpanel.presence;

/** Immutable Bukkit values captured on Paper's main thread. */
public record ObservedPlayer(
    String uuid,
    String name,
    String sessionStartedAt,
    String firstSeenAt,
    Long totalPlayTimeMillis) {}
