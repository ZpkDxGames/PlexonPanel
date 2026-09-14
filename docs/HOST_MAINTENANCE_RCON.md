# Host-local maintenance command channel

PlexonPanel manual full backups use a Host-owned RCON channel for the maintenance warning countdown, the final `save-all flush`, and post-start readiness checks. The dashboard never receives the RCON password and cannot submit arbitrary RCON commands.

## Security model

The Host implementation exposes only three fixed operations internally:

- one of the six full-backup warning broadcasts;
- `save-all flush` immediately before shutdown;
- `list` as a post-start readiness probe.

There is no browser action that accepts a console command string. `maintenance.status` exposes only safe command-channel metadata (`enabled`, `transport`, loopback target, and port). The password file path and password are not returned.

`commandChannel.host` is validated to loopback only (`127.0.0.1`, `localhost`, or `::1`). This prevents PlexonPanel itself from using RCON over the network. Minecraft/Paper may still listen on a wider interface depending on its RCON implementation, so the host firewall and cloud firewall must not expose the RCON port externally.

## PlexonCraft migration

1. Generate a strong RCON password locally on the Linux host. Do not paste it into the dashboard, GitHub, logs, Discord, or support messages.
2. Store only the password in `/etc/plexonpanel-host/rcon.password`.
3. Make the secret root-owned and readable by the Host service group, but never world-readable or writable. One suitable layout is:

   ```sh
   sudo chown root:plexonpanel-host /etc/plexonpanel-host/rcon.password
   sudo chmod 0640 /etc/plexonpanel-host/rcon.password
   ```

4. In the Minecraft `server.properties`, enable RCON and configure the same password and the selected port, for example:

   ```properties
   enable-rcon=true
   rcon.port=25575
   rcon.password=REPLACE_WITH_THE_HOST_LOCAL_SECRET
   broadcast-rcon-to-ops=false
   ```

5. Do **not** expose TCP/25575 through the cloud security group, router/NAT, public firewall, or any reverse proxy. If the Minecraft RCON listener binds beyond loopback, explicitly firewall that port from non-loopback traffic.
6. Add the Host configuration block:

   ```json
   "commandChannel": {
     "enabled": true,
     "host": "127.0.0.1",
     "port": 25575,
     "secretFile": "/etc/plexonpanel-host/rcon.password",
     "commandTimeoutMillis": 5000,
     "readinessTimeoutMillis": 1500
   }
   ```

7. Restart Minecraft/Paper so the RCON settings take effect, then restart `plexonpanel-host` so the new Host configuration is loaded.
8. Confirm the Host and Minecraft are healthy before starting the first manual full backup. Do not test by opening the RCON port publicly.

## Manual full-backup countdown

The Host owns a fixed 30-minute countdown and emits warnings at exactly:

- 30 minutes;
- 15 minutes;
- 1 minute;
- 30 seconds;
- 15 seconds;
- 5 seconds.

The first warning is sent immediately when the countdown starts. The Host persists handled warning boundaries in `/var/lib/plexonpanel-host/maintenance/countdown.json` (or the configured Host data directory equivalent).

If the Host process restarts during `COUNTDOWN`, the job remains the same durable maintenance job. Already handled boundaries are not replayed. Boundaries that elapsed while the Host was unavailable are recorded as missed, and the next future boundary continues on the original 30-minute timeline. The browser does not own or recreate the timer.

## Final save behavior

Immediately after the countdown and before `systemctl stop`, the Host issues exactly:

```text
save-all flush
```

The Host requires a valid RCON response. Authentication failure, timeout, connection failure, protocol failure, empty response, or a command rejection fails the maintenance job before the destructive stop phase. In that case PlexonPanel does not intentionally stop Minecraft or begin the cold archive.

## Secret-file requirements

When enabled, the command channel requires an absolute secret-file path. The Host rejects:

- symlinked/non-regular secret files;
- empty or oversized secrets;
- newline/NUL content inside the password;
- world-readable/writable/executable secret files;
- group-writable or group-executable secret files.

The secret is read only by the Host process, kept out of browser-safe failures/status, and its temporary byte buffer is cleared after each command attempt.

## Transitional note

This step removes Paper from the critical command path of **manual full backups**. The legacy restart workflow may still use the existing Paper maintenance bridge until its later architecture cleanup. Cold-archive stop authority and local archive verification are hardened separately in Step 3.
