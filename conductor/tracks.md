# Conductor Active Tracks

| Status | Track ID | Description | Updated |
| :--- | :--- | :--- | :--- |
| [x] | `modern_ui_redesign` (archived → `_archive/`) | Modern, clean, professional Material 3 UI overhaul | 2026-10-13 |
| [x] | workspace_standardization | Industrial cleanup + env/config/docs standardization (ADR-0022) | 2026-10-14 |
| [x] | harness_bridge | Stdio bridge + per-harness config matrix for OpenCode/Claude/Codex/VS Code/Cursor/Gemini (ADR-0023) | 2026-10-14 |
| [x] | harness_direct_ca | CA export + verified direct-remote mode + CI harness job (ADR-0024) | 2026-10-14 |
| [x] | harness_live_verify | ALPN mandate + per-call timeouts + CA chaining, verified vs physical test device (ADR-0025) | 2026-10-14 |
| [x] | harness_live_proof | Owner-delegated token retrieval via dashboard UI; full live proof (verify exit 0, fuzz 12/12, smoke 4/4); secrets purged | 2026-10-14 |
| [x] | harness_closeout | Clipboard repaired headlessly; BLE blocked on foreground grant (consent held); docs synced | 2026-10-14 |
| [x] | harness_ble_grant | Drove real grant flow (BT allowed, rest denied); scan works, zero advertisers; scratch verified token-free | 2026-10-14 |
| [x] | harness_ble_standdown | Host-peripheral attempt refused (dongle rejects adv w/ live lab-peripheral link); lab peripheral untouched, BT restored | 2026-10-14 |
| [x] | harness_ble_periph | Owner freed radio; peripheral built+registered, scans still empty, location granted; lab peripheral needs physical reconnect | 2026-10-14 |
| [x] | harness_ble_final | Lab peripheral disregarded; rebuilt+kernel-active adv, still empty; torn down pristine; secrets hash-swept | 2026-10-14 |
| [x] | harness_ble_k380 | Keyboard proven BR/EDR (not BLE) via phone 133s + host stall; stubs cleared; BLE target still open | 2026-10-14 |
| [x] | harness_ble_proven | Full BLE proof vs lab test peripheral (scan/connect/read/subscribe/disconnect) + media path live; warts logged | 2026-10-14 |
| [x] | harness_fixes_live | Subscribe error routing + indicate CCCD value fixed, unit-tested, proven live; tile-start path autonomous | 2026-10-14 |
| [x] | harness_guard_scope | Secure-only confirm dialog (reverted Rotate/kill-switch guards); lint 0 errors; QR + BLE re-proven live | 2026-10-14 |
| [x] | harness_adr_closeout | ADR-0026/0027 written (subscribe honesty, guard scope); 185/185 unit; server down, restart pending | 2026-10-14 |
| [x] | harness_restart_reproof | Owner restarted; full BLE loop re-proven on current build; secrets purged | 2026-10-14 |
| [x] | harness_docs_sync | Harness/API/testing/env/README/llms/AGENTS cross-refs synced; table fix; dupe removed | 2026-10-14 |
| [x] | harness_mirror_fix | Diagnosed mirror-black as FLAG_SECURE; toggled Secure screen OFF for owner visibility | 2026-10-14 |
| [x] | unified_client | Unified `phone-mcp` client (serve/smoke/pair/configure/list/call/media/export-ca; 9 harnesses; ADR-0028) | 2026-10-14 |
| [x] | unified_client_node | Node twin of the client (zero-dep, same CLI; IP-literal SNI fix; ADR-0029) | 2026-10-14 |
| [x] | unified_client_live | Live soak vs test device: self-service pairing, both smokes 4/4, verify complete, fuzz 12/12, export-ca live; secrets purged | 2026-10-14 |
| [x] | project_opencode_setup | Project `opencode.json` (flat shape, env refs, timeout 60000) live on v2.0.28: paired fallback, respawn semantics, real tool calls; token in server log → rotation advised | 2026-10-14 |
| [x] | token_rotation_live | Owner-approved rotation via dashboard tap: old token 401-confirmed dead, new token transcribed (1→l correction via 32-combo probe), both smokes 4/4, stale bridge killed, harness reconnected, live calls re-proven, re-masked + purged | 2026-10-14 |
| [x] | docs_sweep_post_setup | All guides aligned to live findings: hot-reload over restart, known-good snippet + timeout rationale, rotation/log-secret warnings, counts re-verified | 2026-10-14 |
| [x] | phone_mcp_skills | Agent skills for Phone MCP (6 skills, 41/41, ADR-0030; verify 132/132, eval 19/19 + live 6/6 vs test device) | 2026-10-14 |
| [x] | phone_mcp_skills_dist | Skills ship with harness config (bundles in both twins, configure stages/installs, ADR-0031) | 2026-10-15 |
