# CI

`ci.yml` runs on every push/PR with two jobs:

- `unit`: `testDebugUnitTest` (all JVM tests under `app/src/test/`)
  plus `lintDebug` (Android lint incl. NewApi — the API 29 floor is enforced here, not just by review).
- `harness` (no phone needed): bridge unit tests, node/python/bash syntax,
  harness config schemas (JSON/TOML, no baked-in tokens), Node/Python TLS +
  timeout parity, `export-ca` self-test, bridge TLS round-trips and the ALPN
  regression guard over local fixtures (ADR-0024/0025), plus the unified
  clients (ADR-0028/0029): both suites, shared-vectors parity, pinned-TLS
  round-trips, token-free `configure` dry-runs, wheel + `npm pack` builds.

## Reading failures

- Unit test red → open the `test-reports` artifact (`app/build/reports/tests/`), or reproduce locally:
  Android Studio → `testDebugUnitTest`, or `./gradlew testDebugUnitTest` once a wrapper exists.
- Lint red → `lint-results-debug.html` in the same artifact; NewApi findings mean an
  unguarded framework call slipped past the audit (see the version-gate table in chat history /
  `docs/device-matrix.md` constraints).

## Version pins (bump deliberately)

| Tool | Pin | Where |
|------|-----|-------|
| Gradle | 8.9 | `gradle-version` in `ci.yml` (AGP 8.6 needs 8.7+) |
| JDK | Temurin 17 | `setup-java` in `ci.yml`, matches local JDK 17 |
| compileSdk | 35 | `app/build.gradle.kts` + `platforms;android-35` install step |

## Not covered (needs hardware)

R8/shrink verification, CameraX smoke, BLE/GPS/radio behavior, SSE streams, TLS handshake —
all in `docs/device-matrix.md`. A `connectedAndroidTest` job stays out until an emulator
matrix is justified; it would 10x CI minutes for little signal at this stage.
The agent-skills live battery (`scripts/eval-skills.py` live half) likewise needs a
paired phone; its static halves (`verify-skills.py`, eval static) are phone-free
and run anywhere (see `docs/testing.md`).
