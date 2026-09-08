# PlexonCore Integration — PlexonPanel 3.1.0

PlexonPanel 3.1.0 makes the **Paper agent** a PlexonCore-aware module without moving control-plane authority into PlexonCore.

## Contract

- Module ID: `panel`
- Display name: `PlexonPanel`
- Supported Core API: `>=1.0 <2.0`
- Tested Core runtime: PlexonCore `1.0.0` / Core API `1.0`
- Paper platform: Paper 26.2 / Java 25
- Wire protocol: **3**, unchanged
- Routes: `/v1`, unchanged

PlexonCore is a soft dependency of the Paper agent. The Host companion, protocol module, dashboard and relay do not depend on PlexonCore.

## Authority boundary

PlexonCore owns local module registration and diagnostics only. PlexonPanel continues to own server identity, pairing, immutable device grants, `ControlPolicy`, relay transport, telemetry, streams, remote actions, files, backups and player presence according to the existing Paper/Host split.

Core capability metadata is diagnostic metadata. It cannot grant a device scope, bypass a local denial, create Owner authority, bypass confirmation, rotate identity or execute a remote action.

Effective remote permission remains the intersection of immutable device scope, agent-kind authority, local capability, action-specific rules and confirmation requirements.

## Lifecycle

Paper startup resolves Core if available, registers `panel` as `STARTING`, loads existing identity/access state, starts the single existing `AgentRuntime`, registers the local `PlexonPanelAPI`, then marks the module `READY`.

If Core is absent, disabled, unavailable or outside `>=1.0 <2.0`, PlexonPanel starts in `STANDALONE` mode. Unsupported Core is not a reason to disable the Panel control plane.

A Core diagnostics/registration failure must not disconnect Panel transport or mutate Panel security state.

## Core reload

`/plexon reload` reloads PlexonCore only. It must not close or replace `GatewayClient`, create a new WebSocket session, restart Host, restart telemetry/streams, run `access.sync`, clear pairing or rotate identity.

The bridge can repair missing Core module metadata on a later coarse state update, but it does not own transport.

## Health semantics

`READY` means the local Paper module is ready. Browser absence, Host absence, no paired browser, intentionally disabled gateway, or disabled optional streams are not by themselves local module failures.

Relay reconnect/backoff is reported as connection detail and should not flap module health. Invalid required Panel configuration or a true sustained protocol/configuration failure may result in `FAILED`/`DEGRADED` as appropriate.

## Build provisioning

CI downloads the official `PlexonCore-1.0.0.jar`, verifies SHA-256:

```text
4abce6de93293e21b31cb874734430d5bdc77de17a4c3b98fd6a9006e1f13018
```

and installs it into the runner-local Maven repository as `com.zpkdxgames:PlexonCore:1.0.0`.

The Paper JAR distribution check rejects any bundled `com/zpkdxgames/plexoncore/` runtime classes. Core must remain installed separately in Paper's `plugins/` directory for Core mode.

## Diagnostics

`/plexon modules` should show:

```text
PlexonPanel — READY | Core API | 3.1.0
```

`/plexonpanel diagnostics` distinguishes:

```text
Plugin: 3.1.0
Mode: CORE/STANDALONE
Core plugin/API: 1.0.0 / 1.0
Supported Core: >=1.0 <2.0
Module: READY/DEGRADED/FAILED
Public Panel API: REGISTERED
Protocol: 3
```

No diagnostic is permitted to print private keys, pairing codes, bearer credentials, relay signing material, raw signed envelopes or private console/chat/file payloads.
