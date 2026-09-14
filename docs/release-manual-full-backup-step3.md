# Manual Full Backup Architecture — Step 3 Release Note

Step 3 hardens Host-owned cold full backups and local verification.

- Archive creation now requires Host-proven stopped state from systemd plus `MainPID` liveness, with Host-local RCON-down used as a secondary signal when enabled.
- Paper websocket connectivity is no longer part of cold-backup or restore stop authority.
- UUID `.partial` staging files are cleaned safely after interrupted Host archive creation and are never treated as valid restore points.
- Promoted ZIP archives must pass SHA-256, central-directory, entry-count, entry-name, readability, and expanded-size verification before remote upload can begin.
- Existing restore-point verification and remote-fetch verification reuse the stronger local archive verifier.
- Preflight continues to reject unsafe symlinks, oversized sources, insufficient disk, invalid staging, and provider failures before destructive shutdown when detectable.
- Tests cover live `MainPID` rejection, stop-timeout classification, interrupted partial cleanup, symlink rejection, and local verification failures.
- `docs/STEP3_COLD_BACKUP_LOCAL_VERIFICATION.md` documents the backup inclusion/exclusion, retention, staging, verification, and recovery contract.

Production runtime certification remains separate: the real PlexonCraft systemd/PID lifecycle and Host-local RCON shutdown behavior still require live acceptance on the server.
