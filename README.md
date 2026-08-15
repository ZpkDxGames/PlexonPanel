# PlexonPanel

[![Build](https://github.com/ZpkDxGames/PlexonPanel/actions/workflows/build.yml/badge.svg)](https://github.com/ZpkDxGames/PlexonPanel/actions/workflows/build.yml)
[![Release](https://img.shields.io/github/v/release/ZpkDxGames/PlexonPanel)](https://github.com/ZpkDxGames/PlexonPanel/releases/latest)
[![License](https://img.shields.io/github/license/ZpkDxGames/PlexonPanel)](LICENSE)

PlexonPanel is a Paper plugin for monitoring and administering a Minecraft server from a web dashboard. It reports server health, players, plugins, logs, and chat while keeping remote actions disabled until a server operator enables them.

> **Project status:** the Paper plugin is available as an early preview. The hosted dashboard and public pairing service are still under development.

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

The gateway is disabled by default. Until the hosted service is available, developers can use the loopback-only [local gateway](mock-gateway/README.md).

## Commands

| Command | Purpose |
| --- | --- |
| `/plexonpanel status` | Show connection and feature status |
| `/plexonpanel pair` | Request a temporary pairing code |
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

## Roadmap

- Hosted gateway and account authentication
- Next.js administration dashboard
- Public beta testing on Paper 26.2
- SpigotMC and Modrinth publication after the hosted service is ready

## License

PlexonPanel is released under the [MIT License](LICENSE).

Created by [ZpkDxGames](https://github.com/ZpkDxGames).
