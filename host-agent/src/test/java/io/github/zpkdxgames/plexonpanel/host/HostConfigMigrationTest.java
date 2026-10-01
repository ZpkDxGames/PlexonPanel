package io.github.zpkdxgames.plexonpanel.host;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HostConfigMigrationTest {
  @TempDir Path root;

  @Test
  void addsSchemaAndPreservesIdentityAndUnknownOperatorData() throws Exception {
    Path config = root.resolve("host-config.json");
    String legacy =
        "{\"serverId\":\"2f793435-6c3c-4f53-8e5b-b36b9c9db725\","
            + "\"operatorNote\":{\"keep\":true}}\n";
    Files.writeString(config, legacy);
    Set<PosixFilePermission> mode =
        Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.GROUP_READ);
    Files.setPosixFilePermissions(config, mode);

    HostConfigMigration.migrate(config);

    JsonObject migrated = JsonParser.parseString(Files.readString(config)).getAsJsonObject();
    assertEquals(4, migrated.get("schemaVersion").getAsInt());
    assertEquals(
        "2f793435-6c3c-4f53-8e5b-b36b9c9db725", migrated.get("serverId").getAsString());
    assertTrue(migrated.getAsJsonObject("operatorNote").get("keep").getAsBoolean());
    assertEquals(legacy, Files.readString(root.resolve("host-config.json.pre-v4-backup")));
    assertEquals(mode, Files.getPosixFilePermissions(config));
    assertFalse(HostConfigMigration.needsMigration(config));
  }

  @Test
  void refusesFutureSchemaWithoutWritingBackup() throws Exception {
    Path config = root.resolve("host-config.json");
    String future = "{\"schemaVersion\":99,\"operatorNote\":\"untouched\"}\n";
    Files.writeString(config, future);

    assertThrows(IllegalArgumentException.class, () -> HostConfigMigration.migrate(config));
    assertEquals(future, Files.readString(config));
    assertFalse(Files.exists(root.resolve("host-config.json.pre-v4-backup")));
  }

  @Test
  void refusesNonNumericSchemaWithoutRewritingIt() throws Exception {
    Path config = root.resolve("host-config.json");
    String invalid = "{\"schemaVersion\":\"4\",\"operatorNote\":\"untouched\"}\n";
    Files.writeString(config, invalid);

    assertThrows(IllegalArgumentException.class, () -> HostConfigMigration.migrate(config));
    assertEquals(invalid, Files.readString(config));
    assertFalse(Files.exists(root.resolve("host-config.json.pre-v4-backup")));
  }
}
