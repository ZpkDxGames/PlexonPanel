# Backups & Maintenance — Host-authoritative manual full backups

PlexonPanel backup authority lives in the always-on Linux Host Companion. The browser is an authenticated control surface, the relay is transport/routing, and the Paper plugin provides Paper-specific runtime data/actions. Paper is not required for the backup critical path.

Automatic backups and live snapshots are retired product behavior.

> Automatic backups are retired. Full backup creation is manually initiated through **Fully Backup Now** and executed by the always-on Host Companion.

## Final workflow

A normal **Fully Backup Now** operation is:

1. Dashboard requests Host `backup.preflight` and displays the authoritative result.
2. Operator chooses a 30-, 15-, 10-, or 5-minute initial player countdown and explicitly confirms **Fully Backup Now**.
3. Host creates a durable manual `FULL_RESTORE_POINT` job.
4. If Minecraft is online, Host validates and persists the selected warning plan, then sends its notices through the Host-local command channel/RCON. The selected duration is announced immediately; the 1m / 30s / 15s / 5s safety boundaries follow when they fit. The 30-minute plan also includes 15m.
5. Host requires an affirmative `save-all flush` result.
6. Host stops the configured Minecraft systemd service.
7. Host independently proves the service is stopped before archive work begins.
8. Host creates the cold full-server restore point through `.partial` staging.
9. Host verifies the local archive and persists SHA-256/metadata.
10. Host uploads through configured Google Drive/rclone staging/promotion.
11. Host verifies the promoted remote copy.
12. If this workflow stopped Minecraft, Host starts it unconditionally and verifies readiness.
13. The durable job becomes `COMPLETED`, `DEGRADED`, `FAILED`, or `RECOVERY_REQUIRED` as appropriate.

Browser refresh/disconnect never cancels the Host job. `maintenance.status` exposes the durable current operation. During `COUNTDOWN`, it also exposes the durable initial duration, warning plan, deadline, consumed warnings, and remaining seconds; the browser must not create its own authoritative countdown.

## Manual-only policy

The Host scheduler retains restart-only scheduling. It does not schedule full backups.

Legacy full-backup schedule and `restartAfter` fields can remain serialized for rolling-upgrade compatibility, but:

- automatic full-backup execution is rejected;
- legacy full-backup schedule members are ignored by the Host settings model;
- legacy `restartAfter: false` is normalized to `true`;
- the manual full-backup orchestration path restarts Minecraft whenever that operation stopped it, regardless of serialized `restartAfter`.

No live-snapshot action, Paper backup lease/coordinator, or recurring backup scheduler belongs in the supported product path.

## Preflight

`backup.preflight` runs on the Host and fails closed before a backup countdown begins. It verifies, among other Host-owned requirements:

- preflight sizing and cold archive creation traverse only the exact top-level names in Host `backups.include`; unrelated server-root entries are neither read nor archived;
- the configured include list is non-empty and unique when backups are enabled, and a missing, unreadable or symlinked configured source fails closed;
- Google Drive/rclone is configured according to policy;
- `/usr/bin/rclone` exists, is executable and is not a symlink;
- the configured rclone config is absolute, readable and not a symlink;
- backup/staging directories exist, are writable and are not symlinks;
- the source tree can be scanned safely;
- source symlinks/traversal are rejected;
- source size stays within configured limits;
- local free space is sufficient;
- the configured provider passes a bounded connectivity test.

The Host action additionally reports safe operational facts such as Host authentication, destructive-operation busy state, recovery state, backup-root writability and command-channel configuration. Service/RCON readiness is also Host-owned runtime state; Paper connectivity is not a precondition.

Never expose rclone/RCON secrets through the Dashboard or relay.

## Cold archive safety

Full restore points are created only when the Host has proven Minecraft stopped. The backup directory must remain outside the Minecraft server root.

Recommended layout:

```text
/var/lib/plexonpanel-host/backups/
├── restore-points/
├── metadata/
└── staging/
```

Archive creation is crash-safe:

1. pre-scan and validate size/disk headroom;
2. write a unique `.partial` staging archive;
3. bound input/output/entry counts;
4. reject symlinks and traversal;
5. force the archive to disk;
6. atomically promote to `restore-points/<backupId>.zip`;
7. calculate SHA-256;
8. atomically persist metadata.

A `.partial` file is never a valid restore point.

Host/rclone secrets and Host data are excluded from the server archive.

## Google Drive through rclone

Install rclone on the Host and keep its configuration protected under the `plexonpanel-host` account. Example:

```sh
sudo install -d -m 0750 -o plexonpanel-host -g plexonpanel-host /etc/plexonpanel-host
sudo -u plexonpanel-host /usr/bin/rclone config --config /etc/plexonpanel-host/rclone.conf
sudo chmod 0600 /etc/plexonpanel-host/rclone.conf
sudo chown plexonpanel-host:plexonpanel-host /etc/plexonpanel-host/rclone.conf
```

