package io.github.zpkdxgames.plexonpanel.host;

import java.time.*;
import java.util.*;

/** Exact 30-minute manual-backup warning schedule owned by the Host job. */
public final class MaintenanceCountdown {
  public static final List<Integer> BOUNDARIES_SECONDS = List.of(1800, 900, 60, 30, 15, 5);
  private static final Duration MISSED_GRACE = Duration.ofSeconds(1);

  @FunctionalInterface
  public interface Sleeper {
    void sleep(Duration duration) throws InterruptedException;
  }

  @FunctionalInterface
  public interface WarningSender {
    void send(int secondsRemaining);
  }

  public record Result(MaintenanceCountdownStore.State state, List<Integer> newlyMissed) {}

  private final Clock clock;
  private final Sleeper sleeper;

  public MaintenanceCountdown() {
    this(
        Clock.systemUTC(),
        duration -> {
          long millis = duration.toMillis();
          if (millis > 0) Thread.sleep(millis);
        });
  }

  MaintenanceCountdown(Clock clock, Sleeper sleeper) {
    this.clock = Objects.requireNonNull(clock, "clock");
    this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
  }

  /**
   * Runs or resumes the fixed countdown. Each boundary is persisted as handled before emission so a
   * Host crash cannot unnecessarily replay a warning that may already have reached players.
   */
  public Result run(
      MaintenanceCountdownStore store,
      MaintenanceCountdownStore.State initial,
      boolean resumed,
      WarningSender sender)
      throws Exception {
    Objects.requireNonNull(store, "store");
    Objects.requireNonNull(initial, "initial");
    Objects.requireNonNull(sender, "sender");
    Instant startedAt = Instant.parse(initial.startedAt());
    MaintenanceCountdownStore.State state = initial;
    List<Integer> missed = new ArrayList<>();

    if (resumed) {
      Instant now = clock.instant();
      for (int secondsRemaining : BOUNDARIES_SECONDS) {
        if (state.handledSeconds().contains(secondsRemaining)) continue;
        Instant target = startedAt.plusSeconds(1800L - secondsRemaining);
        if (now.isAfter(target.plus(MISSED_GRACE))) missed.add(secondsRemaining);
      }
      state = store.recordRecovery(state, missed);
    }

    for (int secondsRemaining : BOUNDARIES_SECONDS) {
      if (state.handledSeconds().contains(secondsRemaining)) continue;
      Instant target = startedAt.plusSeconds(1800L - secondsRemaining);
      Instant now = clock.instant();
      if (now.isBefore(target)) sleeper.sleep(Duration.between(now, target));
      state = store.markHandled(state, secondsRemaining);
      sender.send(secondsRemaining);
    }

    Instant deadline = startedAt.plusSeconds(1800);
    Instant now = clock.instant();
    if (now.isBefore(deadline)) sleeper.sleep(Duration.between(now, deadline));
    return new Result(state, List.copyOf(missed));
  }
}
