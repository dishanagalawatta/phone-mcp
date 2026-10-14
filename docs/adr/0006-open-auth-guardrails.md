# ADR-0006: Open LAN auth with guardrails

## Status
Accepted (risk acknowledged per user direction)

## Context
User chose open/no-auth + exhaustive scope for v1 velocity.

## Decision
Ship open but: LAN-only bind, Origin header validation (DNS-rebinding), per-category kill-switches (mic/camera default OFF), foreground notification + capture indicators, redacted tool-call log.

## Consequences
Any LAN host can drive *enabled* tools — documented warning on dashboard + README. Upgrade path: Bearer token (random at first launch, QR pairing) without protocol change.
