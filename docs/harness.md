# Phone MCP — Harness Setup Guide

> Preferred path: the unified client — `pip install ./client`, then
> `phone-mcp pair` → `phone-mcp smoke` → `phone-mcp configure --harness all`
> (full guide: `docs/client.md`). This page documents the underlying bridge
> protocol, TLS modes, and per-harness gotchas the client implements.

Connect any MCP harness (OpenCode, Claude Code/Desktop, Codex CLI/Desktop,
VS Code, Cursor, Gemini CLI, Cline, Windsurf, MCP Inspector) to the phone in
minutes. Templates: `config/harness-examples/`. Generator:
`scripts/configure-harness.sh`. Health check: `scripts/phone-mcp-smoke.py`.

## 1. Get the pairing values (once)

1. Start the server on the phone (dashboard → **Start**). Note the LAN URL,
   e.g. `https://192.168.1.50:8080/mcp`.
2. Copy the pairing card: **token** (show/copy/QR) + **TLS SHA-256 fingerprint**.
3. **TOFU:** verify the fingerprint out-of-band once —
   `echo | openssl s_client -connect 192.168.1.50:8080 2>/dev/null | openssl x509 -fingerprint -sha256 -noout`
   must match the dashboard grouping (`AA:BB:…`). Then pin it.

```bash
export PHONE_MCP_URL="https://192.168.1.50:8080/mcp"
export PHONE_MCP_TOKEN="<paste-from-pairing-card>"
export PHONE_MCP_SHA256="<dashboard-fingerprint>"
```

### USB fallback (no LAN): `adb forward` (live-verified)

Prerequisites: USB debugging ON (Developer options), an `adb`-visible device
(`adb devices -l` shows `device`, not `unauthorized`/`offline`), and — on
Windows — the OEM USB driver installed.

Setup (host-side `forward`: the phone's Ktor listener already holds
device:8080, so `adb reverse` fails with `cannot bind listener`):

```bash
adb -s <serial> forward tcp:8080 tcp:8080
export PHONE_MCP_URL="https://127.0.0.1:8080/mcp"
export PHONE_MCP_TOKEN="<paste-from-pairing-card>"
export PHONE_MCP_SHA256="<dashboard-fingerprint>"
```

Multi-device: pass `-s <serial>` (from `adb devices -l`); with a single
phone attached the serial is auto-picked by `scripts/usb-mcp.sh` and
`phone-mcp usb`. List pins
with `adb forward --list`, remove one with `adb forward --remove tcp:8080`
or all with `adb forward --remove-all`.

Verify over the cable (same checks as LAN):

```bash
curl -sk https://127.0.0.1:8080/healthz  # {"ok":true} when up (open probe)
python3 scripts/phone-mcp-smoke.py --url "$PHONE_MCP_URL" \
  --token "$PHONE_MCP_TOKEN" --sha256 "$PHONE_MCP_SHA256"
# expect 4x PASS
```

TLS is unchanged over USB: same token + same SHA-256 pin (fingerprint is
identical on LAN vs USB; pin/cafile modes skip the hostname check so
`127.0.0.1` verifies). `/healthz` stays open; `/mcp`, `/media/*`, `/info`
still need `Authorization: Bearer`. Headless QS-tile start/stop also works
over `adb` (see below) with no LAN.

| Symptom | Cause → fix |
|---------|-------------|
| `adb reverse tcp:8080 tcp:8080` → `cannot bind listener: Address already in use` | Wrong direction: the phone listens on :8080, so `reverse` collides with Ktor — use `adb -s <serial> forward tcp:8080 tcp:8080` (ADR-0036). |
| `unauthorized` | Re-authorize the RSA prompt on the phone, then `adb devices -l` must show `device`. |
| `offline` / empty list | Re-plug, try another cable/port, `adb kill-server && adb start-server`; Windows: install the OEM USB driver. |
| host `:8080` already in use | Free it or forward another host port: `adb -s <serial> forward tcp:18080 tcp:8080` + `PHONE_MCP_URL=https://127.0.0.1:18080/mcp`. |
| TLS verify fails on `127.0.0.1` | Pin the dashboard fingerprint (`PHONE_MCP_SHA256`) or `export-ca` PEM — stock verify rejects the TOFU cert on loopback exactly as on LAN. |

