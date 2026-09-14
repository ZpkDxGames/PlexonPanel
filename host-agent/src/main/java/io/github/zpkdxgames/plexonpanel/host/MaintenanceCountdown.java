package io.github.zpkdxgames.plexonpanel.host;

import java.time.*;
import java.util.*;

/** Pure countdown planner so warning timing and restart recovery can be tested with a fake clock. */
final class MaintenanceCountdown {
  static final List<Integer> FULL_BACKUP_WARNINGS = List.of(1800, 900, 60, 30, 15, 5);
  private static final Duration SEND_TOLERANCE = Duration.ofSeconds(2);

  enum Action {
    SEND,
    SKIP,
    WAIT,
    DONE
  }

  record Decision(Action action, int warningSeconds, Instant target) {}

  private final Clock clock;

  MaintenanceCountdown(Clock clock) {
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  Decision next(Instant deadline, List<Integer> warnings, Set<Integer> consumedWarnings) {
    Objects.requireNonNull(deadline, "deadline");
    Objects.requireNonNull(warnings, "warnings");
    Objects.requireNonNull(consumedWarnings, "consumedWarnings");
    Instant now = clock.instant();
    for (int warning : normalized(warnings)) {
      if (consumedWarnings.contains(warning)) continue;
      Instant target = deadline.minusSeconds(warning);
      if (now.isBefore(target)) return new Decision(Action.WAIT, warning, target);
      if (!now.isAfter(target.plus(SEND_TOLERANCE)))
        return new Decision(Action.SEND, warning, target);
      return new Decision(Action.SKIP, warning, target);
    }
    if (now.isBefore(deadline)) return new Decision(Action.WAIT, 0, deadline);
    return new Decision(Action.DONE, 0, deadline);
  }

  static List<Integer> normalized(List<Integer> warnings) {
    TreeSet<Integer> unique = new TreeSet<>(Comparator.reverseOrder());
    for (Integer warning : warnings) {
      if (warning == null || warning < 0 || warning > 86_400)
        throw new IllegalArgumentException("Invalid maintenance warning boundary");
      unique.add(warning);
    }
    return List.copyOf(unique);
  }

  static int durationSeconds(List<Integer> warnings) {
    List<Integer> normalized = normalized(warnings);
    return normalized.isEmpty() ? 0 : normalized.getFirst();
  }
}
