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
  void unfinishedJobRequiresRecoveryAfterHostRestart() throws Exception {
    var store = new MaintenanceStateStore(temporary);
    var job = store.begin("FULL_RESTORE_POINT", Instant.parse("2026-09-20T07:00:00Z"), true, "STOPPING");
    job = store.update(job, "ARCHIVING", "11111111-1111-1111-1111-111111111111");

    var restarted = new MaintenanceStateStore(temporary);
    var recovery = restarted.recoveryRequired();
    assertNotNull(recovery);
    assertEquals(job.jobId(), recovery.jobId());
    assertEquals("ARCHIVING", recovery.phase());
    assertEquals("11111111-1111-1111-1111-111111111111", recovery.backupId());
    assertThrows(
        IllegalStateException.class,
        () -> restarted.begin("RESTART", Instant.now(), true, "STOPPING"));
  }

  @Test
  void completedJobClearsRecoveryGateAndAppendsHistory() throws Exception {
    var store = new MaintenanceStateStore(temporary);
    var job = store.begin("RESTART", Instant.parse("2026-09-14T07:00:00Z"), true, "COUNTDOWN");
    var finished = store.finish(job, "SUCCESS", "");

    assertEquals("COMPLETE", finished.phase());
    assertNull(store.recoveryRequired());
    assertNotNull(store.begin("RESTART", Instant.parse("2026-09-15T07:00:00Z"), true, "COUNTDOWN"));

    Path history = temporary.resolve("maintenance/history.jsonl");
    assertTrue(Files.isRegularFile(history));
    String text = Files.readString(history);
    assertTrue(text.contains(finished.jobId()));
    assertTrue(text.contains("SUCCESS"));
  }

  @Test
  void failedJobIsTerminalButRetainsExplicitError() throws Exception {
    var store = new MaintenanceStateStore(temporary);
    var job = store.begin("FULL_RESTORE_POINT", null, false, "PREPARING");
    var failed = store.finish(job, "FAILED", "REMOTE_UPLOAD_FAILED");

    assertEquals("FAILED", failed.phase());
    assertEquals("FAILED", failed.result());
    assertEquals("REMOTE_UPLOAD_FAILED", failed.errorCode());
    assertNull(store.recoveryRequired());
  }
}
