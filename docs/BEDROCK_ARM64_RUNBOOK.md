# TonimBedrock installation and rollback

This runbook is for Ubuntu 24.04 ARM64. The draft changes require an isolated staging acceptance before any production installation. The existing game runs in `tonimbedrock` with its current Compose configuration and data volume. Installing the companion adds a control-plane identity and a local read-only Docker connection.

## Before preparing artifacts

- Pin the exact core and Dashboard/Relay draft commit SHAs and record their test runs. Build artifacts from those immutable checkouts and record SHA-256 digests. A matching version string alone cannot identify a build.
- Use a disposable ARM64 node and a test world with the same Bedrock image digest, Docker log driver, stdin/TTY settings and Box64 path as the target. Use standalone relay keys, state and browser credentials created for staging.
- For production, obtain explicit authorization identifying the node, the approved artifact digests, the permitted window, the operator and rollback owner, and the approved changes to the relay, Dashboard release, Bedrock units and enrollment policy. Enable only status and logs in this window. A separate approval is required for game downtime, data writes or each additional capability.

## Build and preflight

Build from the pinned core checkout with Java 25.

```sh
./gradlew :bedrock-host:test :bedrock-host:jar
sha256sum bedrock-host/build/libs/plexonpanel-bedrock-host-6.0.0.jar
```

Build the standalone relay separately from the pinned Dashboard checkout with Node 24.

```sh
npm ci
npm run relay:build
npm run relay:test
npm run relay:standalone:package
```

The relay package is `artifacts/plexonpanel-relay/`. It contains the compiled runtime, systemd unit and environment example. Record file digests, the full `git rev-parse HEAD` and an ISO-8601 UTC build timestamp before copying the package to any node. Set that commit and timestamp as `PLEXON_BUILD_GIT_COMMIT` and `PLEXON_BUILD_TIMESTAMP` in the runtime environment so `/healthz` can identify the candidate.

The signed integration fixture runs the built Java Host and standalone relay from the two checkouts with a Docker simulator and three Paper/Host pairs. After building the relay, run it as an unprivileged Linux user from the root of the core checkout with `JAVA_HOME` pointing to Java 25.

```sh
./gradlew :bedrock-host:testClasses :bedrock-host:jar
node scripts/test-bedrock-relay-integration.mjs /path/to/dashboard-checkout \
  bedrock-host/build/libs/plexonpanel-bedrock-host-6.0.0.jar
```

On the target node, verify native ARM64 Java 25, local Docker and the exact existing game configuration. Preserve the output privately with the rollback record. These commands read state and select fields that exclude container environment variables.

```sh
uname -m
/usr/bin/java -XshowSettings:properties -version 2>&1 | grep -E 'os.arch|java.version'
sudo docker version --format '{{.Server.Version}} {{.Server.Arch}}'
sudo docker inspect --type container --format \
  'name={{.Name}} id={{.Id}} image={{.Config.Image}} imageId={{.Image}} state={{.State.Status}} restart={{.HostConfig.RestartPolicy.Name}} logging={{.HostConfig.LogConfig.Type}} openStdin={{.Config.OpenStdin}} tty={{.Config.Tty}}' \
  tonimbedrock
sudo docker inspect --type container --format \
  '{{range .Mounts}}{{if eq .Destination "/data"}}{{.Type}} {{.Source}} {{.Destination}}{{println}}{{end}}{{end}}' \
  tonimbedrock
sudo stat -c '%U:%G %a %n' /etc/plexonpanel/node-id
```

Confirm `aarch64`, a native Java 25 runtime, the `tonimbedrock` identity, `unless-stopped`, and the expected bind mount from `/srv/plexonpanel/servers/tonimbedrock/data` to `/data`. If the actual image digest, versions, logging mode or permissions differ from the approved staging target, record the differences and validate them in staging. Keep TCP 19132 and the configured UDP 19140–19155 rules as installed. The companion needs neither a public port nor a new game network path.

## Install the isolated Bedrock units

Use these steps first on the isolated ARM64 staging node. Run from the root of the pinned core checkout with the verified JAR at the documented build path. Stop at any unexpected command failure. Set `BEDROCK_RELAY_URL` to the approved `wss://…/v1/agent` URL and `BEDROCK_RELAY_PUBLIC_KEY` to the relay's verified SPKI DER base64 public pin. The unit requires TLS even when the Docker and relay services share a host. Use `host-fixture` only for development against an unprivileged same-user broker fixture and loopback WebSocket.

