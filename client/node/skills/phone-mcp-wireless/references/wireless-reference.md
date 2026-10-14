# Wireless reference

## Shapes

- MAC: `AA:BB:CC:DD:EE:FF` (uppercased after validation).
- UUIDs: canonical `xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx` for `serviceUuid` and `charUuid`.
- Hex payloads: even-length hex, max 1024 chars (512 bytes ATT cap). `ble_read` returns `{hex, size, text?}` where `text` appears only for printable UTF-8.

## Lifecycle

```text
ble_scan -> ble_connect -> ble_read | ble_write | ble_subscribe -> ble_disconnect
```

Always disconnect, even after failures. Reconnect after 30 s idle.

## Arg clamps

| Tool | Args |
|---|---|
| `ble_scan` | `durationMs` 2000..30000 default 10000. |
| `ble_connect` | `address` required. |
| `ble_read` | `address`, `serviceUuid`, `charUuid` required. |
| `ble_write` | Same triple plus `hex` required, `withResponse` default true. Kill-switch gated. |
| `ble_disconnect` | `address` required. |
| `ble_subscribe` | Same triple plus `durationMs` 2000..60000 default 10000. Returns `{count, events[{service, char, atMs, data}]}`. |
| `nfc_status` | No args. Returns `{present, enabled, waitingReaders, lastTapAgeMs?}`. |
| `nfc_read` | `timeoutMs` 1000..60000 default 10000. Returns tag or `waiting_for_tap`. |

## Notes

- API 31+ without `neverForLocation` needs a location grant or scans succeed empty. Pre-31 needs location perm for names.
- Classic BR/EDR peers (for example many keyboards) are invisible; `ble_connect` 133 confirms non-LE or unreachable.
- Subscribe needs a notifiable char; unbonded HID CCCD writes fail at system level and need bonding. Indicate-only chars use CCCD `0x0002`.
- NFC read path is foreground-only; `nfc_read` parks until the next tap or timeout.