Headless server control (no taps needed): once the QS tile is added (dashboard →
**Add QS tile** → system confirm, one time), the server starts and stops remotely:

```bash
adb shell cmd statusbar click-tile com.phonemcp.server/.service.McpServerTile
curl -sk https://192.168.1.50:8080/healthz  # {"ok":true} when up
```

API 34+ note: a background tile-click start is refused when the service
declares camera/mic/location FGS types (`startForeground refused ...
grant runtime perms then retry`). Resume the dashboard first so the start
is foreground-initiated — still no taps needed:

```bash
adb shell input keyevent KEYCODE_WAKEUP
adb shell am start -n com.phonemcp.server/.MainActivity
adb shell cmd statusbar click-tile com.phonemcp.server/.service.McpServerTile
```

(Live-verified Oct 2026 after a force-stop left a stale `running` flag and
background starts refused; foreground resume → `{"ok":true}`.)

`click-tile` silently doing nothing means the tile was never added (check
`adb shell settings get secure sysui_qs_tiles` for a
`com.phonemcp.server` entry) — the dashboard hero now says so until the
one-time **Add QS tile** grant lands.

## 2. Recommended path: stdio bridge (all harnesses)

Direct remote HTTPS works, but every harness TLS stack rejects the phone's
self-signed TOFU cert. The zero-dependency bridge speaks harness-side stdio
and phone-side Streamable HTTP with **fingerprint pinning**, so no harness
needs custom CA handling:

- `bridges/phone-mcp-bridge.mjs` — Node 18+, no deps (preferred)
- `bridges/phone-mcp-bridge.py` — Python 3.10+, stdlib only (no-Node hosts)

Both forward `initialize` / `tools/list` / `tools/call` / `ping`, fan out SSE
`data:` envelopes (stream notifications pass through, final `result` last),
and map HTTP 401 → `-32001`, oversize → `-32600`, network/TLS → `-32603`
with actionable stderr hints. Diagnostics go to stderr; stdout carries only
JSON-RPC (one message per line).

Bridge TLS modes (`PHONE_MCP_*` env): `SHA256` pin (TOFU hash-compare, both
legs authenticated) · `CAFILE` (PEM from `scripts/export-ca.sh` — real chain
verification against the pinned leaf; hostname check skipped since DHCP churn
breaks SAN matching) · pin + cafile combined (strongest) ·
`INSECURE=1` (trusted-LAN opt-out only).

Bridge time budgets: `TIMEOUT_MS` ceiling (default 35000) ·
`FAST_MS` for calls without duration args (default 15000 — hung cheap calls
fail fast) · `MARGIN_MS` headroom over `durationMs`/`timeoutMs` (default 10000,
covers the final SSE envelope). Effective budget = `min(ceiling,
duration + margin | fast)`.

## 2b. Direct-remote mode without the bridge (verified TLS)

Prefer the bridge. If a harness must connect directly (Codex remote entries,
native SSE handling), don't disable verification — pin the leaf as a CA file:

```bash
./scripts/export-ca.sh --host 192.168.1.50 --port 8080 \
  --sha256 "$PHONE_MCP_SHA256" --out ./phone-mcp-ca.pem
# fail-closed: writes NOTHING unless the presented cert hashes to the dashboard pin
export PHONE_MCP_CAFILE="$PWD/phone-mcp-ca.pem"
```

Then point the harness TLS stack at it (re-export after phone identity
rotation — reinstall/reset changes the cert and the dashboard pin):

| Stack | Trust the CA file |
|-------|-------------------|
| Node-based (OpenCode remote, `mcp-remote`) | `NODE_EXTRA_CA_CERTS=$PHONE_MCP_CAFILE` |
| Python-based (`uvx fastmcp-remote --verify`, `mcp-proxy`) | `SSL_CERT_FILE=$PHONE_MCP_CAFILE` (`REQUESTS_CA_BUNDLE` for requests-based clients) |
| Codex remote entry | `SSL_CERT_FILE=$PHONE_MCP_CAFILE codex mcp list` (stock verify otherwise rejects TOFU) |
| Cursor direct HTTP | System keychain import, or `SSL_CERT_FILE` + Electron-fetch flags (see §3 Cursor) |

