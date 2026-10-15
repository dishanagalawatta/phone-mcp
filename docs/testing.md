# Testing — feature × coverage matrix

Every feature has two gates: a JVM unit test (`./gradlew testDebugUnitTest`, also CI)
and a device proof (`scripts/verify-mcp.sh` step or Inspector call per `docs/device-matrix.md`).

## On-device runs

- **Release (R8-shrunk, 21 MB): 14/14 on physical test device.** Same suite, fully green — rules in
  `config/proguard-rules.pro` validated class-by-class on hardware (kotlin, javax.inject,
  listenablefuture, netty channel/buffer/bootstrap/util, test-visible lifecycle/compose/activity).
  Re-validated 2026-10-13 with the `net_*` + IPv6 code: R8 had silently gutted
  `HttpServerCodec` ctors (TLS served, all HTTP died) and negotiated h2 into a
  codec-stripped pipeline (GOAWAY+RST) — fixed with keeps for
  `io.netty.handler.codec.{http,http2,*}`, `io.netty.handler.ssl.**`,
  `io.ktor.server.netty.**` plus dontwarns for netty's absent optional natives
  (brotli4j/zstd/jzlib/lz4/lzma/ning/jboss/pkitesting). Minified release now
  serves 41 tools + live VPN capture on-device.


- **JVM: 226/226** (`testDebugUnitTest`), `lintDebug` 0 errors (incl. `BleGattTest`
  CCCD/message vectors, `ConfirmTextTest` guard copy, `GatekeeperTest`, arg-parser suites,
  plus the dynamic-capability suites below).
- **Physical test device (API 34, hardware): 17/18.** Only `nfcTap` red — it needs a human holding
  a tag during its 45 s window (proven green with a live tap: NXP UID + NDEF). Everything
  else green incl. UI tests, E2E, stream-with-location, rotation, keeper. Green: providers, TLS pin,
  stream-with-location (real GPS fix in batches), kill-switch E2E (incl. ble_write denial),
  rotation-over-HTTP, live NFC tap (NXP UID + NDEF), BLE GATT session, keeper. Red only when
  the device can't cooperate: `rotate`/`toggles` need an unlocked screen for compose, and
  `nfcTap` needs a human holding a tag (both proven green when conditions hold).
  stream-with-location (real GPS fix in batches), kill-switch E2E, rotation UI,
  keeper. (Touch injection on this unit is flaky system-wide; the emulator covers
  UI interaction deterministically — see below.)
- **Physical test device E2E (`McpE2EDeviceTest`): 2/2 green** — full agent journey over loopback
  TLS incl. the `ble_subscribe` sweep (first 10 GATT chars, 2 s each) against the lab
  BLE peripheral at `11:22:33:44:55:66` (example placeholder — set `BLE_PEER` locally), SSE + PCM audio-stream decode, rotation kill,
  and `ui_` gated pass (accessibility bound). `tools/list` asserts ≥28 tools (static surface is now 47: 28 + 13 `net_*` + 6 `capabilities_*`).
- **Emulator (API 34 x86_64, `phone-mcp-emu` AVD): `verify-mcp.sh` fully green over
  adb-forwarded TLS** (47 tools, audio range proof, both SSE streams, net probes;
  `rooted:false`, NFC correctly unavailable), **plus live VPN capture**:
  DNS resolves through the tunnel, plain HTTP 200 relays with host attribution,
  system DNS-over-TLS (`8.8.8.8:853`) and QUIC (`8.8.8.8:443`, bidirectional
  bytes) flow through the relay, `ip6-58` multicast captured. No human needed —
  VPN consent approved via UI tap. Prior emulator result: DashboardUiTest 3/3
  (found + fixed along the way: the Rotate button sat below the fold — dashboard is now
  scrollable; the test scrolls explicitly; rotation emits a logcat marker).
- LAN HTTPS battery from host: init/list(21)/20 tools/media round-trips/SSE (17 events)/
  401-405-400/`-32601`/`-32602` shapes green; TOFU fingerprint matched 4 ways and stable
  across reinstalls.
- **Host fuzz (`scripts/fuzz-mcp.py`): 12/12 green on physical test device** — malformed envelopes,
  wrong arg types, 5 MB body (now 413 past 1 MiB), 4 MB BLE hex (bounded),
  200 KB texts, concurrent SSE (second stream `-32603` in 0.2 s, primary 12/12),
  12-way concurrent calls, auth abuse (10 KB token → 400 at Netty header ceiling,
  still denied), media traversal/range abuse, plus `net_*` wrong-types (21 probes),
  600-entry policy / 300 KB keylog oversize, `net_stream` SSE smoke. Findings fixed: 1024-bit cert →
  2048-bit (ADR-0017), hex cap, body cap, evict over-trim (ADR-0018). Bench rule:
  wake the device first (deep sleep stretches waits ~1.5×); fuzz clients must
  offer ALPN `http/1.1` (Netty drops ALPN-less handshakes).

