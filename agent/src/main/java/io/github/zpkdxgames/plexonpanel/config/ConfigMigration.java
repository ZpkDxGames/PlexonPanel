package io.github.zpkdxgames.plexonpanel.config;

import io.github.zpkdxgames.plexonpanel.util.AtomicFiles;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/** Safe, one-way migration for the operator-owned Paper configuration. */
public final class ConfigMigration {
  private ConfigMigration() {}

  public static void migrate(JavaPlugin plugin) throws IOException {
    Path configPath = plugin.getDataFolder().toPath().resolve("config.yml");
    migrate(plugin.getConfig(), configPath);
  }

  static void migrate(FileConfiguration config, Path configPath) throws IOException {
    if (!Files.isRegularFile(configPath, LinkOption.NOFOLLOW_LINKS)
        || Files.isSymbolicLink(configPath)) {
      throw new IOException("config.yml must be a regular non-symlink file");
    }

    int schemaVersion = config.getInt("schema-version", 0);
    if (schemaVersion < 0 || schemaVersion > PanelSettings.CURRENT_SCHEMA_VERSION) {
      throw new IllegalArgumentException(
          "Unsupported config.yml schema-version " + schemaVersion);
    }

    config.options().copyDefaults(true);
    boolean needsMigration = schemaVersion < PanelSettings.CURRENT_SCHEMA_VERSION;
    if (!needsMigration) return;

    Path backup = configPath.resolveSibling("config.yml.pre-v4-backup");
    if (!Files.exists(backup, LinkOption.NOFOLLOW_LINKS)) {
      Files.copy(configPath, backup, StandardCopyOption.COPY_ATTRIBUTES);
    } else if (!Files.isRegularFile(backup, LinkOption.NOFOLLOW_LINKS)
        || Files.isSymbolicLink(backup)) {
      throw new IOException("Refusing to replace an unsafe config.yml migration backup");
    }

    config.set("schema-version", PanelSettings.CURRENT_SCHEMA_VERSION);
    AtomicFiles.writeUtf8(configPath, config.saveToString());
  }
}
