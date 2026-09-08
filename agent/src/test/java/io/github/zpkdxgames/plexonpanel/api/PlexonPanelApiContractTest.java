package io.github.zpkdxgames.plexonpanel.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class PlexonPanelApiContractTest {
  @Test
  void publicSurfaceIsTheExplicitReadOnlyContract() {
    Set<String> methods =
        Arrays.stream(PlexonPanelAPI.class.getDeclaredMethods())
            .map(Method::getName)
            .collect(Collectors.toSet());

    assertEquals(
        Set.of(
            "productVersion",
            "protocolVersion",
            "coreMode",
            "gatewayEnabled",
            "relayAuthenticated",
            "relayState",
            "serverId",
            "serverFingerprint",
            "paired",
            "localCapabilities",
            "lastConnectedAt",
            "lastRelayMessageAt",
            "reconnectAttempts"),
        methods);

    methods.forEach(
        name -> {
          String normalized = name.toLowerCase();
          assertFalse(normalized.startsWith("send"));
          assertFalse(normalized.startsWith("execute"));
          assertFalse(normalized.startsWith("issue"));
          assertFalse(normalized.startsWith("grant"));
          assertFalse(normalized.startsWith("paircode"));
        });
  }
}
