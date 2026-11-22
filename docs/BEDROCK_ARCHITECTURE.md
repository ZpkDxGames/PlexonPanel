# Standalone Bedrock fleet identity and local Docker authority

This decision applies to the two Bedrock development branches. See the [ARM64 runbook](BEDROCK_ARM64_RUNBOOK.md) for runtime acceptance and rollout.

## Context

The existing fleet binds a Minecraft server UUID to a Paper identity and pairs its optional Linux Host against that authority. Browser tokens, action targets and pending confirmations refer to one server session. Bedrock Dedicated Server has no Paper runtime. The Bedrock game already runs in an ARM64 Docker deployment through Box64, with world data on a persistent host bind mount.

The standalone relay source used for this integration comes from the Dashboard repository's [5.0.0 source tree](https://github.com/ZpkDxGames/PlexonPanel-Dashboard/tree/da691360b39435d02a8671f45f2a2c4c49e3adbe/relay). The current Dashboard branch had no relay source in its working tree. This commit is the historical source reference recorded in the core [5.0 release gates](release-gates-5.0.0.json). Establish the running artifact's identity separately before switching an installed relay.

## Decision

Introduce a `HOST_ONLY` room with a root-approved independent Bedrock host identity and per-server device grants. Signed Protocol 3 envelopes still carry immutable `serverId` and authenticated metadata. A valid Bedrock association includes the server UUID, existing verified node UUID, exact `tonimbedrock` instance key, host public key, explicit `BEDROCK` edition and `DOCKER` runtime. A display name can change independently. Each room owns all its connection state, grants, facts and pending work. The browser's fleet connection ceiling stays 16, and multiple instances sharing a node contribute node capacity once.

Keep the Paper pairing and authorization mirror on their existing path. Explicitly configured Bedrock admission routes to a separate Host-only authorization path in the standalone relay. The legacy fleet and action contracts retain their identifiers. The `bedrock-host-v1` extension signs its identity together with `BEDROCK_HOST` admission data, then uses signed `bedrock.status` and `bedrock.logs` snapshots on the authenticated session. A relay or browser without this extension cannot certify a Bedrock room. A room without a valid Host presents disconnected or unavailable signals.

The Bedrock Host runs as its own non-login service account. A local broker authenticates its peer at a private Unix endpoint and mediates fixed operations for the pinned container. The broker alone opens the Docker socket. The public browser and relay connect only to their existing network paths. The read-only release accepts `STATUS` and `LOGS` requests at the broker. Each later capability needs a separately validated local operation and explicit authorization through the signed relay path.

## Authority and compatibility

| Path | Identity and admission | Data and operations |
| --- | --- | --- |
| Paper plugin + Linux Host | Signed installed identities, existing Paper pairing, grants mirrored to the corresponding Host | Paper owns players, chat, commands and JVM metrics. Linux Host owns exact systemd service state, host resources, existing journals and backups. |
| Bedrock Host + standalone relay extension | Administrator pins the distinct Host key and immutable server/node/key relationship, mediates one-time owner enrollment and can revoke the grant | Docker broker owns bounded container state and logs. Bedrock telemetry owns the distinct readiness, version and roster evidence available from that runtime. |
| Old relay without Bedrock admission | Paper contract as installed | Paper remains usable. Bedrock rooms require the matched relay extension. |
| New Dashboard without an admitted Bedrock room | Existing Paper room validation | Existing Java behavior. A label, inactive grant or unverified message cannot create a live Bedrock view. |
| Matched relay, Dashboard and Bedrock Host | Signed, pinned Host-only identity and a live, scoped device grant | Bedrock reads use the ordinary fleet selector and edition-specific rendering. Additional operations require their own validated control path. |

Container status, CPU, memory and uptime describe the running Docker container. Container memory has its own limit and is separate from node memory and JVM heap. Bedrock gameplay readiness, running server version and player roster require separate evidence with their own freshness. Missing evidence remains unavailable. Logs are bounded and redacted before transport. Neither persistent relay telemetry storage nor a second dashboard is required.

## Identity and grant lifecycle

| Transition | Gate | Result |
| --- | --- | --- |
| Unregistered → provisioned | Local administrator records the exact server/node/key tuple and pins its Host key; duplicate and substitution checks pass | Relay can validate a Host hello for this one room. Any browser still needs an independent grant. |
| Provisioned → Host connected | Matching signed hello, supported protocol/metadata, current session and replay checks | Accept scoped Host-origin evidence with connection/freshness checks. Deny conflicting identity, stale frames and cross-room data. |
| Provisioned → enrolled device | Administrator creates a short-lived, one-time enrollment credential out of band; eligible browser claims it through the existing pairing endpoint | Current scoped device authority is stored for the single room. |
| Enrolled → observing | Authenticated browser token, active device grant and requested scope match the room and generation | Relay emits authorized status or logs according to `server.status` and `console.view.full`. |
| Host loss → unavailable | Disconnect, expiry, freshness timeout or invalid evidence | Clear or mark stale data; reconnect requires the same pinned tuple. |
| Enrolled → revoked | Administrator invalidates the grant under the room's revocation policy | Deny re-use and reconnect. A fresh approved enrollment issues a new grant. |

For external rollout, preserve the current Paper/Host/relay keys, ledger and session routing. Keep read-only staging separate from production and rehearse rollback before installing the new services.

## Source ownership

- **Core repository** owns the Java 25 Bedrock Host, exact local Docker access, Ubuntu service examples, bounds and validation of Docker data, and shared Protocol 3 primitives.
- **Dashboard repository** owns the standalone relay runtime, its Host-only admission and device ledger, browser pairing/session binding, fleet aggregation and edition-aware UI.
- **Operator** approves immutable identities, private file ownership, deployment evidence, revocation and feature gates. The broker, Host, relay and browser remain separately authenticated across their trust boundaries.

## Consequences

An independently authenticated Bedrock instance can share the fleet UI while Paper rooms retain their own authorization. Operators must maintain a separate private Bedrock host identity and device enrollment policy. Stale/missing evidence, an unapproved key and unsupported capabilities fail closed. The rollout depends on a tested standalone relay and matched Dashboard artifact. The game image, Compose file, world, Paper processes and gameplay listeners remain independent of companion deployment and rollback.
