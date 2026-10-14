# Agent Instructions

## Package Manager
Use Gradle wrapper: `./scripts/setup.sh`, `./gradlew testDebugUnitTest`, `./gradlew lintDebug`

## Commit Attribution
AI commits MUST include:
```
Co-Authored-By: Muse Spark 1.3 Free <noreply@example.com>
```

## File-Scoped Commands
| Task | Command |
|------|---------|
| JVM unit test (all `mcp/`) | `./gradlew testDebugUnitTest` |
| Lint (NewApi blocking) | `./gradlew lintDebug` |
| MCP conformance | `TOKEN=<token> ./scripts/verify-mcp.sh <ip>:8080` |
| Host fuzz | `python3 scripts/fuzz-mcp.py` |
| Harness smoke (no harness needed) | `phone-mcp smoke --timeout 20` (or `python3 scripts/phone-mcp-smoke.py --url https://<ip>:8080/mcp --token <t> --sha256 <hex>`) |
| Harness configs | `phone-mcp configure --harness all --out ./generated-harness` (or `./scripts/configure-harness.sh --from-qr '<qr-json>' --harness all`) |
| Export phone CA (verified direct mode) | `phone-mcp export-ca --host <ip> --port 8080 --sha256 <hex> --out ./phone-mcp-ca.pem` (or `./scripts/export-ca.sh …`) |
| Bridge unit tests | `python3 -m unittest discover -s bridges -p "test_*.py"` |
| Client unit + integration tests (Python) | `python3 -m unittest discover -s client/tests` (`pip install -e ./client` for the `phone-mcp` CLI) |
| Client tests (Node twin) | `node --test client/node/test/test-*.mjs` (`npm install -g ./client/node` for the `phone-mcp` CLI) |
| Agent skills gate (6 skills, 41/41) | `python3 scripts/verify-skills.py` |
| Agent skills eval (static + live battery) | `python3 scripts/eval-skills.py` |
| BLE proof (needs `BLE_PEER` MAC on LAN) | `BLE_PEER=AA:BB:CC:DD:EE:FF ./scripts/verify-mcp.sh <ip>:8080` (+ auto-disconnect) |
| Headless server start/stop (needs QS tile added once) | `adb shell cmd statusbar click-tile com.phonemcp.server/.service.McpServerTile` |

## Layer Discipline
- `mcp/` = pure Kotlin (no `android.*`); framework only in `data/`; UI only in `presentation/`; lifecycle only in `service/`
- New I/O = `ToolProvider` + Hilt `@IntoSet` in `di/AppModule.kt` + `ToolDefinition` + timeout (5s sensor / 30s capture)
- Domain failures → `isError:true`; protocol errors only `-32601/-32602/-32603`

## Media / Permissions / Security
- Blobs → `MediaStore` (`cacheDir/mcp/`), `{mediaUrl:/media/{id}, mimeType, expiresAt}`; never inline base64; 15-min TTL / 100 MB cap
- Every runtime perm needs dashboard explainer + manifest + FGS type; camera/mic/location from foreground only (API 34); NFC/clipboard-read foreground-only
- TLS-only + `Authorization: Bearer` on `/mcp`, `/media/*`, `/info` (`/healthz` open); token in EncryptedSharedPreferences, constant-time compare, 401 + `-32001`; LAN-only bind + Origin check; kill-switches default OFF; auth changes need new ADR

## Memory / Conventions
- Decisions → `docs/adr/NNNN-title.md` + index row in `docs/adr/README.md`; never edit accepted ADRs
- Keep `README.md` + `docs/api.md` current with `McpRouter`/`*Provider`; `scripts/verify-mcp.sh` is conformance gate
- Kotlin official style, PascalCase; `@Inject` ctor, Hilt multibinding; `unregisterListener`/`stopScan`/`release` in `finally`
- Naming: `*Provider` (data), `Mcp*` (protocol), `*ViewModel` (presentation)

## Key Files
- `mcp/McpRouter.kt` — dispatcher
- `domain/{ToolProvider,Gatekeeper,MediaStore}.kt` — contracts
- `client/src/phone_mcp_client/` — unified harness-side client, Python twin (`phone-mcp` CLI; canonical TLS/harness logic, parity-tested vs `bridges/`)
- `client/node/src/` — unified client, Node twin (zero-dep `phone-mcp` bin; same CLI; parity-tested vs bridge)
- `.agents/skills/phone-mcp*/` — 6 agent skills (router + sense/capture/wireless/ui/net); keep in sync with `McpRouter`/`*Provider`; gate is `scripts/verify-skills.py`
- `opencode.json` — project harness wiring (flat `mcp.phone-mcp`, env refs only, timeout 60000; creds in 0600 paired store, never in file)
- `data/*Provider.kt` — I/O impls
- `service/McpServerService.kt` — Ktor FGS
- `presentation/{DashboardScreen,DashboardViewModel}.kt` — UI
- `AndroidManifest.xml` — perms + FGS types
- See `docs/environments.md`, `docs/api.md`, `DESIGN.md`, `conductor/workflow.md`
