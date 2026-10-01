# PlexonPanel 3.5.1

PlexonPanel 3.5.1 is a focused backup observability and disk-cleanup patch for the Host Companion.

## Backup progress

- ZIP creation reports source bytes processed against the preflight total.
- Google Drive upload reports real rclone-transferred ZIP bytes, exact total bytes, percentage, and current byte rate at a bounded cadence.
- Only numeric, sanitized progress crosses the Host boundary. Raw rclone output, paths discovered in output, tokens, credentials, and configuration remain Host-local.
- Archive finalization, remote verification, canonical promotion, and VPS cleanup remain separate states so 100% transferred is not presented as fully complete too early.

## Safe local cleanup

The Host removes `restore-points/<backupId>.zip` only after the remote ZIP and metadata have been promoted, their sizes have been verified, and the promoted Google Drive ZIP's SHA-256 matches the verified local ZIP. It first persists verified off-site availability, then releases the local file and updates history to show Google Drive-only availability. An unavailable Drive hash keeps the local ZIP for retry.

An upload failure or timeout keeps the verified local ZIP for **Retry Upload**. If Google Drive succeeds but local deletion fails, the result remains remotely verified and records `SUCCESS_WITH_WARNING` / `LOCAL_CLEANUP_FAILED`; it does not falsely mark the remote backup failed.

## Compatibility

Protocol remains 3, Java remains 25, and Paper API remains 26.2. Existing identities, device grants, recovery journals, Host configuration, and `/v1` routes are preserved.
