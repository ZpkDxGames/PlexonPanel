# Bedrock mutation capability staging

The Bedrock Host and relay expose read-only monitoring. The Java 25 classes under `bedrock-host/src/main/java/io/github/zpkdxgames/plexonpanel/bedrock/operations/` implement later capability behavior through a typed Docker adapter and deterministic fixtures. The adapter is inaccessible through the read-only broker. Connecting any capability to the broker or Dashboard requires its own review and authorization tests.

| Capability | Fixture coverage | Activation condition |
| --- | --- | --- |
| Lifecycle | Start, clean stop, restart, duplicate requests, cooldown, replaced container, dirty stop and unknown dispatch outcome | Bind the exact container identity and launch to reviewed signed requests and an audit trail. Verify the actual image's graceful shutdown and Bedrock-ready evidence. |
| Console | Fixed `list` and `say` lines, bounded input, exclusive ownership policy, ambiguous writes and shared operation lock | Verify TTY, `OpenStdin`, `StdinOnce`, and this image's entrypoint in isolated ARM64 staging. Record acknowledgement only after observing a correlated Bedrock response. |
| Player administration | Separate switch for bounded allowlist and operator commands, complete generation-bound roster evidence, unsigned 64-bit XUID and expiry | Verify syntax and outcomes on the pinned Bedrock build, including names, allowlist persistence and operator resolution. Keep XUID-bearing data private. |
| Cold backups | Player-disconnection approval, verified shutdown, fixed scope, capacity check, atomic bundle, SHA-256, retention and durable restart recovery | Validate against a disposable LevelDB world, including post-restart gameplay. Bind this capability to its own reviewed approval and audit route. |
| Restore and settings | Pending | Review and test restore provenance, traversal, rollback, interruption recovery and per-setting permissions separately. |

## Container lifecycle and stdin

The adapter validates the exact `tonimbedrock` name, Docker image ID, immutable container ID, run start time, restart policy, and writable `/data` bind source before dispatch. The only write operations are start, bounded SIGTERM stop, and fixed stdin attach. The attach path verifies persistent stdin and rechecks the run after the Docker upgrade before sending input. Test on an isolated engine with exclusive mutation ownership because the Docker API cannot make start and stop conditional on a launch timestamp. Start and restart require independent process-state evidence and a new start timestamp. A successful start can report `RUNNING_UNVERIFIED` until a Bedrock-specific readiness check succeeds. `unless-stopped` preserves an intentional stop across daemon restarts until the container is explicitly started.

The image documentation supports attach when stdin and a TTY are enabled. Its upstream PID 1 wrapper invokes `entrypoint-demoter --stdin-on-term stop`. These are properties of the inspected upstream source and need confirmation against the actual deployed image digest. The previously attempted `send-command` helper failed with `/proc/*/exe: Permission denied` under Box64. Use the local attach path in isolated staging with one console owner and no concurrent human attach session. Docker permits outside clients to attach, so the operation lock only serializes PlexonPanel callers. An accepted stdin write is `DISPATCHED` until the server confirms the action. [Upstream attach documentation](https://github.com/itzg/docker-minecraft-bedrock-server/blob/dbf8516038c82236210e509eae6d942a46e578fc/README.md#executing-server-commands) · [Upstream entrypoint](https://github.com/itzg/docker-minecraft-bedrock-server/blob/dbf8516038c82236210e509eae6d942a46e578fc/demoter-entry.sh)

The local Docker fixture requires `alpine:3.22` already available on a disposable Unix-socket Engine and Python 3. It creates a random network-isolated test container, sends two fixed lines through an upgraded stdin-only attach connection, verifies ordering and stdin survival, then verifies a clean exit and new start on the same container. It removes only the container it created. The fixture exercises Docker transport semantics. The Java adapter was also exercised against a disposable Alpine bind-mounted fixture on rootless Docker 29.9.2 amd64. Its console command reached fixture logs, restart produced a new run, and cold archive creation stopped and restarted the same container. ARM64/Box64 and the game-specific responses require separate testing.

```sh
python3 bedrock-host/scripts/validate-docker-staging.py
./gradlew :bedrock-host:test --tests '*BedrockOperationTest'
```

## Cold backup scope and recovery

The local backup operation uses a dedicated mode-0700 directory outside the Bedrock data mount, with fixed includes `worlds/`, `allowlist.json`, and `permissions.json`. The latter two may contain private player information. Restrict backup access to authorized operators and keep the enclosing directories protected. Server properties, external packs and credentials require separately scoped storage. A world depending on external packs needs those packs available during a restore rehearsal.

An approved backup of a running container records restart intent before dispatching a graceful stop. It validates the data mount, clean exit and unchanged container generation, scans the ZIP from a secure directory handle, checks SHA-256, and promotes the bundle atomically. It rechecks the stopped launch before promotion and attempts the restart after an archive or stop failure. A failed restart retains the private recovery record with the container, launch, and data-directory identity. Recovery verifies all three before redispatch. A stable data path must still resolve to the original directory. If inspection returns a different container or leaves an ambiguous stop outstanding, an operator must resolve the state before retrying. A backup may be locally verified while the restart remains unresolved.

This gate covers local cold backup only. App-specific backup upload, live LevelDB snapshots, remote restore and unattended restore are pending. Keep the current backup and recovery data private through the read-only rollout. [Ubuntu ARM64 runbook](BEDROCK_ARM64_RUNBOOK.md)
