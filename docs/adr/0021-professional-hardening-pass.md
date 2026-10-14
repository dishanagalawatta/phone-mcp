# ADR-0021: Professional Hardening Pass (Auth, FGS, Transport, Modularity)

- Status: Accepted
- Date: 2026-10-13
- Supersedes: n/a (hardens ADR-0006/0009/0010/0017/0019)
- Context: Phase 1 audit found FD leak on auth hot path, per-request token creation, inconsistent JSON-RPC codes, START_STICKY headless restart, VPN coroutine leak, predictable ISN, god-classes, a11y node leaks, main-thread I/O, and R8 catch-all.

## Decisions

1. Auth hot path is read-only (`TokenStore.current()`, UTF-8 constant-time compare). `ensure()` only in UI. `SecretsLock` closes `RandomAccessFile` (FD leak fix).
2. Transport errors aligned to MCP 2025-06-18: Origin-blocked and bad version → `-32600` (not `-32603`/`-32602`); `GET /mcp` requires auth before 405; `CancellationException` rethrown in SSE path.
3. FGS services use `START_NOT_STICKY` + null-intent guard (no headless camera/mic/location/VPN resume). VPN tunnel jobs bound to `tunnelJob` and cancelled on stop; ISN from `SecureRandom`; LAN bypass covers wlan/eth/p2p.
4. FGS startup hardening (device-proven on physical test device, API 34): `McpServerService` claims mic/camera/location/connectedDevice types only when their runtime permissions are already granted (fresh-install `SecurityException` crash fix); `startForeground` wrapped in try/catch → graceful stop, never a process crash. `MainActivity` STOP uses plain `startService` (fixes `DidNotStartInTime` when the process was dead); START failures toast instead of crashing. `onDestroy` snapshots + clears engine state synchronously and stops Ktor off-main (no ANR, no stale-flag race on rapid re-Start). Run flag is truthful: set only in `startServer()` on engine creation and cleared in STOP/`onDestroy` — never in `onCreate` (which also runs for STOP-tap creations and produced a stuck Running UI with a dead engine).
4. Single-sourced protocol identity (`mcp/McpProtocol`) + `ArgParser` util (8 providers migrated) + `Map` tool registry (O(1) dispatch) + `Gatekeeper` exact-match for `ble_write`. `net/NetParsers` (529 lines) split into `NetParsers` (parsers + addr utils, 382) + `NetBuilders` (synthesis/checksums, 164) + `NetBits` (shared bit ops); ~40 caller qualifiers updated mechanically, pinned by `NetParsersTest`/`PcapHarTest` + live `net_*` proofs on device. `data/UiProvider` (572 lines) split into `UiProvider` (dispatch/capture/gestures, 394) + `UiTree` (dump/finders/recycling, 195); `ui_dump` (52 nodes), `ui_click` graceful-miss and `ui_screenshot` (PNG 1080x2400) proven live. `ui_screenshot` also needed `android:canTakeScreenshot="true"` (exists since API 30, matching the API-30 gate) — previously failed with "Services don't have the capability". `data/NetInspectProvider` (531 lines) split into dispatcher `NetInspectProvider` (160: dispatch/streaming/pure parsing) + `NetInspectCatalog` (schemas, 121) + `NetInspectActions` (queries, 218) + `NetInspectCapture` (capture/export, 107); full offline battery proven live (status/firewall roundtrip/geo/blocklist/tls/capture-consent/empty-export-errors/`net_stream` SSE 5 events). Dropped the unused `Context` dep from the provider in passing.
5. `TlsStore` validity 3650 → 825 days; secret-hash log removed; `security-crypto` isolated in `CryptoPrefs` for one-file future migration (MasterKeys deprecated).
6. `UiProvider` recycles all owned nodes, adds 10s screenshot timeout; `BleGatt` restores CCCD disable; `FileMediaStore` probes known suffixes; `DashboardViewModel` moves LAN-IP/perms off Main + sensitive clipboard flag.
7. Manifest sets `usesCleartextTraffic=false`. `BLUETOOTH_SCAN neverForLocation` deliberately omitted (app derives location via `location_read`; asserting it would filter beacons and misrepresent behavior).

## Consequences

- Stricter: headless restarts stop; `GET /mcp` now 401s without token; weak TLS rotates sooner (825d).
- Compatible: no tool renames, no version bump, no UX change.
- Verify: `testDebugUnitTest` + `lintDebug` green; `verify-mcp.sh` + Inspector before merge; R8 device-matrix on release.
- Final gate (physical test device, API 34): `connectedDebugAndroidTest` 18/18 (16 pass incl. `fullAgentJourney`, `nfcTapEndToEnd`, camera/mic/GATT suites; 2 pre-existing conditional skips), R8 release live (init/41 tools/vibrate/info), VPN re-proven post-split (consent → 5× ICMP pcap, parsed).
- `FLAG_SECURE` is user-toggleable (Settings → Appearance → Secure screen, default ON): secure windows render black in mirrors (scrcpy), so mirror users switch it off knowingly and re-enable after. Toggle state persists in `phone_mcp_ui` prefs and applies reactively via `LaunchedEffect`; the setter re-snapshots (tick alone serves stale `netState`).
- A11y detection compared against the short component form (`.service.…`) while the system stores the full name (`com.phonemcp.server.service.…`) — dashboard always showed OFF. Fixed with package + class-short-name matching (`isServiceListed`, unit-tested for both forms) plus the `ACCESSIBILITY_ENABLED` master-toggle check.
- `NetworkTunnelCard` carries its own Network-inspection kill-switch (same `Gatekeeper` as Guardrails, Start VPN gated on it) instead of sending users hunting — hint text updated to match.
- VPN live capture proven on physical test device (API 34): consent sheet → tunnel (`vpnActive:true`, background flows relayed) → `net_capture_start` → traffic → `net_capture_stop` → `net_export_pcap` (valid BE pcap v2.4; 5× ICMP 10.0.0.2→8.8.8.8 independently parsed) → tunnel stopped (`vpnActive:false`). One-time OS consent is the only human-gated step; the full capture/export loop is MCP-driven.
