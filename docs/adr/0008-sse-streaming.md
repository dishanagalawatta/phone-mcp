# ADR-0008: MCP-conformant SSE streaming (extends ADR-0001/0004)

## Status
Accepted (extends ADR-0001 stateless JSON and ADR-0004 on-demand tools; neither is modified)

## Context
Agents need live motion/location context (gestures, movement) that polling cannot deliver
without hot-looping `sensors_read`. The 2025+ MCP spec supports per-request SSE streams:
the server answers a POST with `Content-Type: text/event-stream` carrying request-scoped
notifications followed by the final response.

## Decision
- New tool `sensors_stream` served **only** as an SSE response on `POST /mcp`
  (`Accept: text/event-stream` required, else JSON `-32602` directing the client to SSE).
- Framing: `data:` lines with JSON-RPC `notifications/sensors` envelopes per batch,
  final `data:` line with the standard result envelope (`{tool, batches}`), then close.
- Payload per batch: `{t, sensors: {name: [...]}, location?: {lat, lon, acc}, warnings?[]}`.
- Caps: default 200 ms batch / 10 s total, clamped 100–2000 ms / 1–60 s; router backstop 75 s.
- **One stream at a time** (second caller gets `-32603`); client disconnect cancels
  collection and releases sensors/location in `finally`.
- Location gated by the ADR-0007 kill-switch: `includeLocation` defaults true and is
  rejected when Location is off; `includeLocation:false` bypasses.

## Consequences
Positive: Inspector-compatible live data, no new endpoint, same auth/Origin gates.
Negative: one-stream limit (documented; lift with per-client sessions later);
SSE failures surface as error finals inside the stream.
