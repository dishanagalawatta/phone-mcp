# Changelog

All notable changes. Format: Keep a Changelog.

## [Unreleased]

## [0.2.0] - 2026-10-15
### Added
- Dynamic third-party capabilities over local IPC (ADR-0032…0035): other apps register tool bundles via bound-service Messenger, directory ContentProvider, or announce broadcast; namespaced `ext_<app>__<tool>` in `tools/list` with `listChanged:true`, `GET /mcp/events` SSE push, default-deny `ext_` master switch + per-capability approval + signer-pin enforcement, encrypted restart persistence; `capabilities_list/register/unregister/approve/revoke/rescan` management tools (47 static tools total); `docs/external-capabilities.md` provider guide + zero-dep `sample-provider/` reference app; 226 JVM unit tests + on-device `ExternalE2EDeviceTest`, all live-proven vs test device (register→approve→invoke, SSE frames, kill-restart restore)
- Agent skill coverage for dynamic capabilities: router skill routes `ext_*` to new `references/external-apps.md` playbook; bundles re-synced, `verify-skills.py` green
- `scripts/verify-mcp.sh` conformance probes for the approve round-trip and SSE push (expects exactly 2 `list_changed` frames); `scripts/fuzz-mcp.py` net-stream-smoke fixed to assert the single well-formed `-32602` SSE error envelope (live 12/12)
- Skills ship with harness configuration (ADR-0031): `phone-mcp configure` stages the 6 agent skills next to rendered configs (`<out>/skills/`, `--skills-dir` override, `--no-skills` opt-out) and syncs `<cwd>/.agents/skills/` on `--install`; byte-identical bundles in both twins (`phone_mcp_client/skills/`, `client/node/skills/`), live-secret refusal on stage, `configure-harness.sh` fallback stages the same way
- Agent skills for Phone MCP (ADR-0030): 6 project skills in `.agents/skills/` — `phone-mcp` router (pairing, TOFU TLS, timeouts, media, error taxonomy) + `phone-mcp-sense` (5 tools), `phone-mcp-capture` (9), `phone-mcp-wireless` (8), `phone-mcp-ui` (6), `phone-mcp-net` (13) = 41/41 coverage; spec-only frontmatter, ≤430-word bodies, `references/` disclosure
- `scripts/verify-skills.py` (132 checks: spec, budgets, layout, coverage, markdown-only supply-chain hygiene) + `scripts/eval-skills.py` (static 19/19 quality eval + 6-task live battery, transport-graceful SKIP, DOMAIN-ERROR guardrail reporting); both in README/AGENTS.md command tables
- Skills live proof vs test device: smoke 4/4, battery 6/6 answered (`sensors_read`, `battery_read`, `vibrate`, `ble_scan` with real advertisers, `ui_dump` 46 nodes, `net_status` with live VPN flows) after owner enabled `ui_*`/`net_*` kill-switches
- Headless-start fix note (`docs/harness.md`): API 34 refuses camera/mic/location FGS types on background tile-click starts — resume the dashboard first (`am start ...MainActivity`), then click the tile; diagnosed via `startForeground refused` logcat after a force-stop left a stale tile state
- Unified `phone-mcp` client (ADR-0028): `pip install ./client` → `pair`/`smoke`/`configure --harness all --install`/`list`/`call`/`media`/`export-ca`/`serve`; 9 harnesses (opencode, claude, codex, vscode, cursor, gemini, cline, windsurf, generic); 79 unit+integration tests (incl. ping-less spec-compat guard); parity-tested vs `bridges/` twins
- Node twin of the client (`client/node`, zero-dep, Node 18+): same CLI + 9 harnesses; 71 `node:test` tests incl. bridge parity (child-process isolation), loopback integration, real-TLS export-ca (incl. IP-literal SNI fix); `npm pack` verified; CI runs node suite + token-free dry-run + export-ca self-test
- Shared parity fixture (`client/vectors.json`): TLS modes/headers/SSE/budgets/fingerprints asserted by all four implementations (Python client + bridge, Node client + bridge); insecure-mode hostname divergence documented as unobservable
- Pinned-TLS round-trip suites (both twins): full `smoke` over real local TLS — pin PASS with tlsSha256 WARNING proof, wrong-pin mismatch on every probe, cafile PASS after fail-closed export, insecure PASS; mock servers silenced on expected capture noise
- Hygiene sweep: removed dead code (`sha256_of_der_hex`, Node `redacted()`, stray re-export), Node `--help` exits 0 like the Python twin, doc test-counts synced to 79/71
- Twin-parity audit: Node `--verbose` now logs the redacted config (was parsed but ignored), `pair` honors the threaded env instead of `process.env`; compile sweep over every Python/Node file; counts re-synced to 79/71
- Example gap closed: checked-in `cline-mcp.json` + `windsurf-mcp.json` (client-generated, sibling-conformant, token-free); stale doc index rows fixed (`docs/README.md`, `testing.md`, `ci.md`, root README example list)
- Script parity restored: `configure-harness.sh` renders Cline/Windsurf (2 lines; generic shape already handled) — byte-identical to `phone-mcp configure --bridge node`; CI dry-run asserts 9 files
- Docs sweep: hot-reload replaces restart guidance (`docs/client.md`, `docs/harness.md`), known-good OpenCode snippet + `"timeout": 60000` rationale everywhere (guide, harness doc, checked-in example), rotation-staleness + log-secret warnings, `AGENTS.md` project-wiring row; counts re-verified 79/71/26
- Project OpenCode setup (this repo): `opencode.json` with flat-shape `phone-mcp` entry (`phone-mcp serve`, env refs only, `"timeout": 60000`); paired 0600 store; live-verified on OpenCode v2.0.28 — bridge fallback, hot-reload respawn semantics, and real `battery_read` (79%) + `device_info` calls through the harness. NOTE (resolved): the pre-rotation token reached the opencode server log via the `pair` invocation — ROTATED on owner approval (old token 401-confirmed dead, new token re-paired, both smokes 4/4, stale bridge killed, harness reconnected, live calls re-proven, re-masked + purged)
- LIVE PROOF vs physical test device (API 34) @192.168.1.50 (self-service pairing retrieval via adb: EncryptedSharedPreferences confirmed unreachable, TOFU pin verified dashboard-vs-LAN-capture identical, token shown/re-masked on-device, screenshots + temp PEM purged, token never written to disk): Python smoke 4/4 + Node smoke 4/4 (41 tools), `verify-mcp.sh` complete (vibrate, battery, sensors, audio 2s + media range 206, NFC, net_status, SSE 17+3 events, PCM decode OK; BLE skipped, no BLE_PEER), fuzz 12/12, `export-ca` vs live cert verified
- Schema re-verification (Oct 2026): OpenCode flat `mcp` + `environment` + `{env:}` confirmed vs official docs; Gemini `$VAR` env expansion + `httpUrl`-only confirmed, `trust: false` added to gemini renderers, `.env`-file caveat documented
- Client follow-up: PyPI-ready wheel verified (zero-deps, `phone-mcp` entry point; CI builds it); ping-less spec-compat guard (`SpecCompatTest` vs 2026-07-28 removals); CI `harness` job runs client suite + 9-harness token-free dry-run (actual `twine upload` + npm twin still open — need PyPI token / maintainer call)
- Harness bridge matrix (ADR-0023): stdio↔HTTP twins, 7 harness configs, QR generator, smoke probe
- `scripts/export-ca.sh` + `PHONE_MCP_CAFILE` chain verification + CI `harness` job (ADR-0024)
- ALPN `http/1.1` on all bridge TLS paths, per-call budgets (`FAST_MS`/`MARGIN_MS`),
  `configure-harness.sh --export-ca` chaining (ADR-0025; verified vs physical test-device hardware)
