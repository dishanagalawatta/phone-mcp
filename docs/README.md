# Docs Index

| Doc | Purpose |
|-----|---------|
| [api.md](api.md) | MCP API reference (41 tools, transports, SSE, errors) |
| [client.md](client.md) | Unified `phone-mcp` client guide (pair/smoke/configure, 9-harness matrix) |
| [harness.md](harness.md) | Harness setup internals (bridge protocol, TLS modes, per-harness gotchas) |
| [environments.md](environments.md) | Local env setup (`.env.example`, signing, verify) |
| [adr/](adr/README.md) | ADR-0001..0030 + standardization record |
| [../.agents/skills/phone-mcp/](../.agents/skills/phone-mcp/SKILL.md) | Agent skills router (pairing, transport, guardrails) + 5 domain skills (sense/capture/wireless/ui/net), 41/41 tools; gates: `scripts/verify-skills.py`, `scripts/eval-skills.py` |
| [testing.md](testing.md) | Feature × coverage matrix (JVM/device/fuzz) |
| [device-matrix.md](device-matrix.md) | Human-run checklist per API level |
| [ci.md](ci.md) | CI (`testDebugUnitTest` + `lintDebug` + `harness` job, pins) |
| [branding.md](branding.md) | Brand, palette, icon architecture |
| [play-release.md](play-release.md) | Internal testing track |
| [privacy-policy.md](privacy-policy.md) | Hostable policy template |
| [assets/screenshots/](assets/screenshots/) | Dashboard captures (local-only, gitignored — never commit live IPs/fingerprints) |
| Root [`DESIGN.md`](../DESIGN.md) | Semantic design system |
| Root [`CHANGELOG.md`](../CHANGELOG.md) | Keep a Changelog |
| Root [`llms.txt`](../llms.txt) | AI-friendly entry |

Regenerate device proofs: `./scripts/collect-proofs.sh` + `./scripts/verify-mcp.sh <ip>:8080` (output gitignored).
