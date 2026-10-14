# Phone MCP — Client Guide (`phone-mcp`)

One installable client for every harness, in two interchangeable twins.
Stdlib-only in both runtimes — Python 3.10+ (`client/`, zero dependencies)
and Node 18+ (`client/node/`, zero dependencies). Replaces the multi-script
flow with a single entry point; `bridges/` + `scripts/configure-harness.sh`
remain as no-install fallbacks.

## Install

```bash
pip install ./client            # → `phone-mcp` on PATH (Python twin)
pip install phone-mcp-client    # once published to PyPI
npm install -g ./client/node    # → `phone-mcp` on PATH (Node twin)
npm install -g phone-mcp-client # once published to npm
# or run either from the repo without installing:
python3 -m phone_mcp_client.cli --help        # PYTHONPATH=client/src
node client/node/bin/phone-mcp.mjs --help
```

The twins are interchangeable: `configure --bridge phone-mcp` works with
whichever `phone-mcp` is on PATH. Prefer the Python twin on no-Node hosts,
the Node twin on no-Python hosts.

## Pair once, smoke, configure

```bash
phone-mcp pair --qr '<dashboard-qr-json>'   # → ~/.config/phone-mcp/config.json (0600)
phone-mcp smoke                             # 4× PASS: initialize/list/battery/device_info
export PHONE_MCP_URL="https://192.168.1.50:8080/mcp"
export PHONE_MCP_TOKEN="<pairing-card>"
export PHONE_MCP_SHA256="<dashboard-fingerprint>"

phone-mcp configure --harness all --out ./generated-harness
phone-mcp configure --harness opencode --install
phone-mcp configure --harness "claude vscode" --install --cwd .
phone-mcp configure --harness codex --bridge python --install
```

`--bridge phone-mcp` (default, installed CLI) · `node` (repo twin, no install)
· `python` (stdlib twin, no Node). Tokens stay in env/prompt inputs — files
carry `{env:…}` / `${…}` / `$…` / `${input:…}` refs only.

## Harness matrix

| Harness | `configure --harness` | Install target | Verify |
|---|---|---|---|
| OpenCode | `opencode` | project `./opencode.json` or `~/.config/opencode/opencode.json` (`mcp`) | edit, then `opencode mcp list` (hot-reloads; restart only if a stale failure persists) |
| Claude Code/Desktop | `claude` | `./.mcp.json` (project; see `claude mcp add phone-mcp -- phone-mcp serve`) | `claude mcp list` |
| Codex CLI/Desktop | `codex` | `~/.codex/config.toml` | `codex mcp list` |
| VS Code | `vscode` | `./.vscode/mcp.json` | *MCP: List Servers* |
| Cursor | `cursor` | `~/.cursor/mcp.json` | MCP settings panel |
| Gemini CLI | `gemini` | `~/.gemini/settings.json` | `/mcp list` |
| Cline | `cline` | `cline_mcp_settings.json` | Cline MCP panel |
| Windsurf | `windsurf` | `mcp_config.json` | Windsurf MCP panel |
| Any other | `generic` | `./phone-mcp.generic.json` | host docs |
| Inspector | — (guidance printed) | manual Streamable HTTP + Bearer | TOFU-check the cert first |

