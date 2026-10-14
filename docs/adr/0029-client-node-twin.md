# ADR-0029: Node twin of the unified `phone-mcp` client

- **Status:** Accepted
- **Date:** 2026-10-14
- **Context:** ADR-0028 shipped one installable client in Python (stdlib-only),
  leaving no-Python hosts on the repo-relative `bridges/*.mjs` single file +
  bash generator. The harness matrix (9 hosts) and TLS modes (pin / cafile /
  pin+cafile / insecure) now live in two places, and `npm`-based hosts have no
  `phone-mcp` bin. Harness schemas re-verified Oct 2026 (unchanged).
- **Decision:** Ship `client/node/` — a zero-dependency Node 18+ twin of the
  Python client with the identical CLI (`serve/smoke/pair/configure/list/call/
  media/export-ca`), the same 9 harness renderers/installers, and the same
  capture-then-anchor TOFU flow. Pure logic (TLS modes, headers, SSE, budgets)
  is parity-tested against `bridges/phone-mcp-bridge.mjs` via child-process
  isolation (the bridge exits on import without env, so it is never imported
  into the test process). The twins are interchangeable:
  `configure --bridge phone-mcp` works with whichever `phone-mcp` is on PATH.
- **Alternatives considered:** (a) Python-only + Node bridge file — rejected:
  leaves npm hosts without `pair`/`smoke`/`configure --install`. (b) Import
  shared code from `bridges/` — rejected: top-level side effects (env guard +
  stdin loop) make it unimportable; duplication + parity tests is the
  established pattern (same as Python client vs bridges). (c) npm dependency
  on a framework (commander/etc.) — rejected: zero-dep keeps `npx`-style
  installs offline-friendly and matches the repo's bridge philosophy.
- **Consequences:** + Both runtimes installable (`pip`/`npm`), 59 `node:test`
  tests incl. loopback integration + real-TLS export-ca; `npm pack` verified.
  Live-TLS testing caught a real bug class: Node forbids IP literals as SNI
  `servername` (phone LAN URLs are almost always IPs) — fixed + regression-
  tested; the Python twin never had this failure mode. − Two implementations
  to keep in sync (parity vectors duplicated in both suites + CI runs both).
  Actual `npm publish` / `twine upload` still need maintainer credentials.
