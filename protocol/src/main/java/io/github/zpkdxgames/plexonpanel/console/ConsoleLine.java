package io.github.zpkdxgames.plexonpanel.console;

/** Protocol-compatible console line shared by Paper fallback and Host journal sources. */
public record ConsoleLine(
    String capturedAt,
    String level,
    String content,
    String fingerprint,
    boolean truncated,
    String source,
    String journalCursor,
    String invocationId,
    String service,
    String pid,
    String streamSession,
    long sourceSequence) {

  public ConsoleLine(
      String capturedAt, String level, String content, String fingerprint, boolean truncated) {
    this(
        capturedAt,
        level,
        content,
        fingerprint,
        truncated,
        "PAPER_LOG_FALLBACK",
        null,
        null,
        null,
        null,
        null,
        0L);
  }
}
