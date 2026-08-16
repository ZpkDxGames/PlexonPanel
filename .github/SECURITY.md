# Security policy

Only the latest published PlexonPanel release receives security fixes while the project is in preview.

## Reporting a vulnerability

Use GitHub's private vulnerability reporting or security-advisory feature for this repository. Do not publish exploits, credentials, pairing codes, server addresses, logs, or player data in an issue.

Include the affected version, impact, reproduction steps, and a suggested mitigation when possible. Replace private information with safe examples.

## Operator guidance

- Use `wss://` for remote connections; plain `ws://` is limited to loopback development.
- Pin the relay public key before enabling the connection; rc.2 rejects an enabled unsigned configuration.
- Keep console access and destructive player actions disabled unless they are required.
- Review command filters and redaction patterns before connecting a production server.
- Protect `plugins/PlexonPanel/identity/device.key` and rotate the identity after suspected exposure.
- Maintain independent server backups and host-level access controls.
- Never place a Firebase Admin/service-account key in the plugin, browser, Vercel environment, or repository.