Gotchas (re-verified Oct 2026, incl. live on OpenCode v2.0.28): Codex stock TLS-verify rejects the
self-signed TOFU cert → stdio bridge on LAN (remote `url` +
`bearer_token_env_var` block is included commented, needs `export-ca` first).
Gemini direct entries use **`httpUrl`**, not `url` (stdio needs neither).
VS Code prompts for token/fingerprint at connect time. OpenCode hot-reloads
config edits and respawns the bridge (verified: a content change flips a
stale `failed` entry to `connected` without restart) — restart only as a
fallback. Known-good project entry (this repo's `opencode.json`):

```json
{ "mcp": { "phone-mcp": {
  "type": "local", "command": ["phone-mcp", "serve"], "enabled": true,
  "environment": {
    "PHONE_MCP_URL": "{env:PHONE_MCP_URL}",
    "PHONE_MCP_TOKEN": "{env:PHONE_MCP_TOKEN}",
    "PHONE_MCP_SHA256": "{env:PHONE_MCP_SHA256}" },
  "timeout": 60000 } } }
```

`"timeout": 60000` is deliberate, not decoration: the harness default is
5000 ms while the bridge budget ceiling is 35000 ms (30 s captures) — the
harness value must stay above the bridge ceiling or long calls die
harness-side. Unset `{env:…}` refs substitute as empty, and the bridge then
falls back to `~/.config/phone-mcp/config.json` (0600) — which is why pairing
once is enough even when the harness env lacks the vars.

## Direct use (no harness)

```bash
phone-mcp list
phone-mcp call vibrate --args '{"durationMs":200}'
phone-mcp call speaker_say --args '{"text":"hello phone"}'
phone-mcp media <media-id> --out ./photo.jpg
phone-mcp export-ca --host 192.168.1.50 --port 8080 \
  --sha256 "$PHONE_MCP_SHA256" --out ./phone-mcp-ca.pem
```

## Bridge mode (what harnesses execute)

```bash
phone-mcp serve
```

stdin: one JSON-RPC line → POST phone `/mcp` → stdout: response (or each SSE
`data:` envelope, notifications first). Diagnostics → stderr only. HTTP
401 → `-32001`, oversize → `-32600`, TLS/network → `-32603` with the
dashboard-pin hint. Notification `202`/empty → stdio ack (no output).

## TLS modes (`PHONE_MCP_*` env)

`SHA256` pin (TOFU hash-compare, both legs anchored) · `CAFILE` (PEM from
`export-ca`, chain vs pinned leaf, no hostname check — DHCP churn) ·
pin+cafile (strongest) · `INSECURE=1` (trusted LAN only) · default (stock
verify, fails closed). Every path offers ALPN `http/1.1` (phone Netty drops
ALPN-less handshakes). Budgets: `TIMEOUT_MS` 35000 ceiling · `FAST_MS` 15000
· `MARGIN_MS` 10000 over `durationMs`/`timeoutMs`.

## Troubleshooting

Same transport table as `docs/harness.md` §4 (ALPN hang, 401, self-signed,
fingerprint mismatch, `-32600` version/origin, SSE `Accept`, second-stream
`-32603`), plus client-specific rows:

| Symptom | Fix |
|---|---|
| `missing PHONE_MCP_URL/TOKEN` | `phone-mcp pair` first, or export the env vars |
| OpenCode shows `phone-mcp failed: process exited with code 1` | Almost always credentials: the harness substitutes unset `{env:…}` refs as empty, so the bridge falls back to `~/.config/phone-mcp/config.json` (0600) — re-run `phone-mcp pair`. Note `mcp list` can show a STALE failure from before pairing: make any content change to the entry (e.g. `"timeout"`) to force a respawn, then re-list. Live-verified on OpenCode v2.0.28 with the flat `mcp.phone-mcp` shape. |
| Gemini ignores the server silently | Confirm the entry uses `command`/`args`/`env` (stdio) — Gemini only honors `httpUrl` (not `url`) for remote entries, and silently ignores unrecognized keys |
| Gemini doesn't see shell env vars | Export them in the launching shell: `mcpServers` does **not** read `.env` files (upstream issue google-gemini/gemini-cli#2836); `export PHONE_MCP_TOKEN=…` before starting Gemini |
| Gemini tools fail but server connects | Check for an `includeTools` allowlist in `~/.gemini/settings.json` — remove it or add the phone tool names |
| OpenCode lists no tools after edit | Restart OpenCode (servers connect at startup); verify with `opencode mcp list`; per-server `timeout` (default 5000 ms) covers tool *fetch*, not long tool calls |
| paired file stale after rotation | re-`pair` (rotation invalidates old tokens; cert rotation changes the pin) — then force a respawn: long-lived bridge processes resolve credentials once at startup and keep serving the dead token until respawned (edit the harness entry, or restart the harness/session) |
| `not valid JSON` on install | harness file hand-edited badly — client refuses to clobber; fix or back it up |
| Codex `url` ignored on stdio | expected — stdio entries use `command`; the `url` line is informational |

Security: TLS-only + Bearer except `/healthz`; kill-switches
(`audio_*`, `camera_*`, `net_*`, `ui_*`) default OFF — enable on the
dashboard before calling gated tools; trusted LAN only (ADR-0006/0009/0010).
Never pass a live token as a CLI flag under a harness that logs commands:
OpenCode's server log records full command lines, so `pair --token …` there
persists the bearer in plaintext — prefer `pair --qr` on a trusted host and
rotate afterwards if a secret traversed any log. The paired store
(`~/.config/phone-mcp/config.json`, 0600) is the only on-disk home a token
should ever have.

## Protocol compatibility

The phone pins MCP `2025-06-18` (min `2025-03-26`). The client forwards
unknown methods transparently and **never requires `ping`** (removed in spec
`2026-07-28`, which also replaces elicitation with Multi Round-Trip Requests
and the HTTP GET stream with `subscriptions/listen` + `server/discover`).
`smoke` uses only `initialize` → `tools/list` → `tools/call`, so it stays
valid across spec revisions — a CI guard (`SpecCompatTest`) proves it against
a ping-less server. Revisit version negotiation when the phone adopts a newer
protocol version.

## Publishing (maintainer)

The wheel builds dependency-free (`python3 -m pip wheel ./client --no-deps`;
CI checks this). To publish to PyPI you need a PyPI API token (never commit
it) plus a real repo URL and `LICENSE` (both still `TBD`/placeholder):

```bash
# 1. set real URLs in client/pyproject.toml + add LICENSE
# 2. build + upload:
python3 -m pip install build twine
python3 -m build ./client
python3 -m twine upload client/dist/*
# 3. consumers: pip install phone-mcp-client  →  phone-mcp configure --harness all --install
```

Note: the MCP Registry publishes *servers*, not clients — there is no
registry entry for `phone-mcp-client` by design; PyPI (Python twin) and npm
(Node twin, `npm publish ./client/node --access public`) are its
distribution channels.
