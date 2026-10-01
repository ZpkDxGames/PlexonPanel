# Configuration reference

The installable Paper default is [config.yml](../agent/src/main/resources/config.yml); the Host example is [host-config.json](../host-agent/examples/host-config.json). Local files are authoritative and no browser role—including Owner—can override them. PlexonPanel 4.0 uses Paper `schema-version: 4` and Host `schemaVersion: 4`. Paper migrates at startup; the root-owned Host file uses the documented local `--migrate-config` operation while the service is stopped. A legacy file is backed up before atomic replacement, unknown operator keys are preserved, Host ownership/mode are restored, and a future schema is rejected without rewriting it. Identity, pairing and grants live outside these files and are never rotated by configuration migration.

## 4.0.0 full-control examples

PlexonCraft deployments that intentionally want every locally implemented capability can start from [Paper full-control](../agent/examples/config-full-control.yml) and [Host full-control](../host-agent/examples/host-config-full-control.json). These examples are deliberately separate from the conservative public defaults. They preserve protocol 3, immutable grants, command allow/deny policy, confirmations, audit, SafeFiles confinement, backup gating and the Paper/Host authority split. See [full local capability deployment](FULL_CONTROL.md) before applying either example.

Paper's preset enables history, player location/address, full console/chat paths, all implemented player actions, file operations, backup coordination and device revoke. Host's preset enables its lifecycle, file, backup, audit/device/settings capabilities. Host still rejects Paper-only scopes and Paper still does not advertise Host lifecycle authority.

`plugins.reload` is effective only when at least one explicitly configured reload command also passes the console allow/deny policy. The supplied preset exposes `plexonpanel reload` for PlexonPanel itself; it never exposes generic Bukkit/Paper `/reload`.

## Realtime telemetry

| Key | Default | Accepted range / meaning |
| --- | ---: | --- |
| `telemetry.server-interval-ticks` | `40` | 20–72,000 ticks; Paper health capture, two seconds by default. |
| `telemetry.player-snapshot-interval-seconds` | `30` | 5–3,600; full reconciliation snapshot cadence. |
| `telemetry.system-interval-seconds` | `5` | 1–3,600; JVM/system cadence. |
| `telemetry.world-interval-seconds` | `10` | 1–3,600; world inventory cadence. |
| `telemetry.plugin-interval-seconds` | `60` | 10–86,400; plugin inventory cadence. |
| `telemetry.send-player-presence-events` | `true` | Immediate join/leave roster deltas while telemetry is enabled. |
| `telemetry.suppress-unchanged-inventories` | `true` | Suppresses unchanged plugin/world snapshots; full player reconciliation remains periodic/requestable. |

Bukkit/Paper values are captured on the primary server thread, then plain immutable data is serialized and sent by bounded workers. Increase intervals if representative load shows a meaningful MSPT regression. A reconnect invalidates queued captures from the old signed session and produces one fresh player snapshot.

## Player history

Persistent presence is new personal-data collection and is disabled by default.

| Key | Default | Accepted range / meaning |
| --- | ---: | --- |
| `player-history.enabled` | `false` | Enables the Paper-local journal and `players.history.view` capability. It cannot be enabled remotely. |
| `player-history.retention-days` | `30` | 1–3,650 days. |
| `player-history.maximum-events` | `100000` | 100–1,000,000 retained/indexed observations. |
| `player-history.maximum-player-summaries` | `50000` | 100–1,000,000 UUID summaries. |
| `player-history.maximum-journal-file-bytes` | `8388608` | 65,536–67,108,864 bytes before size rotation. |
| `player-history.query-byte-budget` | `33554432` | 1,048,576–268,435,456 bytes scanned per bounded query. |
| `player-history.default-page-size` | `50` | 1 through the configured maximum. |
| `player-history.maximum-page-size` | `100` | 1–100. |
| `player-history.shutdown-flush-seconds` | `5` | 1–30 seconds for bounded writer shutdown. |

Invalid values fail startup/reload with the exact configuration key. When history is off, no presence directory/journal is created, the capability reports false, and `players.history.list` is denied; current roster and live deltas can still operate under `players.view`.

The journal lives below `plugins/PlexonPanel/presence/`, rejects symlinked storage, rotates by UTC day and configured size, and uses restrictive permissions where supported. Paper's `PLAY_ONE_MINUTE` statistic is sampled on the main thread and converted from its historical tick unit to milliseconds (`value × 50`); despite the Bukkit name, it represents ticks played.

See [operations](OPERATIONS.md) for the remaining policy map, file boundaries, runtime diagnostics, and troubleshooting.

## Paper console fallback

Host journald is preferred and remains the only retained-history authority. Paper fallback is
disabled by default and, when enabled, publishes only new `latest.log` lines while the relay reports
Host authority unavailable.

| Key | Default | Accepted range / meaning |
| --- | ---: | --- |
| `console.fallback-enabled` | `false` | Locally enables live-only Paper fallback; never enables retained history. |
| `console.stream-enabled` | `false` | Includes all classified lines while fallback is active. |
| `console.errors-enabled` | `true` | Includes warning/error lines while fallback is active. |
| `console.poll-interval-millis` | `250` | 100–5,000 ms. |
| `console.batch-interval-millis` | `300` | 100–5,000 ms. |
| `console.batch-size` | `100` | 1–1,000 lines per local drain; protocol batches remain capped at 100. |
| `console.ring-buffer-lines` | `1000` | 100–100,000 bounded recent/pending lines. |
| `console.maximum-line-bytes` | `16384` | 512–1,048,576 bytes; longer lines are truncated. |
| `console.redact-patterns` | see example | Additional bounded regular expressions applied before transport. |

The tailer starts at EOF, advances without publishing while Host is authoritative, and does not
write a Paper console-history database. Both the immutable device scope and Paper capability must
permit the selected console view level.
