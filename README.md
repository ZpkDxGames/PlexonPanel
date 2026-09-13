# PlexonPanel 3.4.0

PlexonPanel is an outbound-only control room agent for Paper 26.2 on Java 25 with an optional non-root Linux Host Companion. The 3.4.0 line preserves signed **Protocol 3**, `/v1` routes, existing identities/device grants, and the Paper/Host authority split while adding a Host-authoritative Linux console source and matched relay/Dashboard support.

## Matched Java pair

Install the Paper and Host artifacts from the same release:

- `PlexonPanel-3.4.0.jar`
- `plexonpanel-host-3.4.0.jar`

Do not mix Java bundle versions when replacing the production pair. Preserve the existing `plugins/PlexonPanel/` identity/access state and Host identity/configuration during upgrades.

## Console authority in 3.4.0

When the optional Host Companion is installed and its console policy is enabled, it can stream the configured `plexoncraft.service` journald output through the existing signed Protocol 3 relay.

- Host journald is preferred only while the Host is authenticated and its console source reports healthy.
- Paper keeps the existing `latest.log` tail as an automatic fallback and resumes publication when Host authority disappears.
- Paper remains the **only** `console.execute` authority. Host console access is read-only and `console.execute.allowed` is forced false on the Host.
- Console visibility still requires the device scope and local capability to both allow it.
- Existing Host configs remain valid: if the new `console` object is omitted, Host console starts disabled.

The Host source uses the fixed `/usr/bin/journalctl` executable, bounded replay/history/queues/batches, cursor/invocation persistence, restart backoff, shared severity classification, and pre-transport redaction. See [3.4.0 release notes](RELEASE_NOTES_3.4.0.md).

## Protocol and security

Wire protocol remains **3**. Paper and Host use signed `/v1` transport and preserve the existing Ed25519 identities, device generations/revisions, replay/sequence protections, bounded queues, local policy intersection, high-risk confirmation, and audit rules.

Paper remains authoritative for Paper/JVM telemetry, players, chat, Paper actions, command execution, pairing, and device state. Host remains authoritative for Linux telemetry, systemd lifecycle, Host files/backups, Host-local policy, and—when healthy and enabled—the Linux journald console stream. The relay verifies/routes signed protocol traffic; the Dashboard is the browser presentation/control surface.

The 3.4.0 console extension adds only optional Protocol 3 message/body metadata; it does not require a Protocol 4 migration, identity reset, or automatic re-pair.

See [protocol 3](docs/PROTOCOL.md), [access/scopes](docs/ACCESS.md), [privacy](PRIVACY.md), and [operations](docs/OPERATIONS.md).

## PlexonCore mode

The Paper plugin registers module ID `panel` when a compatible PlexonCore API is available.

- Supported Core API: `>=1.0 <3.0`
- CI compile boundary: PlexonCore `2.0.4`
- Core remains a Paper soft dependency and is not shaded into the Panel JAR.
- Without a compatible Core service, Panel continues in `STANDALONE` mode.

PlexonCore owns only local module registration/diagnostics for this integration. It does not gain authority over Panel pairing, immutable device grants, transport, remote actions, files, backups, console authority, or identity.

See [PlexonCore integration](docs/PLEXONCORE.md) and [local API](docs/API.md).

## Paper performance model

Paper captures Bukkit-owned state on the primary thread and uses bounded workers for serialization/storage/transport. In 3.4.0, listener-driven plugin/world/roster invalidations are debounced and coalesced rather than scheduling an immediate full snapshot for every event in a burst. Existing bounded queues, in-flight snapshot coalescing, slower reconciliation timers, and holder-owned GUI routing remain.

## Host configuration

The conservative example keeps Host console disabled:

- `host-agent/examples/host-config.json`

The full-control PlexonCraft example enables Host console viewing and includes bounded journald settings:

- `host-agent/examples/host-config-full-control.json`

Host console configuration supports source/executable selection constrained to the supported journald path, initial replay size, recent-history size, queue capacity, batch size/interval, maximum line size, cursor persistence cadence, and optional extra redaction patterns.

## Build and package

CI runs the repository suite on Ubuntu 24.04 x64 and ARM64 with Java 25, provisions the pinned PlexonCore 2.0.4 API artifact, and builds both Java agents:

```sh
./gradlew --no-daemon clean test check javadoc :agent:jar :host-agent:jar
python3 scripts/summarize-tests.py
python3 scripts/package-release.py
```

The release package contains:

- `PlexonPanel-3.4.0.jar`
- `plexonpanel-host-3.4.0.jar`
- `PlexonPanel-3.4.0-examples.zip`
- `release-manifest.json`
- `SHA256SUMS.txt`
- `test-summary.txt`

The contract verifies Java 25/class major 69, Protocol 3, Paper metadata, required API/Core bridge classes, matched versioning, non-shading of PlexonCore, the pinned Dashboard/Relay provenance reference, and checksums.

## Upgrade

1. Stop Minecraft and the Host Companion if installed.
2. Back up `plugins/PlexonPanel/` and Host identity/configuration.
3. Replace both Java artifacts with the matching 3.4.0 release pair.
4. Do not delete identity/access files and do not re-pair as an upgrade workaround.
5. If Host console viewing is desired, add the `console` object and console view capabilities from the matching example; otherwise omitted console configuration remains disabled.
6. Start the Host Companion and Paper server.
7. Verify `/plexon modules`, `/plexonpanel status`, `/plexonpanel capabilities`, and `/plexonpanel diagnostics`; confirm UUID/fingerprint and browser credential continuity.

`/plexon reload` remains transport-neutral. `/plexonpanel reload` remains the Panel-owned runtime reload path.

## Release and runtime certification

Repository release closure is source/CI focused: audited source, green matched CI, merged `main`, stable tag, matched downloadable JAR pair and checksums, plus an exact Dashboard/relay provenance record.

Live PlexonCraft deployment is a separate operational acceptance gate. Until the final Paper JAR, Host JAR, relay and Dashboard are actually deployed and exercised on the authorized Linux host—including restart/failure/cursor recovery scenarios—the release manifest must continue to report `runtimeCertification=NOT_EXECUTED` rather than treating CI as runtime evidence.
