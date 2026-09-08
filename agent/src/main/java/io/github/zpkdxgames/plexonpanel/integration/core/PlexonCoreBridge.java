package io.github.zpkdxgames.plexonpanel.integration.core;

import com.zpkdxgames.plexoncore.api.PlexonCoreAPI;
import com.zpkdxgames.plexoncore.api.PlexonCoreAPI.CoreVersion;
import com.zpkdxgames.plexoncore.integration.IntegrationRegistry.IntegrationState;
import com.zpkdxgames.plexoncore.module.ModuleRegistry;
import com.zpkdxgames.plexoncore.module.ModuleRegistry.ModuleDescriptor;
import com.zpkdxgames.plexoncore.module.ModuleRegistry.ModuleState;
import com.zpkdxgames.plexoncore.module.ModuleRegistry.ModuleVersionRange;
import java.time.Instant;
import java.util.Set;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

/** Core registration/diagnostics bridge. It does not own or modify Panel control-plane authority. */
public final class PlexonCoreBridge implements CoreBridge {
  private static final Set<String> CAPABILITIES =
      Set.of(
          "control-plane",
          "outbound-relay",
          "telemetry",
          "console-stream",
          "chat-stream",
          "remote-actions",
          "pairing",
          "device-access",
          "player-presence",
          "panel-api",
          "protocol-v3",
          "host-companion-compatible");

  private final Plugin plugin;
  private final PlexonCoreAPI core;
  private final CoreVersion version;
  private final boolean compatible;
  private boolean ownsRegistration;
  private String registrationState = "NOT_REGISTERED";
  private String detail = "PlexonCore API resolved";

  public PlexonCoreBridge(JavaPlugin plugin) {
    this(plugin, resolveApi());
  }

  PlexonCoreBridge(Plugin plugin, PlexonCoreAPI core) {
    this.plugin = plugin;
    this.core = core;
    this.version = core.version();
    this.compatible = ModuleVersionRange.parse(SUPPORTED_API_RANGE).contains(version);
    if (!compatible) {
      detail = "Core API " + version.apiVersion() + " is outside supported range " + SUPPORTED_API_RANGE;
    }
  }

  private static PlexonCoreAPI resolveApi() {
    RegisteredServiceProvider<PlexonCoreAPI> registration =
        Bukkit.getServicesManager().getRegistration(PlexonCoreAPI.class);
    if (registration == null) {
      throw new IllegalStateException("PlexonCore API service is not registered");
    }
    return registration.getProvider();
  }

  @Override
  public boolean installed() {
    return true;
  }

  @Override
  public boolean available() {
    return compatible;
  }

  @Override
  public boolean compatible() {
    return compatible;
  }

  @Override
  public String pluginVersion() {
    return version.pluginVersion();
  }

  @Override
  public String apiVersion() {
    return version.apiVersion();
  }

  @Override
  public String mode() {
    return compatible && ownsRegistration ? "CORE" : "STANDALONE";
  }

  @Override
  public String registrationState() {
    if (ownsRegistration) {
      return core.modules().find(MODULE_ID).map(descriptor -> descriptor.state().name()).orElse("NOT_REGISTERED");
    }
    return registrationState;
  }

  @Override
  public String detail() {
    if (ownsRegistration) {
      return core.modules().find(MODULE_ID).map(ModuleDescriptor::detail).orElse(detail);
    }
    return detail;
  }

  @Override
  public void registerStarting() {
    if (!compatible) {
      registrationState = "INCOMPATIBLE";
      return;
    }
    register(ModuleState.STARTING, "Initializing PlexonPanel Paper agent");
  }

  private void register(ModuleState state, String newDetail) {
    ModuleDescriptor existing = core.modules().find(MODULE_ID).orElse(null);
    if (existing != null && existing.plugin() == plugin) {
      ownsRegistration = true;
      core.modules().updateState(MODULE_ID, state, newDetail);
      registrationState = state.name();
      detail = newDetail;
      return;
    }

    ModuleDescriptor descriptor =
        new ModuleDescriptor(
            MODULE_ID,
            "PlexonPanel",
            plugin.getName(),
            plugin.getPluginMeta().getVersion(),
            plugin,
            ModuleVersionRange.parse(SUPPORTED_API_RANGE),
            CAPABILITIES,
            state,
            newDetail,
            Instant.now());
    ModuleRegistry.RegistrationResult result = core.modules().register(descriptor);
    ModuleDescriptor registered = result.descriptor();
    ownsRegistration = registered != null && registered.plugin() == plugin;
    registrationState = registered == null ? "NOT_REGISTERED" : registered.state().name();
    detail = result.message();
    if (!result.success() && !ownsRegistration) {
      plugin.getLogger().warning("PlexonCore module registration rejected: " + result.message());
    }
  }

  @Override
  public void markReady(String detail) {
    update(ModuleState.READY, IntegrationState.READY, detail);
  }

  @Override
  public void markDegraded(String detail) {
    update(ModuleState.DEGRADED, IntegrationState.DEGRADED, detail);
  }

  @Override
  public void markFailed(String detail) {
    update(ModuleState.FAILED, IntegrationState.FAILED, detail);
  }

  private void update(ModuleState moduleState, IntegrationState integrationState, String newDetail) {
    if (!compatible) {
      return;
    }
    if (!ownsRegistration || core.modules().find(MODULE_ID).filter(d -> d.plugin() == plugin).isEmpty()) {
      register(moduleState, newDetail);
    } else {
      core.modules().updateState(MODULE_ID, moduleState, newDetail);
    }
    if (!ownsRegistration) {
      return;
    }
    core.integrations()
        .publish(
            "PLEXON_PANEL",
            plugin.getName(),
            plugin.getPluginMeta().getVersion(),
            integrationState,
            CAPABILITIES,
            newDetail);
    registrationState = moduleState.name();
    detail = newDetail == null ? "" : newDetail;
  }

  @Override
  public void unregister() {
    if (!ownsRegistration) {
      return;
    }
    core.modules()
        .find(MODULE_ID)
        .filter(descriptor -> descriptor.plugin() == plugin)
        .ifPresent(descriptor -> core.modules().unregister(MODULE_ID));
    if (compatible) {
      core.integrations()
          .publish(
              "PLEXON_PANEL",
              plugin.getName(),
              plugin.getPluginMeta().getVersion(),
              IntegrationState.DEGRADED,
              CAPABILITIES,
              "PlexonPanel is disabled");
    }
    ownsRegistration = false;
    registrationState = "UNREGISTERED";
  }
}
