---
name: phone-mcp-wireless
description: Use when scanning or connecting Bluetooth LE, reading or writing GATT characteristics, collecting BLE notifications, or waiting for NFC taps over Phone MCP.
---

# Phone MCP Wireless

## Overview

Interact with nearby BLE peripherals through a scan-connect-read-write-subscribe-disconnect lifecycle, and intake NFC taps with explicit user cooperation.

## Preconditions

- Complete `phone-mcp` pairing and smoke first.
- Grant Bluetooth and location permissions (API 31+ scans return empty success without a location grant).
- Enable the `ble_write` kill-switch before any `ble_write` (default OFF, denial asserted, never fired silently).

## Workflow

### 1. Discover and connect

Call `ble_scan` with `durationMs` 2000..30000 (default 10000); expect `{devices[{address,info}], count}` capped at 100 unique LE devices. Classic BR/EDR peers never appear by design. Call `ble_connect` with required `address` (`AA:BB:CC:DD:EE:FF`); expect `{connected:true, services[{uuid, characteristics[]}]}`. A `133` failure means a non-LE or unreachable peer.

Completion criterion: target address appears in scan output and connect returns its service table.

### 2. Exchange GATT data

Call `ble_read` with required `address`, `serviceUuid`, `charUuid`; result carries `{hex, size, text?}`. Call `ble_write` with the same triple plus required even-length `hex` (max 1024 chars = 512 bytes) and `withResponse` (default true); this actuates hardware. Call `ble_subscribe` with the triple plus `durationMs` 2000..60000 (default 10000) to collect notifications; needs a notifiable char (`-32602` otherwise), and unbonded HID CCCD writes are rejected at system level (bond first). Reconnect after 30 s idle; idle peripherals drop links.

Completion criterion: read or subscribe returns bytes; write returns `wrote N bytes`.

### 3. Release and tap NFC

Call `ble_disconnect` with `address` after every session. For NFC, call `nfc_status` for adapter and tap-intake state, then ask the human to tap and call `nfc_read` with `timeoutMs` 1000..60000 (default 10000). Foreground read path only. Expect tag `{id, techs[], records[], atMs}` or `waiting_for_tap` on timeout.

Completion criterion: GATT is disconnected and the NFC tap (or explicit timeout) is recorded.

## Error handling

Treat empty scans with permissions held as a grant-context or Classic-peer issue, not a retry loop. Read subscribe errors fully before re-granting. Always disconnect; never leave GATT sessions open.

## Resources

- `references/wireless-reference.md`: MAC and UUID shapes, hex limits, HID bonding and idle-link notes.
