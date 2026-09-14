package io.github.zpkdxgames.plexonpanel.host;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Method;
import java.time.DayOfWeek;
import java.util.List;
import org.junit.jupiter.api.Test;

class ManualOnlyBackupMigrationTest {
  @Test
  void migrationDisablesFullBackupScheduleButPreservesRestartSchedule() throws Exception {
    MaintenanceSettings.Schedule restartSchedule =
        new MaintenanceSettings.Schedule(true, "DAILY", List.of(), "05:30");
    MaintenanceSettings.Schedule legacyFullSchedule =
        new MaintenanceSettings.Schedule(
            true, "WEEKLY", List.of(DayOfWeek.SATURDAY.name()), "04:00");
    MaintenanceSettings input =
        new MaintenanceSettings(
            1,
            "America/Sao_Paulo",
            new MaintenanceSettings.Restart(
                restartSchedule, List.of(900, 300, 60, 30, 10), 180, 180),
            new MaintenanceSettings.FullRestorePoint(
                legacyFullSchedule,
                "SINGLE_CURRENT",
                1,
                true,
                "PlexonCraft-Latest.zip",
                1800,
                "SIZE_AND_HASH_WHEN_AVAILABLE",
                10_000_000L,
                List.of("logs")));

    Method migration =
        MaintenanceManager.class.getDeclaredMethod("manualOnly", MaintenanceSettings.class);
    migration.setAccessible(true);
    MaintenanceSettings migrated =
        (MaintenanceSettings) migration.invoke(null, input);

    assertEquals(restartSchedule, migrated.restart().schedule());
    assertFalse(migrated.fullRestorePoint().schedule().enabled());
    assertEquals("WEEKLY", migrated.fullRestorePoint().schedule().type());
    assertEquals("04:00", migrated.fullRestorePoint().schedule().time());
    assertEquals("SINGLE_CURRENT", migrated.fullRestorePoint().retentionMode());
    assertTrue(migrated.fullRestorePoint().restartAfter());
  }
}
