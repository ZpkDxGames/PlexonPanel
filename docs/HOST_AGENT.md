# Optional Linux host companion

The host runs Java 25 as a dedicated non-root Linux user, with no inbound listener. It owns an independent Ed25519 identity and can operate only locally configured paths, executable and exact systemd unit. The Host Companion is also the sole authoritative source for PlexonPanel server-console capture, replay, and retained-history queries. Paper remains responsible for Paper/Bukkit-only runtime operations such as locally allowlisted console command execution, but it no longer tails `logs/latest.log` or provides fallback console history. Authorization for already paired devices is mirrored into Host-owned local state so Host-backed controls can remain available while Paper is stopped; pairing and access mutation remain Paper-authoritative.

## Ubuntu 24.04 installation

Use a disposable server first. Adjust the example user, paths and `plexoncraft.service` consistently.

1. Install Java 25 and verify `/usr/bin/java -version`. Install `/usr/bin/rclone` only for optional offsite backups.
2. Create `plexonpanel-host` as a system user with its own private group and no login shell. A permanently shared Paper/Host access-registry group is no longer required for authorization; use narrowly scoped groups or ACLs only where another enabled Host feature actually needs filesystem access.
3. Install the JAR under root-owned `/opt/plexonpanel-host/`. Install root-owned `/etc/plexonpanel-host/host-config.json` mode 0640, readable by the private host group. The host user must not modify its JAR/config/unit/polkit rule.
4. Create `/var/lib/plexonpanel-host` and `/var/backups/plexonpanel`, host-owned mode 0700. The Host keeps its durable authorization mirror at `/var/lib/plexonpanel-host/access/devices.json`; the directory is 0700 and mirror/lock files are 0600. Keep token-refreshing rclone configuration under a dedicated host-owned path such as `/var/lib/plexonpanel-host/rclone/rclone.conf`; never make the root-owned `/etc/plexonpanel-host` policy directory writable. Backups must be outside the live root. Configure only existing, needed top-level include names.
5. Existing installations may leave `accessRegistry` pointed at Paper's `plugins/PlexonPanel/access/devices.json` for one-time migration. If no Host mirror exists yet and that legacy registry is readable, Host imports the validated state once. After the mirror exists, Host starts and authenticates already paired devices without touching the Paper/plugin path. Fresh and changed authorization state arrives from Paper through validated relay snapshots; a fresh installation may therefore bootstrap from an authenticated Paper snapshot without sharing the access directory. Never share private Paper keys or recursively loosen server permissions.
6. Grant host OS read/write access only to paths needed by enabled features. The unit's writable mount allowlist does not grant ownership. Test denied paths. Agents do not need each other's private keys. For console access, grant the Host service identity only the journal-reading access required for the configured Minecraft systemd unit; PlexonPanel never accepts a browser-supplied unit name.
7. Initialize as the host user:

```sh
sudo -u plexonpanel-host /usr/bin/java -jar /opt/plexonpanel-host/plexonpanel-host-3.5.0.jar --init /var/lib/plexonpanel-host
```

Copy only its printed public key to Paper `host.public-key`; copy the existing Paper UUID to host config. Both use the relay public key and WSS `/v1/agent` URL. Reload Paper locally.

8. Review/install the example service and polkit rule as root-owned files. The rule permits only start/stop/restart of exactly `plexoncraft.service` for the host user, with no wildcard or shell. Keep companion lifetime independent from Paper.
9. Reload systemd and start the host locally. Validate monitoring first, then selectively enable capabilities or intentionally apply the full-control example. Restart completes only after service startup and authenticated Paper reconnection.

## Full local Host capabilities

The full-control example enables telemetry, server status/start/stop/restart, bounded read-only file inspection, manual backup operations, audit, settings, and Host-owned console viewing when explicitly enabled. Device pairing, grant changes, revocation, and generation/revision ownership remain Paper-authoritative even if legacy Host configuration still lists `devices.*`; Host effective capabilities force those mutation scopes off, so an explicitly Host-targeted access change fails closed. Already paired credentials continue to authorize Host-backed actions from the private mirror while Paper is offline. Effective backup capability still requires `backups.enabled`. The stable 3.5.0 Host also forces server-tree create/write/upload/rename/delete and `backup.restore` off even if legacy configuration enables them; `restoreEnabled` cannot override that boundary. Effective console viewing requires Host `console.enabled` plus the corresponding `console.view.errors` or `console.view.full` capability. HostConfig continues rejecting Paper-only scope families. Read-only file operations remain confined under `serverRoot`, and lifecycle actions remain bound to the validated exact systemd service name.

