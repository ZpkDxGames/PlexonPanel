# Changelog

## 3.5.0 — backup control room and durable Google Drive flow (live acceptance pending)

- Move maintenance warning broadcasts, final `save-all flush`, and post-start readiness probing onto a Host-local fixed-command RCON client so the Paper plugin is no longer required for those maintenance steps.
- Add protected Host `commandChannel` configuration with loopback-only targets, a secret-file reference instead of an inline password, bounded command/readiness timeouts, and no browser-facing arbitrary command route.
- Add validated 30-, 15-, 10-, and 5-minute initial full-backup countdown presets. Persist the selected duration, warning plan, deadline and consumed boundaries so reconnect/restart recovery skips missed notices instead of replaying them.
- Enforce a tested final-save safety gate: any RCON authentication, timeout, secret, protocol, or save-flush failure fails maintenance before the systemd stop continuation can run.
- Replace Paper reconnection as maintenance startup readiness with active-systemd plus fixed RCON readiness verification.
- Fix Google Drive truthfulness so a provider connectivity test never updates the last successful remote-backup verification. Preserve a verified local archive on bounded remote failure and support retry-upload without another shutdown.
- Constrain full-backup preflight, sizing and cold-archive traversal to the exact configured top-level `backups.include` allowlist. Unrelated server-root entries are never read or archived, while missing, duplicate, empty, symlinked or unreadable configured sources continue to fail closed.
- Guarantee server-availability recovery after a backup-owned stop: start Minecraft and verify systemd plus RCON readiness after success or degraded remote failure.
- Add restart settings with the same countdown presets, daily/weekly/selected-weekday scheduling and independent bounded shutdown/startup timeouts; restart scheduling never creates a backup.
- Rebuild the Backups page as a responsive Host-authoritative control room with preflight, countdown selection, durable phase reconstruction, local/remote verification, degraded recovery and retry controls.
- Keep the live Minecraft tree read-only in the stable Host. Force Host file mutations and remote restore off while retaining read-only inspection and historical interrupted-restore recovery.
- Accept authenticated empty Host console replay and the bounded source-status ordering race without tearing down the relay session.
- Add RCON protocol/security/config/countdown/recovery tests and Host-local migration guidance while preserving signed Protocol 3, existing identities and immutable grants.
- Source CI and preview evidence do not certify production. Stable publication remains gated on the real Ubuntu 24.04/Java 25/Paper 26.2 systemd, RCON, rclone/Google Drive, browser reconnect and degraded-retry acceptance run.

## 3.4.0 — Host-authoritative Linux console and relay parity

- Add a Host-owned journald console source for the configured PlexonCraft systemd service with fixed executable/arguments, bounded replay/history/queues/batches, cursor and invocation persistence, restart backoff, drop accounting, shared severity classification, and pre-transport redaction.
- Make Host console authority depend on authenticated source health and local console capability; keep Paper `latest.log` as an automatically resumable fallback without intentionally replaying Host-owned intervals.
- Preserve Paper as the sole `console.execute` authority. Host never advertises command execution, and the matched relay rejects attempts to route console commands to Host.
- Extend Protocol 3 console line metadata with optional source/session/journal fields without introducing Protocol 4, re-pairing, a new server identity, or mutable device grants.
- Consolidate the standalone relay release line into the canonical Dashboard repository and require Worker/standalone parity for Host console authority, scope filtering, ready-state metadata, and Paper-only command execution.
- Bound and deduplicate Dashboard console history across Host/Paper transitions while keeping Host output visible when Paper is offline and keeping clear/export actions browser-local.
- Debounce listener-driven plugin/world/roster refreshes and preserve existing bounded telemetry workers, in-flight coalescing, slow reconciliation timers, and holder-based GUI event rejection.
- Add backward-compatible Host `console` configuration. Omitted console configuration migrates to disabled defaults; the full-control example explicitly enables Host journald viewing.
- Keep live PlexonCraft deployment and restart/failure acceptance as a separate runtime gate; source/release manifests continue to report `runtimeCertification=NOT_EXECUTED` until those checks are actually performed.

## 3.2.0 — stable repository closure

- Promote the accepted `3.2.0-rc.3` Java source line to stable `3.2.0` without changing protocol 3, identity/pairing/device-grant semantics, or the Paper/Host authority split.
- Keep the accepted PlexonChats lifecycle/API compatibility repair: lifecycle-aware service discovery, current API/event contract, stale-reference avoidance and single-delivery GLOBAL chat semantics.
- Keep PlexonCore diagnostic-only with supported API `>=1.0 <3.0`, pinned 2.0.4 compile boundary and no shaded Core runtime classes.
- Generalize x64/ARM64 Build verification around the repository version instead of a hard-coded RC3 version.
- Replace one-off version-specific publication workflows with one exact-`main` stable release workflow for the matched Paper/Host pair.
- Publish JAR pair, examples, release manifest, test summary and SHA-256 checksums from the verified final source state.
- Refresh current README/Core/API release documentation while preserving historical migration and acceptance evidence.
- Keep live PlexonCraft deployment as a separate operational follow-up; the release manifest continues to report `runtimeCertification=NOT_EXECUTED`.

## 3.1.0 — PlexonCore migration candidate, live acceptance pending

