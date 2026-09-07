# Installation

Use Java 25 and a disposable Paper 26.2 server first. Build the two JARs with the commands in [README](../README.md#build-and-install), stop Paper before installing its JAR, start once to create private identity and local configuration, then configure the relay URL/public key and enable the outbound gateway. Keep mutations disabled while verifying Observer pairing, telemetry and revocation.

The optional host requires a dedicated non-root Linux user, independent identity, exact systemd/polkit policy and shared registry directory: follow [HOST_AGENT](HOST_AGENT.md). Production Ubuntu 24.04 ARM64, cloud, rclone and stability checks are required by [VALIDATION](VALIDATION.md). When upgrading from 2.0, preserve identity/configuration and follow [MIGRATION](MIGRATION.md).
