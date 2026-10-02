# Fleet identity lifecycle

The administrator provisions one public node UUID with the local Host `--init-node` operation.
It creates `/etc/plexonpanel/node-id` exclusively, fsyncs it and never replaces an existing value.
Runtime reads require a root-owned file/hierarchy without group/other write access or symlinks.
Paper and every Host on the node must use this same UUID. The node UUID is public metadata,
not a pairing credential. Private keys, grants and RCON configuration remain per instance.

Paper's additive `fleet.node-id`, `fleet.instance-key` and `fleet.server-name` configuration
and Host's `fleet.nodeId` / `fleet.instanceKey` bind the existing server UUID to that node.
Both signed hellos include the shared fleet contract. System telemetry carries nodeId and an
honest processRole: Paper reports MINECRAFT, Host reports HOST. Existing process metrics from
the Host JVM must never be presented as Minecraft process consumption.

`identity/fleet-binding.json` durably records serverId, nodeId, instanceKey, canonical server
root, canonical identity directory and the public device fingerprint. Restart and rename
preserve this binding. A copied directory, changed node/unit or changed key fails before the
binding is rewritten. An exclusive `identity/fleet.lock` lease prevents simultaneous processes
using that same identity state. The lock inode is never deleted to clear a busy condition.
Agent lease checks and signed relay duplicate-session denial complement one another.

Fleet activation or association changes require a full offline restart/migration; reload may
only change presentation and ordinary policy. Online `rotate confirm` is rejected in fleet
mode before keys or pairing state are changed. This prevents an old binding from being silently
reassigned. Final schema 5 migration will require fleet fields; these additive schema 4
preparations preserve the old rollback path and do not certify production readiness.

## Intentional offline clone/rekey procedure

1. Stop the clone's Paper and Host units and verify both are inactive. Do not alter the original
   server's identities. Keep the original installation and its verified rollback materials.
2. Retain the clone's copied PlexonPanel plugin data and Host state as a private rollback copy
   outside its server backup source. Preserve permissions; never print their contents.
3. Create a new empty PlexonPanel plugin data directory and a new empty private Host state
   directory for the new instance. Copy only reviewed operator policy into the new plugin
   directory, updating the instance key, display name, local endpoints and node UUID. Do not
   copy old keys, device grants, pairing state, retained panel history or fleet-binding files
   into the new identity state.
4. Paper creates a new server UUID/key in that empty state. Initialize a new Host key with the
   existing local `--init <new-host-state>` operation, then attach its public key to the new
   Paper policy and configure the Host with the new Paper server UUID and exact instance unit.
5. Pair the new instance independently. Verify the original server's grants, history, actions
   and credentials are unchanged. Preserve the archived clone state until certification.

This procedure creates a new managed server; it intentionally does not transfer the original
server's immutable identity/history. Moving an existing server while preserving its identity
is a separate offline migration and requires verified rollback plus explicit binding migration.
Never erase a binding automatically in response to startup failure.
