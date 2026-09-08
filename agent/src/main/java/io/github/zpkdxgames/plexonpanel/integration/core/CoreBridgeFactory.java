package io.github.zpkdxgames.plexonpanel.integration.core;

import java.lang.reflect.InvocationTargetException;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

public final class CoreBridgeFactory {
  private static final String CORE_PLUGIN = "PlexonCore";
  private static final String CORE_BRIDGE_CLASS =
      "io.github.zpkdxgames.plexonpanel.integration.core.PlexonCoreBridge";

  private CoreBridgeFactory() {}

  public static CoreBridge resolve(JavaPlugin plugin) {
    Plugin corePlugin = Bukkit.getPluginManager().getPlugin(CORE_PLUGIN);
    if (corePlugin == null) {
      plugin.getLogger().info("PlexonCore not installed; starting PlexonPanel in standalone mode.");
      return new StandaloneCoreBridge(false, "-", "-", "PlexonCore is not installed");
    }

    String version = corePlugin.getPluginMeta().getVersion();
    if (!corePlugin.isEnabled()) {
      plugin.getLogger().warning("PlexonCore is installed but disabled; starting PlexonPanel standalone.");
      return new StandaloneCoreBridge(true, version, "-", "PlexonCore is installed but disabled");
    }

    try {
      Class<?> type = Class.forName(CORE_BRIDGE_CLASS, true, CoreBridgeFactory.class.getClassLoader());
      CoreBridge bridge = (CoreBridge) type.getConstructor(JavaPlugin.class).newInstance(plugin);
      if (!bridge.compatible()) {
        plugin
            .getLogger()
            .warning(
                "PlexonCore API "
                    + bridge.apiVersion()
                    + " is outside supported range "
                    + CoreBridge.SUPPORTED_API_RANGE
                    + "; using standalone compatibility mode.");
      }
      return bridge;
    } catch (InvocationTargetException exception) {
      Throwable cause = exception.getCause() == null ? exception : exception.getCause();
      plugin
          .getLogger()
          .log(
              Level.WARNING,
              "PlexonCore is present but its API could not be resolved; using standalone compatibility mode.",
              cause);
      return new StandaloneCoreBridge(true, version, "-", "PlexonCore API service is unavailable");
    } catch (ReflectiveOperationException | LinkageError | RuntimeException exception) {
      plugin
          .getLogger()
          .log(
              Level.WARNING,
              "PlexonCore is present but could not be linked safely; using standalone compatibility mode.",
              exception);
      return new StandaloneCoreBridge(true, version, "-", "PlexonCore API linkage is unavailable");
    }
  }
}
