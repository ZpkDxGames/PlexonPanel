import {
  createHash,
  createPrivateKey,
  createPublicKey,
  generateKeyPairSync,
  randomUUID,
  sign,
  verify,
} from 'node:crypto';
import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import { createServer } from 'node:http';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const HOST = '127.0.0.1';
const PORT = Number.parseInt(process.env.PORT ?? '8787', 10);
const MAX_FRAME_BYTES = 1_048_576;
const directory = dirname(fileURLToPath(import.meta.url));
const keyPath = join(directory, '.gateway-key.json');
const contexts = new Map();
const pairedDevices = new Set();

const gatewayKeys = loadOrCreateKeys();
const gatewayPublicKeyBase64 = gatewayKeys.publicKey.export({ format: 'der', type: 'spki' }).toString('base64');

const server = createServer(async (request, response) => {
  try {
    if (request.method === 'GET' && request.url === '/health') {
      return json(response, 200, {
        ok: true,
        connectedServers: [...contexts.values()].filter((context) => !context.socket.destroyed).length,
      });
    }
    if (request.method === 'GET' && request.url === '/servers') {
      return json(response, 200, {
        servers: [...contexts.values()].map((context) => ({
          serverId: context.serverId,
          authenticated: context.authenticated,
          paired: context.paired,
          lastMessageType: context.lastMessageType,
          lastSeenAt: context.lastSeenAt,
        })),
      });
    }
    if (request.method === 'POST' && request.url === '/pair') {
      const body = await readJsonBody(request);
      const code = typeof body.code === 'string' ? body.code : '';
      if (!/^\d{6}$/.test(code)) {
        return json(response, 400, { ok: false, error: 'A six-digit pairing code is required' });
      }
      const matches = [...contexts.values()].filter((candidate) => (
        candidate.authenticated
        && candidate.pairing?.code === code
        && Date.now() < candidate.pairing.expiresAt
      ));
      if (matches.length !== 1) {
        return json(response, 403, { ok: false, error: 'Invalid or expired pairing code' });
      }
      const [context] = matches;
      context.paired = true;
      pairedDevices.add(context.serverId);
      context.pairing = null;
      sendEnvelope(context, 'pairing.complete', { pairedAt: new Date().toISOString() });
      return json(response, 200, { ok: true, serverId: context.serverId });
    }
    if (request.method === 'POST' && request.url === '/action') {
      const body = await readJsonBody(request);
      const context = requiredContext(body.serverId);
      if (!context.authenticated || !context.paired) {
        return json(response, 403, { ok: false, error: 'Server is not authenticated and paired' });
      }
      if (typeof body.action !== 'string' || body.action.length > 64
          || body.parameters === null || typeof body.parameters !== 'object' || Array.isArray(body.parameters)) {
        return json(response, 400, { ok: false, error: 'action and parameters are required' });
      }
      const requestId = `mock-${randomUUID()}`;
      sendEnvelope(context, 'action.request', {
        requestId,
        action: body.action,
        actorId: body.actorId ?? 'mock-admin',
        actorDisplayName: body.actorDisplayName ?? 'Mock Admin',
        parameters: body.parameters,
      });
      return json(response, 202, { ok: true, requestId });
    }

    return json(response, 404, {
      ok: false,
      routes: ['GET /health', 'GET /servers', 'POST /pair', 'POST /action'],
    });
  } catch (error) {
    return json(response, error.statusCode ?? 400, { ok: false, error: error.message });
  }
});