```sh
getent passwd pph-tonimbedrock >/dev/null ||
  sudo useradd --system --user-group --no-create-home --home-dir /nonexistent \
    --shell /usr/sbin/nologin pph-tonimbedrock
id pph-tonimbedrock
sudo install -d -o root -g pph-tonimbedrock -m 0750 \
  /etc/plexonpanel-bedrock /etc/plexonpanel-bedrock/tonimbedrock
sudo install -d -o root -g root -m 0755 /opt/plexonpanel-bedrock
sudo install -o root -g root -m 0644 \
  bedrock-host/build/libs/plexonpanel-bedrock-host-6.0.0.jar \
  /opt/plexonpanel-bedrock/plexonpanel-bedrock-host.jar
sudo install -o root -g root -m 0644 \
  bedrock-host/deploy/plexonpanel-bedrock-broker@.service \
  bedrock-host/deploy/plexonpanel-bedrock-host@.service /etc/systemd/system/
```

For a new, approved identity, generate the root-owned Host configuration once. This command prints the public association record. Keep only that record for relay enrollment, and preserve the generated private Host file for subsequent starts or upgrades.

```sh
BEDROCK_NODE_ID=$(sudo cat /etc/plexonpanel/node-id)
sudo /usr/bin/java -jar /opt/plexonpanel-bedrock/plexonpanel-bedrock-host.jar \
  identity-init "$BEDROCK_NODE_ID" "${BEDROCK_RELAY_URL:?Set the approved WSS /v1/agent URL}" \
  "${BEDROCK_RELAY_PUBLIC_KEY:?Set the verified relay SPKI public key}" \
  /etc/plexonpanel-bedrock/tonimbedrock/host.properties
sudo chown root:pph-tonimbedrock /etc/plexonpanel-bedrock/tonimbedrock/host.properties
sudo chmod 0640 /etc/plexonpanel-bedrock/tonimbedrock/host.properties
```

Use [the broker configuration example](../bedrock-host/deploy/broker.properties.example) to provision root-owned mode-0600 `/etc/plexonpanel-bedrock/tonimbedrock/broker.properties` with exactly the same public `serverId` and `nodeId`. The account's only group should be its private service group. Keep the broker config, JAR, units and parent directories outside its write authority. Verify it cannot open the Docker socket, that the unit masks the server directories, and that no broad journal or sudo authority applies to the account. Run the following after approving the relay admission and verifying the paths and units. The Host unit starts the broker as its dependency.

```sh
sudo -u pph-tonimbedrock /usr/bin/test ! -w /run/docker.sock
sudo systemd-analyze verify plexonpanel-bedrock-broker@tonimbedrock.service \
  plexonpanel-bedrock-host@tonimbedrock.service
sudo systemctl daemon-reload
systemctl is-active --quiet docker.service &&
  sudo systemctl start plexonpanel-bedrock-host@tonimbedrock.service
systemctl is-active plexonpanel-bedrock-broker@tonimbedrock.service
systemctl is-active plexonpanel-bedrock-host@tonimbedrock.service
sudo stat -c '%U:%G %a %n' /run/plexonpanel-bedrock/tonimbedrock.sock
```

Expect the socket to be `root:pph-tonimbedrock` mode 0660. The Host process has no Docker daemon socket or server-tree access. Confirm a healthy relay connection in `/healthz` and the Dashboard before enabling the Host unit at boot. Only Docker state and a fresh log window of at most 12 lines from the preceding 10 minutes are available in this phase. The log window replaces the previous snapshot. Older or rotated Docker logs may be outside PlexonPanel's reach.

## Register the Host and enroll a browser

Point `PLEXON_RELAY_BEDROCK_CONFIG` at an absolute, private registration file. For the systemd relay, use `/etc/plexonpanel-relay/bedrock.json` owned by `root:plexonpanel-relay` with mode 0640 and a root-controlled parent directory. For the local fixture, use `$STAGE_DIR/bedrock.json` owned by the staging process account with mode 0600. Set these fields from the verified public association record. Keep the initial `generation` value for a new registration.

```json
{
  "serverId": "<serverId from identity-init>",
  "nodeId": "<nodeId from identity-init>",
  "instanceKey": "tonimbedrock",
  "publicKey": "<publicKey from identity-init>",
  "generation": 1,
  "revokedDevices": []
}
```

