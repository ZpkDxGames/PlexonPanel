# ADR: PlexonPanel 4.0 ownership and Protocol 3

Status: accepted for the 4.0.0 source candidate.

## Decision

PlexonPanel 4.0 keeps signed wire Protocol 3. The 3.5.1 backup-progress, Paper history, and
Host journald pagination contracts are additive message types/fields. They do not justify breaking
existing device credentials, server identities, relay rooms, or the production Durable Object
class. Unsupported actions continue to fail with explicit compatibility errors.

## Ownership

| Concern | Authoritative component | Persistence |
| --- | --- | --- |
| Server identity, pairing challenges, immutable device grants | Paper | `plugins/PlexonPanel` |
| Player presence and durable player history | Paper | bounded local Paper journal |
| Paper telemetry, player/plugin snapshots, Paper actions | Paper | bounded memory plus local audit |
| systemd lifecycle, machine telemetry, retained console history | Host | system journal plus bounded Host state |
| Live console while Host journal authority is unavailable | Paper fallback | bounded memory only; no retained history |
| Full backup, local/remote verification, retry, cleanup, recovery | Host | Host data directory and provider |
| Operator presentation and confirmation | Dashboard | preferences and signed device credential only |
| Routing, session attachment, replay/rate enforcement | Relay | bounded coordination metadata only |

Relay runtimes do not persist console bodies, player history, backup archives, RCON credentials,
or provider tokens. The Cloudflare Worker and standalone runtime enforce the same generated scope
contract and parity tests.

## Security boundaries

- Paper remains the only grant authority. Host mirrors grants for Paper-offline operations but
  cannot invent or restore a revoked grant.
- Host is the only operating-system authority. Paper does not invoke systemd, journald, rclone, or
  cold-backup filesystem operations.
- Host journal output is preferred whenever its signed source reports healthy. The relay then
  suppresses Paper output. If Host authority is unavailable and local Paper fallback is enabled,
  Paper tails only new `latest.log` lines with bounded reads/buffers, shared redaction and no disk
  history; the Dashboard labels the source transition explicitly.
- The always-on Host remains read-only for the live Minecraft tree. Network-reachable restore and
  file mutation stay disabled in 4.0; planned restores remain a local operator procedure.
- High-risk actions require both an immutable grant and the locally enabled capability, plus the
  existing confirmation boundary.

## Configuration and migration

Paper `config.yml` and Host `host-config.json` use schema version 4. Paper migrates during startup;
the root-owned Host policy uses a stopped-service, local `--migrate-config` operation so the daemon
never gains write access to `/etc`. Older files are backed up and atomically migrated with Host
ownership/mode preserved. Identity, pairing, grants, backup metadata, and journals are not
rewritten. A future schema is rejected without modification.

## Rollout and rollback

Protocol 3 permits a rolling sequence: Dashboard/relay, Host, then Paper. The Dashboard retains
fallbacks for a Host without cursor pagination and exposes unsupported/offline/disabled states
instead of fabricating data. Rollback restores the pinned 3.5.0 binaries/deployment and their
saved configuration backups; schema 4 only adds recognized markers/defaults and does not rotate
identity or pairing material.
