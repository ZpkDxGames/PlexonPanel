# PlexonPanel live-backup read contract

PlexonPanel Host is intentionally non-root and must not own or write the live Minecraft server tree. On Linux, ordinary directory default ACLs are not sufficient for live backups when Paper or a plugin creates/replaces a file as mode `0600`: the new file can retain a named ACL entry for `plexonpanel-host` while its ACL mask removes the effective read bit.

PlexonPanel 3.4.1 therefore ships an optional **root-owned, local-only backup read bridge** for servers that exhibit that behavior. It is the supported replacement for ad-hoc `setfacl` watchers.

## Security model

The bridge:

- runs as a separate root-owned systemd service; the Java Host remains `plexonpanel-host` and non-root;
- reads a root-owned JSON file only at startup;
- accepts no network traffic and no command, path, user, or argument from the browser, relay, Paper, or Host process;
- is restricted to one fixed `serverRoot` and a bounded set of top-level backup include names;
- rejects a symlinked server root and never follows symlinks while scanning/watching includes;
- grants the named `plexonpanel-host` user **read** on regular files and **read/traverse** on included directories, never write;
- grants only traverse on the server root itself;
- explicitly excludes `plugins/PlexonPanel/access` and its descendants because that subtree has a separate shared read/write group contract between Paper and Host;
- repairs ACLs after create, close-write, attribute-change, and atomic move-in events;
- treats a target that disappears between an inotify event and `setfacl` as normal transient file churn, while persistent ACL failures still fail closed;
- uses fixed `/usr/bin/setfacl` argv arrays, never a shell command;
- has no network address families and a restricted systemd capability set.

The bridge is not an authorization boundary for Dashboard actions. Host path policy, device scopes, backup include policy, secret/database exclusions, ZIP safety, and restore checks remain independently enforced.

## Installation on PlexonCraft

Prerequisites on Ubuntu 24.04:

```bash
sudo apt-get install --yes acl
```

Install the shipped files from the release examples package:

```bash
sudo install -d -o root -g root -m 0755 /usr/local/lib/plexonpanel
sudo install -o root -g root -m 0755 \
  host-agent/examples/backup-read-bridge.py \
  /usr/local/lib/plexonpanel/backup-read-bridge.py

sudo install -d -o root -g root -m 0750 /etc/plexonpanel-host
sudo install -o root -g root -m 0600 \
  host-agent/examples/read-bridge.json \
  /etc/plexonpanel-host/read-bridge.json

sudo install -o root -g root -m 0644 \
  host-agent/examples/plexonpanel-backup-read-bridge.service \
  /etc/systemd/system/plexonpanel-backup-read-bridge.service

sudo systemctl daemon-reload
sudo systemctl enable --now plexonpanel-backup-read-bridge.service
```

Before enabling, edit `/etc/plexonpanel-host/read-bridge.json` as root so `serverRoot` and `include` match the Host backup configuration and the actual world topology. A configured include that does not exist is allowed to remain absent; Backup Diagnostics will report it as missing. Do not add arbitrary host paths.

The example service hardens and permits writes only under `/opt/plexoncraft/server`. If the trusted server root differs, edit the root-owned `ReadWritePaths=` line to the same absolute root as `serverRoot`. Do not use a broad parent such as `/opt`, `/`, or `/home`.

Only one ACL guardian should authoritatively manage Host backup-read ACLs for the same server tree. Once this bridge passes its acceptance checks, disable/remove older ad-hoc ACL guardian services rather than running them in parallel. The `plugins/PlexonPanel/access` directory remains managed by its dedicated `plexonpanel-access` shared-group contract, not by the backup-read bridge.

## Acceptance checks

After installation, run the following locally during the 3.4.1 runtime gate:

```bash
sudo systemctl status plexonpanel-backup-read-bridge.service --no-pager
sudo -u plexonpanel-host test -r /opt/plexoncraft/server/Survival_World/level.dat
echo "HOST_READ=$?"
sudo -u plexonpanel-host test -w /opt/plexoncraft/server/Survival_World/level.dat
echo "HOST_WRITE=$?"
```

Expected: `HOST_READ=0` and `HOST_WRITE=1`.

Also verify the shared device registry still permits Host writes:

```bash
sudo -u plexonpanel-host test -w /opt/plexoncraft/server/plugins/PlexonPanel/access/devices.json
echo "REGISTRY_WRITE=$?"
sudo -u plexonpanel-host test -w /opt/plexoncraft/server/plugins/PlexonPanel/access/devices.json.lock
echo "LOCK_WRITE=$?"
```

Expected: both values are `0`.

Then execute `save-all flush` from Paper repeatedly and repeat the checks. Also exercise representative plugin file churn. Finally run **Backup Diagnostics** from the Dashboard; `unreadableDurableCount` must remain zero for required durable data.

Do not remove an existing working ACL guardian until these checks pass with this service. After the new bridge is accepted, do not leave the old guardian running in parallel.

## Upgrade

Replace the Python file and service unit from the new release, review any changes to the fixed root/include configuration, then run:

```bash
sudo systemctl daemon-reload
sudo systemctl restart plexonpanel-backup-read-bridge.service
```

The service performs an initial reconciliation at startup before entering its recursive inotify loop.

## Uninstall / rollback

Stop and disable the service first:

```bash
sudo systemctl disable --now plexonpanel-backup-read-bridge.service
sudo rm -f /etc/systemd/system/plexonpanel-backup-read-bridge.service
sudo rm -f /usr/local/lib/plexonpanel/backup-read-bridge.py
sudo rm -f /etc/plexonpanel-host/read-bridge.json
sudo systemctl daemon-reload
```

Stopping the bridge does not remove existing ACL entries. Remove those only after confirming another supported read contract is active. Do not recursively `chmod`, `chown` the server to the Host, or grant Host write access as a rollback shortcut.

## Failure behavior

The bridge logs only local operational errors through systemd. It does not copy file contents, secrets, OAuth material, or rclone configuration into logs. PlexonPanel's `backup.preflight` remains the operator-facing source for missing/unreadable durable data. A live snapshot fails closed with a typed backup-source error if required data remains unreadable after Paper's save preparation.
