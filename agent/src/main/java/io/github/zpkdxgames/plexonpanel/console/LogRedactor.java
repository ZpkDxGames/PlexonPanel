package io.github.zpkdxgames.plexonpanel.console;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public final class LogRedactor {
  private final List<Pattern> patterns;

  public LogRedactor(List<String> expressions) {
    List<Pattern> compiled = new ArrayList<>();
    for (String expression : expressions) {
      try {
        compiled.add(Pattern.compile(expression));
      } catch (PatternSyntaxException error) {
        throw new IllegalArgumentException(
            "Invalid console redaction pattern: " + expression, error);
      }
    }
    this.patterns = List.copyOf(compiled);
  }

  public String redact(String input) {
    String result = input;
    for (Pattern pattern : patterns) {
      result = pattern.matcher(result).replaceAll("<redacted>");
    }
    return result;
  }
}
