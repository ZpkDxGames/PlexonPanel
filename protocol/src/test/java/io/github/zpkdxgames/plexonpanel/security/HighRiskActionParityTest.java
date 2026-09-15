package io.github.zpkdxgames.plexonpanel.security;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Set;
import org.junit.jupiter.api.Test;

class HighRiskActionParityTest {
  @Test
  void maintenanceAndBackupAliasesRequireBackendConfirmation() {
    Set<String> required =
        Set.of(
            "backup.full.delete",
            "backup.full.restore",
            "maintenance.settings.update",
            "maintenance.restart.now",
            "maintenance.full-backup.create",
            "maintenance.recovery.resolve");

    assertTrue(Scopes.HIGH_RISK.containsAll(required));
    assertEquals("backup.delete", Scopes.required("backup.full.delete"));
    assertEquals("backup.restore", Scopes.required("backup.full.restore"));
    assertEquals("maintenance.configure", Scopes.required("maintenance.settings.update"));
    assertEquals("maintenance.restart", Scopes.required("maintenance.restart.now"));
    assertEquals("maintenance.run", Scopes.required("maintenance.full-backup.create"));
    assertEquals("maintenance.run", Scopes.required("maintenance.recovery.resolve"));
  }
}
