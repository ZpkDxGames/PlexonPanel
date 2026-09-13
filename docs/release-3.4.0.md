# PlexonPanel 3.4.0

PlexonPanel 3.4.0 is the matched Paper/Host release for Host-authoritative Linux console viewing, automatic Paper console fallback, canonical Worker/standalone relay parity, and Paper-side event/snapshot performance hardening.

## Stable boundary

- Paper plugin and Host Companion are released as a matched `3.4.0` pair.
- Java 25 / class major 69.
- Paper API 26.2.
- Signed wire protocol remains **3** under `/v1`.
- Existing server identity, pairing state, device generations/revisions and immutable grants are preserved.
- PlexonCore remains compile-only/non-shaded with the existing supported runtime boundary.
- Matched Dashboard/relay source: `ZpkDxGames/PlexonPanel-Dashboard@16d0768952d77b8054c5b8b88785d3f903c5359d`.
- Matched Dashboard/relay post-merge CI: GitHub Actions run `34775174696`, successful.
- The same provenance is recorded in `release-manifest.json`; runtime certification remains a separate field.

## Host-authoritative console

- The Host Companion can follow the configured PlexonCraft systemd unit through `/usr/bin/journalctl` using explicit `ProcessBuilder` arguments without a shell.
- Journal cursor/invocation state is persisted atomically under Host data storage for bounded resume/recovery behavior.
- Replay, recent history, producer queue, batching, line size and reconnect backoff are bounded.
- Shared classification and redaction run before console transport. Optional extra Host-local redaction patterns remain local configuration.
- Host authority requires authenticated Host transport, a healthy console source and enabled Host console capability.
- Paper `latest.log` remains positioned while suppressed and resumes publication when Host authority disappears.
- `console.execute` remains Paper-only; Host is read-only for console output and forces command capability off.

## Dashboard and relay match

The matched Dashboard 3.4.0 source line includes both Cloudflare Worker and standalone Node relay runtimes. Both enforce the same Host console health/authority rules, console scope filtering, Protocol 3 replay/signature checks, and Paper-only command execution. The Dashboard keeps bounded browser console history, source-transition deduplication, explicit source state, startup/invocation separators, and Host-output continuity while Paper is offline.

## Paper performance hardening

Listener-driven plugin/world/roster invalidations are debounced and coalesced into bounded near-term refreshes. Existing bounded telemetry/presence workers, in-flight snapshot coalescing, reconciliation timers and holder-owned GUI routing remain intact. The release does not introduce unbounded main-thread work or transport I/O on the Paper tick thread.

## Migration

Existing Host configuration remains valid. An omitted `console` object migrates to disabled defaults. Operators who want Host journald viewing can adopt the matching `console` object and console-view capabilities from `host-agent/examples/host-config-full-control.json` without deleting identities or re-pairing browsers.

## Assets

- `PlexonPanel-3.4.0.jar`
- `plexonpanel-host-3.4.0.jar`
- `PlexonPanel-3.4.0-examples.zip`
- `release-manifest.json`
- `SHA256SUMS.txt`
- `test-summary.txt`

## Runtime certification

Repository closure and runtime acceptance are intentionally distinct. The stable source release may record audited source, green CI, exact provenance, generated artifacts and checksums, but `runtimeCertification=NOT_EXECUTED` remains mandatory until the final Paper JAR, Host JAR, relay and Dashboard are deployed to the authorized PlexonCraft Linux host and startup/shutdown/restart, journal cursor recovery, Paper fallback, Host failure and relay restart scenarios are actually exercised.
