// Cross-repository test of the real Java JAR, Unix broker and standalone relay.
// Docker and the three Paper/Host pairs are simulated. All sockets and keys are disposable.
import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { randomInt, randomUUID } from "node:crypto";
import { once } from "node:events";
import { access, mkdtemp, rm, writeFile } from "node:fs/promises";
import { createServer } from "node:net";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { setTimeout as delay } from "node:timers/promises";
import { fileURLToPath, pathToFileURL } from "node:url";

const [dashboard, jar] = process.argv.slice(2).map((path) => resolve(path));
if (!dashboard || !jar || process.getuid?.() === 0)
  throw new Error("Run as an unprivileged Linux user: node scripts/test-bedrock-relay-integration.mjs <dashboard-checkout> <bedrock-jar>");

const backend = fileURLToPath(new URL("..", import.meta.url));
const dashboardFile = (relative) => pathToFileURL(join(dashboard, relative)).href;
const { createFleetFixture } = await import(dashboardFile("relay/tests/support/fleet-fixture.mjs"));
const { hostKeys } = await import(dashboardFile("relay/tests/support/bedrock-fixture.mjs"));
const { parseBedrockConfig } = await import(dashboardFile("relay/dist/standalone/bedrock-config.js"));
const java = process.env.JAVA_HOME ? join(process.env.JAVA_HOME, "bin/java") : "java";
const fixtureClasses = join(backend, "bedrock-host/build/classes/java/test");
const directory = await mkdtemp(join(tmpdir(), "pp-bedrock-"));
const dockerSocket = join(directory, "docker.sock");
const brokerSocket = join(directory, "broker.sock");
const hostConfig = join(directory, "host.properties");
const containerId = "b".repeat(64);
const startTime = Date.now() - 60 * 60_000;
const startedAt = new Date(startTime).toISOString();
const dockerTime = (offset) => new Date(startTime + offset).toISOString();
const startupLogs = [
  `${dockerTime(1000)} [2026-11-21 12:00:01 INFO] Starting Server\n`,
  `${dockerTime(1001)} [2026-11-21 12:00:01 INFO] Version: 1.26.66.1\n`,
].join("");
const recentLogs = `${new Date(Date.now() - 1000).toISOString()} \u001b[32mFixture ready 🧱\u001b[0m from 192.0.2.23 xuid=1234567890123456\n`;
const dockerRoutes = [];
const children = [];
const browserEvents = [];
let fleet, browser, dockerFailure;
let dockerAvailable = true;
let registration;

function launch(...args) {
  const child = spawn(java, args, { stdio: ["ignore", "pipe", "pipe"] });
  const record = { child, diagnostic: "" };
  for (const stream of [child.stdout, child.stderr])
    stream.on("data", (text) => { record.diagnostic = (record.diagnostic + text).slice(-4096); });
  child.on("error", (error) => { record.failure = error; });
  children.push(record);
  return child;
}

async function eventually(probe, timeout = 15_000) {
  const deadline = performance.now() + timeout;
  while (performance.now() < deadline) {
    for (const { child, diagnostic, failure } of children) {
      if (failure) throw failure;
      if (child.exitCode !== null || child.signalCode !== null) throw new Error(`Fixture Java exited: ${diagnostic}`);
    }
    if (dockerFailure) throw dockerFailure;
    const value = await probe();
    if (value) return value;
    await delay(50);
  }
  throw new Error(`Fixture condition timed out: ${children.map((entry) => entry.diagnostic).join("\\n")}`);
}

async function nextEvent(label, predicate) {
  return eventually(() => {
    const index = browserEvents.findIndex(predicate);
    return index === -1 ? false : browserEvents.splice(index, 1)[0];
  }).catch((error) => {
    throw new Error(`Did not receive ${label}; buffered message types: ${browserEvents.map((event) => event.eventType ?? event.type).join(", ")}`,
      { cause: error });
  });
}

