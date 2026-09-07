# PlexonPanel 3.0.0 implementation

Baselines: plugin `6317bfbba1990cd8a96852b03f8097a524f745db`; dashboard `98166f7d9b3851a1c96d160c2b5eefc3b231c58f`.

1. Preserve protocol 3, the outbound Ed25519 transport, Paper/Host identity pins, existing grants, bounded queues, local policy and database-less relay design.
2. Add Paper-authoritative join/leave events, honest reload/crash semantics and a bounded, private, disabled-by-default local presence journal.
3. Split telemetry cadences, capture Bukkit values on the primary thread, send presence on a dedicated bounded worker and reconcile with complete snapshots.
4. Add `players.history.view`, `players.history.list` and `players.snapshot.request` with independent Paper and relay enforcement, strict bounds and immutable existing grants.
5. Route transient presence/history without Durable Object persistence, strip history-only fields for unauthorized devices and keep action results private.
6. Integrate responsive Online/History player views, stable cursor pagination, truthful unavailable states, UTC tooltips and read-only offline details in Dashboard 2.2.0.
7. Verify Java 25 on Ubuntu x64/ARM64, workerd relay smoke, dashboard tests/builds, reproducible artifacts and release metadata.
8. Open paired review branches; merge, tag, release and deploy only after every live acceptance gate has approved evidence.

## Verification

Implementation and local checks are complete. See VALIDATION.md for exact results and pending live gates.

## Release gate

The attached specification requires a disposable Paper 26.2 integration run, production-like ARM64 verification, rclone validation, live Vercel/Cloudflare validation and a 30-minute stability run. Keep evidence and unverified requirements explicit. Do not publish production artifacts as validated before those gates pass.
