# Privacy and data handling

Agents use outbound TLS to the configured relay; Vercel serves the browser UI. No Firebase, telemetry database, or server-side Vercel credential is used.

| Location | Data and lifetime |
| --- | --- |
| Paper | Private identity, immutable device registry, mandatory privileged-operation audit, and—only when explicitly enabled—a bounded player-presence journal and summary under `presence/`. |
| Host | Independent private identity, local audit, archives/metadata, and recovery journal. It stores no player-presence history. |
| Cloudflare | Public identity pins, current grants/generation/revision, and short-lived pairing/rate/pending-routing coordination. No persisted telemetry, inventory, presence body, history result, file body, or action result. |
| Browser memory | Bounded live streams, roster, queried history, and transient file/action responses for the open workspace. |
| Browser IndexedDB | Per-server credentials until expiry/forget and a sanitized one-hour cache with bounded performance charts. Online players and detailed player history are excluded. |
| Optional rclone remote | Explicit backup archives under separately managed provider retention; no automatic presence export. |

`player-history.enabled` defaults to `false` because login/logout/session retention is personal-data collection. When enabled, Paper records UUID, plain account name, observed UTC instants, generated event/session IDs, bounded termination state, known durations, and optional Paper play-time summary. It does not record address, location, chat, console, kick text, display-name formatting, secrets, or tokens. Unknown logout time and duration remain null rather than being guessed.

Paper removes journal files outside the configured retention period and bounds event, player-summary, file-size, scan-byte, result, and worker-queue growth. Protect the `presence/` directory like other private server records. For rollback, archive it privately rather than combining it with the security audit or publishing it as release evidence.

The trusted relay necessarily sees transient routed data; TLS is not end-to-end encryption against the relay. Player location/address, full console/chat, and history remain separately scoped and locally gated. Use a trusted browser profile, revoke lost devices locally, and keep raw logs, player records, credentials, and production configuration out of issues and release artifacts.
