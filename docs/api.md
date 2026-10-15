# Phone MCP — API Reference

Base: `https://<phone-lan-ip>:8080`. TLS-only (self-signed TOFU — verify SHA-256 against dashboard once, then pin). Protocol `2025-06-18` (min `2025-03-26`).

Auth: `Authorization: Bearer <token>` (dashboard pairing card, QR, rotation) on `/mcp`, `/media/*`, `/info`. `/healthz` open. Failure → HTTP 401 + JSON-RPC `-32001`.

Generated from `mcp/McpRouter.kt` + `data/*Provider.kt` + `NetInspectCatalog.kt`. `tools/list` declares **45 static tools** (28 + 13 `net_*` + 4 `capabilities_*`) plus dynamic `ext_<app>__<tool>` entries from registered third-party capabilities (see below). `initialize` advertises `capabilities.tools.listChanged: true`.

## Transports

| Endpoint | Auth | Description |
|----------|------|-------------|
| `POST /mcp` | Bearer | JSON-RPC 2.0: `initialize`, `tools/list`, `tools/call`, `ping`. Requires `MCP-Protocol-Version: 2025-06-18` + `Accept: application/json, text/event-stream`. Origin-checked; bad version/origin → `-32600`. `GET /mcp` → 401 without token, else 405. |
| `GET /media/{id}` | Bearer | Sidecar blob (`cacheDir/mcp/`, 15-min TTL, 100 MB cap). Range supported (`Range: bytes=0-99` → 206). |
| `GET /info` | Bearer | Server/tool counts, TLS fingerprint. Also `externalTools`, `externalCapabilities`, `capabilitiesVersion` (dynamic-capability deltas). |
| `GET /healthz` | open | Liveness (service started?). |

## External app capabilities (dynamic tools)

Third-party on-device apps register tool bundles over local IPC (bound
service, directory ContentProvider, announce broadcast — ADR-0032, full
provider guide: `docs/external-capabilities.md`). Registered tools appear
as `ext_<capabilityId>__<tool>` in `tools/list` and standard `tools/call`.

| Tool | Args | Notes |
|------|------|-------|
| `capabilities_list` | — | Registry snapshot + `capabilitiesVersion` (poll for changes). |
| `capabilities_register` | `capabilityId` + `packageName` (req), `displayName`, `version`, `tools[]` (req) | Provisions a record for an installed provider app. |
| `capabilities_unregister` | `capabilityId` (req) | Removes a record. |
| `capabilities_rescan` | `packageName` (opt) | Re-fetches authoritative descriptors from installed provider apps. |

Invocation of `ext_*` tools is gated by the `ext_` kill-switch (dashboard
**External App Capabilities**, default OFF); unenabled calls return
`isError` naming the kill-switch. Unknown/malformed `ext_*` names return
`-32602`; unreachable providers and timeouts (>25 s) return `isError`.

## Calling convention

```bash
curl -sk -X POST https://192.168.1.50:8080/mcp \
 -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
 -H 'MCP-Protocol-Version: 2025-06-18' -H "Authorization: Bearer $TOKEN" \
 -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"vibrate","arguments":{}}}'
```

Success → `result {content[], isError:false}`. Tool-logic failure → `result {content[], isError:true}` naming the kill-switch/permission. Protocol errors only `-32601` (unknown method/tool) / `-32602` (bad args) / `-32603` (busy/internal) / `-32001` (auth) / `-32600` (transport gate).

## Agent harnesses

Harnesses (OpenCode, Claude Code/Desktop, Codex, VS Code, Cursor, Gemini CLI,
Cline/Windsurf) connect through the unified `phone-mcp` client
(`phone-mcp serve` stdio bridge — stock TLS stacks reject the self-signed
TOFU cert, so the client owns Bearer + SHA-256 pinning; guide: `docs/client.md`):

```bash
export PHONE_MCP_URL="https://192.168.1.50:8080/mcp" PHONE_MCP_TOKEN="<pairing-card>"
export PHONE_MCP_SHA256="<dashboard-fingerprint>"
python3 scripts/phone-mcp-smoke.py --url "$PHONE_MCP_URL" --token "$PHONE_MCP_TOKEN" --sha256 "$PHONE_MCP_SHA256"
./scripts/configure-harness.sh --from-qr '<qr-json>' --harness all
```

Full matrix: `docs/harness.md` + `config/harness-examples/` (per-harness
configs, tokens via env only). Bridge twins: `bridges/phone-mcp-bridge.mjs`
(Node, preferred) / `bridges/phone-mcp-bridge.py` (stdlib Python).

## Tools

### Sensors / location
| Tool | Args | Notes |
|------|------|-------|
| `sensors_read` | `types[]`, `timeoutMs` (5s cap) | Accelerometer/light/etc snapshot. |
| `sensors_stream` | `durationMs`, `intervalMs`, `includeLocation` | SSE batches + GPS; one stream at a time (second → `-32603`). |
| `location_read` | — | GPS fix; emulator needs mock route. |

