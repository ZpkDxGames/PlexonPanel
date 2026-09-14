package io.github.zpkdxgames.plexonpanel.host;

import static org.junit.jupiter.api.Assertions.*;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MaintenanceCountdownTest {
  @TempDir java.nio.file.Path temporary;

  @Test
  void fullBackupUsesExactThirtyMinuteWarningContract() throws Exception {
    MaintenanceStateStore state = new MaintenanceStateStore(temporary);
    MaintenanceStateStore.Job job = state.begin("FULL_RESTORE_POINT", null, false, "QUEUED");
    MutableClock clock = new MutableClock(Instant.parse("2026-09-14T12:00:00Z"));
    RecordingChannel channel = new RecordingChannel();
    MaintenanceCountdown countdown =
        new MaintenanceCountdown(state, channel, clock, clock::advance);

    MaintenanceStateStore.Job finished =
        countdown.run(
            job,
            MaintenanceCountdown.FULL_BACKUP_WARNINGS,
            MaintenanceCommandChannel.Operation.FULL_BACKUP);

    assertEquals(List.of(1800, 900, 60, 30, 15, 5), channel.warnings);
    assertEquals(channel.warnings, finished.emittedWarningSeconds());
    assertTrue(finished.skippedWarningSeconds().isEmpty());
    assertEquals("2026-09-14T12:30:00Z", finished.countdownDeadline());
    assertEquals(Instant.parse("2026-09-14T12:30:00Z"), clock.instant());
  }

  @Test
  void restartDuringCountdownDoesNotReplayAndSkipsOnlyMissedBoundaries() throws Exception {
    MaintenanceStateStore state = new MaintenanceStateStore(temporary);
    MaintenanceStateStore.Job job = state.begin("FULL_RESTORE_POINT", null, false, "QUEUED");
    Instant start = Instant.parse("2026-09-14T12:00:00Z");
    job = state.startCountdown(job, start.plusSeconds(1800));
    job = state.markWarningEmitted(job, 1800);

    MutableClock clock = new MutableClock(start.plusSeconds(1000));
    MaintenanceStateStore restartedStore = new MaintenanceStateStore(temporary);
    MaintenanceStateStore.Job recovered = restartedStore.recoverInterrupted();
    assertEquals("COUNTDOWN", recovered.phase());
    assertEquals("RESUMED_AFTER_HOST_RESTART", recovered.countdownRecovery());

    RecordingChannel channel = new RecordingChannel();
    MaintenanceCountdown countdown =
        new MaintenanceCountdown(restartedStore, channel, clock, clock::advance);
    MaintenanceStateStore.Job finished =
        countdown.run(
            recovered,
            MaintenanceCountdown.FULL_BACKUP_WARNINGS,
            MaintenanceCommandChannel.Operation.FULL_BACKUP);

    assertEquals(List.of(60, 30, 15, 5), channel.warnings);
    assertEquals(List.of(1800, 60, 30, 15, 5), finished.emittedWarningSeconds());
    assertEquals(List.of(900), finished.skippedWarningSeconds());
    assertEquals(start.plusSeconds(1800), clock.instant());
  }

  private static final class RecordingChannel implements MaintenanceCommandChannel {
    private final List<Integer> warnings = new ArrayList<>();

    @Override
    public void warning(Operation operation, int remainingSeconds) {
      assertEquals(Operation.FULL_BACKUP, operation);
      warnings.add(remainingSeconds);
    }

    @Override
    public void saveAllFlush() {}

    @Override
    public boolean ready() {
      return true;
    }
  }

  private static final class MutableClock extends Clock {
    private Instant current;

    private MutableClock(Instant current) {
      this.current = current;
    }

    void advance(Duration duration) {
      current = current.plus(duration);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      if (!ZoneOffset.UTC.equals(zone)) throw new UnsupportedOperationException();
      return this;
    }

    @Override
    public Instant instant() {
      return current;
    }
  }
}
