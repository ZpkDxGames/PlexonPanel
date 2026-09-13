package io.github.zpkdxgames.plexonpanel.console;

/** Backward-compatible Paper adapter over the shared Protocol 3 classifier. */
public final class LogClassifier {
  private final ConsoleClassifier delegate = new ConsoleClassifier();

  public Classification classify(String line) {
    ConsoleClassifier.Classification value = delegate.classify(line);
    return new Classification(value.level(), value.fingerprint());
  }

  public record Classification(String level, String fingerprint) {
    public boolean isProblem() {
      return "ERROR".equals(level) || "WARN".equals(level);
    }
  }
}
