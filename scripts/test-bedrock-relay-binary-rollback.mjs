// Rehearse an upgrade and binary rollback using only disposable loopback peers and SQLite state.
import assert from "node:assert/strict";
import { randomInt, randomUUID } from "node:crypto";
import { once } from "node:events";
import { dirname, join, resolve } from "node:path";
import { pathToFileURL } from "node:url";

const [checkoutPath, candidatePath, legacyPath] = process.argv.slice(2);
assert.ok(checkoutPath && candidatePath && legacyPath,
  "Usage: node scripts/test-bedrock-relay-binary-rollback.mjs <dashboard-checkout> <candidate-package> <legacy-5.0-package>");
function load(root, relative) { return import(pathToFileURL(join(resolve(root), relative)).href); }
const { createMixedFixture } = await load(checkoutPath, "relay/tests/support/bedrock-fixture.mjs");
const { inbox } = await load(checkoutPath, "relay/tests/support/fleet-fixture.mjs");
const { publicKeyFingerprint } = await load(checkoutPath, "relay/dist/protocol.js");
const entrypoint = "relay/dist/standalone/server.js";
const candidate = await load(candidatePath, entrypoint);
const legacy = await load(legacyPath, entrypoint);
const f = await createMixedFixture({ autoHost: false });
const { bedrock, ...fixtureConfig } = f.relay.config;
const legacyConfig = { ...fixtureConfig, databasePath: join(dirname(fixtureConfig.databasePath), "legacy-first.sqlite") };
const port = f.relay.server.address().port;
let relay = f.relay;

function request(path, options = {}) {
  return fetch(`${f.base}${path}`, { ...options, signal: AbortSignal.timeout(8000),
    headers: { Origin: f.origin, ...options.headers } });
}
function session(grant) {
  return request("/v1/dashboard/session", { headers: { Authorization: `Bearer ${grant.accessToken}` } });
}
async function start(binary, config, version, bedrockConnections) {
  await relay.close();
  relay = binary.createStandaloneRelay({ ...config, port });
  await relay.start();
  for (const room of f.rooms) {
    await f.attachJava(room, "PAPER");
    await f.attachJava(room, "HOST");
  }
  await f.telemetry();
  const health = await (await request("/healthz")).json();
  assert.equal(health.version, version);
  assert.equal(health.protocolVersion, 3);
  assert.equal(health.connections.paper, 3);
  assert.equal(health.connections.host, 3);
  if (bedrockConnections !== undefined) assert.equal(health.connections.bedrockHost, bedrockConnections);
  return health;
}

async function pairOnLegacy(room) {
  const received = inbox(room.paper.socket, true);
  const fingerprint = await publicKeyFingerprint(room.paperKeys.publicKey);
  const requestId = randomUUID();
  let code;
  do { code = String(randomInt(1_000_000)).padStart(6, "0"); } while (code === f.code);
  const scopes = ["server.status"];
  await room.paper.send("agent.pairing_begin", { requestId, code, fingerprint, role: "Owner", scopes,
    expiresAt: new Date(Date.now() + 60_000).toISOString() });
  await received(m => m.envelope.type === "pairing.registered");
  const pending = request("/v1/pairings/claim", { method: "POST", headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ code, name: "Phase 2.5 disposable Paper browser" }) });
  const { body: consume } = await received(m => m.envelope.type === "pairing.consume");
  assert.equal(consume.requestId, requestId);
  const issuedAt = Math.floor(Date.now() / 1000);
  await room.paper.send("pairing.accepted", { requestId, generation: 1, revision: room.revision++,
    device: { deviceId: consume.deviceId, name: consume.name, role: "Owner", scopes, issuedAt, expiresAt: issuedAt + 3600,
      lastSeen: issuedAt } });
  const response = await pending;
  assert.equal(response.status, 200);
  const grant = await response.json();
  assert.equal(grant.serverId, room.serverId);
  assert.deepEqual(grant.scopes, scopes);
  return grant;
}

async function checkLegacyGrant(grant, room) {
  assert.equal((await session(grant)).status, 200);
  const browser = await f.browser(grant);
  try {
    const ready = await browser.next(m => m.type === "dashboard.ready");
    assert.equal(ready.serverId, room.serverId);
    assert.deepEqual(ready.device.scopes, grant.scopes);
    const allowed = randomUUID();
    browser.socket.send(JSON.stringify({ type: "dashboard.action", action: "server.status", requestId: allowed, parameters: {} }));
    await browser.next(m => m.eventType === "action.result" && m.body.requestId === allowed);
    assert.deepEqual(f.requests.filter(x => x.requestId === allowed).map(x => [x.serverId, x.kind]), [[room.serverId, "HOST"]]);
    const denied = randomUUID();
    browser.socket.send(JSON.stringify({ type: "dashboard.action", action: "server.start", requestId: denied, parameters: {} }));
    const refusal = await browser.next(m => m.type === "dashboard.action_rejected" && m.requestId === denied);
    assert.equal(refusal.code, "SCOPE_DENIED");
    assert.equal(f.requests.some(x => x.requestId === denied), false);
  } finally { browser.socket.close(); }
}

try {
  const before = await start(legacy, legacyConfig, "5.0.0");
  const grants = [];
  for (const room of f.rooms) grants.push(await pairOnLegacy(room));
  for (const [index, room] of f.rooms.entries()) await checkLegacyGrant(grants[index], room);
  console.log("PASS three actual Relay 5.0.0 pairing flows and scope enforcement");

  const upgraded = await start(candidate, { ...legacyConfig, bedrock }, "5.0.0-bedrock.1", 0);
  for (const key of ["protocolVersion", "fleetContract", "actionContract", "configurationSchemas"])
    assert.deepEqual(upgraded[key], before[key]);
  for (const [index, room] of f.rooms.entries()) await checkLegacyGrant(grants[index], room);
  await f.attach();
  const bedrockClaim = await f.claim();
  assert.equal(bedrockClaim.status, 200);
  const bedrockGrant = await bedrockClaim.json();
  const browser = await f.browser(bedrockGrant);
  await browser.next(m => m.type === "dashboard.ready");
  browser.socket.send(JSON.stringify({ type: "dashboard.action", action: "server.start", requestId: randomUUID(), parameters: {} }));
  assert.equal((await browser.next(m => m.type === "relay.error")).code, "BEDROCK_READ_ONLY");
  browser.socket.close();
  const revocable = await f.browser(grants[0]);
  const closed = once(revocable.socket, "close", { signal: AbortSignal.timeout(5000) });
  await f.sync(f.rooms[0], []);
  assert.equal((await closed)[0].code, 4003);
  assert.equal((await session(grants[0])).status, 401);
  console.log("PASS Protocol 3 candidate cutover preserves 5.0 browser tokens and routing, admits read-only Bedrock and applies Paper revocation");

  const rolledBack = await start(legacy, legacyConfig, "5.0.0");
  assert.equal(rolledBack.connections.bedrockHost ?? 0, 0);
  assert.equal((await session(bedrockGrant)).status, 401);
  assert.equal((await session(grants[0])).status, 401);
  for (let i = 1; i < grants.length; i++) await checkLegacyGrant(grants[i], f.rooms[i]);
  console.log("PASS Relay 5.0.0 binary rollback reuses SQLite, keeps three signed Paper/Host rooms, retains revocation and denies the Bedrock token");
} finally {
  await relay.close();
  await f.close();
}
