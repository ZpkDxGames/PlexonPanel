# Step 3 — Cold backup pipeline and local verification

The Linux PlexonPanel Host Companion owns cold full-restore-point creation. This step strengthens the post-countdown path without changing the Step 2 warning/final-save contract.

## Stop proof

Archive creation requires `SystemdService.stopped()` to prove that the configured unit is `inactive` or `failed` and that `MainPID` is either zero or no longer alive. An inactive unit with a still-live MainPID fails closed. Paper websocket connectivity is not a positive proof of shutdown and is not required for cold archive creation.

The maintenance orchestrator therefore reaches `ARCHIVING` only after systemd stop has completed and the stop proof succeeds. When the Host-local RCON channel is enabled, loss of RCON readiness is an additional secondary shutdown signal; it does not replace the systemd/process proof.

The archive walker continues to re-check the authoritative stopped state while reading files. If Minecraft becomes active again, the operation aborts rather than continuing against mutable data.

## Pre-shutdown checks

The full-backup preflight performs the checks that can be proven safely while Minecraft is still online. It pre-scans the configured server root, enforces entry and maximum-source-size bounds, validates the backup staging area, checks usable backup-volume space, and validates the configured provider prerequisites used by the full-backup policy.

This keeps detectable `BACKUP_SIZE_LIMIT`, `BACKUP_DISK_SPACE_INSUFFICIENT`, unsafe-symlink, staging, and provider failures ahead of the destructive systemd-stop boundary whenever possible.

## Disaster-recovery contents

A full restore point is a disaster-recovery snapshot of the configured Minecraft `serverRoot`, not a small allowlisted export. Persistent reconstruction data is included unless it matches an explicit safe exclusion. This includes, as applicable:

- worlds and player data;
- plugin JARs and plugin configuration;
- plugin databases and other persistent plugin state under the server root, including `.db`, `.sqlite`, `.sqlite3`, `.mv.db`, and equivalent formats;
- permission and economy state stored by server plugins;
- datapacks;
- Paper, Minecraft, and server configuration required to reconstruct the instance.

The archive walker never follows symbolic links. A symlink encountered inside the included server tree is rejected rather than dereferenced.

## Exclusions and secret boundary

Default maintenance settings exclude known transient/noise paths such as:

- `logs`;
- `crash-reports`;
- `cache`;
- `.cache`.

Operators may configure additional bounded relative exclusions. Absolute paths, traversal (`..`), backslashes, and oversized exclusion sets are rejected by settings validation.

Internal `.partial` files and PlexonPanel restore/rollback staging paths are also excluded. The backup destination itself is required to remain outside `serverRoot`; this keeps Host state, Host credentials/secrets, rclone configuration, the backup repository, and other Host-side control-plane data outside the Minecraft disaster-recovery archive.

## Archive staging and recovery

Full restore points are created under the Host backup root, which must remain outside `serverRoot` and must not be a symlink. New archives are first written as UUID-named `.partial` files under the bounded staging directory, forced to disk, and atomically promoted to the restore-point directory.

At Host restore-point-manager initialization, only unmistakable UUID `.partial` staging files are removed. Symlinks matching the partial pattern are deleted without following their targets; unrelated staging files are preserved. More than 1024 matching partial artifacts fails closed for operator review.

`.partial` files are never listed as valid restore points because inventory is metadata-driven and accepts only `FULL_RESTORE_POINT` metadata.

## Local verification gate

After atomic archive promotion and before any off-site upload, the Host:

1. calculates SHA-256;
2. opens the ZIP central directory and requires the persisted entry count to match;
3. streams every entry through bounded traversal/name validation;
4. rejects duplicate entries, malformed ZIPs, oversized expanded content, or source-byte mismatches;
5. writes metadata with `verification = VERIFIED_LOCAL` only after those checks pass.

A local verification failure aborts before rclone upload. `verify()` repeats the same structural/hash/size checks so restore and retry-upload operations cannot rely on stale metadata alone.

Remote downloads are subjected to the same local verifier before they are promoted back into the local restore-point inventory.

## Retention

Retention runs only after the newly created local restore point has been promoted and verified. `SINGLE_CURRENT` keeps the current non-emergency restore point, while `ROTATING` keeps the configured bounded history. Emergency pre-restore points are skipped by normal retention.

Because retention occurs after local verification, an unverified replacement cannot silently delete the only previously verified local restore point.

## Existing protections retained

The existing full restore-point safeguards remain in force: pre-scan, disk-space/maximum-size checks, symlink rejection, traversal protection, entry/input/output bounds, fsync/force-to-disk, atomic promotion, durable SHA-256 metadata, bounded local retention, and Step 4 rclone promotion/retry/failure policy.

## Validation

Automated coverage includes:

- inactive/failed systemd states with zero/dead MainPID;
- rejection of an inactive unit whose MainPID is still alive;
- explicit `SERVER_STOP_TIMEOUT` failure classification;
- exact UUID `.partial` cleanup without following symlinks;
- server-root symlink rejection without following the target;
- SHA-256 mismatch rejection;
- entry-count mismatch rejection;
- malformed/truncated ZIP rejection;
- expanded-size/source-size verification;
- existing preflight size, disk-space, staging, and maintenance safety tests.

Live production certification still requires observing a full backup with Paper disabled/offline, verified systemd/MainPID stop/start behavior, Host-local RCON shutdown/readiness behavior when enabled, a durable local archive/hash, and the subsequent Step 4 off-site behavior.
