package io.github.zpkdxgames.plexonpanel.api;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Local, read-only PlexonPanel service exposed through Bukkit ServicesManager. */
public interface PlexonPanelAPI {
  String productVersion();

  int protocolVersion();

  boolean coreMode();

  boolean gatewayEnabled();

  boolean relayAuthenticated();

  ConnectionStateView relayState();

  UUID serverId();

  String serverFingerprint();

  boolean paired();

  Set<String> localCapabilities();

  Optional<Instant> lastConnectedAt();

  Optional<Instant> lastRelayMessageAt();

  int reconnectAttempts();

  record ConnectionStateView(String state, boolean authenticated) {}
}
