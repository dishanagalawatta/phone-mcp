# ADR-0010: TLS-only server with on-device self-signed cert (TOFU)

## Status
Accepted

## Context
Bearer auth (ADR-0009) closed casual LAN abuse, but cleartext HTTP left tokens sniffable —
the last production blocker. A phone-hosted LAN server cannot use public CA certs (no stable
domain), so the choice is self-signed TOFU vs no TLS.

## Decision
- TLS-only listener (same port 8080, fail-closed: no cleartext fallback when the identity
  cannot be built). All endpoints incl. `/healthz` now ride TLS (auth rules unchanged).
- Identity: RSA cert generated once via Ktor's certificate builder (SHA-256 pinned explicitly —
  the builder defaults to SHA-1), persisted as app-private **BKS** (Android's PKCS#12 KeyStore
  cannot round-trip Ktor-written stores; BKS is native via the built-in BC provider).
  Atomic write (temp + validate + rename) with self-heal; 10-year validity, SANs for
  localhost/phone-mcp.local/current LAN IPs.
- Trust: SHA-256 fingerprint shown grouped in the dashboard pairing card, QR payload
  (`{"url","token","sha256"}`), and the startup log; agents verify once (TOFU) then pin.

## Consequences
Positive: passive sniffing dead; Play data-safety flips to encrypted-in-transit.
Negative: self-signed means first-connect verification is a human step (documented in
device-matrix); clients must accept the TOFU cert (curl `-k`, Inspector allow);
IP changes don't invalidate the pin (fingerprint is key-bound, not IP-bound).
