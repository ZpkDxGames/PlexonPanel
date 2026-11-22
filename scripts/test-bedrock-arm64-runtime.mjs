// Runs only on the disposable native Ubuntu GitHub-hosted ARM64 acceptance runner.
import assert from "node:assert/strict";
import { execFile } from "node:child_process";
import { randomInt, randomUUID } from "node:crypto";
import { once } from "node:events";
import { lstat, mkdir, mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { connect } from "node:net";
import { arch, tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { createServer } from "node:tls";
import { setTimeout as delay } from "node:timers/promises";
import { fileURLToPath, pathToFileURL } from "node:url";
import { promisify } from "node:util";

const exec = promisify(execFile);
const [dashboard, jar] = process.argv.slice(2).map((path) => resolve(path));
const { RUNNER_ENVIRONMENT, BEDROCK_EVIDENCE, BEDROCK_STAGING_RUN_ID, BEDROCK_CONTAINER_ID, BEDROCK_IMAGE_ID, BEDROCK_DATA, JAVA_HOME } = process.env;
assert.equal(RUNNER_ENVIRONMENT, "github-hosted");
assert.equal(arch(), "arm64");
assert.match(await readFile("/etc/os-release", "utf8"), /^ID=ubuntu$/m);
assert.match(await readFile("/etc/os-release", "utf8"), /^VERSION_ID="24\.04"$/m);
assert.ok(process.getuid() !== 0 && dashboard && jar && BEDROCK_EVIDENCE && BEDROCK_DATA && JAVA_HOME);
assert.match(BEDROCK_STAGING_RUN_ID ?? "", /^[A-Za-z0-9._-]{1,80}$/);
assert.match(BEDROCK_CONTAINER_ID ?? "", /^[0-9a-f]{64}$/);
assert.match(BEDROCK_IMAGE_ID ?? "", /^sha256:[0-9a-f]{64}$/);
assert.match(JAVA_HOME, /^\/[A-Za-z0-9_./+-]+$/);

const deploy = fileURLToPath(new URL("../bedrock-host/deploy/", import.meta.url));
const artifact = "/opt/plexonpanel-bedrock/plexonpanel-bedrock-host.jar";
const configDir = "/etc/plexonpanel-bedrock/tonimbedrock";
const trustFile = "/etc/plexonpanel-bedrock/phase2-ca.p12";
const brokerSocket = "/run/plexonpanel-bedrock/tonimbedrock.sock";
const user = "pph-tonimbedrock";
const hostUnit = "plexonpanel-bedrock-host@tonimbedrock.service";
const brokerUnit = "plexonpanel-bedrock-broker@tonimbedrock.service";
const trustPassword = "phase2-public-cert";
const dir = await mkdtemp(join(tmpdir(), "pp-bedrock-phase2-"));
await mkdir(BEDROCK_EVIDENCE, { recursive: true });
const receipts = [];
const clients = new Set();
const tlsClients = new Set();
const importDashboard = (path) => import(pathToFileURL(join(dashboard, path)).href);
const { createFleetFixture } = await importDashboard("relay/tests/support/fleet-fixture.mjs");
const { hostKeys } = await importDashboard("relay/tests/support/bedrock-fixture.mjs");
const { createStandaloneRelay } = await importDashboard("relay/dist/standalone/server.js");
const { loadBedrockConfig } = await importDashboard("relay/dist/standalone/bedrock-config.js");
const { pairingLookupId } = await importDashboard("relay/dist/security.js");
let fleet, recoveryRelay, tlsServer, relayPort, disposableAccount = false, installed = false, failure;

async function run(file, args, timeout = 30_000) {
  return (await exec(file, args, { timeout, maxBuffer: 512 * 1024 })).stdout.trim();
}
const sudo = (file, ...args) => run("sudo", ["-n", file, ...args]);
const systemctl = (...args) => sudo("systemctl", ...args);

function pass(check, fields = {}) {
  receipts.push({ check, result: "PASS", ...fields });
  console.log(JSON.stringify(receipts.at(-1)));
}

async function eventually(label, probe, timeout = 30_000) {
  const deadline = performance.now() + timeout;
  while (performance.now() < deadline) {
    const result = await probe();
    if (result) return result;
    await delay(100);
  }
  throw new Error(`Timed out waiting for ${label}`);
}

async function verifiedContainer() {
  const fields = await run("docker", ["inspect", "--type", "container", "--format",
    '{{json .Id}} {{json .Image}} {{json .Name}} {{json (index .Config.Labels "com.plexonpanel.staging.run")}} {{json .State.StartedAt}}', BEDROCK_CONTAINER_ID]);
  const parts = fields.split(" ").map(JSON.parse);
  const [id, image, name, label, startedAt] = parts;
  assert.deepEqual([id, image, name, label], [BEDROCK_CONTAINER_ID, BEDROCK_IMAGE_ID, "/tonimbedrock", BEDROCK_STAGING_RUN_ID]);
  const mount = await run("docker", ["inspect", "--type", "container", "--format",
    '{{range .Mounts}}{{if eq .Destination "/data"}}{{.Type}} {{.Source}} {{.RW}}{{end}}{{end}}', id]);
  assert.equal(mount, `bind ${BEDROCK_DATA} true`);
  assert.equal(await run("docker", ["inspect", "--format", "{{.State.Running}}", id]), "true");
  return startedAt;
}

async function makeTlsProxy() {
  const key = join(dir, "key.pem");
  const cert = join(dir, "cert.pem");
  const trust = join(dir, "ca.p12");
  await run("openssl", ["req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "1", "-subj", "/CN=127.0.0.1",
    "-addext", "subjectAltName=IP:127.0.0.1", "-keyout", key, "-out", cert]);
  await run(join(JAVA_HOME, "bin/keytool"), ["-importcert", "-noprompt", "-alias", "phase2", "-file", cert,
    "-storetype", "PKCS12", "-keystore", trust, "-storepass", trustPassword]);
  tlsServer = createServer({ key: await readFile(key), cert: await readFile(cert) }, (client) => {
    if (!relayPort) { client.destroy(); return; }
    const target = connect({ host: "127.0.0.1", port: relayPort });
    tlsClients.add(client);
    client.on("error", () => target.destroy());
    target.on("error", () => client.destroy());
    client.on("close", () => { tlsClients.delete(client); target.destroy(); });
    target.on("close", () => client.destroy());
    client.pipe(target).pipe(client);
  });
  tlsServer.listen(0, "127.0.0.1");
  await once(tlsServer, "listening");
  return { url: `wss://127.0.0.1:${tlsServer.address().port}/v1/agent`, trust };
}

async function installUnits(registration, gatewayPublicKey, hostKeysForStage, tls) {
  for (const path of ["/etc/plexonpanel-bedrock", "/opt/plexonpanel-bedrock", "/run/plexonpanel-bedrock",
    `/etc/systemd/system/${hostUnit.replace("@tonimbedrock", "@")}`, `/etc/systemd/system/${brokerUnit.replace("@tonimbedrock", "@")}`]) {
    await assert.rejects(lstat(path), { code: "ENOENT" }, `staging path already in use: ${path}`);
  }
  await sudo("useradd", "--system", "--user-group", "--no-create-home", "--home-dir", "/nonexistent", "--shell", "/usr/sbin/nologin", user);
  disposableAccount = true;
  installed = true;
  await sudo("install", "-d", "-o", "root", "-g", user, "-m", "0750", "/etc/plexonpanel-bedrock", configDir);
  await sudo("install", "-d", "-o", "root", "-g", "root", "-m", "0755", "/opt/plexonpanel-bedrock");
  await sudo("install", "-o", "root", "-g", "root", "-m", "0644", jar, artifact);
  await sudo("install", "-o", "root", "-g", user, "-m", "0640", tls.trust, trustFile);
  const { serverId, nodeId } = registration;
  const identity = `serverId=${serverId}\nnodeId=${nodeId}\n`;
  const hostProperties = `${identity}serverName=TonimBedrock\nrelayUrl=${tls.url}\nrelayPublicKey=${gatewayPublicKey}\npublicKey=${hostKeysForStage.publicKey}\nprivateKey=${hostKeysForStage.privateKey}\n`;
  await writeFile(join(dir, "host.properties"), hostProperties, { mode: 0o600 });
  await writeFile(join(dir, "broker.properties"), identity, { mode: 0o600 });
  await sudo("install", "-o", "root", "-g", user, "-m", "0640", join(dir, "host.properties"), `${configDir}/host.properties`);
  await sudo("install", "-o", "root", "-g", "root", "-m", "0600", join(dir, "broker.properties"), `${configDir}/broker.properties`);
  for (const template of ["plexonpanel-bedrock-host@.service", "plexonpanel-bedrock-broker@.service"]) {
    const isHost = template.includes("-host@");
    const trust = isHost ? ` -Djavax.net.ssl.trustStore=${trustFile} -Djavax.net.ssl.trustStorePassword=${trustPassword}` : "";
    const source = (await readFile(join(deploy, template), "utf8")).replace("/usr/bin/java", `${JAVA_HOME}/bin/java${trust}`);
    await writeFile(join(dir, template), source, { mode: 0o600 });
    await sudo("install", "-o", "root", "-g", "root", "-m", "0644", join(dir, template), `/etc/systemd/system/${template}`);
  }
  await systemctl("daemon-reload");
  await sudo("systemd-analyze", "verify", hostUnit, brokerUnit);
  await systemctl("start", hostUnit);
  await systemctl("is-active", hostUnit, brokerUnit);
}

async function probeAs(account, code, ...args) {
  return run("sudo", ["-n", "-u", account, "python3", "-c", code, ...args]);
}

async function verifyAccessBoundary({ serverId, nodeId }) {
  assert.equal(await sudo("stat", "-c", "%U:%G:%a", brokerSocket), `root:${user}:660`);
  assert.equal(await run("id", ["-nG", user]), user);
  const pid = await systemctl("show", "--property=MainPID", "--value", hostUnit);
  const brokerPid = await systemctl("show", "--property=MainPID", "--value", brokerUnit);
  assert.match(pid, /^[1-9]\d*$/);
  assert.match(brokerPid, /^[1-9]\d*$/);
  assert.equal(await sudo("ps", "-p", pid, "-o", "user:32="), user);
  assert.equal(await sudo("ps", "-p", brokerPid, "-o", "user:32="), "root");
  const probeDenied = `
import socket, sys
for path in sys.argv[1:]:
    with socket.socket(socket.AF_UNIX) as conn:
        conn.settimeout(2)
        try: conn.connect(path)
        except PermissionError: continue
        else: raise AssertionError("unexpected socket permission")
`;
  await probeAs(user, probeDenied, "/run/docker.sock");
  await probeAs("nobody", probeDenied, brokerSocket);
  await probeAs("root", `
import socket, sys
with socket.socket(socket.AF_UNIX) as conn:
    conn.settimeout(5)
    conn.connect(sys.argv[1])
    assert conn.recv(1) == b"", "a reachable socket did not reject the wrong kernel peer"
`, brokerSocket);
  await probeAs(user, `
import json, socket, struct, sys
path, server_id, node_id = sys.argv[1:]
request = dict(version=1, operation="STATUS", serverId=server_id, nodeId=node_id, instanceKey="tonimbedrock")
for rewrite in ({"operation": "RESTART"}, {"instanceKey": "wrong-target"}, {"path": "/var/run/docker.sock"}):
    body = json.dumps(request | rewrite).encode("utf-8")
    with socket.socket(socket.AF_UNIX) as conn:
        conn.settimeout(5)
        conn.connect(path)
        conn.sendall(struct.pack(">I", len(body)) + body)
        try: data = conn.recv(1)
        except ConnectionResetError: data = b""
        assert data == b"", "broker returned a response to a refused request"
`, brokerSocket, serverId, nodeId);
  pass("real systemd service accounts, socket modes, peer and operation denial");
}

async function health() {
  const response = await fetch(`${fleet.base}/healthz`, { signal: AbortSignal.timeout(3000) });
  return response.json();
}

async function claim(code) {
  return fetch(`${fleet.base}/v1/pairings/claim`, { method: "POST", signal: AbortSignal.timeout(5000),
    headers: { Origin: fleet.origin, "Content-Type": "application/json" },
    body: JSON.stringify({ code, name: "Disposable ARM64 browser" }) });
}

function checkSession(credential) {
  return fetch(`${fleet.base}/v1/dashboard/session`, { signal: AbortSignal.timeout(3000),
    headers: { Origin: fleet.origin, Authorization: `Bearer ${credential.accessToken}` } });
}

async function browserFor(credential) {
  const socket = new WebSocket(credential.websocketUrl, { protocols: ["plexonpanel-v3", `auth.${credential.accessToken}`],
    headers: { origin: fleet.origin } });
  clients.add(socket);
  const events = [];
  socket.addEventListener("message", ({ data }) => {
    events.push(JSON.parse(data));
    if (events.length > 128) events.shift();
  });
  socket.addEventListener("close", () => clients.delete(socket));
  await once(socket, "open", { signal: AbortSignal.timeout(5000) });
  return {
    socket,
    next: (description, match, timeout) => eventually(description, () => {
      const index = events.findIndex(match);
      return index >= 0 && events.splice(index, 1)[0];
    }, timeout),
  };
}

async function verifyRevokedUpgrade(credential) {
  const socket = new WebSocket(credential.websocketUrl, { protocols: ["plexonpanel-v3", `auth.${credential.accessToken}`],
    headers: { origin: fleet.origin } });
  const outcome = await Promise.race([
    once(socket, "open", { signal: AbortSignal.timeout(5000) }).then(() => "opened"),
    once(socket, "error", { signal: AbortSignal.timeout(5000) }).then(() => "rejected"),
  ]);
  socket.close();
  assert.equal(outcome, "rejected", "revoked WebSocket credential must fail to upgrade");
}

async function verifyLegacyRooms() {
  const status = await health();
  assert.equal(status.connections.paper, 3);
  assert.equal(status.connections.host, 3);
  assert.equal(new Set(fleet.rooms.map((room) => room.serverId)).size, 3);
  for (const [index, room] of fleet.rooms.entries()) {
    const browser = await fleet.browser(index);
    try {
      const msg = await browser.next((event) => event.eventType === "service.status");
      assert.equal(msg.serverId, room.serverId);
      assert.equal(msg.body.resources.source, "SYSTEMD_CGROUP");
    } finally { browser.socket.close(); }
  }
}

async function replaceRelay(bedrock) {
  await (recoveryRelay ?? fleet.relay).close();
  const config = { ...fleet.relay.config, port: relayPort, bedrock };
  recoveryRelay = createStandaloneRelay(config);
  await recoveryRelay.start();
  for (const room of fleet.rooms) {
    await fleet.attach(room, "PAPER");
    await fleet.attach(room, "HOST");
    await fleet.sync(room);
  }
  await fleet.telemetry();
}

try {
  const before = await verifiedContainer();
  const tls = await makeTlsProxy();
  const keys = hostKeys();
  let registration;
  const code = String(randomInt(1_000_000)).padStart(6, "0");
  const registrationFile = join(dir, "bedrock.json");
  fleet = await createFleetFixture({ paperRooms: 3,
    componentVersion: (key, agent) => key === "alpha" || key === "bravo" && agent === "PAPER" ? "5.0.0" : "6.0.0",
    bedrock: async ({ nodeId, pairingLookup }) => {
      const issuedAt = Date.now();
      await writeFile(registrationFile, JSON.stringify({ serverId: randomUUID(), nodeId, instanceKey: "tonimbedrock",
        publicKey: keys.publicKey, generation: 1, enrollment: { id: randomUUID(), lookupId: await pairingLookup(code),
          issuedAt, expiresAt: issuedAt + 240_000 } }), { mode: 0o600 });
      registration = await loadBedrockConfig(registrationFile);
      return registration;
    } });
  relayPort = Number(new URL(fleet.base).port);
  assert.equal((await claim(code)).status, 403, "valid code cannot enroll before Host admission");
  await installUnits(registration, fleet.relayPublicKey, keys, tls);
  const initial = await eventually("signed Bedrock Host admission", async () => {
    const current = await health();
    return current.connections.bedrockHost === 1 && current;
  });
  assert.equal(initial.rooms, 4);
  await verifyLegacyRooms();
  pass("real WSS Bedrock Host admission alongside three authenticated signed Paper and Host fixtures");

  await verifyAccessBoundary(registration);
  const response = await claim(code);
  assert.equal(response.status, 200);
  const credential = await response.json();
  assert.equal(credential.serverId, registration.serverId);
  assert.deepEqual(credential.scopes, ["overview.view", "telemetry.view", "server.status", "console.view.full"]);
  assert.equal((await claim(code)).status, 403, "enrollment cannot be replayed");
  assert.equal((await checkSession(credential)).status, 200);
  const browser = await browserFor(credential);
  const ready = await browser.next("Bedrock ready", (msg) => msg.type === "dashboard.ready" && msg.agents.host);
  assert.deepEqual(ready.agents, { paper: false, host: true, hostInstalled: true });
  assert.equal(ready.server.roomMode, "HOST_ONLY");
  assert.equal(ready.server.nodeId, registration.nodeId);
  const generation = ready.server.hostSession;
  browser.socket.send(JSON.stringify({ type: "dashboard.action", action: "server.restart", requestId: randomUUID(), parameters: {} }));
  const denied = await browser.next("read-only denial", (msg) => msg.type === "relay.error");
  assert.equal(denied.code, "BEDROCK_READ_ONLY");
  pass("one-use Owner enrollment, exact read-only scopes and remote lifecycle refusal");

  const status = await browser.next("actual Docker CPU, memory, uptime and Bedrock version", (msg) => {
    const body = msg.body;
    return msg.eventType === "bedrock.status" && body.containerState === "running" &&
      body.containerCpuPercent != null && body.containerMemoryBytes > 0 && body.containerUptimeSeconds > 0 && body.version;
  }, 45_000);
  assert.equal(status.agentSession, generation);
  assert.equal(status.body.containerRestartPolicy, "unless-stopped");
  assert.equal(status.body.cpuUnit, "PERCENT_OF_ONE_CORE");
  assert.equal(status.body.source, "DOCKER_ENGINE");
  assert.equal(status.body.gameplayReady, null);
  assert.ok(Math.abs(Date.now() - Date.parse(status.body.containerStatsCapturedAt)) < 30_000);
  assert.ok(Math.abs(Date.parse(before) - Date.parse(status.body.containerStartedAt)) <= 1);
  assert.equal(status.body.version, process.env.BEDROCK_VERSION ?? "1.26.66.1");
  assert.ok(status.body.containerMemoryLimitBytes >= status.body.containerMemoryBytes);
  pass("fresh real Docker readings and launch-bound Bedrock version", { version: status.body.version,
    cpuPercent: status.body.containerCpuPercent, memoryBytes: status.body.containerMemoryBytes,
    memoryLimitBytes: status.body.containerMemoryLimitBytes, uptimeSeconds: status.body.containerUptimeSeconds });

  // Fixed synthetic sentinel to the staged container's existing stdout. No game command or secret is involved.
  await verifiedContainer();
  await run("docker", ["exec", "--user", "0", BEDROCK_CONTAINER_ID, "sh", "-c",
    "printf '\\033[32mPP_PHASE2_REDACTION\\033[0m from 192.0.2.23 xuid=1234567890123456\\n' > /proc/1/fd/1"]);
  const logs = await browser.next("redacted marker read from the real Docker log driver", (msg) => msg.eventType === "bedrock.logs" &&
    msg.body.lines.some((line) => line.content.includes("PP_PHASE2_REDACTION")));
  const marker = logs.body.lines.find((line) => line.content.includes("PP_PHASE2_REDACTION"));
  assert.doesNotMatch(marker.content, /192\.0\.2\.23|1234567890123456|\u001b/u);
  assert.ok(logs.body.available && logs.body.recentWindow && logs.body.lines.length <= 12);
  pass("real Docker log redaction and bounded recent window");

  await systemctl("stop", hostUnit);
  await browser.next("offline Host readiness", (msg) => msg.type === "dashboard.ready" && !msg.agents.host);
  const offline = await health();
  assert.equal(offline.connections.bedrockHost, 0);
  assert.equal(offline.connections.paper, 3);
  await systemctl("start", hostUnit);
  const resumed = await browser.next("authenticated reconnect with new signed session", (msg) => msg.type === "dashboard.ready" &&
    msg.agents.host && msg.server.hostSession !== generation);
  const fresh = await browser.next("fresh data in new Host session", (msg) => msg.eventType === "bedrock.status" &&
    msg.agentSession === resumed.server.hostSession && Date.now() - Date.parse(msg.body.capturedAt) < 30_000);
  assert.equal(fresh.body.containerStartedAt, status.body.containerStartedAt);
  pass("Host interruption, offline state and signed reconnect with fresh telemetry");

  const newCode = String((Number(code) + 1) % 1_000_000).padStart(6, "0");
  const issuedAt = Date.now();
  await writeFile(registrationFile, JSON.stringify({ ...registration, revokedDevices: [credential.deviceId],
    enrollment: { id: randomUUID(), lookupId: await pairingLookupId(newCode, fleet.relay.config.pairingCodePepper),
      issuedAt, expiresAt: issuedAt + 180_000 } }), { mode: 0o600 });
  await replaceRelay(await loadBedrockConfig(registrationFile));
  await eventually("Host reconnected through restarted relay", async () => (await health()).connections.bedrockHost === 1, 45_000);
  assert.equal((await checkSession(credential)).status, 401);
  await verifyRevokedUpgrade(credential);
  const renewed = await claim(newCode);
  assert.equal(renewed.status, 200);
  const replacement = await renewed.json();
  assert.notEqual(replacement.deviceId, credential.deviceId);
  await verifyLegacyRooms();
  pass("durable per-device revocation after relay restart and new host-only claim");

  await systemctl("stop", hostUnit, brokerUnit);
  await replaceRelay(undefined);
  const rollback = await health();
  assert.equal(rollback.rooms, 3);
  assert.equal(rollback.connections.bedrockHost ?? 0, 0);
  assert.equal((await checkSession(credential)).status, 401);
  assert.equal((await checkSession(replacement)).status, 401);
  await verifyRevokedUpgrade(replacement);
  await verifyLegacyRooms();
  assert.equal(await verifiedContainer(), before, "read-only rollout or rollback restarted the game");
  pass("rollback to legacy-only relay with the three rooms and game launch preserved");
} catch (error) {
  failure = error;
  if (installed) {
    const journal = await sudo("journalctl", "-n", "60", "--no-pager", "-u", hostUnit, "-u", brokerUnit).catch(() => "");
    await writeFile(join(BEDROCK_EVIDENCE, "companion-diagnostic.log"), `${journal}\n`);
  }
  throw error;
} finally {
  for (const client of clients) client.close();
  for (const socket of tlsClients) socket.destroy();
  if (tlsServer) tlsServer.close();
  await recoveryRelay?.close();
  await fleet?.close();
  if (installed) {
    await systemctl("stop", hostUnit, brokerUnit).catch(() => {});
    await sudo("rm", "-f", `${configDir}/host.properties`, `${configDir}/broker.properties`, trustFile, artifact,
      "/etc/systemd/system/plexonpanel-bedrock-host@.service", "/etc/systemd/system/plexonpanel-bedrock-broker@.service").catch(() => {});
    await sudo("rmdir", configDir, "/etc/plexonpanel-bedrock", "/opt/plexonpanel-bedrock").catch(() => {});
    await systemctl("daemon-reload").catch(() => {});
  }
  if (disposableAccount) await sudo("userdel", user).catch(() => {});
  await rm(dir, { recursive: true, force: true });
  await writeFile(join(BEDROCK_EVIDENCE, "companion-receipt.json"), `${JSON.stringify({ result: failure ? "FAIL" : "PASS",
    ...(failure ? { failure: failure.message } : {}), receipts }, null, 2)}\n`);
}
