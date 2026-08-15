# Agent protocol v1

PlexonPanel uses UTF-8 JSON text messages over WebSocket. Every message is a signed envelope; the `body` field is Base64URL-encoded UTF-8 JSON so its signed representation is unambiguous.

The normative envelope shape is [protocol/envelope.schema.json](../protocol/envelope.schema.json).

## Signature input

Ed25519 signs the UTF-8 bytes of these fields separated by a single LF (`\n`) and with no trailing LF:

```text
protocolVersion
type
messageId
serverId
timestamp
body
```

`signature` is unpadded Base64URL. Agent public keys use Base64-encoded X.509 SubjectPublicKeyInfo; agent private keys use Base64-encoded PKCS#8 and are never transmitted.

## Limits and freshness

- Envelope: 1 MiB maximum UTF-8 size.
- Decoded body: 512 KiB maximum.
- Default inbound clock skew: 30 seconds (configurable from 5–300 seconds).
- Message IDs: UUIDs and single-use inside the replay window.
- Protocol version mismatch: reject, do not guess compatibility.

## Agent-to-gateway messages

| Type | Purpose |
| --- | --- |
| `agent.hello` | Version, public identity, runtime labels, pairing status |
| `agent.challenge_response` | Device proof for a gateway nonce |
| `agent.pair_request` | Ask for a short-lived pairing code |
| `agent.unpair_request` | Ask the control plane to revoke the binding |
| `telemetry.system` | CPU, memory, disk, and process uptime |
| `telemetry.server` | Paper version, players, TPS, and tick-time statistics |
| `inventory.players` | Current player snapshot |
| `inventory.plugins` | Current plugin snapshot |
| `console.lines` | Bounded batch of redacted/classified new log lines |
| `chat.message` | Accepted global chat message |
| `action.result` | Decision/result for one remote action request |

## Gateway-to-agent messages

| Type | Purpose |
| --- | --- |
| `gateway.challenge` | Random nonce the device signs with a domain prefix |
| `pairing.code` | Code and ISO-8601 expiry shown only to an authorized server operator |
| `pairing.complete` | Mark the local identity as paired |
| `pairing.revoked` | Clear local pairing state |
| `heartbeat.ack` | Application heartbeat acknowledgement |
| `action.request` | One privileged action request |

An `action.request` body has this shape:

```json
{
  "requestId": "request-018f",
  "action": "player.kick",
  "actorId": "account_123",
  "actorDisplayName": "Alex Admin",
  "parameters": {
    "playerId": "66431911-ce8c-48f3-9846-4754a8af21ef",
    "reason": "Requested by an administrator"
  }
}
```

Supported action names are `console.execute`, `player.message`, `player.kick`, `player.ban`, `player.unban`, `player.whitelist`, and `chat.global.send`. The agent applies global and per-action policy locally; the gateway must also enforce RBAC before sending.

## Compatibility

Additive body fields may be ignored within protocol v1. Changing signature canonicalization, field meaning, required envelope fields, or action semantics requires a new protocol version.
