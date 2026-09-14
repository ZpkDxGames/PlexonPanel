package io.github.zpkdxgames.plexonpanel.control;

import java.util.*;
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
  private static final Pattern DETAIL_KEY = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,31}");

  private final String code;
  private final String phase;
  private final boolean retryable;
  private final String safeRelativePath;
  private final Map<String, String> safeDetails;

  public OperationFailure(String code, String phase, String message, boolean retryable) {
    this(code, phase, message, retryable, null, Map.of(), null);
  }

  public OperationFailure(
      String code,
      String phase,
      String message,
      boolean retryable,
      String safeRelativePath) {
    this(code, phase, message, retryable, safeRelativePath, Map.of(), null);
  }

  public OperationFailure(
      String code,
      String phase,
      String message,
      boolean retryable,
      String safeRelativePath,
      Throwable cause) {
    this(code, phase, message, retryable, safeRelativePath, Map.of(), cause);
  }

  public static OperationFailure withSafeDetails(
      String code,
      String phase,
      String message,
      boolean retryable,
      Map<String, String> safeDetails) {
    return new OperationFailure(code, phase, message, retryable, null, safeDetails, null);
  }

  private OperationFailure(
      String code,
      String phase,
      String message,
      boolean retryable,
      String safeRelativePath,
      Map<String, String> safeDetails,
      Throwable cause) {
    super(validateMessage(message), cause);
    this.code = validate(CODE, code, "code");
    this.phase = validate(PHASE, phase, "phase");
    this.retryable = retryable;
    this.safeRelativePath = validateRelativePath(safeRelativePath);
    this.safeDetails = validateDetails(safeDetails);
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
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("phase", phase);
    data.put("retryable", retryable);
    if (safeRelativePath != null) data.put("safeRelativePath", safeRelativePath);
    data.putAll(safeDetails);
    return Map.copyOf(data);
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

  private static Map<String, String> validateDetails(Map<String, String> details) {
    if (details == null || details.isEmpty()) return Map.of();
    if (details.size() > 8) throw new IllegalArgumentException("Too many safe detail fields");
    Map<String, String> safe = new LinkedHashMap<>();
    for (var entry : details.entrySet()) {
      String key = Objects.requireNonNull(entry.getKey(), "detail key");
      String value = Objects.requireNonNull(entry.getValue(), "detail value");
      if (!DETAIL_KEY.matcher(key).matches()) throw new IllegalArgumentException("Invalid safe detail key");
      if (value.length() > 128 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0)
        throw new IllegalArgumentException("Invalid safe detail value");
      safe.put(key, value);
    }
    return Map.copyOf(safe);
  }
}