## Console authority and retained history

The Host Companion is the single authoritative console source. Live capture follows the exact locally configured systemd unit through the locally validated `/usr/bin/journalctl` executable. The Host persists only the journal cursor/invocation metadata needed for bounded reconnect replay; it does not create an unlimited duplicate console database.

The authenticated Host control plane exposes two retained-history actions:

- `console.history` requires `console.view.full`;
- `console.history.errors` requires `console.view.errors` and still restricts results to warning/error classifications server-side.

History requests may contain only the typed fields `before`, `after`, `limit`, `invocationId`, and `levels`. Timestamps are ISO-8601 instants, `invocationId` is a validated 32-hex systemd invocation identifier, and severity values are drawn from the fixed console severity set. Unknown fields are rejected. The browser cannot provide a unit name, journal executable, output mode, or arbitrary `journalctl` flags.

Each history response returns at most 100 lines. The Host additionally bounds the number of journal records scanned, raw bytes consumed, returned encoded console bytes, and query execution time. Console content passes through the same shared redaction/classification path as live Host console output before it can reach the relay. Entries from a different `_SYSTEMD_UNIT` are rejected defensively even though `journalctl` is already unit-pinned.

**History is limited by systemd-journald retention on the VPS.** PlexonPanel must not describe this as unlimited history. If journald has rotated an entry away, PlexonPanel cannot recover it. A stopped Paper server does not remove retained journal history because the Host remains online and queries journald independently.

Paper no longer owns server-console capture or retained replay. Legacy Paper `console.stream-enabled`, `console.errors-enabled`, polling, batching, and ring-buffer settings may remain parseable during migration but do not restore Paper console authority and must not be treated as a fallback. Paper still owns `console.execute` because execution must remain on the Bukkit/Paper command path; viewing and execution intentionally have separate authorities.

## Backups

Configure `backups.enabled`, exact include names, retention 1–1000, size 1 MiB–1 TiB, the protected rclone destination, and the Host-local command channel. Full backups are manual-only. Before confirming **Fully Backup Now**, the operator chooses exactly 30, 15, 10, or 5 minutes; the Host validates and durably persists that countdown and its warning plan. Automatic restart scheduling is independent and uses the same presets, daily/weekly/selected-weekday schedules, and bounded shutdown/startup timeouts. It never creates a backup.

For an online server, the always-on Host sends the selected player warnings over fixed-command loopback RCON, requires an affirmative `save-all flush`, stops the exact configured systemd service, and independently proves it inactive before reading the server tree. Paper is not part of this critical path. The Host then creates a cold staged ZIP, verifies SHA-256/metadata locally, uploads through Google Drive/rclone staging and promotion, verifies the promoted remote object, and brings Minecraft back online with systemd plus RCON readiness checks.

Rclone uses only fixed `/usr/bin/rclone`, a configured `remote:path`, and a protected local config. Browsers cannot choose executable/remote arguments or see credentials. A connectivity test updates test status only; it does not claim a successful remote-backup verification. If the local archive verifies but Google Drive ultimately fails, the Host restores server availability and records a degraded/retryable job. **Retry Upload** reuses that verified local archive without another shutdown.

The job and selected countdown survive browser refresh, disconnect, and Host restart. The Dashboard reconstructs status from Host-owned durable state. Closing the browser does not cancel the operation, and the browser never owns the authoritative timer.

## Read-only boundary and historical restore recovery

Direct remote restore is not part of the stable 3.5.0 capability contract. The Host mounts the Minecraft tree read-only and forces `backup.restore` plus Host file mutations off. Perform a planned restore locally under the server operator's recovery procedure, outside the network-reachable Host process.

Historical interrupted-restore journals remain recognized so an upgrade cannot bypass an existing safety gate. On `RECOVERY_REQUIRED`, leave Paper stopped, inspect local journal/logs, repair storage/permissions and run:

```sh
sudo -u plexonpanel-host /usr/bin/java -jar /opt/plexonpanel-host/plexonpanel-host-3.5.0.jar /etc/plexonpanel-host/host-config.json --recover-restore
```

Recovery restores saved originals and removes new targets left by a historical interrupted restore; repeating it preserves originals already recovered. Malformed/unknown journals fail closed. Never delete a journal to bypass recovery. Validate files and gameplay before deliberately starting Paper. Real systemd/RCON/rclone interruption and Google Drive verification tests remain stable-release gates.
