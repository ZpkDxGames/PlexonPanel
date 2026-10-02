package io.github.zpkdxgames.plexonpanel.identity;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FleetIdentityTest {
  private final FleetIdentity first = FleetIdentity.parse(
      "20000000-0000-4000-8000-000000000001", "10000000-0000-4000-8000-000000000001", "plexoncraft", "PlexonCraft");

  @Test
  void fixturesCoverSharedAndIndependentNodes() throws Exception {
    try (var resource = getClass().getResourceAsStream("/fleet-contract-v1.json")) {
      assertNotNull(resource);
      var manifest = JsonParser.parseReader(new InputStreamReader(resource, StandardCharsets.UTF_8)).getAsJsonObject();
      assertEquals(FleetContract.PROTOCOL_VERSION, manifest.get("protocolVersion").getAsInt());
      assertEquals(FleetContract.PAPER_SCHEMA_VERSION, manifest.get("paperSchemaVersion").getAsInt());
      assertEquals(FleetContract.HOST_SCHEMA_VERSION, manifest.get("hostSchemaVersion").getAsInt());
      assertEquals(FleetContract.INSTANCE_KEY_PATTERN, manifest.get("instanceKeyPattern").getAsString());
      assertEquals("sha256:c5c8a5d21dd6b108f63fa6ecf00683c56ddb34cfb6716a2166ae22d3d438f902", FleetContract.ID);
      var fixtures = manifest.getAsJsonArray("fixtures");
      var identities = new java.util.ArrayList<FleetIdentity>();
      for (var fixture : fixtures) {
        var value = fixture.getAsJsonObject();
        identities.add(FleetIdentity.parse(value.get("serverId").getAsString(), value.get("nodeId").getAsString(),
            value.get("instanceKey").getAsString(), value.get("serverName").getAsString()));
      }
      assertEquals(3, identities.size());
      assertEquals(identities.get(0).nodeId(), identities.get(1).nodeId());
      assertNotEquals(identities.get(0).nodeId(), identities.get(2).nodeId());
      assertFalse(identities.get(0).sameTarget(identities.get(1)));
    }
  }

  @Test
  void renameCannotChangeAuthorizationTarget() {
    var renamed = first.renamed("A different display name");
    assertTrue(first.sameTarget(renamed));
    assertEquals(first.minecraftUnit(), renamed.minecraftUnit());
  }

  @Test
  void wrongNodeCannotBeSubstitutedForSameServer() {
    var wrongNode = new FleetIdentity(first.serverId(), UUID.randomUUID(), first.instanceKey(), first.serverName());
    assertFalse(first.sameTarget(wrongNode));
  }

  @Test
  void rejectsUnitInjectionAndNoncanonicalIdsWithoutEchoingInputs() {
    for (String key : new String[] {"../server2", "x.service", "x@server2", "x;stop", "UPPER", "", "x\n"})
      assertThrows(IllegalArgumentException.class, () -> new FleetIdentity(first.serverId(), first.nodeId(), key, "label"));
    var failure = assertThrows(IllegalArgumentException.class, () -> FleetIdentity.parseUuid("1-1-1-1-1", "nodeId"));
    assertEquals("Invalid nodeId", failure.getMessage());
    assertThrows(IllegalArgumentException.class, () -> FleetIdentity.parseUuid("00000000-0000-0000-0000-000000000000", "nodeId"));
  }
}