Start the relay with the matching Host registration, then start the broker and Host. Verify `connections.bedrockHost: 1` before enrolling a browser. When the device owner is ready, run the Bedrock enrollment helper in a private administrator session. For local staging, run it from the pinned Dashboard checkout after `npm run relay:build`.

```sh
node --env-file="$STAGE_DIR/relay.env" relay/scripts/bedrock-enrollment.mjs
```

In an approved production window, set `RELAY_RELEASE_DIR` to the approved candidate directory in the installed unit. Use its helper and the relay's protected environment file.

```sh
sudo /usr/bin/node --env-file=/etc/plexonpanel-relay/relay.env \
  "${RELAY_RELEASE_DIR:?Set the installed candidate directory}/relay/scripts/bedrock-enrollment.mjs"
```

The helper prints a one-time six-digit `code` and a separate `enrollment` object. Add only the object under the `enrollment` key in the private registration file and restart the standalone relay to load it. Wait for the same Host to reconnect and share the code out of band with the approved device owner. The owner has five minutes from generation to claim it in **Fleet → Pair browser**. Keep the code out of logs, screenshots, GitHub, CI and shared terminals. An expired, consumed or attempt-limited code requires a fresh enrollment.

The relay keeps Bedrock grants in its own SQLite sidecar, named by appending `.bedrock` to `PLEXON_RELAY_DATABASE_PATH`. Restrict it and the state directory to the relay account and retain them in the documented backup. To revoke a device, add its Bedrock device UUID to `revokedDevices` and restart the standalone relay within the approved window. To revoke all Bedrock grants, increment `generation`, delete `enrollment` and restart. Verify the prior credential fails to reconnect. Keep the published identity, latest generation and revocations across restarts and rollbacks.

## Staging acceptance

The Core draft's **Bedrock Phase 2.5 ARM64 runtime parity** workflow runs on a disposable GitHub-hosted Ubuntu 24.04 ARM64 runner. It pins Docker 29.10.0, Java 25.0.4.1, the reported production image digest, the Dashboard revision, and the official BDS executable. It generates its own bind mount, test keys, state and unpublished bridge. Use the draft check and its evidence bundle to verify the gate before reviewing the separate Oracle preflight. [Workflow](../.github/workflows/bedrock-runtime-acceptance.yml) · [Phase 2.5 readiness and rollback](BEDROCK_PHASE25_READINESS.md)

