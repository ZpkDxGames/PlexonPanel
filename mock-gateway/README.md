# Loopback mock gateway

This dependency-free Node.js server is for local plugin development. It binds
only to `127.0.0.1`, authenticates the plugin with protocol v2, registers
plugin-generated pairing codes, requests reconnect snapshots, and can send
safe development actions.

It is not the production relay: it has no TLS, browser authorization, durable
identity binding, origin controls, or multi-server abuse protection. Do not
expose it to a LAN or the internet.

## Start

```bash
cd mock-gateway
npm start
```

Copy the printed `gateway.public-key` into plugin configuration:

```yaml
gateway:
  enabled: true
  url: "ws://127.0.0.1:8787/v1/agent"
  public-key: "PASTE_THE_PRINTED_KEY"
  require-signed-messages: true
```

The plugin appends its persistent `serverId` query automatically. Keep remote
actions disabled until a specific test requires one. Restart/reload
PlexonPanel, run `/plexonpanel diagnostics`, then `/plexonpanel pair`.

## Complete pairing

Redeem the displayed code:

```bash
curl -X POST http://127.0.0.1:8787/pair \
  -H 'content-type: application/json' \
  -d '{"code":"123456"}'
```

## Send a safe test action

Enable `remote-actions.enabled` and the relevant local toggle. The default
console policy permits `tps`:

```bash
curl -X POST http://127.0.0.1:8787/action \
  -H 'content-type: application/json' \
  -d '{
    "serverId":"YOUR_SERVER_UUID",
    "action":"console.execute",
    "parameters":{"command":"tps"}
  }'
```

Use `GET /health` and `GET /servers` for development diagnostics. The generated
`.gateway-key.json` is ignored by Git and must remain local.
