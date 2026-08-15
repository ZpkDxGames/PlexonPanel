# PlexonPanel

[![Build](https://github.com/ZpkDxGames/PlexonPanel/actions/workflows/build.yml/badge.svg)](https://github.com/ZpkDxGames/PlexonPanel/actions/workflows/build.yml)
[![Release](https://img.shields.io/github/v/release/ZpkDxGames/PlexonPanel)](https://github.com/ZpkDxGames/PlexonPanel/releases/latest)
[![License](https://img.shields.io/github/license/ZpkDxGames/PlexonPanel)](LICENSE)

PlexonPanel is a Paper plugin for monitoring and administering a Minecraft server from a web dashboard. It reports server health, players, plugins, logs, and chat while keeping remote actions disabled until a server operator enables them.

> **Project status:** `1.0.0-rc.1` implements the release-candidate protocol used by the private dashboard and Cloud Run gateway. Validate it on a disposable server before production use.

## Features

- CPU, memory, disk, JVM, TPS, tick-time, and player-count monitoring
- Player and installed-plugin information
- Redacted warning and error reporting
- Optional console and global-chat streaming
- Optional remote player, whitelist, ban, chat, and console actions
- Local action audit logs and per-feature privacy controls
- Optional PlexonChats global-channel adapter

## Requirements

| Component | Version |
| --- | --- |
| Paper | 26.2 (`26.2.build.112-stable`) |
| Java | 25 |
| PlexonChats | Optional; integration API support required |

## Installation

1. Download the JAR from the [latest release](https://github.com/ZpkDxGames/PlexonPanel/releases/latest).
2. Place it in the Paper server's `plugins/` directory.
3. Start the server once and review `plugins/PlexonPanel/config.yml`.
4. Enable only the data streams and remote actions you intend to use.

The gateway is disabled by default. Configure its `wss://.../v1/agent` URL and
pin the gateway's Ed25519 public key before enabling it. Developers can use the
loopback-only [local gateway](mock-gateway/README.md).

## Commands

| Command | Purpose |
| --- | --- |
| `/plexonpanel status` | Show connection and feature status |
| `/plexonpanel pair` | Generate and register a one-use five-minute pairing code |
| `/plexonpanel unpair` | Revoke the current pairing |
| `/plexonpanel rotate confirm` | Replace the local server identity |
| `/plexonpanel reload` | Reload and validate the configuration |
| `/plexonpanel diagnostics` | Show safe connection diagnostics |

These commands require their matching `plexonpanel.*` permission and default to server operators.

## Building

```bash
./gradlew clean test :agent:jar
```

The plugin is written to `agent/build/libs/PlexonPanel-<version>.jar`. Additional development information is available in [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md).

## Privacy and security

Console output, chat, player information, and network addresses can contain sensitive data. Review [PRIVACY.md](PRIVACY.md) and the [security policy](.github/SECURITY.md) before connecting a production server.

Generated identities, pairing codes, server logs, and local gateway keys must never be committed to this repository.

## Pairing and reconnect behavior

Each server keeps a persistent UUID and Ed25519 identity under
`plugins/PlexonPanel/identity/`. The plugin generates the PIN locally, sends it
only over its authenticated signed WebSocket, and displays it only after the
gateway confirms registration. The PIN is never persisted by the plugin.

Cloud Run periodically closes long WebSocket requests, so the plugin reconnects
with bounded exponential backoff and repeats the identity challenge. Once a
browser has claimed a PIN, restarting the Minecraft server restores the paired
state automatically without creating a duplicate server.

## Roadmap

- release-candidate end-to-end deployment testing
- public beta testing on Paper 26.2
- SpigotMC and Modrinth publication after the hosted service is validated

## License

PlexonPanel is released under the [MIT License](LICENSE).

Created by [ZpkDxGames](https://github.com/ZpkDxGames).