Example Host provider fields:

```json
{
  "rcloneExecutable": "/usr/bin/rclone",
  "rcloneRemote": "gdrive:PlexonCraft",
  "rcloneConfig": "/etc/plexonpanel-host/rclone.conf"
}
```

PlexonPanel invokes rclone through fixed `ProcessBuilder` argument arrays. No browser-supplied executable, arbitrary flags, shell interpolation, credentials, or token output are allowed.

### Safe promotion

For `SINGLE_CURRENT`, the Host uploads a unique staging object, verifies it, promotes it to the canonical object and verifies the promoted result before cleanup. The previous known-good remote copy remains protected until the replacement is verified.

A bounded provider failure after local verification does not invalidate the local backup. The Host restores Minecraft availability and records a degraded/retryable result.

`backup.full.retry-upload` reuses the existing verified local archive. It does not perform another Minecraft shutdown.

## Durable job states

The Host persists destructive job state under its data directory. The public job contract includes job ID, kind, requester, phase and phase timestamp, start/update/completion timestamps, result/error fields, backup ID, progress percent, local/remote verification flags and restart-recovery state.

The manual full-backup path can expose these phases:

```text
QUEUED
PREFLIGHT
COUNTDOWN
FINAL_SAVE
STOPPING_SERVER
WAITING_FOR_STOP
ARCHIVING
VERIFYING_LOCAL
VERIFYING_REMOTE
STARTING_SERVER
VERIFYING_STARTUP
COMPLETED
DEGRADED
FAILED
RECOVERY_REQUIRED
```

Live archive/upload byte progress is emitted separately with the same durable `jobId`; consumers must match job IDs and must not use stale progress from an earlier operation. Upload progress can report `UPLOADING_REMOTE` while the durable maintenance job remains inside its archive/provider execution segment.

## Failure policy

### Before shutdown

If provider/storage preflight, command channel, final save, or another pre-destructive requirement fails, the job fails before the stop boundary and Minecraft remains online.

### After shutdown / ambiguous destructive state

If failure occurs after the Host may have stopped Minecraft, or a Host restart interrupts an ambiguous destructive phase, the durable job becomes `RECOVERY_REQUIRED`. Another destructive maintenance operation is blocked until recovery is reconciled.

The Host attempts bounded availability recovery after an in-process failure, but it does not silently clear durable recovery state when safety is ambiguous.

### Degraded remote failure

If the local archive is verified but remote upload/verification fails:

- preserve the valid local restore point;
- preserve the previous known-good remote object;
- restore Minecraft online when this workflow stopped it;
- mark the operation degraded/retryable;
- expose Retry Upload without another shutdown.

## Restore and recovery

Direct remote restore is not part of the stable Host capability contract. The always-on Host mounts the Minecraft tree read-only and forces `backup.restore` off even when a rolling-upgrade configuration still contains the legacy key. Perform a planned restore locally under the server operator's recovery procedure, outside the network-reachable Host process.

Historical interrupted-restore journals remain recognized so an upgrade cannot bypass an existing safety gate. Do not merge restore semantics into **Fully Backup Now** and do not delete recovery journals to bypass safety.

For interrupted restore recovery, keep Minecraft stopped and run the matched Host artifact:

```sh
sudo -u plexonpanel-host /usr/bin/java \
  -jar /opt/plexonpanel-host/plexonpanel-host-<version>.jar \
  /etc/plexonpanel-host/host-config.json \
  --recover-restore
```

## Host permissions

Run `plexonpanel-host` as a dedicated non-root identity. Mount `serverRoot` read-only and grant only the access required to:

- read the configured Minecraft server root;
- write the Host data/backup directory;
- read protected rclone configuration;
- read/write its durable authorization mirror/state as configured;
- read the configured journald unit for console authority;
- control only the configured Minecraft systemd unit;
- reach the configured local RCON/command channel.

Do not recursively `chown` the server to the Host, use `chmod 777`, or run the Host as root.

## Production verification

Repository tests/CI are not production certification. Before claiming the Step 8 release certified, record exact deployed backend/dashboard SHAs and artifact hashes, then execute the real Host/systemd/RCON/rclone gates on the production VPS, including:

- Host remains connected and authorized while Minecraft/Paper is stopped;
- warning boundaries are delivered by Host RCON;
- failed/blank final save prevents shutdown;
- stop proof reaches inactive + MainPID zero/not alive;
- cold archive and SHA/metadata verify;
- Google Drive staging/promotion/final verification succeeds;
- Minecraft automatically returns online;
- Dashboard reconnect reconstructs the active job;
- controlled remote failure produces degraded/retryable state;
- Retry Upload succeeds without a second shutdown;
- journald console history remains available while Paper is offline.

If a runtime gate is not executed, record it as `NOT_EXECUTED`; never infer production success from source or CI alone.
