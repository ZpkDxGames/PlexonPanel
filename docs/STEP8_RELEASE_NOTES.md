# PlexonPanel — Step 8 Status Reconciliation & Manual Full Backup Finalization

Step 8 finalizes the Host-authoritative manual full-backup contract without redesigning the Step 1–7 architecture.

## Final product contract

Automatic backups are retired. Full backup creation is manually initiated through **Fully Backup Now** in the matched Dashboard and executed by the always-on Host Companion.

The Host owns the entire critical path:

1. authoritative preflight;
2. durable maintenance job state;
3. mandatory 30-minute warning countdown with 30m / 15m / 1m / 30s / 15s / 5s boundaries;
4. Host-local RCON/player notices;
5. affirmative `save-all flush` requirement;
6. systemd stop and independent stopped-service proof;
7. cold full-server archive creation through `.partial` staging;
8. local archive/SHA-256/metadata verification;
9. Google Drive/rclone staging, promotion and final verification;
10. automatic Minecraft service restart and readiness verification.

Paper is not required for this backup critical path.

## Step 8 backend changes

- Legacy `restartAfter: false` is normalized for rolling-upgrade compatibility.
- The manual full-backup orchestration path now starts Minecraft whenever that operation stopped it; serialized `restartAfter` cannot suppress availability recovery.
- `maintenance.status` job-state contract version 3 exposes durable countdown data while a job is in `COUNTDOWN`:
  - `countdownDeadline`;
  - `countdownRemainingSeconds`;
  - `countdownWarningsSent`;
  - `countdownState`.
- Invalid/missing durable countdown state is reported unavailable rather than replaced with an invented timer.
- The existing degraded provider policy remains: a verified local backup survives bounded remote failure, the previous known-good remote copy stays protected, Minecraft availability is restored, and Retry Upload reuses the local archive without another shutdown.

## Preserved Step 1–7 architecture

Step 8 preserves:

- Host durable job/recovery state;
- Host-owned RCON maintenance channel;
- cold backup and local verification safety;
- Google Drive/rclone staged promotion and retry policy;
- removal of Paper backup coordination and recurring backup execution;
- durable Host authorization mirror;
- Host/journald console history authority.

## Release gating

The canonical build matrix must pass on both Linux x64 and Linux ARM64 and produce the matched Paper/Host release pair.

Source/CI success is not production certification. The real VPS gates—deployed artifact hashes, Host/Minecraft service state, RCON, rclone/Google Drive, real **Fully Backup Now**, browser reconnect and controlled degraded/retry behavior—must be recorded separately. Any gate not actually executed must remain `NOT_EXECUTED`.
