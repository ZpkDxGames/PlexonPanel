# Contributing

Thanks for improving PlexonPanel.

## Development setup

1. Install Java 25. The Gradle wrapper downloads Gradle 9.7.0 automatically.
2. Fork and clone the repository.
3. Run `./gradlew clean test :agent:jar`.
4. Use a disposable Paper 26.2 server and the loopback mock gateway for manual tests.

## Pull requests

- Keep remote capabilities disabled by default.
- Never make Bukkit/Paper API calls from a networking or file worker.
- Add tests for protocol, policy, redaction, identity, or queue changes.
- Update the protocol schema and docs whenever the wire contract changes.
- Do not commit server logs, generated identities, pairing codes, private keys, access tokens, or real player data.
- Explain security and privacy impact in the pull-request description.

Run the full verification command before opening a pull request:

```bash
./gradlew clean test javadoc :agent:jar
```

For security reports, follow [SECURITY.md](SECURITY.md) instead of opening a public issue.
