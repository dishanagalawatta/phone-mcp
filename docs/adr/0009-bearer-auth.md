# ADR-0009: Bearer-token auth with QR pairing (supersedes open auth in ADR-0006)

## Status
Accepted (supersedes the open/no-auth decision in ADR-0006; guardrails carry over; hardened by ADR-0021: `GET /mcp` is now auth-first — 401 without token, 405 with token)

## Context
ADR-0006 shipped open LAN auth for v1 velocity with an acknowledged LAN-attacker risk.
With all 14 tools + SSE streaming live, any same-Wi-Fi host can drive mic/camera/GPS —
the risk now outweighs the velocity benefit.

## Decision
- Every data endpoint (`POST /mcp` incl. SSE, `GET /media/*`, `GET /info`) requires
  `Authorization: Bearer <token>`. `/healthz` and `GET /mcp` (static 405) stay open.
- Token: 256-bit `SecureRandom`, URL-safe base64 (43 chars), stored in
  EncryptedSharedPreferences, generated on first pairing view / first auth check.
- Comparison is constant-time (`MessageDigest.isEqual`); failures answer HTTP 401 with
  JSON-RPC `-32001` (new server-defined code) and a log line that never includes the token.
- Pairing: dashboard shows endpoint URL + masked token (show/copy), a QR encoding
  `{"url","token"}`, and one-tap rotation (old token dies immediately).

## Consequences
Positive: casual LAN abuse closed; rotation bounds leak impact; no accounts/PKI/OAuth.
Negative: cleartext LAN HTTP still exposes the token to sniffers — TLS remains the
follow-up track before any public listing. Existing open-mode testers must re-pair once.
