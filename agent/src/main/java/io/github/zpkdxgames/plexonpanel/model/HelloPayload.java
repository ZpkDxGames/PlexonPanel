package io.github.zpkdxgames.plexonpanel.model;

import java.util.Map;

public record HelloPayload(
    String agentName,
    String agentVersion,
    int protocolVersion,
    String publicKey,
    String publicKeyFingerprint,
    String serverSoftware,
    String minecraftVersion,
    String javaVersion,
    String operatingSystem,
    boolean paired,
    Map<String, Boolean> capabilities
) {
}
