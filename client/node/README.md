# phone-mcp-client (Node)

Unified MCP client for **Phone MCP** — the Node twin of `client/` (Python).
Zero dependencies (Node 18+). Same CLI, same protocol, same 9 harnesses.

## Install

```bash
npm install -g ./client/node   # → `phone-mcp` on PATH
# or run from the repo without installing:
node client/node/bin/phone-mcp.mjs --help
```

No-install fallback (single file, same protocol): `bridges/phone-mcp-bridge.mjs`.

## Use (identical to the Python client)

```bash
phone-mcp pair --qr '<dashboard-qr-json>'
phone-mcp smoke
phone-mcp configure --harness all --out ./generated-harness
phone-mcp configure --harness opencode --install
phone-mcp list
phone-mcp call vibrate --args '{"durationMs":200}'
phone-mcp media <media-id> --out ./photo.jpg
phone-mcp export-ca --host 192.168.1.50 --port 8080 --sha256 $PHONE_MCP_SHA256 --out ./phone-mcp-ca.pem
phone-mcp serve   # what harnesses execute (stdio bridge)
```

Env: `PHONE_MCP_URL` · `PHONE_MCP_TOKEN` · `PHONE_MCP_SHA256` ·
`PHONE_MCP_CAFILE` · `PHONE_MCP_INSECURE=1` · `PHONE_MCP_TIMEOUT_MS=35000` /
`FAST_MS=15000` / `MARGIN_MS=10000`. Tokens stay in env/prompt inputs —
generated files carry refs only; `pair` saves `~/.config/phone-mcp/config.json`
(0600). The two clients are interchangeable: `configure --bridge phone-mcp`
works with whichever `phone-mcp` is on PATH.

## Tests

```bash
npm test --prefix client/node   # node:test, no phone needed (71 tests)
```

Parity is enforced both ways: `resolveTls`/`buildHeaders`/SSE/budget vectors
are asserted equal against `bridges/phone-mcp-bridge.mjs` (child-process
isolation — the bridge exits on import without env), and the Python suite
asserts the same vectors against `bridges/phone-mcp-bridge.py`.

## Publishing (maintainer)

`npm pack --dry-run` is CI-checked. To publish you need an npm account with
access + a real repo URL and `LICENSE` (both still placeholders):

```bash
npm publish ./client/node --access public
```

Note: the MCP Registry publishes *servers*, not clients — npm (+ PyPI for the
Python twin) is this client's distribution channel.
