# Roles and scopes

The local operator chooses a role when issuing a five-minute one-use code. Built-in roles are immutable. Effective permission intersects current grant with local capability at both relay and agent. Owner never overrides policy. Custom roles use access.roles.<Name>.scopes; unknown scopes fail startup. Existing grant scopes are immutable: revoke and re-pair to change them. Op/deop and restore require literal Owner even for custom scope sets.

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

The local registry is plugins/PlexonPanel/access/devices.json with an adjacent lock, at most 64 devices, 1–30-day expiry and last authorized activity (throttled to one minute). Revoke-all increments generation. Devices cannot grant themselves more scopes. Action aliases are defined in Scopes.ACTIONS and mirrored in TypeScript; chunks use the parent download scope.
