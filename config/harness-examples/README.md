# Harness examples — copy, don't commit tokens
| File | Harness | Notes |
|------|---------|-------|
| `opencode.json` | OpenCode (`~/.config/opencode/opencode.json` or project `opencode.json`) | `phone-mcp` = stdio bridge (recommended, handles TOFU). `phone-mcp-direct` = disabled remote example. `scripts/configure-harness.sh --harness opencode` merges it. |
| `claude-mcp.json` | Claude Code (`.mcp.json`), Claude Desktop (`claude_desktop_config.json`) | Same `mcpServers` shape. CLI: `claude mcp add phone-mcp -- node bridges/phone-mcp-bridge.mjs` or remote `--transport http … --header "Authorization: Bearer …"` (remote rejects self-signed — use bridge on LAN). |
| `cline-mcp.json` | Cline (`cline_mcp_settings.json`) | Same `mcpServers` stdio shape as Claude/Cursor. |
| `codex.toml` | Codex CLI/Desktop (`~/.codex/config.toml`) | Remote `url` + `bearer_token_env_var` shown; comment covers the bridge fallback (`codex mcp add phone-mcp --command …`). |
| `vscode-mcp.json` | VS Code (`.vscode/mcp.json`) | stdio + `inputs` prompts so the token is never stored in the file. |
| `cursor-mcp.json` | Cursor (`~/.cursor/mcp.json`) | stdio bridge. If trying direct Streamable HTTP, Cursor needs the CA trusted (self-signed LAN certs fail otherwise). |
| `windsurf-mcp.json` | Windsurf (`mcp_config.json`) | Same `mcpServers` stdio shape as Claude/Cursor. |
| `gemini-settings.json` | Gemini CLI (`~/.gemini/settings.json`) | stdio bridge. Gotcha: Gemini uses `httpUrl` (not `url`) for direct entries. |
| `generic-mcp.json` | Any `mcpServers` host | Node bridge + Python fallback twin. |

All templates reference `$PHONE_MCP_*` env vars — replace `192.168.1.50:8080`
with your dashboard LAN URL and export the token/fingerprint; never check a
real token into git. Full per-harness guide: `docs/client.md` (client) +
`docs/harness.md` (bridge internals).
Generator: `phone-mcp configure --harness all --out ./generated-harness`
(or `scripts/configure-harness.sh --from-qr '<qr-json>' --harness all` —
same 9 templates, repo-relative bridge paths).
Smoke test: `phone-mcp smoke`
(or `scripts/phone-mcp-smoke.py --url … --token … --sha256 …`).
