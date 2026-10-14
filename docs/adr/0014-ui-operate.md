# ADR-0014: Operate-the-phone via AccessibilityService (new capability)

## Status
Accepted

## Context
Agents could sense and actuate hardware but not operate the phone (see screen, tap, type).
Doing so needs OS-privileged APIs: screen capture and UI introspection/action.

## Decision
- One `PhoneMcpAccessibilityService` (user-enables in Settings; `BIND_ACCESSIBILITY_SERVICE`,
  `canPerformGestures` + `canRetrieveWindowContent`) backs six `ui_*` tools, all under a
  new `ui_` kill-switch default OFF with a dashboard toggle: `ui_screenshot` (API 30+
  `takeScreenshot` — no MediaProjection consent dialog, graceful error on 29),
  `ui_dump` (flat indexed list with bounds/flags), `ui_click` (x/y, text, or resource-id —
  exactly one), `ui_swipe` (`dispatchGesture`), `ui_type` (`ACTION_SET_TEXT`), `ui_key`
  (back/home/recents).
- Privacy: password fields redacted from dumps by default (`includeSensitive` opt-in);
  typed text and read text never enter logs (only tool names). Manifest carries a
  plain-language description shown in Settings; Play Console needs the accessibility
  declaration + prominent disclosure (tracked in play-release).
- Timeouts everywhere (10 s screenshot/gesture, 15 s implicit); node walks capped
  (500 nodes / depth 12); media flows through the existing `/media` sidecar.

## Consequences
Positive: full observe/act loop with one permission and one toggle.
Negative: highest-risk capability in the app — Play review scrutiny, requires manual
enablement (no headless provisioning), taps act on whatever is foregrounded.