server.on('upgrade', (request, socket) => {
  const upgradeUrl = new URL(request.url ?? '/', `http://${HOST}:${PORT}`);
  const expectedServerId = upgradeUrl.searchParams.get('serverId');
  if (upgradeUrl.pathname !== '/v1/agent'
      || !/^[0-9a-f-]{36}$/i.test(expectedServerId ?? '')
      || request.headers.upgrade?.toLowerCase() !== 'websocket') {
    socket.end('HTTP/1.1 404 Not Found\r\nConnection: close\r\n\r\n');
    return;
  }
  const clientKey = request.headers['sec-websocket-key'];
  if (typeof clientKey !== 'string') {
    socket.end('HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n');
    return;
  }
  const accept = createPublicKeyHash(clientKey);
  socket.write([
    'HTTP/1.1 101 Switching Protocols',
    'Upgrade: websocket',
    'Connection: Upgrade',
    `Sec-WebSocket-Accept: ${accept}`,
    '\r\n',
  ].join('\r\n'));

  const context = {
    socket,
    buffer: Buffer.alloc(0),
    expectedServerId,
    serverId: null,
    agentPublicKey: null,
    fingerprint: null,
    challenge: null,
    authenticated: false,
    paired: false,
    pairing: null,
    lastMessageType: null,
    lastSeenAt: null,
  };
  socket.on('data', (chunk) => consumeFrames(context, chunk));
  socket.on('error', (error) => console.error(`[socket] ${error.message}`));
  socket.on('close', () => {
    if (context.serverId && contexts.get(context.serverId) === context) {
      contexts.delete(context.serverId);
    }
  });
});

server.listen(PORT, HOST, () => {
  console.log(`PlexonPanel local gateway: ws://${HOST}:${PORT}/v1/agent`);
  console.log(`HTTP controls: http://${HOST}:${PORT}`);
  console.log(`gateway.public-key: ${gatewayPublicKeyBase64}`);
  console.log('Loopback development only. Do not expose this process to a network.');
});

function loadOrCreateKeys() {
  if (existsSync(keyPath)) {
    const stored = JSON.parse(readFileSync(keyPath, 'utf8'));
    return {
      privateKey: createPrivateKey({ key: Buffer.from(stored.privateKey, 'base64'), format: 'der', type: 'pkcs8' }),
      publicKey: createPublicKey({ key: Buffer.from(stored.publicKey, 'base64'), format: 'der', type: 'spki' }),
    };
  }
  const generated = generateKeyPairSync('ed25519');
  writeFileSync(keyPath, JSON.stringify({
    privateKey: generated.privateKey.export({ format: 'der', type: 'pkcs8' }).toString('base64'),
    publicKey: generated.publicKey.export({ format: 'der', type: 'spki' }).toString('base64'),
  }, null, 2), { encoding: 'utf8', mode: 0o600, flag: 'wx' });
  return generated;
}

function createPublicKeyHash(clientKey) {
  return createHash('sha1')
    .update(clientKey + '258EAFA5-E914-47DA-95CA-C5AB0DC85B11')
    .digest('base64');
}

function consumeFrames(context, chunk) {
  context.buffer = Buffer.concat([context.buffer, chunk]);
  while (context.buffer.length >= 2) {
    const first = context.buffer[0];
    const second = context.buffer[1];
    const finalFrame = (first & 0x80) !== 0;
    const opcode = first & 0x0f;
    const masked = (second & 0x80) !== 0;
    let length = second & 0x7f;
    let offset = 2;

    if (!masked) {
      return closeSocket(context.socket, 1002, 'Client frames must be masked');
    }
    if (length === 126) {
      if (context.buffer.length < 4) return;
      length = context.buffer.readUInt16BE(2);
      offset = 4;
    } else if (length === 127) {
      if (context.buffer.length < 10) return;
      const longLength = context.buffer.readBigUInt64BE(2);
      if (longLength > BigInt(MAX_FRAME_BYTES)) {
        return closeSocket(context.socket, 1009, 'Frame too large');
      }
      length = Number(longLength);
      offset = 10;
    }
    if (length > MAX_FRAME_BYTES) {
      return closeSocket(context.socket, 1009, 'Frame too large');
    }
    if (context.buffer.length < offset + 4 + length) return;

    const mask = context.buffer.subarray(offset, offset + 4);
    const payload = Buffer.from(context.buffer.subarray(offset + 4, offset + 4 + length));
    context.buffer = context.buffer.subarray(offset + 4 + length);
    for (let index = 0; index < payload.length; index += 1) {
      payload[index] ^= mask[index % 4];
    }

    if (opcode === 0x8) {
      context.socket.end(encodeFrame(0x8, payload));
      return;
    }
    if (opcode === 0x9) {
      context.socket.write(encodeFrame(0xA, payload));
      continue;
    }
    if (opcode !== 0x1 || !finalFrame) {
      return closeSocket(context.socket, 1003, 'Only complete text frames are supported');
    }
    handleAgentMessage(context, payload.toString('utf8'));
  }
}

