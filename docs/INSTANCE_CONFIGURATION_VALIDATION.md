# Instance configuration validation

Schema 5 runtime and `--validate-fleet <key>` read existing authority without changing permissions, files or services. Registration validates the intended allocation before publishing it. `--validate-node` requires the local Linux administrator, checks all registered instances and rejects reused RCON credentials. Output is restricted to node UUID and public allocations; private configurations, passwords, credential digests and environment values are never printed.

## Filesystem contract

| Path | Owner/group | Mode | Authority |
| --- | --- | --- | --- |
| Host configuration and existing pre-migration backups | root / pph-key | 0640 | Root changes configuration; paired Host reads |
| Host configuration directory and launcher | root / pph-key | 0750 | Paired Host reads/traverses; Minecraft denied |
| Host state and instance backup directory | pph-key / pph-key | 0700 | Private to that Host |
| RCON secret | pph-key / pph-key | 0600 | Matches the existing owner-only RCON reader |
| Immutable rclone configuration, when configured | root / pph-key | 0640 | Paired Host reads; mutable provider-state handling is dependent work |

Root-owned ancestors must not be writable by other accounts or groups. Symlinks and non-regular private files are rejected. Named ACLs may not grant Minecraft, another Host or global groups access to private files/directories. Host supplementary groups are limited to its own instance group and the fixed `plexonpanel-backup` coordination group. Global journal/admin/root and other instance groups are rejected.

These requirements are stricter than the operator's earlier prepared-directory write tests. In particular, the reported legacy backup parent remains owned by the old Host account. That specific ownership is preserved now and must be addressed with a recorded reversible ownership/ACL transition during the coordinated deployment, after complete rollback evidence is accepted. Passing the earlier Host backup create/delete test does not imply this new ancestor-authority check has passed. No production ownership/ACL change is performed by these source additions.

## Minecraft/RCON binding

The validator reads `server.properties` without emitting its contents. Minecraft and RCON ports must match the public registry; `enable-rcon` must agree with the immutable Host configuration. An enabled channel requires a distinct per-instance ASCII secret of at least 16 bytes, with only terminal CR/LF ignored, and the Minecraft password must equal that private secret. Mismatches produce fixed codes without values or parser causes. The existing RCON transport retains its owner-only secret permission rule.

The registry reserves logical allocations, not kernel ports. Actual listening addresses/ports, firewall exposure, RCON authentication, source authority and service control are separate runtime gates. Run node validation before starting any coordinated 5.0 instances; then execute the live two-server denial and control matrix. Root may read all private configurations for this local audit; the network-facing Host never receives cross-instance configuration access.

## Deployment commands

Once verified 5.0 binaries/configuration/provisioning exist, the local operator runs:

```sh
sudo /usr/bin/java -jar /opt/plexonpanel/releases/5.0.0/plexonpanel-host-5.0.0.jar --validate-node
sudo -u pph-plexoncraft /usr/bin/java -jar /opt/plexonpanel/releases/5.0.0/plexonpanel-host-5.0.0.jar --validate-fleet plexoncraft
```

These commands describe future deployment validation. They have not run on the VPS, do not authorize destructive cleanup and do not imply a stable artifact is published. Port/password validation and private metadata/ACL/group tests execute locally; actual production file/account/service boundaries remain NOT_EXECUTED. Final service templates remain gated on the complete provisioning, provider-state and Paper migration entry-point contracts.
