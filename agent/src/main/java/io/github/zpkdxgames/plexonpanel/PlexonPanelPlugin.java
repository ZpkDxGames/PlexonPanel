package io.github.zpkdxgames.plexonpanel;

import io.github.zpkdxgames.plexonpanel.api.DefaultPlexonPanelAPI;
import io.github.zpkdxgames.plexonpanel.api.PlexonPanelAPI;
import io.github.zpkdxgames.plexonpanel.command.PlexonPanelCommand;
import io.github.zpkdxgames.plexonpanel.config.Messages;
import io.github.zpkdxgames.plexonpanel.config.PanelSettings;
import io.github.zpkdxgames.plexonpanel.identity.DeviceIdentity;
import io.github.zpkdxgames.plexonpanel.identity.IdentityStore;
import io.github.zpkdxgames.plexonpanel.identity.PairingState;
import io.github.zpkdxgames.plexonpanel.integration.core.CoreBridge;
import io.github.zpkdxgames.plexonpanel.integration.core.CoreBridgeFactory;
import java.nio.file.Path;
import java.util.Objects;
import java.util.logging.Level;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

public final class PlexonPanelPlugin extends JavaPlugin {
  private IdentityStore identityStore;
  private DeviceIdentity identity;
  private PairingState pairingState;
  private Messages messages;
  private AgentRuntime runtime;
  private io.github.zpkdxgames.plexonpanel.ui.AdminGui gui;
  private CoreBridge coreBridge;
  private PlexonPanelAPI panelApi;

  public io.github.zpkdxgames.plexonpanel.ui.AdminGui gui() {
    return gui;
  }

  @Override
  public void onEnable() {
    coreBridge = CoreBridgeFactory.resolve(this);
    coreBridge.registerStarting();
    saveDefaultConfig();
    migrateConfigDefaults();
    try {
      Path dataDirectory = getDataFolder().toPath();
      identityStore = new IdentityStore(dataDirectory);
      identity = identityStore.loadOrCreate();
      pairingState = new PairingState(dataDirectory);
      messages = new Messages(this);
      Path marker = dataDirectory.resolve("protocol-version.txt");
      if (!java.nio.file.Files.exists(marker)) {
        java.nio.file.Files.writeString(marker, "3\n");
        getLogger()
            .info(
                "Initialized the protocol-3 marker without changing the server identity, pairing state,"
                    + " or immutable device grants.");
      }
      gui = new io.github.zpkdxgames.plexonpanel.ui.AdminGui(this);

      PlexonPanelCommand commandHandler = new PlexonPanelCommand(this);
      PluginCommand command =
          Objects.requireNonNull(
              getCommand("plexonpanel"), "plexonpanel command is missing from plugin.yml");
      command.setExecutor(commandHandler);
      command.setTabCompleter(commandHandler);

      startRuntime(PanelSettings.load(getConfig()));
      registerPanelApi();
      coreBridge.markReady("Paper agent ready; protocol 3 control plane active");
      getLogger()
          .info(
              "PlexonPanel "
                  + getPluginMeta().getVersion()
                  + " enabled; server fingerprint "
                  + identity.fingerprint()
                  + "; mode "
                  + coreBridge.mode());
    } catch (Exception error) {
      if (coreBridge != null) {
        coreBridge.markFailed("PlexonPanel startup failed safely");
      }
      getLogger()
          .log(Level.SEVERE, "PlexonPanel could not start safely and will be disabled", error);
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
    unregisterPanelApi();
    if (coreBridge != null) {
      coreBridge.unregister();
    }
  }

  public synchronized void reloadAgent() throws java.io.IOException {
    reloadConfig();
    migrateConfigDefaults();
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
      if (coreBridge != null) {
        coreBridge.markReady("Paper agent ready after PlexonPanel reload");
      }
    } catch (RuntimeException error) {
      next.close();
      if (coreBridge != null) {
        coreBridge.markFailed("PlexonPanel runtime restart failed");
      }
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
      if (coreBridge != null) {
        coreBridge.markFailed("Identity rotation runtime construction failed");
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
      if (coreBridge != null) {
        coreBridge.markReady("Paper agent ready after explicit identity rotation");
      }
    } catch (RuntimeException error) {
      next.close();
      if (coreBridge != null) {
        coreBridge.markFailed("Identity rotation runtime start failed");
      }
      throw error;
    }
  }

  private void startRuntime(PanelSettings settings) throws java.io.IOException {
    AgentRuntime next = createRuntime(settings, identity);
    next.start();
    runtime = next;
  }

  private AgentRuntime createRuntime(PanelSettings settings, DeviceIdentity deviceIdentity)
      throws java.io.IOException {
    Path serverRoot = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
    return new AgentRuntime(this, settings, deviceIdentity, pairingState, serverRoot);
  }

  private void registerPanelApi() {
    if (panelApi != null) {
      return;
    }
    panelApi = new DefaultPlexonPanelAPI(this);
    getServer()
        .getServicesManager()
        .register(PlexonPanelAPI.class, panelApi, this, ServicePriority.Normal);
  }

  private void unregisterPanelApi() {
    PlexonPanelAPI current = panelApi;
    panelApi = null;
    if (current != null) {
      getServer().getServicesManager().unregister(PlexonPanelAPI.class, current);
    }
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

  public CoreBridge coreBridge() {
    return coreBridge;
  }

  public boolean panelApiRegistered() {
    return panelApi != null;
  }

  private void migrateConfigDefaults() {
    getConfig().options().copyDefaults(true);
    saveConfig();
  }
}
