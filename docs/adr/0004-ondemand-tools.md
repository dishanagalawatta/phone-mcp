# ADR-0004: On-demand snapshot/clip tools

## Status
Accepted

## Context
Continuous sensors (gyro, mic, video) could stream or poll. MCP `tools/call` is request-response.

## Decision
Every I/O = tool: `sensors_read`, `location_read`, `audio_record`, `camera_capture`, `video_record`, `speaker_say`, `vibrate`, `torch`, `ble_scan`, `nfc_status`, `clipboard_get/set`, `battery_read`, `device_info`. Continuous use = client polling / short clips.

## Consequences
Simple, cacheable, testable. No `resources/subscriptions` in v1. Document poll intervals + clip caps (e.g. video ≤10s default).
