package io.github.zpkdxgames.plexonpanel.config;

import io.github.zpkdxgames.plexonpanel.util.AtomicFiles;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/** Safe, one-way migration for the operator-owned Paper configuration. */
public final class ConfigMigration {
  // The defaults-aware legacy correction remains a distinct step before fleet migration.
  private static final int LEGACY_SCHEMA_VERSION = 4;
  private ConfigMigration() {}

  public static void migrate(JavaPlugin plugin) throws IOException {
    Path configPath = plugin.getDataFolder().toPath().resolve("config.yml");
    migrate(plugin.getConfig(), configPath);
  }

  static void migrate(FileConfiguration config, Path configPath) throws IOException {
    migrate(config, configPath, AtomicFiles::writeUtf8);
  }

  @FunctionalInterface
  interface ConfigWriter {
    void write(Path destination, String content) throws IOException;
  }

  static void migrate(FileConfiguration config, Path configPath, ConfigWriter writer)
      throws IOException {
    if (!Files.isRegularFile(configPath, LinkOption.NOFOLLOW_LINKS)
        || Files.isSymbolicLink(configPath)) {
      throw new IOException("config.yml must be a regular non-symlink file");
    }

    // The explicit-null overload bypasses attached Bukkit defaults, even when
    // copyDefaults is enabled. A bundled marker is not a persisted disk marker.
    Object persistedMarker = config.get("schema-version", null);
    int schemaVersion = schemaVersion(persistedMarker);
    if (schemaVersion < 0 || schemaVersion > 5) {
      throw new IllegalArgumentException(
          "Unsupported config.yml schema-version " + schemaVersion);
    }

    config.options().copyDefaults(true);
    boolean needsMigration = schemaVersion < LEGACY_SCHEMA_VERSION;
    if (!needsMigration) return;

    Path backup = configPath.resolveSibling("config.yml.pre-v4-backup");
    if (!Files.exists(backup, LinkOption.NOFOLLOW_LINKS)) {
      Files.copy(configPath, backup, StandardCopyOption.COPY_ATTRIBUTES);
    } else if (!Files.isRegularFile(backup, LinkOption.NOFOLLOW_LINKS)
        || Files.isSymbolicLink(backup)) {
      throw new IOException("Refusing to replace an unsafe config.yml migration backup");
    }

    config.set("schema-version", LEGACY_SCHEMA_VERSION);
    try {
      writer.write(configPath, config.saveToString());
    } catch (IOException | RuntimeException failure) {
      // A failed publication must not make this in-memory object appear migrated
      // on a retry. The original disk file and the first backup remain authoritative.
      config.set("schema-version", persistedMarker);
      throw failure;
    }
  }

  private static int schemaVersion(Object raw) {
    if (raw == null) return 0;
    if (!(raw instanceof Number number))
      throw new IllegalArgumentException("config.yml schema-version must be an integer");
    try {
      return new BigDecimal(number.toString()).intValueExact();
    } catch (ArithmeticException | NumberFormatException error) {
      throw new IllegalArgumentException("config.yml schema-version must be an integer", error);
    }
  }
}
