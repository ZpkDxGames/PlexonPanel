package io.github.zpkdxgames.plexonpanel.console;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Redacts common credential shapes plus locally configured patterns before relay transport. */
public final class ConsoleRedactor {
  private static final Pattern BEARER =
      Pattern.compile("(?i)(authorization\\s*[:=]\\s*bearer\\s+)[^\\s,;]+", Pattern.UNICODE_CASE);
  private static final Pattern ASSIGNMENT =
      Pattern.compile(
          "(?i)((?:password|passwd|token|secret|api[_-]?key|access[_-]?key)\\s*[:=]\\s*)[^\\s,;]+",
          Pattern.UNICODE_CASE);
  private static final Pattern PRIVATE_KEY = Pattern.compile("(?i)BEGIN [A-Z0-9 ]*PRIVATE KEY");

  private final List<Pattern> patterns;

  public ConsoleRedactor(List<String> expressions) {
    List<Pattern> compiled = new ArrayList<>();
    for (String expression : expressions == null ? List.<String>of() : expressions) {
      if (expression == null || expression.isBlank() || expression.length() > 512)
        throw new IllegalArgumentException("Invalid console redaction pattern");
      try {
        compiled.add(Pattern.compile(expression));
      } catch (PatternSyntaxException error) {
        throw new IllegalArgumentException("Invalid console redaction pattern", error);
      }
    }
    this.patterns = List.copyOf(compiled);
  }

  public String redact(String input) {
    if (PRIVATE_KEY.matcher(input).find()) return "<redacted private key material>";
    String result = BEARER.matcher(input).replaceAll("$1<redacted>");
    result = ASSIGNMENT.matcher(result).replaceAll("$1<redacted>");
    for (Pattern pattern : patterns) result = pattern.matcher(result).replaceAll("<redacted>");
    return result;
  }
}
