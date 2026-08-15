# Privacy

PlexonPanel sends data only when its gateway is enabled and connected. Individual data categories have additional feature toggles.

## Data categories

| Category | Examples | Default |
| --- | --- | --- |
| Host metrics | CPU load, RAM totals/usage, disk totals/usage, uptime, OS/Java labels | Enabled |
| Server health | Paper/Minecraft version, TPS, tick time, player count | Enabled |
| Player inventory | UUID, username/display name, world, game mode, ping, health, level, op/whitelist | Enabled |
| Precise player location | Coordinates, yaw, pitch | Disabled |
| Player network address | IP address | Disabled |
| Plugin inventory | Plugin metadata and enabled state | Enabled |
| Error logs | Redacted WARN/ERROR console lines and fingerprints | Enabled |
| Full console | Redacted lines written after the plugin begins tailing | Disabled |
| Chat | Player UUID/name and global message text | Disabled |
| Action audit | Actor, action, target identifier, decision, result code, timestamp | Local file enabled |

The plugin starts reading `logs/latest.log` at the current end of the file; it does not upload old console history after startup. Unsent console lines are dropped rather than retained indefinitely.

## Operator responsibilities

Server operators decide which features to enable, define retention in the hosted service, provide legally required player notices, and restrict dashboard access. Chat, IP addresses, console output, plugin metadata, and player information can be personal or sensitive data depending on jurisdiction and server policy.

Local audit files are stored in `plugins/PlexonPanel/audit/` and removed according to `audit.retention-days`. Identity files are not telemetry and should never be published.
