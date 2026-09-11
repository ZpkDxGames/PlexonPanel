# PlexonCore Integration — PlexonPanel 3.2.0

PlexonPanel 3.2.0 keeps the **Paper agent** PlexonCore-aware without moving control-plane authority into PlexonCore.

## Contract

- Module ID: `panel`
- Display name: `PlexonPanel`
- Supported Core API: `>=1.0 <3.0`
- CI compile boundary: PlexonCore `2.0.4`
- Paper platform: Paper 26.2 / Java 25
- Wire protocol: **3**, unchanged
- Routes: `/v1`, unchanged

PlexonCore is a soft dependency of the Paper agent. The Host companion, protocol module, dashboard and relay do not depend on PlexonCore.

## Authority boundary

PlexonCore owns local module registration and diagnostics only. PlexonPanel continues to own server identity, pairing, immutable device grants, `ControlPolicy`, relay transport, telemetry, streams, remote actions, files, backups and player presence according to the existing Paper/Host split.

Core capability metadata is diagnostic metadata. It cannot grant a device scope, bypass a local denial, create Owner authority, bypass confirmation, rotate identity or execute a remote action.

Effective remote permission remains the intersection of immutable device scope, agent-kind authority, local capability, action-specific rules and confirmation requirements.

## Lifecycle

Paper startup resolves Core if available, registers `panel` as `STARTING`, loads existing identity/access state, starts the existing `AgentRuntime`, registers the local `PlexonPanelAPI`, then marks the module `READY`.

If Core is absent, disabled, unavailable or outside `>=1.0 <3.0`, PlexonPanel continues in `STANDALONE` mode. An unavailable Core diagnostics path must not disconnect Panel transport or mutate Panel security state.

For Core API 2.x, Panel uses owner-aware module state/update/unregister semantics. Core 1.x compatibility remains supported through the legacy module API path.

## Core reload

`/plexon reload` reloads PlexonCore only. It must not close or replace `GatewayClient`, create a new WebSocket session, restart Host, restart telemetry/streams, run `access.sync`, clear pairing or rotate identity.

The bridge can repair missing Core module metadata on a later state update, but it does not own transport.

## Build provisioning

CI downloads the official `PlexonCore-2.0.4.jar`, verifies SHA-256:

```text
61d625a717da9f46ee9231e1970d84b4c317ae12cf4090cdf7c9d39b6a1a9baf
```

and installs it into the runner-local Maven repository as `com.zpkdxgames:PlexonCore:2.0.4`.

The Paper JAR distribution check rejects bundled `com/zpkdxgames/plexoncore/` runtime classes. Core must remain installed separately in Paper's `plugins/` directory for Core mode.

## Diagnostics

`/plexonpanel diagnostics` reports the Panel version, Core/standalone mode, detected Core plugin/API version, supported Core range, module state, local Panel API registration and protocol version. Diagnostics must never print private keys, pairing codes, bearer credentials, relay signing material, raw signed envelopes or private console/chat/file payloads.
