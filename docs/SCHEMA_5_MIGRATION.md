# Staged schema-5 migration

This additive implementation prepares schema 5 while preserving the schema-4 defaults and component version 4.0.0 until coordinated activation. It is not runtime certification or a stable 5.0 release. Do not replace the deployed 3.5 relay during preparation.

`InstanceLayout` derives directories, account names, unit names and journal namespace exclusively from the validated immutable instance key. Server display names select none of these. Linux instance keys are limited to 28 characters so `pph-<key>` fits Ubuntu's 32-character username limit ([Ubuntu 24.04 useradd documentation](https://manpages.ubuntu.com/manpages/noble/man8/useradd.8.html)). The generic wire identity parser remains compatible with its existing 32-character key bound; the narrower Linux account constraint is enforced by both schema-5 instance runtimes. Host schema 5 requires a bound server/node/key identity and the exact prepared paths:

| Purpose | Path for `plexoncraft` |
| --- | --- |
| Server | `/srv/plexonpanel/servers/plexoncraft/server` |
| Host launcher | `/srv/plexonpanel/servers/plexoncraft/host` |
| Host config | `/etc/plexonpanel/instances/plexoncraft/host-config.json` |
| State | `/var/lib/plexonpanel/instances/plexoncraft` |
| Access mirror | `/var/lib/plexonpanel/instances/plexoncraft/access/devices.json` |
| Backups | `/var/backups/plexonpanel/instances/plexoncraft` |
| Private RCON secret | `/etc/plexonpanel/instances/plexoncraft/rcon.secret` |
| Private rclone config | `/etc/plexonpanel/instances/plexoncraft/rclone.conf` |

Offsite backup bases must end with the immutable server UUID, avoiding collisions with another instance. RCON secrets have instance-specific paths; node-wide uniqueness of RCON/listen ports still requires registry validation and actual runtime checks. Schema-5 Host runtime validates its config path/account and rejects symlinked data/source/secret paths before loading identity state. Paper schema 5 requires explicit fleet metadata, the exact server/plugin directories and Minecraft account. The shared root-provisioned node UUID is checked before either component creates private identity state.

## Explicit planning and application

The Host's local CLI adds `<host-config.json> --plan-fleet <instanceKey>` and `<host-config.json> --migrate-fleet <instanceKey>`. Planning reads the staged config and public node UUID; output contains only schema versions, server/node/key IDs, exact unit, backup filename and whether changes are needed. It never prints the config, relay key, RCON data, remote base or opaque operator fields. Application requires the local Linux administrator and the canonical staged config path. It updates only schema/fleet fields after complete candidate validation, preserves identities and existing operator configuration, and keeps `host-config.json.pre-v5-backup` with original ownership/permissions. A failure before atomic publication leaves the original file and first backup intact.

Paper exposes `FleetConfigMigration.plan` and `migrate` for the coordinated migration tool/runtime integration. It reads persisted YAML directly, attaches real defaults only for candidate validation, preserves unknown operator values and creates `config.yml.pre-v5-backup`. Neither planning nor application rotates server/device identities or pairing grants. A changed node/key binding requires explicit offline migration. Existing schema-5 config is not rewritten or downgraded by the legacy schema-4 migration path.

Malformed/fractional/overflowed/future schema markers fail closed. Host CLI and Paper startup errors omit arbitrary exception messages and causes. Schema-5 Host accepts opaque operator metadata without granting it runtime authority; known capability, channel, identity and path fields remain validated.

Do not apply these operations to the legacy installation as an isolated production step. Preserve its original files, units, private identity/grant state and installed binaries. Prepare the target layout and validated public identifiers first, then execute coordinated activation only after backup coverage, off-VPS verification and rollback gates permit it. New pairing may be chosen explicitly for a fresh instance, while the original installation remains an independent rollback option.

Remaining integration: mandatory version/schema activation, operator-facing Paper planning/apply entry points, node port registry, exact-unit authorization and scoped journald access, final service templates, migration/rollback rehearsal and actual multi-server runtime/security certification.
