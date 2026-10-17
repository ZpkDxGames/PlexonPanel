# v5 Host session persistence and backup schedules

The owner directive supersedes the historical manual-only policy. Protocol 3 and Host configuration schema 5 remain unchanged; maintenance-settings schemaVersion remains 1.

The Host writes owner-only `telemetry/session-telemetry.sqlite` (WAL/NORMAL) and atomic `telemetry/player-events-current.json` under its existing dataDirectory. The new systemd InvocationID clears previous-boot console, presence, chat and lifecycle tables. A Host restart during the same invocation preserves rows. Observed inactive/failed state finalizes open presence; unknown status does not prove shutdown. JSON is rebuilt from SQLite on startup. Optional local `session-storage.json` sets consoleRows (default50000) and eventRows (default10000).

`console.history` uses epoch-bound SQLite cursors, severity and substring filters. `events.session.list` requires players.view; `chat.history` requires chat.view. Compatibility reads use server.status includeSession/includeChat with the additional grant and capability checks. The relay implementation is not supplied; its action/event routing needs installation certification. Only trusted Paper logger prefixes produce structured presence/chat records.

New settings: `schedule.backups` with enabled(defaultfalse), cadence(DAILY/WEEKLY/SELECTED_DAYS/INTERVAL_HOURS), weekdays, time, intervalHours1..168, countdownMinutes30/15/10/5, skipIfEmptySinceLastBackup and forceAfterHours. Calendar schedules use timezone; intervals align to epoch. Durable occurrence claims and the existing worker/node lock are shared with manual backups. Unknown activity/verified-Drive evidence runs the backup. The existing preflight→countdown→save-flush→stop-proof→partial ZIP→SHA256→isolated rclone remote verify/promotion→restart pipeline remains authoritative. A manual zero countdown requires RCON proof of zero players at queue and final-save time. Local retention is REMOTE_ONLY_AFTER_VERIFY or KEEP_LOCAL_ROTATING. Schedules remain disabled until a reviewed update enables them.

Java25 requires `--enable-native-access=ALL-UNNAMED` for SQLite JNI (included in the fleet Host service example). No live VPS, credential, relay or deployment change is performed by this source package.

Incomplete journal streams also obey the event row cap. If an open join is evicted, presenceComplete becomes false and onlineCount becomes unknown rather than claiming a complete roster; the flag survives a Host restart and resets on the next Minecraft boot. Chat message IDs are deduplicated across journal replay. Read-only SQL/history queries use the independent Host status worker so they remain responsive during an upload.

The Host release now includes exactly four approved Xerial SQLite shared libraries: Linux/glibc and Linux-Musl for x86_64 and aarch64. The packaging verifier and CI artifact metadata enforce this explicit multi-platform set and reject other native payloads. This workspace executed JNI on x64; ARM64/musl runtime gates remain pending. The old native-free artifact description is superseded.

Maintenance settings accept both maintenance.settings.update and maintenance.configure under the same maintenance.configure scope and high-risk confirmation. The dashboard retains settings.update for existing relay compatibility.
