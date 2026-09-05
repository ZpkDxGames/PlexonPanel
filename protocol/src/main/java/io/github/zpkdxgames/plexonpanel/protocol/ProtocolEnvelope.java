package io.github.zpkdxgames.plexonpanel.protocol;

public record ProtocolEnvelope(
    int protocolVersion,
    String type,
    String messageId,
    String serverId,
    String timestamp,
    String body,
    String signature) {}
