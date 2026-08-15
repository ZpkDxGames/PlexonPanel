# Contributing

Contributions and reproducible bug reports are welcome.

## Development setup

1. Install Java 25.
2. Fork and clone the repository.
3. Run `./gradlew clean test javadoc :agent:jar`.
4. Use a disposable Paper 26.2 server for manual testing.

The loopback-only gateway under `mock-gateway/` is available for connection, pairing, and action tests.

## Pull requests

- Keep remote capabilities disabled by default.
- Keep Bukkit and Paper state access on the server thread.
- Add or update tests when changing protocol, identity, redaction, queue, or action-policy code.
- Update `protocol/envelope.schema.json` when the wire format changes.
- Do not commit logs, generated identities, pairing codes, credentials, or real player data.
- Describe any privacy or security impact in the pull request.

Report vulnerabilities privately by following [SECURITY.md](SECURITY.md).