### Audio / camera / output
| Tool | Args | Notes |
|------|------|-------|
| `audio_record` | `durationMs` 1000..30000 | AAC `.m4a` → `/media` URL. `audio_*` kill-switch default OFF. |
| `audio_stream` | `durationMs`, `chunkMs` | SSE base64 PCM 16 kHz s16le 32 KB/chunk + `rms`; one stream at a time. |
| `camera_capture` | `lens: back\|front` | JPG → `/media`. `camera_*` OFF by default; foreground grant (API 34). |
| `camera_video` | `durationMs`, `withAudio` | H.264 `.mp4` → `/media` (30s cap). |
| `speaker_say` | `text` (req, ≤1000), `rate`, `pitch` 0.5..2.0 | On-device TTS; blank → `-32602`. Check `output` field for `remote_submix(mirrored)` (scrcpy forwarding). |
| `vibrate` | `durationMs` 1..5000 (def 200) | One-shot buzz. |
| `torch` | `on: boolean` (req) | Needs `CAMERA` runtime. |

### BLE / NFC / system
| Tool | Args | Notes |
|------|------|-------|
| `ble_scan` | `durationMs` 2000..30000 | Unique devices; pre-31 needs location perm. API 31+ without `neverForLocation` also needs a location grant to deliver results (empty success otherwise). Classic (BR/EDR) peers are invisible by design. |
| `ble_connect` | `address` (req) | GATT + service discovery table. Fails `133` against non-LE or unreachable peers. |
| `ble_read` | `address`, `serviceUuid`, `charUuid` | Read characteristic. |
| `ble_write` | same + `valueHex` (≤4 MB cap) | Actuates hardware — `ble_write` kill-switch gated (denial asserted, never fired). |
| `ble_disconnect` | `address` | Drop GATT. Always call after a session (verify script does). |
| `ble_subscribe` | `address`, `serviceUuid`, `charUuid`, `durationMs` | Notify-collect sweep (first 10 chars × 2s in E2E). Needs a notifiable char (`-32602` otherwise); unbonded HID CCCD writes are rejected at system level (honest error, needs bonding); indicate-only chars get the 0x0002 CCCD value. Idle peripherals drop links — reconnect first after >30 s idle. |
| `nfc_status` | — | Adapter/enabled + tap-intake state. Foreground-only read path. |
| `nfc_read` | `timeoutMs` (45s window) | Waits for human tap; NXP UID + NDEF. |
| `clipboard_get` | — | Foreground-only (API 29+); null headless. |
| `clipboard_set` | `text` (req) | Works headless. |
| `battery_read` | — | Level/charging/temp, no perm. |
| `device_info` | — | Model/API, app version, protocol, uptime, `tlsSha256` (= dashboard fingerprint). |

### Network inspection (`net_*` kill-switch default OFF; HTTPS never decrypted — SNI/host only)
| Tool | Args | Notes |
|------|------|-------|
| `net_status` | — | `vpnActive`, interfaces, rooted. |
| `net_connections` | — | Live flows + per-app package + SNI (incl. tcp6/udp6, ip6-58 multicast). |
| `net_usage` | — | Per-UID counters (TrafficStats). |
| `net_capture_start` | policy args | Needs dashboard VPN consent first (`vpn_consent_required`). |
| `net_capture_stop` | — | Stops ring capture. |
| `net_export_pcap` | `format: pcap\|pcapng` | Validated via `tshark -r`; download via `/media`. |
| `net_export_har` | — | HAR 1.2 (captured HTTP; HTTPS = SNI metadata only). |
| `net_firewall_get` | — | Current policy. |
| `net_firewall_set` | `blockedHosts[]`, CIDR | Live kill (`blocked` flow + curl 000); reset restores 200. |
| `net_blocklist_check` | `host` | Offline probe (no VPN needed). |
| `net_geo_lookup` | `ip` | Offline probe. |
| `net_tls_info` | keylog import/export args | `storedKeys`, `observedSniFlows`, `interception:false`. |
| `net_stream` | clamps | SSE 3 batches + final; second stream → `-32603`. |

### UI automation (`ui_*` kill-switch; AccessibilityService; passwords redacted)
| Tool | Args | Notes |
|------|------|-------|
| `ui_screenshot` | — | PNG 1080×2400; needs `canTakeScreenshot` (API 30+); 10s timeout; nodes recycled. |
| `ui_dump` | — | Node tree (≈52 nodes proven). |
| `ui_click` | `x`, `y` or node selector | Graceful miss (no throw). |
| `ui_swipe` | `x1,y1,x2,y2`, `durationMs` | Gesture inject. |
| `ui_type` | `text` | Types into focused field. |
| `ui_key` | `keyCode` | Key events. |

## SSE / streaming errors
- Second concurrent `sensors_stream`/`audio_stream`/`net_stream` → `-32603` in ~0.2s; primary unaffected (12/12).
- Oversize body (>1 MiB) → 413; 10 KB token → 400 at Netty header ceiling (still denied); 4 MB BLE hex bounded; 600-entry policy / 300 KB keylog oversize rejected.
- Fuzz clients must offer ALPN `http/1.1` (Netty drops ALPN-less handshakes — curl works because it offers `h2,http/1.1`; the harness bridges in `bridges/` negotiate `http/1.1` on every TLS path, ADR-0025). Wake device first (deep sleep stretches waits ~1.5×).

## Examples
See `scripts/verify-mcp.sh` (init/list/vibrate/battery/sensors/audio-range/SSE/PCM-decode/net probes) and `scripts/fuzz-mcp.py` (12/12 malformed/type/concurrency/auth/media abuse).

## Agent usage
Step-by-step playbooks per domain live in `.agents/skills/phone-mcp*/` (router `phone-mcp` + `sense`/`capture`/`wireless`/`ui`/`net`, 41/41 tools; ADR-0030). This page stays the normative tool/transport reference; the skills point here.
