# ADR-0028: Unified `phone-mcp` client (stdio bridge + harness installer)

- **Status:** Accepted
- **Date:** 2026-10-14
- **Context:** ADR-0023 shipped the protocol fix (zero-dependency stdio
  bridges) but left the client surface fragmented: two bridge twins
  (`bridges/*.mjs|*.py`), a bash generator (`scripts/configure-harness.sh`),
  seven checked-in example configs, and a separate smoke script. Every new
  harness (Cline, Windsurf, future hosts) multiplies the matrix, and hosts
  without the repo checked out have no installable entry point. Harness
  config schemas were re-verified Oct 2026 (OpenCode `mcp` local/remote,
  Claude `mcpServers` stdio, Codex `config.toml` `mcp_servers` stdio+remote,
  VS Code `servers`+`inputs`, Gemini `mcpServers`+`timeout`).
- **Decision:** Ship one stdlib-only installable client, `client/`
  (`phone-mcp-client` 0.1.0, `phone-mcp` console script, Python 3.10+, zero
  dependencies), as the canonical harness-side client:
  `serve` (stdio bridge) · `smoke`/`doctor` (4-probe) · `pair` (QR → 0600
  paired config) · `configure --harness all|… --install` (9 harnesses:
  opencode, claude, codex, vscode, cursor, gemini, cline, windsurf, generic
  + Inspector guidance) · `list`/`call`/`media`/`export-ca`. Canonical TLS
  logic (pin / cafile / pin+cafile / insecure / default, ALPN `http/1.1`,
  per-call budgets) lives in `client/src/phone_mcp_client/`; the standalone
  `bridges/` twins stay as no-install fallbacks with parity tests enforcing
  sync. Secrets stay in env/prompt inputs — generated files carry refs only;
  the paired token file is 0600.
- **Alternatives considered:** (a) Keep scripts-only — rejected: no
  installable artifact, bash+Python+JSON drift, each harness added by hand.
  (b) Depend on the official `mcp` Python SDK — rejected: adds install weight
  and has no TOFU-pin semantics; stdlib `urllib`+`ssl` already covers
  Streamable HTTP + SSE fan-out and is offline-testable. (c) Node-only CLI —
  rejected: Python stdlib reaches no-Node hosts (the `.py` twin's reason to
  exist); the client keeps `--bridge node|python` for generated configs.
- **Consequences:** + One `pip install ./client` per host, one command per
  task, 65 unit+integration tests (mock loopback phone: smoke/list/call/media)
  + parity tests vs `bridges/` + `configure --install` merges without
  clobbering. − New surface to maintain (harness schema drift now lands in
  `harnesses.py` + `docs/client.md`); `scripts/configure-harness.sh` stays
  for repo-local flows and must not diverge (docs point at the client first).
