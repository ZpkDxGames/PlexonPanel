# PlexonPanel 4.0.0

PlexonPanel 4.0.0 coordinates the Paper plugin, Linux Host, Dashboard and both relay runtimes on
the existing signed Protocol 3 contract.

- Integrates live archive/upload progress, local and remote SHA-256 verification, safe remote
  promotion, verified-only VPS ZIP cleanup and retry without another server stop.
- Adds Host journald cursor pagination with bounded scans, redaction, chronological pages and
  least-privilege errors-only behavior.
- Restores explicit console-source transitions and an opt-in bounded, redacted Paper live fallback
  while Host journal authority is unavailable; retained history remains Host-only.
- Exposes Paper-owned durable player history with opt-in retention, cursor pagination and honest
  live/durable reconciliation.
- Adds backed-up atomic schema-4 migration without rotating identity, pairing or immutable grants;
  root-owned Host policy migrates through an explicit stopped-service local operation.
- Replaces stale release constants with a coordinated manifest containing source commits, CI run
  provenance, protocol/Java/Paper versions, artifact hashes and separate certification states.
- Keeps remote restore and Host writes to the live server tree disabled; local operator recovery
  remains the supported restore path.

Stable publication remains blocked until the exact 4.0 artifacts pass the live certification gates
recorded in `docs/release-gates-4.0.0.json`.
