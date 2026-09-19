# Roles and scopes

The local operator chooses a role when issuing a five-minute one-use code. Built-in roles are immutable. Effective permission intersects current grant with local capability at both relay and agent. Owner never overrides policy. Custom roles use access.roles.<Name>.scopes; unknown scopes fail startup. Existing grant scopes are immutable: revoke and re-pair to change them. Op/deop require literal Owner even for custom scope sets. The `backup.restore` grant remains in Protocol 3 compatibility metadata, but the stable 3.5.1 Host forces that capability off.

## 3.5.1 local authority

The full-control presets preserve separate authority without pretending both agents have the same handlers. Paper owns players, chat, command execution, player actions and plugins. Host owns server-console viewing, `server.start`, `server.stop`, `server.restart`, manual full-backup creation/verification/Google Drive retry, and restart scheduling. Paper exposes `server.status`; legacy `backup.create` coordination metadata remains Protocol 3-compatible but is not part of the Host-owned critical path. The stable Host exposes read-only file inspection and forces server-tree mutations, remote restore and Host-side device mutation off. Telemetry, audit and settings remain available where handlers exist. See [full local capability deployment](FULL_CONTROL.md) for the complete matrix.

If Access shows a local capability enabled but `This Device: Not granted`, the device credential is stale for that scope. Do not mutate it in place. Revoke the intended device and pair it again with the intended role. A newly issued Owner grant contains the current canonical `Scopes.ALL`; an existing Owner grant keeps exactly the scopes it originally received.

| Scope                     | Observer | Moderator | Administrator | Owner |
| ------------------------- | :------: | :-------: | :-----------: | :---: |
| `audit.view`              |    —     |     —     |       ✓       |   ✓   |
| `audit.view.self`         |    ✓     |     ✓     |       ✓       |   ✓   |
| `backup.create`           |    —     |     —     |       ✓       |   ✓   |
| `backup.delete`           |    —     |     —     |       —       |   ✓   |
| `backup.download`         |    —     |     —     |       —       |   ✓   |
| `backup.restore`          |    —     |     —     |       —       |   ✓   |
| `backup.view`             |    —     |     —     |       ✓       |   ✓   |
| `chat.send`               |    —     |     ✓     |       ✓       |   ✓   |
| `chat.send.minimessage`   |    —     |     —     |       —       |   ✓   |
| `chat.view`               |    —     |     ✓     |       ✓       |   ✓   |
| `console.execute.allowed` |    —     |     —     |       ✓       |   ✓   |
| `console.view.errors`     |    ✓     |     ✓     |       ✓       |   ✓   |
| `console.view.full`       |    —     |     —     |       ✓       |   ✓   |
| `devices.revoke`          |    —     |     —     |       ✓       |   ✓   |
| `devices.view`            |    —     |     —     |       ✓       |   ✓   |
| `files.create`            |    —     |     —     |       —       |   ✓   |
| `files.delete`            |    —     |     —     |       —       |   ✓   |
| `files.download`          |    —     |     —     |       ✓       |   ✓   |
| `files.list`              |    —     |     —     |       ✓       |   ✓   |
| `files.read`              |    —     |     —     |       ✓       |   ✓   |
| `files.rename`            |    —     |     —     |       —       |   ✓   |
| `files.upload`            |    —     |     —     |       —       |   ✓   |
| `files.write`             |    —     |     —     |       ✓       |   ✓   |
| `overview.view`           |    ✓     |     ✓     |       ✓       |   ✓   |
| `player.ban`              |    —     |     ✓     |       ✓       |   ✓   |
| `player.feed`             |    —     |     —     |       —       |   ✓   |
| `player.gamemode`         |    —     |     —     |       —       |   ✓   |
| `player.heal`             |    —     |     —     |       —       |   ✓   |
| `player.kick`             |    —     |     ✓     |       ✓       |   ✓   |
| `player.kill`             |    —     |     —     |       —       |   ✓   |
| `player.message`          |    —     |     ✓     |       ✓       |   ✓   |
| `player.op`               |    —     |     —     |       —       |   ✓   |
| `player.teleport`         |    —     |     —     |       —       |   ✓   |
| `player.unban`            |    —     |     ✓     |       ✓       |   ✓   |
| `player.whitelist`        |    —     |     ✓     |       ✓       |   ✓   |
| `players.address`         |    —     |     —     |       —       |   ✓   |
| `players.history.view`    |    —     |     ✓     |       ✓       |   ✓   |
| `players.location`        |    —     |     —     |       —       |   ✓   |
| `players.view`            |    ✓     |     ✓     |       ✓       |   ✓   |
| `plugins.config`          |    —     |     —     |       ✓       |   ✓   |
| `plugins.reload`          |    —     |     —     |       ✓       |   ✓   |
| `plugins.view`            |    ✓     |     ✓     |       ✓       |   ✓   |
| `server.restart`          |    —     |     —     |       ✓       |   ✓   |
| `server.start`            |    —     |     —     |       —       |   ✓   |
| `server.status`           |    ✓     |     ✓     |       ✓       |   ✓   |
| `server.stop`             |    —     |     —     |       —       |   ✓   |
| `settings.view`           |    ✓     |     ✓     |       ✓       |   ✓   |
| `telemetry.view`          |    ✓     |     ✓     |       ✓       |   ✓   |

The table describes immutable credential grants, not guaranteed runtime capability. In the stable 3.5.1 Host, `backup.restore` and Host `files.create/write/upload/rename/delete` never become effective even when an Owner grant contains those compatibility scopes. `players.history.view` is usable only while `player-history.enabled` is true on Paper. Owner cannot override that setting. Existing device records are immutable and do not acquire the new scope during upgrade; revoke and re-pair only a device whose operator should gain history. Live current-roster deltas remain under `players.view`.

The local registry is `plugins/PlexonPanel/access/devices.json` with an adjacent lock, at most 64 devices, 1–30-day expiry and last authorized activity (throttled to one minute). Revoke-all increments generation. Devices cannot grant themselves more scopes. Action aliases are defined in `Scopes.ACTIONS` and mirrored in TypeScript; chunks use the parent download scope.
