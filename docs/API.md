# PlexonPanel Local API — 3.2.0

PlexonPanel 3.2.0 registers `io.github.zpkdxgames.plexonpanel.api.PlexonPanelAPI` through Bukkit `ServicesManager` after the Paper `AgentRuntime` has started successfully.

The service is **local-only and read-only**. It is intended for other installed Paper plugins that need sanitized Panel state. It is not a remote-control surface and does not replace protocol 3.

## Lookup

```java
RegisteredServiceProvider<PlexonPanelAPI> registration =
    Bukkit.getServicesManager().getRegistration(PlexonPanelAPI.class);
PlexonPanelAPI panel = registration == null ? null : registration.getProvider();
```

Consumers should treat the service as optional. PlexonPanel unregisters it during plugin disable after closing its runtime.

## Read surface

```java
String productVersion();
int protocolVersion();
boolean coreMode();
boolean gatewayEnabled();
boolean relayAuthenticated();
ConnectionStateView relayState();
UUID serverId();
String serverFingerprint();
boolean paired();
Set<String> localCapabilities();
Optional<Instant> lastConnectedAt();
Optional<Instant> lastRelayMessageAt();
int reconnectAttempts();
```

Returned collections are immutable/copy views. Methods return already-available local state and must not block the Paper main thread waiting for network I/O.

`localCapabilities()` is descriptive local policy state only. It does not grant a device any scope and is not an authorization API.

## Explicit exclusions

The API does not expose private keys, pairing codes, pairing pepper, browser/bearer credentials, relay signing secrets, Host private keys, mutable `DeviceRegistry`, raw signed messages, console/chat/file contents, session signatures, generic protocol send methods, remote action execution, credential issuance or scope-grant operations.

For authorization, pairing, revocation, files, backups and remote actions, the existing PlexonPanel control-plane rules remain authoritative.
