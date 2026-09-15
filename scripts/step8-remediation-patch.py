#!/usr/bin/env python3
from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f'{label} marker not found')
    return text.replace(old, new, 1)


# 1) Full-backup failure must remain fail-closed until a local restore point is verified.
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

# 2) Provide an explicit, service-readiness-gated operator resolution path for maintenance recovery.
if 'public synchronized Map<String, Object> resolveRecovery(' not in text:
    marker = '''  /** Manual full backups always use the mandatory Host-owned countdown while Minecraft is online. */
  public String fullRestorePointNow(DeviceRegistry.Device device, boolean ignoredSkipCountdown)
      throws Exception {
    return queue("FULL_RESTORE_POINT", null, device, false, false);
  }
'''
    method = marker + '''
  /**
   * Resolves a durable maintenance recovery gate only after the operator has restored Minecraft to
   * a positively verified running state. This never invents backup success; the interrupted job is
   * finalized as FAILED while preserving its original error code.
   */
  public synchronized Map<String, Object> resolveRecovery(DeviceRegistry.Device device)
      throws Exception {
    if (closed.get()) throw new IllegalStateException("HOST_OFFLINE");
    MaintenanceStateStore.Job recovery = state.recoveryRequired();
    if (recovery == null)
      return Map.of("resolved", false, "state", "NONE");

    Map<String, Object> serviceStatus = service.status();
    if (!"active".equals(serviceStatus.get("state")))
      throw new OperationFailure(
          "RECOVERY_STATE_NOT_VERIFIED",
          "RECOVERY_REQUIRED",
          "Minecraft must be running before maintenance recovery can be acknowledged.",
          true,
          Map.of(
              "jobId", recovery.jobId(),
              "state", recovery.phase(),
              "requiredServiceState", "active"));

    requireCommandChannel();
    MinecraftCommandChannel.Result readiness = commandChannel.readinessProbe();
    if (!readiness.success())
      throw new OperationFailure(
          "RECOVERY_STATE_NOT_VERIFIED",
          "RECOVERY_REQUIRED",
          "Minecraft must pass the Host-local readiness probe before maintenance recovery can be acknowledged.",
          true,
          Map.of("jobId", recovery.jobId(), "state", recovery.phase()));

    String originalCode = recovery.errorCode();
    state.markRecovered(recovery, "FAILED");
    MaintenanceStateStore.Job resolved = state.active();
    publish(
        "maintenance.recovery.resolved",
        Map.of(
            "jobId", recovery.jobId(),
            "kind", recovery.kind(),
            "result", "FAILED",
            "errorCode", originalCode == null ? "" : originalCode));
    audit(
        "maintenance.recovery.resolve",
        "SUCCESS",
        actor(device, false),
        false,
        recovery.jobId(),
        recovery.backupId(),
        Map.of("verifiedServiceState", "active", "readinessVerified", true));
    return Map.of(
        "resolved", true,
        "jobId", recovery.jobId(),
        "state", resolved == null ? "FAILED" : resolved.phase());
  }
'''
    if marker not in text:
        raise SystemExit('fullRestorePointNow marker not found')
    text = text.replace(marker, method, 1)

path.write_text(text)

# 3) Preserve the original failure code when the recovery gate is acknowledged as failed.
state_path = Path('host-agent/src/main/java/io/github/zpkdxgames/plexonpanel/host/MaintenanceStateStore.java')
state_text = state_path.read_text()
old_mark = '''  public synchronized void markRecovered(Job job, String result) throws IOException {
    Job cleared =
        transition(
            job,
            "RECOVERY_REQUIRED",
            job.backupId(),
            null,
            null,
            null,
            null,
            false);
    finish(cleared, result, "SUCCESS".equals(result) ? "" : "RECOVERY_REQUIRED");
  }
'''
new_mark = '''  public synchronized void markRecovered(Job job, String result) throws IOException {
    Job cleared =
        transition(
            job,
            "RECOVERY_REQUIRED",
            job.backupId(),
            null,
            null,
            null,
            null,
            false);
    if ("SUCCESS".equals(result)) {
      finish(cleared, result, "");
      return;
    }
    String code = safe(job.errorCode());
    if (code.isBlank()) code = "RECOVERY_REQUIRED";
    finish(
        cleared,
        result,
        code,
        "Recovery was acknowledged after Minecraft service state and readiness were verified.");
  }
'''
if old_mark in state_text:
    state_text = state_text.replace(old_mark, new_mark, 1)
elif 'Recovery was acknowledged after Minecraft service state and readiness were verified.' not in state_text:
    raise SystemExit('markRecovered marker not found')
state_path.write_text(state_text)

# 4) Wire the operator action into Host dispatch and the existing maintenance.run scope.
host_path = Path('host-agent/src/main/java/io/github/zpkdxgames/plexonpanel/host/HostMain.java')
host_text = host_path.read_text()
if 'case "maintenance.recovery.resolve"' not in host_text:
    marker = '''                case "maintenance.restart.now" -> {
                  boolean skip = p.has("skipCountdown") && p.get("skipCountdown").getAsBoolean();
                  return Map.of("jobId", maintenance.restartNow(device, skip), "state", "QUEUED");
                }
'''
    replacement = marker + '''                case "maintenance.recovery.resolve" -> {
                  return maintenance.resolveRecovery(device);
                }
'''
    host_text = replace_once(host_text, marker, replacement, 'Host recovery action')