Smoke-test before touching any harness (stdlib only, no phone-side change):

```bash
python3 scripts/phone-mcp-smoke.py --url "$PHONE_MCP_URL" \
  --token "$PHONE_MCP_TOKEN" --sha256 "$PHONE_MCP_SHA256"
# expect 4× PASS; on FAIL check URL/token/pin, dashboard state, LAN/adb.
```

Generate configs from the dashboard QR (`{"url","token","sha256"}` envelope):

```bash
./scripts/configure-harness.sh --from-qr '<paste-qr-json>' --harness all
./scripts/configure-harness.sh --url "$PHONE_MCP_URL" --token "$PHONE_MCP_TOKEN" \
  --sha256 "$PHONE_MCP_SHA256" --harness opencode --install
# Chain CA export in the same run (needs the QR/--sha256 pin):
./scripts/configure-harness.sh --from-qr '<paste-qr-json>' --harness all \
  --export-ca --ca-out ./phone-mcp-ca.pem
```

Both `phone-mcp configure` and `configure-harness.sh` stage the 6 agent
skills next to the rendered configs (`skills/`, `--no-skills` to skip) and,
on `--install`, sync them into `./.agents/skills/` — tools without the
playbooks is a half-connected agent. Full behavior: `docs/client.md`.

## 3. Per-harness wiring

Tokens stay in **env vars / prompt inputs** — never bake them into checked-in
config. Replace `192.168.1.50:8080` with your dashboard URL throughout.

### OpenCode — `~/.config/opencode/opencode.json` (or project `opencode.json`)

```json
{ "mcp": { "phone-mcp": {
  "type": "local",
  "command": ["node", "bridges/phone-mcp-bridge.mjs"],
  "enabled": true,
  "environment": {
    "PHONE_MCP_URL": "{env:PHONE_MCP_URL}",
    "PHONE_MCP_TOKEN": "{env:PHONE_MCP_TOKEN}",
    "PHONE_MCP_SHA256": "{env:PHONE_MCP_SHA256}"
  },
  "timeout": 60000
} } }
```

`"timeout": 60000` keeps long calls (30 s captures) alive harness-side:
the default 5000 ms budget can kill anything slower than tool-fetch.

Verify: `opencode mcp list` shows `phone-mcp` connected — config edits
hot-reload (live-verified on v2.0.28; restart only if a stale failure
persists). Direct-remote alternative
(`type: remote`, `headers: {Authorization: Bearer {env:…}}`) skips the bridge —
verify TLS via §2b (`NODE_EXTRA_CA_CERTS`), never `NODE_TLS_REJECT_UNAUTHORIZED=0`
outside trusted LAN.

### Claude Code / Desktop / Cline / Windsurf — `mcpServers`

```bash
claude mcp add phone-mcp -- node bridges/phone-mcp-bridge.mjs
# remote (rejects self-signed on LAN — bridge preferred):
# claude mcp add --transport http phone-mcp https://192.168.1.50:8080/mcp \
#   --header "Authorization: Bearer $PHONE_MCP_TOKEN"
```

`.mcp.json` / `claude_desktop_config.json` equivalent in
`config/harness-examples/claude-mcp.json`. Desktop-only harnesses can also use
`npx mcp-remote … --header "Authorization: Bearer …"` with
`NODE_EXTRA_CA_CERTS=$PHONE_MCP_CAFILE` (§2b) instead of the bridge.

### Codex — `~/.codex/config.toml`

```toml
[mcp_servers.phone-mcp]
url = "https://192.168.1.50:8080/mcp"
bearer_token_env_var = "PHONE_MCP_TOKEN"
```

