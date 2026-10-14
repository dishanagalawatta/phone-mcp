# ADR-0016: BLE notify-collect (extends ADR-0012)

## Status
Accepted (extends ADR-0012; no prior ADR modified)

## Context
ADR-0012 left notify/subscribe deferred. Passive event collection (button presses,
sensor broadcasts from peripherals) fits the on-demand model without a persistent
subscription channel: collect-then-return, like a bounded sensor poll.

## Decision
- New tool `ble_subscribe {address, serviceUuid, charUuid, durationMs}` (default 10 s,
  clamped 2–60 s): enables notifications via the CCCD (0x2902), collects change events
  as `{service, char, atMs, data:{hex, text?, size}}`, disables afterwards, returns
  `{count, events}`. Passive read-only: no kill-switch (like `ble_read`); only
  `ble_write` stays gated.
- One shared `BluetoothGattCallback` with per-operation slots (connect/discover/read/
  write/descriptor) plus a notification channel sink; per-device mutex preserved;
  15 s op timeouts; characteristic must advertise notify/indicate or the call fails
  explicitly (no silent empty success).

## Consequences
Positive: live peripheral events without SSE plumbing or background subscriptions.
Negative: one-shot windows only — true push subscriptions remain future work.
