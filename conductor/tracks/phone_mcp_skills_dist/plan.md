# Track: phone_mcp_skills_dist — Ship skills with harness configuration

Status: complete. `configure` (both twins + bash fallback) stages/installs the 6 skills; ADR-0031.

## Spec

Another agent's harness setup missed the skills: `configure` rendered connection files only. Fix at the distribution point so configs and playbooks always travel together.

## Plan

- [x] Bundle byte-identical skill copies in both twins (12 md files each)
- [x] Python `skills` subpackage + `--no-skills`/`--skills-dir` + install-sync to `<cwd>/.agents/skills` + live-secret refusal
- [x] Node `skills.mjs` mirror + same flags/behavior
- [x] Tests: `test_skills.py` (10) + `test-skills.mjs` (8) incl. byte-sync + secret guards; fix `test-cli.mjs` out-dir count 9→10
- [x] `verify-skills.py` bundle-sync checks (132→156); fix short-token false positive (≥8 chars) in both twins
- [x] `configure-harness.sh` stages the same way (+ flags + token guard)
- [x] Wheel build proves bundle ships (13 skills files incl. `__init__.py`)
- [x] Docs: ADR-0031 + index, CHANGELOG, client.md, harness.md, README, AGENTS.md sync rule, docs index
