# Migration to PlexonPanel 3.1.0

This is a conservative Paper-agent PlexonCore migration. Protocol 3, `/v1`, identities, pairing, immutable device grants and Paper/Host authority remain unchanged.

## Before upgrading

1. Stop Minecraft.
2. Back up the complete `plugins/PlexonPanel/` directory, including identity, pairing and `access/devices.json` state.
3. Back up the Host companion configuration/identity if Host is installed.
4. Keep `PlexonCore-1.0.0.jar` installed for Core mode. Core remains optional; standalone Panel operation is supported.
5. Do not delete or regenerate Panel identity/access state.

## Replace artifacts

Replace the Paper JAR with `PlexonPanel-3.1.0.jar`. If using the coordinated Host bundle, replace the Host executable JAR with `plexonpanel-host-3.1.0.jar` while preserving its existing configuration and identity.

The Dashboard/Relay may be deployed as coordinated 3.1.0 metadata. Wire protocol remains 3, so mixed 3.0.2/3.1.0 combinations are an explicit validation target rather than a protocol migration.

## Startup verification

Before Paper starts, verify the optional Host can still report the expected Host-only state and can perform its authorized lifecycle actions.

Start Paper and run:

```text
/plexon modules
/plexon integrations
/plexon diagnostics
/plexonpanel status
/plexonpanel capabilities
/plexonpanel diagnostics
```

Expected Panel diagnostics include:

```text
Plugin: 3.1.0
Mode: CORE
Core plugin/API: 1.0.0 / 1.0
Protocol: 3
```

`/plexon modules` should show PlexonPanel as `READY` with module ID `panel`.

## Identity acceptance

Compare the server UUID and fingerprint before and after the upgrade. They must match. Existing 3.0.2 paired browser credentials must authenticate without re-pairing and existing immutable scopes must not change.

A missing `protocol-version.txt` marker is recreated as protocol 3 without clearing pairing state. Re-pairing is not a migration workaround.

## Core reload acceptance

With Paper authenticated and a Dashboard connected, run `/plexon reload` and verify all of the following:

- the existing Paper relay connection remains alive;
- Host remains connected;
- the browser remains connected;
- no new Paper wire session is created;
- no `access.sync` is caused by Core reload;
- Panel remains or returns `READY` in Core.

`/plexonpanel reload` remains a Panel-owned operation and may replace the Panel `AgentRuntime` according to its existing semantics.

## Standalone acceptance

Remove PlexonCore and restart Paper. PlexonPanel must start in `STANDALONE` mode with the complete existing Panel control plane available. Restore PlexonCore and restart Paper; the same identity, grants and paired browser credentials must remain valid and `panel` must register `READY` again.

## Rollback

Stop Paper and restore the previous 3.0.2 Paper/Host JARs. Keep `plugins/PlexonPanel/` and Host identity/access data intact. Do not delete the plugin folder and do not re-pair devices merely to roll back.

Stable `v3.1.0` remains blocked until the live acceptance evidence in `docs/release-gates.json` is complete. Publish/use `v3.1.0-rc.1` for acceptance first.
