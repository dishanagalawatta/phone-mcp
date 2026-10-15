# Environments

Local-only configuration for Phone MCP. No cloud, no telemetry.

## Prerequisites

- Android Studio Giraffe+, JDK 17, Android SDK 35, device/emulator API 29+
- Same WiFi for phone + agent host; `adb` for USB fallback (`adb -s <serial> forward tcp:8080 tcp:8080` → `PHONE_MCP_URL=https://127.0.0.1:8080/mcp`; never `adb reverse` — it collides with the phone's :8080 listener, ADR-0036)

## Setup

```bash
cp .env.example .env
./scripts/setup.sh   # env check (java, SDK, adb)
```

Open folder in Android Studio, let it provision Gradle wrapper + SDK 35, then Run `app`.

## Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `PHONE_MCP_PORT` / `ServerConfig.port` | Ktor listen port | `8080` |
| `PHONE_MCP_HOST` / `ServerConfig.host` | bind (keep `0.0.0.0` LAN) | `0.0.0.0` |
| `PHONE_MCP_TOKEN` / `TOKEN` | Bearer token from dashboard pairing card | — (required for `/mcp`, `/media/*`, `/info`) |
| `BLE_PEER` | MAC for `verify-mcp.sh` ble_subscribe probe | — (optional) |
| `PHONE_MCP_URL` | Phone MCP endpoint for bridges + smoke | `https://<lan-ip>:8080/mcp` |
| `PHONE_MCP_SHA256` | Pinned TLS fingerprint (dashboard, colons ok) | — (or `PHONE_MCP_CAFILE`) |
| `PHONE_MCP_CAFILE` | PEM from `scripts/export-ca.sh` (verified direct mode) | — (or `PHONE_MCP_SHA256`) |
| `PHONE_MCP_INSECURE=1` | Skip TLS verification (trusted LAN only) | unset (verify) |
| `PHONE_MCP_TIMEOUT_MS` / `FAST_MS` / `MARGIN_MS` | Bridge per-call budgets (ceiling / no-duration calls / duration headroom) | `35000` / `15000` / `10000` |
| `PHONE_MCP_KEYSTORE_*` | Release signing (file/password/alias/key password) | — (unset = debug signing, not for Play upload) |
| `ANDROID_HOME` | SDK override (else `local.properties` `sdk.dir`) | `$HOME/Android/Sdk` |
| Media TTL / cap (`FileMediaStore`) | sidecar eviction | `15 min / 100 MB` |
| Kill-switches (`Gatekeeper`) | `audio_*`, `camera_*`, `net_*`, `ui_*` default OFF | OFF |

USB note: over `adb forward` set `PHONE_MCP_URL=https://127.0.0.1:8080/mcp`
(same `PHONE_MCP_TOKEN` + `PHONE_MCP_SHA256` as LAN — fingerprint identical;
pin/cafile modes skip the hostname check so loopback verifies). Full USB
walkthrough: `docs/harness.md` §1 + ADR-0036.

## Verify

```bash
export TOKEN=<paste-from-dashboard>
./scripts/verify-mcp.sh 192.168.1.50:8080
./scripts/collect-proofs.sh 192.168.1.50:8080  # regenerates device proofs (gitignored build/ output)
python3 scripts/fuzz-mcp.py --help  # 12/12 host fuzz, needs ALPN http/1.1
```

## Rules

- `local.properties` (`sdk.dir=...`) and `.env` are machine-specific — never commit (see `.gitignore`).
- Release keystore credentials come from env only — never commit a keystore.
- TLS is self-signed TOFU: verify SHA-256 fingerprint against dashboard once, then pin.
