# Changelog

All notable changes to PlexonPanel are documented here. The project follows
[Semantic Versioning](https://semver.org/).

## [Unreleased]

## [1.0.0-rc.2] - 2026-08-15

### Added

- Database-less Cloudflare relay compatibility with the server UUID in the
  agent WebSocket URL.
- Relay-requested reconnect snapshots for server, system, player, plugin, and
  up to 100 current-process redacted console lines.
- Bounded completed-action cache so duplicate request IDs return the prior
  result instead of executing twice.

### Changed

- Server telemetry defaults to five seconds and system telemetry to ten seconds
  to keep free-tier message volume modest.
- Relay URL validation rejects embedded credentials, queries, and fragments.
- An enabled connection now requires signed messages and a pinned relay public
  key.
- Documentation now makes Paper/local browser ownership and no hosted telemetry
  retention explicit.

### Security

- Reconnect snapshots remain bounded and are sent only after the signed nonce
  challenge succeeds.
- Scheduling failures are cached, returned once per request ID, and included in
  the Paper-local audit.

## [1.0.0-rc.1] - 2026-08-15

### Added

- Protocol v2 capability advertisement and authenticated gateway acknowledgement.
- Plugin-generated, gateway-registered six-digit pairing PINs with five-minute expiry.
- Pairing request correlation, collision rejection, and no plaintext PIN persistence.
- Release-candidate tests for secure PIN generation and registration state.

### Changed

- Gateway connection is not considered operational until the signed Ed25519 challenge completes.
- Initial telemetry waits for gateway authentication.
- Local development gateway and envelope schema implement protocol v2.

## [0.1.0] - 2026-08-14

### Added

- Paper 26.2/Java 25 plugin workspace.
- Persistent Ed25519 device identity and one-time pairing flow.
- Signed, replay-protected WebSocket protocol.
- CPU, RAM, disk, JVM, TPS, tick-time, player, and plugin telemetry.
- Bounded/redacted console and error streaming.
- Paper chat capture plus optional PlexonChats global-channel contract.
- Opt-in console, player, whitelist, ban, and dashboard-chat actions.
- Local JSONL action auditing and retention.
- Admin commands, diagnostics, unit tests, CI, protocol schema, and local test gateway.

[Unreleased]: https://github.com/ZpkDxGames/PlexonPanel/compare/v1.0.0-rc.2...HEAD
[1.0.0-rc.2]: https://github.com/ZpkDxGames/PlexonPanel/compare/v1.0.0-rc.1...v1.0.0-rc.2
[1.0.0-rc.1]: https://github.com/ZpkDxGames/PlexonPanel/compare/v0.1.0...v1.0.0-rc.1
[0.1.0]: https://github.com/ZpkDxGames/PlexonPanel/releases/tag/v0.1.0
