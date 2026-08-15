package io.github.zpkdxgames.plexonpanel.model;

public record ConsoleLine(
    String capturedAt,
    String level,
    String content,
    String fingerprint,
    boolean truncated
) {
}
