# Security policy

## Supported versions

PlexonPanel is pre-1.0. Only the newest published release receives security fixes.

## Reporting a vulnerability

Use GitHub's private vulnerability reporting/security-advisory feature for the PlexonPanel repository. Do not open a public issue containing an exploit, private key, pairing code, access token, server address, log archive, or player data.

Include the affected version, impact, reproduction steps, and any suggested mitigation. Replace all credentials and personal data with safe examples.

## Deployment requirements

- Keep the gateway behind TLS (`wss://`). Plain `ws://` is accepted only for loopback development.
- Pin the exact Ed25519 gateway public key in `config.yml`; do not disable signed-message enforcement in production.
- Restrict dashboard accounts with MFA and least-privilege roles when the hosted dashboard is introduced.
- Keep remote console and destructive player actions disabled unless required.
- Review command allow/deny expressions and redaction patterns before connecting a production server.
- Protect `plugins/PlexonPanel/identity/device.key` with filesystem permissions and backups appropriate to your host.
- Rotate the identity after suspected key exposure and revoke the old server in the dashboard.

No remote administration system eliminates the need for server backups, host access controls, and human review.
