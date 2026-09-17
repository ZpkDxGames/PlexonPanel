# PlexonPanel 3.5.0 — durable backup countdowns

PlexonPanel 3.5.0 is the matched Paper/Host release for selectable Host-owned backup and restart countdowns, truthful Google Drive verification state, and a read-only stable Host boundary. It remains on signed Protocol 3 and Java 25 for Paper 26.2.

## Selectable full-backup countdown

- `maintenance.full-backup.create` accepts only `countdownSeconds` values `1800`, `900`, `600`, or `300`; omission safely defaults to 30 minutes.
- Each selection produces a bounded warning plan. The selected duration is announced immediately, followed by the established safety boundaries that fit within it.
- The Host persists the complete warning plan beside the deadline and consumed warnings. A Host reconnect/restart during countdown resumes the selected plan rather than silently reverting to 30 minutes.
- `maintenance.status` contract version 4 reports `countdownInitialSeconds` and `countdownWarningSeconds` as Host-owned state.
- The critical path remains warning → affirmative `save-all flush` → proven systemd stop → cold archive → local verification → Google Drive staging/promotion/verification → automatic start/readiness.

## Restart scheduling

Restart-only schedules continue to use Host-persisted warning arrays and now share the Dashboard's 30/15/10/5-minute preset experience. Restart scheduling remains independent from manual full backups and cannot create one implicitly.

## Backup correctness

`provider.test` now records only connectivity-test state. It no longer advances `lastSuccessfulVerificationAt`; that field changes only after a real promoted remote backup has been verified. A verified local archive remains eligible for Retry Upload after bounded remote failure without another Minecraft shutdown.

## Stable read-only Host boundary

The stable systemd example mounts the Minecraft `serverRoot` read-only and removes the shared server-write group. Host effective capabilities force `files.write`, `files.create`, `files.rename`, `files.delete`, `files.upload`, and `backup.restore` off even when legacy configuration keys remain true. Backup/list/read/download/lifecycle/provider operations remain available under their existing scope and local-policy intersections.

This resolves the source-side authority conflict without granting the always-on, network-reachable Host write access to the live server tree. Live acceptance must still prove the root-owned local backup-read bridge, Host preflight, and full end-to-end backup path.

## Compatibility

- Protocol stays at `3`; routes stay under `/v1`.
- Existing identities and paired credentials remain valid and gain no scopes automatically.
- Paper and Host JARs must be upgraded together.
- Automatic full backups and live snapshots remain retired.

## Release gate

CI must pass on Ubuntu 24.04 x64 and ARM64. Stable publication additionally requires the matched Dashboard/relay revision and live PlexonCraft systemd/RCON/rclone acceptance. Do not infer production certification from repository tests.