function handleAgentMessage(context, text) {
  try {
    const { envelope, body } = decodeEnvelope(text);
    if (envelope.type === 'agent.hello') {
      const publicKey = createPublicKey({
        key: Buffer.from(body.publicKey, 'base64'),
        format: 'der',
        type: 'spki',
      });
      if (!verifyEnvelope(envelope, publicKey)) throw new Error('Invalid agent hello signature');
      if (envelope.serverId !== context.expectedServerId) throw new Error('Agent URL server ID mismatch');
      context.serverId = envelope.serverId;
      context.agentPublicKey = publicKey;
      context.fingerprint = body.publicKeyFingerprint;
      context.paired = pairedDevices.has(context.serverId);
      contexts.set(context.serverId, context);
      context.challenge = randomUUID();
      sendEnvelope(context, 'gateway.challenge', { nonce: context.challenge });
    } else {
      if (!context.agentPublicKey || envelope.serverId !== context.serverId) throw new Error('Agent identity is not established');
      if (!verifyEnvelope(envelope, context.agentPublicKey)) throw new Error('Invalid agent signature');
      if (envelope.type === 'agent.challenge_response') {
        const validProof = body.nonce === context.challenge && verify(
          null,
          Buffer.from(`challenge:${body.nonce}`, 'utf8'),
          context.agentPublicKey,
          Buffer.from(body.proof, 'base64url'),
        );
        if (!validProof) throw new Error('Invalid challenge response');
        context.authenticated = true;
        sendEnvelope(context, 'gateway.authenticated', {
          authenticatedAt: new Date().toISOString(),
          paired: context.paired,
        });
        console.log(`[agent] authenticated ${context.serverId}`);
      } else if (envelope.type === 'agent.pairing_begin') {
        if (!context.authenticated) throw new Error('Agent must authenticate before pairing');
        const requestedExpiry = Date.parse(body.expiresAt);
        const maximumExpiry = Date.now() + 5 * 60_000 + 30_000;
        const collision = [...contexts.values()].some((candidate) => (
          candidate !== context
          && candidate.pairing?.code === body.code
          && Date.now() < candidate.pairing.expiresAt
        ));
        if (!/^\d{6}$/.test(body.code)
            || typeof body.requestId !== 'string'
            || body.fingerprint !== context.fingerprint
            || !Number.isFinite(requestedExpiry)
            || requestedExpiry <= Date.now()
            || requestedExpiry > maximumExpiry
            || collision) {
          sendEnvelope(context, 'pairing.rejected', {
            requestId: typeof body.requestId === 'string' ? body.requestId : 'invalid',
            reason: collision ? 'code_collision' : 'invalid_request',
          });
          return;
        }
        const challengeId = randomUUID();
        context.pairing = {
          code: body.code,
          requestId: body.requestId,
          challengeId,
          expiresAt: requestedExpiry,
        };
        sendEnvelope(context, 'pairing.registered', {
          requestId: body.requestId,
          challengeId,
          expiresAt: new Date(requestedExpiry).toISOString(),
        });
        console.log(`[pair] registered challenge for ${context.serverId}`);
      } else if (envelope.type === 'agent.unpair_request') {
        context.paired = false;
        pairedDevices.delete(context.serverId);
        context.pairing = null;
        sendEnvelope(context, 'pairing.revoked', { revokedAt: new Date().toISOString() });
      } else if (envelope.type === 'action.result') {
        console.log(`[result] ${body.requestId} ${body.code}: ${body.message}`);
      }
    }
    context.lastMessageType = envelope.type;
    context.lastSeenAt = new Date().toISOString();
  } catch (error) {
    console.error(`[reject] ${error.message}`);
    closeSocket(context.socket, 1008, 'Invalid signed protocol message');
  }
}

