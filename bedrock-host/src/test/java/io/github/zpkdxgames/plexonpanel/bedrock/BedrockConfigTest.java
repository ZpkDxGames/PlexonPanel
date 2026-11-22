package io.github.zpkdxgames.plexonpanel.bedrock;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.URI;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@EnabledOnOs(OS.LINUX)
class BedrockConfigTest {
  @TempDir Path directory;

  @Test
  void generatesOnePrivateIdentityBoundToExistingNode() throws Exception {
    String relayKey = relayKey();
    UUID nodeId = UUID.randomUUID();
    Path config = directory.resolve("host.properties");
    URI endpoint = URI.create("ws://127.0.0.1:8787/v1/agent");
    var id = BedrockConfig.initialize(nodeId, endpoint, relayKey, config);
    assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(config));
    var loaded = BedrockConfig.loadHost(config, true);
    assertEquals(nodeId, loaded.binding().nodeId());
    assertEquals(id.serverId(), loaded.binding().serverId());
    assertNotEquals(nodeId, id.serverId());
    assertEquals(id.publicKeyBase64(), loaded.identity().publicKeyBase64());
    assertThrows(FileAlreadyExistsException.class,
        () -> BedrockConfig.initialize(nodeId, endpoint, relayKey, config));
    assertEquals(id.serverId(), BedrockConfig.loadHost(config, true).binding().serverId());

    Path alias = directory.resolve("linked.properties");
    Files.createSymbolicLink(alias, config);
    assertThrows(IOException.class, () -> BedrockConfig.loadHost(alias, true));
    Files.setPosixFilePermissions(config, PosixFilePermissions.fromString("rw-r--r--"));
    assertThrows(IOException.class, () -> BedrockConfig.loadHost(config, true));
  }

  @Test
  void refusesNonLoopbackCleartextAndRelayUrlOverrides() throws Exception {
    String relayKey = relayKey();
    for (String target : new String[] {
        "ws://example.test/v1/agent", "ws://localhost/v1/agent",
        "wss://example.test/v1/agent?serverId=other",
        "wss://partner@example.test/v1/agent", "wss://example.test/legacy",
        "https://example.test/v1/agent",
    }) {
      Path config = directory.resolve("rejected.properties");
      assertThrows(IllegalArgumentException.class,
          () -> BedrockConfig.initialize(UUID.randomUUID(), URI.create(target), relayKey, config));
      assertFalse(Files.exists(config));
    }
  }

  private static String relayKey() throws Exception {
    var pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    return Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
  }
}
