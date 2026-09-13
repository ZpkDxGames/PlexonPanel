package io.github.zpkdxgames.plexonpanel.host;

import static org.junit.jupiter.api.Assertions.*;

import java.time.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class MaintenanceScheduleTest {
  @Test
  void dailyScheduleUsesConfiguredTimezone() {
    var schedule = new MaintenanceSettings.Schedule(true, "DAILY", List.of(), "04:00");
    ZoneId zone = ZoneId.of("America/Sao_Paulo");
    Instant after = ZonedDateTime.of(2026, 9, 13, 3, 59, 59, 0, zone).toInstant();
    Instant next = MaintenanceSchedule.next(schedule, zone, after);
    assertEquals(ZonedDateTime.of(2026, 9, 13, 4, 0, 0, 0, zone).toInstant(), next);
  }

  @Test
  void weeklySundayDoesNotUseFixedMinuteIntervals() {
    var schedule =
        new MaintenanceSettings.Schedule(true, "WEEKLY", List.of("SUNDAY"), "04:00");
    ZoneId zone = ZoneId.of("America/Sao_Paulo");
    Instant after = ZonedDateTime.of(2026, 9, 13, 4, 0, 1, 0, zone).toInstant();
    Instant next = MaintenanceSchedule.next(schedule, zone, after);
    assertEquals(ZonedDateTime.of(2026, 9, 20, 4, 0, 0, 0, zone).toInstant(), next);
  }

  @Test
  void selectedWeekdaysFindNextMatchingLocalDate() {
    var schedule =
        new MaintenanceSettings.Schedule(
            true, "SELECTED_WEEKDAYS", List.of("MONDAY", "WEDNESDAY"), "02:30");
    ZoneId zone = ZoneId.of("Europe/Berlin");
    Instant after = ZonedDateTime.of(2026, 3, 29, 12, 0, 0, 0, zone).toInstant();
    Instant next = MaintenanceSchedule.next(schedule, zone, after);
    assertEquals(DayOfWeek.MONDAY, next.atZone(zone).getDayOfWeek());
    assertEquals(LocalTime.of(2, 30), next.atZone(zone).toLocalTime());
  }

  @Test
  void migratedDefaultsDoNotEnableDestructiveSchedules() {
    var settings = MaintenanceSettings.migratedDefaults();
    assertFalse(settings.restart().schedule().enabled());
    assertFalse(settings.fullRestorePoint().schedule().enabled());
    assertEquals("America/Sao_Paulo", settings.timezone());
  }

  @Test
  void occurrenceKeysAreStableAndDistinct() {
    Instant one = Instant.parse("2026-09-13T07:00:00Z");
    Instant two = one.plus(Duration.ofDays(1));
    assertEquals(
        MaintenanceSchedule.occurrenceKey("restart", one),
        MaintenanceSchedule.occurrenceKey("restart", one));
    assertNotEquals(
        MaintenanceSchedule.occurrenceKey("restart", one),
        MaintenanceSchedule.occurrenceKey("restart", two));
  }
}
