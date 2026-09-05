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
    String hostPublicKey) {}
