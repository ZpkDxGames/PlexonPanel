package io.github.zpkdxgames.plexonpanel.host;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MaintenanceSettingsTest {
  @TempDir Path temporary;

  @Test
  void missingSettingsUseSafeManualOnlyMigrationDefaults() throws Exception {
    var settings = MaintenanceSettings.load(temporary.resolve("maintenance.json"));
    assertEquals(1, settings.schemaVersion());
    assertEquals("America/Sao_Paulo", settings.timezone());
    assertFalse(settings.restart().schedule().enabled());
    assertTrue(settings.fullRestorePoint().restartAfter());
    assertEquals("SINGLE_CURRENT", settings.fullRestorePoint().retentionMode());
  }

  @Test
  void savedSettingsRoundTripAndRemainHostOwned() throws Exception {
    Path path = temporary.resolve("state/maintenance-settings.json");
    var defaults = MaintenanceSettings.migratedDefaults();
    var configured =
        new MaintenanceSettings(
            defaults.schemaVersion(),
            "Europe/Berlin",
            new MaintenanceSettings.Restart(
                new MaintenanceSettings.Schedule(true, "DAILY", List.of(), "04:15"),
                List.of(300, 60, 10),
                120,
                240),
            new MaintenanceSettings.FullRestorePoint(
                "ROTATING",
                4,
                true,
                "PlexonCraft-Latest.zip",
                900,
                "SIZE_AND_HASH_WHEN_AVAILABLE",
                8L * 1024 * 1024 * 1024,
                List.of("logs", "cache")));

    configured.save(path);
    assertTrue(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS));
    var loaded = MaintenanceSettings.load(path);
    assertEquals(configured, loaded);
    assertEquals("Europe/Berlin", loaded.timezone());
  }

  @Test
  void legacyBackupSchedulesAreIgnoredAndNotReserialized() throws Exception {
    Path path = temporary.resolve("maintenance.json");
    Files.writeString(
        path,
        """
        {
          "schemaVersion": 1,
          "timezone": "America/Sao_Paulo",
          "restart": {
            "schedule": {"enabled": false, "type": "DAILY", "weekdays": [], "time": "04:00"},
            "warningSeconds": [60, 10],
            "stopTimeoutSeconds": 180,
            "startupTimeoutSeconds": 180
          },
          "fullRestorePoint": {
            "schedule": {"enabled": true, "type": "WEEKLY", "weekdays": ["SUNDAY"], "time": "04:00"},
            "retentionMode": "SINGLE_CURRENT",
            "retentionCount": 1,
            "restartAfter": true,
            "canonicalFilename": "PlexonCraft-Latest.zip",
            "uploadTimeoutSeconds": 1800,
            "verificationMode": "SIZE_AND_HASH_WHEN_AVAILABLE",
            "maximumBytes": 1099511627776,
            "excludes": ["logs"]
          },
          "liveSnapshot": {
            "schedule": {"enabled": true, "type": "DAILY", "weekdays": [], "time": "03:00"}
          }
        }
        """);

    var loaded = MaintenanceSettings.load(path);
    assertNotNull(loaded);
    assertFalse(loaded.restart().schedule().enabled());
    assertEquals("SINGLE_CURRENT", loaded.fullRestorePoint().retentionMode());

    loaded.save(path);
    JsonObject saved = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
    assertFalse(saved.has("liveSnapshot"));
    assertFalse(saved.getAsJsonObject("fullRestorePoint").has("schedule"));
  }

  @Test
  void legacyRestartAfterFalseIsNormalizedToAutomaticRestart() throws Exception {
    Path path = temporary.resolve("legacy-restart-after.json");
    Files.writeString(
        path,
        """
        {
          "schemaVersion": 1,
          "timezone": "America/Sao_Paulo",
          "restart": {
            "schedule": {"enabled": false, "type": "DAILY", "weekdays": [], "time": "04:00"},
            "warningSeconds": [60, 10],
            "stopTimeoutSeconds": 180,
            "startupTimeoutSeconds": 180
          },
          "fullRestorePoint": {
            "retentionMode": "SINGLE_CURRENT",
            "retentionCount": 1,
            "restartAfter": false,
            "canonicalFilename": "PlexonCraft-Latest.zip",
            "uploadTimeoutSeconds": 1800,
            "verificationMode": "SIZE_AND_HASH_WHEN_AVAILABLE",
            "maximumBytes": 1099511627776,
            "excludes": ["logs"]
          }
        }
        """);

    var loaded = MaintenanceSettings.load(path);
    assertTrue(loaded.fullRestorePoint().restartAfter());

    var direct =
        new MaintenanceSettings.FullRestorePoint(
            "SINGLE_CURRENT",
            1,
            false,
            "PlexonCraft-Latest.zip",
            1800,
            "SIZE_AND_HASH_WHEN_AVAILABLE",
            1_099_511_627_776L,
            List.of("logs"));
    assertTrue(direct.restartAfter());

    loaded.save(path);
    JsonObject saved = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
    assertTrue(saved.getAsJsonObject("fullRestorePoint").get("restartAfter").getAsBoolean());
  }

  @Test
  void invalidTimezoneAndExclusionFailClosed() {
    var base = MaintenanceSettings.migratedDefaults();
    assertThrows(
        RuntimeException.class,
        () ->
            MaintenanceSettings.fromJson(
                JsonParser.parseString(
                    "{\"schemaVersion\":1,\"timezone\":\"Not/A_Zone\",\"restart\":{},\"fullRestorePoint\":{}}")));

    var traversalExclude =
        new MaintenanceSettings(
            1,
            base.timezone(),
            base.restart(),
            new MaintenanceSettings.FullRestorePoint(
                "SINGLE_CURRENT",
                1,
                true,
                "PlexonCraft-Latest.zip",
                1800,
                "SIZE_AND_HASH_WHEN_AVAILABLE",
                1024L * 1024 * 1024,
                List.of("../secrets")));
    assertThrows(IllegalArgumentException.class, () -> MaintenanceSettings.validate(traversalExclude));
  }

  @Test
  void invalidSettingsFileTypeAndOversizeAreRejected() throws Exception {
    Path directory = Files.createDirectory(temporary.resolve("not-a-file"));
    assertThrows(Exception.class, () -> MaintenanceSettings.load(directory));

    Path huge = temporary.resolve("huge.json");
    Files.writeString(huge, "x".repeat(70_000));
    assertThrows(Exception.class, () -> MaintenanceSettings.load(huge));
  }
}
