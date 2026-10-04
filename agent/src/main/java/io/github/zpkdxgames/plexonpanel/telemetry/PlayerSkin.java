package io.github.zpkdxgames.plexonpanel.telemetry;

import java.net.URL;
import java.util.Locale;
import java.util.regex.Pattern;

/** Exposes only a public texture identifier; never forwards profile blobs or arbitrary URLs. */
final class PlayerSkin {
  private static final Pattern TEXTURE_PATH = Pattern.compile("/texture/[0-9a-fA-F]{64}");
  private PlayerSkin() {}

  static String textureId(URL skin) {
    if (skin == null || !skin.getHost().equalsIgnoreCase("textures.minecraft.net")
        || !(skin.getProtocol().equals("https") || skin.getProtocol().equals("http"))
        || skin.getUserInfo() != null || skin.getQuery() != null || skin.getRef() != null
        || skin.getPort() != -1) return null;
    String path = skin.getPath();
    if (!TEXTURE_PATH.matcher(path).matches()) return null;
    return path.substring("/texture/".length()).toLowerCase(Locale.ROOT);
  }
}
