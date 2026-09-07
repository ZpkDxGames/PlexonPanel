package io.github.zpkdxgames.plexonpanel.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.zpkdxgames.plexonpanel.identity.DeviceIdentity;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProtocolCodecTest {
  private static final Instant NOW = Instant.parse("2026-08-14T12:00:00Z");

  @Test
  void signedEnvelopeRoundTripsAndVerifies() throws Exception {
    DeviceIdentity identity = identity();
    ProtocolCodec codec = new ProtocolCodec(Clock.fixed(NOW, ZoneOffset.UTC));

    String encoded = codec.encodeSigned("telemetry.test", Map.of("value", 42), identity);
    DecodedMessage decoded = codec.decode(encoded);

    assertEquals("telemetry.test", decoded.envelope().type());
    assertEquals(42, decoded.body().get("value").getAsInt());
    assertEquals(NOW, codec.timestamp(decoded.envelope()));
    assertTrue(codec.verify(decoded.envelope(), identity.keyPair().getPublic()));
  }

  @Test
  void signedBodiesPreserveExplicitNullsRequiredByWireContracts() throws Exception {
    DeviceIdentity identity = identity();
    ProtocolCodec codec = new ProtocolCodec(Clock.fixed(NOW, ZoneOffset.UTC));
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("state", "JOINED");
    body.put("sessionEndedAt", null);
    body.put("sessionDurationMillis", null);

    DecodedMessage decoded =
        codec.decode(codec.encodeSigned("players.presence", body, identity));

    assertTrue(decoded.body().has("sessionEndedAt"));
    assertTrue(decoded.body().get("sessionEndedAt").isJsonNull());
    assertTrue(decoded.body().has("sessionDurationMillis"));
    assertTrue(decoded.body().get("sessionDurationMillis").isJsonNull());
  }

  @Test
  void modifyingSignedMetadataInvalidatesSignature() throws Exception {
    DeviceIdentity identity = identity();
    ProtocolCodec codec = new ProtocolCodec(Clock.fixed(NOW, ZoneOffset.UTC));
    ProtocolEnvelope original =
        codec.decode(codec.encodeSigned("agent.hello", Map.of("ok", true), identity)).envelope();
    ProtocolEnvelope modified =
        new ProtocolEnvelope(
            original.protocolVersion(),
            "action.request",
            original.messageId(),
            original.serverId(),
            original.timestamp(),
            original.body(),
            original.signature());

    assertFalse(codec.verify(modified, identity.keyPair().getPublic()));
  }

  @Test
  void oversizedBodiesAreRejectedBeforeSigning() throws Exception {
    DeviceIdentity identity = identity();
    ProtocolCodec codec = new ProtocolCodec();
    String oversized = "x".repeat(ProtocolCodec.MAX_BODY_BYTES + 1);

    assertThrows(
        IllegalArgumentException.class,
        () -> codec.encodeSigned("test", Map.of("value", oversized), identity));
  }

  @Test
  void ambiguousMessageTypesAreRejectedBeforeSigning() throws Exception {
    ProtocolCodec codec = new ProtocolCodec();

    assertThrows(
        IllegalArgumentException.class,
        () -> codec.encodeSigned("test\naction.request", Map.of(), identity()));
  }

  private static DeviceIdentity identity() throws Exception {
    return new DeviceIdentity(
        UUID.fromString("66431911-ce8c-48f3-9846-4754a8af21ef"),
        NOW,
        KeyPairGenerator.getInstance("Ed25519").generateKeyPair());
  }
}
