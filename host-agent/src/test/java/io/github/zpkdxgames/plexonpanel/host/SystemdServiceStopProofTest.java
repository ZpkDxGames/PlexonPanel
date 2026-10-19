package io.github.zpkdxgames.plexonpanel.host;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;

class SystemdServiceStopProofTest {
  @Test
  void inactiveWithZeroMainPidIsProvenStopped() {
    assertTrue(SystemdService.stopped(Map.of("state", "inactive", "pid", 0L), pid -> false));
  }

  @Test
  void failedWithDeadMainPidIsProvenStopped() {
    assertTrue(SystemdService.stopped(Map.of("state", "failed", "pid", 42L), pid -> false));
  }

  @Test
  void inactiveWithLiveMainPidFailsClosed() {
    assertFalse(SystemdService.stopped(Map.of("state", "inactive", "pid", 42L), pid -> true));
  }

  @Test
  void activeUnitNeverPassesColdBackupGate() {
    assertFalse(SystemdService.stopped(Map.of("state", "active", "pid", 0L), pid -> false));
  }

  @Test
  void unknownStateNeverPassesColdBackupGate() {
    assertFalse(SystemdService.stopped(Map.of("state", "unknown", "pid", 0L), pid -> false));
  }
}
