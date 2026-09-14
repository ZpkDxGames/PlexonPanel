package io.github.zpkdxgames.plexonpanel;

import io.github.zpkdxgames.plexonpanel.action.PaperActions;
import io.github.zpkdxgames.plexonpanel.audit.LocalAudit;
import io.github.zpkdxgames.plexonpanel.chat.ChatStreamService;
import io.github.zpkdxgames.plexonpanel.config.ControlPolicy;
import io.github.zpkdxgames.plexonpanel.config.PanelSettings;
import io.github.zpkdxgames.plexonpanel.control.ControlEngine;
import io.github.zpkdxgames.plexonpanel.identity.DeviceIdentity;
import io.github.zpkdxgames.plexonpanel.identity.PairingState;
import io.github.zpkdxgames.plexonpanel.presence.PlayerPresenceService;
import io.github.zpkdxgames.plexonpanel.security.DeviceRegistry;
import io.github.zpkdxgames.plexonpanel.telemetry.TelemetryService;
import io.github.zpkdxgames.plexonpanel.transport.GatewayClient;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.plugin.java.JavaPlugin;

public final class AgentRuntime implements AutoCloseable {
  private final PanelSettings settings;
  private final GatewayClient gateway;
  private final ChatStreamService chat;
  private final TelemetryService telemetry;
  private final PlayerPresenceService presence;
  private final ControlEngine actions;
  private final ControlPolicy policy;
  private final DeviceRegistry devices;
  private final AtomicBoolean started = new AtomicBoolean();
  private final AtomicBoolean closed = new AtomicBoolean();

  public AgentRuntime(
      JavaPlugin plugin,
      PanelSettings settings,
      DeviceIdentity identity,
      PairingState pairingState,
      Path serverRoot)
      throws java.io.IOException {
    this.settings = settings;
    this.policy =
        ControlPolicy.load(plugin.getConfig(), settings, plugin.getDataFolder().toPath());
    this.devices =
        new DeviceRegistry(
            plugin.getDataFolder().toPath().resolve("access").resolve("devices.json"),
            identity.serverId().toString());
    LocalAudit localAudit =
        new LocalAudit(
            plugin.getDataFolder().toPath().resolve("audit"), settings.audit().retentionDays());
    localAudit.clean();
    this.gateway =
        new GatewayClient(plugin, settings, identity, pairingState, policy, devices, localAudit);
    this.chat = new ChatStreamService(plugin, settings.chat(), gateway);
    this.presence =
        new PlayerPresenceService(
            plugin.getDataFolder().toPath(), settings.playerHistory(), plugin.getLogger());
    this.telemetry =
        new TelemetryService(plugin, settings.telemetry(), gateway, serverRoot, presence);
    PaperActions backend = new PaperActions(plugin, policy, settings, chat, presence, telemetry);
    this.actions =
        new ControlEngine(
            devices,
            policy.capabilities(),
            localAudit,
            policy.files(),
            backend::execute,
            gateway,
            gateway::isAuthenticated,
            identity.serverId().toString());
    // Backup and maintenance coordination is Host-owned. Paper only services its normal action
    // surface plus the Step 6 request to republish the authoritative access registry.
    gateway.setInboundHandler(
        message -> {
          switch (message.envelope().type()) {
            case "access.authority.request" -> {
              try {
                actions.syncAccess();
              } catch (java.io.IOException error) {
                throw new IllegalStateException("Host access authority refresh failed", error);
              }
            }
            default -> actions.accept(message);
          }
        });
    gateway.setConnectedHandler(
        () -> {
          try {
            actions.syncAccess();
          } catch (java.io.IOException e) {
            throw new IllegalStateException("Access sync failed", e);
          }
          telemetry.sendInitialSnapshots();
        });
    gateway.setSnapshotRequestHandler(telemetry::sendInitialSnapshots);
  }

  public void start() {
    if (closed.get() || !started.compareAndSet(false, true)) return;
    chat.start();
    telemetry.start();
    gateway.start();
  }

  public ControlPolicy policy() {
    return policy;
  }

  public DeviceRegistry devices() {
    return devices;
  }

  public PanelSettings settings() {
    return settings;
  }

  public GatewayClient gateway() {
    return gateway;
  }

  public TelemetryService telemetry() {
    return telemetry;
  }

  public PlayerPresenceService presence() {
    return presence;
  }

  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) return;
    actions.close();
    telemetry.close();
    presence.close();
    gateway.close();
    chat.close();
  }
}