host_path.write_text(host_text)

scopes_path = Path('protocol/src/main/java/io/github/zpkdxgames/plexonpanel/security/Scopes.java')
scopes_text = scopes_path.read_text()
if 'actions.put("maintenance.recovery.resolve", "maintenance.run");' not in scopes_text:
    marker = '    actions.put("maintenance.full-backup.create", "maintenance.run");\n'
    scopes_text = replace_once(
        scopes_text,
        marker,
        marker + '    actions.put("maintenance.recovery.resolve", "maintenance.run");\n',
        'recovery scope mapping',
    )
scopes_path.write_text(scopes_text)

# 5) Regression coverage for fail-closed behavior and recovery error preservation.
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

state_test = Path('host-agent/src/test/java/io/github/zpkdxgames/plexonpanel/host/MaintenanceStateStoreTest.java')
state_test_text = state_test.read_text()
if 'failedRecoveryAcknowledgementPreservesOriginalErrorCode' not in state_test_text:
    marker = '\n  @Test\n  void completedJobClearsRecoveryGateAndAppendsHistory() throws Exception {'
    addition = '''
  @Test
  void failedRecoveryAcknowledgementPreservesOriginalErrorCode() throws Exception {
    var store = new MaintenanceStateStore(temporary);
    var job = store.begin("FULL_RESTORE_POINT", null, false, "QUEUED");
    job = store.transition(job, "WAITING_FOR_STOP", null, 40, true, false, false, true);
    job = store.recoverInterrupted();
    String originalCode = job.errorCode();

    store.markRecovered(job, "FAILED");

    var resolved = store.active();
    assertNotNull(resolved);
    assertEquals("FAILED", resolved.phase());
    assertEquals(originalCode, resolved.errorCode());
    assertFalse(resolved.restartRecoveryRequired());
    assertNull(store.recoveryRequired());
    assertNull(store.blocking());
  }
'''
    if marker not in state_test_text:
        raise SystemExit('MaintenanceStateStoreTest insertion marker not found')
    state_test_text = state_test_text.replace(marker, '\n' + addition + marker, 1)
state_test.write_text(state_test_text)

# 6) Exercise bridge race policy in CI without touching real ACLs.
bridge_test = Path('scripts/test-backup-read-bridge.py')
bridge_test.write_text('''#!/usr/bin/env python3
import importlib.util
from pathlib import Path
import stat
from types import SimpleNamespace

bridge_path = Path("host-agent/examples/backup-read-bridge.py")
spec = importlib.util.spec_from_file_location("plexonpanel_backup_read_bridge", bridge_path)
bridge = importlib.util.module_from_spec(spec)
assert spec.loader is not None
spec.loader.exec_module(bridge)

class FakeStat:
    def __init__(self, inode: int):
        self.st_mode = stat.S_IFREG | 0o600
        self.st_dev = 1
        self.st_ino = inode

original_lstat = bridge.lstat_safe
original_run = bridge.subprocess.run
original_sleep = bridge.time.sleep
try:
    fixed = FakeStat(10)
    bridge.lstat_safe = lambda _path: fixed
    bridge.subprocess.run = lambda *args, **kwargs: SimpleNamespace(returncode=1)
    bridge.time.sleep = lambda _seconds: None
    assert bridge.run_setfacl(Path("/srv/transient.db-wal"), "u:plexonpanel-host:r--") is False

    states = iter([FakeStat(20), FakeStat(21), FakeStat(21)])
    results = iter([SimpleNamespace(returncode=1), SimpleNamespace(returncode=0)])
    bridge.lstat_safe = lambda _path: next(states)
    bridge.subprocess.run = lambda *args, **kwargs: next(results)
    assert bridge.run_setfacl(Path("/srv/replaced.dat"), "u:plexonpanel-host:r--") is True

    root = Path("/opt/plexoncraft/server")
    assert bridge.excluded_path(root, root / "plugins/PlexonPanel/identity/device.key")
    assert bridge.excluded_path(root, root / "plugins/PlexonPanel/audit/audit.jsonl")
    assert bridge.excluded_path(root, root / "plugins/spark/tmp/cache.bin")
    assert not bridge.excluded_path(root, root / "plugins/GhostBlocks/ghostblocks.yml")
finally:
    bridge.lstat_safe = original_lstat
    bridge.subprocess.run = original_run
    bridge.time.sleep = original_sleep
''')

# 7) Keep bridge syntax/race checks in the normal x64+ARM CI contract.
build_path = Path('.github/workflows/build.yml')
build_text = build_path.read_text()
if 'name: Validate backup read bridge' not in build_text:
    marker = '''      - name: Test, check, Javadoc and build matched pair
        run: ./gradlew --no-daemon clean test check javadoc :agent:jar :host-agent:jar
'''
    step = '''      - name: Validate backup read bridge
        shell: bash
        run: |
          set -euo pipefail
          python3 -m py_compile host-agent/examples/backup-read-bridge.py
          python3 scripts/test-backup-read-bridge.py

'''
    if marker not in build_text:
        raise SystemExit('Build workflow test marker not found')
    build_text = build_text.replace(marker, step + marker, 1)
build_path.write_text(build_text)
