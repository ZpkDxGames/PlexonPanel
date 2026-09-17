# Step 4 — Google Drive upload and failure policy

This step keeps Google Drive authority entirely in the Linux Host Companion. The browser may request a manual full backup or retry an existing verified local backup, but it never supplies the rclone executable, rclone flags, credentials, or remote path.

## Host-local provider contract

The active Host configuration owns `backups.rcloneExecutable`, `backups.rcloneRemote`, and `backups.rcloneConfig`. The executable remains fixed to `/usr/bin/rclone`; the configured remote is validated by `HostConfig`; the rclone configuration path must be absolute and remains Host-local. Token-based providers atomically rewrite this file during refresh, so deploy it in a dedicated Host-owned directory such as `/var/lib/plexonpanel-host/rclone/`, not beside root-owned Host policy under `/etc/plexonpanel-host`. Provider configuration changes take effect when the Host process loads the updated Host configuration.

Raw rclone stdout/stderr is never forwarded to the dashboard. Command output is bounded and redacted, and operational failures use stable error codes instead of provider output.

## Preflight before maintenance

A full restore-point request now enters `PREFLIGHT` before any countdown or Minecraft shutdown. The Host fails the job before countdown when any required check fails:

- the rclone provider is configured;
- `/usr/bin/rclone` exists, is executable, is not a symlink, and matches the fixed executable contract;
- the Host-local rclone config is an absolute, readable regular file and is not a symlink;
- the backup base and staging directories are writable, real directories and not symlinks;
- the server tree fits the configured maximum backup size and entry limits;
- local free space can accommodate the scanned source size plus bounded archive headroom;
- a short, non-destructive provider connectivity test succeeds.

There is no implicit local-only fallback when this preflight fails.

## Successful remote promotion

After the server is stopped and the local ZIP has been atomically promoted and SHA-256 verified, the Host performs the off-site phase inside one `uploadTimeoutSeconds` safety boundary:

1. upload the ZIP to a unique staging object;
2. verify staged ZIP size against the local archive;
3. upload metadata to its unique staging object;
4. verify staged metadata size;
5. preserve any current canonical ZIP/metadata as job-specific previous objects;
6. copy staged ZIP to the canonical restore-point name and verify its size;
7. copy staged metadata to the canonical metadata name and verify its size;
8. only after both canonical objects verify, prune the temporary staging/previous objects.

Remote steps use bounded retries inside the same total timeout. A retry never extends the configured maximum offline upload duration.

The local SHA-256 remains the authoritative local integrity proof. The current rclone promotion path verifies remote object sizes; it does not expose credentials or raw provider hashes to the browser.

## Drive outage and degraded completion

A remote failure after local verification does not invalidate or delete the local restore point. `FullRestorePointManager` records the local-only result with an explicit off-site error code.

If this maintenance run stopped Minecraft, a degraded off-site result always starts Minecraft again after the bounded remote phase, even when the normal `restartAfter` preference is disabled. That preference may preserve an intentionally stopped server only after a successful off-site result; it cannot allow a Drive outage to strand PlexonCraft offline.

The durable maintenance result is `DEGRADED`, not `SUCCESS` and not a generic failed-backup state. The completion payload includes the backup ID, local/off-site availability, error code, `retryUploadAvailable`, and whether a safety restart overrode the normal restart preference so reconnecting dashboards can present the recovery action accurately.

The promotion sequence preserves the previous verified remote restore point before canonical replacement. If canonical promotion fails, the Host attempts to restore the previous canonical pair from staging and leaves recovery material intact when cleanup cannot safely complete within the deadline.

## Retry Upload

`backup.full.retry-upload` operates from the Host-owned, SHA-256-verified local archive. It does not stop Minecraft again and reuses the same staging, verification, preservation, promotion, and cleanup rules. On success, the backup metadata is rewritten as remotely verified.

The action is safe to repeat: each invocation uses a unique job identifier and does not treat browser-supplied paths or rclone arguments as authoritative.

## Failure codes and observability

Expected provider-related states include `RCLONE_UNAVAILABLE`, `RCLONE_CONFIG_INVALID`, `RCLONE_EXECUTABLE_UNAVAILABLE`, `RCLONE_CONFIG_UNREADABLE`, `RCLONE_TEST_FAILED`, `RCLONE_COMMAND_FAILED`, `RCLONE_UPLOAD_TIMEOUT`, `RCLONE_UPLOAD_INTERRUPTED`, `REMOTE_VERIFY_FAILED`, and `REMOTE_PROMOTION_FAILED`.

Maintenance/provider events remain sanitized. Backup archive progress continues through the existing Host event channel; Step 4 additionally exposes `PREFLIGHT`, `PREFLIGHT_COMPLETE`, off-site verification state, degraded completion, retry availability, and the degraded safety-restart decision without forwarding raw rclone output.

Remote transfer observability uses a separate `UPLOADING_REMOTE` event. It never treats local archive bytes as already uploaded: the event starts at `bytesUploaded = 0` with the verified ZIP size as `totalBytes`, and advances to that ZIP size only after the remote upload/promotion phase has returned verified. `progress` is therefore emitted only as a reliable coarse 0-to-100% milestone rather than fabricated streaming precision. The event exposes only the fixed provider label `RCLONE` and a sanitized provider state such as `UPLOADING` or `VERIFIED_REMOTE`; rclone stdout/stderr, tokens, credentials, arbitrary remote arguments, and provider secrets are not forwarded. Retry Upload uses the same progress contract.

## Migration and operations

Before enabling manual full backups in production:

1. install rclone at `/usr/bin/rclone`;
2. place the rclone configuration in a dedicated Host-owned, non-browser-accessible absolute path whose parent permits rclone's atomic token refresh;
3. set a validated `backups.rcloneRemote` in Host configuration;
4. ensure the backup directory is outside the Minecraft server root and writable by the Host service account;
5. restart/reload the Host process according to the deployment procedure so it loads the intended Host configuration;
6. use the provider test/preflight before initiating maintenance;
7. retain the verified local restore point whenever an off-site phase is degraded and use `Retry Upload` after connectivity is restored.

Do not grant root execution, unrestricted sudo, recursive ownership changes, or broad filesystem permissions to make rclone or backup storage work.

## Step 4 certification

The automated suite covers durable degraded state, bounded upload timeout, provider failure without credential/output leakage, interrupted upload, transient retry success, retry after an earlier provider outage, promotion rollback, local disk-headroom calculations, and sanitized truthful remote-progress semantics. Production certification still requires an actual Host/rclone/Google Drive run to prove provider authentication, real upload behavior, canonical verification, retry while Minecraft is online, and restart behavior under a simulated Drive outage.
