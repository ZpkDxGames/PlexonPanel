# PlexonPanel 3.5.0 — Step 8 production certification gates

PlexonPanel 3.5.0 source integration is complete only when the matched Paper plugin, Host Companion, Dashboard/relay, Google Drive provider, systemd lifecycle, and **Fully Backup Now** workflow have been exercised end-to-end on the authorized PlexonCraft host.

Repository CI is required evidence, but it is not runtime certification. Never convert a green unit/build result into a live PASS for a gate that was not actually executed.

## 3.5.0 candidate provenance

Current matched control-plane evidence:

- Dashboard/relay `main`: `72d1f36b99e07ba1d319f18a2e574e5d7ca0a493`;
- Dashboard CI: run `35171476557` (`Dashboard and relay checks`, PASS);
- production relay deployment: run `35171476559` (PASS);
- production relay `/healthz`: version `3.5.0`, Protocol `3`, `cloudflare-worker`, commit `72d1f36b99e07ba1d319f18a2e574e5d7ca0a493` (verified 2026-09-17 UTC);
- Vercel status for the merged Dashboard commit: PASS.

Record the remaining exact values after the backend pull request merges and its required CI completes:

- backend/Paper/Host `main` commit;
- backend x64 and ARM64 CI run;
- Paper and Host JAR SHA-256 values;
- deployed Dashboard and relay identities.

Protocol remains `3` and Java remains `25`. Runtime certification remains `NOT_EXECUTED` until every required live gate below is completed. A green source build is not a substitute for deployment or production evidence.

Production relay deployment configuration is now present and the matched 3.5.0 relay identity has been verified. Keep all protected token/account values in GitHub configuration; never place them in this document.

## Final backup contract

Automatic backups and live snapshots are retired product behavior.

> Automatic backups are retired. Full backup creation is manually initiated through **Fully Backup Now** and executed by the always-on Host Companion.

The production flow to certify is:

`Dashboard`
→ **Fully Backup Now**
→ explicit confirmation
→ Host `backup.preflight`
→ durable Host job
→ operator-selected 30m / 15m / 10m / 5m warning countdown
→ Host-local delivery of the selected initial notice plus the safety boundaries that fit
→ affirmative `save-all flush`
→ Host stops the configured Minecraft systemd unit
→ Host independently proves Minecraft is stopped
→ cold full-server archive through `.partial` staging
→ local SHA-256/metadata verification
→ Google Drive/rclone staging, promotion, and final verification
→ Host automatically starts Minecraft when this workflow stopped it
→ startup/readiness verification
→ durable `COMPLETED` state.

A bounded remote/provider failure after local verification must preserve the local restore point and previous known-good remote copy, restore Minecraft availability, record `DEGRADED`, and permit **Retry Upload** without another shutdown.

Paper is not a prerequisite for this critical path. Browser refresh/disconnect never owns or cancels the job.

## Evidence required for every live gate

Record:

- exact deployed backend/Paper/Host commit;
- exact deployed Dashboard/relay commit;
- Paper and Host JAR SHA-256 values;
- target CPU architecture (`linux-x64` or `linux-arm64`);
- service identity and server ID without exposing credentials;
- operator;
- UTC start/end timestamps;
- relevant bounded request/job IDs and sanitized logs;
- PASS / FAIL / NOT_EXECUTED;
- rollback/recovery action and result for induced failures.

Never include RCON secrets, rclone config contents, OAuth material, private keys, access tokens, device secrets, or credential-bearing command output.

## Safe pre-deployment inspection

Before replacing live files, inspect the actual production host.

```bash
systemctl status plexonpanel-host.service --no-pager
systemctl status plexoncraft.service --no-pager

systemctl show plexonpanel-host.service \
  -p User -p Group -p ActiveState -p SubState -p MainPID
systemctl show plexoncraft.service \
  -p ActiveState -p SubState -p MainPID

sudo find /opt/plexonpanel-host -maxdepth 1 -type f \
  -name 'plexonpanel-host-*.jar' -exec sha256sum {} \;
sudo find /opt/plexoncraft/server/plugins -maxdepth 1 -type f \
  -name 'PlexonPanel*.jar' -exec sha256sum {} \;

sudo test -r /etc/plexonpanel-host/host-config.json
sudo jq 'keys' /etc/plexonpanel-host/host-config.json

journalctl -u plexonpanel-host.service -n 100 --no-pager
journalctl -u plexoncraft.service -n 100 --no-pager
```

