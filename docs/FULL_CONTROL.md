# Full local capability deployment

PlexonPanel 3.0.1 adds explicit full-control examples for the intended PlexonCraft deployment without changing signed wire protocol 3 or weakening the local authorization model.

Use:

- Paper: `agent/examples/config-full-control.yml`
- Host: `host-agent/examples/host-config-full-control.json`

These are intentionally separate from the conservative public defaults.

## Authorization remains an intersection

An operation is executable only when all applicable layers agree:

1. the immutable device grant contains the required canonical scope;
2. the relevant local agent advertises that scope as an effective capability;
3. the relay accepts and routes the request;
4. the executing agent independently authorizes the request and its typed parameters.

Owner receives the canonical `Scopes.ALL` set only when a new Owner grant is issued. Owner does not bypass a locally disabled capability. Existing device grants never gain scopes in place; revoke and re-pair a device that legitimately needs a scope introduced after its original credential was issued.

## Authority matrix

| Capability family | Paper | Host |
| --- | --- | --- |
| Overview | `overview.view` | not a Host capability |
| Telemetry | `telemetry.view` | `telemetry.view` |
| Players/history/location/address | enabled by Paper policy | not a Host capability |
| Console | enabled by Paper policy | not a Host capability |
| Chat | enabled by Paper policy | not a Host capability |
| Player actions | enabled by Paper policy | not a Host capability |
| Plugins | enabled by Paper policy | not a Host capability |
| Files | SafeFiles under configured Paper roots | SafeFiles under `serverRoot` |
| Backups | `backup.create` save/flush coordination only | full backup family |
| Server status | enabled | enabled |
| Server start/stop/restart | not Paper authority | enabled by Host policy |
| Audit/devices/settings | enabled where implemented | enabled where implemented |

A disabled/non-applicable Host cell for Paper-only actions is correct. A disabled Paper cell for Host lifecycle is also correct. Do not add scope aliases or claim handlers that do not exist merely to make the dashboard matrix visually uniform.

## Paper full-control policy

The Paper preset enables player history, player location/address, full console streaming, dashboard chat sending including MiniMessage, every currently implemented player action, device revoke, SafeFiles operations, backup coordination and the existing monitoring/audit/settings capabilities.

`plugins.reload` remains explicit. The preset configures only `PlexonPanel: "plexonpanel reload"` and adds exactly that command to the console allowlist. The capability is advertised only when at least one configured reload command actually passes the same local command allow/deny policy used at execution time. Generic Bukkit/Paper `/reload` is not exposed.

Full console capability does not mean arbitrary command execution. `ALLOWLIST_WITH_CONFIRMATION`, explicit allow rules, deny rules, maximum clock skew, high-risk confirmation and audit remain active.

Paper file access remains confined by `SafeFiles`/`PathPolicy`: canonical roots, no `..` escape, protected PlexonPanel data, write-root allowlisting, symlink protections, bounded transfers and extension restrictions remain in force.

## Host full-control policy

The Host preset enables telemetry, server status/start/stop/restart, all currently implemented file operations, the complete backup family, audit, devices and settings.

Host configuration still rejects Paper-only scope families such as players, console, chat, player actions and plugins. Lifecycle operations still use the validated exact systemd service name; the daemon must run as a dedicated non-root Linux user with permission to manage that configured unit.

Backup capability remains defense-in-depth gated. A configured backup scope is effective only while `backups.enabled` is true; restore additionally requires `backups.restoreEnabled`.

Host files remain confined beneath `serverRoot`. Full Plexon server control is not unrestricted Linux filesystem or shell access.

## Existing devices and re-pairing

After enabling a local capability, the Access page can still show `This Device: Not granted`. That means the credential itself lacks the scope. Do not mutate the stored credential. Verify the role, revoke/forget the intended device, generate a new pairing code and pair it again with the intended role. A newly paired Owner receives the current canonical Owner scope set.

## High-risk actions

Confirmation and audit remain mandatory for player ban/kill, op/deop, file delete, backup delete/restore, server stop/restart and device revoke. Full local capability never means bypassing those controls.

## Dashboard 3.0 note

The Dashboard 3.0 UI intentionally hides Files and Backups workspaces for now. Their backend capabilities may still be enabled by these presets for readiness and Host operations. The browser-local Display Update Rate setting does not require a new `settings.write` scope.

## Validation before production

Run the normal Gradle test/build/package pipeline, then validate the presets in a disposable environment. Pair a new Owner, inspect the Access matrix, exercise representative safe Paper and Host actions, and separately test high-risk confirmation. Destructive file, backup restore and lifecycle validation must not target irreplaceable production data.
