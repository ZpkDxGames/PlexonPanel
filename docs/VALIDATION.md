# Validation and release acceptance

Status: review candidate; production release is blocked on live gates. Source baselines: Paper `2e3bbf34c486cbc595b1d6b503281f4fc0c72f19`, dashboard `d1583af58017ee2aad2538a0ae5cec74f97e71b3`.

## Local evidence

The completed local checks passed **53 Java tests** (44 shared protocol, 3 Paper, 6 host), **27 relay tests** and **22 dashboard/client/tool tests**. Lint and TypeScript passed. Next 16.3.1 built only `/` and the standard not-found route. Java/Javadoc and both JARs built; Javadoc reports missing-comment warnings. Actual local workerd passed v3 authentication, one-use pairing, browser session, bounded telemetry, scoped denial and live revocation. Environment: Linux x64, Temurin 25.0.4.1, Gradle 9.7.0 and Node 24.19.0. Final branch CI rechecks recovered source.

Visual QA used synthetic server-rendered instances of actual components/styles at desktop, 768-pixel tablet and 390-pixel phone widths. Cards/navigation/tables were reviewed. This was fixture review, not authenticated live-server E2E. Temporary QA routes/datasets were removed before production build.

## Live gates still pending

| Gate                            | Required evidence                                                                                                                                              | Blocker                                           |
| ------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------- |
| Disposable Paper 26.2 / Java 25 | Startup/reload/disable, identity, pairing/expiry/revocation, GUI permissions/clicks, real telemetry, chat/console, all typed operations and plugin integration | Disposable server and operator setup/EULA         |
| Ubuntu 24.04 ARM64              | Actual non-root OS permissions, systemd/polkit allow/deny, host independence and ARM Java                                                                      | Target host access                                |
| Cloudflare + Vercel             | Deployed identity migration, origins/CSP, browser pairing/denial, private results, reconnect/hibernation and server isolation                                  | Deployment accounts and approved test environment |
| rclone                          | Fixed remote/config, offsite integrity/retrieval, failures and retention                                                                                       | Disposable configured remote                      |
| Restore interruption            | Save lease watchdog, emergency archive, interrupted rename boundaries, repeated recovery and gameplay after deliberate startup                                 | Disposable Paper/systemd environment              |
| 30-minute stability             | TPS/MSPT baseline comparison, bounded memory/queues, high-volume streams and outages/reconnect                                                                 | Representative loaded server                      |

Record exact commits/JAR hashes, hardware/OS/Java/Paper versions, configurations and sanitized outcomes. Never commit secrets, player data, raw logs or backup bodies as evidence.

## Release gate

Finish paired CI and all live checks. Update the agent repository's `docs/release-gates.json` with true results and concrete evidence, then review/merge the paired PRs and deliberately create aligned `v2.0.0`. The manually dispatched workflow checks all evidence and creates only a draft release from an existing tag. It does not auto-publish or overwrite releases. Review artifacts/checksums, publish deliberately and deploy during coordinated maintenance. No production merge/tag/deployment/release is authorized by passing unit tests alone.
