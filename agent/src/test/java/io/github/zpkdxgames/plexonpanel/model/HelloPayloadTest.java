package io.github.zpkdxgames.plexonpanel.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HelloPayloadTest {
  @Test
  void fleetHelloCarriesTheSharedContractAndImmutableNodeBesidePresentationFields() {
    HelloPayload payload = new HelloPayload("PlexonPanel", "5.0.0", 3,
        "public-fixture-key", "public-fixture-fingerprint", "Paper 26.2", "26.2", "25", "Linux",
        true, Map.of("telemetry.view", true), "PAPER", "host-public-fixture",
        io.github.zpkdxgames.plexonpanel.identity.FleetContract.ID,
        "10000000-0000-4000-8000-000000000001", "plexoncraft", "Renamed PlexonCraft");
    JsonObject serialized = new Gson().toJsonTree(payload).getAsJsonObject();
    assertEquals(io.github.zpkdxgames.plexonpanel.identity.FleetContract.ID,
        serialized.get("fleetContract").getAsString());
    assertEquals("10000000-0000-4000-8000-000000000001", serialized.get("nodeId").getAsString());
    assertEquals("plexoncraft", serialized.get("instanceKey").getAsString());
    assertEquals("Renamed PlexonCraft", serialized.get("serverName").getAsString());
  }

  @Test
  void serializesCanonicalRelayHandshakeFieldNames() {
    HelloPayload payload =
        new HelloPayload(
            "PlexonPanel",
            "3.0.0",
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

    assertEquals("3.0.0", serialized.get("pluginVersion").getAsString());
    assertEquals("Paper 26.2", serialized.get("paperVersion").getAsString());
    assertFalse(serialized.has("agentVersion"));
    assertFalse(serialized.has("serverSoftware"));
  }
}
