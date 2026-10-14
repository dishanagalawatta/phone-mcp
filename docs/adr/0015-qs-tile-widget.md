# ADR-0015: QS tile + home widget for server control

## Status
Accepted

## Context
Starting/stopping the server required opening the dashboard. A tile and a widget give
one-tap control, and both are headlessly testable (`cmd statusbar`, dumpsys).

## Decision
- `McpServerTile` (toggle tile, `unlockAndRun`, state mirrors the run flag) with a
  dashboard "Add QS tile" button (`requestAddTileService` on API 33+, manual guidance below).
- `PhoneMcpWidget` (status + Start/Stop; Start uses a foreground-service PendingIntent,
  Stop a service one) with a dashboard "Add widget" button (`requestPinAppWidget`).
  Service start/stop broadcasts refresh placed widgets.
- Verified on-device: tile added + tapped (start/stop logged), widget pinned and Start
  verified (server up at moment of tap). Note: `cmd statusbar click-tile` does NOT
  deliver to custom tiles on Samsung — human taps only.

## Consequences
Positive: no-app-open control; background FGS-start limits still apply on API 34+
(dashboard remains the fallback).
Negative: widget Start can fail silently when the app was long-backgrounded; Stop always works.
