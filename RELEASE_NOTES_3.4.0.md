# PlexonPanel 3.4.0 — Host-authoritative Linux console

PlexonPanel 3.4.0 extends the existing protocol-3 control plane so the optional Linux Host Companion can stream the real `plexoncraft.service` journald output to the Dashboard while Paper remains the only remote command-execution authority. The release also consolidates the standalone relay line and tightens Paper-side listener/telemetry scheduling without changing server identity, pairing, device grants, or the signed Protocol 3 envelope.

## Host journald console

- Adds a Host-owned `JOURNALD` console source for the configured systemd service using the fixed `/usr/bin/journalctl` executable and explicit `ProcessBuilder` arguments; no shell command construction is introduced.
- Persists journal cursor and invocation state under the Host data directory so reconnects and Host restarts can resume from a bounded source position instead of blindly replaying the whole service log.
- Uses bounded replay, recent-history, queue, batch, and maximum-line limits with drop accounting and restart backoff.
- Applies the shared console severity classifier and redactor before transport. Additional Host-local redaction expressions can be configured without sending those expressions to the browser.
- Reports explicit source health/state so relay authority depends on a healthy readable journal, not merely on a connected Host socket.

## Authority and fallback

- Host becomes the preferred console producer only when it is authenticated, its journald source is healthy, and local Host policy enables full console viewing.
- Paper keeps its existing `latest.log` tail positioned while Host is authoritative but suppresses duplicate publication. If Host authority disappears, Paper resumes as the fallback producer without intentionally replaying the suppressed interval.
- `console.execute` remains Paper-only. The Host advertises no command-execution capability, and the matched relay rejects attempts to route console commands to Host.
- Protocol remains 3 and existing device grants are unchanged. Console visibility continues to require the intersection of device scope and local capability.

## Console privacy and bounds

- Shared redaction/classification code is used by both Paper fallback and Host journald paths.
- Host console batches are bounded before relay transport. The matched Dashboard keeps a bounded browser-session history and performs source-transition deduplication using journal/session metadata where available.
- The Dashboard may continue showing Host console output while Paper is offline; the command entry is unavailable until Paper reconnects.
- Clearing the Dashboard console remains browser-local and never deletes journald or Minecraft log files.

## Paper performance hardening

- Plugin/world lifecycle changes and player roster invalidations are debounced into near-term refreshes instead of scheduling an immediate full inventory snapshot for every event in a burst.
- Existing bounded telemetry/presence workers, in-flight coalescing, slow reconciliation timers, and holder-based GUI event rejection remain in place.
- The changes do not introduce an unbounded queue, synchronous relay I/O on the Paper main thread, or a new polling loop.

## Host configuration migration

Existing Host configuration remains loadable because an omitted `console` object migrates to disabled defaults. The public conservative example keeps Host console disabled. The full-control example enables:

- `console.view.errors`
- `console.view.full`
- `console.enabled: true`
- source `JOURNALD`
- executable `/usr/bin/journalctl`
- bounded replay/history/queue/batch settings

The Host still forces `console.execute.allowed` false.

## Matched Dashboard and relay

The matched PlexonPanel-Dashboard 3.4.0 release consolidates the previously separate standalone relay source line into the canonical repository and applies the same Host-console authority rules to both Cloudflare Worker and standalone Node relay runtimes. Worker and standalone continue to preserve Protocol 3 pairing, access-sync, backup coordination, scope filtering, replay protection, and bounded transport behavior.

## Validation and runtime status

Repository CI is responsible for Java 25 compilation, tests/checks/Javadoc, x64 and ARM64 build verification, matched Paper/Host packaging, release-manifest provenance, and SHA-256 verification. Dashboard/relay CI separately verifies lint, type checking, Worker/standalone relay tests and smoke/package paths, and the production Next.js build.

Live PlexonCraft acceptance remains a separate runtime gate. Until the final Paper JAR, Host JAR, relay and Dashboard are actually deployed and exercised on the authorized Linux host, runtime certification must remain `NOT_EXECUTED` rather than being inferred from source or CI success.
