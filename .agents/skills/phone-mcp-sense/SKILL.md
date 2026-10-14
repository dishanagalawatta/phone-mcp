---
name: phone-mcp-sense
description: Use when reading phone sensors, streaming IMU batches, getting GPS location, checking battery, or querying device info over Phone MCP. Covers sensors_read, sensors_stream, location_read, battery_read, device_info.
---

# Phone MCP Sense

## Overview

Read environmental and position state from the phone. Use snapshots for one-shot checks and SSE streams for motion windows.

## Preconditions

- Complete `phone-mcp` pairing and smoke first.
- For `sensors_stream` with `includeLocation:true`, enable the Location kill-switch and grant location permission.

## Workflow

### 1. Snapshot first

Call `sensors_read` with an explicit `types` subset from `accelerometer, gyroscope, magnetic_field, pressure, light, proximity, gravity, linear_acceleration, rotation_vector, humidity, ambient_temperature`. Omit `types` for the default set. Clamp `timeoutMs` to 100..10000 (default 2000). Inspect `readings[]` plus `unavailable[]` for missing or timed-out sensors.

Completion criterion: every requested type appears in either `readings` or `unavailable`.

### 2. Stream motion windows

Call `sensors_stream` only over SSE (`Accept: text/event-stream`). Set `intervalMs` 100..2000 (default 200), `durationMs` 1000..60000 (default 10000), `includeLocation` (default true). Read `notifications/sensors` batches (`t`, `sensors{}`, optional `location{lat,lon,acc}`, `warnings[]`).

Completion criterion: stream ends with a final `{tool, batches}` envelope; a second concurrent stream fails with `-32603` by design, so stop the first before starting another.

### 3. Fix position and power

Call `location_read` for a GPS fix (emulators need a mock route). Call `battery_read` freely (no permission; returns `percent`, `charging`, `plugged*`, `temperatureC`). Call `device_info` for model, API level, app version, protocol, uptime, and `tlsSha256` (must equal the dashboard fingerprint).

Completion criterion: location, battery, and device fields are recorded with units; `tlsSha256` matches the pinned value.

## Error handling

Treat `isError:true` as a domain signal (missing sensor, permission, disabled kill-switch). Treat `-32602` as bad sensor name or out-of-range arg; `-32603` as a busy stream slot. Never retry a second stream; close the first.

## Resources

- `references/sense-reference.md`: arg clamps, batch shape, GPS and emulator notes.
