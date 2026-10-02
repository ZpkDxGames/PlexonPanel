# Instance control and journal authority

Schema 5 runtime uses `minecraft@<instanceKey>.service` under `mc-<instanceKey>` and a separate `pph-<instanceKey>` Host account. The immutable key is at most 28 characters so the prefixed Linux account fits Ubuntu's limit. Presentation names never select a unit, account or journal namespace.

## Exact service authorization

`host-agent/examples/fleet/00-plexonpanel-instance-control.rules` is a root-owned polkit rule for the systemd D-Bus manager. For a canonical Host account it allows only `org.freedesktop.systemd1.manage-units`, the exact paired Minecraft unit, and `start`, `stop` or `restart`. Other systemd operations, other units and malformed Host account names are explicitly denied. Normal administrator policy is left unchanged. Hosts require no sudo grant, root process, global systemd authority or unit-file management permission.

The filename orders this rule before ordinary administrator rules. Provision it as root, mode 0644, in `/etc/polkit-1/rules.d`; preserve any legacy rule separately for rollback and inspect rule ordering. Do not install while an existing broad Host authorization remains effective. Actual production certification must query the effective policy and execute allowed/denied controls with both Host accounts. The executable rule has 45 local authorization test cases; this is source-policy evidence, not a production authorization PASS.

Primary contract: systemd v255 `src/core/dbus-util.c` supplies `unit` and `verb` details for `org.freedesktop.systemd1.manage-units`: <https://github.com/systemd/systemd/blob/v255/src/core/dbus-util.c>.

## Scoped journald

The final Minecraft template must set `LogNamespace=plexonpanel-%i`. Schema 5 Host startup derives `plexonpanel-<instanceKey>` locally and passes it to both live streaming and retained history. Both commands pin `/usr/bin/journalctl`, the exact unit and `--namespace=plexonpanel-<instanceKey>`. Wildcard/default-plus namespace selectors, a different instance namespace and browser-provided unit/namespace flags are rejected. Schema 4 keeps its existing default journal behavior until coordinated migration.

Host accounts must not belong to `systemd-journal`, `adm` or another group with broad journal access. Grant each Host read/traverse access only to its namespace directory and journal files, including default ACLs for newly created files. No global journal ACL is needed. systemd normally stores a namespace under `/var/log/journal/<machine-id>.plexonpanel-<instanceKey>`; volatile fallback under `/run/log/journal` must also be checked during startup/recovery. Verify actual directory ownership, namespace permissions, new-file inheritance and retained history after journal rotation/restart before marking this gate PASS.

Primary documentation: systemd v255 `LogNamespace=` and `journalctl --namespace` (<https://github.com/systemd/systemd/blob/v255/man/systemd.exec.xml>, <https://github.com/systemd/systemd/blob/v255/man/journalctl.xml>). A namespace is a separate journald instance; querying the default namespace alone will not read these logs.

## Certification boundary

Final service templates and production provisioning follow only after the CLI, root-owned registry, port collision validation, private configuration, backup-lock provisioning and bounded shutdown contracts are finalized. These source changes do not install production rules, change VPS journals or replace legacy services. VPS rule effectiveness, namespace ACLs and two-server control isolation remain NOT_EXECUTED.
