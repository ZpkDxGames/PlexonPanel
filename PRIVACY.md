# Privacy and data handling

Agents use outbound TLS to the configured relay; Vercel serves the browser UI. No Firebase, telemetry database or server-side Vercel credentials are used.

| Location               | Data and lifetime                                                                                                                    |
| ---------------------- | ------------------------------------------------------------------------------------------------------------------------------------ |
| Paper                  | Private identity, current device registry, mandatory local audit retained 1–365 configured days.                                     |
| Host                   | Independent private identity, local audit, archives/metadata and recovery journal.                                                   |
| Cloudflare             | Public identity pins, current grants/generation/revision, short-lived pairing/rate coordination; no persisted telemetry/file bodies. |
| Browser memory         | Bounded streams/history and transient file/action responses.                                                                         |
| Browser IndexedDB      | Per-server credentials until expiry/forget; sanitized cache for one hour with 30-minute charts.                                      |
| Optional rclone remote | Explicit archives under separately managed provider retention.                                                                       |

The trusted relay necessarily sees transient routed data; TLS does not make it end-to-end encrypted against the relay. Provider request metadata is subject to the operator's account policy. Relay observability is disabled; never log payloads.

Full console/chat and player location/address are separate local/scope opt-ins. Sensitive player fields, file bodies and action outputs are excluded from persistent browser cache. Audit excludes command text, message text, file bodies and secrets. Redaction cannot identify every plugin-specific secret; restrict verbose streams.

Use a private browser profile on a trusted device. A stolen bearer credential remains usable within remaining scopes and local policy until expiry/revocation. Forget clears browser data; revoke lost devices locally. Protect identity backups outside dashboard transfers and release artifacts.
