# Manual full-backup migration — Step 2

This migration moves maintenance warnings and the final save flush from the Paper plugin to the Linux PlexonPanel Host Companion.

## Preconditions

- Step 1 durable maintenance-job state is already deployed.
- `plexonpanel-host` continues to run as its dedicated non-root account.
- The Minecraft systemd service remains the configured `serviceName`.
- Do not expose the RCON port to the public network.

## 1. Choose one RCON password

Use a strong unique value that is not reused for the relay, SSH, Google Drive/rclone, Minecraft accounts, or dashboard credentials.

Create the Host-local secret without placing the password on a command line:

```bash
sudo install -d -o plexonpanel-host -g plexonpanel-host -m 0750 /etc/plexonpanel-host
sudo install -o plexonpanel-host -g plexonpanel-host -m 0600 /dev/null /etc/plexonpanel-host/rcon.password
read -rsp 'RCON password: ' RCON_PASS; echo
printf '%s\n' "$RCON_PASS" | sudo tee /etc/plexonpanel-host/rcon.password >/dev/null
sudo chown plexonpanel-host:plexonpanel-host /etc/plexonpanel-host/rcon.password
sudo chmod 0600 /etc/plexonpanel-host/rcon.password
```

Use the same value for `rcon.password` in the Minecraft server configuration. Do not commit either value.

## 2. Enable Minecraft RCON

In the deployed server's `server.properties`, configure:

```properties
enable-rcon=true
rcon.port=25575
rcon.password=<same secret stored in /etc/plexonpanel-host/rcon.password>
```

Restart Minecraft only after the firewall rule in the next section has been prepared.

## 3. Restrict the RCON port to the local host

The required security property is that TCP port `25575` is unreachable from non-loopback interfaces. Use the machine's existing firewall tooling rather than adding a second unmanaged firewall stack.

For a UFW-managed host, an example policy is:

```bash
sudo ufw allow in on lo to any port 25575 proto tcp
sudo ufw deny in to any port 25575 proto tcp
sudo ufw status verbose
```

If the server uses nftables, cloud-provider security groups, or another firewall manager, implement the equivalent local-only rule there instead.

After Minecraft starts, inspect the effective socket and firewall state. If RCON is listening on a wildcard address, the deny rule is mandatory. Do not proceed until the port is blocked externally.

## 4. Add Host configuration

Add this sibling object to `/etc/plexonpanel-host/host-config.json`:

```json
"maintenanceCommand": {
  "enabled": true,
  "host": "127.0.0.1",
  "port": 25575,
  "secretFile": "/etc/plexonpanel-host/rcon.password",
  "commandTimeoutSeconds": 5,
  "readinessTimeoutSeconds": 180
}
```

The `host` field is intentionally restricted to loopback. The secret path must be absolute. The password must not be copied into this JSON file.

## 5. Restart the Host Companion

After validating the JSON and file ownership, restart only the Host Companion so it reloads the new local command-channel settings:

```bash
sudo systemctl restart plexonpanel-host.service
sudo systemctl status plexonpanel-host.service --no-pager
```

Do not use broad ownership changes, `chmod 777`, or root execution for the Host process.

## 6. Runtime acceptance

Before treating Step 2 as production-certified, verify on PlexonCraft that:

1. the Paper PlexonPanel plugin can be disabled while the Host remains running;
2. a Host maintenance warning reaches connected Minecraft players through RCON;
3. `save-all flush` succeeds through the Host command channel;
4. an intentionally wrong RCON secret causes a safe authentication/readiness failure and does not stop Minecraft;
5. an unreachable RCON port times out safely and does not stop Minecraft;
6. no password appears in Host logs, relay traffic, dashboard responses, audit metadata, or diagnostics;
7. the RCON port is not reachable from another machine;
8. after restoring the correct configuration, a maintenance restart reaches RCON readiness without relying on a Paper-plugin reconnect event.

## Rollback

If the command channel must be disabled before the next roadmap step, set:

```json
"maintenanceCommand": {
  "enabled": false,
  "host": "127.0.0.1",
  "port": 25575,
  "secretFile": "/etc/plexonpanel-host/rcon.password",
  "commandTimeoutSeconds": 5,
  "readinessTimeoutSeconds": 180
}
```

Then restart `plexonpanel-host.service`. A disabled channel fails maintenance before shutdown; it does not silently fall back to Paper maintenance commands.
