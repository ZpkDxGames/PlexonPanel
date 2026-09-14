# Host-local RCON maintenance command channel

PlexonPanel maintenance uses a narrow Host-local RCON client for player warnings, the final `save-all flush`, and post-start readiness probing. The dashboard cannot submit console commands through this channel. The supported command set is compiled into the Host companion.

## Security model

- The Host configuration accepts only loopback command-channel targets (`127.0.0.1`, `localhost`, or loopback IPv6), and the resolved socket target is checked again before connecting.
- The RCON password is not a Host JSON field. The Host reads it from `commandChannel.secretFile` when a maintenance command is issued.
- The secret file must be a regular, non-symlink file readable only by its owner; group/other access and owner-execute permissions are rejected.
- RCON responses are reduced to bounded success/error classifications. Passwords and raw command responses are not sent to the relay, dashboard, audit records, diagnostics, or normal logs.
- There is no generic dashboard-to-RCON action. Maintenance code can only broadcast the fixed countdown message, execute `save-all flush`, and run the fixed `list` readiness probe.
- Keep the RCON listener unreachable from untrusted networks even though PlexonPanel itself connects only to loopback.

## PlexonCraft migration

1. Generate a strong random RCON password locally on the server. Do not paste it into the dashboard, a GitHub issue, logs, or the Host JSON file.
2. Configure Paper/Minecraft `server.properties` with RCON enabled and the matching password/port, for example:

   ```properties
   enable-rcon=true
   rcon.port=25575
   rcon.password=<host-local-secret>
   ```

3. Store the same password in a dedicated Host-local file. A typical Linux setup is:

   ```bash
   sudo install -o plexonpanel-host -g plexonpanel-host -m 600 /dev/null /etc/plexonpanel-host/rcon.secret
   sudoedit /etc/plexonpanel-host/rcon.secret
   ```

   Enter only the password (an optional final newline is accepted). Do not make the file executable, group-readable, or world-readable.

4. Add the command-channel block to the Host configuration:

   ```json
   "commandChannel": {
     "enabled": true,
     "host": "127.0.0.1",
     "port": 25575,
     "secretFile": "/etc/plexonpanel-host/rcon.secret",
     "commandTimeoutMillis": 5000,
     "readinessTimeoutSeconds": 180
   }
   ```

5. Restart Paper once so the `server.properties` RCON change takes effect, then restart `plexonpanel-host.service` so the Host loads the new JSON configuration.
6. Verify the RCON port is not reachable from outside the host. The exact firewall policy depends on the PlexonCraft network topology; permit only the local Host path and reject external access to the RCON port. Do not blindly bind the entire Minecraft player listener to loopback if players connect directly to that listener.
7. Exercise a controlled maintenance run before production use. Confirm the warning is visible and a deliberately invalid RCON secret causes the job to fail before systemd stop.

## Maintenance behavior

A full backup countdown is Host-owned and uses exactly these boundaries:

- 30 minutes
- 15 minutes
- 1 minute
- 30 seconds
- 15 seconds
- 5 seconds

The 30-minute warning is due immediately when the countdown is created. The countdown deadline and consumed warning boundaries are persisted under the Host data directory. If the Host restarts during the countdown, it resumes from that durable deadline, does not replay consumed warnings, records boundaries missed while offline, and continues with the next valid boundary.

Immediately before shutdown, the Host executes `save-all flush`. A timeout, authentication failure, malformed RCON response, inaccessible secret, blank response, explicit command rejection, or other negative command result fails the maintenance job before the stop continuation can execute. A protocol-valid RCON packet alone is not sufficient to affirm the final save. The server is left running in that case.

After a maintenance-controlled start, readiness requires both an active systemd service and a successful fixed RCON readiness probe. Paper/PlexonPanel plugin reconnection is no longer required for this maintenance command path.

## Error classifications

Expected safe error codes include `COMMAND_CHANNEL_DISABLED`, `RCON_SECRET_INVALID`, `RCON_SECRET_PERMISSIONS`, `RCON_AUTH_FAILED`, `RCON_TIMEOUT`, `RCON_UNAVAILABLE`, `RCON_TARGET_NOT_LOOPBACK`, `RCON_PROTOCOL_ERROR`, `RCON_COMMAND_REJECTED`, `RCON_IO_FAILED`, and `SERVER_READINESS_TIMEOUT`. These codes intentionally do not contain the password or raw RCON response text.
