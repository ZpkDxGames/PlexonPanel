# Validation and release acceptance

Status: PlexonPanel **3.1.0 release candidate**. Stable publication is blocked on fresh live evidence. The implementation starts from reviewed 3.0.2 main `8847f52dac68aa855595f4d8c5fa695645d22872` and preserves wire protocol 3.

For `v3.1.0-rc.1`, the Dashboard/Relay runtime remains the reviewed **3.0.2 / protocol 3** stack. The required mixed-version acceptance target is therefore Dashboard/Relay 3.0.2 + Paper/Host 3.1.0.

## Automated evidence

The 3.1.0 branch must pass clean tests, Javadoc, Paper/Host JAR builds and release packaging on GitHub-hosted Ubuntu 24.04 x64 and ARM64. CI provisions the official PlexonCore 1.0.0 artifact with its pinned SHA-256, verifies Panel remains protocol 3, verifies the public local API and Core bridge are present, and rejects a Paper JAR that shades PlexonCore runtime classes.

Core-specific automated coverage verifies standalone fallback, the explicit read-only API contract, Core bridge separation from `GatewayClient`/`AgentRuntime`/`syncAccess`/device authorization, and the upgrade safeguard that recreating `protocol-version.txt` cannot clear pairing state.

All existing protocol/security/reliability tests remain release requirements, including immutable grants, Owner/local-policy intersection, replay/clock handling, file confinement, stale-socket behavior, presence privacy, Host access semantics, backup safety and protocol version 3.

## Live gates still pending

| Gate | Required evidence |
| --- | --- |
| Disposable Paper 26.2 / Java 25 | Startup, unchanged identity, protocol 3, telemetry/streams/actions and diagnostics. |
| Full capability Access matrix | Paper/Host authority cells remain truthful; existing immutable grants do not gain scopes. |
| High-risk confirmation/audit | Representative destructive actions retain confirmation and sanitized audit behavior. |
| Ubuntu 24.04 ARM64 | Non-root Host/systemd behavior and Paper/Host connectivity. |
| Cloudflare + Vercel | Relay/dashboard compatibility, private routing, reconnect and server isolation. |
| rclone | Offsite backup integrity, failure handling and retention. |
| Restore interruption | Save lease, emergency archive and idempotent recovery. |
| 30-minute representative load | TPS/MSPT, event latency, snapshots, heap/queues/streams/reconnect remain healthy. |
| Player-presence lifecycle | JOINED/LEFT, reconnect, plugin reload, history privacy and UNKNOWN_DISCONNECT semantics. |
| Orphan-session recovery | Forced termination remains honest and duplicate-free. |
| Mixed-version protocol 3 | **Required for RC1:** Dashboard/Relay 3.0.2 + Paper/Host 3.1.0 preserves fingerprint, grants, revocation and lifecycle without a protocol bump. If Dashboard/Relay 3.1.0 is later coordinated, validate the reciprocal/all-3.1.0 combinations too. |
| Realtime reconciliation | Relay/browser outage, stale-session isolation, snapshot replay and no access-sync churn. |
| Privacy/scope validation | Observer/current-only, stale grant unchanged, Owner still intersected with local policy. |
| Core module registration | `/plexon modules` shows `PlexonPanel — READY | Core API | 3.1.0`; diagnostics show Core/API/protocol versions. |
| Core reload no transport restart | `/plexon reload` produces no Paper/Host/browser reconnect, no new Panel wire session and no `access.sync`. |
| Standalone without Core | Remove Core, restart Paper, full Panel behavior remains available in STANDALONE; restore Core with same identity/grants. |
| Twenty-cycle Panel lifecycle | 20 Dashboard Stop → Start cycles: Host stays authenticated while Paper stops, Host-only start works, Paper re-authenticates, telemetry resumes, no persistent BACKOFF/4008, no re-pair, Core returns READY. |

Record exact accepted commits, final JAR hashes, OS/Java/Paper/dashboard/relay versions, configuration differences, timestamps and sanitized outcomes in `docs/release-gates.json`. Never commit secrets, real player records, raw private logs, tokens or production configuration.

## RC-first release gate

`v3.1.0-rc.1` is the acceptance artifact. Green CI permits publishing the prerelease for live validation; it does **not** authorize stable `v3.1.0`.

All JSON gates intentionally remain false until live evidence is reviewed. Stable publication requires every gate in `docs/release-gates.json` to be `passed: true` with non-empty evidence. The gated final workflow refuses to create the final draft otherwise.
