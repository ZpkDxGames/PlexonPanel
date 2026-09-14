package io.github.zpkdxgames.plexonpanel.control;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;

class OperationFailureTest {
  @Test
  void safeDetailsAreReturnedWithStandardFailureMetadata() {
    OperationFailure failure =
        new OperationFailure(
            "BUSY",
            "COUNTDOWN",
            "Another Host maintenance job is already active.",
            true,
            Map.of(
                "jobId", "11111111-1111-1111-1111-111111111111",
                "state", "COUNTDOWN",
                "kind", "FULL_RESTORE_POINT"));

    assertEquals("COUNTDOWN", failure.safeData().get("phase"));
    assertEquals(true, failure.safeData().get("retryable"));
    assertEquals("11111111-1111-1111-1111-111111111111", failure.safeData().get("jobId"));
    assertEquals("FULL_RESTORE_POINT", failure.safeData().get("kind"));
  }

  @Test
  void unsafeStructuredDetailsAreRejected() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new OperationFailure(
                "BUSY",
                "QUEUED",
                "Another Host maintenance job is already active.",
                true,
                Map.of("jobId", "unsafe\nvalue")));
  }
}
