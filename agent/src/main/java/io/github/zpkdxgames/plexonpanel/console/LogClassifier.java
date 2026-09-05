package io.github.zpkdxgames.plexonpanel.console;

import io.github.zpkdxgames.plexonpanel.util.Hashing;
import java.util.Locale;
import java.util.regex.Pattern;

public final class LogClassifier {
  private static final Pattern UUID =
      Pattern.compile(
          "(?i)\\b[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\\b");
  private static final Pattern NUMBERS = Pattern.compile("(?<![A-Za-z])[-+]?\\d+(?:\\.\\d+)?");
  private static final Pattern LINE_NUMBER = Pattern.compile("\\.java:\\d+");
  private static final Pattern WHITESPACE = Pattern.compile("\\s+");

  public Classification classify(String line) {
    String upper = line.toUpperCase(Locale.ROOT);
    String level;
    if (upper.contains("[ERROR]")
        || upper.contains(" ERROR]:")
        || upper.contains("EXCEPTION")
        || upper.contains("CAUSED BY:")) {
      level = "ERROR";
    } else if (upper.contains("[WARN]") || upper.contains(" WARN]:") || upper.contains("WARNING")) {
      level = "WARN";
    } else if (upper.contains("[DEBUG]") || upper.contains(" DEBUG]:")) {
      level = "DEBUG";
    } else {
      level = "INFO";
    }

    String fingerprint = null;
    if ("ERROR".equals(level) || "WARN".equals(level)) {
      String normalized = UUID.matcher(line).replaceAll("<uuid>");
      normalized = LINE_NUMBER.matcher(normalized).replaceAll(".java:<line>");
      normalized = NUMBERS.matcher(normalized).replaceAll("<n>");
      normalized = WHITESPACE.matcher(normalized).replaceAll(" ").strip().toLowerCase(Locale.ROOT);
      fingerprint = Hashing.sha256Hex(normalized).substring(0, 24);
    }
    return new Classification(level, fingerprint);
  }

  public record Classification(String level, String fingerprint) {
    public boolean isProblem() {
      return "ERROR".equals(level) || "WARN".equals(level);
    }
  }
}
