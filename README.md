# PlexonPanel 3.0.0

PlexonPanel is an outbound-only control room for Paper 26.2 on Java 25, with an optional non-root Linux host companion. Version 3.0 adds Paper-authoritative player presence history, immediate join/leave roster deltas, independently scheduled telemetry, and full-snapshot reconciliation. The signed wire contract remains **protocol 3** and routes remain under `/v1`.

**Release status:** review candidate. Automated checks do not replace the pending live acceptance gates in [validation](docs/VALIDATION.md), and the stable release must remain blocked until those gates contain real evidence.

## Privacy-first player presence

Current online-player updates operate under `players.view`. Persistent history is a separate, local Paper capability and is disabled by default. When an operator explicitly enables `player-history.enabled`, Paper writes a bounded JSONL journal and summary beneath `plugins/PlexonPanel/presence/`. The relay routes results transiently and the dashboard does not cache detailed history.

History access requires all three conditions:

1. Local history policy is enabled.
2. The immutable device grant contains `players.history.view`.
3. Paper and the relay both authorize the request.

Existing protocol-3 credentials retain their old scopes and are never silently upgraded. Revoke and re-pair only devices that should gain the new history scope. Observer remains current-roster-only by default.

## Build and install

```sh
./gradlew --no-daemon clean test javadoc :agent:jar :host-agent:jar
python3 scripts/package-release.py
```

Expected outputs are `agent/build/libs/PlexonPanel-3.0.0.jar`, `host-agent/build/libs/plexonpanel-host-3.0.0.jar`, and these review assets in `build/release/`:

- `PlexonPanel-3.0.0.jar`
- `plexonpanel-host-3.0.0.jar`
- `PlexonPanel-3.0.0-examples.zip`
- `release-manifest.json`
- `SHA256SUMS.txt`

Install the Paper JAR only while the server is stopped. Start once to create configuration and identity, then configure the pinned relay WSS URL/public key. Verify `/plexonpanel status`, `/plexonpanel capabilities`, and `/plexonpanel diagnostics` before enabling mutations or history. Preserve the existing UUID, fingerprint, identity files, and `access/devices.json` during upgrades.

The optional Host artifact is rebuilt at 3.0.0 because all Gradle modules share one bundle version; it gains no player-history capability and never reads Paper player data.

## Security boundary

Every action intersects the immutable device grant with the executing agent's current local capability. Owner cannot override a local denial. New installations disable mutations, full console, chat sending, files, host lifecycle, backups, and persistent player history. No Firebase, telemetry database, inbound Minecraft administration port, generic shell, or RCON is required. Cloudflare retains identity/access/pairing coordination only—not telemetry, inventories, presence events, history results, file bodies, or action results.

See [configuration](docs/CONFIGURATION.md), [operations](docs/OPERATIONS.md), [roles and scopes](docs/ACCESS.md), [protocol 3](docs/PROTOCOL.md), [migration and rollback](docs/MIGRATION.md), [privacy](PRIVACY.md), and [validation/release gates](docs/VALIDATION.md).
