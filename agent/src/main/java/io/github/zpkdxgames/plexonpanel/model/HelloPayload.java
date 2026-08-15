package io.github.zpkdxgames.plexonpanel.model;

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
    boolean paired
) {
}
