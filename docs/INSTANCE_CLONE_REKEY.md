# Intentional offline clone/rekey procedure

Ordinary restart preserves server/node UUIDs, grants, keys and binding state. A copied directory is rejected when its registered UUID, canonical path, node or key differs; simultaneous identity-state use is rejected by the exclusive process lease. Display-name changes do not rekey anything.

Intentional cloning allocates a **new instance key and server UUID**. Rekeying an existing installation follows the same replacement-instance procedure, with the old instance retained for rollback. Do not edit a registered UUID in place or remove its registry entry to defeat clone detection. Revoking or forgetting one server must not remove another server's grants.

## Prepare without changing the original

1. Verify a consistent cold source backup and its archive integrity. Accept the current operational/off-VPS rollback evidence before production migration. Keep the original installation, service units, private configuration, identities and grants in place. OCI remains intentionally skipped by operator decision.
2. Allocate a new key (1–28 allowed Linux characters), distinct `mc-<key>`/`pph-<key>` accounts, canonical directories and a new public RFC UUID. The node UUID comes from `/etc/plexonpanel/node-id`; never regenerate it to make a copied installation work. On another node, use that node's existing/provisioned UUID.
3. Restore only the verified server content into the **new** canonical server directory. Do not clone Host configuration/state/backups, RCON secret files, provider runtime tokens or Host launcher permissions. Provision a fresh schema-5 Host configuration with the new UUID/key/paths/exact Minecraft unit and unique game/RCON ports. Keep its provider destination under the new server UUID; allocate a distinct private RCON secret. Provider authorization and secrets stay on the VPS.
4. Run the root-only Host registration and `--validate-node` checks after preparing the new server.properties and isolated private files. The root registry rejects duplicate UUIDs/keys and game/RCON port collisions. The original entry remains authoritative and retained.

## Preserve copied private state before first startup

Keep both **new-instance** units inactive with MainPID zero. Verify no manually launched JVM is using the new instance. If a copied `identity/fleet.lock` exists, a local administrator must successfully acquire a nonblocking **POSIX record lock** on its existing inode before moving any private state; release it only after the offline move. Java FileChannel uses this lock family. Linux `flock` is a different family and is not proof that the identity lease is free. Never delete or replace an active lock inode.

Create a root-owned 0700 preservation directory outside every Minecraft server/backup include, for example `/var/lib/plexonpanel/clone-preservation/<newKey>/<publicOperationUuid>`. Its ancestors must be root-owned, not writable by Minecraft/Host, and contain no symlinks. Preserve the cloned plugin's `identity`, `access`, `audit`, `presence`, `config.yml`, any `config.yml.pre-v4-backup`/`config.yml.pre-v5-backup`, and `protocol-version.txt` there. Prefer atomic moves on the same filesystem; otherwise verify the private copy before removing the cloned copy. Do not print private contents or their hashes. Do not put this archive under the server root: the backup read helper could otherwise grant Host read access to copied keys. The original source directories remain untouched.

Prepare a new Paper config from the preserved copy using a local private editor, retaining gateway pinning, operator fields and reviewed policy while setting schema 5 and the **new** `fleet.node-id`, `fleet.instance-key`, and chosen `fleet.server-name`. Clear the copied `host.public-key` until the new Host identity is established. Do not copy access grants or pairing state back. Review all configured Files roots so they refer only to the new instance; dormant Files capabilities remain disabled unless explicitly enabled and reviewed. Preserve history privately; fresh runtime history/audit starts in the new identity namespace.

## Initialize and pair

Start only the new Minecraft instance after coordinated 5.0 deployment is ready. Paper's preflight validates its account/root/node/port and creates fresh keys at the registry's new UUID. Initialize the new Host in its empty private state; configure its public key in the new Paper policy and pair each component through the supported flow. Pair the Dashboard independently to the new server and verify its displayed UUID/key/node before any control. The old Dashboard entry/grants remain independent. For replacement rekey, revoke the old server's devices through its own authority only after the replacement and rollback plan are validated; do not bulk-clear fleet browser storage or relay rooms.

Execute two-instance routing, copied-state rejection, service/journal/backup denial, per-server revocation and restart tests. Verify new backups carry the new UUID/key/node and use the new remote namespace. A retained world copy is not proof of a successful migration or runtime recovery. The actual production clone/rekey rehearsal remains NOT_EXECUTED until these outcomes are recorded.

## Roll back

Stop the replacement instance, preserve its state privately and return traffic/deployment to the retained original instance using its original units, binaries, configuration and grants. Do not copy replacement identities or grants into the original. Preserve the new registry allocation and archived clone state while diagnosing; no automatic deletion or registry rebinding is part of rollback.
