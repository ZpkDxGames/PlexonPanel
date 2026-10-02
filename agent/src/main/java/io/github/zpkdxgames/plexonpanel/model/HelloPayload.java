package io.github.zpkdxgames.plexonpanel.model;

import java.util.Map;

public record HelloPayload(
    String agentName,
    String pluginVersion,
    int protocolVersion,
    String publicKey,
    String publicKeyFingerprint,
    String paperVersion,
    String minecraftVersion,
    String javaVersion,
    String operatingSystem,
    boolean paired,
    Map<String, Boolean> capabilities,
    String agentKind,
    String hostPublicKey,
    String fleetContract,
    String nodeId,
    String instanceKey,
    String serverName) {
  public HelloPayload(String agentName, String pluginVersion, int protocolVersion,
      String publicKey, String publicKeyFingerprint, String paperVersion, String minecraftVersion,
      String javaVersion, String operatingSystem, boolean paired, Map<String, Boolean> capabilities,
      String agentKind, String hostPublicKey) {
    this(agentName, pluginVersion, protocolVersion, publicKey, publicKeyFingerprint, paperVersion,
        minecraftVersion, javaVersion, operatingSystem, paired, capabilities, agentKind, hostPublicKey,
        null, null, null, null);
  }
}
