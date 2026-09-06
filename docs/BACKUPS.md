# Backups and restore

Install the host according to [HOST_AGENT](HOST_AGENT.md) before enabling backup capabilities.

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
sudo -u plexonpanel-host /usr/bin/java -jar /opt/plexonpanel-host/plexonpanel-host-2.0.0.jar /etc/plexonpanel-host/host-config.json --recover-restore
```

Recovery restores saved originals and removes new targets; repeating it after interruption preserves originals already recovered. Malformed/unknown journals fail closed. Never delete a journal to bypass recovery. Validate files and gameplay before deliberately starting Paper. Actual ARM64/systemd/rclone interruption tests remain release gates.
