# Bedrock verification and release status

## Verified locally

| Check | Result |
| --- | --- |
| Java 25 and Gradle 9.7.0 `clean test check javadoc :agent:jar :host-agent:jar :bedrock-host:jar` | 376 passed with zero failures or skips. Paper agent 49, Linux Host 196, Protocol 91 and Bedrock Host 40. |
| Host systemd and local helpers | Both new templates parsed with `systemd-analyze --user verify`. All 45 existing polkit assertions and the backup bridge fixture passed. The legacy multi-UID ACL fixture requires a privileged runner and hit `fchown` permission denial in the unprivileged workspace. |
| Signed cross-repository transport | The packaged Java Host, real Unix broker and standalone relay passed with a simulated Docker daemon and three simulated Paper/Host rooms. This exercised enrollment, source and session binding, CPU/RAM, version from startup evidence, redacted log output, Docker outage and action denial. |
| Disposable Docker Engine 29.9.2 on x86-64 | Local Alpine containers exercised the Docker reads and both log formats, bounded stdin attach, verified stop/start and a checked cold archive. Game syntax, gameplay readiness and Box64 were outside these fixtures. |
| Dashboard | `npm ci`, typecheck, lint, purge, 130 tests and production build passed. Production Chromium smoke passed. The structural Chromium suite passed 30 invariants across 64 route, theme and viewport combinations on a fresh dev cache after the first local run timed out. |
| Standalone relay | 21 tests passed. Coverage includes legacy Paper enrollment, 5.0 and 6.0 compatibility, mixed-version rooms, Bedrock Host admission, one-use enrollment, expiry, revocation, replay rejection and scoped routing. Standalone smoke and packaging passed. |
| Mixed-fleet Chromium | A browser claimed an actual local relay grant and showed TonimBedrock with three independently authenticated fixture Paper rooms. The browser verified node deduplication, edition switching, a filtered bounded Docker log view, read-only behavior and zero axe violations on desktop and mobile. |

Reproduce the cross-repository checks using the exact revisions in the two drafts. Browser receipts and screenshots are produced by `scripts/bedrock-qa.mjs`. GitHub Actions results and artifacts are linked from the drafts. [Core draft](https://github.com/ZpkDxGames/PlexonPanel/pull/104) · [Dashboard draft](https://github.com/ZpkDxGames/PlexonPanel-Dashboard/pull/95) · [Reproduction and deployment](BEDROCK_ARM64_RUNBOOK.md)

The first implementation CI run passed on ARM64. On x64 it exposed an existing lifecycle fixture that assumed its single-worker executor was idle as soon as an authorization denial reached the test. The fixture now exercises capability and confirmation on independent instances. [First implementation CI](https://github.com/ZpkDxGames/PlexonPanel/actions/runs/44965195284)

## Capability status

| Capability | Status |
| --- | --- |
| Host-only identity, private enrollment and revocation, Docker status, CPU/RAM, uptime, observed BDS version and bounded redacted logs | Implemented and verified locally. Native Ubuntu ARM64 and the actual Bedrock image remain release acceptance gates. |
| Existing Paper rooms and Protocol 3 transport | Verified with signed fixtures and the existing Java and browser suites. Production cutover requires checking the three deployed rooms and the live relay artifact. |
| Lifecycle, fixed console input, player administration and cold backups | Local staged implementations with separate tests. Browser and relay actions require their own signed authorization, review, audit and staging acceptance before activation. |
| Gameplay-aware readiness, live roster, game-specific command acknowledgement | Pending positive evidence against the pinned Bedrock release on ARM64/Box64. |
| Restore, settings mutations and live LevelDB snapshots | Pending separate implementation and acceptance. |

Production deployment was outside the execution scope. Use the staged cutover and rollback procedure after the operator approves the target, window and artifact digests. [Source audit](BEDROCK_SOURCE_AUDIT.md) · [Architecture decision](BEDROCK_ARCHITECTURE.md) · [Operation gates](BEDROCK_OPERATIONS_STAGING.md)
