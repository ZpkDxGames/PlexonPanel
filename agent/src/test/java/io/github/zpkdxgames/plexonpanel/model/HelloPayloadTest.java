package io.github.zpkdxgames.plexonpanel.model;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class HelloPayloadTest {
    @Test
    void serializesCanonicalRelayHandshakeFieldNames() {
        HelloPayload payload = new HelloPayload(
            "PlexonPanel",
            "1.0.0-rc.2",
            2,
            "agent-public-key",
            "agent-fingerprint",
            "Paper 26.2",
            "26.2",
            "25",
            "Windows x86_64",
            false,
            Map.of("telemetry", true)
        );

        JsonObject serialized = new Gson().toJsonTree(payload).getAsJsonObject();

        assertEquals("1.0.0-rc.2", serialized.get("pluginVersion").getAsString());
        assertEquals("Paper 26.2", serialized.get("paperVersion").getAsString());
        assertFalse(serialized.has("agentVersion"));
        assertFalse(serialized.has("serverSoftware"));
    }
}
