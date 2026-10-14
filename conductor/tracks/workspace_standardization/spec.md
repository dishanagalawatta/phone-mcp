# Track Specification: Workspace Standardization

## Goal
Bring repo to industrial standard: pristine workspace (no caches/build outputs), gitignored secrets, complete env/config/docs (`.gitignore`, `.env.example`, `docs/environments.md`, `DESIGN.md`, `docs/api.md`, ADR-0022, `CHANGELOG.md`, `llms.txt`), trimmed `AGENTS.md`, archived completed tracks.

## Functional Requirements
1. Cleanup: purge `__pycache__`, `build/`, `app/build/`, `.gradle/`, `.kotlin/`; relocate stray PNGs to `docs/assets/screenshots/`.
2. Config: `.gitignore` + `.env.example` + `docs/environments.md`; `local.properties`/`.env`/keystore never committed.
3. Docs: `README.md` (TOC + pointers), `DESIGN.md` (M3 system), `docs/api.md` (41 tools from code), ADR-0022 + index, `CHANGELOG.md` + `llms.txt` + `docs/README.md`.
4. Agents: `AGENTS.md` <60 lines, file-scoped commands, commit attribution, layer/security rules intact.
5. Conductor: archive `modern_ui_redesign`, stage this track, keep TDD Standard gates.

## Non-Functional Requirements & Constraints
- STRICTLY EXCLUDE `TODO.md`, `.agents/commands`, `.agents/skills` from modifications (read-only inventory only).
- Preserve layer discipline (`mcp/` pure Kotlin) and ADR-0021 security posture; no tool renames.
- Verify: `./scripts/setup.sh`, `testDebugUnitTest` + `lintDebug`, `verify-mcp.sh` + Inspector before merge.
