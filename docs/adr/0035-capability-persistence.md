# ADR-0035: Restart persistence for capability records

- **Status:** Accepted
- **Date:** 2026-10-15
- **Context:** The capability registry was purely in-memory: every process
  death (reboot, battery optimization, update) wiped registrations AND
  user consent, forcing providers to re-announce and owners to re-approve
  before agents saw the tools again.
- **Decision:** Persist record snapshots (id, package, display, version,
  tools JSON, signer pin, approval, registered-at) as one string in
  EncryptedSharedPreferences (same `CryptoPrefs` + `SecretsLock` mechanism
  as the bearer token). `PhoneMcpApp.onCreate` restores once and then saves
  on every registry version bump. Codec lives in pure-Kotlin
  `mcp/CapabilitySnapshots.kt` (JVM-tested round-trip); only string I/O
  lives in `data/ExternalCapabilityStore`. Restored records get a fresh
  heartbeat (normal TTL grace for re-announce) and re-run validation +
  signer/approval rules on import. Corrupt payloads fail open to empty
  (never crash boot).
- **Alternatives considered:** (a) Plain SharedPreferences — rejected:
  signer pins + approval state deserve the same encrypted store as the
  token. (b) DataStore/Proto — rejected: one small string needs no new
  dependency or schema machinery. (c) Persist heartbeats verbatim —
  rejected: a 3-day-old record would look live; fresh grace + TTL is
  honest.
- **Consequences:** + Registrations and consent survive restarts with no
  provider changes. + Save path is version-bump-driven (no polling).
  − Very large registries rewrite one string per change (bounded: 32 caps
  max — negligible).