- Live-device proof (physical test device @192.168.1.50): `verify-mcp.sh` exit 0, `fuzz-mcp.py` 12/12,
  smoke 4/4, 41 tools, both bridges authenticated end-to-end
- `verify-mcp.sh`: skip (don't crash on) audio/media legs when the `audio_*` kill-switch is OFF
- Clipboard side effect repaired via headless `clipboard_set`; BLE proof blocked on
  foreground `BLUETOOTH_SCAN` grant (all runtime perms `granted=false` — consent boundary held)
- Delegated foreground grant driven (BT SCAN+CONNECT allowed, camera/mic/location/notify denied);
  `ble_scan` now works mechanically but finds zero advertisers (quiet room); connect proof awaits a peripheral
- Host-as-peripheral attempt stood down: USB BT dongle rejects advertising (Invalid Parameters)
  while the paired/encrypted lab-peripheral input link is up — link left intact, host BT restored pristine
- Lab peripheral disconnected by owner → host peripheral built (Battery Service live, adv registered) but
  phone scans still empty; location grant (Approximate) driven, still empty — radiation unproven,
  peripheral reconnect needs physical-side action (stub cleared); token revealed once in-transcript, re-masked + purged
- Lab peripheral disregarded per owner: controller power-cycled, peripheral rebuilt, kernel-active adv + location grant,
  2 more empty scans; torn down pristine (alias/discoverability/link untouched); scratch hash-swept token-free
- BLE proof COMPLETE vs lab BLE peripheral (test gadget): scan 13 devices, connect + full GATT discovery,
  read battery level, subscribe collect cycle clean, disconnect clean; `verify-mcp.sh` BLE section + disconnect fix;
  audio path live (owner enabled mic guardrail; PCM chunk valid); known warts: HID-subscribe misleading
  permission text, indicate uses notify CCCD value
- Subscribe warts FIXED + proven live: honest CCCD-failure routing (root cause preserved — Samsung demands
  BLUETOOTH_PRIVILEGED for unbonded HID CCCD) and spec-correct indicate value (0x0002 turns status-6
  rejection into a clean collect); QS-tile server control path proven (full autonomous start, no UI taps)
- Confirm guard scoped to Secure screen only (Rotate/kill-switches reverted per owner); lint fully green
  (produceState→LaunchedEffect QR restructure, QR render + full BLE loop re-proven on-device)
- Docs sync pass: harness guide (QS-tile control, BLE agent notes, guard row, table fix), BLE findings in
  `api.md`, harness track in `testing.md` (185/185 JVM, 26/26 bridge, CI job, live proofs), env vars,
  README/llms/AGENTS cross-refs, branding dupe removed
- ADR-0026 (BLE subscribe honesty + CCCD correctness) + ADR-0027 (secure-screen guard scope)
- Keyboard identified as BR/EDR keyboard (bonded to host) but it is BR/EDR, not BLE — invisible to
  `ble_scan`/GATT by design (phone 133s + host connect stall agree); stubs cleared; valid BLE target still needed
- Mirror-black diagnosis: FLAG_SECURE window (expected behavior); Secure screen toggled OFF via automation
  so the owner can see the dashboard — re-enable after (token/QR visible in mirror meanwhile)
- `.gitignore`, `.env.example`, `docs/environments.md` (local-only config)
- `DESIGN.md` (Material 3 semantic design system)
- `docs/api.md` (41-tool MCP reference from `McpRouter`/`*Provider`)
- ADR-0022 workspace standardization
- `docs/README.md` index, `llms.txt`, `CHANGELOG.md`
- `docs/assets/screenshots/` (relocated dashboard captures)
- `conductor/tracks/workspace_standardization/` track
### Changed
- `AGENTS.md` trimmed to <60 lines (agents-md structure, file-scoped commands)
- `README.md`: TOC, env pointer, 41-tool ADR range, script inventory
- Archived `conductor/tracks/modern_ui_redesign/` → `_archive/` (12/12 complete)
### Removed
- `scripts/__pycache__/`, `build/`, `app/build/` (442 MB), `.gradle/`, `.kotlin/` (regenerable; proofs via `collect-proofs.sh`)

## [0.1.0] - 2026-10-13
### Added
- 41 MCP tools + SSE streams + VPN net-inspect + UI automation + TLS TOFU + bearer QR pairing
- ADR-0001..0021, `docs/testing.md` (148/148 JVM, 17/18 test device, 12/12 fuzz), `docs/device-matrix.md`
