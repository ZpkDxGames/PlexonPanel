# PlexonPanel 3.5.1

This patch adds real ZIP and Google Drive transfer progress and releases the temporary VPS ZIP after verified remote promotion.

## Matched artifacts

- `PlexonPanel-3.5.1.jar`
- `plexonpanel-host-3.5.1.jar`
- `PlexonPanel-3.5.1-examples.zip`
- `release-manifest.json`
- `SHA256SUMS.txt`
- `test-summary.txt`

Install Paper and Host artifacts as a matched pair. Preserve `plugins/PlexonPanel/`, Host identity/configuration, maintenance state, and recovery journals.

## Operational contract

- ZIP progress is derived from actual source bytes read.
- Upload progress is derived from bounded rclone one-line statistics.
- Raw provider output and credentials never enter browser events.
- The local ZIP is removed only after verified remote archive and metadata promotion.
- Upload failure retains the local ZIP for retry.
- Local cleanup failure retains the ZIP and records a warning without invalidating the verified remote copy.

Protocol 3, Java 25, Paper 26.2, and all existing identity and device-grant formats remain unchanged.
