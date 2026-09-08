package io.github.zpkdxgames.plexonpanel.api;

import io.github.zpkdxgames.plexonpanel.AgentRuntime;
import io.github.zpkdxgames.plexonpanel.PlexonPanelPlugin;
import io.github.zpkdxgames.plexonpanel.integration.core.CoreBridge;
import io.github.zpkdxgames.plexonpanel.protocol.ProtocolCodec;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

public final class DefaultPlexonPanelAPI implements PlexonPanelAPI {
  private final PlexonPanelPlugin plugin;

  public DefaultPlexonPanelAPI(PlexonPanelPlugin plugin) {
    this.plugin = plugin;
  }

  @Override
  public String productVersion() {
    return plugin.getPluginMeta().getVersion();
  }

  @Override
  public int protocolVersion() {
    return ProtocolCodec.VERSION;
  }

  @Override
  public boolean coreMode() {
    CoreBridge bridge = plugin.coreBridge();
    return bridge != null && "CORE".equals(bridge.mode());
  }

  @Override
  public boolean gatewayEnabled() {
    AgentRuntime runtime = plugin.runtime();
    return runtime != null && runtime.settings().gateway().enabled();
  }

  @Override
  public boolean relayAuthenticated() {
    AgentRuntime runtime = plugin.runtime();
    return runtime != null && runtime.gateway().isAuthenticated();
  }

  @Override
  public ConnectionStateView relayState() {
    AgentRuntime runtime = plugin.runtime();
    if (runtime == null) {
      return new ConnectionStateView("STOPPED", false);
    }
    return new ConnectionStateView(runtime.gateway().state().name(), runtime.gateway().isAuthenticated());
  }

  @Override
  public UUID serverId() {
    return plugin.identity().serverId();
  }

  @Override
  public String serverFingerprint() {
    return plugin.identity().fingerprint();
  }

  @Override
  public boolean paired() {
    return plugin.pairingState().isPaired();
  }

  @Override
  public Set<String> localCapabilities() {
    AgentRuntime runtime = plugin.runtime();
    if (runtime == null) {
      return Set.of();
    }
    TreeSet<String> enabled = new TreeSet<>();
    runtime
        .policy()
        .capabilities()
        .forEach(
            (capability, available) -> {
              if (Boolean.TRUE.equals(available)) {
                enabled.add(capability);
              }
            });
    return Set.copyOf(enabled);
  }

  @Override
  public Optional<Instant> lastConnectedAt() {
    AgentRuntime runtime = plugin.runtime();
    return runtime == null ? Optional.empty() : Optional.ofNullable(runtime.gateway().lastConnectedAt());
  }

  @Override
  public Optional<Instant> lastRelayMessageAt() {
    AgentRuntime runtime = plugin.runtime();
    return runtime == null ? Optional.empty() : Optional.ofNullable(runtime.gateway().lastMessageAt());
  }

  @Override
  public int reconnectAttempts() {
    AgentRuntime runtime = plugin.runtime();
    return runtime == null ? 0 : runtime.gateway().reconnectAttempts();
  }
}
