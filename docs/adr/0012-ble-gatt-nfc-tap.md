# ADR-0012: BLE GATT sessions + NFC tap intake (extends ADR-0004)

## Status
Accepted (extends ADR-0004's on-demand model; no prior ADR modified)

## Context
`ble_scan` and `nfc_status` left the two most physical I/Os half-exposed. BLE GATT
(connect/read/write) and real NFC tag reads complete the exhaustive-I/O promise, but both
have platform realities: GATT is callback-driven and serial; NFC reader mode needs a
foreground Activity and a human tap.

## Decision
- BLE: `ble_connect` (connect + service discovery → UUID table), `ble_read`,
  `ble_disconnect` are open tools; `ble_write` is gated by a new `ble_write` kill-switch
  (default OFF, dashboard toggle) because it actuates external hardware. One cached
  `BluetoothGatt` per address, per-device mutex, 15 s op timeouts, explicit disconnect.
  Notify/subscribe deferred (streaming story stays sensor-only for v1).
- NFC: `nfc_read {timeoutMs}` long-polls for the next tap (returns `waiting_for_tap`
  past the timeout so the agent can prompt the user and retry); recent taps answer from
  cache. Reader mode is demand-driven (enabled on the resumed Activity exactly while
  waits are parked) — no idle tap hijacking. NDEF Text/URI decoded; payloads capped
  (4 KB, hex) and truncated flag set.

## Consequences
Positive: full radio matrix, no background NFC polling (impossible by OS design).
Negative: BLE writes need an explicit user toggle per session; NFC reads need the
dashboard foregrounded and a human tap — both are inherent, documented constraints.
