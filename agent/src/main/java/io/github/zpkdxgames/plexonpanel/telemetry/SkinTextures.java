package io.github.zpkdxgames.plexonpanel.telemetry;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;

/** Bounded decoder for an already-applied profile property; never performs network or storage I/O. */
public final class SkinTextures {
  private SkinTextures() {}

  public static String fromProperty(String encoded) {
    if (encoded == null || encoded.isEmpty() || encoded.length() > 8192) return null;
    try {
      byte[] decoded = Base64.getDecoder().decode(encoded);
      if (decoded.length > 6144) return null;
      String json = new String(decoded, StandardCharsets.UTF_8);
      // Reject excessive nesting before Gson allocates a recursive JSON tree.
      int depth = 0;
      boolean quoted = false;
      boolean escaped = false;
      for (int i = 0; i < json.length(); i++) {
        char c = json.charAt(i);
        if (quoted) {
          if (escaped) escaped = false;
          else if (c == '\\') escaped = true;
          else if (c == '"') quoted = false;
        } else if (c == '"') quoted = true;
        else if (c == '{' || c == '[') { if (++depth > 32) return null; }
        else if (c == '}' || c == ']') depth--;
      }
      JsonObject root = JsonParser.parseString(json).getAsJsonObject();
      JsonObject skin = root.getAsJsonObject("textures").getAsJsonObject("SKIN");
      if (!skin.get("url").isJsonPrimitive() || !skin.getAsJsonPrimitive("url").isString()) return null;
      URI uri = URI.create(skin.get("url").getAsString());
      if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
          || !"textures.minecraft.net".equalsIgnoreCase(uri.getHost())
          || uri.getRawUserInfo() != null || uri.getPort() != -1
          || uri.getRawQuery() != null || uri.getRawFragment() != null) return null;
      String path = uri.getRawPath();
      if (path == null || !path.matches("/texture/[0-9a-fA-F]{56,64}")) return null;
      return path.substring("/texture/".length()).toLowerCase(Locale.ROOT);
    } catch (RuntimeException invalidProperty) {
      return null;
    }
  }
}
