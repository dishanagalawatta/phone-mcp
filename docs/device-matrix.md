# Device Matrix — human-run verification

Static checks (balance, TOML, API audit) pass in this repo. The following needs real
hardware/emulators; run top to bottom, tick off per API level.

## Targets

| Target | Why it matters |
|--------|----------------|
| Emulator API 29 | minSdk: FGS types, deprecated `MediaRecorder()`, no BLE/NFC runtime perms, clipboard FG rule starts here |
| Emulator API 33 | `POST_NOTIFICATIONS` runtime, photo picker era, TTS voices vary |
| Emulator API 35 / physical device | Target SDK: 6 h `dataSync` FGS timeout, while-in-use camera/mic, predictive back (no impact) |
| Physical device (any 31+) | Real sensors, real BLE peers, torch, vibration you can feel |

## Procedure (each target)

1. `./scripts/setup.sh` → JDK 17, SDK present, `adb` reachable.
2. Open in Android Studio → sync (installs wrapper + SDK 35) → `./gradlew testDebugUnitTest` → all green.
3. Install + launch → tap **⚡ Grant Full Agent Access** (or grant permissions and enable mic/camera/location toggles individually) → Start.
4. `./scripts/verify-mcp.sh <lan-ip>:8080` → `initialize`, `tools/list` (47 tools), `vibrate` 200,
   `GET /mcp` → 405, `/info` count, `battery_read`, `sensors_read` (emulator: mostly `unavailable` — correct),
   `audio_record` 2 s → `full:200` + `range:206`, `net_status` (+ `net_geo_lookup`/`net_blocklist_check`
   offline probes — both pass with the `net_` toggle OFF).
5. Inspector (Streamable HTTP → `https://<lan-ip>:8080/mcp`, accept the self-signed cert after
   matching its fingerprint to the dashboard):
   - `camera_capture` → JPG renders; `video_record {"durationMs":3000}` → `.mp4` plays.
   - `location_read` → on emulator, set a mock route first (Extended Controls → Location).
   - `speaker_say {"text":"MCP online"}` → audible.
   - `torch {"on":true}` → flash on, then `{"on":false}` → off (verify off state!).
   - `ble_scan {"durationMs":5000}` → near a BLE peripheral; without `BLUETOOTH_CONNECT`, names show `?` (expected).
   - `clipboard_set` then `clipboard_get` **with the app foregrounded** → text round-trips.
   - `nfc_status` → emulator reports unavailable (correct); physical NFC-on device reports `enabled:true`.
6. Kill-switches: disable Camera → `camera_capture` returns `isError` naming the kill-switch; re-enable → 200.
   Same for Network inspection: with `net_` OFF, `net_status` returns `isError` naming the switch; enable it,
   then dashboard → Start VPN → approve the system consent sheet → `net_capture_start` reports `capturing`.
   Generate traffic (browser refresh) → `net_connections` shows flows with SNI/host → `net_export_pcap
   {"format":"pcapng"}` → open in Wireshark; `net_export_har` → HAR viewer. Set a firewall rule
   (`net_firewall_set {"blockedHosts":["example.com"]}`) → matching flow marked `blocked`, traffic dropped.
7. Background: background the app 2 min → `sensors_read` still 200 (FGS alive); on API 35 note the 6 h `dataSync` ceiling for marathon runs.
8. Stay awake for timing benches: deep sleep stretches bounded waits ~1.4–1.6×
   (`nfc_read` 60 s → ~84 s) and stalls SSE emission; wake (`adb shell input
   keyevent KEYCODE_WAKEUP`) + `settings put system screen_off_timeout 600000`
   before fuzz/benchmark runs, restore after. Unit-behavior is unaffected —
   only wall-clock timing.

## Known platform constraints (do not file as bugs)

- Clipboard read + NFC tap-to-read are foreground-only by OS design.
- Pre-31 BLE scanning rides on `ACCESS_FINE_LOCATION` (requested); 31+ uses `BLUETOOTH_SCAN` (+`CONNECT` for names).
- `torch` needs `CAMERA` runtime even though it is "just a light".
- Emulator mic/camera/sensors are faked; silence/black/static values are environment, not app bugs.
- `video_record` with `withAudio:true` needs mic grant or it fails fast with a retry-silent hint.
