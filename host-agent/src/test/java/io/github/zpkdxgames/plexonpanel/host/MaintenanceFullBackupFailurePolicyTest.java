package io.github.zpkdxgames.plexonpanel.host;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class MaintenanceFullBackupFailurePolicyTest {
  @Test
  void archiveFailureBeforeLocalVerificationFailsClosed() {
    assertFalse(MaintenanceManager.shouldStartAfterFullBackupFailure(true, false));
  }

  @Test
  void preDestructiveFailureNeverStartsService() {
    assertFalse(MaintenanceManager.shouldStartAfterFullBackupFailure(false, false));
    assertFalse(MaintenanceManager.shouldStartAfterFullBackupFailure(false, true));
  }

  @Test
  void verifiedLocalBackupMayRecoverServiceAvailability() {
    assertTrue(MaintenanceManager.shouldStartAfterFullBackupFailure(true, true));
  }
}
