package io.github.zpkdxgames.plexonpanel.host;

import java.time.*;
import java.util.*;

/** Calendar-aware recurrence calculation. Recompute after every claimed occurrence. */
public final class MaintenanceSchedule {
  private MaintenanceSchedule() {}

  public static Instant next(
      MaintenanceSettings.Schedule schedule, ZoneId zone, Instant strictlyAfter) {
    Objects.requireNonNull(schedule, "schedule");
    Objects.requireNonNull(zone, "zone");
    Objects.requireNonNull(strictlyAfter, "strictlyAfter");
    if (!schedule.enabled()) return null;
    LocalTime time = LocalTime.parse(schedule.time());
    ZonedDateTime cursor = strictlyAfter.atZone(zone);
    LocalDate start = cursor.toLocalDate();
    for (int offset = 0; offset <= 14; offset++) {
      LocalDate date = start.plusDays(offset);
      if (!matches(schedule, date.getDayOfWeek())) continue;
      ZonedDateTime candidate = ZonedDateTime.of(date, time, zone);
      Instant instant = candidate.toInstant();
      if (instant.isAfter(strictlyAfter)) return instant;
    }
    throw new IllegalStateException("Unable to calculate next maintenance occurrence");
  }

  public static String occurrenceKey(String scheduleId, Instant occurrence) {
    if (scheduleId == null || !scheduleId.matches("[A-Za-z0-9_.-]{1,64}"))
      throw new IllegalArgumentException("Invalid schedule id");
    return scheduleId + ":" + occurrence.toString();
  }

  static boolean matches(MaintenanceSettings.Schedule schedule, DayOfWeek day) {
    return switch (schedule.type()) {
      case "DAILY" -> true;
      case "WEEKLY", "SELECTED_WEEKDAYS" -> schedule.weekdays().contains(day.name());
      default -> throw new IllegalArgumentException("Unsupported schedule type");
    };
  }
}
