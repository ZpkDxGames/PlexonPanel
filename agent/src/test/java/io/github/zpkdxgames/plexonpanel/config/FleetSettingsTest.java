package io.github.zpkdxgames.plexonpanel.config;

import static org.junit.jupiter.api.Assertions.*;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class FleetSettingsTest {
  private YamlConfiguration config() {
    return YamlConfiguration.loadConfiguration(new InputStreamReader(
        getClass().getResourceAsStream("/config.yml"), StandardCharsets.UTF_8));
  }

  @Test void legacyRemainsUnchangedAndFleetIdentityIsSeparateFromItsName() {
    var config = config();
    assertNull(PanelSettings.load(config).fleet());
    String node = UUID.randomUUID().toString();
    config.set("fleet.node-id", node);
    config.set("fleet.instance-key", "plexoncraft");
    config.set("fleet.server-name", "PlexonCraft");
    var id = PanelSettings.load(config).fleet().identityFor(UUID.randomUUID());
    config.set("fleet.server-name", "Renamed");
    var renamed = PanelSettings.load(config).fleet().identityFor(id.serverId());
    assertTrue(id.sameTarget(renamed));
    assertEquals("minecraft@plexoncraft.service", renamed.minecraftUnit());
  }

  @Test void invalidOrIncompleteFleetPolicyFailsBeforeRuntimeActivation() {
    var config = config();
    config.set("fleet.node-id", UUID.randomUUID().toString());
    assertThrows(IllegalArgumentException.class, () -> PanelSettings.load(config));
    config.set("fleet.instance-key", "plexoncraft;stop-server2");
    config.set("fleet.server-name", "Test");
    assertThrows(IllegalArgumentException.class, () -> PanelSettings.load(config));
    config.set("fleet.instance-key", "plexoncraft");
    config.set("fleet.node-id", "invalid-private-input");
    var failure = assertThrows(IllegalArgumentException.class, () -> PanelSettings.load(config));
    assertFalse(failure.toString().contains("invalid-private-input"));
    assertNull(failure.getCause());
  }
}
