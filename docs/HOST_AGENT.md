# Optional Linux host companion

The host runs Java 25 as a dedicated non-root Linux user, with no inbound listener. It owns an independent Ed25519 identity and can operate only locally configured paths, executable and exact systemd unit. Version 3.0.1 adds an explicit [full-control Host example](../host-agent/examples/host-config-full-control.json) while preserving the conservative [public example](../host-agent/examples/host-config.json). Host still has no player, console, chat or plugin authority and never scans Paper player data.

## Ubuntu 24.04 installation

Use a disposable server first. Adjust the example user, paths and `plexoncraft.service` consistently.

1. Install Java 25 and verify `/usr/bin/java -version`. Install `/usr/bin/rclone` only for optional offsite backups.
2. Create `plexonpanel-host` as a system user with its own private group and no login shell. Create `plexonpanel-access`; add Paper and host users to it and restart services after group changes.
3. Install the JAR under root-owned `/opt/plexonpanel-host/`. Install root-owned `/etc/plexonpanel-host/host-config.json` mode 0640, readable by the private host group. The host user must not modify its JAR/config/unit/polkit rule.
4. Create `/var/lib/plexonpanel-host` and `/var/backups/plexonpanel`, host-owned mode 0700. Backups must be outside the live root. Configure only needed top-level include names.
5. Start Paper once to generate `plugins/PlexonPanel/access/devices.json`. Make **only the access directory** group-owned by `plexonpanel-access`, mode 2770 (setgid), and registry/lock files 0660. Parents need traversal rights. Never share private Paper keys or recursively loosen server permissions.
6. Grant host OS read/write access only to paths needed by enabled features. The unit's writable mount allowlist does not grant ownership. Test denied paths. Agents do not need each other's private keys.
7. Initialize as the host user:

```sh
sudo -u plexonpanel-host /usr/bin/java -jar /opt/plexonpanel-host/plexonpanel-host-3.0.1.jar --init /var/lib/plexonpanel-host
```

Copy only its printed public key to Paper `host.public-key`; copy the existing Paper UUID to host config. Both use the relay public key and WSS `/v1/agent` URL. Reload Paper locally.

8. Review/install the example service and polkit rule as root-owned files. The rule permits only start/stop/restart of exactly `plexoncraft.service` for the host user, with no wildcard or shell. Keep companion lifetime independent from Paper.
9. Reload systemd and start the host locally. Validate monitoring first, then selectively enable capabilities or intentionally apply the full-control example. Restart completes only after service startup and authenticated Paper reconnection.

## Full local Host capabilities

The 3.0.1 full-control example enables telemetry, server status/start/stop/restart, all implemented file operations, the complete backup family, audit, devices and settings. Effective backup capability still requires `backups.enabled`; restore additionally requires `restoreEnabled`. HostConfig continues rejecting Paper-only scope families. File operations remain confined under `serverRoot`, and lifecycle actions remain bound to the validated exact systemd service name.

## Backups

Configure `backups.enabled`, exact include names, retention 1–1000, size 1 MiB–1 TiB and interval (zero disables scheduling). Host action capabilities still apply. Online backup needs Paper's remote-actions/backups enabled; unattended jobs also require `allow-host-schedule`.

A renewable save lease preserves world autosave settings, pauses saving and flushes on the Paper thread. Host renews every 20 seconds; Paper resumes after a 60-second lost-renewal watchdog. Backup aborts on lease loss. Completion/error/plugin shutdown restores previous autosave settings. Do not manually toggle saving during a lease.

Offline backup requires inactive/failed service. Bounded ZIP/SHA-256 metadata stay local. Symlinks, panel identities, audit/logs, credentials and active database files are excluded. A world lease cannot freeze third-party plugin writes: **stop Paper for consistent plugin data**. Validate includes against your plugin stack.

Rclone uses only fixed `/usr/bin/rclone`, configured remote:path and a protected local config. Browsers cannot choose executable/remote arguments. Offsite-copy failure preserves local data and reports failure. Local retention and remote-provider retention are separate. Dashboard listings are 50/page; browser downloads max 64 MiB. Retrieve larger archives locally/offsite. Download cancellation is available; running backup jobs are not browser-cancellable.

## Restore and recovery

Restore requires literal Owner, host backup.restore, local restoreEnabled, a stopped service and disconnected Paper. The UI obtains a one-minute device/archive-bound nonce, requires the typed configured server name and separate final confirmation.

The host verifies SHA-256, takes an emergency backup, validates bounded ZIP entries, stages on the server filesystem and journals original target existence before renames. Traversal, links, protected targets and expansion-limit violations fail. Plugin folders and root JARs are individual targets; panel identity stays protected. Restore never starts Paper automatically. Emergency archives cannot be deleted by the browser.

On recovery-required, leave Paper stopped, inspect local journal/logs, repair storage/permissions and run:

```sh
sudo -u plexonpanel-host /usr/bin/java -jar /opt/plexonpanel-host/plexonpanel-host-3.0.1.jar /etc/plexonpanel-host/host-config.json --recover-restore
```

Recovery restores saved originals and removes new targets; repeating it after interruption preserves originals already recovered. Malformed/unknown journals fail closed. Never delete a journal to bypass recovery. Validate files and gameplay before deliberately starting Paper. Actual systemd/rclone interruption tests remain release gates.
