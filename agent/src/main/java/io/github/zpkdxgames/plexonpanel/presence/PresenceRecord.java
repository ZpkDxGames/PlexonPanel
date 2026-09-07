package io.github.zpkdxgames.plexonpanel.presence;

/**
 * A bounded, plain-data presence observation. All timestamps are UTC ISO-8601 instants. A null
 * session end or duration is intentionally unknown and must never be guessed by a consumer.
 */
public record PresenceRecord(
    String eventId,
    String sessionId,
    String uuid,
    String name,
    State state,
    String observedAt,
    String sessionStartedAt,
    String sessionEndedAt,
    Long sessionDurationMillis,
    PresenceTermination termination) {

  public enum State {
    JOINED,
    LEFT
  }
}
