# Development

## Requirements

- Java 25
- Paper 26.2 for server testing
- Node.js 20 or newer only for the loopback mock gateway

The Gradle wrapper supplies the required Gradle version.

## Build and test

```bash
./gradlew clean test javadoc :agent:jar
```

The installable plugin is written to `agent/build/libs/`.

## Project layout

- `agent/` — Paper plugin and tests
- `integrations/plexonchats-api/` — optional PlexonChats public contract
- `protocol/envelope.schema.json` — signed WebSocket envelope
- `mock-gateway/` — loopback-only development server

The production Cloudflare Worker is maintained in the private
`PlexonPanel-Dashboard` repository under `relay/` because Vercel and relay
deployment are reviewed together.

## Runtime boundaries

Paper state is read and changed on the server thread. System metrics,
networking, log tailing, and local audit writes use bounded background workers.
Remote requests are checked against advertised capability, local feature
settings, replay state, and action policy before execution.

Protocol v2 messages use signed JSON envelopes over WebSocket. The relay must
complete the Ed25519 nonce challenge before pairing, telemetry, snapshots, or
actions are accepted. Changing required envelope fields, canonical signature
input, or action semantics requires another protocol version.

The production relay retains the historical `gateway.*` message/config names
for wire compatibility. It stores only coordination metadata; tests must not
assume a telemetry database.

## Reconnect and idempotency

After `gateway.authenticated` or signed `gateway.snapshot_request`, the runtime
sends current server/system/player/plugin state and up to 100 redacted console
ring entries in 25-line messages. Live chat is never replayed.

Remote action request UUIDs are guarded by both an in-flight set and a bounded
1024-result access-ordered cache. A duplicate completed UUID returns the same
result; it does not execute again. Any future action implementation must remain
safe under retries and run required Bukkit/Paper calls on the main thread.

## PlexonChats adapter

The optional adapter expects PlexonChats to expose the contract under
`com.antondev.chats.api`. If unavailable, PlexonPanel continues without the
adapter and standard Paper chat capture remains available.

## Loopback gateway

```bash
cd mock-gateway
npm start
```

The mock binds to `127.0.0.1` and is intended only for development. Follow
[its README](../mock-gateway/README.md) for pairing and action examples.

## Releases

1. Update the project version and `CHANGELOG.md`.
2. Run the full build/test/Javadoc command with Java 25.
3. Test the JAR on a disposable Paper 26.2 server against the review relay.
4. Complete pair/reload/restart/unpair and duplicate-action acceptance.
5. Push a matching `vX.Y.Z` tag or run the release workflow manually.

GitHub publishes the JAR and SHA-256 checksum. Source archives are supplied by
GitHub.
