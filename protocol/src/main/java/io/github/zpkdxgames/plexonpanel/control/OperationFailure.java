package io.github.zpkdxgames.plexonpanel.control;

import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Browser-safe failure contract for control-plane operations.
 *
 * <p>The message and optional path are deliberately bounded and sanitized. Raw exception messages,
 * absolute paths, command output and credentials must never be copied into this type.
 */
public final class OperationFailure extends RuntimeException {
  private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");
  private static final Pattern PHASE = Pattern.compile("[A-Z][A-Z0-9_]{0,47}");
  private static final Pattern RELATIVE =
      Pattern.compile("[A-Za-z0-9][A-Za-z0-9_ ./()@+,'\\-]{0,511}");

  private final String code;
  private final String phase;
  private final boolean retryable;
  private final String safeRelativePath;

  public OperationFailure(String code, String phase, String message, boolean retryable) {
    this(code, phase, message, retryable, null, null);
  }

  public OperationFailure(
      String code,
      String phase,
      String message,
      boolean retryable,
      String safeRelativePath) {
    this(code, phase, message, retryable, safeRelativePath, null);
  }

  public OperationFailure(
      String code,
      String phase,
      String message,
      boolean retryable,
      String safeRelativePath,
      Throwable cause) {
    super(validateMessage(message), cause);
    this.code = validate(CODE, code, "code");
    this.phase = validate(PHASE, phase, "phase");
    this.retryable = retryable;
    this.safeRelativePath = validateRelativePath(safeRelativePath);
  }

  public String code() {
    return code;
  }

  public String phase() {
    return phase;
  }

  public boolean retryable() {
    return retryable;
  }

  public String safeRelativePath() {
    return safeRelativePath;
  }

  public Map<String, Object> safeData() {
    if (safeRelativePath == null)
      return Map.of("phase", phase, "retryable", retryable);
    return Map.of(
        "phase", phase,
        "retryable", retryable,
        "safeRelativePath", safeRelativePath);
  }

  private static String validate(Pattern pattern, String value, String label) {
    Objects.requireNonNull(value, label);
    if (!pattern.matcher(value).matches()) throw new IllegalArgumentException("Invalid " + label);
    return value;
  }

  private static String validateMessage(String value) {
    Objects.requireNonNull(value, "message");
    if (value.isBlank() || value.length() > 320 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0)
      throw new IllegalArgumentException("Invalid safe operation message");
    return value;
  }

  private static String validateRelativePath(String value) {
    if (value == null || value.isBlank()) return null;
    String normalized = value.replace('\\', '/');
    if (normalized.startsWith("/")
        || normalized.contains("../")
        || normalized.equals("..")
        || normalized.contains(":" )
        || !RELATIVE.matcher(normalized).matches())
      throw new IllegalArgumentException("Invalid safe relative path");
    return normalized;
  }
}
