# PlexonPanel 3.4.1 runtime-certification gates

Stable `v3.4.1` promotion is blocked until the matched Paper plugin, Host companion, Dashboard/relay, backup provider, scheduler, and restore workflow have been exercised end-to-end on the authorized PlexonCraft production-equivalent host.

Repository CI is necessary but is not runtime certification. A green unit/build result must never be recorded as a live PASS for the gates below.

## Current source-integration baseline

- Java/Paper/Host implementation merge: `069a3453005a6b50000154498f9fd69acf72be13`
- Dashboard/relay merge: `a8be1e136e92f1003380082edf6494f410c0d5e7`
- Dashboard post-merge CI: `34792944291`
- Java final source-gate CI: `34793062255`
- Protocol: `3`
- Java: `25`
- Runtime certification at source integration: `NOT_EXECUTED`

If any repository changes are merged after this document, record the exact deployed `main` commit in the acceptance evidence. Do not silently treat the implementation baseline above as the deployed release SHA.

## Required evidence for every gate

Record:

- exact Java/Paper/Host deployed commit;
- exact Dashboard/relay deployed commit and relay configuration revision;
- Paper JAR SHA-256 and Host JAR SHA-256;
- server ID and service identity, without exposing credentials or secrets;
- test operator;
- UTC start/end timestamps;
- observed result and bounded relevant logs;
- PASS / FAIL / NOT_EXECUTED status;
- rollback action and rollback result when a failure is induced;
- any deviation from the configured production topology.

Never include OAuth material, rclone config contents, private keys, access tokens, device secrets, or raw credential-bearing command output in the evidence bundle.

## 1. Upgrade and identity preservation

PASS requires all of the following:

- upgrade from the currently deployed Panel without deleting the stable server identity or device registry;
- Paper plugin starts and registers with PlexonCore correctly;
- Host companion starts under the intended non-root service identity;
- existing pairing remains valid or migrates deterministically;
- a new one-use pairing can be completed when explicitly tested;
- Dashboard connects to the correct server and does not create a duplicate server record;
- plugin restart, Host restart, Dashboard refresh, and relay interruption recover without duplicate sessions or stale action authority.

## 2. General control-plane and telemetry sanity

Verify:

- online/offline state, players, TPS, MSPT, heap, process/system CPU, and uptime are sane;
- unavailable CPU is never represented as a valid `0%` sample;
- console streaming is bounded, live, and reconnect-safe;
- chat stream preserves its origin and does not echo-loop;
- permitted actions succeed and denied/disabled capabilities fail on the authoritative server/Host side;
- remote command execution remains main-thread controlled and returns one structured result per request ID;
- duplicate/stale request replay, malformed payloads, protocol mismatch, wrong-server routing, and replaced sessions are rejected;
- multi-server isolation is verified when more than one server is configured;
- Cloudflare/relay exposure is limited to the intended control-plane surface.

## 3. Backup read bridge and permission-drift gate

This gate specifically targets the failure mode where repeated Paper saves alter ownership or access on files required by the non-root Host backup process.

PASS requires:

1. Confirm the Host service runs as its intended non-root identity.
2. Confirm the configured backup roots are readable through the supported local backup-read ACL/permission bridge.
3. Execute repeated authoritative Paper save cycles using `save-all flush` under normal server operation.
4. Re-check the same backup roots and representative world files after the saves.
5. Run `backup.preflight` through the real control path.
6. Confirm no permission repair requires broad world-writable permissions, root execution of the Host service, or manual ownership drift.
7. Confirm denied/unreadable files are surfaced as an explicit bounded preflight failure rather than an optimistic Ready state.

Record representative ownership/mode/ACL metadata before and after the test, but do not include secrets or unrelated player data.

## 4. Backup preflight and Paper↔Host save coordination

PASS requires:

- the Host requests the structured Paper save lease before a live snapshot;
- Paper performs/acknowledges the intended save coordination without Protocol 4;
- a successful preflight is only reported after the authoritative save/flush boundary and filesystem checks complete;
- failed Paper coordination is returned to the Host immediately instead of degrading into an opaque relay timeout;
- phase/status progression is observable and terminates deterministically;
- stale or duplicate coordination responses cannot complete a newer backup request.

