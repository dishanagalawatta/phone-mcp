# ADR-0007: Dashboard safety model (extends ADR-0006)

## Status
Accepted (extends ADR-0006, which is unchanged)

## Context
ADR-0006 shipped open-auth LAN with mic/camera kill-switches default OFF. The dashboard and remaining
sensitive tools (location) needed an explicit in-app safety contract.

## Decision
- In-app kill-switches for **microphone, camera, and location**, all default OFF per session.
  Enabling is an explicit per-session toggle; service restart resets to OFF.
- Dashboard shows server URL(s), live tool-call log (method + tool name only, never argument values),
  and runtime permission states with grant buttons.
- `McpServerService` exposes START/STOP actions and a same-process run flag; STOP tears down Ktor.

## Consequences
Location joins the default-off set (new vs ADR-0006). Agents must ask the user to flip toggles first;
`tools/call` on a disabled tool returns `isError:true` naming the kill-switch.
