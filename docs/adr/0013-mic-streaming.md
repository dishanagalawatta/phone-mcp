# ADR-0013: Live mic PCM streaming (extends ADR-0008)

## Status
Accepted (extends ADR-0008's SSE framing; neither it nor ADR-0004 is modified)

## Context
Agents need live audio context (voice commands, ambient events) that `audio_record`
clips cannot deliver without hot-looping. The MCP-conformant SSE transport from ADR-0008
generalizes cleanly beyond sensors.

## Decision
- New tool `audio_stream` served only as SSE (`Accept: text/event-stream` required).
  Batches: `{seq, sampleRateHz: 16000, channels: 1, encoding: pcm_s16le, chunkMs,
  rms: 0..1, dataBase64}`. Defaults 10 s total / 1 s chunks (clamped 2–60 s / 250 ms–5 s).
- Capture via `AudioRecord` (raw PCM; `MediaRecorder` is file-oriented) read in ~100 ms
  slices so cancellation/deadline checks stay responsive; `release()` in `finally`.
- Gated by the existing `audio_` kill-switch like `audio_record` (no new toggle).
- Router generalized: any `supportsStreaming` provider rides `handleStream`; the SSE
  notification method comes from the provider (`notifications/audio` here,
  `notifications/sensors` preserved).

## Consequences
Positive: ~43 KB/s per 1 s chunk on LAN, VU-meter `rms` included, reuses all SSE
machinery (busy guard, timeouts, cancel semantics).
Negative: continuous mic = battery drain; document short windows. Decode cost sits
with the agent (base64 PCM, no compression negotiated in v1).
