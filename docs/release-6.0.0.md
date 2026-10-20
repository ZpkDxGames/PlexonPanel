# PlexonPanel 6.0.0 — Full-stack durability

The Host exposes four independent preflight gates, local-first cold backups, explicit recovery resolution and additive countdown/stage status. All work uses Java 25 stable APIs. Protocol 3, Paper/Host configuration schemas 5 and maintenance settings schema 2 remain unchanged.

## Destination and recovery behavior

`backup.create` and `maintenance.full-backup.create` accept optional `destinationMode`: AUTO (default), DRIVE_AND_LOCAL or LOCAL_ONLY. AUTO uses the configured provider; local fallback requires `fullRestorePoint.allowLocalFallback=true` (default false). Explicit Drive never silently downgrades. Explicit local never uploads. All gates run independently, then readiness is enforced by recovery > source > storage > required provider. The same FullBackupSource include/exclusion/symlink policy governs the source scan and archive. Existing stricter BACKUP_SOURCE_* errors are retained alongside new gate errors. Free space requires two source copies plus 256 MiB, with overflow failing closed.

The selected destination and skipped stages survive durable job transitions and countdown restart. The real BackupStage enum has 14 entries; STOP_SERVICE is index 6. UPLOAD and REMOTE_SHA256 are the off-site entries between LOCAL_SHA256 and RESTART_RELEASE. `maintenance.status` retains legacy keys and adds indexed stage definitions, destination metadata, countdown kind/endsAt/totalSeconds and recovery/operation flags.

`backup.full.retry-upload` re-verifies the immutable local archive and uploads without acquiring the world-maintenance lock, creating a maintenance job or stopping Minecraft. Its transfer reservation rejects concurrent backup requests promptly. Legacy Java overloads retain their original local-release behavior; destination-aware operations keep the verified local copy.

`maintenance.recovery.resolve` requires active systemd service and a successful Minecraft readiness probe. Backup recovery is one click; interrupted restore recovery additionally requires the literal boolean `acknowledgeRestore=true` after operator world review. Original error codes remain in history. The restore journal is archived with resolution metadata, never rolled back on an online world. Automatic recovery reconciliation remains disabled.

## Installing the matched artifacts

CI builds `PlexonPanel-6.0.0.jar` and `plexonpanel-host-6.0.0.jar`, verifies Java class version 69, and checks SQLite JNI support on Linux x64 and ARM64. Use the checksums and exact commit recorded in the accompanying release manifest. Preserve private configuration, identities, credentials and the prior working binaries.

Install/restart the updated Host service before adopting destination-aware Dashboard controls. Use the existing instance-specific service and installation paths documented in INSTANCE_SERVICE_DEPLOYMENT.md; do not substitute a generic unit or regenerate identities. The Dashboard tolerates an older Host by disabling unsupported destination choices. A healthy old v5.3 Dashboard continues to consume the additive Host contract.

The workspace verification uses fake systemd/rclone transports and browser wire fixtures. It does not certify a live staging VPS, real Drive upload/restore, or an installed production Host. Before operational use, perform the four staging smoke cases from the attached v6 playbook: clear latched backup recovery, create a verified local ZIP with rclone unavailable, promote it without stopping Minecraft, and observe a five-minute countdown across routes. An interrupted restore always requires world review.

## Verification

The final GitHub Actions runs, test counts, matched JAR digests, Dashboard commit and browser receipts are included in the release package. The release-gates JSON pins the exact verified Dashboard candidate. Source-backed contracts are preserved; live operational acceptance remains explicitly unexecuted until the operator performs the staging smoke test.