Use `jq 'keys'` only as a safe structural check. Do not print the entire Host configuration into an acceptance transcript.

Do **not** recursively `chown` the Minecraft server, use `chmod 777`, run the Host as root, disclose rclone/RCON secrets, or delete known-good backup data.

## Gate 1 — Host independence

PASS requires:

1. Minecraft starts online and the Host is connected/authenticated.
2. Dashboard is paired/authenticated.
3. Stop Minecraft while leaving `plexonpanel-host.service` running.
4. Host remains connected and authorized.
5. Dashboard remains authenticated.
6. Host-owned journald console history remains accessible.
7. Host backup/provider/systemd actions remain available while Paper is offline.
8. Start Minecraft again and verify one authoritative Paper + Host session returns.

Useful service proof:

```bash
systemctl show plexonpanel-host.service -p ActiveState -p SubState -p MainPID
systemctl show plexoncraft.service -p ActiveState -p SubState -p MainPID
```

## Gate 2 — Warning channel and durable countdown

Run authorized **Fully Backup Now** operations covering each selectable initial duration: 30, 15, 10, and 5 minutes.

PASS requires:

- the selected initial warning is delivered immediately;
- 30 minutes retains 15m / 1m / 30s / 15s / 5s;
- 15 minutes retains 1m / 30s / 15s / 5s;
- 10 minutes retains 1m / 30s / 15s / 5s;
- 5 minutes retains 1m / 30s / 15s / 5s;
- warnings are delivered through the Host-local command channel/RCON, not Paper backup coordination;
- refresh/reconnect reconstructs the Host-owned initial duration, warning plan, deadline, consumed boundaries, and remaining time rather than creating a browser timer;
- a controlled Host restart during a shorter countdown resumes that selected plan and does not revert to 30 minutes.

## Gate 3 — Final save boundary

PASS requires:

- Host sends `save-all flush` through the configured local command channel;
- an affirmative response is required;
- blank, failed, timed-out, or invalid responses prevent the destructive stop boundary;
- the failure is bounded and visible as a Host-owned job failure.

Do not expose the command-channel secret while testing.

## Gate 4 — Cold stop proof

PASS requires:

- Host requests stop of the configured Minecraft systemd unit;
- the unit reaches inactive/stopped state;
- `MainPID` is zero/not alive;
- archive work does not begin before both conditions are proven.

Observe without changing permissions:

```bash
systemctl show plexoncraft.service \
  -p ActiveState -p SubState -p MainPID -p Result
```

## Gate 5 — Local cold archive

PASS requires:

- unique `.partial` staging during archive creation;
- configured persistent server content is present;
- transient/secret Host paths are excluded;
- symlink/traversal protections hold;
- final ZIP is readable;
- SHA-256 and metadata match;
- backup inventory reports the verified local restore point.

Record the backup ID, archive size, duration, and final archive SHA-256. Do not include unrelated player data in evidence.

## Gate 6 — Google Drive / rclone

Use the Host-owned Provider Test and the real full-backup upload path.

PASS requires:

- provider is configured and reaches the intended remote using the fixed Host-side rclone executable/config;
- browser/relay never receives rclone credentials/config contents;
- staging upload completes;
- staging verification succeeds before promotion;
- canonical promotion succeeds;
- promoted object is verified;
- previous known-good remote data remains protected until the new object is verified;
- `lastTestAt` and `lastSuccessfulVerificationAt` remain truthful and distinct.

The acceptance record may name the configured remote label/path, but must not contain OAuth/config secrets.

## Gate 7 — Automatic restart and readiness

PASS requires:

- if the manual full-backup workflow stopped Minecraft, it starts Minecraft again automatically;
- readiness verification succeeds;
- Dashboard reflects the service online again;
- legacy serialized `restartAfter: false` cannot suppress restart;
- an upload/provider failure after local verification still restores Minecraft availability.

Proof:

```bash
systemctl show plexoncraft.service \
  -p ActiveState -p SubState -p MainPID -p Result
```

## Gate 8 — Dashboard reconnect and Paper independence

During a real backup:

1. close or disconnect the Dashboard;
2. reopen/reconnect it;
3. verify the same durable Host job ID and current phase are recovered;
4. ensure stale progress from another job ID is ignored;
5. verify the operation continues while Paper is offline during stopped-server phases;
6. verify Host-owned console history remains available while Paper is offline.

