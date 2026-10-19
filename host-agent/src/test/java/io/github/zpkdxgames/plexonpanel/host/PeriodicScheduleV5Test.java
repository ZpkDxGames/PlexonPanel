package io.github.zpkdxgames.plexonpanel.host;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class PeriodicScheduleV5Test {
  @Test
  void intervalsHaveStableUtcAnchorsAcrossTimezones() {
    var s = new MaintenanceSettings.Schedule(true, "INTERVAL_HOURS", List.of(), "04:00", 6);
    Instant after = Instant.parse("2026-10-18T05:59:59Z");
    assertEquals(
        Instant.parse("2026-10-18T06:00:00Z"),
        MaintenanceSchedule.next(s, ZoneId.of("America/Sao_Paulo"), after));
    assertEquals(
        MaintenanceSchedule.next(s, ZoneId.of("Asia/Tokyo"), after),
        MaintenanceSchedule.next(s, ZoneOffset.UTC, after));
    assertEquals(
        Instant.parse("2026-10-18T12:00:00Z"),
        MaintenanceSchedule.next(s, ZoneOffset.UTC, Instant.parse("2026-10-18T06:00:00Z")));
  }

  @Test
  void selectedDaysUseExistingCalendarSemantics() {
    var s =
        new MaintenanceSettings.Schedule(
            true, "SELECTED_DAYS", List.of("MONDAY", "FRIDAY"), "04:00");
    assertEquals(
        Instant.parse("2026-10-19T04:00:00Z"),
        MaintenanceSchedule.next(s, ZoneOffset.UTC, Instant.parse("2026-10-18T12:00:00Z")));
  }

  @Test
  void legacySchedulesMigrateDisabledButV2OptInSurvives() {
    var gson = new Gson();
    JsonObject json = gson.toJsonTree(MaintenanceSettings.migratedDefaults()).getAsJsonObject();
    json.getAsJsonObject("fullRestorePoint")
        .getAsJsonObject("schedule")
        .addProperty("enabled", true);
    json.addProperty("schemaVersion", 1);
    assertFalse(MaintenanceSettings.fromJson(json).fullRestorePoint().schedule().enabled());
    json.addProperty("schemaVersion", 2);
    assertTrue(MaintenanceSettings.fromJson(json).fullRestorePoint().schedule().enabled());
  }

  @Test
  void intervalsOutsideOneThrough168HoursFailClosed() {
    for (int hours : List.of(0, 169)) {
      var json = new Gson().toJsonTree(MaintenanceSettings.migratedDefaults()).getAsJsonObject();
      var s = json.getAsJsonObject("fullRestorePoint").getAsJsonObject("schedule");
      s.addProperty("type", "INTERVAL_HOURS");
      s.addProperty("intervalHours", hours);
      assertThrows(IllegalArgumentException.class, () -> MaintenanceSettings.fromJson(json));
    }
  }
}