const docker = createServer((socket) => {
  let request = "";
  socket.on("error", () => socket.destroy());
  socket.on("data", (data) => {
    request += data.toString("ascii");
    if (!request.includes("\r\n\r\n")) return;
    try {
      const [method, target, protocol] = request.split("\r\n", 1)[0].split(" ");
      assert.equal(method, "GET");
      assert.equal(protocol, "HTTP/1.1");
      dockerRoutes.push(target);
      let status = 200;
      let response;
      if (!dockerAvailable) {
        status = 503;
        response = "fixture daemon unavailable";
      } else if (target === "/v1.44/containers/tonimbedrock/json") {
        response = JSON.stringify({ Id: containerId, Name: "/tonimbedrock", Created: startedAt,
          State: { Status: "running", Running: true, Paused: false, Restarting: false, OOMKilled: false, StartedAt: startedAt },
          Config: { Image: "itzg/minecraft-bedrock-server:stable", Tty: true },
          HostConfig: { RestartPolicy: { Name: "unless-stopped" } }, RestartCount: 1 });
      } else if (target === `/v1.44/containers/${containerId}/stats?stream=false&one-shot=true`) {
        response = JSON.stringify({ id: containerId, read: new Date().toISOString(),
          cpu_stats: { cpu_usage: { total_usage: Math.trunc(performance.now() * 1_250_000) } },
          memory_stats: { usage: 536_870_912, limit: 1_073_741_824 } });
      } else {
        const url = new URL(target, "http://docker");
        assert.equal(url.pathname, `/v1.44/containers/${containerId}/logs`);
        assert.equal(url.searchParams.get("stdout"), "true");
        assert.equal(url.searchParams.get("stderr"), "true");
        assert.equal(url.searchParams.get("timestamps"), "true");
        assert.match(url.searchParams.get("since") ?? "", /^\d+\.\d{9}$/);
        if (url.searchParams.has("until")) {
          assert.match(url.searchParams.get("until"), /^\d+\.\d{9}$/);
          assert.equal(url.searchParams.get("tail"), "all");
          response = startupLogs;
        } else {
          assert.equal(url.searchParams.get("tail"), "12");
          response = recentLogs;
        }
      }
      const bytes = Buffer.from(response);
      socket.end(Buffer.concat([
        Buffer.from(`HTTP/1.1 ${status} Fixture\r\nConnection: close\r\nContent-Length: ${bytes.length}\r\n\r\n`),
        bytes,
      ]));
    } catch (error) {
      dockerFailure = error;
      socket.destroy();
    }
  });
});

