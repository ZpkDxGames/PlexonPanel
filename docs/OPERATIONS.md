# Configuration and operations

[config.yml](../agent/src/main/resources/config.yml) is the authoritative conservative Paper example. Reload local policy with `/plexonpanel reload`; restart for JAR upgrades. Never use generic Bukkit hot reload.

For the intended PlexonCraft full-capability deployment, use the separate [Paper full-control](../agent/examples/config-full-control.yml) and [Host full-control](../host-agent/examples/host-config-full-control.json) examples and review [FULL_CONTROL.md](FULL_CONTROL.md). Enabling these examples does not remove device scopes, confirmations, audit, console allow/deny rules, file confinement, backup gating or Paper/Host authority boundaries. After enabling new local capabilities, re-pair only devices that legitimately need missing immutable scopes.

| Setting                                                                     | Effect                                                                               |
| --------------------------------------------------------------------------- | ------------------------------------------------------------------------------------ |
| `gateway.enabled/url/public-key`                                            | Pinned outbound WSS; empty keys cannot authenticate.                                 |
| `telemetry.enabled`                                                         | Overview, independently scheduled metrics/inventories, and live player deltas.       |
| `telemetry.*-interval-*`                                                    | Server/player/system/world/plugin cadences; all are explicitly bounded.              |
| `telemetry.send-player-presence-events`                                     | Immediate current-roster deltas under `players.view`; no persistent history needed. |
| `player-history.*`                                                          | Opt-in Paper-local presence retention, query limits, page bounds, and flush timeout. |
| `telemetry.include-player-location/address`                                 | Separate sensitive-field opt-ins, also requiring scopes.                             |
| `console.errors-enabled/stream-enabled`                                     | WARN/ERROR versus full console visibility.                                           |
| `chat.stream-enabled/allow-dashboard-send/allow-minimessage-from-dashboard` | Independent stream/send/format permissions.                                          |
| `remote-actions.enabled`                                                    | Master Paper mutation switch, including file writes and chat sends.                  |
| `remote-actions.console.enabled/mode/allow/deny`                            | DISABLED, ALLOWLIST or ALLOWLIST_WITH_CONFIRMATION. Deny wins; no unrestricted mode. |
| `remote-actions.players.<action>`                                           | Independent typed actions; op/deop also require Owner.                               |
| `remote-actions.plugin-reload-commands`                                     | Exact plugin → fixed local command, checked by console policy before it is advertised. |
| `files.enabled/roots/permissions`                                           | Named roots, writable-root opt-in and independent action switches.                   |
| `access.*`                                                                  | Local pairing role, 1–30-day grants, custom scopes, optional remote revoke.          |
| `host.public-key`                                                           | Independent host key trusted by Paper.                                               |
| `backups.enabled/allow-host-schedule`                                       | Save leases and separate unattended-backup authorization.                            |
| `audit.retention-days`                                                      | 1–365 days; security auditing is mandatory despite the old enabled switch.           |

## Full-control deployment checks

Before production, confirm Paper reports its supported scopes only, Host reports its supported scopes only, and a newly paired Owner receives the current canonical scope set. Host must still reject `players.*`, `player.*`, `console.*`, `chat.*` and `plugins.*`; Paper must still leave `server.start`, `server.stop` and `server.restart` disabled. A blue/non-applicable matrix cell is correct when that agent has no handler.

The Host runtime user needs explicit permission for the configured systemd unit. Full Host file capability stays under `serverRoot`; do not broaden it to `/`. Backups remain effective only when `backups.enabled` is true, and restore additionally requires `restoreEnabled`. Test stop/restart and restore only on disposable data or a planned maintenance target.

## Player presence lifecycle

Paper is authoritative. A join observed while the plugin is active opens one generated session ID and may append `JOINED`; quit or kick closes it once with a known duration. Kick text is never retained. A following quit cannot double-close the session. A rapid reconnect receives a distinct session ID.

Enabling/reloading the plugin while players are already online reconciles them into the current roster without pretending a login was observed. Disable/shutdown flushes pending writes for the configured bounded timeout and does not fabricate logout events for connected players. On the next startup, journal sessions orphaned by a crash or prior shutdown are marked `UNKNOWN_DISCONNECT`; their exact logout and duration remain unknown, while the last definitely observed instant is retained.

History uses UUID identity and plain account names. Names may change. UTC journal files rotate by date and additionally by configured byte limit; the summary is atomically replaced. Retention cleanup, per-file size, scan files/bytes/time, events, summaries, result pages, record size, and queue depth are bounded. A truncated final JSONL line is ignored without discarding earlier valid records. Any rejected/failed write changes diagnostics to degraded and is never reported as saved.

