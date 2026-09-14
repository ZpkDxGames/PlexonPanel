# Manual full-backup durable job state

This document describes the Step 1 Host contract for the manual full-backup architecture. It establishes durable Host authority for the operation lifecycle without yet removing the legacy Paper command path or recurring maintenance scheduler. Those migrations are intentionally handled by later roadmap steps.

## Control contract

The existing dashboard action remains:

- action: `maintenance.full-backup.create`
- authoritative target: `HOST`
- required scope: `maintenance.run`
- successful enqueue response: `{ "jobId": "<uuid>", "state": "QUEUED" }`

The dashboard does not own an operation timer or job lifecycle. Once the Host accepts the request it writes the job to `<host data>/maintenance/active-job.json` before submitting execution to the maintenance worker. A second destructive request observes the non-terminal journal and is rejected with `BUSY` rather than creating another job.

`maintenance.status` remains the reconnect/read contract in this step. Its `currentOperation` field contains the complete durable job record. `jobStateContractVersion` is `2`. The legacy `nextFullRestorePoint` field is retained only for migration compatibility and `fullBackupScheduleDeprecated` is `true`; Step 5 removes the obsolete scheduled full-backup behavior.

## Durable job fields

The Host persists the following information for every maintenance job:

- `jobId` and operation `kind`;
- requester device ID and display name (or the local Host scheduler identity for legacy automatic work);
- `phase` and `phaseTimestamp`;
- scheduled occurrence when the operation came from the legacy scheduler;
- start, update, and completion timestamps;
- result, bounded error code, and a safe error message that never contains raw command output;
- backup ID once known;
- bounded `progressPercent`;
- whether the operation was automatic;
- whether this Host job stopped the Minecraft service;
- whether local backup verification passed;
- whether remote backup verification passed;
- whether restart recovery still requires operator verification.

Terminal jobs are appended to `maintenance/history.jsonl`. The current journal remains bounded, regular-file only, non-symlinked, and UUID validated.

## Phase model

The Step 1 journal recognizes the target manual-backup state vocabulary:

`QUEUED` → `PREFLIGHT` → `COUNTDOWN` → `FINAL_SAVE` → `STOPPING_SERVER` → `WAITING_FOR_STOP` → `ARCHIVING` → `VERIFYING_LOCAL` → `VERIFYING_REMOTE` → `STARTING_SERVER` → `VERIFYING_STARTUP` → `COMPLETED`

`DEGRADED`, `FAILED`, and `RECOVERY_REQUIRED` are explicit outcome/recovery states. The underlying full-restore-point manager still performs some archive/upload/verification work as one call in this step, so not every future phase can yet represent a long independently executing sub-stage. Steps 3 and 4 split the cold archive and Google Drive pipeline further.

## Host restart classification

On Host construction the durable journal is inspected before new work is accepted. A non-terminal job left by a previous Host process is never assumed successful.

- interruption before destructive service lifecycle work becomes terminal `FAILED` with `HOST_RESTART_INTERRUPTED`;
- interruption in an ambiguous destructive phase, or while the job records that it stopped the server, becomes `RECOVERY_REQUIRED` and blocks new destructive maintenance;
- if a locally verified backup exists, the server is no longer considered stopped by the job, and only remote verification remained incomplete, the job becomes terminal `DEGRADED` with `REMOTE_VERIFICATION_PENDING`.

This classification is deliberately conservative. It preserves service safety without inventing success from an interrupted process.

## Security boundary

The change does not widen Host authority beyond existing capabilities. The Host remains a dedicated non-root process, destructive work is serialized by the existing `ReentrantLock`, no arbitrary shell command is accepted from the browser, rclone credentials remain Host-local, and job payloads contain neither credentials nor raw command output.

## Transitional limitations

Step 1 establishes state ownership only. The current countdown/final-save transport can still depend on the Paper connection, the old recurring full-restore-point schedule still exists in compatibility mode, and startup verification still observes the Paper reconnect revision. These are not considered final architecture and are intentionally removed or replaced in later roadmap steps.
