package io.github.zpkdxgames.plexonpanel.control;

import com.google.gson.*;

public final class JsonFields {
  private JsonFields() {}

  public static String text(JsonObject body, String key, int max) {
    JsonElement v = body.get(key);
    if (v == null
        || !v.isJsonPrimitive()
        || !v.getAsJsonPrimitive().isString()
        || v.getAsString().length() > max
        || v.getAsString().indexOf('\0') >= 0) throw new IllegalArgumentException("Invalid " + key);
    return v.getAsString();
  }

  public static String optional(JsonObject body, String key, String fallback, int max) {
    return body.has(key) ? text(body, key, max) : fallback;
  }

  public static boolean bool(JsonObject body, String key) {
    JsonElement v = body.get(key);
    return v != null
        && v.isJsonPrimitive()
        && v.getAsJsonPrimitive().isBoolean()
        && v.getAsBoolean();
  }

  public static long integer(JsonObject body, String key, long fallback, long min, long max) {
    if (!body.has(key)) return fallback;
    JsonElement v = body.get(key);
    if (!v.isJsonPrimitive()
        || !v.getAsJsonPrimitive().isNumber()
        || !v.getAsString().matches("-?[0-9]{1,18}"))
      throw new IllegalArgumentException("Invalid " + key);
    long n = v.getAsLong();
    if (n < min || n > max) throw new IllegalArgumentException("Invalid " + key);
    return n;
  }
}
