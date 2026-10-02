package io.github.zpkdxgames.plexonpanel.config;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigMigrationTest {
  @TempDir Path root;

  private YamlConfiguration withBundledDefaults(String yaml, boolean copyDefaults)
      throws Exception {
    YamlConfiguration config = new YamlConfiguration();
    config.loadFromString(yaml);
    try (var resource = getClass().getResourceAsStream("/config.yml")) {
      assertNotNull(resource, "The regression must use the real packaged defaults");
      config.setDefaults(
          YamlConfiguration.loadConfiguration(
              new InputStreamReader(resource, StandardCharsets.UTF_8)));
    }
    config.options().copyDefaults(copyDefaults);
    return config;
  }

  @Test
  void missingPersistedMarkerMigratesDespiteRealBundledDefaults() throws Exception {
    Path configPath = root.resolve("config.yml");
    String legacy = "gateway:\n  enabled: false\noperator:\n  nested: keep-me\n";
    Files.writeString(configPath, legacy);
    YamlConfiguration config = withBundledDefaults(legacy, false);
    assertEquals(4, config.getInt("schema-version"));
    assertNull(config.get("schema-version", null));

    ConfigMigration.migrate(config, configPath);

    YamlConfiguration persisted = YamlConfiguration.loadConfiguration(configPath.toFile());
    assertEquals(4, persisted.getInt("schema-version", 0));
    assertEquals("keep-me", persisted.getString("operator.nested"));
    assertFalse(persisted.getBoolean("gateway.enabled"));
    assertEquals(legacy, Files.readString(root.resolve("config.yml.pre-v4-backup")));
    assertDoesNotThrow(() -> PanelSettings.load(persisted));
  }

  @Test
  void copiedDefaultsDoNotMaskMissingPersistedMarker() throws Exception {
    Path configPath = root.resolve("config.yml");
    String legacy = "operator-note: keep-me\n";
    Files.writeString(configPath, legacy);
    YamlConfiguration config = withBundledDefaults(legacy, true);
    assertEquals(4, config.getInt("schema-version"));
    assertNull(config.get("schema-version", null));

    ConfigMigration.migrate(config, configPath);

    assertEquals(4, YamlConfiguration.loadConfiguration(configPath.toFile()).getInt("schema-version", 0));
    assertEquals(legacy, Files.readString(root.resolve("config.yml.pre-v4-backup")));
  }

  @Test
  void restartIsIdempotentAndRetainsTheOriginalBackup() throws Exception {
    Path configPath = root.resolve("config.yml");
    String legacy = "schema-version: 0\noperator-note: first-copy\n";
    Files.writeString(configPath, legacy);
    ConfigMigration.migrate(withBundledDefaults(legacy, false), configPath);
    String migrated = Files.readString(configPath);
    YamlConfiguration restarted = withBundledDefaults(migrated, true);

    ConfigMigration.migrate(restarted, configPath, (path, contents) -> fail("Must not rewrite a current config"));

    assertEquals(migrated, Files.readString(configPath));
    assertEquals(legacy, Files.readString(root.resolve("config.yml.pre-v4-backup")));
  }

  @Test
  void failedAtomicPublicationPreservesDiskBackupAndAllowsRetry() throws Exception {
    Path configPath = root.resolve("config.yml");
    String legacy = "operator-note: survive-failure\n";
    Files.writeString(configPath, legacy);
    YamlConfiguration config = withBundledDefaults(legacy, true);
    AtomicBoolean attempted = new AtomicBoolean();

    assertThrows(IOException.class, () -> ConfigMigration.migrate(config, configPath, (path, contents) -> {
      attempted.set(true);
      throw new IOException("Simulated failure before atomic replacement");
    }));

    assertTrue(attempted.get());
    assertEquals(legacy, Files.readString(configPath));
    assertEquals(legacy, Files.readString(root.resolve("config.yml.pre-v4-backup")));
    assertNull(config.get("schema-version", null));
    assertEquals("survive-failure", config.getString("operator-note"));
    ConfigMigration.migrate(config, configPath);
    assertEquals(4, YamlConfiguration.loadConfiguration(configPath.toFile()).getInt("schema-version", 0));
    assertEquals(legacy, Files.readString(root.resolve("config.yml.pre-v4-backup")));
  }

  @Test
  void refusesSymlinkBackupWithoutChangingConfigOrItsTarget() throws Exception {
    Path configPath = root.resolve("config.yml");
    String legacy = "operator-note: untouched\n";
    Files.writeString(configPath, legacy);
    Path target = root.resolve("unrelated.yml");
    Files.writeString(target, "untouched-target\n");
    Files.createSymbolicLink(root.resolve("config.yml.pre-v4-backup"), target);

    assertThrows(IOException.class, () -> ConfigMigration.migrate(withBundledDefaults(legacy, true), configPath));

    assertEquals(legacy, Files.readString(configPath));
    assertEquals("untouched-target\n", Files.readString(target));
  }

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

  @Test
  void refusesNonIntegerSchemaWithoutRewritingIt() throws Exception {
    Path configPath = root.resolve("config.yml");
    String malformed = "schema-version: 3.5\noperator-note: untouched\n";
    Files.writeString(configPath, malformed);
    YamlConfiguration config = new YamlConfiguration();
    config.loadFromString(malformed);

    assertThrows(IllegalArgumentException.class, () -> ConfigMigration.migrate(config, configPath));
    assertEquals(malformed, Files.readString(configPath));
    assertFalse(Files.exists(root.resolve("config.yml.pre-v4-backup")));
  }
}
