# PlexonPanel 3.3.0 — Backups & Maintenance

PlexonPanel 3.3.0 delivers the Host-authoritative Backups & Maintenance subsystem while preserving wire protocol 3 and the existing Paper/Host authority split.

## Highlights

- Host-owned calendar scheduling for automatic restarts and cold full restore points, including timezone-aware daily, weekly, and selected-weekday schedules.
- Player-facing maintenance countdowns, `save-all flush`, graceful systemd lifecycle control, and authenticated Paper reconnect before a restart is considered successful.
- A dedicated `FULL_RESTORE_POINT` path that runs with Paper stopped and can include worlds, player data, plugin databases, permissions/economy state, configs, and other persistent server-owned data.
- Safe archive creation outside the Minecraft server root with pre-scan/free-space checks, bounded ZIP creation, `.partial` staging, fsync, atomic promotion, SHA-256 metadata, retention, and fail-closed path/symlink protections.
- Google Drive support through Host-local `rclone`: unique staging upload, verification, preservation of the previous known-good current restore point, promotion only after success, and retry upload without recreating a cold backup.
- Owner-only restore preparation/execution with short-lived confirmation tokens, typed server-name confirmation, emergency pre-restore backup, safe staging/extraction, rollback journal, and crash-recovery gating.
- Completed Dashboard Backups & Maintenance workspace with real schedules, next-run state, current operation/progress, backup inventory, provider status, verification/retry/restore/delete actions, and recovery/offline states.
- Scheduled restart now skips an intentionally stopped server without starting it. A cold full restore point may run while the server is already stopped and preserves that stopped state.

## Matched control-plane provenance

- Paper plugin and Host Companion are released together as PlexonPanel 3.3.0.
- Matched Dashboard/Relay final merged source: `ZpkDxGames/PlexonPanel-Dashboard@bc07b2b5b850f357e21ecb470873f5b558764c58`.
- Dashboard final-main verification is GitHub Actions run `34769723268`.
- Wire protocol remains `3`; no protocol-generation migration is required.
- Google Drive/rclone credentials remain Host-local and are never exposed to the browser or Paper plugin.

## Migration

- Existing live snapshots and legacy backup metadata remain supported.
- Existing `backups.intervalMinutes` scheduling remains available for legacy live snapshots.
- New destructive maintenance schedules migrate disabled and require an explicit saved schedule before automatic downtime begins.
- Existing pairing/device grants remain subject to their immutable scopes plus local capability policy; newly required scopes may require the existing revoke/re-pair flow.

## Validation and deployment gate

The stable source must pass clean Java 25 builds/tests/Javadocs for both the Paper plugin and Host Companion, release packaging/checksum validation, and the matched Dashboard production build before publication.

Runtime certification of the actual PlexonCraft host remains a separate operational deployment gate for systemd lifecycle, real Google Drive staging/promotion/outage behavior, and destructive restore/recovery. The release manifest therefore keeps `runtimeCertification` as `NOT_EXECUTED`; do not interpret source/CI success as live-host destructive-operation certification.
