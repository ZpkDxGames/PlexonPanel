# Installation

Use Java 25 and a disposable Paper 26.2 server first. Build or download the matched `PlexonPanel-4.0.0.jar` and `plexonpanel-host-4.0.0.jar`, verify `SHA256SUMS.txt`, stop Paper before installing its JAR, start once to create private identity and local configuration, then configure the relay URL/public key and enable the outbound gateway. Keep mutations disabled while verifying Observer pairing, telemetry and revocation.

The optional Host requires a dedicated non-root Linux user, independent identity, exact systemd/polkit policy and shared registry directory: follow [HOST_AGENT](HOST_AGENT.md). Production Linux x64/ARM64, relay, rclone and stability checks are required by [VALIDATION](VALIDATION.md). For upgrades, preserve identity/configuration and follow [MIGRATION](MIGRATION.md).
