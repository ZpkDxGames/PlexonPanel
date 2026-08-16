# Privacy

PlexonPanel sends data only when its relay connection is enabled and
authenticated. Individual sensitive categories have additional local toggles.
The rc.2 hosted relay forwards current frames to authorized browsers but does
not write telemetry, console, chat, player, plugin, or action-result frames to
application storage.

## Data categories

| Category | Examples | Default |
| --- | --- | --- |
| Host metrics | CPU load, RAM/disk usage, uptime, OS/Java labels | Enabled |
| Server health | Paper/Minecraft version, TPS, tick time, player count | Enabled |
| Player snapshot | UUID, name, world, mode, ping, health, level, op/whitelist | Enabled |
| Precise player location | coordinates, yaw, pitch | Disabled |
| Player network address | IP address | Disabled |
| Plugin snapshot | plugin metadata and enabled state | Enabled |
| Error console | redacted WARN/ERROR lines and fingerprints | Enabled |
| Full console | redacted lines observed after tailing starts | Disabled |
| Chat | player UUID/name and global message text | Disabled |
| Action audit | actor, action, target, decision/result, timestamp | Local file enabled |

## Where data exists

| Location | Retention behavior |
| --- | --- |
| Paper memory | bounded live queues and console ring; cleared on shutdown |
| Paper disk | persistent identity/config plus rotating JSONL action audit |
| Cloudflare relay memory | current frames only while routing active sockets |
| Cloudflare Durable Object storage | public identity, paired/revocation state, short-lived pairing/rate metadata |
| Vercel | no PlexonPanel server data or browser credential |
| Paired browser IndexedDB | scoped credential and bounded workspace convenience cache |

The console tailer begins at the current end of `logs/latest.log`; it does not
upload the pre-start log file. When an authorized dashboard connects, the
plugin may resend up to 100 redacted lines already present in the current
in-memory ring, in bounded batches. Unsent lines are dropped rather than queued
indefinitely. Chat has no reconnect snapshot.

The browser cache is bounded by the dashboard and is not a compliance log or
backup. It can disappear when site data is cleared, storage is evicted, the
credential expires, or `/plexonpanel unpair` is run. A Paper-local audit is the
authoritative remote-action record.

## Transport statement

Production uses `wss://` TLS. Plugin envelopes and every privileged relay
message are Ed25519-signed and replay-checked. The Cloudflare relay terminates
TLS and processes frames in memory to route them, so the system is not
end-to-end encrypted. Do not enable a category whose current contents should
not be visible to the relay operator and paired dashboard administrators.

## Operator responsibilities

Server operators choose enabled features, protect the Paper host and plugin
identity, restrict dashboard access, provide legally required notices, and
configure an appropriate `audit.retention-days`. Chat, IP addresses, console
output, plugin metadata, and player information may be personal or sensitive
depending on jurisdiction and server policy.

Local audit files are stored in `plugins/PlexonPanel/audit/`. Identity files are
not telemetry but must never be published. Never import a Firebase Admin or
service-account credential into the plugin or dashboard.
