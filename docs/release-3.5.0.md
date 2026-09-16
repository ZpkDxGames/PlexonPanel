# PlexonPanel 3.5.0

PlexonPanel 3.5.0 adds operator-selectable 30-, 15-, 10-, and 5-minute Host-owned countdowns for **Fully Backup Now**, matching countdown controls for automatic restarts, and durable countdown recovery across Dashboard/Host reconnects.

The release also fixes remote-verification truthfulness: a Google Drive/rclone connectivity test no longer updates the timestamp reserved for a successfully verified remote backup.

The stable Host authority boundary is read-only for the live Minecraft tree. Server-tree file mutations and direct restore are not advertised as effective Host capabilities. Backup verification, retry-upload, retention/deletion, systemd lifecycle, provider diagnostics, and cold-backup creation remain supported under the existing device-scope and local-policy intersection.

## Matched artifacts

- `PlexonPanel-3.5.0.jar`
- `plexonpanel-host-3.5.0.jar`
- `PlexonPanel-3.5.0-examples.zip`
- `release-manifest.json`
- `SHA256SUMS.txt`
- `test-summary.txt`

Paper and Host artifacts must be installed as a matched pair. Preserve identities, device registries/mirrors, Host configuration, and recovery journals during upgrade.

## Compatibility

Protocol remains 3, Java remains 25, and Paper API remains 26.2. No identity reset, blanket re-pair, or automatic scope expansion is required.

## Certification

Stable publication is gated on exact Dashboard/relay provenance, x64 and ARM64 CI, and real PlexonCraft backup acceptance: selected warning delivery, `save-all flush`, stop proof, local archive verification, Google Drive promotion/verification, automatic restart/readiness, reconnect recovery, and bounded failure handling. A gate not executed must remain `NOT_EXECUTED`.
