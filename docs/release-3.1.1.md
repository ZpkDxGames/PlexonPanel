# PlexonPanel 3.1.1

PlexonPanel 3.1.1 is a source/CI/release maintenance release for PlexonCore 2.0.4 compatibility. It preserves protocol 3 and the existing Paper/Host/Dashboard/Relay authority model.

## Changed

- Paper agent supports PlexonCore API `>=1.0 <3.0`.
- Build/test provenance is pinned to the exact PlexonCore 2.0.4 release JAR and SHA-256.
- Core 2 module state transitions are owner-aware.
- Core 2 disable cleanup is owner-scoped and cannot remove a foreign module registration.
- Paper and Host artifacts are versioned 3.1.1 while the wire protocol remains version 3.

## Unchanged

Pairing, server/device identity, relay authentication, immutable device grants, remote-action scopes/confirmation policy, telemetry, console/chat streaming, reconnect behavior, host companion, backup authority, `/v1` routes and protocol-v3 wire compatibility are unchanged from 3.1.0.

The reviewed Dashboard/Relay 3.0.2 protocol-v3 contract remains compatible because this patch does not change the wire protocol or API routes.

## Certification boundary

The historical 3.1.0 live-acceptance evidence is retained as historical evidence and is not relabeled as 3.1.1 runtime validation.

- SOURCE/CI/RELEASE: eligible after green CI and publication
- RUNTIME CERTIFICATION: NOT EXECUTED for 3.1.1 in this agent environment
