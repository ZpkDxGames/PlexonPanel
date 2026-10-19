# PlexonPanel 5.0.0

PlexonPanel coordinates multiple Paper 26.2 servers on Java 25 with one non-root Linux Host per instance, a browser Dashboard, and a coordination-only Worker or standalone relay. Signed Protocol 3 carries the shared fleet contract; Paper/Host configuration schemas are 5. Server UUID, node UUID and immutable instance key determine routing, authorization, paths and units. Display names can change independently.

Source is prepared for 5.0.0. Stable publication and production deployment remain held by recorded migration/runtime/security gates. Preserve the original 4.0 installation and the deployed VPS relay 3.5.0 until coordinated activation and verified rollback are ready.

## Matched components

Build/install the coordinated `PlexonPanel-5.0.0.jar`, `plexonpanel-host-5.0.0.jar`, Dashboard 5.0.0 and Relay 5.0.0. The Host JAR is architecture-neutral Java and CI executes Linux x64/ARM64. [Dashboard/Relay repository](https://github.com/ZpkDxGames/PlexonPanel-Dashboard) owns the corresponding control-plane components. Exact source revisions, CI runs, hashes and unexecuted certification states are recorded in release manifests.

Paper owns pairing/device authority, Minecraft/JVM telemetry, players/chat and command execution. Host owns its exact Minecraft systemd service, cgroup accounting, private backups/provider state and retained namespace journal history. Healthy Host console is preferred; an explicitly enabled Paper fallback remains bounded, redacted and live-only. Host has read access to its paired server, no server-directory write access and no global journal group.

## Instance setup and migration

Use the canonical per-instance paths/accounts and units in [service deployment](docs/INSTANCE_SERVICE_DEPLOYMENT.md). Initialize the public node UUID once; allocate unique server UUIDs, keys and game/RCON ports in the root-controlled [node registry](docs/NODE_INSTANCE_REGISTRY.md). Host configuration planning/apply is an explicit local operation; [Paper bootstrap](docs/PAPER_FLEET_BOOTSTRAP.md) validates public authority before migrating configuration or creating keys. [Private configuration validation](docs/INSTANCE_CONFIGURATION_VALIDATION.md) checks owners/modes/ACLs, allowed groups and RCON consistency without printing secrets.

The [exact service/journal rule](docs/INSTANCE_AUTHORIZATION.md), stable shared node lock, bounded JVM shutdown, pinned read-authority helper and [private provider state](docs/PROVIDER_RUNTIME_STATE.md) are supplied in `host-agent/examples/fleet/`. All Hosts on one node obey one backup lease through archive/upload/recovery. Runtime token refresh uses isolated writable state; root-controlled provider seeds stay protected.

Ordinary restarts retain identities and device grants. [Intentional clone/rekey](docs/INSTANCE_CLONE_REKEY.md) allocates a new instance and preserves old/copied private state outside server backup includes. Pair each server independently; revocation/selection/confirmation/completion remain bound to the original server and signed session.

## Release gates and rollback

[Release preparation](docs/release-5.0.0.md) and [gate states](docs/release-gates-5.0.0.json) are authoritative for readiness. Execute actual two-server controls/telemetry, backup contention/integrity/upload, namespace/service/filesystem denial, graceful recovery, browser interaction, migration and rollback before publishing stable. OCI backup remains intentionally skipped by operator decision; current operational/off-VPS backup verification and rollback rehearsal remain required.

Original 4.0 binaries, private configuration, service paths, relay coordination and Caddy/certificate state must remain recoverable. Historical [4.0 source README](https://github.com/ZpkDxGames/PlexonPanel/blob/9efa8fcaf3d75d859e01fd712a2826aa8f974f29/README.md) describes that retained installation. No source merge grants permission to delete those materials or replace the standalone relay in isolation.

See [Protocol 3](docs/PROTOCOL.md), [privacy](PRIVACY.md), [changelog](CHANGELOG.md) and the synchronized Dashboard deployment gate for operational details.
