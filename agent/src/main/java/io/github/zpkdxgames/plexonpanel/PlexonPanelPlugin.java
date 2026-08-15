package io.github.zpkdxgames.plexonpanel;

import io.github.zpkdxgames.plexonpanel.command.PlexonPanelCommand;
import io.github.zpkdxgames.plexonpanel.config.Messages;
import io.github.zpkdxgames.plexonpanel.config.PanelSettings;
import io.github.zpkdxgames.plexonpanel.identity.DeviceIdentity;
import io.github.zpkdxgames.plexonpanel.identity.IdentityStore;
import io.github.zpkdxgames.plexonpanel.identity.PairingState;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Path;
import java.util.Objects;
import java.util.logging.Level;

public final class PlexonPanelPlugin extends JavaPlugin {
    private IdentityStore identityStore;
    private DeviceIdentity identity;
    private PairingState pairingState;
    private Messages messages;
    private AgentRuntime runtime;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        try {
            Path dataDirectory = getDataFolder().toPath();
            identityStore = new IdentityStore(dataDirectory);
            identity = identityStore.loadOrCreate();
            pairingState = new PairingState(dataDirectory);
            messages = new Messages(this);

            PlexonPanelCommand commandHandler = new PlexonPanelCommand(this);
            PluginCommand command = Objects.requireNonNull(getCommand("plexonpanel"), "plexonpanel command is missing from plugin.yml");
            command.setExecutor(commandHandler);
            command.setTabCompleter(commandHandler);

            startRuntime(PanelSettings.load(getConfig()));
            getLogger().info("PlexonPanel " + getPluginMeta().getVersion() + " enabled; server fingerprint " + identity.fingerprint());
        } catch (Exception error) {
            getLogger().log(Level.SEVERE, "PlexonPanel could not start safely and will be disabled", error);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        AgentRuntime current = runtime;
        runtime = null;
        if (current != null) {
            current.close();
        }
    }

    public synchronized void reloadAgent() {
        reloadConfig();
        PanelSettings candidateSettings = PanelSettings.load(getConfig());
        AgentRuntime next = createRuntime(candidateSettings, identity);
        AgentRuntime previous = runtime;
        runtime = null;
        if (previous != null) {
            previous.close();
        }
        try {
            next.start();
            runtime = next;
            messages.reload();
        } catch (RuntimeException error) {
            next.close();
            throw error;
        }
    }

    public synchronized void rotateIdentity() throws Exception {
        AgentRuntime previous = runtime;
        PanelSettings currentSettings = PanelSettings.load(getConfig());
        pairingState.clear();
        DeviceIdentity replacement = identityStore.rotate();
        identity = replacement;
        AgentRuntime next;
        try {
            next = createRuntime(currentSettings, replacement);
        } catch (RuntimeException error) {
            runtime = null;
            if (previous != null) {
                previous.close();
            }
            throw error;
        }
        runtime = null;
        if (previous != null) {
            previous.close();
        }
        try {
            next.start();
            runtime = next;
        } catch (RuntimeException error) {
            next.close();
            throw error;
        }
    }

    private void startRuntime(PanelSettings settings) {
        AgentRuntime next = createRuntime(settings, identity);
        next.start();
        runtime = next;
    }

    private AgentRuntime createRuntime(PanelSettings settings, DeviceIdentity deviceIdentity) {
        Path serverRoot = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
        return new AgentRuntime(this, settings, deviceIdentity, pairingState, serverRoot);
    }

    public AgentRuntime runtime() {
        return runtime;
    }

    public DeviceIdentity identity() {
        return identity;
    }

    public PairingState pairingState() {
        return pairingState;
    }

    public Messages messages() {
        return messages;
    }
}
