# Protocol 3 contract

Product/bundle version 3.0.0, wire version 3. Routes retain `/v1`; protocol 2 is explicitly incompatible. Paper and host initiate WSS `/v1/agent?serverId=<uuid>&agentKind=PAPER|HOST` with `X-PlexonPanel-Protocol: 3`.

## Envelope

Every agent-direction message is Ed25519 signed, with exactly seven JSON fields:

| Field           | Encoding                                                                         |
| --------------- | -------------------------------------------------------------------------------- |
| protocolVersion | Number 3                                                                         |
| type            | `[a-z][a-z0-9_.-]{0,95}`                                                         |
| messageId       | UUID v1–5, RFC variant                                                           |
| serverId        | Existing Paper UUID                                                              |
| timestamp       | UTC ISO-8601, within 30 seconds of receiver clock                                |
| body            | Unpadded Base64URL of one strict UTF-8 JSON object, at most 65,536 decoded bytes |
| signature       | Ed25519 64 bytes, 86 unpadded Base64URL characters                               |

Envelope maximum is 131,072 UTF-8 bytes. Sign this exact UTF-8 concatenation with LF separators and **no trailing LF**:

```text
3\n<type>\n<messageId>\n<serverId>\n<timestamp>\n<body>
```

Keys use Base64 DER: X.509/SPKI public, PKCS#8 private locally. Validate fields, server binding, timestamp, signature and replay before trust. Java and TypeScript verify the identical public-only [wire fixture](../protocol/test-fixtures/v3-envelope.json).

Every agent→relay body contains signed `_session` (fresh random UUID per WebSocket) and `_sequence` (positive safe integer, hello is 1, strictly increases). The relay rejects a different session or repeated/old sequence; reconnection changes the session and discards queued old frames. Relay→agent uses the bounded envelope UUID/freshness replay guard, which fails closed at capacity.

## Authentication and grants

1. `agent.hello` identifies kind, versions, key/fingerprint, local capabilities and Paper's independently pinned host key.
2. Relay sends signed `gateway.challenge`; agent responds with `agent.challenge_response`, proving its key by signing UTF-8 `challenge:<nonce>`. Challenge expires after 15 seconds.
3. `gateway.authenticated` follows verification. Paper establishes/reuses the room identity pin. Host must match Paper's host pin and cannot establish one itself.
4. Local pairing creates a one-use five-minute role/scope grant. `agent.pairing_begin` registers a peppered code lookup. HTTP POST `/v1/pairings/claim` accepts code and label only; client role/scopes are rejected.
5. Relay sends `pairing.consume`; Paper persists locally before `pairing.accepted` returns requestId, generation, revision and device. Concurrent claims and revocation races fail closed.
6. The HMAC bearer credential binds audience `plexonpanel-relay`, protocol, server/device IDs, immutable role/scopes, generation and issue/expiry times (maximum 30 days).
7. Browser preflights `/v1/dashboard/session` with Authorization, then uses WebSocket subprotocols `plexonpanel-v3` and `auth.<credential>`. Tokens never enter URLs; origin must be allowlisted.

`access.sync` carries authoritative local generation/revision/devices. Stale state cannot undo revocation; a host can remove existing grants, never invent one. Revoke-all increments generation, active revoked browsers close and agents independently check their local registry. Last-seen means successful authorized activity, throttled to one minute, not idle presence.

## Actions and events

Browser sends `{type:"dashboard.action",requestId,action,parameters,agentKind?}`. Relay checks current grant/scope, local capability, Owner/confirmation constraints, size and rates. Signed `action.request` carries authenticated context; the agent repeats local authorization, validates typed arguments, durably audits intent and executes on bounded workers.

`dashboard.action_queued` acknowledges routing only. Private `server.event` with eventType `action.result` returns final data/status only to the requesting device. Status includes SUCCESS, CONFLICT, DENIED, FAILED or NOT_AVAILABLE. Disconnect/timeout requires local outcome verification and is never automatically replayed. A completed operation whose result audit fails explicitly requests verification before retrying.

Parameters: at most 16 keys / 49,152 bytes. Per-device limits: 20 actions or 160 chunks per 10 seconds, 32 pending requests; room limit 64 pending. Agents have independent bounded gates. Intent UUIDs survive process restart through the local audit replay window. Commands/messages/file bodies are excluded from audit parameters.

Inventory batches use `snapshotId`, `capturedAt`, `offset`, `complete`, and `truncated`, targeting ≤48 KiB. Clients stage a snapshot until its complete batch, discard mismatched/out-of-order batches, replay only newer presence deltas, and clear online players at an authenticated-session boundary. Recipient filters independently remove player location/address, history-only fields, full-console levels, and disabled events. Host cannot spoof Paper telemetry or player presence.

### Player-presence additions in 3.0

These are additive protocol-3 messages; older bodies remain valid and consumers must ignore unknown optional fields.

| Name | Direction | Authority and bounds |
| --- | --- | --- |
| `players.presence` | Paper event | Immediate minimal `JOINED`/`LEFT` delta; requires `players.view`; history-only fields are removed without `players.history.view`. |
| `players.history.list` | Dashboard action | Requires immutable `players.history.view` grant and current Paper capability. Query/status/date/cursor fields are strict; page size defaults to 50 and is at most 100. Result is private and transient. |
| `players.snapshot.request` | Dashboard action | Maps to `players.view`, accepts no parameters, is limited independently to once per device per five seconds, and coalesces concurrent Paper captures. |

Presence timestamps are UTC ISO-8601 instants. UUID is identity; the name is bounded plain account metadata. `sessionEndedAt` and `sessionDurationMillis` remain null when unknown. Termination is one of `OPEN`, `QUIT`, `KICK`, or `UNKNOWN_DISCONNECT`. A `JOINED` observation represents an event actually seen by the enabled plugin; enable/reload reconciliation does not manufacture a login event. On startup, an orphaned prior session is closed as unknown-disconnect while preserving the last definitely observed instant rather than guessing an exact logout.

`inventory.players` may add `sessionId` and `sessionStartedAt`. `firstSeenAt` and `lastLoginAt` are included only when persistent history is locally enabled and the recipient holds `players.history.view`. `players.history.list` returns newest-first `entries`, nullable `nextCursor`, `hasMore`, `boundedWindow`, `capturedAt`, and `historyEnabled`. Cursors are opaque and bound to the original filter/window; they never contain a caller-selected path.

Presence event bodies, player inventory bodies, and history action results are excluded from Durable Object persistence. Action results remain routed only to the requesting device.

Transfers use start → ordered 16 KiB chunks → cancellation/expiry, with final SHA-256 verification. File bodies and action results are transient. Durable Object storage contains identity/access/pairing coordination only; bounded socket attachments retain pending routing metadata across hibernation. Cloudflare's attachment limit is [16,384 bytes](https://developers.cloudflare.com/durable-objects/best-practices/websockets/); signed session sequences avoid unbounded replay arrays.
