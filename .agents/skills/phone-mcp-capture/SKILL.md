---
name: phone-mcp-capture
description: Use when capturing phone photos or video, recording or streaming microphone audio, speaking text, vibrating, toggling torch, or reading and writing clipboard over Phone MCP.
---

# Phone MCP Capture

## Overview

Capture camera and microphone blobs as `/media` URLs and drive haptic, light, speech, and clipboard outputs.

## Preconditions

- Complete `phone-mcp` pairing and smoke first.
- Enable `camera_*` before any camera call and `audio_*` before any mic call (both default OFF).
- Grant camera and mic permissions from the foreground (API 34 while-in-use).

## Workflow

### 1. Capture stills and clips

Call `camera_capture` with `lens: back|front` (default `back`); result is `{mediaUrl, mimeType:image/jpeg}`. Call `camera_video` with `lens`, `durationMs` 2000..30000 (default 5000), `withAudio` (default true, needs mic grant); result is `{mediaUrl, mimeType:video/mp4}`. Download via authenticated `GET /media/{id}` before the 15-min TTL.

Completion criterion: media URL downloads to a non-empty file with the expected MIME.

### 2. Record or stream audio

Call `audio_record` with `durationMs` 1000..30000 (default 5000); result is AAC `.m4a` via `/media`. Call `audio_stream` only over SSE with `durationMs` 2000..60000 (default 10000) and `chunkMs` 250..5000 (default 1000); each batch carries base64 PCM 16 kHz s16le plus `rms`. One stream at a time; a second fails with `-32603`.

Completion criterion: clip downloads and plays, or stream batches arrive with monotonically increasing `seq`.

### 3. Drive outputs

Call `speaker_say` with required `text` (max 1000 chars), `rate`/`pitch` 0.5..2.0; inspect `output` route and suspect `remote_submix(mirrored)` when sound exits the computer instead of the phone (mirror with `--no-audio`). Call `vibrate` with `durationMs` 1..5000 (default 200). Call `torch` with required boolean `on` (needs `CAMERA` runtime). Call `clipboard_set` with required `text` (works headless); call `clipboard_get` only while the app is focused (API 29+ returns null headless).

Completion criterion: each output returns its status text (`vibrated Nms`, `torch on|off`, `spokeChars`, clipboard ack) without `isError:true`.

## Error handling

Treat blank `text` or bad `lens` as `-32602`. Treat `SecurityException` text as a missing foreground grant. Never inline blobs as base64; always follow `mediaUrl`.

## Resources

- `references/capture-reference.md`: arg clamps, media shapes, mirror-audio and foreground notes.
