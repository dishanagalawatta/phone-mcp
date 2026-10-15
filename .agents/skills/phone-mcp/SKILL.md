---
name: phone-mcp
description: Use when connecting to Phone MCP, pairing a phone, diagnosing transport or auth errors, downloading media blobs, or when unsure which phone-mcp domain skill applies. Routes to sense, capture, wireless, ui, and net skills.
---

# Phone MCP

## Overview

Connect an agent to an Android phone exposing 47 static tools plus dynamic third-party `ext_*` tools over LAN HTTPS MCP. Pair once, verify transport, then delegate to a domain skill.

## Preconditions

- Phone and agent host on the same LAN, or `adb reverse tcp:8080 tcp:8080` for USB.
- Server started on the phone (dashboard shows IP:port).
- Pairing values available: LAN URL, bearer token, TLS SHA-256 fingerprint.

## Workflow

### 1. Pair and verify transport

Run `phone-mcp pair --qr '<dashboard-qr-json>'`, then `phone-mcp smoke` and expect 4x PASS (`initialize`, `tools/list`, `battery_read`, `device_info`).

Completion criterion: smoke reports 4x PASS; otherwise stop and fix transport before calling domain tools.

Prefer the `phone-mcp` CLI over raw curl. Raw clients must send `MCP-Protocol-Version: 2025-06-18` plus `Accept: application/json, text/event-stream`, offer ALPN `http/1.1` (Netty drops ALPN-less handshakes), and use Bearer auth. See `references/core-transport.md`.

### 2. Respect guardrails

Check kill-switches before calling gated tools: `audio_*`, `camera_*`, `net_*`, `ui_*`, and `ble_write` default OFF. Third-party `ext_*` tools need the dashboard External switch ON plus per-capability approval (`capabilities_approve`). Enable on the dashboard. Request runtime permissions from the foreground (camera, mic, location on API 34). Never log tokens; log tool name, `isError`, and RPC code only.

### 3. Handle results and media

Distinguish domain failures (`result.isError:true` naming the kill-switch or permission) from protocol errors (`-32601` unknown, `-32602` bad args, `-32603` busy or internal, `-32001` auth, `-32600` transport gate). Download blobs via authenticated `GET /media/{id}`; never expect inline base64. Blobs expire after 15 min and cap at 100 MB; Range requests are supported.

### 4. Route to a domain skill

- Environmental or position state: `phone-mcp-sense` (`sensors_read`, `sensors_stream`, `location_read`, `battery_read`, `device_info`).
- Photos, clips, sound, haptics, clipboard: `phone-mcp-capture`.
- Bluetooth LE or NFC taps: `phone-mcp-wireless`.
- Screen observe and operate: `phone-mcp-ui`.
- VPN capture, firewall, connection tables: `phone-mcp-net`.
- Third-party app tools (`ext_*`, consent, live list changes): this skill only — see `references/external-apps.md` before calling them.

Completion criterion: every phone tool call is covered by exactly one domain skill; this skill never issues domain calls itself.

## Timeouts

Keep harness timeout at 60000 ms. Bridge ceiling is 35000 ms (30 s captures); fast calls fail at 15000 ms; duration-bearing calls add 10000 ms margin. Wake the device first; deep sleep stretches waits ~1.5x.

## Resources

- `references/core-transport.md`: endpoints, TLS modes, curl shape, troubleshooting table.
- `references/external-apps.md`: third-party capability discovery, two-gate calling convention, SSE push, trust notes.
