# PlexonPanel 3.2.0-rc.2

PlexonPanel 3.2.0-rc.2 is the final intended Phase 2 source/release candidate for the control plane. It remains a compatible minor release from stable 3.1.1: wire protocol stays at version 3 while Paper/Host reconnect behavior, diagnostics and the coupled standalone-relay deployment path are hardened.

RC1 is superseded for coupled deployment because dashboard dependency certification discovered HIGH/CRITICAL advisories after the immutable Java RC1 prerelease was published. The remediated dashboard dependency graph belongs to RC2. RC1 remains unchanged as historical evidence.

## Exact coupled boundary

- Dashboard/relay repository: `ZpkDxGames/PlexonPanel-Dashboard`.
- Dashboard/relay branch: `phase2/3.2.0-control-plane`.
- Dashboard/relay exact candidate: `bb2c1d76f38ce9ce49aa7f3ece278cc0df2d02f2`.
- Dashboard CI: `34524470086` — SUCCESS.
- Matching Java component: this repository's exact `3.2.0-rc.2` tag/candidate; its SHA is recorded by `release-manifest.json` at build/release time.
- Wire protocol: `3`.
- Java: `25`, class major `69`.
- PlexonCore: `2.0.4`, compile-only/non-shaded; CI pins release JAR SHA-256 `61d625a717da9f46ee9231e1970d84b4c317ae12cf4090cdf7c9d39b6a1a9baf`.

## Preserved architecture

- Paper remains authoritative for Paper/Bukkit operations and Minecraft-side capability checks.
- Host remains authoritative only for explicitly enabled VPS/service capabilities.
- Dashboard remains presentation plus authenticated operator workflow.
- Relay remains transport, routing and framing rather than execution authority.
- One-use five-minute pairing, stable server identity, device grants, request/replay guards, bounded queues/workers, reconnect backoff and CPU available/unavailable semantics are preserved.
- The Worker relay remains a rollback path while the standalone relay/Tunnel path awaits runtime certification.

## Source/release certification

The final dashboard candidate passed its high/critical dependency audit, lint, TypeScript checks, relay tests, dashboard tests, Worker smoke, standalone smoke/package and production Next.js build. Java CI must independently verify Java 25, tests/check/Javadocs, both JARs, class major 69, protocol 3, PlexonCore checksum/non-shading, checksums and provenance before this RC is published.

The release manifest must identify the exact Java source commit, dashboard candidate above, both rollback commits and `runtimeCertification=NOT_EXECUTED`.

## Rollback

- PlexonPanel: `v3.1.1` / `e0984b625d692de6076afa7e20c4fe4b35f07e9a`.
- Dashboard: `03777c7dc108b54dda625c7f56f5e723ca35124f` plus the currently deployed Worker relay rollback path.
- Preserve paired identity/device state unless intentional revocation is required.

## Runtime boundary

This candidate is SOURCE/CI/RELEASE certified only. PlexonCraft runtime certification has **NOT EXECUTED** for RC2. Stable `v3.2.0` remains unpublished until `docs/PHASE2_RUNTIME_GATES.md` passes with zero HIGH/CRITICAL runtime defects.
