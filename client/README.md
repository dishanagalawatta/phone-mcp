# phone-mcp-client

Unified MCP client for **Phone MCP** — one install, every harness.

Stdlib-only Python (3.10+, zero dependencies). Speaks harness-side **stdio**
and phone-side **Streamable HTTP** with Bearer auth + SHA-256 TOFU pinning,
so no harness needs custom CA handling for the phone's self-signed cert.

## Install

```bash
pip install ./client
# or: pip install -e ./client  (editable, repo worktrees)
phone-mcp --help
```

No-install fallback (single file, same protocol): `bridges/phone-mcp-bridge.mjs`
(Node 18+) or `bridges/phone-mcp-bridge.py` (stdlib Python).

No-Python hosts: the Node twin (`client/node/`, zero-dep, same CLI) is
interchangeable — `npm install -g ./client/node`.

## Pair once

```bash
phone-mcp pair --qr '<dashboard-qr-json>'        # saves ~/.config/phone-mcp/config.json (0600)
# or explicit:
phone-mcp pair --url https://192.168.1.50:8080/mcp --token <t> --sha256 <hex>
phone-mcp smoke                                  # expect 4× PASS
```

USB fallback: `adb -s <serial> forward tcp:8080 tcp:8080` → `--url https://127.0.0.1:8080/mcp` (same token + pin; or `phone-mcp usb --serial <serial>` / `scripts/usb-mcp.sh`).

## Configure harnesses

```bash
export PHONE_MCP_URL="https://192.168.1.50:8080/mcp"
export PHONE_MCP_TOKEN="<pairing-card>"
export PHONE_MCP_SHA256="<dashboard-fingerprint>"

phone-mcp configure --harness all --out ./generated-harness
phone-mcp configure --harness opencode --install          # merge into ~/.config/opencode/opencode.json
phone-mcp configure --harness "claude vscode" --install --cwd .
phone-mcp configure --harness codex --bridge python --install
```

| Harness | Install target | Notes |
|---|---|---|
| OpenCode | `~/.config/opencode/opencode.json` (`mcp`) | `type: local` stdio; restart after edit; `/mcp` to verify |
| Claude Code/Desktop, Cline, Windsurf | `./.mcp.json` (project) | `mcpServers` stdio; `claude mcp add phone-mcp -- phone-mcp serve` |
| Codex | `~/.codex/config.toml` | stdio bridge (LAN) + commented remote `url` + `bearer_token_env_var`; `codex mcp list` |
| VS Code | `./.vscode/mcp.json` | stdio + `inputs` prompts (token never stored) |
| Cursor | `~/.cursor/mcp.json` | `mcpServers` stdio |
| Gemini CLI | `~/.gemini/settings.json` | `mcpServers` stdio + `timeout`; direct entries use `httpUrl` |
| Generic | `./phone-mcp.generic.json` | Node + Python twins |
| Inspector | manual | Streamable HTTP + Bearer; accept cert only after TOFU check |

Tokens stay in **env vars / prompt inputs** — generated files never contain
a literal token. The literal LAN URL is baked in (not a secret).

## Use

```bash
phone-mcp list                          # 41 tools
phone-mcp call vibrate --args '{"durationMs":200}'
phone-mcp call camera_capture --args '{"lens":"back"}'
phone-mcp media <media-id> --out ./photo.jpg
phone-mcp export-ca --host 192.168.1.50 --port 8080 --sha256 $PHONE_MCP_SHA256 --out ./phone-mcp-ca.pem
```

## Bridge mode (what harnesses run)

```bash
phone-mcp serve   # stdin: harness JSON-RPC → stdout: JSON-RPC; diagnostics → stderr
```

Env: `PHONE_MCP_URL` (req), `PHONE_MCP_TOKEN` (req), `PHONE_MCP_SHA256` (pin),
`PHONE_MCP_CAFILE` (export-ca PEM), `PHONE_MCP_INSECURE=1` (trusted LAN only),
`PHONE_MCP_TIMEOUT_MS=35000` / `FAST_MS=15000` / `MARGIN_MS=10000`.

TLS modes: pin (TOFU hash-compare) · cafile (chain vs pinned leaf, no hostname
check — DHCP churn) · pin+cafile (strongest) · insecure (opt-out) · default
(stock verify, fails closed). Every path offers ALPN `http/1.1` (Netty drops
ALPN-less handshakes).

## Tests

```bash
python3 -m unittest discover -s client/tests -v
pip install -e ./client && phone-mcp configure --harness all --out /tmp/gen
```

See `docs/client.md` (guide) + ADR-0028 (decision).
