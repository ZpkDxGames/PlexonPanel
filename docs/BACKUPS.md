# Backups & Maintenance

PlexonPanel keeps backup and maintenance authority in the Linux Host Companion. The browser is a control surface and the Paper plugin only coordinates player warnings and safe world persistence; neither receives systemd authority, the rclone credential file, Google OAuth tokens, or unrestricted host filesystem access.

## Backup classes

### Live Snapshot

Live snapshots preserve the existing non-disruptive backup path. Paper remains online, creates a renewable save lease, runs `save-all flush`, temporarily coordinates autosave, and the Host archives the configured live-snapshot include set. Active database formats remain excluded because third-party plugin writes cannot be frozen safely while Paper is running.

Use live snapshots for lightweight recovery points. The legacy `backups.intervalMinutes` scheduler remains supported for migrated installations, although new maintenance scheduling is calendar-aware and stored separately.

### Full Restore Point

A full restore point is a cold disaster-recovery archive of the configured Minecraft server root. The Host gracefully stops the configured systemd service, waits for Paper to disconnect, then archives worlds, player data, plugin JARs/configuration, plugin databases (`.db`, `.sqlite`, `.sqlite3`, `.mv.db`), permissions/economy state, datapacks and the other persistent files required to reconstruct PlexonCraft.

Full restore points use a dedicated inclusion policy. They do **not** reuse the live-snapshot database exclusions. Configured exclusions such as `logs`, `crash-reports`, cache folders and temporary data are skipped. The Host backup directory is outside `serverRoot`, and Host/rclone secrets are never part of the archive.

Archive creation is crash-safe:

1. pre-scan the stopped server tree and verify size/disk headroom;
2. write a unique staging `.partial` archive;
3. bound input bytes, output size and entry count;
4. reject symlinks/traversal;
5. force the archive to disk;
6. atomically promote it to `restore-points/<backupId>.zip`;
7. calculate SHA-256;
8. atomically write metadata.

A `.partial` file is never considered a restore point.

## Local layout

The recommended Host-owned layout is:

```text
/var/lib/plexonpanel-host/backups/
├── restore-points/
├── metadata/
└── staging/
```

Existing live-snapshot storage remains compatible. Never place the backup destination inside `/opt/plexoncraft/server`.

The dashboard intentionally keeps its historical 64 MiB browser download cap. Large full restore points stay on the Host and/or off-site provider instead of moving through dashboard JSON/chunk transport.

## Calendar-aware maintenance

Authoritative schedule configuration is stored by the Host at:

```text
/var/lib/plexonpanel-host/maintenance-settings.json
```

The dashboard edits this file through validated Host actions; it is not browser-local. New/migrated installations create the file with destructive schedules disabled until explicitly enabled.

Recommended PlexonCraft defaults are:

```text
timezone: America/Sao_Paulo
restart: daily 04:00
full restore point: Sunday 04:00
warnings: 900, 300, 60, 30, 10 seconds
remote retention: SINGLE_CURRENT
restart after full backup: true
```

The Host uses Java `ZoneId`/`ZonedDateTime` calendar calculations rather than a repeating 10080-minute delay. Each scheduled occurrence is durably claimed with `scheduleId + scheduledOccurrenceInstant`, preventing duplicate execution when the Host restarts near a schedule boundary.

When the Sunday full restore point and the daily restart are due at the same instant, they collapse into one serialized maintenance operation: warning → Paper flush → graceful stop → cold archive → SHA-256 → optional off-site promotion → start → authenticated Paper reconnect.

All destructive actions share one Host-level operation lock. A conflicting request returns `BUSY`; the dashboard should display the active operation instead of retrying it.

## Google Drive through rclone

PlexonPanel uses rclone rather than implementing a browser/Paper OAuth client. Install rclone at `/usr/bin/rclone` and create a protected Host-local configuration, for example:

```sh
sudo install -d -m 0750 -o plexonpanel-host -g plexonpanel-host /etc/plexonpanel-host
sudo -u plexonpanel-host /usr/bin/rclone config --config /etc/plexonpanel-host/rclone.conf
sudo chmod 0600 /etc/plexonpanel-host/rclone.conf
sudo chown plexonpanel-host:plexonpanel-host /etc/plexonpanel-host/rclone.conf
```

Create a Google Drive remote named `gdrive`, then set the Host backup provider fields:

```json
{
  "rcloneExecutable": "/usr/bin/rclone",
  "rcloneRemote": "gdrive:PlexonCraft",
  "rcloneConfig": "/etc/plexonpanel-host/rclone.conf"
}
```

