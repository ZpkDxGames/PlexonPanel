# Host-owned maintenance command channel

This document describes the Step 2 maintenance command transport used by the PlexonPanel Host Companion.

## Architecture

The Host Companion owns the Minecraft commands required by destructive maintenance. The dashboard does not receive RCON credentials and cannot submit arbitrary RCON commands.

The command channel exposes only three semantic operations inside the Host process:

- broadcast a Host-generated maintenance warning;
- execute the fixed `save-all flush` command;
- probe post-start readiness with the fixed `list` command.

There is no protocol action, dashboard route, or browser parameter that accepts a raw Minecraft command for this channel. `console.execute.allowed` remains disabled on the Host.

## RCON network boundary

`maintenanceCommand.host` accepts loopback values only (`127.0.0.1`, `localhost`, `::1`). The Host refuses non-loopback configuration.

Minecraft/Paper RCON must not be reachable from an external interface. If the deployed server cannot bind RCON independently to loopback, enforce the boundary with the host firewall. The intended policy is:

- loopback traffic to the configured RCON TCP port is allowed;
- inbound non-loopback traffic to that port is denied;
- the game port remains governed by the normal Minecraft firewall policy.

Verify the effective listening/firewall state on the production machine before enabling `maintenanceCommand.enabled`.

## Secret file

The RCON password is referenced by path only:

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

The password itself must never be placed in `host-config.json`, dashboard state, relay payloads, audit metadata, diagnostics, logs, or repository files.

The Host validates that the secret is a regular non-symlink file with a bounded size. On Linux it rejects world-readable/world-writable permissions and group write/execute permissions. Production deployment should normally use mode `0600` owned by the dedicated Host service account.

## Maintenance behavior

Manual full backups use the fixed Host-owned countdown:

1. 30 minutes — sent immediately when the countdown starts;
2. 15 minutes;
3. 1 minute;
4. 30 seconds;
5. 15 seconds;
6. 5 seconds.

The absolute deadline, emitted boundaries, skipped boundaries, and recovery marker are persisted in the Host maintenance journal. Browser disconnects therefore have no effect on the countdown.

If the Host process restarts during countdown, the job resumes from the persisted deadline. Already-emitted boundaries are not replayed. Boundaries that elapsed while the Host was unavailable are recorded as skipped and the next valid boundary is used.

Immediately before `systemctl stop`, the Host executes `save-all flush` through RCON. Failure to authenticate, connect, complete the command, or receive a valid RCON response fails the maintenance job before the destructive stop boundary. Minecraft is left running.

After a Host-owned restart, readiness is determined from the configured systemd unit plus the Host-local RCON readiness probe rather than a Paper-plugin reconnection event.

## Error classification

The command channel reports bounded codes rather than raw socket responses or credential material. Current classifications include:

- `COMMAND_CHANNEL_DISABLED`
- `RCON_AUTH_FAILED`
- `RCON_PROTOCOL_FAILED`
- `RCON_TIMEOUT`
- `RCON_CONNECT_FAILED`
- `RCON_IO_FAILED`
- `RCON_SECRET_INVALID`
- `RCON_SECRET_PERMISSIONS`
- `RCON_SAVE_FLUSH_FAILED`
- `RCON_READINESS_TIMEOUT`

Raw RCON replies are not copied into dashboard errors or audit records.
