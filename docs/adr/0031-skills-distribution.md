# ADR-0031: Ship agent skills with harness configuration

- **Status:** Accepted
- **Date:** 2026-10-15
- **Context:** ADR-0030 created 6 agent skills (41/41 tools), but `phone-mcp
  configure` rendered only harness *connection* files — agents that picked up
  the configs got tools with no playbooks (reported: another agent's harness
  setup missed the skills entirely). The installed wheel cannot read the repo
  checkout, so repo-relative lookup fails for `pip install ./client` users.
- **Decision:** Bundle byte-identical skill copies inside both client twins
  (`phone_mcp_client/skills/`, `client/node/skills/`) and make `configure`
  stage them next to the rendered configs (`<out>/skills/`, `--skills-dir`
  override, `--no-skills` opt-out) plus sync `<cwd>/.agents/skills/` on
  `--install` (project-scoped; global when `--cwd ~`). Single install target:
  `.agents/skills/` is the agent-compatible discovery dir every major harness
  reads (OpenCode, Claude, Cline, Windsurf, Cursor, Gemini), so one location
  covers all 9 harnesses with no per-harness skills dirs. Staged copies are
  static markdown: the stage aborts if the live token/pin (≥8 chars, avoiding
  short-word false positives) is found in any file. Source of truth stays
  `.agents/skills/`; `scripts/verify-skills.py` (+ twin unit tests) enforce
  byte-identity, and `scripts/configure-harness.sh` stages the same way.
- **Alternatives considered:** (a) Repo-relative lookup at configure time —
  rejected: breaks for installed wheels run outside a checkout (the primary
  `pip install` path). (b) Hint text only (`npx skills add …`) — rejected:
  hints don't fix missed skills; the registry path also needs a repo URL +
  LICENSE that are still TBD. (c) Per-harness native skills dirs
  (`.claude/skills`, `.opencode/skills`, …) — rejected: 9 destinations to
  maintain for one discovery mechanism that already covers them all.
- **Consequences:** + Every `configure` output (CLI twins + bash fallback)
  carries configs *and* skills; agents can't miss them. + Wheel grows by 12
  markdown files (verified in built artifact). − Skill edits now require
  re-copying into both bundles (mechanical `cp -r`, gate-enforced); noted in
  `AGENTS.md` so future agents don't drift.
