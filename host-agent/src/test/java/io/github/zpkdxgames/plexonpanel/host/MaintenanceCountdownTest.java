package io.github.zpkdxgames.plexonpanel.host;

import static org.junit.jupiter.api.Assertions.*;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class MaintenanceCountdownTest {
  private static final Instant START = Instant.parse("2026-09-14T12:00:00Z");
  private static final Instant DEADLINE = START.plusSeconds(1800);

  @Test
  void fullBackupUsesExactlyTheRequiredWarningBoundaries() {
    assertEquals(List.of(1800, 900, 60, 30, 15, 5), MaintenanceCountdown.FULL_BACKUP_WARNINGS);
    assertEquals(1800, MaintenanceCountdown.durationSeconds(MaintenanceCountdown.FULL_BACKUP_WARNINGS));
  }

  @Test
  void firstThirtyMinuteWarningIsDueImmediately() {
    var planner = new MaintenanceCountdown(Clock.fixed(START, ZoneOffset.UTC));
    var decision = planner.next(DEADLINE, MaintenanceCountdown.FULL_BACKUP_WARNINGS, Set.of());

    assertEquals(MaintenanceCountdown.Action.SEND, decision.action());
    assertEquals(1800, decision.warningSeconds());
    assertEquals(START, decision.target());
  }

  @Test
  void hostRecoverySkipsMissedBoundaryAndContinuesWithNextValidBoundary() {
    Set<Integer> consumed = new LinkedHashSet<>();
    consumed.add(1800);
    var afterRestart =
        new MaintenanceCountdown(Clock.fixed(START.plusSeconds(903), ZoneOffset.UTC));
    var missed = afterRestart.next(DEADLINE, MaintenanceCountdown.FULL_BACKUP_WARNINGS, consumed);

    assertEquals(MaintenanceCountdown.Action.SKIP, missed.action());
    assertEquals(900, missed.warningSeconds());

    consumed.add(900);
    var next = afterRestart.next(DEADLINE, MaintenanceCountdown.FULL_BACKUP_WARNINGS, consumed);
    assertEquals(MaintenanceCountdown.Action.WAIT, next.action());
    assertEquals(60, next.warningSeconds());
    assertEquals(DEADLINE.minusSeconds(60), next.target());
  }

  @Test
  void consumedWarningsAreNeverReplayed() {
    Set<Integer> consumed = Set.of(1800, 900, 60, 30, 15);
    Instant fiveSecondBoundary = DEADLINE.minusSeconds(5);
    var planner = new MaintenanceCountdown(Clock.fixed(fiveSecondBoundary, ZoneOffset.UTC));
    var decision = planner.next(DEADLINE, MaintenanceCountdown.FULL_BACKUP_WARNINGS, consumed);

    assertEquals(MaintenanceCountdown.Action.SEND, decision.action());
    assertEquals(5, decision.warningSeconds());
  }
}
