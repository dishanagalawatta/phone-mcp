# Implementation Plan: Workspace Standardization

## Phase 1: Cleanup Operations
- [x] Task 1.1: Purge `scripts/__pycache__/`, `build/`, `app/build/`, `.gradle/`, `.kotlin/`; relocate `app/build/*.png` → `docs/assets/screenshots/`.
- [x] Task 1.2: Verify exclusions untouched (`TODO.md` absent, `.agents/commands`, `.agents/skills` read-only).

## Phase 2: Config + Docs Updates
- [x] Task 2.1: Create `.gitignore`, `.env.example`, `docs/environments.md`.
- [x] Task 2.2: Trim `AGENTS.md` (<60 lines), create `DESIGN.md`, update `README.md`.
- [x] Task 2.3: Add ADR-0022 + index row, create `docs/api.md` (41 tools), `CHANGELOG.md`, `llms.txt`, `docs/README.md`.
- [x] Task 2.4: Archive `modern_ui_redesign` → `_archive/`; stage this track; verify gates (`setup.sh`, `testDebugUnitTest`, `lintDebug`, `verify-mcp.sh`).
