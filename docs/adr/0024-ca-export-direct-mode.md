# ADR-0024: CA export + verified direct-remote harness mode

- **Status:** Accepted
- **Date:** 2026-10-14
- **Context:** ADR-0023 made the stdio bridge the default harness path because
  stock harness TLS stacks reject the phone's self-signed TOFU cert
  (ADR-0010). Direct-remote configs (Codex `url` + `bearer_token_env_var`,
  Claude `--transport http`, Cursor/ VS Code Streamable HTTP) remain desirable
  — one fewer process, native harness SSE handling — but had only insecure
  workarounds (`NODE_TLS_REJECT_UNAUTHORIZED=0`, `PHONE_MCP_INSECURE=1`).
  Separately, the Python bridge's pin check read the peer cert through
  urllib's response chain (`res.fp.raw…`), which yields no cert on CPython —
  so Python pin mode failed closed on every pinned connection while Node pin
  mode worked. The smoke probe had the same extraction bug and additionally
  ran pinned probes over an unverified context.
- **Decision:**
  1. `scripts/export-ca.sh` pulls the leaf cert via `openssl s_client` and
     writes `phone-mcp-ca.pem` only after its SHA-256 matches the dashboard
     fingerprint (TOFU, fail closed on mismatch/rotation). This is a
     **host-side pull**, not on-phone provisioning — ADR-0023's rejection of
     per-laptop keychain provisioning stands; the bridge stays the default.
  2. Both bridges accept `PHONE_MCP_CAFILE`: chain-verify against the pinned
     leaf (hostname check skipped — DHCP churn breaks SAN matching; the
     anchor IS the phone identity), composable with the SHA-256 pin.
  3. Python pin path fixed via capture-then-verify: pre-flight handshake grabs
     the leaf DER, hash-compare against the pin, then the data connection is
     chain-verified against that exact leaf (in-memory `cadata` anchor) — both
     legs authenticated, stdlib-only. The smoke probe reuses the same helpers
     instead of its broken extractor.
  4. Direct-mode trust documented per stack (`NODE_EXTRA_CA_CERTS`,
     `SSL_CERT_FILE`/`REQUESTS_CA_BUNDLE`) in `docs/harness.md`.
  5. CI `harness` job pins the matrix: bridge unit tests, syntax checks,
     config schema validation (JSON/TOML, no baked-in tokens), 6-mode
     Node/Python TLS parity, `export-ca` self-test and TLS round-trips over
     local fixtures — no phone required.
- **Alternatives considered:** (a) Keep pin-via-urllib-response — rejected:
  empirically broken on CPython (returns no cert). (b) Custom `HTTPSConnection`
  subclass capturing same-connection DER — rejected: more machinery than
  capture-then-verify for equal authentication (both legs verified against the
  pinned leaf). (c) `GET /mcp` server sessions for native streaming — deferred:
  needs on-device verification (still open).
- **Consequences:** + Pin mode now actually authenticates on both twins
  (22/22 unit, live TLS round-trips); + direct-remote mode has a verified,
  non-insecure path; + CI guards harness drift. − Pin-without-cafile costs one
  extra TLS handshake per call (negligible vs 5–30s tool timeouts). − `export-ca`
  needs re-running after phone identity rotation (documented, fail-closed).
