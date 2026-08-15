# Mock gateway

This dependency-free Node.js harness tests the Paper agent before the hosted gateway/dashboard exists. It binds only to `127.0.0.1`, implements the signed protocol, authenticates agent identities, issues pairing codes, and can send development actions.

It is not production software: there are no user accounts, RBAC, TLS, durable storage, rate limits, or multi-tenant controls.

## Start

```bash
cd mock-gateway
npm start
```

Copy the printed `gateway.public-key` into the plugin config and set:

```yaml
gateway:
  enabled: true
  url: "ws://127.0.0.1:8787/v1/agent"
  public-key: "PASTE_THE_PRINTED_KEY"
  require-signed-messages: true
```

Keep remote actions disabled until a specific test needs them. Reload PlexonPanel, run `/plexonpanel pair`, and note the code shown in Minecraft/console.

## Complete pairing

Get the server ID from `/plexonpanel status`, then redeem the displayed code:

```bash
curl -X POST http://127.0.0.1:8787/pair \
  -H 'content-type: application/json' \
  -d '{"serverId":"YOUR_SERVER_UUID","code":"123456"}'
```

## Send a safe test action

Enable `remote-actions.enabled` and the relevant local action toggle. The default console policy permits `tps`:

```bash
curl -X POST http://127.0.0.1:8787/action \
  -H 'content-type: application/json' \
  -d '{
    "serverId":"YOUR_SERVER_UUID",
    "action":"console.execute",
    "parameters":{"command":"tps"}
  }'
```

Use `GET /health` and `GET /servers` for development diagnostics. The generated `.gateway-key.json` is ignored by Git and should remain local.