For the isolated workflow only, `ENABLE_LAN_VISIBILITY=true` enables a response body on the NetherNet `/v1/join` probe. In a disposable x86-64 comparison using BDS 1.26.66.1, disabled visibility returned HTTP 200 with an empty body. Enabled visibility returned the expected server and level metadata. Require that metadata and the `Server started.` log for each staged launch. A 200 response alone does not demonstrate readiness. Retain the operator-approved LAN policy for any live rollout. [NetherNet probe](https://github.com/itzg/docker-minecraft-bedrock-server/blob/dbf8516038c82236210e509eae6d942a46e578fc/README.md#test-the-connection)

For an all-local staging relay, run the following from the pinned Dashboard checkout. The Node process writes staging secrets directly to a mode-0600 environment file in an operator-private directory. `relay.pub` contains the public key pin for the Bedrock Host. The database and enrollment file stay in the same private directory.

```sh
umask 077
STAGE_DIR=$(mktemp -d)
node --input-type=module - "$STAGE_DIR" "$(git rev-parse HEAD)" <<'NODE'
import { generateKeyPairSync, randomBytes } from "node:crypto";
import { writeFileSync } from "node:fs";
import { join } from "node:path";

const dir = process.argv[2];
const { publicKey, privateKey } = generateKeyPairSync("ed25519");
const pin = publicKey.export({ format: "der", type: "spki" }).toString("base64");
const settings = {
  PLEXON_BUILD_GIT_COMMIT: process.argv[3],
  PLEXON_BUILD_TIMESTAMP: new Date().toISOString(),
  PLEXON_RELAY_HOST: "127.0.0.1",
  PLEXON_RELAY_PORT: "8788",
  PLEXON_RELAY_DASHBOARD_ORIGINS: "http://127.0.0.1:4173",
  PLEXON_RELAY_TRUSTED_PROXY: "none",
  PLEXON_RELAY_DATABASE_PATH: join(dir, "relay.db"),
  PLEXON_RELAY_BEDROCK_CONFIG: join(dir, "bedrock.json"),
  PAIRING_CODE_PEPPER: randomBytes(32).toString("hex"),
  ACCESS_TOKEN_SECRET: randomBytes(32).toString("hex"),
  GATEWAY_ED25519_PUBLIC_KEY: pin,
  GATEWAY_ED25519_PRIVATE_KEY: privateKey.export({ format: "der", type: "pkcs8" }).toString("base64"),
};
const options = { mode: 0o600, flag: "wx" };
writeFileSync(join(dir, "relay.env"), Object.entries(settings).map(([k, v]) => `${k}=${v}\n`).join(""), options);
writeFileSync(join(dir, "relay.pub"), `${pin}\n`, options);
NODE
```

1. Prepare the pinned Bedrock identity using the staging node UUID. Verify the relay key pin, publish only the public key to the relay enrollment policy, and keep the Host private key in its root-controlled private configuration. Allocate an independent UUID and grant for this instance. Keep `instanceKey=tonimbedrock` and use the verified node UUID shared by all staged instances on that node.
2. Once `bedrock.json` contains the matching Host registration, start the relay from the pinned Dashboard checkout with `node --env-file="$STAGE_DIR/relay.env" relay/dist/standalone/server.js`. Check `curl -fsS http://127.0.0.1:8788/healthz`. For an all-local browser run, use `NEXT_PUBLIC_PLEXON_RELAY_URL=http://127.0.0.1:8788 npm run dev`. The development CSP admits that exact HTTP and WS origin. For remote staging, use TLS and allow only the exact staging HTTPS/WSS origin in the staging Dashboard CSP. Keep staging on its own hostname and data paths.
3. Install and start the restricted broker and unprivileged Host. In the Dashboard Fleet route, select **Pair browser** and enter the relay origin, an approved device name and the six-digit code issued by the administrator. Keep the existing Paper credentials in the same staging browser profile. Confirm a single `TonimBedrock` entry, readable Docker state and telemetry, and authorized bounded console history and live output. Compare CPU and memory against Docker on the same sample interval. Container CPU is relative to one core, so a result above 100% can be valid. Container memory and its limit are separate from node RAM and JVM heap. Running-container uptime belongs to the current container start. Unknown version, player roster, readiness or stale resources must display as unavailable.
4. Verify another Bedrock device with no grant cannot open the room, the enrollment code expires and cannot be reused, and a revoked credential cannot reconnect. Attempt the wrong server, node and agent identity. Simultaneously verify that Paper grants, frames and console actions stay scoped to their original rooms and that node capacity is counted once for the shared node.
5. Interrupt the staging Host and relay independently. Check that the UI removes stale evidence, reconnects with the same identity, and that bounded log replay neither duplicates accepted lines nor leaks raw controls or credentials. Use a disposable test container to exercise expected errors and unavailable states.
6. Save artifact digests, verification output and redacted evidence. Record native ARM64/Box64 evidence separately from other local fixtures. Move to production preparation only after mixed-edition acceptance and rollback rehearsal pass.

## Approved production window

Before rollout, keep a root-only rollback directory outside the Bedrock tree. Record the active release paths and SHA-256 digests, systemd unit definitions and drop-ins, exact Dashboard deployment ID, relay build and contract fields, authorized origins, proxy routes, base service health and the original three Paper/Host room connections. Capture the standalone relay coordination database and related private policy by its supported consistent backup mechanism. Copying a live SQLite database file without its WAL or an online backup is insufficient. Leave existing key pins, token secrets and Paper device registries in place. Keep the rollback database and signing material private and verify the previously approved restore procedure.

Read the relay baseline locally and record Paper and Host service state using their existing unit names.

```sh
curl -fsS http://127.0.0.1:8787/healthz
systemctl is-active minecraft@tonimsmp.service minecraft@tonimtowny.service minecraft@tonimlobby.service
systemctl is-active plexonpanel-host@tonimsmp.service plexonpanel-host@tonimtowny.service plexonpanel-host@tonimlobby.service
systemctl is-active tonimnetwork-velocity.service caddy.service plexonpanel-relay.service
```

Install the candidate relay artifact alongside the working artifact, preserving the loopback listener, trusted local proxy, existing Ed25519 and HMAC material, coordination path and exact browser-origin allowlist. For the historical packaged layout, use a new root-owned release directory such as `/opt/plexonpanel-relay/releases/<DASHBOARD_SHA>/`. Copy only the verified artifact files into it and verify the digests at the destination. Use a single new systemd drop-in named `/etc/systemd/system/plexonpanel-relay.service.d/50-bedrock-release.conf` to point the installed unit to the candidate. Replace the placeholders with the approved full SHA.

```ini
[Service]
WorkingDirectory=/opt/plexonpanel-relay/releases/<DASHBOARD_SHA>
ExecStart=
ExecStart=/usr/bin/node /opt/plexonpanel-relay/releases/<DASHBOARD_SHA>/relay/dist/standalone/server.js
```

Set the approved build identity and private registration path in the existing protected relay environment file. If the recorded service uses a different Node path or hardened filesystem allowlist, review the effective unit and preserve its equivalent restrictions. Review the new drop-in and validate the unit before restarting only the relay.

```sh
sudo systemd-analyze verify plexonpanel-relay.service
sudo systemctl daemon-reload
sudo systemctl restart plexonpanel-relay.service
curl -fsS http://127.0.0.1:8787/healthz
```

The coordinated relay cutover permits a brief control-plane reconnect while all Minecraft processes keep running. Verify the three Paper/Host pairs, existing sessions and scoped Paper operations against the replacement before admitting Bedrock. Leave Caddy, Velocity, the Paper units, the Bedrock container, the world and the Compose file out of the cutover.

Start only the new Bedrock broker and Host units after their exact accounts, file permissions and systemd protections pass preflight. Publish the approved Dashboard artifact only if its relay URL/CSP match the vetted origin. Complete the out-of-band enrollment with the device owner and check fresh Bedrock metrics, bounded logs, lack of mutation controls, and continued Java behavior from the operator's actual Dashboard. Record the running relay commit, observed room types, test time and result. Inspect listening addresses and confirm Docker remains reachable only through the intended local endpoint.

Verify the replacement reports `protocolVersion: 3`, the approved `gitCommit`, the recorded legacy `fleetContract` and `actionContract`, and live `connections.paper: 3`, `connections.host: 3`, and `connections.bedrockHost: 1` on `/healthz`. Room count alone cannot prove that those connections are authenticated or that Bedrock is playable. Verify each room and the actual gameplay endpoint separately.

## Rollback criteria and order

Roll back for a Paper room regression, a changed relay/key identity, a Bedrock association mismatch, an unexpected Docker/write surface, unauthorized access, or a staging-to-production configuration mismatch.

1. Stop the new Bedrock Host and broker units with `sudo systemctl stop plexonpanel-bedrock-host@tonimbedrock.service plexonpanel-bedrock-broker@tonimbedrock.service`. Disable their boot enablement if it was added. Clear any pending enrollment and advance the Bedrock registration generation before any future admission. Remove the Bedrock registration setting from the active relay environment. Keep the Bedrock container and its world running throughout this rollback.
2. If Dashboard production was promoted, restore the exact previous deployment and verify its relay origin. Move only the new `50-bedrock-release.conf` drop-in into the private rollback directory and restore the recorded relay environment and release pointer. Preserve existing relay secrets and Paper identities. Run `sudo systemctl daemon-reload`, restart just the relay and verify health, reconnect and authorization for the prior Paper/Host rooms.
3. Use the prior coordination snapshot only if the candidate changed state incompatibly. Preserve the candidate state privately for review and reconcile any intervening device revocations before restoring older authority. Keep the Bedrock grants and keys out of the previous relay as documented in the rollback record.
4. Check the three Paper servers, Velocity, Caddy and the existing Bedrock gameplay endpoint against the recorded baseline. Verify the original Bedrock image/container identity, restart policy and data mount. Retain logs and digests for diagnosis.

## Operations after read-only acceptance

- **Lifecycle** requires separate approval and target-specific testing of exact container transitions, graceful stop, preconditions, retries, audit and readiness. An intentional Docker stop under `unless-stopped` is expected to remain stopped until an explicit start.
- **Console and players** require the actual itzg ARM64/Box64 console to pass stdin, ownership and reconnect tests. The prior `send-command` attempt failed under `/proc/*/exe` permissions. Report a submitted command as dispatched until its result is positively established. Verify Bedrock roster, allowlist, XUID and operator operations independently.
- **Backups and restore** require a disposable world test and separate downtime/data approval. Start with a cold LevelDB backup and verify integrity, restart and interruption recovery. Restore goes through its own review and needs independently retained rollback data. Settings writes and live snapshots require their own validation.

See the [mutation capability staging guide](BEDROCK_OPERATIONS_STAGING.md) for the local operation fixtures and individual activation conditions.
