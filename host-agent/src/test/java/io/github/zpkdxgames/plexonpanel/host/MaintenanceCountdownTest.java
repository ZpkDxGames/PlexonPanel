package io.github.zpkdxgames.plexonpanel.host;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MaintenanceCountdownTest {
  @TempDir Path temporary;

  @Test
  void emitsExactRequiredBoundariesAndEndsAtThirtyMinutes() throws Exception {
    MutableClock clock = new MutableClock(Instant.parse("2026-09-14T12:00:00Z"));
    MaintenanceCountdown countdown = new MaintenanceCountdown(clock, clock::advance);
    MaintenanceCountdownStore store = new MaintenanceCountdownStore(temporary);
    String jobId = UUID.randomUUID().toString();
    var state = store.start(jobId, clock.instant());
    List<Integer> sent = new ArrayList<>();

    var result = countdown.run(store, state, false, sent::add);

    assertEquals(List.of(1800, 900, 60, 30, 15, 5), sent);
    assertEquals(List.of(), result.newlyMissed());
    assertEquals(Instant.parse("2026-09-14T12:30:00Z"), clock.instant());
    assertEquals(sent, result.state().handledSeconds());
  }

  @Test
  void hostRestartSkipsMissedBoundaryAndContinuesFutureOnes() throws Exception {
    Instant started = Instant.parse("2026-09-14T12:00:00Z");
    MutableClock clock = new MutableClock(started.plus(Duration.ofMinutes(20)));
    MaintenanceCountdown countdown = new MaintenanceCountdown(clock, clock::advance);
    MaintenanceCountdownStore store = new MaintenanceCountdownStore(temporary);
    String jobId = UUID.randomUUID().toString();
    var state = store.start(jobId, started);
    state = store.markHandled(state, 1800);
    List<Integer> sent = new ArrayList<>();

    var result = countdown.run(store, state, true, sent::add);

    assertEquals(List.of(900), result.newlyMissed());
    assertEquals(List.of(60, 30, 15, 5), sent);
    assertTrue(result.state().handledSeconds().containsAll(MaintenanceCountdown.BOUNDARIES_SECONDS));
    assertEquals(1, result.state().hostRestartCount());
    assertEquals(started.plus(Duration.ofMinutes(30)), clock.instant());
  }

  private static final class MutableClock extends Clock {
    private Instant now;

    private MutableClock(Instant now) {
      this.now = now;
    }

    void advance(Duration duration) {
      if (!duration.isNegative()) now = now.plus(duration);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
