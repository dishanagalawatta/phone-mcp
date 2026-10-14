# ADR-0023: Harness bridge + per-harness MCP config matrix

- **Status:** Accepted
- **Date:** 2026-10-14
- **Context:** Phone MCP serves Streamable HTTP over TLS-only with a
  self-signed TOFU cert + Bearer token (ADR-0009/0010). Harnesses (OpenCode,
  Claude Code/Desktop, Codex, VS Code, Cursor, Gemini CLI, Cline/Windsurf)
  all support remote Streamable HTTP with headers, but their stock TLS stacks
  reject self-signed certs, and each uses a different config shape
  (`opencode.json` local/remote, `.mcp.json`/`mcpServers`, `config.toml`
  `bearer_token_env_var`, `.vscode/mcp.json` inputs, Gemini `httpUrl`).
- **Decision:** Ship zero-dependency stdio↔HTTP bridges
  (`bridges/phone-mcp-bridge.mjs` Node 18+, `bridges/phone-mcp-bridge.py`
  Python stdlib) that own Bearer + SHA-256 TOFU pin / insecure-LAN fallback
  and SSE fan-out, plus a checked-in config matrix
  (`config/harness-examples/` × 7 harnesses), a generator
  (`scripts/configure-harness.sh`, QR → configs), a smoke probe
  (`scripts/phone-mcp-smoke.py`), and a guide (`docs/harness.md`).
  Secrets travel via env/prompt inputs only — never baked into config.
- **Alternatives considered:** (a) Direct-remote configs only — rejected:
  fails on self-signed TLS in every harness without insecure env hacks, and
  fragments per-harness CA workarounds. (b) Third-party bridge (`mcp-remote` /
  `fastmcp-remote` / `mcp-proxy`) — documented as fallback, rejected as the
  default: extra installs (`npx`/`uvx`), no fingerprint-pin semantics, wider
  supply chain. (c) On-phone CA provisioning Chord — rejected: pushes
  per-laptop keychain steps onto every user.
- **Consequences:** + One verified path per harness, offline-testable
  (`bridges/test_bridge.py` 16/16, live mock round-trips, smoke PASS/FAIL);
  + pin-mismatch and 401 errors stay actionable. − Bridge stdio is one
  process per harness session (fine: stateless server, §0001). − Harness
  config schemas drift; `docs/harness.md` carries the per-harness gotchas
  (Gemini `httpUrl`, Codex env-var ref, Cursor CA) and must be re-verified
  when harnesses rev their MCP support.
