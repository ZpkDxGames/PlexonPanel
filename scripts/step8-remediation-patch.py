#!/usr/bin/env python3
from pathlib import Path

path = Path('host-agent/src/main/java/io/github/zpkdxgames/plexonpanel/host/MaintenanceManager.java')
text = path.read_text()

if 'static boolean shouldStartAfterFullBackupFailure(' not in text:
    needle = '      if (destructiveBoundary) tryStartAfterFailure();'
    index = text.rfind(needle)
    if index < 0:
        raise SystemExit('full-backup recovery-start call not found')
    replacement = (
        '      if (shouldStartAfterFullBackupFailure(destructiveBoundary, job.localBackupVerified()))\n'
        '        tryStartAfterFailure();'
    )
    text = text[:index] + replacement + text[index + len(needle):]

    marker = '  private void tryStartAfterFailure() {'
    helper = (
        '  static boolean shouldStartAfterFullBackupFailure(\n'
        '      boolean destructiveBoundary, boolean localBackupVerified) {\n'
        '    return destructiveBoundary && localBackupVerified;\n'
        '  }\n\n'
    )
    if marker not in text:
        raise SystemExit('recovery-start method marker not found')
    text = text.replace(marker, helper + marker, 1)
    path.write_text(text)

test = Path('host-agent/src/test/java/io/github/zpkdxgames/plexonpanel/host/MaintenanceFullBackupFailurePolicyTest.java')
test.write_text('''package io.github.zpkdxgames.plexonpanel.host;

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
''')
