# PlexonPanel 3.3.0 — Backups & Maintenance

PlexonPanel 3.3.0 extends the existing protocol-3 control plane into a Host-authoritative maintenance and disaster-recovery subsystem. The Paper plugin and Host Companion remain a matched pair; Google Drive credentials, systemd authority, cold archive creation, and restore execution remain Host-only.

## Automated restart scheduling

- Added Host-owned calendar schedules using IANA timezones.
- Supports daily, weekly, and selected-weekday schedules.
- Persists scheduled-occurrence claims before execution to suppress duplicate maintenance after Host restarts near the trigger minute.
- Adds configurable warning intervals, graceful Paper save flushing, systemd stop/start, startup timeout, and authenticated Paper reconnect before a restart is considered successful.
- When the Sunday full restore point overlaps the daily restart, the Host collapses both into one cold maintenance operation.

## Full cold restore points

- Added a separate `FULL_RESTORE_POINT` path instead of weakening the existing live-snapshot safety policy.
- Full restore points run only after Paper is stopped and therefore include mutable plugin database state such as SQLite/H2 files together with worlds, player data, plugin state, configs, permissions/economy data, and other persistent server files.
- Backup storage is required outside the Minecraft server root.
- Archive creation uses bounded pre-scan/disk checks, staging `.partial` output, fsync, atomic promotion, entry/size limits, symlink/traversal rejection, SHA-256 metadata, and configurable local retention.
- Noise such as logs, crash reports, and caches is excluded by configurable full-restore-point policy without reusing the live-snapshot database exclusion list.

## Google Drive / rclone

- Promoted the existing rclone configuration into the full restore-point provider path.
- Uses fixed ProcessBuilder argument arrays with Host-local credentials; the browser and Paper plugin never receive OAuth/rclone secrets.
- Uploads to a unique staging object, verifies it, preserves the previous canonical restore point, then promotes the replacement.
- A failed upload or promotion keeps the completed local archive and the prior remote restore point.
- Added provider health/status and a retryable upload path so an existing local restore point can be re-sent without recreating the cold backup.

## Restore improvements

- Full restore remains Owner-only and requires the existing device/capability authorization plus a short-lived archive/device-bound confirmation token and typed server name.
- The Host stops PlexonCraft when needed, verifies the selected archive, creates an emergency cold pre-restore backup, writes a rollback journal, extracts through bounded safe staging, and rolls back on replacement failure.
- ZIP-slip, traversal, duplicate-entry, unsafe-name, symlink, and expansion-limit protections remain fail-closed.
- If verified off-site metadata remains but the local ZIP was removed, the Host can retrieve the canonical remote archive to Host staging, verify size/SHA-256, and continue through the normal restore pipeline.
- Optional post-restore start still requires successful systemd state plus a fresh authenticated Paper reconnect.

## Maintenance recovery and observability

- Added durable destructive-job state and crash-recovery gating.
- Added Host-reported current phase, next schedule occurrences, backup progress, provider status, local/off-site verification metadata, and recovery-required state for the matched Dashboard workspace.
- All destructive maintenance uses the existing Host operation lock; conflicting requests fail with `BUSY` rather than racing.

## Dashboard pairing

The matched Dashboard Backups & Maintenance workspace provides schedule editing, real progress, inventory, provider status, manual restart, live snapshot, full restore point, verification, retry upload, restore/delete controls, and recovery/offline state. Browser archive downloads remain capped at 64 MiB.

## Migration

- Protocol generation remains 3.
- Existing live snapshots and legacy backup metadata remain supported.
- Existing `backups.intervalMinutes` behavior remains available for the legacy live-snapshot scheduler.
- New Host maintenance settings default destructive schedules to disabled for migrated installations. Enabling automatic downtime requires an explicit saved schedule.

## Validation status

Canonical CI builds and tests the Paper/Host pair on the repository-supported runner matrix. The release remains operationally dependent on live-host certification for actual systemd lifecycle, Google Drive promotion/outage behavior, and destructive restore recovery before production deployment is declared complete.
