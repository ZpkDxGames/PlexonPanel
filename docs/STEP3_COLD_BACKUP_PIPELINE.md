# Step 3 — Cold backup pipeline and local verification

Step 3 makes the Linux Host Companion authoritative for proving that Minecraft is stopped before a full-server archive is created, and it makes local archive verification a mandatory gate before any off-site upload.

## Authority model

The Paper plugin is not part of the cold-backup safety decision.

The Host uses the configured systemd service as the primary lifecycle authority. `SystemdService.stopped()` requires an `inactive` or `failed` unit and also rejects a stale `MainPID` that is still alive. A running or ambiguous unit therefore cannot enter the cold archive phase.

When the Host-local RCON command channel is enabled, a successful readiness probe is treated as a secondary signal that Minecraft is still reachable. A cold operation does not proceed while that signal contradicts the stopped state.

The existing `BooleanSupplier` constructor parameter on `FullRestorePointManager` remains only for source compatibility with current Host wiring and tests; its Paper connectivity value is deliberately ignored.

## Cold archive sequence

For a manual full restore point, the Host performs the following local sequence after the Step 2 countdown and final `save-all flush` gate:

1. stop the configured systemd unit;
2. wait until the Host proves the service stopped;
3. scan the full server tree under the configured maximum-size and entry-count policy;
4. create a ZIP in the Host-owned staging directory;
5. continuously re-check the systemd stopped state while reading source files;
6. fsync the completed staging archive;
7. atomically promote the staging file into the restore-point directory;
8. verify the promoted local ZIP;
9. only then write metadata that marks the restore point locally verified;
10. only after local verification may the rclone provider phase begin.

A server-state change during archive creation fails the job instead of continuing from a live tree.

## Local verification gate

`LocalBackupVerifier` is the required integrity gate for new local full restore points, retry uploads, and remotely fetched restore-point archives.

The verifier checks:

- the archive is a regular non-symlink file;
- SHA-256 matches the expected digest;
- the ZIP central directory is readable;
- the central-directory entry count matches metadata;
- every streamed entry has a safe relative path;
- duplicate entries are rejected;
- traversal and platform-drive syntax are rejected;
- expanded bytes remain within the configured bound;
- the streamed entry count matches metadata;
- the expanded byte count agrees with the recorded source byte count.

A newly created backup reaches `LOCAL_ARCHIVE_VERIFIED` only after these checks pass. A verification failure occurs before any rclone upload attempt.

## Interrupted archive recovery

Cold archives are first written as UUID-named `.partial` files inside the Host staging directory.

On `FullRestorePointManager` initialization, `PartialBackupRecovery` removes only bounded files matching the exact UUID `.partial` pattern. It does not recursively clean the staging directory and it does not follow symlinks. Unrelated staging files are preserved.

This allows a Host restart after an interrupted archive write to recover safely without treating a partial file as a restore point.

## Restore behavior

Full-restore and restore-recovery stop checks no longer depend on the Paper websocket connection.

Before replacing server content, the Host proves the service is stopped using the same Host authority. During restore file replacement, systemd state is re-checked. If the restore is configured to start Minecraft afterward, startup completion is based on systemd `active` plus Host-local RCON readiness when that command channel is enabled.

Remote restore-point downloads are structurally verified with the same local archive verifier before atomic promotion into the local restore-point directory.

## Failure boundaries

Failures before a successful systemd stop leave Minecraft running.

Failures after the Host has stopped Minecraft remain subject to the durable maintenance recovery policy introduced in Step 1 and the degraded/restart behavior introduced in Step 4. Step 3 does not weaken the Google Drive timeout, promotion, retry-upload, or previous-restore-point preservation rules.

No browser request can bypass the mandatory Step 2 full-backup countdown or supply filesystem paths, shell commands, rclone flags, RCON commands, or provider credentials.

## Repository certification

The automated source gate covers:

- inactive/failed systemd state with zero/dead/live `MainPID` cases;
- active/unknown systemd states failing the cold stop proof;
- valid local ZIP verification;
- hash mismatch;
- entry-count mismatch;
- truncated ZIP rejection;
- bounded interrupted `.partial` cleanup;
- symlink cleanup without following the target;
- the existing Step 1/2/4/7 regression suite on Java 25 x64 and ARM.

Repository CI is necessary but is not production certification.

## Production acceptance still required

Before Step 3 is recorded as live-certified on PlexonCraft, exercise the real Linux Host Companion with the deployed service, RCON, filesystem and rclone configuration:

1. prove a normal stopped Minecraft service passes the Host stop gate;
2. simulate or reproduce an `inactive`/`failed` service state with a still-live recorded Java `MainPID` and prove archive creation is blocked;
3. complete a real cold full-server ZIP while Paper is unavailable;
4. verify the resulting local SHA-256 and structural verification state;
5. confirm rclone does not begin before local verification succeeds;
6. interrupt a staging archive and restart the Host, then confirm only the UUID `.partial` artifact is cleaned;
7. confirm the server restarts according to the durable maintenance policy after success or a Step 4 degraded remote result;
8. verify no Paper connection event is required for cold backup creation, stop proof, restore recovery, or Host reporting.

Do not claim production certification until these live gates are recorded with the deployed Host/Paper build pair and exact commit SHA.