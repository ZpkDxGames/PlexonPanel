package io.github.zpkdxgames.plexonpanel.host;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MaintenanceStateStoreTest {
  @TempDir Path temporary;

  @Test
  void duplicateOccurrenceClaimPersistsAcrossHostRestart() throws Exception {
    Instant occurrence = Instant.parse("2026-09-20T07:00:00Z");
    var first = new MaintenanceStateStore(temporary);
    assertTrue(first.claim("full-restore-point", occurrence));
    assertFalse(first.claim("full-restore-point", occurrence));

    var restarted = new MaintenanceStateStore(temporary);
    assertFalse(restarted.claim("full-restore-point", occurrence));
    assertTrue(restarted.claim("restart", occurrence));
  }

  @Test
  void manualJobPersistsRequesterPhaseTimestampAndProgress() throws Exception {
    var store = new MaintenanceStateStore(temporary);
    var queued =
        store.begin(
            "FULL_RESTORE_POINT", null, false, "QUEUED", "device-123", "Owner desktop");

    assertEquals("device-123", queued.requesterDeviceId());
    assertEquals("Owner desktop", queued.requesterDeviceName());
    assertEquals("QUEUED", queued.phase());
    assertFalse(queued.phaseTimestamp().isBlank());
    assertEquals(0, queued.progressPercent());

    var preflight =
        store.transition(queued, "PREFLIGHT", null, 5, false, false, false, false);
    assertEquals("PREFLIGHT", preflight.phase());
    assertEquals(5, preflight.progressPercent());
    assertEquals("device-123", preflight.requesterDeviceId());
    assertNotEquals(queued.phaseTimestamp(), preflight.phaseTimestamp());

    var reloaded = new MaintenanceStateStore(temporary).active();
    assertNotNull(reloaded);
    assertEquals(preflight.jobId(), reloaded.jobId());
    assertEquals(5, reloaded.progressPercent());
    assertEquals("Owner desktop", reloaded.requesterDeviceName());
  }

  @Test
  void hostRestartBeforeDestructivePhaseFailsDeterministically() throws Exception {
    var store = new MaintenanceStateStore(temporary);
    var job =
        store.begin(
            "FULL_RESTORE_POINT", null, false, "QUEUED", "device-123", "Owner desktop");
    job = store.transition(job, "COUNTDOWN", null, 10, false, false, false, false);

    var restarted = new MaintenanceStateStore(temporary);
    var recovered = restarted.recoverInterrupted();
    assertNotNull(recovered);
    assertEquals("FAILED", recovered.phase());
    assertEquals("HOST_RESTART_INTERRUPTED", recovered.errorCode());
    assertFalse(recovered.restartRecoveryRequired());
    assertNull(restarted.recoveryRequired());
  }

  @Test
  void hostRestartDuringDestructivePhaseFailsClosedIntoRecoveryRequired() throws Exception {
    var store = new MaintenanceStateStore(temporary);
    var job =
        store.begin(
            "FULL_RESTORE_POINT", null, false, "QUEUED", "device-123", "Owner desktop");
    job = store.transition(job, "WAITING_FOR_STOP", null, 40, true, false, false, false);

    var restarted = new MaintenanceStateStore(temporary);
    var recovery = restarted.recoverInterrupted();
    assertNotNull(recovery);
    assertEquals(job.jobId(), recovery.jobId());
    assertEquals("RECOVERY_REQUIRED", recovery.phase());
    assertEquals("HOST_RESTART_INTERRUPTED", recovery.errorCode());
    assertTrue(recovery.hostStoppedServer());
    assertTrue(recovery.restartRecoveryRequired());
    assertNotNull(restarted.recoveryRequired());
    assertThrows(
        IllegalStateException.class,
        () -> restarted.begin("RESTART", Instant.now(), false, "QUEUED"));
  }

  @Test
  void verifiedLocalBackupCanDegradeSafelyWhenOnlyRemoteVerificationWasInterrupted()
      throws Exception {
    var store = new MaintenanceStateStore(temporary);
    var job =
        store.begin(
            "FULL_RESTORE_POINT", null, false, "QUEUED", "device-123", "Owner desktop");
    job =
        store.transition(
            job,
            "VERIFYING_REMOTE",
            "11111111-1111-1111-1111-111111111111",
            82,
            false,
            true,
            false,
            false);

    var restarted = new MaintenanceStateStore(temporary);
    var degraded = restarted.recoverInterrupted();
    assertNotNull(degraded);
    assertEquals("DEGRADED", degraded.phase());
    assertEquals("DEGRADED", degraded.result());
    assertTrue(degraded.localBackupVerified());
    assertFalse(degraded.remoteBackupVerified());
    assertEquals("REMOTE_VERIFICATION_PENDING", degraded.errorCode());
    assertNull(restarted.recoveryRequired());
  }

  @Test
  void completedJobClearsRecoveryGateAndAppendsHistory() throws Exception {
    var store = new MaintenanceStateStore(temporary);
    var job =
        store.begin(
            "FULL_RESTORE_POINT", null, false, "QUEUED", "device-123", "Owner desktop");
    job =
        store.transition(
            job,
            "VERIFYING_STARTUP",
            "11111111-1111-1111-1111-111111111111",
            98,
            false,
            true,
            true,
            false);
    var finished = store.finish(job, "SUCCESS", "");

    assertEquals("COMPLETED", finished.phase());
    assertEquals(100, finished.progressPercent());
    assertTrue(finished.localBackupVerified());
    assertTrue(finished.remoteBackupVerified());
    assertNull(store.recoveryRequired());
    assertNotNull(store.begin("RESTART", Instant.now(), false, "QUEUED"));

    Path history = temporary.resolve("maintenance/history.jsonl");
    assertTrue(Files.isRegularFile(history));
    String text = Files.readString(history);
    assertTrue(text.contains(finished.jobId()));
    assertTrue(text.contains("COMPLETED"));
  }

  @Test
  void failedJobIsTerminalAndStoresOnlySafeErrorText() throws Exception {
    var store = new MaintenanceStateStore(temporary);
    var job = store.begin("FULL_RESTORE_POINT", null, false, "QUEUED");
    var failed = store.finish(job, "FAILED", "REMOTE_UPLOAD_FAILED");

    assertEquals("FAILED", failed.phase());
    assertEquals("FAILED", failed.result());
    assertEquals("REMOTE_UPLOAD_FAILED", failed.errorCode());
    assertEquals("Operation did not complete (REMOTE_UPLOAD_FAILED).", failed.errorMessage());
    assertNull(store.recoveryRequired());
  }
}
