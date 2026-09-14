# Step 3 — Cold backup pipeline and local verification

The Linux PlexonPanel Host Companion owns cold full-restore-point creation. This step strengthens the post-countdown path without changing the Step 2 warning/final-save contract.

## Stop proof

Archive creation requires `SystemdService.stopped()` to prove that the configured unit is `inactive` or `failed` and that `MainPID` is either zero or no longer alive. An inactive unit with a still-live MainPID fails closed. Paper websocket connectivity is not a positive proof of shutdown and is not required for cold archive creation.

The maintenance orchestrator therefore reaches `ARCHIVING` only after systemd stop has completed and the stop proof succeeds.

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

## Existing protections retained

The existing full restore-point safeguards remain in force: pre-scan, disk-space/maximum-size checks, symlink rejection, traversal protection, entry/input/output bounds, fsync/force-to-disk, atomic promotion, durable SHA-256 metadata, bounded local retention, and Step 4 rclone promotion/retry/failure policy.

Host data, provider credentials and the backup destination remain outside the Minecraft server root and are therefore not included in the disaster-recovery archive. Configured transient exclusions such as logs, crash reports and caches continue to be honored.

## Validation

Automated coverage includes:

- inactive/failed systemd states with zero/dead MainPID;
- rejection of an inactive unit whose MainPID is still alive;
- exact UUID `.partial` cleanup without following symlinks;
- SHA-256 mismatch rejection;
- entry-count mismatch rejection;
- malformed/truncated ZIP rejection;
- the existing backup symlink, size, disk-space and maintenance safety tests.

Live production certification still requires observing a full backup with Paper disabled, verified systemd stop/start behavior, a durable local archive/hash, and the subsequent Step 4 off-site behavior.
