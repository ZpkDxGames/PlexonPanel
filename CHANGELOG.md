# Changelog

## 3.0.0 — review candidate, live acceptance pending

- Added Paper-authoritative `JOINED`/`LEFT` presence observations with per-session IDs, honest unknown-disconnect semantics, bounded daily/size-rotated JSONL storage, atomic summaries, retention cleanup, stable cursor pagination, and history disabled by default.
- Added protocol-3 `players.presence`, `players.history.list`, and `players.snapshot.request` extensions without changing signed envelopes, `/v1` routes, server identity, or existing credential scopes.
- Added `players.history.view` for newly issued Moderator, Administrator, and Owner grants. Existing grants remain immutable; Observer does not receive history by default and Owner cannot bypass disabled local history.
- Split server, system, world, plugin, player snapshot, and immediate presence flows. Paper captures Bukkit state on its primary thread; bounded workers handle storage and transport. Reconnect discards stale-session telemetry and requests one authoritative roster snapshot.
- Added event-priority queue admission, coalesced/rate-limited manual roster refresh, snapshot/delta reconciliation, duplicate protection, and truthful reconnect/offline dashboard states.
- Rebuilt the unchanged Host companion as 3.0.0; no Host player capability or filesystem scraping was added.
- Updated privacy, configuration, migration, diagnostics, artifact metadata, cross-language tests, and gated draft-release checks. Stable publication remains blocked on recorded live acceptance.

## 2.0.0 — review candidate, production acceptance pending

- Protocol 3, local role/scoped grants, capability intersection, live revocation, signed connection sequences and restart-aware duplicate suppression.
- Paper 26.2/Java 25 telemetry, typed controls, console/chat policy and holder-owned inventory GUI.
- Restricted conflict-aware file editing and verified bounded transfers.
- Optional non-root systemd companion, local/rclone backups and stopped-server restore with emergency archive/idempotent journal recovery.
- Matching responsive twelve-section dashboard, isolated server workspaces, explicit connectivity and actual operation results.
- Cross-language fixtures, security/visual/runtime checks, review artifacts and gated draft release workflow.
