# UI reference

## Observe-act-verify loop

```text
ui_dump -> ui_screenshot -> ui_click|swipe|type|key -> ui_dump (+ screenshot when layout matters)
```

Never act from a stale dump. Re-dump after every action.

## Selector rules

- `ui_click` takes exactly one of `x/y`, `text`, `resourceId`. Coordinates clamp to 0..10000. Text is a visible-substring match.
- `ui_type` prefers `resourceId`, then `selectorText`, then the focused field. Target must report editable; text truncates at 1000 chars.
- `ui_swipe` needs `x1,y1,x2,y2` plus `durationMs` 50..2000 default 300.
- `ui_key` takes `back|home|recents`.
- `ui_dump` takes `includeSensitive` default false. Passwords redact unless true; request true only when the task needs the value.

## Screenshot

`ui_screenshot` needs API 30+ and the `canTakeScreenshot` path; 10 s timeout. Result is `{mediaUrl, mimeType:image/png, width, height, sizeBytes}`. Nodes recycle after capture.

## Guards and privacy

- Secure-screen toggles ask first (`confirm_dialog_ok` node confirms, cancel leaves state). This is by design.
- Type and read text never enters the tool-call log; log tool names and node identifiers only.
- No active window means the device is asleep; wake it and retry.
- Graceful misses (`No element found`, `Element refused`) need a fresh dump, not a blind retry.