- Register the Paper agent as PlexonCore module `panel` against Core API `>=1.0 <2.0`, with `STARTING`/`READY`/`DEGRADED`/`FAILED` diagnostics and safe standalone fallback.
- Add the local read-only `PlexonPanelAPI` through Bukkit `ServicesManager` for sanitized product, protocol, Core mode, relay state, identity fingerprint, pairing and local capability diagnostics.
- Preserve signed wire protocol 3 and `/v1`; no protocol 4, new remote Core scope, identity migration or device-grant migration was introduced.
- Keep PlexonCore diagnostic-only: Core metadata cannot grant scopes, bypass `ControlPolicy`, run remote actions, rotate identity, expose pairing secrets or own Panel transport.
- Keep `/plexon reload` transport-neutral. Core registration does not create/close `GatewayClient`, restart streams/telemetry/Host or trigger `access.sync`.
- Preserve all 3.0.2 reconnect/failure-isolation behavior, including stale-socket isolation, destination failure isolation, access-sync rules and Host reconciliation semantics.
- Repair a missing `protocol-version.txt` as protocol 3 without clearing existing pairing state, preventing an accidental re-pair requirement during upgrade.
- Add Core absence/API/read-only/isolation regression coverage and CI distribution checks that reject shaded PlexonCore runtime classes.
- Coordinate Paper/Host bundle metadata to 3.1.0 while keeping Host behavior and authority independent from PlexonCore.
- Add Core/API/migration documentation, RC-first release workflow and new live gates for Core registration, Core-reload transport stability, standalone mode and 20-cycle lifecycle acceptance.

## 3.0.2 — reliability candidate, live lifecycle acceptance pending

- Stop Host reconnect/service polling from publishing unconditional full `access.sync` snapshots.
- Keep Host access publication mutation-driven through authorized local revocation.
- Separate Paper authenticated-session initialization from Dashboard snapshot refresh so browser reconnects do not re-sync access.
- Scope Paper ping/send/close/error recovery to the socket that actually failed and preserve bounded reconnect/session reset behavior.
- Extend `/ppanel diagnostics` with reconnect, session-prefix, protocol-code and critical-queue visibility without printing secrets.
- Make the PlexonCraft full-control preset pair new intended operators as Owner while preserving `Owner = Scopes.ALL` and local capability intersection.
- Mark Java SIGTERM (143) as a successful intentional Host systemd stop while preserving `Restart=on-failure` and `RestartSec=5`.
- Preserve protocol v3, identities, the shared access registry and existing immutable grants.

## 3.0.1 — review candidate, live acceptance pending

- Added explicit Paper and Host full-control examples for the intended PlexonCraft deployment while keeping conservative public defaults unchanged.
- Enabled the full truthful Paper capability set through local policy: history/location/address, console/chat, all implemented player actions, plugin config/reload, SafeFiles operations, backup coordination, device revoke, audit and settings.
- Enabled the full truthful Host capability set through configuration: telemetry, lifecycle, files, complete backup family, audit, devices and settings; Host still rejects Paper-only scopes.
- Fixed `plugins.reload` capability advertisement so at least one configured reload command must actually pass the same local console allow/deny policy used at execution time. The supplied preset exposes only `plexonpanel reload`, never generic `/reload`.
- Added Paper policy tests, Host authority tests and backup effective-gating tests. Existing protocol tests continue to enforce immutable grants, Owner/local-policy intersection and unknown-scope rejection.
- Preserved protocol 3, Ed25519 signing/pinning, immutable device grants, high-risk confirmations, audit, file confinement, service-name validation, backup gating, bounded queues/payloads and replay/clock protections.
- Added full-capability migration/access/operations documentation, including the required revoke/re-pair path when local policy is enabled but an existing device lacks a scope.
- Bumped coordinated Paper/Host bundle metadata and gated build/release assets to 3.0.1. Stable publication remains blocked on fresh live acceptance evidence.

## 3.0.0 — review candidate, live acceptance pending

- Added Paper-authoritative `JOINED`/`LEFT` presence observations with per-session IDs, honest unknown-disconnect semantics, bounded daily/size-rotated JSONL storage, atomic summaries, retention cleanup, stable cursor pagination, and history disabled by default.
- Added protocol-3 `players.presence`, `players.history.list`, and `players.snapshot.request` extensions without changing signed envelopes, `/v1` routes, server identity, or existing credential scopes.
- Added `players.history.view` for newly issued Moderator, Administrator, and Owner grants. Existing grants remain immutable; Observer does not receive history by default and Owner cannot bypass disabled local history.
- Split server, system, world, plugin, player snapshot, and immediate presence flows. Paper captures Bukkit state on its primary thread; bounded workers handle storage and transport. Reconnect discards stale-session telemetry and requests one authoritative roster snapshot.
- Added event-priority queue admission, coalesced/rate-limited manual roster refresh, snapshot/delta reconciliation, duplicate protection, and truthful reconnect/offline dashboard states.
- Rebuilt the unchanged Host companion as 3.0.0; no Host player capability or filesystem scraping was added.
- Updated privacy, configuration, migration, diagnostics, artifact metadata, cross-language tests, and gated draft-release checks. Stable publication remains blocked on recorded live acceptance.

## 2.0.0 — review candidate, production acceptance pending

- Protocol 3, local role/scoped grants, capability intersection, live revocation, signed connection sequences and restart-aware duplicate suppression.
- Paper 26.2/Java 25 telemetry, typed controls, console/chat policy and holder-owned inventory GUI.
- Restricted conflict-aware file editing and verified bounded transfers.
- Optional non-root systemd companion, local/rclone backups and stopped-server restore with emergency archive/idempotent journal recovery.
- Matching responsive twelve-section dashboard, isolated server workspaces, explicit connectivity and actual operation results.
- Cross-language fixtures, security/visual/runtime checks, review artifacts and gated draft release workflow.
