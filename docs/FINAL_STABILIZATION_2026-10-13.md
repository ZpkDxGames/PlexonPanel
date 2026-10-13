# Final stabilization — review candidate, not a stable release

## Baseline and plan

Remote refs verified before edits: Dashboard `revamp/6.5.0` at `51d433e865bdf5053e1819ec64406dc956655e79`; Core `main` at `209f8b41d4fe6360a8dcf2f9cdabe7b7df6cc128`. Both review branches are `fix/final-stabilization-2026-10-13`. Public versions remain unchanged pending certification.

1. Trace producers, reducer and views; preserve protocol/authority boundaries.
2. Map Paper process uptime, remove Console Plugins and duplicate Console code.
3. Replace the resource history and renderer; remove merged resource columns and old focused renderer.
4. Consolidate backup decisions, isolate query generations and retain durable failures.
5. Test source, build matched artifacts, run browser gates in CI, then require live certification.

## Findings and changes

- Paper `SystemMetrics.collect()` emits `processUptimeMillis` in `telemetry.system`. Overview incorrectly read `uptimeMillis` from health/system snapshots. It now selects the exact emitted field and applies Paper-system freshness. The reducer already rejects old agent sessions and clears system data on session rollover. Zero is valid; missing/stale is unavailable. No receipt-time-derived uptime or Host fallback is used. The invented historical uptime sparkline is removed.
- Console now has Console and Chat only. Plugin panel, reload button and unused ConsoleLines/filter/export state were removed. Inventory protocol and reducer remain: backend capability, diagnostics and permission contracts still use plugin actions, and removal of a UI workspace is not authorization to delete those contracts.
- Old FocusedMetricPanel is removed. Resource columns are removed from the merged Sample model. TimeSeries no longer renders CPU/memory; it continues to serve tick/player/GC charts. Resource events never append/reproject the tick history. Independent source owners contain bounded immutable pages; one append copies at most 128 points and 68 page references. Each point retains capture/receipt time, session, cadence, source, value/missing state, and service process identity when available. Session changes reset only that owner. Missing/invalid capture time does not become a fake receipt-time point.
- Charts select visible pages/points by binary search, preserve extrema within pixel buckets and split on null, cadence gaps, agent or service process changes. Rendering uses the shared 1-second clock; hover owns only inspection state. Y maxima expand immediately and contract after 30 seconds of calm. The same all-source scale limits compare toggles. Lines use no path morph animation in either motion mode. Source times stay independent; excessively future samples remain outside the present window rather than being shifted to a fabricated clock.
- Backup state now has a canonical `backupView` selector combining current successful query evidence with matching newer phase/progress events. Missing IDs no longer compare as the truthy placeholder `—`. Terminal durable failures cannot be overwritten by stale progress. A remote retry can be reported as verified without synthesizing a COMPLETED maintenance job.
- A single bounded query loop replaces the competing mount/poll interpretations. It ignores responses from old server/revision/Host identities, clears failed query results, prevents overlap and resets prepared actions on identity change. Recovery wins even when preflight cannot complete. Compact header, wrapping timeline, source/provider readiness and dedicated recovery evidence replace the oversized hero/recovery button.

## Producer-to-display audit

Protocol v3 envelopes preserve server ID, agent kind/session and original body. Relay forwards data; no source timestamps are regenerated here.

| Evidence | Producer/message | Cadence/session | State and freshness | Display |
|---|---|---|---|---|
| TPS, MSPT | Paper ServerSnapshot / telemetry.server | declared sourceIntervalMillis / paperSession | server, receipts.paperHealth; classifyTelemetry | Overview, tick charts |
| Player count | Paper telemetry.server | health cadence / paperSession | server.onlinePlayers | Overview |
| Ping/roster | Paper inventory.players, presence.delta | event/snapshot; reconciled 5.5s / paperSession | players; snapshot lineage, old-session rejection | Players, Overview |
| JVM heap, process CPU | Paper SystemMetrics / telemetry.system | declared system cadence / paperSession | system and independent heap/processCpu resource owners; paperSystem receipt | Overview, ResourcePanel |
| Uptime | Paper SystemMetrics.processUptimeMillis / telemetry.system | system cadence / paperSession | canonical paperProcessUptime selector; exact field and freshness | Overview |
| Host CPU/memory | Host SystemMetrics / telemetry.system | FAST_TELEMETRY_MILLIS / hostSession | hostSystem and independent hostCpu/memory owners; own receipt | HostTelemetry, ResourcePanel |
| Minecraft CPU/memory | Host SystemdService / service.status.resources | envelope sourceIntervalMillis / hostSession + processSession | service and independent serviceCpu/serviceMemory; own capture | ResourcePanel |
| Service readiness | Host service.status + RCON readiness cache | service cadence / hostSession | service.state/minecraftReady | runtime health, Backups recovery |
| Paper/Host connection | Relay dashboard.ready | connection events / bound sessions | ready.agents; snapshots reset on identity change | Fleet, runtime health |
| Console output | Host console.lines / journald | stream sequence / Host stream session | mergeConsoleLines, bounded 2500 lines, receipt/capture | ConsoleView |
| Console command | Paper console.execute | signed request / Paper session | command review/result; grant intersection | ConsoleView |
| Backup job | Host maintenance.status, maintenance.phase, backup.progress | query 3.5s active/15s idle + pushes / hostSession/jobId | canonical backupView; terminal durable state wins | Backups |
| Provider | Host maintenance.status.provider + successful preflight | same query cadence / hostSession | current query evidence only | Backups |
| Inventory | Host backup.full.list | same query cadence / hostSession | current restore-point records; never rewrites job success | Backups |

## Live backup diagnosis boundary

The exact live unreadable path is **not known**. No SSH target/key or paired Dashboard credential was provided to this execution. The existing walker already prunes configured exclusions in both preVisitDirectory and visitFileFailed. Claiming the live error was that historical bug would be unsupported. Core changes add sanitized relative-path evidence to source failures and retain it in preflight/durable failure messages. No ACL, exclusion, configured root or recovery flag has been changed on the VPS.

Classify the actual path before correction: excluded sensitive path -> validate loaded excludes/pruning; required content -> narrow read/traverse correction; stale include/root -> correct configuration. Never recursively grant Host access. Recovery resolution retains the existing live systemd-active and RCON probe checks.

## Verification and release gates

Local: npm ci, typecheck/unit/contract/30-minute virtual soak suite (82 tests), production build, gzip budget. Core Java 25: test/check/Javadoc/matched JARs (302 tests, zero failures/errors/skips). See final execution report for final counts.

Local Chromium could not start: `socket() failed: Operation not permitted`. Browser gates are added to CI; a launched CI workflow is not evidence of a pass. New gate covers six directive viewports, all relevant routes and Chat, reduced/normal motion, mounted uptime and independent resource geometry/hover. Developer projection counters are compile-time excluded from production.

Required before merge/stable: successful responsive/interaction/accessibility CI; visual review; actual uptime restart/reconnect; Console /list, /tps and policy tests; multi-minute graph observation under load; real cold backup through offsite verification; failed-provider retry without shutdown; browser/Host reconnect; recovery/partials inspection; both instances; exact-main artifacts; production deployment and build identity. None of these unavailable live results is substituted with fixture evidence.
