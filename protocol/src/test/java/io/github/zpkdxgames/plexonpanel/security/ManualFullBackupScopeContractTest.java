package io.github.zpkdxgames.plexonpanel.security;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ManualFullBackupScopeContractTest {
  @Test
  void manualFullBackupActionRetainsMaintenanceRunScope() {
    assertEquals("maintenance.run", Scopes.required("maintenance.full-backup.create"));
    assertTrue(Scopes.HIGH_RISK.contains("maintenance.full-backup.create"));
  }
}
