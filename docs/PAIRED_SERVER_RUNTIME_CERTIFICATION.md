# PlexonCraft / TonimSMP multi-instance verification

The operator reports the VPS migration complete, with independent Minecraft accounts, Hosts, configuration, identities, state and backups. Minecraft roots are `/srv/plexonpanel/servers/plexoncraft/server` and `/srv/plexonpanel/servers/tonimsmp/server`; TonimSMP voice chat uses UDP 24455. This work does not modify that installation or assert independently observed production states.

## Source review

The inspected Core base is `fb3c5444715179425183aec6d1f1fbe140211753` (5.0.0). `HostConfig` binds schema-5 paths and the exact Minecraft service to the fleet identity. `InstanceConfigurationAudit` requires the registered instance, account and Minecraft/RCON settings. `InstanceLayout` derives distinct server/config/state/backup/secret/account/unit/journal paths from each instance key. Host ingress verifies the envelope server UUID, relay signature and replay gate before handling an action. Authorization remains a Host-local mirror of the authoritative Paper grant registry. Display-name changes cannot change the immutable target.

The companion Dashboard redesign requires no Core runtime code, protocol, schema, JAR, unit or VPS-path change. Its new Host regression mounts two separate durable authorization mirrors, intentionally uses the same browser device UUID with different grants, rejects applying one instance's snapshot to the other, revokes one grant, restarts both mirrors, and verifies that the other instance retains its own role and unchanged registry bytes.

## Executed local checks

- The protocol and Host production sources and tests were freshly compiled using Java 25 and the declared Gson/JUnit versions. JUnit executed 247 tests successfully, including the new paired-mirror regression. Main resources and protocol test fixtures were included. This is a direct javac/JUnit run, not a Gradle build, Paper process, systemd operation or VPS certification.
- `node scripts/test-instance-polkit.mjs` passed 45 executed source-rule cases. Production rule installation/effectiveness was not exercised.
- `python3 scripts/test-backup-read-bridge.py` exited successfully.
- `python3 scripts/test-instance-read-authority.py --require-isolation` passed the initial FD/ACL/pinned-inode/lock cases, then stopped when this container rejected a synthetic-account `chown` with `EINVAL`. Full cross-account isolation was not established here.
- The Gradle wrapper could not download its declared distribution from Java in this network environment. Full Gradle/Paper tests and exact artifact checks remain GitHub CI requirements; the wrapper and build settings were not altered.

## Runtime checks still required

Use the companion Dashboard `docs/PAIRED_SERVER_WORKSPACE.md` for rollout and browser checks. Begin with read-only verification against both actual instances and their own identities, scoped console/history, players, backups, capabilities and node/service metrics. Preserve current live server states. Do not rerun migration, regenerate identities, copy access registries, replace working JARs or modify voice-chat ports for the client redesign.

Real start/stop/restart, Host disconnect, relay loss, restricted-role/revocation, backup/restore and cross-account process/filesystem/journal denials need an approved maintenance window or isolated staging instances. Capture the current state and target identity for each case. Distinguish local simulated action completions from actual game/systemd observations. Full end-to-end runtime certification remains outstanding until those checks and deployed HTTPS/WSS/browser evidence execute successfully.
