# PlexonPanel 3.1.0

PlexonPanel is an outbound-only control room for Paper 26.2 on Java 25 with an optional non-root Linux Host companion. Version 3.1.0 makes the **Paper agent** a first-class PlexonCore module while preserving the complete 3.0.2 security model, Paper/Host authority split, immutable device grants and signed **protocol 3** wire contract under `/v1`.

**Release status:** 3.1.0 release candidate. Automated checks do not replace the pending live acceptance gates in [validation](docs/VALIDATION.md). Stable `v3.1.0` remains blocked until those gates contain reviewed evidence.

## PlexonCore mode

With compatible PlexonCore installed, the Paper plugin registers module ID `panel` and should appear as:

```text
PlexonPanel — READY | Core API | 3.1.0
```

Supported Core API is `>=1.0 <2.0`; PlexonCore 1.0.0 / API 1.0 is the coordinated runtime. Core is a Paper soft dependency and is **not** shaded into `PlexonPanel-3.1.0.jar`.

Without Core, with Core disabled, or with an unsupported Core API, PlexonPanel continues in `STANDALONE` mode. The Host companion, protocol module, relay and dashboard do not acquire a PlexonCore runtime dependency.

See [PlexonCore integration](docs/PLEXONCORE.md), [local API](docs/API.md) and [3.1.0 migration](docs/MIGRATION_3_1.md).

## Security and authority boundary

Paper remains authoritative for Paper/JVM telemetry, players, console/chat, plugin actions, Paper files, pairing/device registry and Paper-local policy. Host remains authoritative for Linux telemetry, systemd lifecycle, Host files/backups and Host-local policy. Relay remains a signed protocol verifier/router and Dashboard remains the browser presentation/control room.

PlexonCore handles local module registration and diagnostics only. Its capability metadata cannot grant scopes, bypass `ControlPolicy`, create Owner privilege, bypass confirmation, rotate identity or execute remote actions.

Existing protocol-3 credentials and immutable device grants are never silently upgraded. Upgrading 3.0.2 → 3.1.0 must preserve server UUID, fingerprint, Paper/Host identities, pairing state, device IDs/generations/revisions and existing browser credentials. A missing `protocol-version.txt` marker is recreated as protocol 3 without clearing pairing.

## Full local capabilities

The installable defaults remain conservative. The intended PlexonCraft full-control deployment can use [Paper full-control](agent/examples/config-full-control.yml) and [Host full-control](host-agent/examples/host-config-full-control.json). Read [full local capability deployment](docs/FULL_CONTROL.md) before applying them.

`plugins.reload` is advertised only when at least one explicit plugin reload mapping is executable under the same local console policy. Generic Bukkit/Paper `/reload` is not enabled.

## Privacy-first player presence

Current online-player updates operate under `players.view`. Persistent history remains separate, local to Paper and disabled by default. History requires local history policy, the immutable `players.history.view` device grant and Paper/relay authorization. PlexonCore registration does not enable history or alter presence payloads.

## Build and package

CI provisions the official PlexonCore 1.0.0 API artifact after verifying its pinned SHA-256, then runs:

```sh
./gradlew --no-daemon clean test check javadoc :agent:jar :host-agent:jar
python3 scripts/package-release.py
```

Expected outputs:

- `PlexonPanel-3.1.0.jar`
- `plexonpanel-host-3.1.0.jar`
- `PlexonPanel-3.1.0-examples.zip`
- `release-manifest.json`
- `SHA256SUMS.txt`

The release contract verifies protocol 3, required API/Core bridge classes, 3.1.0 plugin metadata and that no `com/zpkdxgames/plexoncore/` runtime classes are bundled.

## Install / upgrade

Stop Minecraft first. Back up and preserve the complete `plugins/PlexonPanel/` directory; it contains critical identity/access state. Replace the Paper JAR and, if coordinated, the Host JAR. Do not delete identity/access files and do not re-pair as an upgrade workaround.

After startup verify `/plexon modules`, `/plexonpanel status`, `/plexonpanel capabilities` and `/plexonpanel diagnostics`. Confirm the UUID/fingerprint and existing browser credential are unchanged.

`/plexon reload` must not reconnect Paper/Host/browser transport or trigger `access.sync`. `/plexonpanel reload` remains the Panel-owned runtime reload path.

## RC-first release process

`v3.1.0-rc.1` is published first for live acceptance. Stable `v3.1.0` is produced only after all entries in `docs/release-gates.json` contain approved evidence, including Core module registration, Core-reload transport stability, standalone operation and the required 20 Stop → Start lifecycle cycles.

See [configuration](docs/CONFIGURATION.md), [operations](docs/OPERATIONS.md), [roles and scopes](docs/ACCESS.md), [protocol 3](docs/PROTOCOL.md), [migration and rollback](docs/MIGRATION.md), [privacy](PRIVACY.md), and [validation/release gates](docs/VALIDATION.md).
