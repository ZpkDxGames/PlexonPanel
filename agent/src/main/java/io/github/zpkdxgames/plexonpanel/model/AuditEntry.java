package io.github.zpkdxgames.plexonpanel.model;

public record AuditEntry(
    String timestamp,
    String requestId,
    String actorId,
    String actorDisplayName,
    String action,
    String target,
    boolean allowed,
    boolean success,
    String resultCode) {}
