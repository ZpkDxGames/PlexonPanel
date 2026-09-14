# Step 5 — Manual-only Host backup migration

PlexonPanel full backups are now Host-owned and manual-only. Paper no longer coordinates backup saves, warnings, autosave leases, shutdown, archive creation, Google Drive upload, or restart verification.

## Removed Paper configuration

The following Paper-plugin keys are retired and ignored if they remain in an upgraded `plugins/PlexonPanel/config.yml`:

```yaml
backups:
  enabled: true
  allow-host-schedule: true
```

Remove that block after upgrading. It can no longer advertise or enable `backup.create`, maintenance, provider, or systemd lifecycle capabilities on Paper.

## Removed automatic backup scheduling

The Host no longer executes recurring backups from either of these legacy inputs:

- `backups.intervalMinutes`
- `maintenance-settings.json -> fullRestorePoint.schedule`

Existing values may remain on disk during migration, but they are non-authoritative. On Host startup, the persisted full-restore-point schedule is rewritten disabled. No interval scheduler or calendar full-backup scheduler is started. Restart scheduling remains independent and may stay enabled.

`backup.create` is retained only as an explicit compatibility alias for the same manual cold full-backup job as `maintenance.full-backup.create`. It does not create a live snapshot and is never invoked by a scheduler.

## Host-local command channel

When the server is online, a manual full backup uses the Host-local RCON maintenance channel. Configure it in the Host config:

```json
"commandChannel": {
  "enabled": true,
  "host": "127.0.0.1",
  "port": 25575,
  "secretFile": "/etc/plexonpanel-host/rcon.secret",
  "commandTimeoutMillis": 5000,
  "readinessTimeoutSeconds": 180
}
```

RCON must be bound/firewalled to loopback. Store only the password in the secret file and restrict that file to the Host service account (for example mode `0600`). The credential is never sent to the dashboard, relay, Paper, audit payloads, or diagnostics.

The Host owns the fixed full-backup warning sequence: 30 minutes, 15 minutes, 1 minute, 30 seconds, 15 seconds, and 5 seconds. Immediately before systemd stop it issues `save-all flush`; failure prevents the stop/archive phase. Countdown state is persisted under the Host data directory so a Host restart during countdown resumes from the original timestamp without replaying already-emitted warnings.

## Full backup flow

1. A user explicitly starts a full backup from an authorized dashboard/device action.
2. The Host performs local/provider preflight before the countdown.
3. If Minecraft is running, the Host performs the fixed warning countdown and final save flush over loopback RCON.
4. The Host stops the configured systemd service and verifies the process/readiness channel is down.
5. The Host creates and verifies the cold local restore point.
6. The Host applies the Google Drive/rclone upload and failure policy from Step 4.
7. The Host restarts Minecraft where required and verifies systemd plus the Host-local readiness channel.

The Paper plugin may be disabled or disconnected for the entire operation.

## Rollback note

Do not re-enable the removed Paper coordination classes or the recurring backup schedulers as a rollback mechanism. Roll back the matched Host/Paper/dashboard release together if an architectural rollback is required.
