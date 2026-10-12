package io.github.zpkdxgames.plexonpanel.host;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class BackupRecoveryPreflightTest {
  @Test
  void noRecoverySourceKeepsPreflightClear() {
    assertFalse(HostMain.backupRecoveryRequired(false, false));
  }

  @Test
  void restoreRecoveryBlocksPreflight() {
    assertTrue(HostMain.backupRecoveryRequired(true, false));
  }

  @Test
  void durableMaintenanceRecoveryAlsoBlocksPreflight() {
    assertTrue(HostMain.backupRecoveryRequired(false, true));
  }
}
