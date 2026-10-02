# PlexonPanel fleet contract v1

The fleet contract is additive metadata within signed Protocol 3 bodies. The envelope fields, `/v1` routes, action scopes and action-contract identifier remain unchanged. Paper and Host configuration schema 5 is independent of the wire-protocol version.

The canonical fixture describes three servers: two on one node and one on a separate node. Both repositories carry the same fixture bytes. Its contract ID hashes compact parsed JSON in the existing property order:

`sha256:c5c8a5d21dd6b108f63fa6ecf00683c56ddb34cfb6716a2166ae22d3d438f902`

`serverId` identifies the authorized server. `nodeId` identifies the associated physical/logical node. Each must be a canonical RFC UUID with version 1–5 and variant 2. `instanceKey` is a bounded lowercase installation key used to derive the exact systemd unit; it does not replace the server authorization ID. Server names are bounded presentation labels and can change without rotating identity or grants.

The final 5.0 hello adds `fleetContract`, `nodeId`, `instanceKey` and `serverName` to its signed body. Host is authoritative for node telemetry. Relay integrations must bind these fields to the authenticated server, check Paper/Host association, reject substitution and reject unsupported contract IDs. All server-specific routing and grants remain bound to the envelope's immutable `serverId`.

The Dashboard connection ceiling is 16 simultaneous server sessions. Credentials, pending actions, caches and confirmation targets must remain per server/session. Node totals must be counted once per node and show freshness; Host process metrics must not be labeled Minecraft process metrics.

This contract groundwork does not activate schema 5 or deploy a runtime. Component versions stay at 4.0.0 while the dependent 5.0 runtime changes are implemented and tested. Protocol 3 peers without fleet metadata remain legacy/degraded during coordinated rollout. Stable 5.0 publication requires the complete implementation and executed migration, multi-server, security and rollback gates.
