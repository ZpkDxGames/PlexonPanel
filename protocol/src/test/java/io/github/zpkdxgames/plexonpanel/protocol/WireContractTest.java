package io.github.zpkdxgames.plexonpanel.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import io.github.zpkdxgames.plexonpanel.identity.KeyCodec;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;

class WireContractTest {
  @Test
  void verifiesTheNodeGeneratedCanonicalEnvelope() throws Exception {
    try (var input = getClass().getResourceAsStream("/v3-envelope.json")) {
      assertNotNull(input);
      var fixture =
          JsonParser.parseString(new String(input.readAllBytes(), StandardCharsets.UTF_8))
              .getAsJsonObject();
      var codec = new ProtocolCodec();
      var message = codec.decode(fixture.get("envelope").toString());
      assertTrue(
          codec.verify(
              message.envelope(), KeyCodec.decodePublic(fixture.get("publicKey").getAsString())));
      assertEquals(fixture.get("body"), message.body());
      var e = message.envelope();
      assertEquals(
          fixture.get("canonical").getAsString(),
          String.join(
              "\n",
              Integer.toString(e.protocolVersion()),
              e.type(),
              e.messageId(),
              e.serverId(),
              e.timestamp(),
              e.body()));
    }
  }

  @Test
  void aNewConnectionChangesTheSignedSessionAndRestartsSequence() {
    var session = new AgentSession();
    var first = session.stamp(Map.of("ok", true));
    var second = session.stamp(Map.of("ok", true));
    assertEquals(1, first.get("_sequence").getAsInt());
    assertEquals(2, second.get("_sequence").getAsInt());
    session.reset();
    var reconnect = session.stamp(Map.of());
    assertNotEquals(first.get("_session"), reconnect.get("_session"));
    assertEquals(1, reconnect.get("_sequence").getAsInt());
  }

  @Test
  void escapedInventoriesAlwaysFitTheSignedBodyBudget() {
    var values = new ArrayList<Map<String, String>>();
    for (int i = 0; i < 512; i++) values.add(Map.of("text", "\t\n".repeat(500)));
    var batches = SnapshotBatches.split("players", values, 512);
    int count = 0;
    for (var batch : batches) {
      assertTrue(new Gson().toJson(batch).getBytes(StandardCharsets.UTF_8).length < 65536);
      count += ((List<?>) batch.get("players")).size();
    }
    assertEquals(512, count);
    assertEquals(true, batches.getLast().get("complete"));
  }
}
