# Configuration and operations

[config.yml](../agent/src/main/resources/config.yml) is the authoritative Paper example. Reload local policy with `/plexonpanel reload`; restart for JAR upgrades. Never use generic Bukkit hot reload.

| Setting                                                                     | Effect                                                                               |
| --------------------------------------------------------------------------- | ------------------------------------------------------------------------------------ |
| `gateway.enabled/url/public-key`                                            | Pinned outbound WSS; empty keys cannot authenticate.                                 |
| `telemetry.enabled`                                                         | Overview, metrics and inventories.                                                   |
| `telemetry.include-player-location/address`                                 | Separate sensitive-field opt-ins, also requiring scopes.                             |
| `console.errors-enabled/stream-enabled`                                     | WARN/ERROR versus full console visibility.                                           |
| `chat.stream-enabled/allow-dashboard-send/allow-minimessage-from-dashboard` | Independent stream/send/format permissions.                                          |
| `remote-actions.enabled`                                                    | Master Paper mutation switch, including file writes and chat sends.                  |
| `remote-actions.console.enabled/mode/allow/deny`                            | DISABLED, ALLOWLIST or ALLOWLIST_WITH_CONFIRMATION. Deny wins; no unrestricted mode. |
| `remote-actions.players.<action>`                                           | Independent typed actions; op/deop also require Owner.                               |
| `remote-actions.plugin-reload-commands`                                     | Exact plugin → fixed local command, checked by console policy.                       |
| `files.enabled/roots/permissions`                                           | Named roots, writable-root opt-in and independent action switches.                   |
| `access.*`                                                                  | Local pairing role, 1–30-day grants, custom scopes, optional remote revoke.          |
| `host.public-key`                                                           | Independent host key trusted by Paper.                                               |
| `backups.enabled/allow-host-schedule`                                       | Save leases and separate unattended-backup authorization.                            |
| `audit.retention-days`                                                      | 1–365 days; security auditing is mandatory despite the old enabled switch.           |

## File boundaries

Roots must exist and resolve to approved absolute directories. A mutation needs scope, agent capability, a writable root and OS permission. Paths reject absolute browser inputs, `..`, encoded traversal, backslashes, symlink components and broad OS roots. Identity/panel data, audit/logs, dotfiles, rclone credentials, private keys and sensitive configurations are protected.

Listings use 100 entries/page and a 10,000-entry scan bound. Allowed UTF-8 text reads/edits/uploads are at most 24 KiB; JSON is validated, other text syntax needs operator review. SQL is read-only. Binary/executable writes are denied. Downloads are at most 8 MiB for general files and 64 MiB for backups, in 16 KiB ordered chunks with SHA-256 verification, cancellation and expiry.

Existing saves compare the original SHA-256, flush a temporary file and atomically replace. Stale saves return CONFLICT and retain the browser draft. New creation reserves a destination to prevent clobbering; a crash can leave an empty reservation. Rename copies then verifies/deletes the original; a crash can leave both names. Reconcile these locally before retrying. Unsupported secure filesystem operations fail closed.

## Bounded metrics and work

Host CPU uses successive `/proc/stat` deltas excluding guest double-counting. Paper process CPU is separate; zero is valid and missing stays unknown. Host available/used/total memory, JVM heap/non-heap/direct buffers/RSS/swap, GC and load averages are distinct. Directory scans run off-thread once per 180 seconds with a two-second/50,000-entry budget; incomplete scans are unavailable.

Bukkit state capture stays on the server thread; network, file, process, compression and large inventory serialization use bounded workers. Inventories cap at 512 players, 256 plugins and 64 worlds; batches mark truncation. Console lines are bounded/redacted and telemetry may be dropped during saturation. Browser history is 361 five-second samples (30 minutes), live console 600 lines and chat 200; a smaller sanitized cache expires after one hour.

Disconnect rejects pending actions with an unknown outcome and never resends them. Check audit/server state before a deliberate retry. If execution completes but result auditing fails, the response explicitly requires local verification.

## Troubleshooting

| Symptom                    | Check                                                                                     |
| -------------------------- | ----------------------------------------------------------------------------------------- |
| Protocol mismatch          | Upgrade all components to v3 and re-pair browsers.                                        |
| Relay offline              | Outbound DNS/TLS, clock, WSS route and relay public key.                                  |
| Agent offline              | Paper and host are separate processes; host presence does not mean Paper runs.            |
| Expired/revoked credential | Generate a fresh role-bound code locally.                                                 |
| Capability unavailable     | Local policy, current scopes and correct source agent.                                    |
| Host rejected              | Paper-side host pin, shared UUID, exact unit and registry permissions.                    |
| File conflict              | Preserve draft, inspect actual file and save against a fresh hash.                        |
| Save lease refused         | Paper backup setting, host trust and automatic-job opt-in.                                |
| Recovery required          | Keep Paper stopped and follow local host recovery; never delete the journal to bypass it. |

Use sanitized diagnostics for support. Keep raw logs, player data and credentials out of public issues.
