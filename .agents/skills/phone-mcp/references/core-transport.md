# Phone MCP core transport

## Endpoints

- `POST /mcp`: JSON-RPC 2.0 `initialize`, `tools/list`, `tools/call`, `ping`. Requires `MCP-Protocol-Version: 2025-06-18` and `Accept: application/json, text/event-stream`. Origin-checked.
- `GET /mcp/events`: Bearer-required SSE push of `notifications/tools/list_changed` on every external-capability change (`:ping` keepalive every 25 s). Hold open; re-call `tools/list` on each event.
- `GET /media/{id}`: Bearer-required sidecar blob (`cacheDir/mcp/`, 15-min TTL, 100 MB cap, `Range` supported).
- `GET /info`: Bearer-required server and tool counts plus TLS fingerprint.
- `GET /healthz`: open liveness probe.

## Call shape

```bash
curl -sk -X POST https://192.168.1.50:8080/mcp \
 -H 'Content-Type: application/json' \
 -H 'Accept: application/json, text/event-stream' \
 -H 'MCP-Protocol-Version: 2025-06-18' \
 -H "Authorization: Bearer $TOKEN" \
 -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"vibrate","arguments":{}}}'
```

Success returns `result.content[]` with `isError:false`. Domain failures return `isError:true` naming the kill-switch or permission.

## Pairing

```bash
phone-mcp pair --qr '<dashboard-qr-json>'
phone-mcp smoke                # expect 4x PASS
phone-mcp configure --harness all --out ./generated-harness
```

USB fallback: `adb -s <serial> forward tcp:8080 tcp:8080`, then use `https://127.0.0.1:8080/mcp` (same token + SHA-256 pin; pin/cafile modes skip the hostname check so loopback works). Headless start/stop needs the QS tile added once: `adb shell cmd statusbar click-tile com.phonemcp.server/.service.McpServerTile`.

## TLS modes (`PHONE_MCP_*` env)

Pin (`SHA256` TOFU hash-compare), cafile (PEM from `phone-mcp export-ca`, chain vs pinned leaf, no hostname check for DHCP churn), pin+cafile (strongest), `INSECURE=1` (trusted LAN only), default stock verify (fails closed). Every path offers ALPN `http/1.1`.

Verify TOFU once out-of-band:

```bash
echo | openssl s_client -connect 192.168.1.50:8080 2>/dev/null \
 | openssl x509 -fingerprint -sha256 -noout
```

The output must match the dashboard grouping before pinning.

## Troubleshooting

| Symptom | Fix |
|---|---|
| Hang, curl works | Missing ALPN offer; use the bridge or set ALPN `http/1.1`. |
| 401 / `-32001` | Wrong or rotated token; re-copy the pairing card and re-pair. |
| Self-signed / verify failed | Expected TOFU; set `PHONE_MCP_SHA256` or use `export-ca`. |
| Fingerprint mismatch | Phone identity rotated; re-verify dashboard value. |
| `-32600` version or origin | Keep protocol header and SSE accept; bridge sets both. |
| Second stream `-32603` | One live stream per kind; stop the first. |
| Oversize body | Limit is 1 MiB; 10 KB tokens hit the Netty header ceiling. |
