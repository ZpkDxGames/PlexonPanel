# PlexonPanel

PlexonPanel is a security-first Paper server agent for a future hosted administration dashboard. The plugin collects server health and inventory data, streams explicitly enabled logs and chat, and executes narrowly authorized administrative actions over a signed WebSocket protocol.

This repository is milestone 1: the complete Paper plugin, protocol contract, tests, and local gateway harness. The hosted gateway and Next.js dashboard are the next milestone and are intentionally not faked inside the plugin.

## Support

| Component | Supported version |
| --- | --- |
| Paper | 26.2 (`26.2.build.112-stable`) |
| Minecraft | Version bundled with Paper 26.2 |
| Java | 25 |
| Gradle | 9.7.0 wrapper |
| PlexonChats | Optional global-channel API contract included in this workspace |

## Features

| Capability | Data or action | Default |
| --- | --- | --- |
| Resource monitor | Process/system CPU, JVM/physical RAM, disk, uptime | On |
| Paper health | TPS (1/5/15 minute), average/p95/max tick time, player counts | On |
| Players | UUID, name, display name, world, mode, ping, health, level, op/whitelist | On |
| Player location/IP | Optional inventory fields | Off |
| Plugin checker | Name, version, main class, authors, website, enabled state | On |
| Error detection | Redacted WARN/ERROR log lines with stable fingerprints | On |
| Full console stream | Batched tail of `logs/latest.log` | Off |
| Chat stream | Paper global and optional PlexonChats global messages | Off |
| Remote console | Regex allowlist plus denylist, captured/redacted output | Off |
| Player/chat actions | Message, kick, ban, unban, whitelist, global chat send | Off |
| Audit trail | One JSONL file per UTC day | On |

## Security model

- Each installation creates a persistent Ed25519 identity under `plugins/PlexonPanel/identity/`. The private key never leaves the server.
- Every protocol envelope is signed. Privileged gateway messages require a pinned gateway public key.
- Timestamps, unique message IDs, bounded payloads, replay protection, and bounded queues are enforced before actions run.
- Pairing uses a short-lived, one-time code. The code is an authorization step, not the long-term secret.
- Remote features are opt-in. Console commands additionally require an allow rule and must not match any deny rule.
- Bukkit/Paper state is read or changed on the server thread; networking, log tailing, system metrics, and audit writes use dedicated background workers.
- Sensitive-looking log and command-output values are redacted before transmission. Review and extend the configured patterns for your environment.

See [SECURITY.md](SECURITY.md), [PRIVACY.md](PRIVACY.md), and [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) before exposing a gateway publicly.

## Install

1. Install Paper 26.2 and run it with Java 25.
2. Copy `PlexonPanel-0.1.0.jar` into the server's `plugins/` directory.
3. Start the server once, then review `plugins/PlexonPanel/config.yml`.
4. Configure the hosted gateway URL and its Ed25519 public key when that service is available.
5. Enable the gateway and only the telemetry/actions you intend to expose.
6. Restart or run `/plexonpanel reload`, then pair with `/plexonpanel pair`.

The placeholder production URL is disabled by default. For local development, use the included [mock gateway](mock-gateway/README.md).

## Commands

| Command | Permission | Purpose |
| --- | --- | --- |
| `/plexonpanel status` | `plexonpanel.status` | Connection, identity, and feature state |
| `/plexonpanel pair` | `plexonpanel.pair` | Request a short-lived dashboard pairing code |
| `/plexonpanel unpair` | `plexonpanel.unpair` | Revoke/clear local pairing state |
| `/plexonpanel rotate confirm` | `plexonpanel.rotate` | Replace the server identity and break pairing |
| `/plexonpanel reload` | `plexonpanel.reload` | Validate config and restart plugin services |
| `/plexonpanel diagnostics` | `plexonpanel.diagnostics` | Safe connection and queue diagnostics |

All permissions default to server operators. `plexonpanel.admin` grants every child permission.

## Build

```bash
./gradlew clean test :agent:jar
```

The installable artifact is written to `agent/build/libs/PlexonPanel-0.1.0.jar`. Build the GitHub-ready source-and-binary archive with:

```bash
./gradlew releaseBundle
```

## Workspace layout

- `agent/` — Paper plugin and unit tests.
- `integrations/plexonchats-api/` — small public contract for clean PlexonChats interop.
- `protocol/` — language-neutral wire schema.
- `mock-gateway/` — loopback WebSocket pairing/action harness with no npm dependencies.
- `docs/` — architecture, protocol, integration, and release notes.

## Roadmap

- [x] Paper 26.2 agent, secure identity, telemetry, inventories, log/chat adapters, remote actions, auditing.
- [x] Signed protocol contract and local gateway harness.
- [ ] Hosted gateway, durable event store, tenant isolation, rate limits, and key rotation flow.
- [ ] Next.js dashboard with account authentication, server pairing, RBAC, and live views.
- [ ] Paper test-server matrix and public beta hardening.
- [ ] SpigotMC and Modrinth publishing after the hosted control plane and beta review are complete.

## License

[MIT](LICENSE) © 2026 ZpkDxGames.
