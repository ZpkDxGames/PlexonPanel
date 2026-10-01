# PlexonPanel 4.0.0

PlexonPanel 4.0.0 is a coordinated Paper, Linux Host, Dashboard and relay release on signed
Protocol 3. It incorporates live cold-backup progress and verified remote cleanup, Paper-owned
player history, Host-owned cursor-paged journald history, explicit schema-4 migration, and
traceable build identities across the control plane.

## Compatibility

- Java 25; Paper API `26.2.build.121-stable`.
- Wire Protocol 3 remains unchanged. The 4.0 additions are additive.
- Paper/Host identity, pairing state, immutable device grants, access revision/generation,
  maintenance state, backup metadata and history journals are preserved.
- The Cloudflare relay retains the existing Durable Object class so rollout does not discard room
  state. Worker and standalone relays remain supported and parity-tested.
- Network-reachable restore and Host mutation of the live server tree remain disabled. Restore is
  a planned local operator procedure.

## Configuration migration

Paper `config.yml` gains `schema-version: 4`; Host `host-config.json` gains `schemaVersion: 4`.
Paper migrates at startup. The root-owned Host policy is migrated while its service is stopped by
running the local `--migrate-config` operation as root. Originals are retained as
`config.yml.pre-v4-backup` and `host-config.json.pre-v4-backup`; schema/default changes are atomic,
and the Host owner/group/mode are restored. Future schemas fail closed. This migration does not
rotate or rewrite identity, pairing or device grants.

## Upgrade

1. Record the current Paper JAR, Host JAR, Dashboard/relay commits and service units. Back up the
   two configs plus Paper identity/access state and Host persistent state privately.
2. Keep the working 3.5.0 binaries/deployment available. Do not delete Durable Objects or re-pair
   devices for the version bump.
3. Deploy the exact Dashboard/relay commit named in `release-manifest.json`; verify `/api/build`
   and relay `/healthz` report 4.0.0, Protocol 3 and that commit.
4. Stop Host, copy `plexonpanel-host-4.0.0.jar`, update the inspected unit to that exact path, run
   `sudo /usr/bin/java -jar /opt/plexonpanel-host/plexonpanel-host-4.0.0.jar /etc/plexonpanel-host/host-config.json --migrate-config`, then restart Host and review its journal before changing Paper.
5. Stop Paper, remove/archive duplicate PlexonPanel JARs, install `PlexonPanel-4.0.0.jar`, then
   start Paper normally. Do not use Bukkit/Paper hot reload for a JAR replacement.
6. Verify unchanged server fingerprint, device grants, Host pin, protocol, capabilities, telemetry,
   presence, console authority, history states and backup/provider preflight.
7. Run the release certification matrix in `docs/release-gates-4.0.0.json` against the exact
   checksummed artifacts. Publication is blocked until mandatory gates record evidence and PASS.

## Rollback

1. Stop Paper and Host cleanly; preserve 4.0 logs and migration backups.
2. Restore the recorded 3.5.0 Paper and Host JARs and their prior unit paths.
3. Redeploy Dashboard/relay commit `5d25671bc73569e18599be1e9034291d1f87d171` when a control-plane
   rollback is required.
4. Restore config files from the pre-v4 backups only if the older component cannot tolerate the
   additive schema key. Never delete identity, access, Host authorization mirror or relay storage.
5. Start Host/Paper, verify the same fingerprint and grants, then repeat connection and backup
   metadata checks.

## Certification truth

The release manifest is generated from `docs/release-gates-4.0.0.json`. Source review, CI,
security, runtime, backup, console, history and restart/recovery remain distinct states. A source
build or preview deployment is not runtime certification, and no state is PASS without recorded
evidence from the exact candidate.
