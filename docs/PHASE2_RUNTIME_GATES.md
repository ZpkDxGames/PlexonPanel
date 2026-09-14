# PlexonPanel 3.4.1 — Step 8 production certification gates

PlexonPanel 3.4.1 source integration is complete only when the matched Paper plugin, Host Companion, Dashboard/relay, Google Drive provider, systemd lifecycle, and **Fully Backup Now** workflow have been exercised end-to-end on the authorized PlexonCraft host.

Repository CI is required evidence, but it is not runtime certification. Never convert a green unit/build result into a live PASS for a gate that was not actually executed.

## Step 8 accepted source baseline

At this Step 8 documentation refresh:

- backend/Paper/Host `main` contains the Step 8 merge `3a00bc75f8ad0fb2a953845fee4efa6e5d065d55`;
- Dashboard/relay accepted `main` is `c051d59010ec1d09f6a5a624246a6b108b85ae12`;
- Dashboard final-main CI run `34905567114` passed application checks, Worker/standalone relay tests, both relay smoke suites, standalone packaging, and the production Next.js build;
- backend candidate CI run `34905789066` passed on Linux x64 and Linux ARM64;
- the candidate PR merge ref `a438185b0719c497d8d7b4f4edce8d0cdfef78f6` and backend merge `3a00bc75f8ad0fb2a953845fee4efa6e5d065d55` share Git tree `6af00a2bcf4b72214c87133b14117b78ac7f2173`;
- Paper JAR SHA-256 from the matched x64/ARM64 candidate artifacts is `84f3189212aa181dce651cd83e05e23c826246661fb44bfecedbec954df7d219`;
- Host JAR SHA-256 is `8516ad2d68e090bb3672fbd39050bceb1a0df325a5fa7776f277982dcea375e5`;
- protocol remains `3` and Java remains `25`;
- runtime certification remains `NOT_EXECUTED` until the live gates below are completed.

The GitHub production-relay workflow has not deployed this accepted dashboard revision: source validation passed, but deployment configuration was absent (`CLOUDFLARE_API_TOKEN`; the workflow environment also showed `CLOUDFLARE_ACCOUNT_ID` and `PRODUCTION_RELAY_URL` unset). Vercel production identity also remains a separate deployment check.

If any repository changes after this document, the acceptance record must use the exact deployed `main` commit. Do not silently treat the source baseline above as the final deployed SHA.

## Final backup contract

Automatic backups and live snapshots are retired product behavior.

> Automatic backups are retired. Full backup creation is manually initiated through **Fully Backup Now** and executed by the always-on Host Companion.

The production flow to certify is:

`Dashboard`
→ **Fully Backup Now**
→ explicit confirmation
→ Host `backup.preflight`
→ durable Host job
→ mandatory 30-minute warning countdown
→ Host-local warning delivery at 30m / 15m / 1m / 30s / 15s / 5s
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

Run an authorized **Fully Backup Now** operation and observe the real countdown.

PASS requires warnings at:

- 30 minutes;
- 15 minutes;
- 1 minute;
- 30 seconds;
- 15 seconds;
- 5 seconds.

Confirm warnings are delivered through the Host-local command channel/RCON, not Paper backup coordination. Refresh/reconnect the Dashboard during countdown and verify it reconstructs the Host-owned deadline/remaining state rather than creating a browser timer.

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

Do **not** create or move `v3.4.1`, publish a stable GitHub release, or mark runtime certification PASS while a required production gate is FAIL or NOT_EXECUTED, except that an explicitly unauthorized destructive degraded-provider fault-injection gate may remain the sole documented `NOT_EXECUTED` exception if the release owner accepts that limitation.

After the required gates pass:

1. record exact deployed backend and Dashboard commits plus CI/deployment provenance;
2. record final Paper and Host JAR SHA-256 values;
3. record the completed acceptance table and recovery evidence;
4. package from the exact accepted source commit so `release-manifest.json` names that commit;
5. update the manifest to runtime certification PASS only from real evidence;
6. verify Protocol 3, Java 25, accepted Dashboard SHA/CI, architecture support, and checksums;
7. only then create immutable tag `v3.4.1` and publish stable release artifacts.

Until then, source may be repository-green, but production certification remains `NOT_EXECUTED`.