package io.github.zpkdxgames.plexonpanel.model;

public record ChatRecord(
    String capturedAt,
    String messageId,
    String channel,
    String senderUuid,
    String senderName,
    String content,
    String source
) {
}
