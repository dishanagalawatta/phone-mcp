# ADR-0018: Fuzz + harden pass (host bench over LAN TLS)

## Status
Accepted

## Context
`scripts/fuzz-mcp.py` (stdlib-only host bench, 9 phases) ran against the physical test device
over LAN HTTPS: malformed envelopes (incl. 500-deep nesting), wrong arg types
across 20+ tools, 5 MB body, 4 MB BLE hex, 200 KB texts, concurrent SSE
streams, 12-way concurrent calls, auth abuse, media traversal/range abuse.
Every probe asserts a well-formed reply and post-probe health.

## Decision
- Real finding 1 (fixed, ADR-0017): 1024-bit RSA identity rejected by modern
  TLS stacks → 2048-bit + weak-key rotation, migration proven on reinstall.
- Real finding 2 (fixed here): `ble_write` hex unbounded → cap at 1024 chars
  (ATT ceiling 512 bytes) in `RadioProvider.parseHexArg` (pure, unit-tested).
- Real finding 3 (fixed here): POST /mcp had no body bound → 413 past 1 MiB
  declared Content-Length (`McpHttp.isBodyTooLarge`, unit-tested). Chunked
  bodies without a declared length remain unbounded (accepted residual).
- Real finding 4 (fixed here): `FileMediaStore.evictExpired` sized from the
  pre-TTL file list and over-evicted → re-list after the TTL pass (unit-tested:
  oldest goes, newest fit the cap).
- Non-findings (bench/environment, documented not fixed): no-ALPN TLS clients
  are dropped by the Netty pipeline (all real MCP clients send ALPN);
  10 KB bearer tokens die at Netty's 8 KB header ceiling with 400, still denied;
  deep sleep stretches bounded waits ~1.4–1.6× and stalls SSE (bench rule:
  wake the device first — `docs/device-matrix.md` §8). The `-32603` single-
  stream guard held under a true concurrent assault (12/12 batches primary,
  0.2 s rejection secondary).

## Consequences
Positive: fuzz 9/9 green on hardware; every bound has a JVM unit test.
Negative: 413/hex-cap rejections are new shapes agents may see (documented in
`docs/testing.md`); weak-cert rotation invalidates one TOFU pin (ADR-0017).
