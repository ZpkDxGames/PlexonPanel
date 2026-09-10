# PlexonPanel 3.2.0-rc.1

PlexonPanel 3.2.0-rc.1 is the Phase 2 control-plane release candidate. It is a compatible minor release from stable 3.1.1: wire protocol remains version 3 while Paper/Host reconnect behavior, diagnostics and the coupled standalone relay deployment path are hardened.

## Exact release boundary

- Paper plugin and host companion: this repository, `phase2/3.2.0-control-plane`.
- Dashboard/standalone relay source candidate: `ZpkDxGames/PlexonPanel-Dashboard` commit `ece21970f5dbea71bb79951b6551e7b798d9fa8b` on the matching Phase 2 branch.
- Wire protocol: 3.
- Java/Paper: Java 25 / class major 69, Paper API 26.2.
- PlexonCore: compile-only integration, API range `>=1.0 <3.0`, CI pinned to the exact PlexonCore 2.0.4 release JAR checksum.

## Phase 2 changes

- Shared bounded exponential reconnect backoff with jitter for Paper and Host participants; retry diagnostics expose attempt count/backoff/session state.
- Host loopback `ws://` is accepted only for the standalone local relay path; non-loopback plaintext WebSocket endpoints remain rejected.
- Existing one-use five-minute pairing, stable server identity, request/replay guards, immutable device grants, capability checks and execution-boundary authorization are preserved.
- CPU telemetry explicitly distinguishes unavailable data from a real numeric zero and retains low-frequency/bounded expensive sampling.
- CI adds explicit Phase 2 control-plane, CPU semantics, Java 25 distribution and cross-component release checks.
- Dashboard/relay adds the coupled standalone Protocol 3 relay candidate while retaining the Worker deployment as rollback until runtime certification.

## Authority model

Paper owns Paper/Bukkit interaction and Minecraft-side authorization. Host owns only explicitly enabled VPS/service capabilities. Dashboard owns presentation and authenticated operator requests. Relay owns transport, routing and framing. Browser state is never authoritative for a forbidden server/host action.

## Migration order

Preserve the 3.1.1 Paper/Host binaries and current dashboard/Worker relay first. Stage the standalone relay on loopback, verify service health and Tunnel routing, deploy the exact dashboard candidate, then install both 3.2.0-rc.1 Java artifacts. Do not remove the rollback relay or promote stable until the complete PlexonCraft end-to-end gate passes.

## Rollback

- PlexonPanel stable rollback: `v3.1.1`, commit `e0984b625d692de6076afa7e20c4fe4b35f07e9a`.
- Dashboard source rollback: `main`, commit `03777c7dc108b54dda625c7f56f5e723ca35124f`, plus the previously deployed Worker relay configuration.
- Preserve `plugins/PlexonPanel` identity/device state when rolling back unless revocation is intentional.

## Certification boundary

This prerelease is SOURCE/CI/RELEASE only. Runtime certification for 3.2.0-rc.1 is not claimed. Stable 3.2.0 remains unpublished until `docs/PHASE2_RUNTIME_GATES.md` passes with zero HIGH/CRITICAL defects.
