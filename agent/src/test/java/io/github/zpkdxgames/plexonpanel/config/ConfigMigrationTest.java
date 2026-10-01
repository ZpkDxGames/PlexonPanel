package io.github.zpkdxgames.plexonpanel.config;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigMigrationTest {
  @TempDir Path root;

  @Test
  void migratesLegacyConfigAtomicallyWithoutDroppingOperatorData() throws Exception {
    Path configPath = root.resolve("config.yml");
    String legacy = "gateway:\n  enabled: false\noperator-note: keep-me\n";
    Files.writeString(configPath, legacy);
    YamlConfiguration config = new YamlConfiguration();
    config.loadFromString(legacy);

    ConfigMigration.migrate(config, configPath);

    YamlConfiguration migrated = YamlConfiguration.loadConfiguration(configPath.toFile());
    assertEquals(4, migrated.getInt("schema-version"));
    assertEquals("keep-me", migrated.getString("operator-note"));
    assertEquals(legacy, Files.readString(root.resolve("config.yml.pre-v4-backup")));
  }

  @Test
  void refusesFutureSchemaWithoutRewritingIt() throws Exception {
    Path configPath = root.resolve("config.yml");
    String future = "schema-version: 99\noperator-note: untouched\n";
    Files.writeString(configPath, future);
    YamlConfiguration config = new YamlConfiguration();
    config.loadFromString(future);

    assertThrows(IllegalArgumentException.class, () -> ConfigMigration.migrate(config, configPath));
    assertEquals(future, Files.readString(configPath));
    assertFalse(Files.exists(root.resolve("config.yml.pre-v4-backup")));
  }
}