function decodeEnvelope(text) {
  if (Buffer.byteLength(text, 'utf8') > MAX_FRAME_BYTES) throw new Error('Envelope is too large');
  const envelope = JSON.parse(text);
  const required = ['protocolVersion', 'type', 'messageId', 'serverId', 'timestamp', 'body', 'signature'];
  for (const field of required) {
    if (envelope[field] === undefined || envelope[field] === null) throw new Error(`Missing envelope field ${field}`);
  }
  if (envelope.protocolVersion !== 2) throw new Error('Unsupported protocol version');
  const body = JSON.parse(Buffer.from(envelope.body, 'base64url').toString('utf8'));
  return { envelope, body };
}

function verifyEnvelope(envelope, publicKey) {
  return verify(null, Buffer.from(signable(envelope), 'utf8'), publicKey, Buffer.from(envelope.signature, 'base64url'));
}

function sendEnvelope(context, type, body) {
  const envelope = {
    protocolVersion: 2,
    type,
    messageId: randomUUID(),
    serverId: context.serverId,
    timestamp: new Date().toISOString(),
    body: Buffer.from(JSON.stringify(body), 'utf8').toString('base64url'),
    signature: '',
  };
  envelope.signature = sign(null, Buffer.from(signable(envelope), 'utf8'), gatewayKeys.privateKey).toString('base64url');
  context.socket.write(encodeFrame(0x1, Buffer.from(JSON.stringify(envelope), 'utf8')));
}

function signable(envelope) {
  return [
    envelope.protocolVersion,
    envelope.type,
    envelope.messageId,
    envelope.serverId,
    envelope.timestamp,
    envelope.body,
  ].join('\n');
}

function encodeFrame(opcode, payload) {
  const length = payload.length;
  if (length < 126) return Buffer.concat([Buffer.from([0x80 | opcode, length]), payload]);
  if (length <= 0xffff) {
    const header = Buffer.alloc(4);
    header[0] = 0x80 | opcode;
    header[1] = 126;
    header.writeUInt16BE(length, 2);
    return Buffer.concat([header, payload]);
  }
  const header = Buffer.alloc(10);
  header[0] = 0x80 | opcode;
  header[1] = 127;
  header.writeBigUInt64BE(BigInt(length), 2);
  return Buffer.concat([header, payload]);
}

function closeSocket(socket, code, reason) {
  const reasonBytes = Buffer.from(reason, 'utf8').subarray(0, 123);
  const payload = Buffer.alloc(2 + reasonBytes.length);
  payload.writeUInt16BE(code, 0);
  reasonBytes.copy(payload, 2);
  socket.end(encodeFrame(0x8, payload));
}

function requiredContext(serverId) {
  const context = contexts.get(serverId);
  if (!context || context.socket.destroyed) {
    const error = new Error('Unknown or disconnected serverId');
    error.statusCode = 404;
    throw error;
  }
  return context;
}

async function readJsonBody(request) {
  const chunks = [];
  let size = 0;
  for await (const chunk of request) {
    size += chunk.length;
    if (size > 65_536) throw new Error('Request body is too large');
    chunks.push(chunk);
  }
  return JSON.parse(Buffer.concat(chunks).toString('utf8'));
}

function json(response, statusCode, body) {
  const content = Buffer.from(JSON.stringify(body, null, 2), 'utf8');
  response.writeHead(statusCode, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': content.length,
    'Cache-Control': 'no-store',
  });
  response.end(content);
}
