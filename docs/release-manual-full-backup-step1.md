# Manual full-backup architecture — Step 1

PlexonPanel Host now owns a versioned, durable maintenance-job journal for manual full-backup requests. Jobs are persisted as `QUEUED` before worker execution, retain requester identity, phase timestamps, progress, backup verification and server-recovery flags, and remain queryable through `maintenance.status` after dashboard reconnects.

Interrupted Host processes are classified deterministically: pre-destructive interruption fails safely, ambiguous destructive interruption becomes `RECOVERY_REQUIRED`, and a verified local backup waiting only for off-site verification may become `DEGRADED`. Duplicate destructive requests remain serialized and return `BUSY` while another non-terminal Host job exists.

This is a state/control-plane foundation only. Paper command transport and the legacy recurring full-backup scheduler remain temporarily for migration compatibility and are removed in later roadmap steps.
