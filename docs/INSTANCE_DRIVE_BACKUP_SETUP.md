# Connect the existing instance Drive folders

The observed `RCLONE_UNAVAILABLE` preflight means the **running selected Host** has no off-site destination. A screenshot of `VPS Servers Backup/PlexonCraft` and `VPS Servers Backup/TonimSMP` proves the folders exist, not that either Host has credentials or a configured remote. Do not delete/move existing Drive archives, rerun migration, change identities, or modify the Minecraft/relay services to connect them.

## Read-only check first

Use the reviewed `scripts/instance-drive-backup.py` from this repository on the VPS:

```bash
sudo python3 scripts/instance-drive-backup.py check
```

The output contains only public instance IDs, booleans and an include count. It never prints Host configuration, RCON secrets, rclone contents, OAuth tokens, provider fingerprints, or directory listings. It does not contact Drive or change any service or file. `providerConfiguredOnDisk` is explicitly disk configuration, not proof the running process loaded it. The Dashboard's provider status is the running Host authority.

If a matching remote is already configured on disk but the Dashboard says not configured, review the Host's `hostConfigRestartRequired`, make sure no maintenance job is running, and restart **only the affected Host**. Then test Drive and refresh preflight. Do not blindly run first-time setup over a previously configured/refreshed provider.

## Separate destinations

Open each existing Drive folder and copy its URL. The screenshot does not contain their IDs. Rclone's `root_folder_id` selects that precise folder, avoiding ambiguous duplicate folder names. Use separate per-instance OAuth files/accounts; do not share a live token-refresh file.

| Instance | Remote root | Host remote inside that folder |
|---|---|---|
| `plexoncraft` | Existing `PlexonCraft` folder ID | `gdrive:plexonpanel/<PlexonCraft server UUID>` |
| `tonimsmp` | Existing `TonimSMP` folder ID | `gdrive:plexonpanel/<TonimSMP server UUID>` |

The UUID subdirectory is required by schema 5 and is read from the existing Host identity. Existing manually uploaded archives in the folder are untouched; they do not automatically become verified PlexonPanel history. The setup helper refuses to assign the same known folder ID to both instances.

## Confirmed TonimSMP include correction

The operator's 2026-10-04 read-only inspection found all three dimension directories at `world/dimensions/minecraft/{overworld,the_nether,the_end}`. The configured top-level `world_nether`, `world_the_end` and `permissions.yml` are absent. Including `world` recursively covers the nested dimensions, players and shared world metadata. The current Host intentionally rejects any missing configured source; connecting Drive alone does not fix this include mismatch.

After confirming that exact layout, add **`--modern-world-layout` to both plan and apply** below for TonimSMP. This opt-in correction requires `world` included and all three ordinary dimension directories present with no symlink ancestors. It removes only the three named defaults when they are genuinely absent. Existing legacy folders/permission files remain included, and arbitrary missing/unreadable sources are never silently removed. Plan prints the proposed include list and removed names. Apply rechecks the correction before writes and retains the original include list in the private rollback copy. No world directory is moved, created or deleted.

Do not add this flag to another instance without confirming its own layout. The correction and Drive binding are applied together during the same stopped-Host setup window. Minecraft can remain online while preparing OAuth and reviewing a plan. Warn the operator before any Host stop/start; a real cold-backup job is a separate explicitly scheduled Minecraft shutdown/restart.

## First-time setup, one instance at a time

Install Ubuntu's rclone package if `/usr/bin/rclone` is missing. Keep the executable fixed to `/usr/bin/rclone`:

```bash
sudo apt-get update
sudo apt-get install rclone
```

The example below connects **TonimSMP**. Repeat it for PlexonCraft with `PP_KEY=plexoncraft` and that server's own folder URL and separately authorized OAuth file.

```bash
PP_KEY=tonimsmp
sudo install -d -o "pph-$PP_KEY" -g "pph-$PP_KEY" -m 0700 \
  "/var/lib/plexonpanel/instances/$PP_KEY/provider-setup"
sudo -u "pph-$PP_KEY" /usr/bin/rclone config \
  --config "/var/lib/plexonpanel/instances/$PP_KEY/provider-setup/rclone.conf"
```

Create exactly one remote named **gdrive**, select Google Drive, and authorize write access to the intended Google account. For a VPS without a browser, answer no to automatic browser authorization and follow rclone's headless authorization instructions using your own computer. Keep the resulting credentials in the VPS terminal and private file; never paste token output into chat, GitHub or Dashboard settings. This setup accepts an ordinary OAuth Drive remote. Shared Drive/service-account/resource-key configurations need a separately reviewed manual setup.

```bash
sudo chmod 0600 "/var/lib/plexonpanel/instances/$PP_KEY/provider-setup/rclone.conf"
read -r -p 'Paste this server’s existing Drive folder URL: ' PP_DRIVE_FOLDER
sudo python3 scripts/instance-drive-backup.py plan "$PP_KEY" \
  --folder "$PP_DRIVE_FOLDER" \
  --credential-file "/var/lib/plexonpanel/instances/$PP_KEY/provider-setup/rclone.conf"
```

The plan preserves identity, relay/WSS configuration, RCON settings, scopes/capabilities, include names, retention and maximum size. It changes only backup enablement and the validated provider binding. It does not execute a remote write, start a backup, stop Minecraft, or claim Drive authentication passed.

After reviewing the plan and checking that this Host has no active maintenance job, stop only that Host. Minecraft remains in its current state:

