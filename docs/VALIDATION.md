# Validation and release acceptance

Status: PlexonPanel 3.0.0 review candidate; stable publication is blocked on live gates. Source baselines were plugin `6317bfbba1990cd8a96852b03f8097a524f745db` and dashboard `98166f7d9b3851a1c96d160c2b5eefc3b231c58f`.

## Automated evidence

An intermediate branch build at plugin commit `5b537bdc8f25d2cd10bf66a174b5e8e1d46e60a2` passed the repository's clean Gradle test, Javadoc, Paper JAR, Host JAR, and packaging gate on both GitHub-hosted Ubuntu 24.04 x64 and ARM64 ([workflow run 34058130416](https://github.com/ZpkDxGames/PlexonPanel/actions/runs/34058130416)). Both architecture artifacts were byte-identical:

| Asset | SHA-256 |
| --- | --- |
| `PlexonPanel-3.0.0.jar` | `d6149334101c21dbf9f71bc6b012d41efae05217ecf84ac52a7e2454e65fb2a8` |
| `plexonpanel-host-3.0.0.jar` | `e605c21a6c120258ea86f21d72dd1f52c7c77b7b689b1a8f0e86abf351534040` |

The JAR manifests reported implementation version 3.0.0 and the external release manifest reported product 3.0.0, protocol 3, and Java 25. The final branch revision must rerun this same matrix; hashes must be recorded again if Java sources change.

The paired dashboard workspace passed ESLint, application/relay TypeScript, a Next 16.3.1 production build, **29 relay tests**, and **34 dashboard/client/UI tests**. Local workerd smoke is automated simulation only, not deployed or paired-Paper acceptance.

Automated presence coverage includes first join, normal quit/duration, kick/quit de-duplication, rapid reconnect, duplicate event rejection, reload reconciliation, shutdown behavior, orphan unknown-disconnect, name changes, UTC/null semantics, truncated tails, daily/size rotation, retention and index/event bounds, unsafe paths/permissions, atomic summary writes, bounded flush/queue pressure, privacy exclusions, scopes/capabilities, stable pagination, relay filtering/private routing/no-storage, and dashboard snapshot/delta/session/cache behavior.

## Live gates still pending

| Gate | Required evidence | Current blocker |
| --- | --- | --- |
| Disposable Paper 26.2 / Java 25 | Startup, upgrade/reload/disable, same identity, pairing/revocation, real telemetry, chat/console and typed-operation regression. | Disposable server and operator setup/EULA. |
| Player-presence lifecycle/history | Real join, quit, kick, rapid reconnect, plugin reload, closed-dashboard retention, disabled-history no-write, multiple pages and optional name change. | Controlled players/accounts and private test server. |
| Crash/orphan recovery | Forced termination followed by honest `UNKNOWN_DISCONNECT`, preserved last observation, null exact end/duration and no duplicate closure. | Disposable failure environment. |
| Realtime latency/reconciliation | One-tick-plus-network delta target, relay/browser outage, stale-session discard, complete snapshot replay, refresh rate/coalescing and Paper-only operation. | Paired relay/dashboard/Paper environment. |
| Permissions/privacy | Observer current-only, qualifying new grants, unchanged pre-3.0 grant, Owner denied when history is off; inspect journal, relay, browser, logs and audits for prohibited data. | Controlled role devices and storage access. |
| Mixed-version protocol 3 | New dashboard + 2.0 Paper, 3.0 Paper + older UI/relay, all current; preserve fingerprint/grants and revocation. | Retained reviewed artifacts/deployments. |
| Ubuntu 24.04 ARM64 runtime | Non-root permissions, systemd/polkit allow/deny, Host independence and Paper/Host connection. | Target host access. |
| Cloudflare + Vercel | Identity migration, CSP/origins, private results, hibernation/reconnect and server isolation without clearing state. | Approved deployment accounts/environment. |
| rclone | Fixed executable/config/remote, offsite integrity/retrieval, failures and retention. | Disposable configured remote. |
| Restore interruption | Save lease watchdog, emergency archive, interrupted renames, repeat recovery and gameplay validation. | Disposable Paper/systemd environment. |
| 30-minute representative load | Baseline versus 3.0 TPS/MSPT, event latency, snapshot duration, heap/queues/journal/index, stream pressure and reconnect. | Representative loaded server. |

Record exact accepted commits, final JAR hashes, hardware/OS/Java/Paper/dashboard/relay versions, configuration differences, timestamps and sanitized outcomes in `docs/release-gates.json`. Never commit secrets, real player records, raw logs, tokens, or production configuration.

## Release gate

All JSON gates intentionally remain false. After paired final CI and real evidence review, merge normally, create the reviewed plugin tag `v3.0.0`, and manually dispatch the gated workflow. It creates only a draft release from an existing tag and cannot run unless every required gate has non-empty approved evidence. Review assets/checksums and publish deliberately; green CI alone never authorizes a stable release or production deployment.
