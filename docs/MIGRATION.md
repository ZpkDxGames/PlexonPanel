# Migration and rollback

## 3.0.0 to 3.0.1

PlexonPanel 3.0.1 is a local capability-policy and deployment hardening update. It keeps signed wire protocol 3, `/v1` routes, the Paper UUID/Ed25519 identity, Host pinning and existing device records. No existing grant is silently expanded.

The conservative install defaults remain conservative. Full-control deployments can opt into `agent/examples/config-full-control.yml` and `host-agent/examples/host-config-full-control.json`; review [FULL_CONTROL.md](FULL_CONTROL.md) before applying them.

Upgrade both JARs together during maintenance. Stop Paper for the Paper JAR replacement and update the Host service JAR path to `plexonpanel-host-3.0.1.jar` before restarting the Host companion. Preserve the existing identity and access registry.

After applying a full-control local policy, inspect Access with a newly paired Owner. If an applicable capability is locally enabled while `This Device` says Not granted, the credential predates that scope. Revoke that device, generate a new pairing code and pair it again with the intended role. Do not edit the existing credential or registry scopes in place.

Host continues to reject Paper-only scopes; Paper continues not to claim `server.start`, `server.stop` or `server.restart`. `plugins.reload` now advertises only when at least one configured plugin reload command is executable under the same local console allow/deny policy. No generic `/reload` is introduced.

Do not copy successful 3.0.0 live-release evidence into 3.0.1. The 3.0.1 release gates must be recorded again, including a disposable full-capability Access-matrix check and representative high-risk confirmation tests.

## 2.0 to 3.0

PlexonPanel 3.0.0 keeps signed wire protocol 3, `/v1` routes, the Paper UUID/Ed25519 identity, the Host pin, and every existing device grant. The migration adds missing configuration defaults without overwriting existing values. It never enables persistent history and never adds `players.history.view` to an issued credential.

### Upgrade

1. Schedule maintenance and privately back up the known-good JARs, `config.yml`, identity, access registry, Host configuration, and any recovery journals. Do not place them in dashboard transfers or release evidence.
2. Review the new `telemetry` cadence/event keys and `player-history` section. Leave `player-history.enabled: false` until the privacy purpose, retention period, filesystem protection, and authorized roles have been approved locally.
3. Deploy the compatible Dashboard 2.2 relay/UI while preserving its signing secrets, identity pins, Durable Object namespace, and existing storage. Do not clear Durable Objects to work around migration errors.
4. Stop Paper, replace the Paper JAR with `PlexonPanel-3.0.0.jar`, and start on Java 25/Paper 26.2. Never use generic Bukkit hot reload for a JAR upgrade.
5. Verify the same UUID and fingerprint, protocol 3 authentication, current capability map, live roster reconciliation, `/plexonpanel diagnostics`, and denial behavior before enabling mutations or history.
6. Existing paired devices continue to work for their original scopes. If a qualifying Moderator, Administrator, or Owner device should read history, revoke it and issue a new local pairing only after history is enabled. Observer remains current-roster-only by default.
7. Rebuild/install `plexonpanel-host-3.0.0.jar` during coordinated maintenance if the Host is used. Its player-data authority is unchanged: none.

If history is enabled, Paper creates `plugins/PlexonPanel/presence/`. Keep it outside backup/release sharing unless the operator has explicitly classified that personal data. Disabling history stops new persistent observations and reports the capability unavailable; manage existing retained files according to local policy.

## Rollback to 2.0

Stop Paper first and restore the known-good 2.0 components together. Preserve identity and `access/devices.json`; deleting them is not a rollback step. A 2.0 plugin ignores the new configuration keys and `presence/` directory. Archive the presence directory privately before rollback rather than deleting it casually, mixing it into the privileged-operation audit, or exposing it through release artifacts.

Dashboard 2.2 tolerates a 2.0 protocol-3 Paper agent and displays history as unavailable. An older protocol-3 dashboard/relay ignores additive 3.0 fields/events, but it cannot expose history. Do not attempt to reuse protocol-2 credentials or rename `/v1` routes.
