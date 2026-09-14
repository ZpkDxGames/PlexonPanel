# Step 5 — Manual-only Host backup migration

PlexonPanel full backups are Host-owned and manual-only. The Paper plugin no longer coordinates backup saves, autosave leases, maintenance warnings, final save flushes, shutdown, archive creation, provider upload, or restart verification.

## Paper migration

The following legacy Paper keys are retired and ignored if they remain in an upgraded `plugins/PlexonPanel/config.yml`:

```yaml
backups:
  enabled: true
  allow-host-schedule: true
```

They can no longer advertise or enable `backup.*`, `maintenance.*`, `provider.*`, or systemd lifecycle authority on Paper. The shipped Paper examples no longer contain this block.

The Paper runtime no longer accepts `backup.coordination` or `maintenance.coordination` messages and no longer emits their result messages. `BackupCoordinator` and `MaintenanceCoordinator` are removed.

## Host migration

Two historical automatic backup inputs are retired:

- `backups.intervalMinutes` from the Host JSON config;
- `fullRestorePoint.schedule` from `maintenance-settings.json`.

The Host parser continues to tolerate an old `intervalMinutes` field for upgrade compatibility, but no runtime code reads it to schedule work. Shipped Host examples omit it. `backup.preflight` reports `legacyIntervalMinutes: 0` and `backupMode: MANUAL_FULL_ONLY`.

On Host startup and on every maintenance-settings update, the persisted full-restore-point schedule is rewritten disabled. Only the independent restart schedule continues to run automatically. A historical automatically queued full-backup job is rejected before the destructive phase.

The legacy live-snapshot runtime has been removed from `HostMain`; `backup.create` is retained only as an explicit compatibility alias for the same manual cold full-backup job as `maintenance.full-backup.create`.

## Host-local maintenance transport

When Minecraft is online, manual full backup uses the Step 2 Host-local command channel. Production uses loopback RCON with the password stored only in the Host secret file. Browser, relay and Paper payloads never contain the credential and cannot supply arbitrary maintenance command text.

The Host owns the fixed warning sequence:

- 30 minutes
- 15 minutes
- 1 minute
- 30 seconds
- 15 seconds
- 5 seconds

Immediately before systemd stop, the Host requires a successful `save-all flush`. The durable countdown survives browser disconnects and Host process restarts without replaying already-consumed warning boundaries.

## Manual full-backup path

1. An authorized user explicitly invokes the full-backup action.
2. Host performs Step 4 local/provider preflight before countdown.
3. Host performs the Step 2 warning sequence and final save flush through the Host-local command channel.
4. Host stops the configured systemd service and verifies the Step 3 stopped-service/MainPID proof.
5. Host creates the cold restore point and performs Step 3 local ZIP/hash verification.
6. Host applies the Step 4 Google Drive/rclone promotion and failure policy.
7. Host restores service availability as configured and verifies Host-local Minecraft readiness.

Paper websocket state is informational only in `HostMain`. The full-backup path can proceed while the Paper plugin is disabled or disconnected.

## Failure semantics retained

- Provider authentication/preflight failure aborts before the countdown.
- A failed final save flush prevents systemd stop.
- Duplicate destructive work is rejected as `BUSY` by the durable Host job/operation lock.
- A verified local backup with failed off-site promotion remains `DEGRADED` according to Step 4 and can be retried explicitly.
- Recovery-required destructive state blocks new operations until recovery is resolved.

## Rollback

Do not restore the removed Paper coordinators, Paper save lease, Paper maintenance bridge, live-snapshot scheduler, or calendar full-backup scheduler independently. If architectural rollback is required, roll back the matched Host/Paper/dashboard release together.

## Runtime certification still required

Automated CI is necessary but not sufficient for final release certification. Production acceptance must still observe real warning delivery, save flush, systemd stop/start, local archive/hash verification, Google Drive upload, a complete backup with Paper disabled, Drive failure to `DEGRADED`, duplicate-run rejection, and provider-auth failure before countdown.