`players.history.list` runs on the dedicated storage worker. Search accepts a bounded name or UUID fragment, `ALL`/`ONLINE`/`OFFLINE`, an optional UTC range, opaque cursor, and at most 100 entries. Results are newest first. `boundedWindow: true` means retention or a safety budget prevented an exhaustive older scan.

## File boundaries

Roots must exist and resolve to approved absolute directories. A mutation needs scope, agent capability, a writable root and OS permission. Paths reject absolute browser inputs, `..`, encoded traversal, backslashes, symlink components and broad OS roots. Identity/panel data, audit/logs, dotfiles, rclone credentials, private keys and sensitive configurations are protected.

Listings use 100 entries/page and a 10,000-entry scan bound. Allowed UTF-8 text reads/edits/uploads are at most 24 KiB; JSON is validated, other text syntax needs operator review. SQL is read-only. Binary/executable writes are denied. Downloads are at most 8 MiB for general files and 64 MiB for backups, in 16 KiB ordered chunks with SHA-256 verification, cancellation and expiry.

Existing saves compare the original SHA-256, flush a temporary file and atomically replace. Stale saves return CONFLICT and retain the browser draft. New creation reserves a destination to prevent clobbering; a crash can leave an empty reservation. Rename copies then verifies/deletes the original; a crash can leave both names. Reconcile these locally before retrying. Unsupported secure filesystem operations fail closed.

## Bounded metrics and work

Host CPU uses successive `/proc/stat` deltas excluding guest double-counting. Paper process CPU is separate; zero is valid and missing stays unknown. Host available/used/total memory, JVM heap/non-heap/direct buffers/RSS/swap, GC and load averages are distinct. Directory scans run off-thread once per 180 seconds with a two-second/50,000-entry budget; incomplete scans are unavailable.

Bukkit state capture stays on the server thread; network, journal, file, process, compression and inventory serialization use bounded workers. Server health defaults to 40 ticks, current-player reconciliation to 30 seconds, system metrics to 5 seconds, worlds to 10 seconds, and plugins to 60 seconds. Immediate presence transport uses a separate bounded event worker so periodic telemetry cannot starve it. The shared gateway admits critical traffic ahead of events and events ahead of discardable telemetry.

Inventories cap at 512 players, 256 plugins and 64 worlds; batches mark truncation and share one capture instant. Concurrent player snapshots coalesce, and manual refresh is independently limited to once per device per five seconds. A new authenticated transport session invalidates old queued captures and requests a complete reconciliation snapshot. Console lines are bounded/redacted and lower-priority telemetry may be dropped during saturation. Browser performance history is 361 five-second samples (30 minutes), live console 600 lines and chat 200; the sanitized one-hour cache excludes players and presence history.

Disconnect rejects pending actions with an unknown outcome and never resends them. Check audit/server state before a deliberate retry. If execution completes but result auditing fails, the response explicitly requires local verification.

## Troubleshooting

| Symptom                    | Check                                                                                     |
| -------------------------- | ----------------------------------------------------------------------------------------- |
| Protocol mismatch          | Upgrade all components to v3 and re-pair browsers.                                        |
| Relay offline              | Outbound DNS/TLS, clock, WSS route and relay public key.                                  |
| Agent offline              | Paper and host are separate processes; host presence does not mean Paper runs.            |
| Expired/revoked credential | Generate a fresh role-bound code locally.                                                 |
| Capability unavailable     | Local policy, current scopes and correct source agent.                                    |
| Local enabled / device denied | Revoke and re-pair that intended device; immutable grants are never edited in place.   |
| History tab unavailable    | Distinguish an old grant, disabled `player-history.enabled`, and a pre-3.0 Paper agent.    |
| Presence degraded          | `/plexonpanel diagnostics`; inspect directory safety/permissions, storage, and queue load. |
| Roster reconciling         | Wait for the complete Paper snapshot; repeated manual refresh is limited to five seconds. |
| Host rejected              | Paper-side host pin, shared UUID, exact unit and registry permissions.                    |
| File conflict              | Preserve draft, inspect actual file and save against a fresh hash.                        |
| Save lease refused         | Paper backup setting, host trust and automatic-job opt-in.                                |
| Recovery required          | Keep Paper stopped and follow local host recovery; never delete the journal to bypass it. |

Use sanitized diagnostics for support. Keep raw logs, player data and credentials out of public issues.
