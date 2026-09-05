package io.github.zpkdxgames.plexonpanel.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HelloPayloadTest {
  @Test
  void serializesCanonicalRelayHandshakeFieldNames() {
    HelloPayload payload =
        new HelloPayload(
            "PlexonPanel",
            "2.0.0",
            3,
            "agent-public-key",
            "agent-fingerprint",
            "Paper 26.2",
            "26.2",
            "25",
            "Windows x86_64",
            false,
            Map.of("telemetry.view", true),
            "PAPER",
            "");

    JsonObject serialized = new Gson().toJsonTree(payload).getAsJsonObject();

    assertEquals("2.0.0", serialized.get("pluginVersion").getAsString());
    assertEquals("Paper 26.2", serialized.get("paperVersion").getAsString());
    assertFalse(serialized.has("agentVersion"));
    assertFalse(serialized.has("serverSoftware"));
  }
}
