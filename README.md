# PlexonPanel 3.0.2

PlexonPanel is an outbound-only control room for Paper 26.2 on Java 25, with an optional non-root Linux host companion. Version 3.0.2 hardens relay/agent lifecycle recovery while keeping every currently implemented Paper and Host capability locally enable-able through explicit full-control presets while preserving the truthful authority split, immutable device grants and the signed **protocol 3** wire contract under `/v1`.

**Release status:** review candidate. Automated checks do not replace the pending live acceptance gates in [validation](docs/VALIDATION.md), and the stable release must remain blocked until those gates contain real evidence.

## Download PlexonPanel 3.0.2

The current tested 3.0.2 release candidate is published from this repository under [`v3.0.2-rc.1`](https://github.com/ZpkDxGames/PlexonPanel/releases/tag/v3.0.2-rc.1).

- [Download `PlexonPanel-3.0.2.jar`](https://github.com/ZpkDxGames/PlexonPanel/releases/download/v3.0.2-rc.1/PlexonPanel-3.0.2.jar) — install this JAR in Paper's `plugins/` directory.
- [Download `plexonpanel-host-3.0.2.jar`](https://github.com/ZpkDxGames/PlexonPanel/releases/download/v3.0.2-rc.1/plexonpanel-host-3.0.2.jar) — run this separately as the Linux Host companion; do **not** place it in Paper's `plugins/` directory.
- [Download `PlexonPanel-3.0.2-examples.zip`](https://github.com/ZpkDxGames/PlexonPanel/releases/download/v3.0.2-rc.1/PlexonPanel-3.0.2-examples.zip) — configuration, systemd/policy examples and operational documentation.
- [Download `SHA256SUMS.txt`](https://github.com/ZpkDxGames/PlexonPanel/releases/download/v3.0.2-rc.1/SHA256SUMS.txt) for integrity verification.

The release remains marked as a prerelease until the documented live Linux/Paper/Cloudflare/Vercel acceptance matrix is completed.

## Full local capabilities

The installable defaults remain conservative. The intended PlexonCraft full-control deployment can instead use [Paper full-control](agent/examples/config-full-control.yml) and [Host full-control](host-agent/examples/host-config-full-control.json). Read [full local capability deployment](docs/FULL_CONTROL.md) before applying them.

Paper remains authoritative for players, console, chat, player actions and plugins. Host remains authoritative for systemd lifecycle and the complete backup family. Both expose files/telemetry/audit/devices/settings only where real handlers exist. Host still rejects Paper-only scope families and Paper still does not claim `server.start`, `server.stop` or `server.restart`.

`plugins.reload` is advertised only when at least one explicit plugin reload mapping is actually executable under the local console allow/deny policy. The supplied preset exposes only PlexonPanel's own `/plexonpanel reload` path; generic Bukkit/Paper `/reload` is not enabled.

Existing protocol-3 credentials keep their original immutable scopes. If Access shows a local capability enabled but `This Device: Not granted`, revoke and re-pair that intended device. A newly paired Owner receives the current canonical `Scopes.ALL`; Owner still cannot override a local denial.

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

Expected outputs are `agent/build/libs/PlexonPanel-3.0.2.jar`, `host-agent/build/libs/plexonpanel-host-3.0.2.jar`, and these review assets in `build/release/`:

- `PlexonPanel-3.0.2.jar`
- `plexonpanel-host-3.0.2.jar`
- `PlexonPanel-3.0.2-examples.zip`
- `release-manifest.json`
- `SHA256SUMS.txt`

Install the Paper JAR only while the server is stopped. Start once to create configuration and identity, then configure the pinned relay WSS URL/public key. Verify `/plexonpanel status`, `/plexonpanel capabilities`, and `/plexonpanel diagnostics` before enabling mutations or history. Preserve the existing UUID, fingerprint, identity files, and `access/devices.json` during upgrades.

If the Host companion is used, update its JAR to 3.0.2 in the same maintenance window and preserve its identity/configuration. Its authority remains separate from Paper; it never gains player, console, chat or plugin capabilities.

## Security boundary

Every action intersects the immutable device grant with the executing agent's current local capability. Owner cannot override a local denial. Public defaults disable mutations, full console, chat sending, files, host lifecycle, backups, and persistent player history. Full-control examples still retain confirmations, audit, command allow/deny rules, path confinement, backup effective gating, service-name validation, bounded payloads/queues and signed transport. No Firebase, telemetry database, inbound Minecraft administration port, generic shell, or RCON is required.

See [configuration](docs/CONFIGURATION.md), [operations](docs/OPERATIONS.md), [roles and scopes](docs/ACCESS.md), [protocol 3](docs/PROTOCOL.md), [migration and rollback](docs/MIGRATION.md), [privacy](PRIVACY.md), and [validation/release gates](docs/VALIDATION.md).
