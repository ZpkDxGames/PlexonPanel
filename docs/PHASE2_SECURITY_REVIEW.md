# PlexonPanel 3.2.0-rc.1 Phase 2 security review

Scope: Paper plugin, host companion, protocol/replay layer and the coupled dashboard/standalone-relay source candidate.

## Pairing and identity

Pairing remains Paper-originated, one-use and five minutes by default. Server identity is a stable local cryptographic identity, not a dashboard display name. Device grants are persisted at the execution authority and can be revoked. The relay handshake validates component kind, protocol version, key identity, fresh challenge, session nonce/sequence and room/server ID.

## Replay and routing

Protocol envelopes are freshness/signature checked. Request IDs and bounded recent-request ledgers reject duplicate privileged work. Session sequence/message-ID guards reject reconnect/stale replay. Cross-server room mismatch is rejected. Offline transport does not create an unbounded privileged action backlog.

## Capabilities and execution authority

Paper and Host capabilities remain separate. The dashboard cannot grant itself a server-side forbidden scope. Privileged Paper actions are dispatched through the controlled server execution path rather than arbitrary relay worker threads. Host actions remain constrained to configured service/host capabilities; no generic unauthenticated shell is introduced.

## Resource bounds and sensitive streams

Console/chat histories and outbound paths use bounded buffering/queues and drop accounting. Relay payload size, connection counts, handshake time and rate-sensitive pairing paths are bounded. Console redaction remains enabled where practical. The standalone relay persists coordination metadata only, not telemetry/log/chat/command payload history.

## Transport/configuration

Remote endpoints require secure WebSocket transport; plaintext WebSocket is limited to loopback for the colocated standalone relay. Standalone relay configuration rejects wildcard origins, weak/missing secrets, mismatched Ed25519 keys and accidental public binding. Reconnect uses bounded exponential delay with jitter instead of a busy loop.

## PlexonCore boundary

PlexonCore is compile-only/non-shaded. Panel consumes Core lifecycle/diagnostics services but Core does not become the control-plane authority. Core 2 lifecycle updates and cleanup are owner-aware.

## Findings

No HIGH or CRITICAL security defect was identified in the reviewed source candidate and automated contract surface. This is a source security review only: deployed Cloudflare Tunnel routing, Unix service permissions, destructive-action confirmation behavior, multi-server isolation and stale-session behavior still require the dedicated runtime matrix before stable promotion.