Induce at least one safe coordination failure and verify that the Dashboard reports a structured failure with action/status/code context rather than a generic success or endless spinner.

## 5. Google Drive / rclone provider gate

When off-site backup is configured, verify the exact loaded Host configuration rather than browser-local assumptions.

PASS requires:

- provider state distinguishes `CONFIGURED_UNTESTED`, `CONNECTED`, and `DEGRADED` correctly;
- Provider Test reaches the configured remote through the fixed Host-side rclone executable and config path;
- the browser never receives rclone configuration contents or provider credentials;
- `lastTestAt` tracks the latest test attempt;
- `lastSuccessfulVerificationAt` remains the timestamp of the latest successful provider verification even after a later failed test;
- remote labels shown to the Dashboard are bounded and sanitized;
- provider command output remains bounded and secret-redacted;
- the remote canonical restore point is only promoted after staging verification succeeds.

For Google Drive, confirm the expected destination exists and is accessible using the production Host credentials without displaying those credentials in the evidence.

## 6. Live snapshot success gate

Run an actual live snapshot while the server is online.

PASS requires:

- preflight passes first;
- save coordination completes;
- snapshot phases progress through the expected bounded lifecycle;
- configured restore-point roots are included and unsafe/unconfigured external paths are not silently added;
- symlink and volatile-file policy behaves as documented;
- the local archive and metadata are created successfully;
- integrity/size verification succeeds before off-site promotion;
- inventory/status reports the resulting restore point truthfully;
- the Dashboard transitions from active progress to a confirmed success state without requiring a page reload;
- a second request cannot create an unsafe overlapping backup operation.

Record archive size, duration, job/request ID, resulting canonical filename, and SHA-256 when available.

## 7. Provider-outage and degraded-mode gate

Induce a controlled provider failure without corrupting the last known-good restore point. Examples include temporarily denying remote reachability or using an authorized reversible provider-side test condition.

PASS requires:

- the local backup result is not falsely described as remotely verified;
- provider status becomes explicit `DEGRADED` / failed state;
- the last successful verification timestamp is preserved;
- failed staging is not promoted over the canonical remote restore point;
- cleanup/rollback leaves the previous canonical restore point recoverable;
- the Dashboard retains last-confirmed data only with an explicit stale/unavailable indication;
- retrying after provider recovery succeeds without Host restart unless the configuration itself changed.

Restore normal provider access and record the recovery result.

## 8. Host restart and loaded-configuration gate

PASS requires:

- Host restart preserves server identity and device authorization;
- loaded backup/provider configuration after restart matches the intended file on disk;
- configuration changes that require restart are not falsely reported as already active;
- provider state and maintenance settings return from the running Host, not optimistic browser defaults;
- reconnect does not replay stale destructive requests;
- Paper/Host/Dashboard return to one authoritative session for the server.

## 9. Cold restore-point gate

Create the configured cold/full restore point during an authorized maintenance window with the server in the required stopped/quiesced state.

PASS requires:

- the intended configured server data is captured without transient live-write races;
- the archive completes within the configured storage/size limits;
- metadata identifies the restore point and its verification state;
- off-site promotion follows the same staging-before-canonical safety rule when enabled;
- the server can subsequently start normally from the unchanged production data before any restore drill begins.

Do not delete the known-good production copy as part of this test.

## 10. Maintenance scheduler and collision gate

Verify the scheduler that is actually authoritative in Protocol 3. `backups.intervalMinutes` remains the live-snapshot interval mechanism; do not certify removed/dead calendar fields as working features.

PASS requires:

- the configured recurring backup interval is reflected by the running Host;
- disabled scheduling performs no unattended backup action;
- due maintenance executes once, not multiple times after reconnect/restart;
- simultaneous or overlapping maintenance intents serialize safely rather than running destructive operations concurrently;
- skipped/deferred work is visible as such instead of being reported as completed;
- unattended destructive scheduling remains disabled unless explicitly intended and validated for the deployment;
- restart scheduling, if enabled for the deployment, performs the documented warning/save/stop/start lifecycle and recovers the control plane afterward.

Record at least one real scheduled execution rather than only a manual `run now` action.

## 11. Restore drill

