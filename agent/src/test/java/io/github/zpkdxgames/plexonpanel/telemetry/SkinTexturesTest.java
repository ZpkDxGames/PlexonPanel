package io.github.zpkdxgames.plexonpanel.telemetry;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.Gson;
import io.github.zpkdxgames.plexonpanel.model.PlayerSnapshot;
import io.github.zpkdxgames.plexonpanel.model.ServerSnapshot;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

class SkinTexturesTest {
  private static String property(String url) {
    return Base64.getEncoder().encodeToString(("{\"textures\":{\"SKIN\":{\"url\":\"" + url + "\"}}}").getBytes(StandardCharsets.UTF_8));
  }
  @Test void readsOnlyBoundedAppliedMinecraftTextures() {
    String hash = "A".repeat(64);
    assertEquals(hash.toLowerCase(), SkinTextures.fromProperty(property("http://textures.minecraft.net/texture/" + hash)));
    assertEquals("b".repeat(56), SkinTextures.fromProperty(property("https://textures.minecraft.net/texture/" + "b".repeat(56))));
    for (String url : List.of("https://evil.example/texture/" + hash,
        "https://textures.minecraft.net.evil.example/texture/" + hash,
        "https://textures.minecraft.net@evil.example/texture/" + hash,
        "https://textures.minecraft.net:443/texture/" + hash,
        "https://textures.minecraft.net/texture/" + hash + "?redirect=evil",
        "https://textures.minecraft.net/texture/" + hash + "#x",
        "ftp://textures.minecraft.net/texture/" + hash,
        "https://textures.minecraft.net/texture/short")) assertNull(SkinTextures.fromProperty(property(url)), url);
    assertNull(SkinTextures.fromProperty(null)); assertNull(SkinTextures.fromProperty("not base64"));
    assertNull(SkinTextures.fromProperty("a".repeat(8193)));
    assertNull(SkinTextures.fromProperty(Base64.getEncoder().encodeToString(
        ("[".repeat(100) + "0" + "]".repeat(100)).getBytes(StandardCharsets.UTF_8))));
    assertNull(SkinTextures.fromProperty(Base64.getEncoder().encodeToString("{}".getBytes(StandardCharsets.UTF_8))));
  }
  @Test void oldSnapshotConstructorsRemainCompatibleAndMetadataSerializes() {
    PlayerSnapshot old = new PlayerSnapshot("uuid", "name", "name", "world", "SURVIVAL", 10, 20, 20, 0, false, true, null, null, 20, 0, null, null, null, null);
    assertNull(old.skinTexture());
    PlayerSnapshot applied = new PlayerSnapshot("uuid", "name", "name", "world", "SURVIVAL", 10, 20, 20, 0, false, true, null, null, 20, 0, null, null, null, null, "a".repeat(64));
    assertTrue(new Gson().toJson(applied).contains("\"skinTexture\":\"" + "a".repeat(64)));
    ServerSnapshot server = new ServerSnapshot("now", "paper", "v", "v", 0, 20, List.of(20.0), 1, 0, 1, 1, 1, 0, 1000);
    assertNull(server.onlineMode());
    ServerSnapshot offline = new ServerSnapshot("now", "paper", "v", "v", 0, 20, List.of(20.0), 1, 0, 1, 1, 1, 0, 1000, false);
    assertTrue(new Gson().toJson(offline).contains("\"onlineMode\":false"));
  }
}
