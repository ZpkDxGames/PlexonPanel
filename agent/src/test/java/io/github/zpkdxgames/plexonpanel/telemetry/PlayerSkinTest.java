package io.github.zpkdxgames.plexonpanel.telemetry;

import static org.junit.jupiter.api.Assertions.*;
import java.net.URI;
import org.junit.jupiter.api.Test;

class PlayerSkinTest {
  @Test void acceptsOnlyMinecraftTextureIdentifiers() throws Exception {
    String hash = "ab".repeat(32);
    assertEquals(hash, PlayerSkin.textureId(URI.create("https://textures.minecraft.net/texture/" + hash.toUpperCase()).toURL()));
    assertEquals(hash, PlayerSkin.textureId(URI.create("http://textures.minecraft.net/texture/" + hash).toURL()));
    assertNull(PlayerSkin.textureId(null));
    for (String url : new String[] {
      "https://evil.test/texture/" + hash, "https://textures.minecraft.net.evil.test/texture/" + hash,
      "https://user@textures.minecraft.net/texture/" + hash, "https://textures.minecraft.net:443/texture/" + hash,
      "https://textures.minecraft.net/texture/" + hash + "?token=private",
      "https://textures.minecraft.net/texture/" + hash + "#fragment",
      "https://textures.minecraft.net/texture/short", "https://textures.minecraft.net/texture/" + hash + "/extra"
    }) assertNull(PlayerSkin.textureId(URI.create(url).toURL()), url);
  }
}
