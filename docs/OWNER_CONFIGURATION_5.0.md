# Owner configuration for the two-instance VPS

The selected-server Dashboard update requires no replacement Host or Paper JAR. It uses existing Protocol 3 actions and scopes. Keep both schema 5 installations, accounts, services, identities, grant registries, state and backups intact. TonimSMP voice chat UDP 24455 is unaffected.

An Owner pairing already receives every canonical scope when issued. The grant and the executing agent’s local policy both authorize an operation; Owner does not bypass local policy or grant unrestricted Linux access. Existing grants are immutable. This Dashboard change adds no scope, so an existing valid Owner pairing does not need to be replaced.

## Configuration editing

The Dashboard Configuration workspace prefers connected Paper for file operations. Host remains a read-only fallback while Paper is unavailable. Before this update the file browser preferred Host even when Paper supported writes.

Paper can edit bounded text files beneath an explicitly writable instance root. Changes require a review and the file’s SHA-256 precondition. A conflicting server-side modification rejects the save and retains the browser’s edits. Plugin or Paper reload/restart requirements still apply to the particular setting; saving a file does not automatically reload it.

The existing path policy protects `server.properties`, PlexonPanel’s own configuration/data, identity, audit and credential files. These require local operator management. The Host configuration is also local and authoritative. Dashboard client appearance settings are browser-local and do not alter either agent.

If local file editing is disabled, make an explicit change to the intended instance’s existing Paper configuration. Back up that file first. Merge the required values into the existing mapping; do not replace the full configuration with a legacy preset.

| Instance | Paper configuration | Writable file root |
| --- | --- | --- |
| PlexonCraft | `/srv/plexonpanel/servers/plexoncraft/server/plugins/PlexonPanel/config.yml` | `/srv/plexonpanel/servers/plexoncraft/server` |
| TonimSMP | `/srv/plexonpanel/servers/tonimsmp/server/plugins/PlexonPanel/config.yml` | `/srv/plexonpanel/servers/tonimsmp/server` |

Required existing keys for read/write editing:

```yaml
remote-actions:
  enabled: true
files:
  enabled: true
  roots:
    server:
      path: "/srv/plexonpanel/servers/plexoncraft/server"
      read: true
      write: true
  permissions:
    list: true
    read: true
    download: true
    write: true
```

Use the TonimSMP root in the TonimSMP configuration. The permissions for `create`, `rename`, `delete` and `upload` can also be enabled individually if those existing operations are intended. Enabling remote actions also activates any other separately enabled remote capability, so review the current policy before changing it. Do not change identity, pairing, fleet instance key, Host public key, relay origin, account ownership or service unit names.

Apply local configuration changes only during an appropriate maintenance window using the existing configuration reload procedure or a controlled restart for that instance. A Dashboard-only deployment needs neither. Confirm the selected identity and Access capability matrix afterward. Do not edit stored credentials to add scopes; re-pair only if a genuinely missing immutable scope is required.

## Lifecycle feedback and certification

Start, graceful stop and restart are Host operations. Their successful result can arrive after Paper disconnects or reconnects. The Dashboard now binds pending requests to their executing agent’s session, alongside the server, browser socket and signed grant. Paper replacement invalidates Paper requests; Host replacement, browser disconnection, server switching or grant changes still invalidate affected pending commands without automatic replay.

Signed relay fixtures reproduce this ordering without starting or stopping either VPS instance. Local source tests do not certify actual Minecraft, systemd, WSS/proxy or backup behavior. Full deployed runtime certification remains outstanding.
