package io.github.zpkdxgames.plexonpanel;

import io.github.zpkdxgames.plexonpanel.action.RemoteActionDispatcher;
import io.github.zpkdxgames.plexonpanel.audit.AuditService;
import io.github.zpkdxgames.plexonpanel.chat.ChatStreamService;
import io.github.zpkdxgames.plexonpanel.config.PanelSettings;
import io.github.zpkdxgames.plexonpanel.console.ConsoleStreamService;
import io.github.zpkdxgames.plexonpanel.identity.DeviceIdentity;
import io.github.zpkdxgames.plexonpanel.identity.PairingState;
import io.github.zpkdxgames.plexonpanel.telemetry.TelemetryService;
import io.github.zpkdxgames.plexonpanel.transport.GatewayClient;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

public final class AgentRuntime implements AutoCloseable {
    private final PanelSettings settings;
    private final GatewayClient gateway;
    private final AuditService audit;
    private final ChatStreamService chat;
    private final ConsoleStreamService console;
    private final TelemetryService telemetry;
    private final RemoteActionDispatcher actions;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

    public AgentRuntime(
        JavaPlugin plugin,
        PanelSettings settings,
        DeviceIdentity identity,
        PairingState pairingState,
        Path serverRoot
    ) {
        this.settings = settings;
        this.gateway = new GatewayClient(plugin, settings, identity, pairingState);
        this.audit = new AuditService(plugin, settings.audit(), plugin.getDataFolder().toPath());
        this.chat = new ChatStreamService(plugin, settings.chat(), gateway);
        this.console = new ConsoleStreamService(plugin, settings.console(), gateway, serverRoot);
        this.telemetry = new TelemetryService(plugin, settings.telemetry(), gateway, serverRoot);
        this.actions = new RemoteActionDispatcher(
            plugin,
            settings.remoteActions(),
            settings.console(),
            gateway,
            audit,
            chat
        );
        gateway.setInboundHandler(actions::accept);
        gateway.setConnectedHandler(telemetry::sendInitialSnapshots);
    }

    public void start() {
        if (closed.get() || !started.compareAndSet(false, true)) {
            return;
        }
        audit.start();
        chat.start();
        console.start();
        telemetry.start();
        gateway.start();
    }

    public PanelSettings settings() {
        return settings;
    }

    public GatewayClient gateway() {
        return gateway;
    }

    public ConsoleStreamService console() {
        return console;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        gateway.close();
        telemetry.close();
        console.close();
        chat.close();
        audit.close();
    }
}
