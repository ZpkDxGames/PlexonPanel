# Host authorization mirror

PlexonPanel keeps pairing and access mutation authoritative in Paper while allowing the Linux Host Companion to remain usable when Minecraft is stopped.

- Paper publishes Protocol 3 `access.sync` after authentication and every pairing/revocation mutation.
- The relay validates that state using the existing generation/revision/device contract, persists its coordination copy, and forwards a relay-signed `access.authority.sync` snapshot to Host when Host is online.
- Host may send `access.authority.request`; the relay accepts it only from authenticated Host and asks authenticated Paper to republish `access.sync`.
- Host stores the accepted state at `<dataDirectory>/access/devices.json` with private POSIX permissions. Host authorization does not update `lastSeen`, so the mirror revision remains exactly the Paper-authoritative revision and stale rollback comparisons remain meaningful.
- Generation rollback, revision rollback, equal-revision content conflicts, malformed snapshots, wrong server IDs and invalid grants fail closed. Diagnostics expose only state/generation/revision/device count/source/rejection code, never keys or bearer credentials.
- `accessRegistry` is retained as a migration-only bootstrap path. It is read only when the Host mirror is still the deterministic empty bootstrap state. Once a valid mirror exists, Paper may be stopped and the plugin path may be unavailable without preventing Host startup.
- Access-management actions continue to route to Paper. Host forces effective `devices.*` capabilities off to prevent its mirror from becoming a second grant authority.

## Migration check

1. Start the matched Paper and Host versions once so the Host mirror becomes `READY`.
2. Confirm `/var/lib/plexonpanel-host/access` is host-owned mode 0700 and its registry/lock are 0600.
3. Stop Minecraft/Paper, restart `plexonpanel-host.service`, and reconnect an already paired browser. Host-backed backup, provider, console and systemd actions must remain authorized.
4. Start Paper, revoke a device, wait for the access snapshot, stop Paper again, and verify the revoked credential cannot use Host actions.
5. Do not delete or replace the Host mirror to work around a rejection. Investigate the exposed mirror diagnostic and restore a newer valid Paper-authoritative state.
