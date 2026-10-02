# Node backup coordination

Fleet-enabled Hosts share a kernel file lock at `/run/plexonpanel/locks/<nodeId>.lock`.
The administrator creates the inode once at boot, owned by root and writable only by the
node's Host coordination group. Every parent must be root-owned, non-writable to group/others
and free of symlinks. Hosts open an existing regular file with `NOFOLLOW_LINKS`; they never
create, unlink or replace it. Do not delete a lock file to clear a busy state: doing so can
create two independently locked inodes. Kernel locks disappear when their owner exits.

The maintenance worker waits up to one hour before entering the Minecraft stop boundary.
Its lease covers countdown, shutdown, archive/hash/upload, verification and startup/recovery.
Upload retries and local restore/emergency archives use the same coordinator. Interruption
cancels a waiting operation. Heartbeat/telemetry schedulers remain independent. Status exposes
`nodeBackup.enabled`, `localLeaseActive` and `waiting`; progress emits `WAITING_FOR_NODE` and
maintenance emits `maintenance.node.queued`. These report local process participation rather
than claiming a global owner identity.

The additive Host `fleet` configuration contains `nodeId` and `instanceKey`. It binds the
existing immutable `serverId` to its node and requires the exact service
`minecraft@<instanceKey>.service`. Legacy schema 4 without `fleet` retains legacy behavior
for rollback. Schema 5 activation will require fleet metadata; this preparation is not
production certification. Final service/tmpfiles provisioning follows the finalized CLI,
configuration, control authorization and shutdown contracts.

Automated coverage includes separate JVM contention and process death, interrupted waits,
independent heartbeats, separate nodes, symlink denial, nested leases, exact-unit validation,
and actual ZIP creation delayed until the node lease is obtained. Production concurrency
and service recovery remain separate runtime gates.
