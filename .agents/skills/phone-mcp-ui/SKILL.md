---
name: phone-mcp-ui
description: Use when screenshotting the phone screen, dumping the accessibility node tree, tapping, swiping, typing, or pressing system keys over Phone MCP.
---

# Phone MCP UI

## Overview

Observe the screen and operate it through the accessibility service. Always observe before acting, then verify.

## Preconditions

- Complete `phone-mcp` pairing and smoke first.
- Enable the AccessibilityService (Settings → Accessibility → Phone MCP) and the `ui_*` kill-switch (default OFF).
- Wake the device; `ui_dump` reports no active window on a sleeping device.

## Workflow

### 1. Observe

Call `ui_dump` (optional `includeSensitive`, default false; password fields stay redacted unless true) for the flat node list (`index, text, description, id, bounds, flags`). Call `ui_screenshot` for a PNG via `/media` (needs API 30+, 10 s timeout). Correlate dump bounds with the screenshot before choosing a target.

Completion criterion: dump returns nodes and screenshot downloads to a viewable PNG.

### 2. Act once

Call `ui_click` with exactly one of `x/y`, `text` substring, or `resourceId`; misses return errors without throwing. Call `ui_swipe` with required `x1,y1,x2,y2` plus `durationMs` 50..2000 (default 300). Call `ui_type` with required `text` (max 1000 chars) plus optional `selectorText` or `resourceId` (defaults to the focused field; target must be editable). Call `ui_key` with `back|home|recents`.

Completion criterion: one action returns success text (`clicked`, `swiped`, `typed N chars`, `pressed <key>`).

### 3. Verify and handle guards

Re-run `ui_dump` (and `ui_screenshot` when layout matters) after every action. Expect a Secure-screen confirm dialog on guarded toggles; confirm via the `confirm_dialog_ok` node or cancel to leave state unchanged. Never log typed text or screen contents beyond node identifiers; the service log records tool names only.

Completion criterion: post-action dump reflects the intended state change, or the guard decision is recorded.

## Error handling

Treat missing service as a setup error (enable accessibility). Treat `Element refused` or `No element found` as a selector issue; re-dump rather than retrying blindly. Treat screenshot timeouts as sleep or API-level issues.

## Resources

- `references/ui-reference.md`: selector rules, observe-act-verify loop, redaction and guard notes.
