# Changelog

All notable changes to PlexonPanel are documented here. The project follows [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Changed

- Simplified the public repository documentation and release layout.
- Removed the unused default gateway address; new installations now start with a blank endpoint.
- Moved contributor and security guidance into GitHub's standard `.github` location.

### Planned

- Hosted gateway and Next.js dashboard.
- Public Paper test-server beta.

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

[Unreleased]: https://github.com/ZpkDxGames/PlexonPanel/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/ZpkDxGames/PlexonPanel/releases/tag/v0.1.0
