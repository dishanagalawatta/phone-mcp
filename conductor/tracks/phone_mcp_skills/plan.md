# Track: phone_mcp_skills — Agent skills for Phone MCP

Status: complete. 6 skills in `.agents/skills/` (router + 5 domain), 41/41 tools, ADR-0030.

## Spec

Agents need procedural skills to fully use phone-mcp: pairing/TLS/media transport, kill-switches, foreground grants, ALPN, single-stream limits, per-domain arg clamps and observe-act-verify loops. No ecosystem skill covers this (verified `npx skills find`). Follow the Agent Skills open standard and OpenCode `.agents/skills/` discovery.

## Plan

- [x] Map 41 tools from `McpRouter` + `data/*Provider` + `docs/api.md`
- [x] Verify skill spec + ecosystem via web research and `npx skills find`
- [x] Create 6 `SKILL.md` (router + sense/capture/wireless/ui/net) with `references/`
- [x] Add `scripts/verify-skills.py` gate (75 checks) and run it
- [x] Harden gate to 132 checks (markdown-only, injection patterns, no curl|sh, token/redaction discipline)
- [x] Add `scripts/eval-skills.py` (19 static checks: focus, completion criteria, disclosure, single-source + 6-task live battery, skips without transport)
- [x] Run bridge/client suites for regression (26 + 79 + 71)
- [x] Write ADR-0030 + index row
- [x] Sync README / llms.txt / api docs pointer
- [ ] Publish to skills.sh index (BLOCKED: needs real repo URL + LICENSE; index builds from `npx skills add <owner>/<repo>` install telemetry, no submission form — same blockers as PyPI publish in `docs/client.md`)
- [x] Live-proof eval battery vs phone (smoke 4/4, battery 4/6 PASS + 2 honest kill-switch denials; server started via foreground-dashboard + tile sequence, documented in `docs/harness.md`)
- [x] Full 6/6 live PASS after owner enabled ui_*/net_* kill-switches (net_status live with VPN flows; ui_dump 46 nodes after binding accessibility service via adb settings at owner's explicit request)
