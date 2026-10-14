# ADR-0022: Workspace Standardization (Cleanup, Env, Docs)

- Status: Accepted
- Date: 2026-10-14
- Supersedes: n/a (hardens ADR-0007/0009/0010/0020/0021 docs posture)
- Context: Workspace accumulated ~450 MB regenerable output (`app/build/`, `build/`, `.gradle/`, `.kotlin/`, `scripts/__pycache__/`), missing `.gitignore`/`DESIGN.md`/`environments`/API reference, misplaced screenshots in `app/build/`, machine-specific `local.properties` at risk of commit. No `TODO.md` present; `.agents/commands` + `.agents/skills` excluded from all modifications.

## Decisions

1. Pristine rule: purge `scripts/__pycache__/`, `build/`, `app/build/`, `.gradle/`, `.kotlin/` (2.8 MB post-cleanup; device proofs regenerable via `collect-proofs.sh` + `verify-mcp.sh`, backed up pre-purge). Relocate stray `app/build/*.png` to `docs/assets/screenshots/`.
2. Add `.gitignore` (Gradle/Android, `local.properties`, `.env`, `__pycache__/`, proofs output) + `.env.example` + `docs/environments.md` (port/host, token, BLE peer, keystore env, SDK override). Release signing stays env-only, never committed.
3. Standardize docs: `README.md` (TOC + env pointer + 41-tool count), `DESIGN.md` (M3 semantic system from `branding.md` + redesign spec), `docs/api.md` (41 tools from `McpRouter`/`*Provider`/`NetInspectCatalog`), `docs/adr/README.md` index, `CHANGELOG.md` + `llms.txt` + `docs/README.md` index.
4. Trim `AGENTS.md` to <60 lines (agents-md: headers+bullets, file-scoped commands, commit attribution) preserving layer discipline, media/perm/security, memory rules.
5. Conductor: archive completed `modern_ui_redesign` to `_archive/`, stage `workspace_standardization` track with TDD Standard gates intact.

## Consequences

- Stricter: build outputs and secrets can no longer be committed by default; `local.properties`/`sdk.dir` stays local.
- Compatible: no tool renames, no version bump, no UX change; all layer/security rules from ADR-0021 preserved.
- Verify: `./scripts/setup.sh`, `./gradlew testDebugUnitTest` + `lintDebug`, `TOKEN=... ./scripts/verify-mcp.sh <ip>:8080` + Inspector before merge; R8 device-matrix on release.
