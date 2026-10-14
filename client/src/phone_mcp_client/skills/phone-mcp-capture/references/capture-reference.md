# Capture reference

## Arg clamps

| Tool | Args and result |
|---|---|
| `camera_capture` | `lens: back\|front` default back. Returns `{mediaUrl, mimeType:image/jpeg, lens, sizeBytes}`. |
| `camera_video` | `lens` default back; `durationMs` 2000..30000 default 5000; `withAudio` default true. Returns `{mediaUrl, mimeType:video/mp4}`. |
| `audio_record` | `durationMs` 1000..30000 default 5000. AAC `.m4a` via `/media` with `{mediaUrl, mimeType, durationMs, sizeBytes}`. |
| `audio_stream` | `durationMs` 2000..60000 default 10000; `chunkMs` 250..5000 default 1000. Batches: `{seq, sampleRateHz:16000, channels:1, encoding:pcm_s16le, chunkMs, rms:0..1, dataBase64}` (~32 KB per chunk). |
| `speaker_say` | `text` required max 1000; `rate`/`pitch` 0.5..2.0 default 1.0. Returns `{spokeChars, output, musicVolume, musicMax}`. |
| `vibrate` | `durationMs` 1..5000 default 200. Returns `vibrated Nms`. |
| `torch` | `on` required boolean. Needs `CAMERA` runtime. |
| `clipboard_set` | `text` required. Works headless. |
| `clipboard_get` | No args. Foreground-only on API 29+; null or empty headless. |

## Media download

```bash
phone-mcp media <media-id> --out ./photo.jpg
curl -sk -H "Authorization: Bearer $TOKEN" https://<ip>:8080/media/<id> -o ./clip.m4a
```

Check `expiresAt`; re-capture after expiry. Use `Range: bytes=0-99` for partial fetches (206).

## Notes

- `camera_*` and `audio_*` kill-switches default OFF; foreground grants required on API 34 with the FGS notification visible.
- `speaker_say.output` values: `speaker`, `wired`, `bt_a2dp`, `remote_submix(mirrored)`. The last means a mirror (scrcpy) is capturing audio; re-run with `--no-audio`.
- `audio_stream` and `camera_video` count against the 35000 ms bridge ceiling; keep the harness timeout at 60000 ms.
- Blank `text` for `speaker_say` or `clipboard_set` returns `-32602`.
