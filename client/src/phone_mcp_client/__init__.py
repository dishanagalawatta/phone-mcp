"""phone-mcp-client — unified MCP client for Phone MCP.

Stdlib-only. Connects any MCP harness (OpenCode, Claude Code/Desktop,
Codex CLI, VS Code, Cursor, Gemini CLI, Cline, Windsurf, Inspector) to the
phone's TLS-only Streamable HTTP endpoint with Bearer auth + SHA-256 TOFU
pinning.

Entry points:
  - CLI: ``phone-mcp serve|smoke|pair|configure|list|call|media|export-ca``
  - Library: :class:`ClientConfig` + :mod:`protocol` + :mod:`harnesses`
"""

from __future__ import annotations

__version__ = "0.1.0"
__protocol_version__ = "2025-06-18"

from .config import ClientConfig  # noqa: E402,F401
