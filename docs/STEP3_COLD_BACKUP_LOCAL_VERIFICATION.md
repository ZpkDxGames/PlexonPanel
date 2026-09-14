# Step 3 — Cold Backup Pipeline and Local Verification

This document records the Host-side safety contract for manual full restore points after the maintenance countdown and final `save-all flush` complete.

## Authority

The Linux PlexonPanel Host Companion is authoritative for the cold-backup boundary. Paper websocket connectivity is not a stop proof and is not required for archive creation.

A full archive may begin only after the configured systemd unit is proven inactive or failed and its `MainPID` is zero or no longer alive. When the Host-local RCON channel is enabled, RCON becoming unreachable is used as an additional secondary shutdown signal. It never replaces the systemd/process proof.

During archive traversal the Host re-checks systemd/process state. If the service becomes active again, the operation fails with `SERVER_STATE_CHANGED_DURING_BACKUP` rather than continuing against mutable Minecraft files.

## Pre-shutdown checks

The manual full-backup preflight scans the configured Minecraft server root before the destructive stop boundary where possible. It enforces:

- source entry and total-size limits;
- configured maximum backup size;
- sufficient usable space in the Host backup volume;
- a writable, non-symlink backup staging area outside `serverRoot`;
- configured provider/rclone prerequisites required by the current full-backup policy.

Low-disk, oversized-source, unsafe-symlink, and unavailable-staging failures should therefore fail before Minecraft is stopped whenever they can be detected in preflight.

## Included data

The full restore point walks the configured Minecraft server root rather than maintaining a small allowlist. Persistent reconstruction data is retained unless explicitly excluded, including:

- worlds and player data;
- plugin JARs and plugin configuration;
- plugin databases such as SQLite/DB files that live under the server root;
- permission and economy state stored under the server root;
- datapacks;
- Paper/Minecraft/server configuration required to reconstruct the server.

The archive walker does not follow symbolic links.

## Excluded data

Default maintenance settings exclude known transient/noise paths:

- `logs`;
- `crash-reports`;
- `cache`;
- `.cache`.

Operators may add bounded relative exclusions through Host-owned maintenance settings. Absolute paths, traversal (`..`), backslashes, and oversized exclusion lists are rejected.

The archive implementation also excludes internal `.partial` and restore/rollback staging artifacts. The backup destination is required to be outside `serverRoot`, so Host state, Host credentials, rclone configuration, the backup repository itself, and other Host-side secrets are not recursively captured through the Minecraft tree.

## Archive staging and promotion

Cold archives are written to a UUID-named `.partial` file in the Host backup staging directory. Creation enforces entry-count, input-size, output-size, path-traversal, and symlink limits.

Before promotion, the Host forces the staging file to disk and re-proves the stopped-server condition. The `.partial` file is then atomically promoted to the restore-point directory. `.partial` files are never listed as valid restore points because inventory is metadata-driven and only promoted archives receive restore-point metadata.

On Host startup, bounded UUID-shaped `.partial` artifacts left by an interrupted archive are removed without following symlinks. Unrelated staging files are not deleted.

## Local verification gate

Remote upload is not eligible immediately after ZIP creation. The promoted local archive must first pass all local checks:

1. SHA-256 is calculated from the promoted archive.
2. The ZIP central directory must be readable.
3. The central-directory entry count must match the archived entry count.
4. Every ZIP entry name is revalidated against traversal/absolute/control-character rules.
5. The ZIP stream is read through so structural corruption is detected.
6. Expanded bytes must remain within the configured maximum and match the source bytes recorded during archive creation.
7. Restore-point metadata containing the SHA-256, archive size, source size, entry count, timestamps, and verification state is written atomically.

Any local verification failure aborts the full-backup operation before the remote-upload block is entered.

The same structural/hash/expanded-size verification is reused when an existing local restore point is explicitly verified and when a remote restore point is fetched back to local storage.

## Retention

Retention operates only after a new local restore point has been promoted and verified. The configured modes are:

- `SINGLE_CURRENT`: retain one non-emergency restore point;
- `ROTATING`: retain the configured bounded count.

Emergency pre-restore points are not removed by normal retention. Because retention runs after successful verification, the Host does not delete the previous verified restore point merely to make room for an unverified replacement.

## Failure and recovery semantics

Before systemd stop, failures are normal failed jobs and Minecraft remains online. After the Host enters the destructive stop boundary, failures are handled by maintenance recovery policy and availability recovery attempts to restart Minecraft when safe.

An interrupted `.partial` archive is not a restore point. An interrupted restore/replacement journal remains a separate `RECOVERY_REQUIRED` condition and must be resolved before further destructive maintenance proceeds.

Local verification failure prevents remote upload. Remote upload/verification failure does not invalidate the already verified local restore point; the later remote-failure policy can expose the degraded/retryable state independently.

## Step 3 test coverage

Repository tests cover:

- inactive/failed systemd state with zero/dead `MainPID`;
- inactive systemd state with a still-live `MainPID` failing closed;
- stop-timeout failure classification;
- interrupted UUID `.partial` cleanup and non-following symlink cleanup;
- server-root symlink rejection;
- SHA mismatch, ZIP entry-count mismatch, malformed/truncated ZIPs, and expanded-size verification.

Live production certification must still verify the real systemd unit, real PID lifecycle, and real Host-local RCON shutdown signal on PlexonCraft before this behavior is promoted as runtime-certified.
