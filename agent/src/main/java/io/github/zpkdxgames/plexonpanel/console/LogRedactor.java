package io.github.zpkdxgames.plexonpanel.console;

import java.util.List;

/** Backward-compatible Paper adapter over the shared Protocol 3 redactor. */
public final class LogRedactor {
  private final ConsoleRedactor delegate;

  public LogRedactor(List<String> expressions) {
    delegate = new ConsoleRedactor(expressions);
  }

  public String redact(String input) {
    return delegate.redact(input);
  }
}
