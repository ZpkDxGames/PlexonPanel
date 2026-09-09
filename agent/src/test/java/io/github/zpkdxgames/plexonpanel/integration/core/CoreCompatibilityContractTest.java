package io.github.zpkdxgames.plexonpanel.integration.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class CoreCompatibilityContractTest {
  @Test
  void panelLifecycleSupportsCoreOneAndTwoWithoutChangingControlPlaneAuthority() {
    assertEquals(">=1.0 <3.0", CoreBridge.SUPPORTED_API_RANGE);
    assertEquals("panel", CoreBridge.MODULE_ID);
  }
}