PlexonPanel invokes rclone with fixed argument arrays through `ProcessBuilder`: no shell interpolation, browser-supplied executable, arbitrary flags, or credential output is allowed. Provider health checks are bounded/read-only and the dashboard receives only provider state/remote label, never the credential file or OAuth token.

### Single-current weekly promotion

The default `SINGLE_CURRENT` policy keeps one canonical disaster-recovery point without overwriting the only known-good object first:

```text
gdrive:PlexonCraft/
├── PlexonCraft-Latest.zip
├── PlexonCraft-Latest.json
└── staging/
```

The Host uploads a unique staging archive, verifies remote size, uploads metadata, preserves the previous canonical objects in recovery staging, promotes the new archive/metadata, verifies the promoted archive, then removes temporary objects. If upload or promotion fails, the prior canonical restore point remains available and the local archive is retained for `Retry upload`.

A Google Drive outage does not intentionally leave PlexonCraft offline. The weekly maintenance job records the off-site failure, preserves the prior remote restore point, and brings the server back online unless a separate restore-recovery condition requires it to remain stopped.

## Restore

Restore remains Owner-only and requires `backup.restore` plus local `backups.enabled`/`restoreEnabled` gates. The dashboard first obtains a one-minute device/archive-bound restore grant and requires typing the configured server name.

For a full restore point the Host then:

1. gracefully stops the systemd service if it is running and verifies Paper disconnect;
2. if the local ZIP was deleted but verified off-site metadata remains, downloads the configured remote object to Host staging, verifies size and SHA-256, then promotes it back to the local restore candidate;
3. verifies the selected archive SHA-256;
4. creates an emergency cold pre-restore backup;
5. creates a restore journal;
6. extracts into Host staging with ZIP-slip, traversal, control-character, duplicate-entry, symlink and expansion-limit protection;
7. atomically replaces top-level targets where supported;
8. rolls back from the journal on failure;
9. removes the journal only after a complete restore/rollback;
10. optionally starts the service and requires systemd active plus authenticated Paper reconnect before returning a running result.

Remote data is never streamed directly into live server paths.

Emergency pre-restore archives cannot be deleted from the dashboard.

## Crash recovery

The Host persists scheduled occurrence claims and destructive job state under its data directory. An unfinished destructive job or restore journal fails closed and is surfaced as recovery-required instead of being assumed successful.

For an interrupted restore, keep Paper stopped, inspect Host logs/storage permissions, then run the matched Host JAR:

```sh
sudo -u plexonpanel-host /usr/bin/java \
  -jar /opt/plexonpanel-host/plexonpanel-host-<version>.jar \
  /etc/plexonpanel-host/host-config.json \
  --recover-restore
```

Recovery restores journaled originals and removes newly introduced targets. Do not delete a restore journal simply to bypass recovery.

## Host permissions

Run `plexonpanel-host` as a dedicated non-root account. It needs exactly enough access to:

- read the configured Minecraft server root;
- write its Host data/backup directory;
- read `/etc/plexonpanel-host/rclone.conf`;
- read the PlexonPanel device registry/state it already uses;
- invoke only the configured PlexonCraft systemd unit.

Do not grant unrestricted root shell access. If sudo/polkit is used for systemd, constrain it to the exact configured service.

A typical data-directory ownership setup is:

```sh
sudo install -d -m 0750 -o plexonpanel-host -g plexonpanel-host \
  /var/lib/plexonpanel-host \
  /var/lib/plexonpanel-host/backups
```

The Minecraft tree must remain readable to the Host during cold backup and writable only where existing restore policy explicitly permits replacement.

## Dashboard capabilities

Host capability configuration controls what the dashboard can expose. The maintenance release adds:

```text
maintenance.view
maintenance.configure
maintenance.restart
maintenance.run
provider.view
provider.test
```

Backup scopes remain:

```text
backup.view
backup.create
backup.download
backup.delete
backup.restore
```

`maintenance.configure` and restore operations should remain Owner/elevated-only. Paper deliberately does not advertise Host-only maintenance/provider scopes.

## Operational validation

Before enabling automatic weekly downtime on production PlexonCraft:

1. test `provider.test` from the dashboard;
2. create a manual live snapshot;
3. create a manual full restore point in a maintenance window;
4. prove the ZIP contains the expected plugin database files and excludes Host/rclone secrets;
5. verify the SHA-256 and remote canonical object;
6. simulate an unavailable remote and verify PlexonCraft returns online while the old remote point survives;
7. test `Retry upload`;
8. perform a restore on a disposable/copy environment and prove emergency backup, rollback journal and Paper reconnect behavior;
9. only then enable the recurring schedule.

Source/CI success is not a substitute for these live-host recovery tests.
