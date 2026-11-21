# Bedrock Host integration — Phase 1 contract

Status: **development only**. No production installation, relay change, or remote-action activation is authorized by this document.

## Goal and verified baseline

Add a Bedrock Dedicated Server to the existing PlexonPanel fleet without pretending it is a Paper/Bukkit instance.

The target deployment is Ubuntu 24.04 ARM64, Docker Engine 29.10.0, a single `itzg/minecraft-bedrock-server:stable` container named `tonimbedrock`, and a persistent `/srv/plexonpanel/servers/tonimbedrock/data` bind mount. It is running independently with `restart: unless-stopped`. No Java server is to be changed.

Existing control plane: standalone PlexonPanel Relay v5.0.0, Protocol 3, fleet contract v1, Paper/Host schema 5, plus three running Paper/Host pairs. The current relay health endpoint reports three paired rooms. No Bedrock identity is yet registered.

## Trust and responsibility boundaries

```text
Browser UI (existing scoped grants and confirmation)
    -> signed Protocol 3 / WSS
Relay (existing room/target and device authorization, adapted for HOST_ONLY)
    -> signed, per-instance Bedrock Host identity
Bedrock Host (unprivileged Java 25 process)
    -> fixed local broker (peer-credential authenticated Unix socket)
Broker (privileged, container-name pinned and strictly allowlisted)
    -> Docker Engine API (only tonimbedrock)
```

Do **not** add `pph-tonimbedrock` to the `docker` group, mount `docker.sock` into a network-facing service, install a broad sudo rule, or expose a generic Docker API. Docker control is effectively host-root privilege; only a small fixed-action broker may mediate it. Browser-supplied container names, executable paths, command arrays, Docker flags, image tags, and volumes must be rejected.

Keep the existing Ed25519 signer, anti-replay guard, session/generation targeting, action scope checks, device revocation, audit and review/confirmation model. Add a distinct host-only bootstrap and owner enrollment flow: Bedrock cannot run the Paper plugin that currently anchors device pairing. Never spoof a Paper hello or weaken relay authorization to make the server appear.

## Identity and compatibility

- Immutable `serverId`: newly allocated RFC UUID; never reuse a Paper instance identifier.
- `nodeId`: existing VPS node identity, subject to authenticated proof; avoid double counting node resources.
- `instanceKey`: `tonimbedrock`, matching the local container policy.
- `edition`: additive enum field `BEDROCK` (legacy Paper peers omit this and are treated as Paper only when the plugin identity is verified).
- `runtime`: `DOCKER`; do not label Docker memory as JVM heap.
- The relay's current action/fleet hashes are **not** evidence of HOST_ONLY admission support. Preserve existing hashes until a versioned compatible negotiation and a specific host-only authorization test have passed.
- Reject a duplicated `serverId`, mismatched node association, misleading PAPER readiness and stale/unsigned data.

## Read-only Phase 1 telemetry

The Bedrock Host should emit signed and timestamped evidence, with unavailable readings represented as null and excluded from success claims:

- Container lifecycle state (`running`, `exited`, `restarting`, `unknown`) and restart policy.
- Exact container CPU and memory, not a host average or JVM heap. Suggested future dashboard service keys: `containerCpuPercent`, `containerMemoryBytes`, `containerStatsCapturedAt`.
- Uptime and last-restart timing; rate-limit polling and cap the collected output.
- A bounded, redacted Docker log stream with explicit cursor/replay semantics.
- Edition/version/online players only from corroborated Bedrock-specific evidence. Container alive does **not** imply Bedrock signaling or gameplay is ready. Do not infer player count from Docker process existence.
- Bedrock-specific metrics should never show Paper TPS/MSPT, heap, plugin list or Bukkit-only capabilities.

## Deferred operations: OFF by default

1. **Lifecycle:** bounded start/stop/restart of exactly `tonimbedrock`; reviewed, rate-limited and audited. No arbitrary compose-file editing or container replacement from the browser.
2. **Console:** test a reliable, scoped stdin/attach relay with bounded text, reconnect and command acknowledgement. The current image's `send-command` helper failed with `/proc/*/exe: Permission denied`; do not rely on it, nor allow a browser to execute `docker exec` in general.
3. **Players:** allowlist and operator management only after console reliability and identity checks. Bedrock XUID is not a Java UUID.
4. **Backups:** capture a consistent Bedrock LevelDB world, preferably a verified cold snapshot or supported save hold/resume sequence, with atomic archive promotion, checksum, retention, encrypted off-host copy and rollback. Never tar the live database and claim consistency without proof.
5. **Settings/files:** confine changes to explicitly reviewed, validated Bedrock properties; never expose general file mutation or server-secret reads.

## Proposed rollout gates

- [ ] Read-only Docker snapshot collector tested with running, stopped, missing, malformed, stale and permission-denied fixtures.
- [ ] Broker safety tests prove other containers and Docker operations cannot be targeted.
- [ ] Relay host-only handshake, standalone owner enrollment and revocation verified under deliberate denial tests.
- [ ] Signed fleet roster shows one BEDROCK instance while the three Paper hosts remain unchanged.
- [ ] UI labels CPU/memory/players honestly and hides TPS/MSPT/JVM values for Bedrock.
- [ ] Console, lifecycle, backups and settings individually gated behind reviewed scopes and tests.
- [ ] Staging tests on ARM64, rollback documentation, and explicit operator approval before deployment.

## Existing environment anomaly (separate maintenance)

Several retired `plexoncraft` or `plexonpanel-node-lock` templated services were reported in automatic restart loops. Investigate exact unit enablement/dependencies separately; do not change them as part of Bedrock integration.

## Preservation requirements

No changes to the three live `minecraft@...` units, existing Host identities, relay signing keys, production Vercel deployment, current Docker `compose.yml`, or Bedrock data. Develop and review from `development/bedrock-host-integration-v1`.
