package io.github.zpkdxgames.plexonpanel.integration.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

class StandaloneCoreBridgeTest {
  @Test
  void absentCoreRemainsStandaloneAndNoop() {
    CoreBridge bridge =
        new StandaloneCoreBridge(false, "-", "-", "PlexonCore is not installed");

    assertFalse(bridge.installed());
    assertFalse(bridge.available());
    assertFalse(bridge.compatible());
    assertEquals("STANDALONE", bridge.mode());
    assertEquals("NOT_INSTALLED", bridge.registrationState());

    bridge.registerStarting();
    bridge.markReady("ready");
    bridge.markDegraded("degraded");
    bridge.markFailed("failed");
    bridge.unregister();

    assertEquals("STANDALONE", bridge.mode());
  }

  @Test
  void installedButUnavailableCoreStillFallsBackSafely() {
    CoreBridge bridge =
        new StandaloneCoreBridge(true, "2.0.0", "2.0", "Unsupported Core API");

    assertEquals("STANDALONE", bridge.mode());
    assertEquals("UNAVAILABLE", bridge.registrationState());
    assertEquals("2.0.0", bridge.pluginVersion());
    assertEquals("2.0", bridge.apiVersion());
  }
}
