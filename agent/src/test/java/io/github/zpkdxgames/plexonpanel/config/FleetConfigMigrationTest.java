package io.github.zpkdxgames.plexonpanel.config;
import static org.junit.jupiter.api.Assertions.*;
import io.github.zpkdxgames.plexonpanel.identity.FleetIdentity;
import java.io.*;
import java.nio.file.*;
import java.util.UUID;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FleetConfigMigrationTest {
  @TempDir Path root;
  private YamlConfiguration defaults() {
    return YamlConfiguration.loadConfiguration(new InputStreamReader(getClass().getResourceAsStream("/config.yml"), java.nio.charset.StandardCharsets.UTF_8));
  }
  private FleetIdentity identity() { return new FleetIdentity(UUID.randomUUID(), UUID.randomUUID(), "plexoncraft", "PlexonCraft"); }
  @Test void unmarkedDiskWithRealBundledDefaultsPlansWithoutWritesThenMigratesAndRestartsIdempotently() throws Exception {
    Path path = root.resolve("config.yml"); String original = "gateway:\n  enabled: false\noperator:\n  nested: preserve-me\n";
    Files.writeString(path, original); var defaults = defaults(); var identity = identity();
    var plan = FleetConfigMigration.plan(defaults, path, identity);
    assertEquals(0, plan.sourceSchema()); assertTrue(plan.changesRequired()); assertEquals(original, Files.readString(path));
    assertFalse(Files.exists(root.resolve("config.yml.pre-v5-backup")));
    FleetConfigMigration.migrate(defaults, path, identity);
    var persisted = YamlConfiguration.loadConfiguration(path.toFile()); assertEquals(5, persisted.getInt("schema-version"));
    assertEquals("preserve-me", persisted.getString("operator.nested")); assertEquals(identity.nodeId(), PanelSettings.load(persisted).fleet().nodeId());
    assertEquals(original, Files.readString(root.resolve("config.yml.pre-v5-backup")));
    String migrated = Files.readString(path);
    FleetConfigMigration.migrate(defaults, path, identity, (p, text) -> fail("Idempotent migration must not publish again"));
    assertEquals(migrated, Files.readString(path));
    ConfigMigration.migrate(persisted, path); assertEquals(migrated, Files.readString(path));
  }
  @Test void failedWritePreservesMarkerCustomFieldsAndFirstBackupThenRetries() throws Exception {
    Path path = root.resolve("config.yml"); String original = "schema-version: 4\noperator-note: preserve-me\n"; Files.writeString(path, original);
    var defaults = defaults(); var identity = identity();
    assertThrows(IOException.class, () -> FleetConfigMigration.migrate(defaults, path, identity, (p, text) -> { throw new IOException("SIMULATED_PUBLICATION_FAILURE"); }));
    assertEquals(original, Files.readString(path)); assertEquals(4, defaults.getInt("schema-version"));
    FleetConfigMigration.migrate(defaults, path, identity);
    assertEquals(original, Files.readString(root.resolve("config.yml.pre-v5-backup")));
  }
  @Test void unsafeBackupBindingChangesFractionalAndFutureMarkersFailWithoutPublication() throws Exception {
    Path path = root.resolve("config.yml"); Files.writeString(path, "schema-version: 4\n"); var defaults = defaults(); var identity = identity();
    Path backup = root.resolve("config.yml.pre-v5-backup"); Files.createSymbolicLink(backup, root.resolve("missing"));
    assertThrows(IOException.class, () -> FleetConfigMigration.migrate(defaults, path, identity)); Files.delete(backup);
    FleetConfigMigration.migrate(defaults, path, identity);
    var replaced = new FleetIdentity(identity.serverId(), UUID.randomUUID(), "plexoncraft", "Changed node");
    assertThrows(IOException.class, () -> FleetConfigMigration.plan(defaults, path, replaced));
    Files.writeString(path, "schema-version: 5.1\n"); assertThrows(IOException.class, () -> FleetConfigMigration.plan(defaults, path, identity));
    Files.writeString(path, "schema-version: 99\n"); assertThrows(IOException.class, () -> FleetConfigMigration.plan(defaults, path, identity));
  }
}
