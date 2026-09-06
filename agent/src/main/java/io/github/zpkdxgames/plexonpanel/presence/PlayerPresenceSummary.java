package io.github.zpkdxgames.plexonpanel.presence;

/** Paper-owned bounded summary index. UUID, rather than the mutable name, is the identity. */
public record PlayerPresenceSummary(
    String uuid,
    String name,
    String firstSeenAt,
    String lastLoginAt,
    String lastLogoutAt,
    String currentSessionStartedAt,
    Long lastSessionDurationMillis,
    Long totalPlayTimeMillis,
    boolean currentlyOnline,
    PresenceTermination lastTermination,
    String currentSessionId,
    String lastDefinitelyObservedAt) {}
