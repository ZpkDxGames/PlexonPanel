package io.github.zpkdxgames.plexonpanel.bedrock;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import io.github.zpkdxgames.plexonpanel.identity.DeviceIdentity;
import io.github.zpkdxgames.plexonpanel.protocol.ProtocolCodec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BedrockRelayProtocolTest {
  private static final String NONCE = "abcdefghijklmnopqrstuv";
  private final ProtocolCodec codec = new ProtocolCodec();
  private DeviceIdentity relay;
  private DeviceIdentity host;
  private BedrockConfig.Host config;
  private BedrockRelayProtocol link;
  private JsonObject gateway;

  @BeforeEach
  void setUp() throws Exception {
    UUID serverId = UUID.randomUUID();
    KeyPairGenerator keys = KeyPairGenerator.getInstance("Ed25519");
    host = new DeviceIdentity(serverId, Instant.now(), keys.generateKeyPair());
    relay = new DeviceIdentity(serverId, Instant.now(), keys.generateKeyPair());
    var binding = new BedrockConfig.Binding(serverId, UUID.randomUUID());
    config = new BedrockConfig.Host(binding, "TonimBedrock", URI.create("ws://127.0.0.1:8787/v1/agent"),
        relay.keyPair().getPublic(), host);
    link = new BedrockRelayProtocol(config);
    var hello = codec.decode(link.hello());
    assertTrue(codec.verify(hello.envelope(), host.keyPair().getPublic()));
    assertEquals("BEDROCK_HOST", hello.body().get("agentKind").getAsString());
    assertEquals("BEDROCK", hello.body().get("edition").getAsString());
    assertEquals(1, hello.body().get("_sequence").getAsInt());
    assertEquals(2, hello.body().getAsJsonArray("capabilities").size());
    assertFalse(hello.body().has("hostPublicKey"));
    gateway = new JsonObject();
    gateway.addProperty("_session", hello.body().get("_session").getAsString());
    gateway.addProperty("bedrockContract", BedrockRelayProtocol.CONTRACT);
    gateway.addProperty("nodeId", binding.nodeId().toString());
    gateway.addProperty("instanceKey", BedrockConfig.INSTANCE);
    gateway.addProperty("nonce", NONCE);
    gateway.addProperty("protocolVersion", 3);
  }

  @Test
  void publishesSignedReadingsOnlyAfterMatchingChallengeAndAuthorization() {
    var data = new JsonObject();
    data.add("containerMemoryBytes", com.google.gson.JsonNull.INSTANCE);
    assertThrows(SecurityException.class, () -> link.reading(BedrockBroker.Operation.STATUS, data));
    assertThrows(SecurityException.class, () -> link.receive(sign("gateway.authenticated")));
    var response = codec.decode(link.receive(sign("gateway.challenge")));
    assertEquals("agent.challenge_response", response.envelope().type());
    assertEquals(2, response.body().get("_sequence").getAsInt());
    assertTrue(DeviceIdentity.verify(host.keyPair().getPublic(), ("challenge:" + NONCE).getBytes(StandardCharsets.UTF_8),
        response.body().get("proof").getAsString()));
    assertNull(link.receive(sign("gateway.authenticated")));
    assertTrue(link.authenticated());
    var status = codec.decode(link.reading(BedrockBroker.Operation.STATUS, data));
    assertEquals("bedrock.status", status.envelope().type());
    assertTrue(codec.verify(status.envelope(), host.keyPair().getPublic()));
    assertEquals(3, status.body().get("_sequence").getAsInt());
    assertEquals("DOCKER", status.body().get("runtime").getAsString());
    assertTrue(status.body().get("containerMemoryBytes").isJsonNull());
    assertThrows(SecurityException.class, () -> link.receive(sign("action.request")));
  }

  @Test
  void rejectsWrongKeyNodeNonceStaleAndRepeatedFrames() throws Exception {
    assertThrows(SecurityException.class,
        () -> link.receive(codec.encodeSigned("gateway.challenge", gateway, host)));
    var wrongServer = new DeviceIdentity(UUID.randomUUID(), Instant.now(), relay.keyPair());
    assertThrows(SecurityException.class,
        () -> link.receive(codec.encodeSigned("gateway.challenge", gateway, wrongServer)));
    var wrongNode = gateway.deepCopy();
    wrongNode.addProperty("nodeId", UUID.randomUUID().toString());
    assertThrows(SecurityException.class,
        () -> link.receive(codec.encodeSigned("gateway.challenge", wrongNode, relay)));
    String challenge = sign("gateway.challenge");
    assertNotNull(link.receive(challenge));
    assertThrows(SecurityException.class, () -> link.receive(challenge));
    var wrongNonce = gateway.deepCopy();
    wrongNonce.addProperty("nonce", "abcdefghijklmnopqrstux");
    assertThrows(SecurityException.class,
        () -> link.receive(codec.encodeSigned("gateway.authenticated", wrongNonce, relay)));
    var stale = codec.decode(sign("gateway.authenticated")).envelope();
    var oldTime = Instant.now().minusSeconds(32).toString();
    String signable = String.join("\n", "3", stale.type(), stale.messageId(), stale.serverId(),
        oldTime, stale.body());
    var staleWire = UnixFrames.JSON.toJsonTree(stale).getAsJsonObject();
    staleWire.addProperty("timestamp", oldTime);
    staleWire.addProperty("signature", relay.signBase64Url(signable.getBytes(StandardCharsets.UTF_8)));
    assertThrows(SecurityException.class, () -> link.receive(staleWire.toString()));
    assertNull(link.receive(sign("gateway.authenticated")));
  }

  @Test
  void reconnectCannotReuseEarlierEpoch() {
    var next = new BedrockRelayProtocol(config);
    next.hello();
    assertThrows(SecurityException.class, () -> next.receive(sign("gateway.challenge")));
  }

  private String sign(String type) {
    return codec.encodeSigned(type, gateway, relay);
  }
}
