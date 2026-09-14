package io.github.zpkdxgames.plexonpanel.host;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MaintenanceCountdownStateTest {
  @TempDir Path temporary;

  @Test
  void countdownDeadlineAndConsumedWarningsSurviveHostRestart() throws Exception {
    Instant started = Instant.parse("2026-09-14T12:00:00Z");
    var first = new MaintenanceStateStore(temporary);
    var job = first.begin("FULL_RESTORE_POINT", null, false, "COUNTDOWN");
    first.beginCountdown(job, 1800, started);
    first.consumeWarning(job, 1800);
    first.consumeWarning(job, 900);

    var restarted = new MaintenanceStateStore(temporary);
    var active = restarted.active();
    assertNotNull(active);
    assertEquals(job.jobId(), active.jobId());
    assertEquals("COUNTDOWN", active.phase());
    var countdown = restarted.countdown(active);
    assertEquals(started.plusSeconds(1800).toString(), countdown.deadline());
    assertEquals(java.util.List.of(1800, 900), countdown.consumedWarnings());
    assertNotNull(restarted.blocking());
  }

  @Test
  void leavingCountdownClearsTransientCountdownJournal() throws Exception {
    var store = new MaintenanceStateStore(temporary);
    var job = store.begin("RESTART", null, false, "COUNTDOWN");
    store.beginCountdown(job, 900, Instant.parse("2026-09-14T12:00:00Z"));
    job = store.update(job, "FINAL_SAVE", null);

    Exception error = assertThrows(Exception.class, () -> store.countdown(job));
    assertEquals("COUNTDOWN_STATE_INVALID", error.getMessage());
  }

  @Test
  void legacyCountdownWithoutDurableDeadlineFallsBackToExistingRecoveryClassifier()
      throws Exception {
    var store = new MaintenanceStateStore(temporary);
    store.begin("FULL_RESTORE_POINT", null, false, "COUNTDOWN");

    var recovered = store.recoverInterrupted();
    assertNotNull(recovered);
    assertEquals("FAILED", recovered.phase());
    assertEquals("HOST_RESTART_INTERRUPTED", recovered.errorCode());
  }
}
