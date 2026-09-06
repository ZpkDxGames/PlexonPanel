# PlexonPanel 2.0.0

An outbound-only control room for Paper 26.2 / Java 25, with an optional non-root Linux host companion. **Review candidate: production acceptance remains pending.** See [validation](docs/VALIDATION.md).

The Paper agent provides telemetry, bounded console/chat streams, typed player controls, plugin inventory, restricted file operations and a local inventory GUI. The host companion stays online while Paper stops and adds fixed-unit systemd lifecycle, local/rclone backups and stopped-server restore. Both connect to the protocol 3 relay in the [dashboard repository](https://github.com/ZpkDxGames/PlexonPanel-Dashboard).

Every action requires a valid current device grant, its exact role/scopes and the executing agent's local capability. Owner cannot override a local denial. New installations disable mutations, full console, chat sending, files, host lifecycle and backups. Existing rc.2 settings are preserved and require review during migration.

## Build and install

```sh
./gradlew --no-daemon clean test javadoc :agent:jar :host-agent:jar
python3 scripts/package-release.py
```

Outputs are `agent/build/libs/PlexonPanel-2.0.0.jar`, `host-agent/build/libs/plexonpanel-host-2.0.0.jar` and the JARs, examples, source manifest and `SHA256SUMS.txt` in `build/release/`. CI uploads review artifacts from Ubuntu x64 and ARM64 builds; these do not replace live acceptance.

1. Use a disposable Paper 26.2 server on Java 25. Stop it, install the Paper JAR in `plugins/`, then start once to create configuration and identity.
2. Configure the relay WSS `/v1/agent` endpoint and public key in `plugins/PlexonPanel/config.yml`; enable the gateway and restart. Keep mutations off.
3. Verify `/plexonpanel status` and the existing server fingerprint. Run `/plexonpanel pair Observer` locally and claim the one-use five-minute code in the dashboard.
4. Verify telemetry, Observer denials and local revocation before enabling individual capabilities. Install the optional host only through the guide below.

`/plexonpanel` opens the player GUI; console use shows status. Commands include `gui`, `status`, `pair [role]`, `devices`, `revoke <device-id>`, `revoke-all`, `capabilities`, `audit`, `reload`, `diagnostics`, plus retained local identity controls. Permissions default to operators. Four immutable built-in roles and optional locally configured custom scopes are supported.

## Guides

- [Configuration, files and troubleshooting](docs/OPERATIONS.md)
- [Roles and scopes](docs/ACCESS.md)
- [Host, backups and restore](docs/HOST_AGENT.md)
- [Protocol 3](docs/PROTOCOL.md)
- [Migration and rollback](docs/MIGRATION.md)
- [Validation/release gates](docs/VALIDATION.md)
- [Development](docs/DEVELOPMENT.md), [security](.github/SECURITY.md), [privacy](PRIVACY.md), [changes](CHANGELOG.md)

No Firebase, telemetry database, inbound Minecraft administration port, generic shell or RCON is required. Cloudflare stores authorization/pairing coordination only. Historical charts are bounded browser state; audit stays local.
