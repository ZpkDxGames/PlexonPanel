# PlexonPanel 3.2.0

PlexonPanel is an outbound-only control room agent for Paper 26.2 on Java 25 with an optional non-root Linux Host companion. The 3.2.0 stable line preserves signed **protocol 3**, `/v1` routes, existing identities/device grants, and the Paper/Host authority split.

The stable source line includes the accepted PlexonChats lifecycle/API compatibility repair from 3.2.0-rc.3. It does not introduce a protocol revision, re-pair requirement, new remote authority, or Dashboard/Relay product rewrite.

## Matched Java pair

Install the Paper and Host artifacts from the same release:

- `PlexonPanel-3.2.0.jar`
- `plexonpanel-host-3.2.0.jar`

Do not mix Java bundle versions when replacing the production pair. Preserve the existing `plugins/PlexonPanel/` identity/access state and Host identity/configuration during upgrades.

## PlexonCore mode

The Paper plugin registers module ID `panel` when a compatible PlexonCore API is available.

- Supported Core API: `>=1.0 <3.0`
- CI compile boundary: PlexonCore `2.0.4`
- Core remains a Paper soft dependency and is not shaded into the Panel JAR.
- Without a compatible Core service, Panel continues in `STANDALONE` mode.

PlexonCore owns only local module registration/diagnostics for this integration. It does not gain authority over Panel pairing, immutable device grants, transport, remote actions, files, backups, or identity.

See [PlexonCore integration](docs/PLEXONCORE.md) and [local API](docs/API.md).

## Protocol and security

Wire protocol remains **3**. Paper and Host use signed `/v1` transport and preserve the existing Ed25519 identities, device generations/revisions, replay/sequence protections, bounded queues, local policy intersection, high-risk confirmation, and audit rules.

Paper remains authoritative for Paper/JVM telemetry, players, console/chat, Paper actions/files and pairing/device state. Host remains authoritative for Linux telemetry, systemd lifecycle, Host files/backups and Host-local policy. The relay verifies/routes signed protocol traffic; the Dashboard is the browser presentation/control surface.

See [protocol 3](docs/PROTOCOL.md), [access/scopes](docs/ACCESS.md), [privacy](PRIVACY.md), and [operations](docs/OPERATIONS.md).

## PlexonChats integration

Panel performs lifecycle-aware optional discovery of the current PlexonChats API/event surface. The active Chats GLOBAL bridge is authoritative for remote global-chat publication, preventing duplicate Panel `chat.message` delivery. LOCAL, PM and cancelled public-chat paths remain excluded from that global stream.

## Build and package

CI runs the complete repository suite on Ubuntu 24.04 x64 and ARM64 with Java 25, provisions the pinned PlexonCore 2.0.4 API artifact, and builds both Java agents:

```sh
./gradlew --no-daemon clean test check javadoc :agent:jar :host-agent:jar
python3 scripts/summarize-tests.py
python3 scripts/package-release.py
```

The release package contains:

- `PlexonPanel-3.2.0.jar`
- `plexonpanel-host-3.2.0.jar`
- `PlexonPanel-3.2.0-examples.zip`
- `release-manifest.json`
- `SHA256SUMS.txt`
- `test-summary.txt`

The contract verifies Java 25/class major 69, protocol 3, Paper metadata, required API/Core bridge classes, matched versioning, non-shading of PlexonCore, the pinned Dashboard/Relay provenance reference, and checksums.

## Upgrade

1. Stop Minecraft and the Host companion if installed.
2. Back up `plugins/PlexonPanel/` and Host identity/configuration.
3. Replace both Java artifacts with the matching 3.2.0 release pair.
4. Do not delete identity/access files and do not re-pair as an upgrade workaround.
5. Start the Host companion and Paper server.
6. Verify `/plexon modules`, `/plexonpanel status`, `/plexonpanel capabilities`, and `/plexonpanel diagnostics`; confirm UUID/fingerprint and browser credential continuity.

`/plexon reload` remains transport-neutral. `/plexonpanel reload` remains the Panel-owned runtime reload path.

## Stable release model

Repository closure is source/release focused: audited source, green CI, merged `main`, stable tag, matched downloadable JAR pair and checksums. Live PlexonCraft deployment/smoke testing is a separate operational follow-up and is not represented as completed by the GitHub stable release itself.
