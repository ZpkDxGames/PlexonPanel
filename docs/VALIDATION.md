# Validation and release acceptance

Status: PlexonPanel 3.0.1 review candidate; stable publication is blocked on fresh live gates. The 3.0.1 work starts from plugin `dc5c689a8cab85e78a401c8a31fdd5709c6ab45a` and preserves wire protocol 3.

## Automated evidence

The 3.0.1 branch must pass the repository's clean Gradle test, Javadoc, Paper JAR, Host JAR and packaging matrix on both GitHub-hosted Ubuntu 24.04 x64 and ARM64. Because Java/config/release metadata changed, prior 3.0.0 hashes are historical evidence only and must not be reused for 3.0.1.

New automated coverage specifically proves the full-control Paper preset enables every Paper-supported scope while leaving Host-only lifecycle/full-backup scopes false, `plugins.reload` is advertised only when a configured reload command is executable under console policy, the conservative public Paper defaults remain conservative, the full-control Host policy enables every Host-supported scope, Host rejects Paper-only scopes, and backup effective gating remains enforced.

Existing protocol/security tests continue to cover Owner/local-policy intersection, immutable existing grants, stale grants lacking newly introduced scopes, unknown scope rejection, bounded credential lifetime, revocation and protocol version 3.

## Historical 3.0.0 automated evidence

An intermediate 3.0.0 branch build at plugin commit `5b537bdc8f25d2cd10bf66a174b5e8e1d46e60a2` passed the repository's clean Gradle test, Javadoc, Paper JAR, Host JAR, and packaging gate on both GitHub-hosted Ubuntu 24.04 x64 and ARM64 ([workflow run 34058130416](https://github.com/ZpkDxGames/PlexonPanel/actions/runs/34058130416)). Those artifact hashes and acceptance results do not constitute 3.0.1 release evidence.

## Live gates still pending

| Gate | Required evidence | Current blocker |
| --- | --- | --- |
| Disposable Paper 26.2 / Java 25 | Startup, same identity, new Owner pairing, truthful Paper capability matrix, telemetry/history/chat/console and typed-operation regression. | Disposable server and operator setup/EULA. |
| Full capability Access matrix | Paper/Host full-control presets, all applicable cells enabled, non-applicable authority cells remain disabled, stale grant versus newly paired Owner behavior. | Paired disposable Paper/Host/relay/dashboard environment. |
| High-risk confirmation/audit | Ban/kill/op, file delete, backup delete/restore, server stop/restart and device revoke remain confirmed and audited. | Disposable accounts/data/service and maintenance window. |
| Player-presence lifecycle/history | Real join, quit, kick, rapid reconnect, plugin reload, closed-dashboard retention, disabled-history no-write and multiple pages. | Controlled players/accounts and private test server. |
| Crash/orphan recovery | Forced termination followed by honest `UNKNOWN_DISCONNECT`, preserved last observation, null exact end/duration and no duplicate closure. | Disposable failure environment. |
| Realtime latency/reconciliation | Relay/browser outage, stale-session discard, complete snapshot replay, refresh rate/coalescing and Paper-only operation. | Paired relay/dashboard/Paper environment. |
| Permissions/privacy | Observer current-only, qualifying new grants, unchanged older grant, Owner denied when local policy is false; inspect journal/relay/browser/logs/audits. | Controlled role devices and storage access. |
| Mixed-version protocol 3 | Compatible protocol-3 combinations preserve fingerprint/grants and revocation without a protocol bump. | Retained reviewed artifacts/deployments. |
| Ubuntu 24.04 ARM64 runtime | Non-root permissions, systemd/polkit allow/deny, Host independence and Paper/Host connection. | Target host access. |
| Cloudflare + Vercel | Identity migration, CSP/origins, private results, hibernation/reconnect and server isolation without clearing state. | Approved deployment accounts/environment. |
| rclone | Fixed executable/config/remote, offsite integrity/retrieval, failures and retention. | Disposable configured remote. |
| Restore interruption | Save lease watchdog, emergency archive, interrupted renames, repeat recovery and gameplay validation. | Disposable Paper/systemd environment. |
| 30-minute representative load | Baseline versus 3.0.1 TPS/MSPT, event latency, snapshot duration, heap/queues/journal/index, stream pressure and reconnect. | Representative loaded server. |

Record exact accepted commits, final JAR hashes, hardware/OS/Java/Paper/dashboard/relay versions, configuration differences, timestamps and sanitized outcomes in `docs/release-gates.json`. Never commit secrets, real player records, raw logs, tokens, or production configuration.

## Release gate

All JSON gates intentionally remain false. After final CI and real evidence review, merge normally, create the reviewed plugin tag `v3.0.1`, and manually dispatch the gated workflow. It creates only a draft release from an existing tag and cannot run unless every required gate has non-empty approved evidence. Review assets/checksums and publish deliberately; green CI alone never authorizes a stable release or production deployment.
