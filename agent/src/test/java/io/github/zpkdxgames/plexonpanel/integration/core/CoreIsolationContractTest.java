package io.github.zpkdxgames.plexonpanel.integration.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class CoreIsolationContractTest {
  @Test
  void coreBridgeCannotOwnPanelTransportOrAuthorization() throws Exception {
    String source =
        Files.readString(
            Path.of(
                "src/main/java/io/github/zpkdxgames/plexonpanel/integration/core/PlexonCoreBridge.java"));

    assertFalse(source.contains("GatewayClient"));
    assertFalse(source.contains("AgentRuntime"));
    assertFalse(source.contains("syncAccess"));
    assertFalse(source.contains("DeviceRegistry"));
    assertFalse(source.contains("ControlPolicy"));
    assertFalse(source.contains("PairingState"));
    assertFalse(source.contains("WebSocket"));
    assertTrue(source.contains("ModuleDescriptor"));
    assertTrue(source.contains("IntegrationState"));
  }

  @Test
  void missingProtocolMarkerCannotClearPairingState() throws Exception {
    String source =
        Files.readString(
            Path.of("src/main/java/io/github/zpkdxgames/plexonpanel/PlexonPanelPlugin.java"));
    int marker = source.indexOf("protocol-version.txt");
    int gui = source.indexOf("gui =", marker);
    assertTrue(marker >= 0 && gui > marker);
    String migrationBlock = source.substring(marker, gui);
    assertFalse(migrationBlock.contains("pairingState.clear"));
    assertTrue(migrationBlock.contains("Files.writeString(marker, \"3\\n\")"));
  }
}