```bash
sudo systemctl stop "plexonpanel-host@$PP_KEY.service"
sudo python3 scripts/instance-drive-backup.py apply "$PP_KEY" \
  --folder "$PP_DRIVE_FOLDER" \
  --credential-file "/var/lib/plexonpanel/instances/$PP_KEY/provider-setup/rclone.conf"
```

Apply requires the selected Host to be inactive/failed with PID 0 and refuses existing provider runtime state or an already configured remote. It validates schema/identity/canonical paths and registry binding, keeps private root-owned rollback copies, installs the immutable seed root:`pph-key` 0640, and atomically updates the matching policy. It does not alter Minecraft files, Host permissions, provider runtime credentials, archives, pairing, or the other instance. It neither stops nor starts any service.

Validate using the **existing** Host JAR before starting it:

```bash
sudo /usr/bin/java -jar "/srv/plexonpanel/servers/$PP_KEY/host/plexonpanel-host-5.0.0.jar" \
  --validate-fleet "$PP_KEY" && \
  sudo systemctl start "plexonpanel-host@$PP_KEY.service"
```

On startup, the existing Host seeds its private writable provider configuration under `/var/lib/plexonpanel/instances/<key>/provider/`, mode 0700 with config/marker 0600. Token refresh is isolated there; the immutable `/etc` directory remains non-writable by the Host. No Host JAR replacement is required for this setup.

## Initialize the first backup namespace

The local setup helper deliberately makes no network calls. It does **not** create the remote `plexonpanel/<server UUID>` directory. The Host connectivity test lists that exact directory; a new instance can therefore have valid OAuth and local permissions yet fail preflight because its namespace does not exist.

After the Host starts and its private runtime configuration is present, create only the reviewed instance namespace using that instance's Host account and runtime credentials. Keep Minecraft and the Host running; this step has no service control or archive upload. Do not use the immutable seed or another instance's credentials for token refresh.

```bash
PP_SERVER_ID="$(sudo python3 -c \
  'import json, sys; print(json.load(open(sys.argv[1]))["serverId"])' \
  "/etc/plexonpanel/instances/$PP_KEY/host-config.json")" && \
sudo -u "pph-$PP_KEY" /usr/bin/rclone mkdir \
  "gdrive:plexonpanel/$PP_SERVER_ID" \
  --config "/var/lib/plexonpanel/instances/$PP_KEY/provider/rclone.conf"
```

Rclone `mkdir` creates the path if absent; rerunning it preserves an existing directory. The configured Drive `root_folder_id` keeps this path inside the selected server's existing Drive folder. Existing manually uploaded archives and the other server's namespace are untouched. A successful folder creation establishes directory access, not a verified backup or restore.

If connectivity fails, separately list `gdrive:` and `gdrive:plexonpanel/<server UUID>` with the same Host account/runtime config. Root access succeeding while the namespace reports `directory not found` identifies the missing directory; it is not evidence that local credential or source permissions need broadening. Keep OAuth content, raw debug logs and directory listings out of chat. After creating the namespace, run **Test Google Drive** in the Dashboard and refresh **backup preflight** before planning a real cold backup.

## Existing provider recovery and rollback

For `EXISTING_PROVIDER_STATE_REQUIRES_OFFLINE_RESEED` or `EXISTING_PROVIDER_REQUIRES_MANUAL_REVIEW`, do not delete the provider directory or copy another instance's state. Preserve the current configuration and use the existing administrator CLI `--plan-provider-reseed <key>` / `--reseed-provider <key>` only after following `PROVIDER_RUNTIME_STATE.md`. The first-time helper deliberately cannot overwrite refreshed credentials.

Apply reports a private rollback directory. Before the Host has been started with the new provider, restore that directory's original `host-config.json` and, if present, `rclone.conf` with root:`pph-key` 0640 while the Host stays stopped. If no seed existed before setup, preserve the newly installed seed privately rather than deleting unrelated files. Validate before restarting. If the Host has already seeded/refreshed provider state, follow offline provider reseed/preservation; a policy rollback alone is not sufficient to reconcile the credential fingerprint. Existing archives are never deleted by either setup or this recovery procedure.

## Validate the real backup separately

1. Select the correct named server in the Dashboard. Confirm its Host reconnects and provider configuration is loaded.
2. Use **Test Google Drive**. This checks connectivity but does not claim a verified backup.
3. Refresh **backup preflight**. Missing provider leaves later storage/source checks unknown, not failed. Repair only the actual reported failure. For foreign-owned VS Code uploads, install the specific file under the matching `mc-key` owner; never grant Host write access or silently skip unreadable data.
4. Review the include list against actual top-level worlds/plugins. The helper reports missing include count but does not change it or scan the full live world tree. The Host preflight remains authoritative for permissions, symlinks, capacity and source safety.
5. Choose a planned maintenance window and appropriate player countdown before **Fully Backup Now**. This is a real cold backup and deliberately stops/restarts the selected Minecraft instance. No such job is executed by source tests or provider setup.
6. Record the real job ID, local verification, exact destination, remote verification, service/RCON recovery and retry behavior. Keep a verified retained restore point before destructive recovery tests.

The fixture tests validate argument/identity/path policy, private file reads, symlink/hardlink rejection, immutable proposal behavior, inactive-Host enforcement, atomic policy/seed installation and private rollback preservation. They do not certify Google OAuth, network access, uploads, token refresh, actual systemd control, or a Minecraft cold backup.

Primary references: [rclone Drive root folder ID](https://rclone.org/drive/#root-folder-id), [create an absent remote directory](https://rclone.org/commands/rclone_mkdir/), [headless setup](https://rclone.org/remote_setup/), [private token state](PROVIDER_RUNTIME_STATE.md), [cold backup contract](BACKUPS.md).
