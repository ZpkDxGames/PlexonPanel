package io.github.zpkdxgames.plexonpanel.host;

import java.time.*;
import java.util.*;

/** Durable Host-owned maintenance countdown with exact warning boundaries. */
final class MaintenanceCountdown {
  static final List<Integer> FULL_BACKUP_WARNINGS = List.of(1800, 900, 60, 30, 15, 5);

  @FunctionalInterface
  interface Sleeper {
    void sleep(Duration duration) throws InterruptedException;
  }

  private final MaintenanceStateStore state;
  private final MaintenanceCommandChannel commands;
  private final Clock clock;
  private final Sleeper sleeper;

  MaintenanceCountdown(MaintenanceStateStore state, MaintenanceCommandChannel commands) {
    this(
        state,
        commands,
        Clock.systemUTC(),
        duration -> {
          long millis = duration.toMillis();
          if (millis > 0) Thread.sleep(millis);
        });
  }

  MaintenanceCountdown(
      MaintenanceStateStore state,
      MaintenanceCommandChannel commands,
      Clock clock,
      Sleeper sleeper) {
    this.state = Objects.requireNonNull(state, "state");
    this.commands = Objects.requireNonNull(commands, "commands");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
  }

  MaintenanceStateStore.Job run(
      MaintenanceStateStore.Job original,
      List<Integer> warningSeconds,
      MaintenanceCommandChannel.Operation operation)
      throws Exception {
    Objects.requireNonNull(original, "original");
    Objects.requireNonNull(operation, "operation");
    List<Integer> warnings = normalize(warningSeconds);
    if (warnings.isEmpty()) throw new IllegalArgumentException("Maintenance countdown needs warnings");

    MaintenanceStateStore.Job job = original;
    boolean fresh = job.countdownDeadline() == null || job.countdownDeadline().isBlank();
    Instant now = clock.instant();
    Instant deadline;
    if (fresh) {
      deadline = now.plusSeconds(warnings.getFirst());
      job = state.startCountdown(job, deadline);
    } else {
      try {
        deadline = Instant.parse(job.countdownDeadline());
      } catch (DateTimeException invalid) {
        throw new IllegalStateException("COUNTDOWN_STATE_INVALID");
      }
      if (!"COUNTDOWN".equals(job.phase())) job = state.startCountdown(job, deadline);
    }

    for (int remaining : warnings) {
      if (job.emittedWarningSeconds().contains(remaining)
          || job.skippedWarningSeconds().contains(remaining)) continue;

      Instant target = deadline.minusSeconds(remaining);
      now = clock.instant();
      boolean immediateFreshBoundary = fresh && remaining == warnings.getFirst();
      if (!immediateFreshBoundary && now.isAfter(target)) {
        job = state.markWarningSkipped(job, remaining);
        continue;
      }
      if (now.isBefore(target)) {
        sleeper.sleep(Duration.between(now, target));
      }

      commands.warning(operation, remaining);
      job = state.markWarningEmitted(job, remaining);
    }

    now = clock.instant();
    if (now.isBefore(deadline)) sleeper.sleep(Duration.between(now, deadline));
    return job;
  }

  private static List<Integer> normalize(List<Integer> values) {
    if (values == null) return List.of();
    TreeSet<Integer> sorted = new TreeSet<>(Comparator.reverseOrder());
    for (Integer value : values) {
      if (value == null || value < 1 || value > 86_400)
        throw new IllegalArgumentException("Invalid maintenance warning boundary");
      sorted.add(value);
    }
    return List.copyOf(sorted);
  }
}