try {
  docker.listen(dockerSocket);
  await once(docker, "listening");
  const host = hostKeys();
  const code = String(randomInt(1_000_000)).padStart(6, "0");
  fleet = await createFleetFixture({ paperRooms: 3, bedrock: async ({ nodeId, pairingLookup }) => {
    const issuedAt = Date.now();
    registration = parseBedrockConfig({ serverId: randomUUID(), nodeId, instanceKey: "tonimbedrock",
      publicKey: host.publicKey, generation: 1, enrollment: {
        id: randomUUID(), lookupId: await pairingLookup(code), issuedAt, expiresAt: issuedAt + 180_000,
      } });
    return registration;
  } });
  const { serverId, nodeId } = registration;
  await writeFile(hostConfig, `serverId=${serverId}\nnodeId=${nodeId}\nserverName=TonimBedrock\n`
      + `relayUrl=${fleet.base.replace("http:", "ws:")}/v1/agent\nrelayPublicKey=${fleet.relayPublicKey}\n`
      + `publicKey=${host.publicKey}\nprivateKey=${host.privateKey}\n`, { mode: 0o600 });
  launch("-cp", `${jar}:${fixtureClasses}`,
    "io.github.zpkdxgames.plexonpanel.bedrock.BedrockBrokerFixtureMain",
    serverId, nodeId, dockerSocket, brokerSocket);
  await eventually(async () => {
    try { await access(brokerSocket); return true; }
    catch (error) { if (error.code === "ENOENT") return false; throw error; }
  });
  launch("-jar", jar, "host-fixture", hostConfig, brokerSocket);
  const health = await eventually(async () => {
    const response = await fetch(`${fleet.base}/healthz`);
    const status = await response.json();
    return status.connections.bedrockHost === 1 && status;
  });
  assert.equal(health.rooms, 4);
  assert.equal(health.connections.paper, 3);
  assert.equal(health.connections.host, 3);
  const claim = await fetch(`${fleet.base}/v1/pairings/claim`, { method: "POST",
    headers: { Origin: fleet.origin, "Content-Type": "application/json" },
    body: JSON.stringify({ code, name: "Integration fixture browser" }) });
  assert.equal(claim.status, 200);
  const credential = await claim.json();
  assert.equal(credential.serverId, serverId);
  browser = new WebSocket(credential.websocketUrl, { protocols: ["plexonpanel-v3", `auth.${credential.accessToken}`],
    headers: { origin: fleet.origin } });
  browser.addEventListener("message", ({ data }) => {
    if (browserEvents.length < 128) browserEvents.push(JSON.parse(data));
  });
  await once(browser, "open");
  const ready = await nextEvent("Bedrock ready", (event) => event.type === "dashboard.ready");
  assert.equal(ready.server.roomMode, "HOST_ONLY");
  assert.equal(ready.server.edition, "BEDROCK");
  assert.equal(ready.server.runtime, "DOCKER");
  assert.equal(ready.server.nodeId, nodeId);
  assert.deepEqual(ready.agents, { paper: false, host: true, hostInstalled: true });
  const memory = await nextEvent("Docker memory", (event) => event.serverId === serverId && event.eventType === "bedrock.status");
  assert.equal(memory.body.containerState, "running");
  assert.equal(memory.body.containerMemoryBytes, 536_870_912);
  assert.equal(memory.body.containerRestartPolicy, "unless-stopped");
  assert.equal(memory.body.gameplayReady, null);
  assert.equal(memory.body.playersOnline, null);
  const logs = await nextEvent("redacted Docker logs", (event) => event.serverId === serverId && event.eventType === "bedrock.logs");
  assert.equal(logs.body.available, true);
  assert.equal(logs.body.lines.length, 1);
  assert.match(logs.body.lines.at(-1).content, /Fixture ready 🧱/);
  assert.doesNotMatch(JSON.stringify(logs.body.lines), /192\.0\.2\.23|1234567890123456|\\u001b/i);
  const cpu = await nextEvent("Docker CPU delta", (event) => event.eventType === "bedrock.status" && event.body.containerCpuPercent > 0);
  assert.equal(cpu.body.cpuUnit, "PERCENT_OF_ONE_CORE");
  assert.ok(cpu.body.containerCpuPercent > 100 && cpu.body.containerCpuPercent < 150);
  assert.equal(cpu.body.version, "1.26.66.1");
  for (let index = 0; index < 3; index++) {
    const legacy = await fleet.browser(index);
    const event = await legacy.next((event) => event.eventType === "service.status");
    assert.equal(event.serverId, fleet.rooms[index].serverId);
    assert.equal(event.body.resources.source, "SYSTEMD_CGROUP");
    legacy.socket.close();
  }
  browser.send(JSON.stringify({ type: "dashboard.action", requestId: randomUUID(), action: "service.restart" }));
  const denied = await nextEvent("read-only denial", (event) => event.type === "relay.error");
  assert.equal(denied.code, "BEDROCK_READ_ONLY");

  dockerAvailable = false;
  const unavailable = await nextEvent("unavailable Docker status",
    (event) => event.eventType === "bedrock.status" && event.body.containerState === "unknown");
  assert.equal(unavailable.body.containerMemoryBytes, null);
  const unavailableLogs = await nextEvent("unavailable Docker logs", (event) => event.eventType === "bedrock.logs" && !event.body.available);
  assert.deepEqual(unavailableLogs.body.lines, []);
  assert.ok(dockerRoutes.length > 10);
  assert.ifError(dockerFailure);
  console.log("Bedrock integration passed: Java JAR, Unix broker, simulated Docker, signed standalone relay, pairing, three simulated Paper rooms, redacted logs, telemetry and read-only denial.");
} finally {
  browser?.close();
  for (const { child } of children) child.kill("SIGTERM");
  await Promise.all(children.map(async ({ child }) => {
    if (child.exitCode !== null || child.signalCode !== null) return;
    await Promise.race([once(child, "exit"), delay(3000)]).catch(() => {});
    if (child.exitCode === null && child.signalCode === null) child.kill("SIGKILL");
  }));
  await fleet?.close();
  if (docker.listening) await new Promise((resolve) => docker.close(resolve));
  await rm(directory, { recursive: true, force: true });
}