A stable release requires an actual restore drill in an authorized maintenance window. Use a disposable copy/staging target or another explicitly approved production-equivalent method whenever possible; do not overwrite the only known-good production data.

PASS requires:

- the selected restore point is the exact intended canonical artifact;
- remote download, when used, completes to staging and is verified before replacement;
- path traversal, symlink, malformed archive, wrong-server, oversized/unsupported, or unauthorized restore inputs are rejected;
- destructive restore requires the intended high-risk capability/authorization path;
- current data is protected by the documented rollback/safety mechanism before replacement;
- restore completes without partial mixed-version data;
- Paper and Host start after restore;
- server identity/device authorization behaves according to the documented restore design;
- Dashboard reconnects to the expected server;
- rollback from an intentionally failed rehearsal is demonstrated and recorded.

Record the restored artifact SHA-256, start/end timestamps, service start result, and rollback result.

## 12. Dashboard recovery and operator-truth gate

Exercise the Backups & Maintenance workspace across success and failure cases.

PASS requires:

- Ready / Warning / Failed / Unknown / Not configured states correspond to authoritative data;
- stale last-confirmed data is visibly labeled stale and is never presented as fresh truth;
- preflight, phase, failure, provider, inventory, scheduling, and recovery diagnostics remain readable on desktop and mobile layouts;
- `ActionError` exposes only bounded safe context (`request`, `action`, `status`, `code`, approved data) and no raw stack/secret material;
- Provider shows both Last test and Last successful verification;
- browser restore/download controls preserve the 64 MiB browser download ceiling and server-side capability gates;
- Worker relay and standalone relay produce equivalent Paper-coordination failure behavior.

## 13. Failure recovery, soak, and security gate

PASS requires:

- relay interruption and reconnection recover deterministically;
- plugin and Host restarts do not leave stale sessions authorized;
- repeated backup status polling does not cause unbounded queue, DOM, or memory growth;
- at least 30 minutes of representative control-plane soak completes without busy reconnect loops or material MSPT regression against the accepted baseline;
- repository/dependency review has zero known HIGH/CRITICAL release-blocking defects for the shipped product graph;
- no credential, token, private config, absolute sensitive path, or raw stack trace is exposed through Dashboard/relay responses or evidence logs.

## Acceptance record

Use a gate table or equivalent evidence record containing at least:

| Gate | Status | Evidence / run ID | Started UTC | Ended UTC | Operator | Rollback result |
| --- | --- | --- | --- | --- | --- | --- |
| Upgrade / identity | NOT_EXECUTED |  |  |  |  |  |
| Control plane / telemetry | NOT_EXECUTED |  |  |  |  |  |
| Backup read bridge | NOT_EXECUTED |  |  |  |  |  |
| Save coordination / preflight | NOT_EXECUTED |  |  |  |  |  |
| Provider / Google Drive | NOT_EXECUTED |  |  |  |  |  |
| Live snapshot | NOT_EXECUTED |  |  |  |  |  |
| Provider outage / recovery | NOT_EXECUTED |  |  |  |  |  |
| Host restart / loaded config | NOT_EXECUTED |  |  |  |  |  |
| Cold restore point | NOT_EXECUTED |  |  |  |  |  |
| Scheduler / collision | NOT_EXECUTED |  |  |  |  |  |
| Restore drill | NOT_EXECUTED |  |  |  |  |  |
| Dashboard recovery truth | NOT_EXECUTED |  |  |  |  |  |
| Soak / security | NOT_EXECUTED |  |  |  |  |  |

## Stable-promotion rule

Do **not** create or move `v3.4.1`, publish a stable GitHub release, or label runtime certification PASS while any required gate is FAIL or NOT_EXECUTED.

After every required gate passes:

1. record exact deployed Java and Dashboard commits plus CI provenance;
2. record final artifact SHA-256 values;
3. record the completed runtime acceptance table and rollback evidence;
4. build/package from the exact accepted source commit so `release-manifest.json` records that commit;
5. verify the packaged manifest still reports Protocol 3, Java 25, the accepted Dashboard commit/CI run, and runtime certification PASS;
6. only then create immutable tag `v3.4.1` and publish the stable release artifacts.