## Harness + bridge track (host-side, phone-verified)

- **Bridge unit tests: 26/26** (`python3 -m unittest discover -s bridges -p "test_*.py"`):
  fingerprint/pin, headers, SSE parsing, QR parsing, TLS-mode matrix, per-call
  budgets, config rendering. Node↔Python parity enforced (6 TLS modes + 9 timeout
  vectors, checked in CI).
- **CI `harness` job** (no phone needed): bridge tests, node/python/bash syntax,
  config schemas (strict JSON + VS Code JSONC + TOML, no baked-in tokens),
  TLS/timeout parity, `export-ca` self-test over local TLS fixtures, bridge
  round-trips, ALPN regression guard (phone Netty drops ALPN-less handshakes),
  plus the unified clients: Python suite (79 tests) + Node suite (71 tests)
  incl. shared `vectors.json` parity, pinned-TLS round-trips, 9-harness
  token-free `configure` dry-runs, wheel + `npm pack` builds.
- **Agent-skills gates (phone-free, run anywhere):** `scripts/verify-skills.py`
  132/132 (spec frontmatter, ≤500-word bodies, `references/` disclosure,
  47/47 tool coverage, markdown-only supply-chain hygiene: no executables,
  no injection patterns, token/redaction discipline) + `scripts/eval-skills.py`
  static 19/19 (focus, completion criteria, single-source ownership).
- **Agent-skills live battery (needs paired phone):** 6/6 answered vs test device —
  `sensors_read`, `battery_read`, `vibrate`, `ble_scan` (real advertisers),
  `ui_dump` (46 nodes), `net_status` (live VPN flows); `ui_*`/`net_*`
  kill-switch denials report as DOMAIN-ERROR (guardrail proof, ADR-0030).
- **Live against test-device hardware:** both bridges authenticated end-to-end (PIN +
  CAFILE modes, all TLS modes reach the real `-32001` gate); `export-ca.sh`
  against the true Ktor cert; `phone-mcp-smoke.py` 4/4; `verify-mcp.sh` exit 0;
  fuzz 12/12; anonymous transport-gate battery 14/14 (401/400/403 shapes,
  malformed, 16× concurrent, healthz stable); full BLE loop vs lab test peripheral.
- **Known environmental residuals (not code):** BLE connect needs a BLE (not
  Classic) peripheral in range; unbonded HID CCCD writes need system-level
  BLUETOOTH_PRIVILEGED (reported honestly, never retried); idle peripherals
  drop GATT links — reconnect before subscribe after >30 s idle.

## Tools (41)

| Tool | Unit test | Device proof |
|------|-----------|--------------|
| `net_status/connections/usage` | — (TrafficStats/PackageManager need a device; flow table covered via parser tests) | Test device: `vpnActive:true`, interfaces enumerated, live flows with per-app package + SNI, `bytesUp/Down` both counting (incl. `tcp6`/`udp6` families); `net_usage` real counters; idle push flow survives past 8 s with downstream bytes (timeout≠EOF fix) |
| `net_capture_start/stop` | `NetPolicyArgsTest` (stream/policy args) | `vpn_consent_required` before approval, `capturing:true` after; system consent sheet approved on-device; rings kept for export |
| `net_export_pcap` (pcap/pcapng) | `PcapHarTest` (global header, SHB/IDB/EPB walk, comment embed) + `NetParsersTest` (IP/TCP checksum vectors) | 27-packet PCAPNG downloaded via `/media`, structurally validated (SHB+IDB+27 EPB); opens in Wireshark via `tshark -r` |
| `net_export_har` | `PcapHarTest` (HAR 1.2 skeleton, escaping) | real captured `GET http://example.com/` → valid HAR 1.2 via `/media`; HTTPS yields SNI metadata only (proven: SNI present, no bodies) |
| `net_firewall_get/set` | `NetPolicyArgsTest` + `FirewallEngineTest` (suffix/CIDR/allow-bypass/deny) | live kill: `blockedHosts:["example.com"]` → curl 000 + flow marked `blocked`/`host example.com blocked`; reset restores 200 |
| `net_blocklist_check` / `net_geo_lookup` | `FirewallEngineTest` (suffix match, private scope) | `verify-mcp.sh` offline probes (no VPN needed) |
| `net_tls_info` (+ keylog import/export) | `PcapHarTest` (keylog round-trip) | import 1 key → `storedKeys:1`, `observedSniFlows:4`, `interception:false`; export path via `/media` |
| `net_stream` | `NetPolicyArgsTest` (arg clamps) | 4 SSE events (3 batches + final) live; second stream → `-32603` (same guard as sensors) |