## Gate 9 — Controlled degraded remote failure and Retry Upload

In a safe reversible test, force a provider/network failure **after a valid local archive exists**.

PASS requires:

- local archive stays verified;
- previous known-good remote object remains protected;
- Minecraft returns online;
- job becomes `DEGRADED` / retryable rather than falsely complete;
- Dashboard exposes **Retry Upload**;
- restore provider access;
- Retry Upload succeeds using the existing local archive;
- no second Minecraft shutdown occurs.

If destructive fault injection is not authorized, this may remain the only explicitly documented `NOT_EXECUTED` destructive gate. Do not mark it PASS from mocks alone.

## Gate 10 — Recovery and failure matrix

At minimum document/test the production behavior for:

- duplicate **Fully Backup Now** click;
- Dashboard disconnect/refresh;
- Host restart during countdown;
- Host restart after Minecraft may have stopped;
- command channel unavailable/auth failure;
- final save negative/blank/timed out;
- systemd permission denied;
- stop timeout or Java process still alive;
- low local disk;
- backup-path permission error;
- symlink/traversal rejection;
- archive/local-verification failure;
- rclone missing/config missing;
- Drive auth/network/staging/promotion/verification failure;
- Retry Upload success;
- Minecraft restart failure/readiness timeout;
- authorization mirror while Paper is offline;
- journald history while Paper is offline.

Ambiguous destructive state must fail closed into durable recovery handling; do not clear recovery state merely to make the Dashboard green.

## Deployment identity gate

Dashboard and relay are one release unit even though Vercel and Cloudflare deploy separately.

After deployment require:

```text
Dashboard GET /api/build gitCommit == accepted Dashboard main SHA
Relay GET /healthz gitCommit       == accepted Dashboard main SHA
Protocol                           == 3 on both
Relay runtimeKind                  == cloudflare-worker
```

Settings → Diagnostics must report the control-plane build as matched. `Unverified` or `Mismatch` is not a production PASS.

The production relay workflow requires:

```text
GitHub Actions secret:   CLOUDFLARE_API_TOKEN
GitHub Actions secret:   CLOUDFLARE_ACCOUNT_ID
GitHub Actions variable: PRODUCTION_RELAY_URL
```

`PRODUCTION_RELAY_URL` must be the exact public HTTPS relay origin used by the Vercel production environment's `NEXT_PUBLIC_PLEXON_RELAY_URL`.

## Acceptance table

| Gate | Status | Evidence / request/job ID | Started UTC | Ended UTC | Operator | Recovery / rollback |
| --- | --- | --- | --- | --- | --- | --- |
| Host independence | NOT_EXECUTED |  |  |  |  |  |
| Warning channel / countdown | NOT_EXECUTED |  |  |  |  |  |
| Final save boundary | NOT_EXECUTED |  |  |  |  |  |
| Cold stop proof | NOT_EXECUTED |  |  |  |  |  |
| Local cold archive | NOT_EXECUTED |  |  |  |  |  |
| Google Drive / rclone | NOT_EXECUTED |  |  |  |  |  |
| Automatic restart / readiness | NOT_EXECUTED |  |  |  |  |  |
| Dashboard reconnect / Paper independence | NOT_EXECUTED |  |  |  |  |  |
| Degraded remote failure / Retry Upload | NOT_EXECUTED |  |  |  |  |  |
| Recovery / failure matrix | NOT_EXECUTED |  |  |  |  |  |
| Dashboard + relay deployed identity | NOT_EXECUTED |  |  |  |  |  |

## Stable-promotion rule

Do **not** create or move `v3.5.0`, publish a stable GitHub release, or mark runtime certification PASS while a required production gate is FAIL or NOT_EXECUTED, except that an explicitly unauthorized destructive degraded-provider fault-injection gate may remain the sole documented `NOT_EXECUTED` exception if the release owner accepts that limitation.

After the required gates pass:

1. record exact deployed backend and Dashboard commits plus CI/deployment provenance;
2. record final Paper and Host JAR SHA-256 values;
3. record the completed acceptance table and recovery evidence;
4. package from the exact accepted source commit so `release-manifest.json` names that commit;
5. update the manifest to runtime certification PASS only from real evidence;
6. verify Protocol 3, Java 25, accepted Dashboard SHA/CI, architecture support, and checksums;
7. only then create immutable tag `v3.5.0` and publish stable release artifacts.

Until then, source may be repository-green, but production certification remains `NOT_EXECUTED`.
