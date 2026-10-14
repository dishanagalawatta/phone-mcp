# ADR-0025: ALPN mandate, per-call timeouts, QR→CA chaining

- **Status:** Accepted
- **Date:** 2026-10-14
- **Context:** Live verification against a physical test device (API 34, 192.168.1.50:8080,
  server running, Ktor/Netty TLS) showed both bridges failing with `socket
  hang up` while curl succeeded. Root cause: the phone's Netty engine drops
  ALPN-less connections on first bytes — curl works only because it offers
  `h2,http/1.1`, while Node `https` and Python `ssl` stdlib offer no ALPN by
  default. All prior mock verification used Python `http.server`, which
  ignores ALPN, so the gap was invisible until real hardware. The same
  session exposed a second bug: Node `checkServerIdentity: <false> ? fn :
  undefined` throws on modern Node (explicit `undefined` rejected) — the
  insecure-only HTTPS mode was untested on both twins. Separately, the
  blanket 35s bridge timeout let hung cheap calls linger, and `export-ca`
  required a manual second command after `configure-harness.sh`.
- **Decision:**
  1. Both bridges always offer ALPN `http/1.1` on TLS (`ALPNProtocols` /
     `set_alpn_protocols`, incl. the Python pre-flight handshake and the
     smoke probe). HTTP/2 deliberately not offered — the server negotiates
     HTTP/1.1 and stdlib h2 framing would add failure modes for no benefit.
  2. Per-call budgets: `TIMEOUT_MS` ceiling (35000) · `FAST_MS` (15000) for
     calls without `durationMs`/`timeoutMs` · `MARGIN_MS` (10000) headroom
     over durations (covers the final SSE envelope). Pure
     `effectiveTimeout`/`messageDurationMs` in both twins, unit-tested
     (26/26) and parity-checked; live-proven with a sleeping mock.
  3. `configure-harness.sh --export-ca [--ca-out]` chains the CA export off
     the QR-derived host/fingerprint; refuses to pin blind without `--sha256`.
  4. Node `checkServerIdentity` is omitted (not `undefined`) unless bypassed.
  5. CI gains an ALPN regression guard (`openssl s_server -alpn`, assert
     negotiated protocol in pin/cafile/insecure modes + default-mode still
     verifies) and insecure-mode round-trips, since plain mocks can't catch
     ALPN or option-shape regressions.
- **Alternatives considered:** (a) Offering `h2,http/1.1` like curl —
  rejected: invites h2 code paths in stdlib clients against a server that
  serves http/1.1. (b) Server-side ALPN leniency — rejected: out of scope
  for the harness track, needs on-device verification, and the current
  behavior matches the documented fuzz constraint ("offer ALPN http/1.1").
- **Consequences:** + Full 7-mode TLS matrix verified on mock *and* real
  hardware (valid modes reach the real `-32001` auth gate; wrong pin fails
  closed). + Cheap calls fail in ~15s instead of ~35s. − Pin-without-cafile
  costs one extra handshake (unchanged). Only remaining gap: valid-token →
  200, which needs the dashboard token (single user command, documented).
