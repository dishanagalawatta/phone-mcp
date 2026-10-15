# Docs Index

| Doc | Purpose |
|-----|---------|
| [api.md](api.md) | MCP API reference (47 static tools + dynamic `ext_*`, transports, SSE, errors) |
| [client.md](client.md) | Unified `phone-mcp` client guide (pair/smoke/configure, 9-harness matrix) |
| [harness.md](harness.md) | Harness setup internals (bridge protocol, TLS modes, per-harness gotchas) |
| [environments.md](environments.md) | Local env setup (`.env.example`, signing, verify) |
| [adr/](adr/README.md) | ADR-0001..0035 + standardization record |
| [external-capabilities.md](external-capabilities.md) | Third-party capability integration guide (provider checklist, protocol, approval, testing, troubleshooting) |
| [../sample-provider/README.md](../sample-provider/README.md) | Zero-dep reference provider app (`demo: echo/add/time`) |
| [.agents/skills/phone-mcp/](../.agents/skills/phone-mcp/SKILL.md) | Agent skills router (pairing, transport, guardrails) + 5 domain skills (sense/capture/wireless/ui/net), 47/47 tools; gates: `scripts/verify-skills.py`, `scripts/eval-skills.py`; distribution: `configure` stages/installs them (ADR-0031) |
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