| Tool | Unit test | Device proof |
|------|-----------|--------------|
| `sensors_read` | `SensorsArgsTest` (defaults/subset/clamp/unknown-throws) | script §sensors_read; shake → values change |
| `sensors_stream` | `StreamArgsTest` + `RouterStreamTest` (emit/final, gate, busy, reject) | script SSE probe (~17 `data:` events) |
| `location_read` | `LocationArgsTest` | emulator mock route → `source:current`, lat/lon present |
| `audio_record` | `AudioArgsTest` | script range proof (`full:200` + `range:206`), playable `.m4a` |
| `camera_capture` | `CameraLensTest` | JPG renders via `/media` URL |
| `camera_video` | `VideoArgsTest` | `.mp4` plays; `withAudio:false` path without mic grant |
| `speaker_say` | `SpeakArgsTest` | audible speech; blank text → `-32602` |
| `vibrate` | `VibrateArgsTest` | physical buzz + `"vibrated 200ms"` |
| `audio_stream` | `AudioStreamTest` (args/rms) + E2E PCM decode (16 kHz s16le, 32 KB/chunk, rms range) | live mic over loopback TLS; LAN probe in `verify-mcp.sh` |
| `torch` | `TorchArgsTest` | flash on, then off (verify off state) |
| `ble_scan/connect/read/disconnect/subscribe` | `ScanArgsTest` + `BleArgsTest` + `bleConnectListsServices` + `BleGattTest` (CCCD notify/indicate values, honest failure text) | live lab-peripheral GATT table over MCP; `ble_write` gated (denial asserted, never fired at hardware); `ble_subscribe` sweep (first 10 chars, 2 s each — stream or explicit notify-unsupported); test-peripheral full loop live (scan, connect + discovery, battery read, notify-collect clean, disconnect clean); HID CCCD on unbonded devices needs system-level BLUETOOTH_PRIVILEGED (reported, not retried); peripherals drop idle GATT links — reconnect before subscribe after >30 s idle |
| `nfc_status` / `nfc_read` | `NfcCodecsTest` (Text/URI/codecs) + `nfcTapEndToEnd` | live tap on test device: NXP UID `<tag-uid>` (redacted) decoded + NDEF parsed; `waiting_for_tap` when idle |
| `clipboard_get/set` | — (framework-only; round-trip is the test) | foreground app → set then get round-trips |
| `battery_read` | `BatteryPayloadTest` (percent/charging/plugs/temp) | script §battery_read |
| `device_info` | — (PackageManager/TlsStore need a device) | `tlsSha256` matches dashboard fingerprint |

## Dynamic capabilities (third-party apps)

| Tool / feature | Unit test | Device proof |
|------|-----------|--------------|
| `capabilities_list/register/unregister/approve/revoke/rescan` | `ExternalCapabilityRegistryTest` (ownership, limits, approval gate, signer pinning) + `ExternalRouterTest` (merge, `ext_` gate, counts) | live register → approve → gated call → revoke → unregister chain on test device |
| `ext_<app>__<tool>` invocation | `ExternalRouterTest.externalCallRoutesWhenEnabled` (fake invoker) | `ExternalE2EDeviceTest` on-device: rescan → approve → real Binder invoke of `ext_demo__echo/add` in the sample app, unknown-tool `-32602`, consent cleanup |
| `notifications/tools/list_changed` push | `ExternalEventsTest` (spec shape, emit on register/unregister, heartbeat silence) | `GET /mcp/events` held open across register/unregister → exactly 2 spec-exact frames; `verify-mcp.sh` asserts the same |
| Restart persistence | `CapabilitySnapshotsTest` (round-trip, corrupt→empty, re-validation, fresh heartbeat) | force-stop → restart → record + approval + signer pin restored, then unregistered clean |

## Protocol / transport / safety

| Feature | Unit test | Device proof |
|---------|-----------|--------------|
| JSON-RPC envelopes + codes | `JsonRpcTest`, `McpRouterTest` | script initialize/list/call; unknown method → `-32601` |
| SSE framing + busy guard | `RouterStreamTest` | second concurrent stream → `-32603` |
| Origin / version-header gates | `McpHttpTest` | foreign `Origin:` → 403; missing version → 400 |
| Range media serving | `McpHttpTest` (parse) + `FileMediaStoreTest` (store/evict) | script range proof `range:206` |
| Kill-switches | `GatekeeperTest` (incl. `net_*` default OFF) | disable Camera → `camera_capture` isError naming the switch |
| Appearance (M3 light/dark) | `ThemeModeTest` (parse/resolve) | emulator screenshots: light default, system-forced dark, manual Dark-in-light-OS override via chips |
| Bearer auth (`-32001`/401) | `AuthTokenTest` | script without `TOKEN` → 401s; rotate → old 401s |
| TLS TOFU fingerprint | `PairingTest` (vector + format + QR payload) | openssl probe matches dashboard |
| LAN discovery | `NetInfoTest` | dashboard URL matches `ip route` subnet |

## Deliberately device-only (no JVM test)

CameraX capture/video, TTS speech, vibration motor, torch LED, BLE scan loop, NFC adapter,
clipboard manager, `TlsStore`/`TokenStore` persistence, QR bitmap rendering — all require
framework services. Their suites assert on the pure seams (arg parsing, payload building);
behavior is proven on hardware per the right column.
