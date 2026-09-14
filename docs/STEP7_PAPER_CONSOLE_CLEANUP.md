# Step 7 — Paper console cleanup and migration

PlexonPanel treats the Linux Host Companion as the sole authoritative server-console source. Paper still owns safe remote command execution, but it no longer captures, buffers, replays, or advertises read authority for server-console output.

## Retired Paper capture settings

The Paper agent no longer reads or acts on these legacy configuration keys:

- `console.stream-enabled`
- `console.errors-enabled`
- `console.poll-interval-millis`
- `console.batch-interval-millis`
- `console.batch-size`
- `console.ring-buffer-lines`
- `console.maximum-line-bytes`

Existing installations may leave those keys in `plugins/PlexonPanel/config.yml` during migration. They are intentionally ignored and cannot restore Paper console authority.

The only retained Paper `console` setting is `console.redact-patterns`. Paper uses those patterns to sanitize results from Paper-owned `console.execute` before returning command output. This is not console capture/history.

## Capability split

Paper always advertises these read capabilities as unavailable:

- `console.view.errors = false`
- `console.view.full = false`

Paper may still advertise `console.execute.allowed` when remote actions, the local command policy, and the Paper command path are enabled.

Device grants can still include console-view scopes. Effective Host console access is resolved against the authenticated Host Companion and its locally configured capabilities; Paper does not need to claim the same read capability.

## Host authority

The Host Companion remains authoritative for:

- live journald capture;
- recent replay;
- historical journal queries;
- service invocation boundaries;
- source health;
- persisted journal cursor;
- console classification and redaction for Host-owned output.

Historical availability remains bounded by the VPS's `systemd-journald` retention. The browser cannot select another unit or supply arbitrary `journalctl` flags.

## Unchanged surfaces

This cleanup does not remove or narrow the Paper chat/event stream. It also does not move Minecraft command execution to Host; normal dashboard `console.execute` remains Paper-owned and unavailable while Paper is offline.

## Runtime acceptance

Repository tests prove that legacy Paper capture keys are ignored and cannot re-enable Paper console-view capabilities. Production acceptance still requires the Step 7 live checks: stop Paper while Host remains online, browse retained journald history, restart Paper and observe continuing Host output, restart Host and verify bounded cursor/replay behavior, and verify history remains unit-restricted.
