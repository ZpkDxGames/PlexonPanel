# PlexonPanel 3.4.1 — Step 8 runtime remediation record (2026-09-15)

This record documents defects discovered during real PlexonCraft VPS certification. It does **not** mark runtime certification PASS. `v3.4.1` stable promotion remains blocked until the remediated matched source is deployed and the required gates are re-executed.

## Runtime environment

- Production host architecture: Linux `aarch64` / release platform `linux-arm64`.
- Java: 25.
- systemd units: `plexonpanel-host.service` and `plexoncraft.service`.
- Protocol: 3.
- Remediated Dashboard/relay accepted source: `5c087fc78b65a4e106a703f73cc7c14aa4731c87`.
- Remediated Dashboard final-main CI: `34918283724`.
- Remediated production relay deployment run: `34918283855`.

## Positive evidence retained

Host independence was exercised before the defects below interrupted the remaining gates:

1. Minecraft was stopped while Host remained active and authenticated.
2. Dashboard continued to report Host connected while Paper was offline.
3. Host-owned journald console history remained available.
4. Dashboard Host-authoritative Start returned Minecraft online and Paper reconnected.

This evidence may be referenced during the final acceptance review, but authorization and backup gates must be rerun against the remediated matched build.

## Defect 1 — stale Host authorization mirror after device mutation

Observed state after replacing the browser Owner grant:

- Paper authority: generation `1788655164418`, revision `380`, device `5fafb43b...` (`WindowsPC`), Owner, 54 scopes.
- Host mirror: generation `1788655164418`, revision `377`, old device `32c22989...` (`TonimPC`), Owner, 54 scopes.
- Restarting Host authenticated successfully but did not advance the mirror.
- Host-authoritative actions returned `DEVICE_REVOKED` for the new current Owner.

The Relay contract previously handled authenticated Host `access.authority.request` by asking Paper to republish `access.sync` only. The production-discovered remediation makes both Worker/Durable Object and standalone relays immediately replay their current validated Paper-authoritative room metadata to Host as `access.authority.sync`, then still request a fresh Paper publication. Host generation/revision/conflict protection remains authoritative against stale rollback.

Dashboard/relay remediation merged through PR #40 and is accepted at `5c087fc78b65a4e106a703f73cc7c14aa4731c87`.

### Required retest

After deploying the remediated Relay and remediated Host candidate:

1. Keep the current `WindowsPC` pairing; do not pair another device.
2. Restart Host while Paper remains online.
3. Confirm Host mirror generation/revision/device exactly match Paper authority.
4. Confirm `maintenance.status`, `provider.status`, `backup.full.list`, and `backup.preflight` no longer return `DEVICE_REVOKED` or `SCOPE_DENIED` for the current Owner.
5. Stop Paper and confirm Host remains authorized from the synchronized mirror.

## Defect 2 — passive RCON readiness polling polluted console history

The Host service status snapshot runs every five seconds. The previous implementation called `commands.readinessProbe()` from that passive snapshot. RCON readiness executes `list`, so Minecraft logged a local RCON client connection and disconnection every five seconds even while no operator action was occurring.

The remediation keeps RCON enabled for the Step 8 critical path while removing passive connection churn:

- passive service telemetry caches readiness;
- one real readiness probe is performed when systemd transitions to active;
- explicit backup preflight refreshes readiness;
- backup/lifecycle operations may demand a fresh probe;
- verified lifecycle start/stop updates the cached state;
- inactive systemd state fails closed without opening RCON.

### Required retest

After deploying the remediated Host JAR:

1. Leave Minecraft and Host idle for at least 30 seconds.
2. Confirm the console no longer contains a new `Thread RCON Client /127.0.0.1 started` / `shutting down` pair every five seconds.
3. Run backup preflight and confirm a bounded RCON readiness check still succeeds.
4. During the real backup, confirm warning broadcasts and final `save-all flush` still use Host-local RCON.
5. Confirm automatic restart readiness remains positively verified.

## Release boundary

The old Build #204 JAR hashes belong to the superseded candidate and must not be used as final release evidence after this remediation. A fresh exact-main backend build is required after the Host fix merges, and its Paper/Host SHA-256 values must be recorded before production retest.

Runtime certification remains `NOT_EXECUTED`/incomplete until the remaining live gates pass. No stable tag or GitHub release may be created from this record alone.
