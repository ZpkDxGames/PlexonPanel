# Development

## Requirements

- Java 25
- Paper 26.2 for server testing
- Node.js 20 or newer only when using the local gateway

The Gradle wrapper supplies the required Gradle version.

## Build and test

```bash
./gradlew clean test javadoc :agent:jar
```

The installable plugin is written to `agent/build/libs/`.

## Project layout

- `agent/` contains the Paper plugin and its tests.
- `integrations/plexonchats-api/` contains the optional public contract used by the PlexonChats adapter.
- `protocol/envelope.schema.json` describes the signed WebSocket envelope.
- `mock-gateway/` contains a loopback-only development server.

## Runtime boundaries

Paper state is read and changed on the server thread. System metrics, networking, log tailing, and local audit writes use bounded background workers. Remote requests are checked against the local feature settings and action policy before execution.

Protocol messages use signed JSON envelopes over WebSocket. Changing required envelope fields, signature input, or action semantics requires a protocol-version change.

## PlexonChats adapter

The optional adapter expects PlexonChats to expose the contract under `com.antondev.chats.api`. If that contract is unavailable, PlexonPanel continues without the adapter and standard Paper chat capture remains available.

## Local gateway

```bash
cd mock-gateway
npm start
```

The gateway binds to `127.0.0.1` and is intended only for local development. Follow [mock-gateway/README.md](../mock-gateway/README.md) for pairing and action examples.

## Releases

1. Update the project version and `CHANGELOG.md`.
2. Run the full build and test command.
3. Test the JAR on a disposable Paper 26.2 server.
4. Push a matching `vX.Y.Z` tag or run the release workflow manually.

GitHub publishes the JAR and its SHA-256 checksum. Source archives are provided automatically by GitHub.