or `codex mcp add phone-mcp --url … --bearer-token-env-var PHONE_MCP_TOKEN`.
Stock Codex TLS-verifies, so on LAN prefer the bridge:
`codex mcp add phone-mcp --command node --args bridges/phone-mcp-bridge.mjs
--env PHONE_MCP_URL=… --env PHONE_MCP_TOKEN=$PHONE_MCP_TOKEN
--env PHONE_MCP_SHA256=$PHONE_MCP_SHA256`. Verify: `codex mcp list`.

### VS Code — `.vscode/mcp.json`

Copy `config/harness-examples/vscode-mcp.json`: stdio bridge + `inputs`
prompts, so the token is asked at connect time and never stored. Command
palette → *MCP: List Servers* to confirm.

### Cursor — `~/.cursor/mcp.json`

Copy `config/harness-examples/cursor-mcp.json` (stdio bridge). Direct
Streamable-HTTP entries need the CA trusted (Cursor honors system keychain /
`SSL_CERT_FILE`); the bridge avoids that entirely. If a direct entry stalls,
check Cursor's *HTTP: Fetch Additional Support / Electron Fetch* settings.

### Gemini CLI — `~/.gemini/settings.json`

Merge `config/harness-examples/gemini-settings.json` under `mcpServers`.
Gotcha: Gemini uses **`httpUrl`**, not `url`, for direct entries
(`gemini mcp add --transport http --header "Authorization: Bearer …" …`).
The stdio entry shown needs no such distinction. Verify: `/mcp list`.

### MCP Inspector (manual probing)

Streamable HTTP → URL + `Authorization: Bearer $TOKEN`; accept the
self-signed cert **after** fingerprint verification (same TOFU step as §1).

## 4. Troubleshooting

| Symptom | Cause → fix |
|---------|-------------|
| Client hangs / `socket hang up`, curl works | Missing ALPN: the phone's Netty drops ALPN-less connections — clients must offer `http/1.1` (bridges do this automatically; raw scripts need `set_alpn_protocols`/`ALPNProtocols`). |
| `401` / `-32001` | Wrong/rotated token → re-copy pairing card, re-export, rotate invalidates old copies. |
| TLS `self-signed` / `verify failed` | Expected TOFU → set `PHONE_MCP_SHA256` (dashboard-verified) or `PHONE_MCP_INSECURE=1` on trusted LAN. |
| `fingerprint mismatch` | Phone cert rotated (reinstall/reset) → re-verify dashboard value, update pin. |
| `-32600` bad version/origin | Harness dropped `MCP-Protocol-Version: 2025-06-18` or sends `Origin` for another host → bridge sets it; direct configs must keep the header + `Accept: application/json, text/event-stream`. |
| SSE tool needs `Accept` | `sensors_stream`/`audio_stream`/`net_stream` require SSE accept → bridge sends it; direct clients must too (else `-32602`). |
| Second stream → `-32603` | One live stream per kind (12/12 fuzz-verified) → stop the first before starting another. |
| BLE scan empty but peers nearby | API 31+ scan needs a location grant (not just BT) or results are silently empty; Classic (non-LE) peers never appear; restart the server if scans went empty across a permission grant (stale BLE client context). |
| `ble_connect` 133 / subscribe "permission" text | 133 = non-LE/unreachable peer. Subscribe failures naming the permission with the grant held mean the peripheral rejected CCCD setup (unbonded HID needs system-level pairing) — read the full message, don't re-grant. Idle peripherals drop GATT links: reconnect before subscribe after >30 s idle, and always `ble_disconnect` after. |
| UI automation hits a confirm dialog | Secure-screen flips ask first by design ("Show screen in mirrors?") — confirm via the `confirm_dialog_ok` node, or Cancel to leave state unchanged. |
| Empty `tools/list` | Service not started / providers unbound → dashboard log + `GET /healthz`. |
| `remote_submix(mirrored)` audio | Screen-mirror forwards phone audio → re-run mirror with `--no-audio`. |

Security notes: TLS-only + Bearer everywhere except `/healthz`; kill-switches
(`audio_*`, `camera_*`, `net_*`, `ui_*`, `ext_*`, `ble_write`) default OFF — tap **⚡ Grant Full Agent Access**
(or individual switches) on the dashboard before calling gated tools; run only on networks you trust (ADR-0006/0009/0010/0037).
