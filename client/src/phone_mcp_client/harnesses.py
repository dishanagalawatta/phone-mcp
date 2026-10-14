"""Per-harness config rendering + install paths.

Every template keeps the Bearer token out of the file: harness configs
carry env-var / prompt references only. The literal LAN URL is baked in
(it is not a secret); rotating the token or fingerprint never requires a
config rewrite — just re-export the env vars.

Verified against (Oct 2026):
- OpenCode ``{"mcp": {name: {"type": "local", "command": [...], "environment": ...}}}``
- Claude Code/Desktop/Cline/Windsurf ``{"mcpServers": {name: {"command", "args", "env"}}}``
- Codex ``~/.codex/config.toml`` ``[mcp_servers.<id>]`` with ``url`` /
  ``bearer_token_env_var`` (remote) or ``command``/``args``/``env`` (stdio)
- VS Code ``.vscode/mcp.json`` ``{"servers": ..., "inputs": ...}``
- Gemini CLI ``~/.gemini/settings.json`` ``mcpServers`` (+ ``timeout``; direct
  entries use ``httpUrl``, stdio entries need no such distinction)
"""

from __future__ import annotations

import json
import os

PHONE_MCP_SERVER_NAME = "phone-mcp"

# ---------------------------------------------------------------------------
# Bridge command selection
# ---------------------------------------------------------------------------

_BRIDGE_COMMANDS = {
    # Installed CLI (preferred after `pip install ./client`): no repo path needed.
    "phone-mcp": (["phone-mcp", "serve"], "phone-mcp"),
    # Zero-dependency repo twins (no install needed; run from the repo root).
    "node": (["node", "bridges/phone-mcp-bridge.mjs"], "node"),
    "python": (["python3", "bridges/phone-mcp-bridge.py"], "python"),
}


def bridge_command(kind: str = "phone-mcp") -> list[str]:
    try:
        return list(_BRIDGE_COMMANDS[kind][0])
    except KeyError:
        raise ValueError(f"--bridge must be one of {sorted(_BRIDGE_COMMANDS)}") from None


def _env_refs(url: str, style: str) -> dict:
    """Env mapping for one harness variable style (URL literal, secrets by ref)."""
    if style == "opencode":
        return {
            "PHONE_MCP_URL": "{env:PHONE_MCP_URL}",
            "PHONE_MCP_TOKEN": "{env:PHONE_MCP_TOKEN}",
            "PHONE_MCP_SHA256": "{env:PHONE_MCP_SHA256}",
        }
    if style == "gemini":
        return {
            "PHONE_MCP_URL": url,
            "PHONE_MCP_TOKEN": "$PHONE_MCP_TOKEN",
            "PHONE_MCP_SHA256": "$PHONE_MCP_SHA256",
        }
    # claude / cursor / generic / cline / windsurf: ${VAR} refs.
    return {
        "PHONE_MCP_URL": url,
        "PHONE_MCP_TOKEN": "${PHONE_MCP_TOKEN}",
        "PHONE_MCP_SHA256": "${PHONE_MCP_SHA256}",
    }


# ---------------------------------------------------------------------------
# Renderers — each returns (filename, text_content)
# ---------------------------------------------------------------------------

def render_opencode(url: str, bridge: str = "phone-mcp") -> tuple[str, str]:
    cmd = bridge_command(bridge)
    cfg = {
        "mcp": {
            PHONE_MCP_SERVER_NAME: {
                "type": "local",
                "command": cmd,
                "enabled": True,
                "environment": _env_refs(url, "opencode"),
            }
        }
    }
    # Keep the literal URL discoverable in a comment-free JSON file via a
    # sibling key? No — tokens stay env-only; URL override lives in env.
    # The smoke/doctor output reminds the user to export PHONE_MCP_URL.
    return "opencode.phone-mcp.json", json.dumps(cfg, indent=2) + "\n"


def render_claude(url: str, bridge: str = "phone-mcp") -> tuple[str, str]:
    cmd = bridge_command(bridge)
    cfg = {
        "mcpServers": {
            PHONE_MCP_SERVER_NAME: {
                "command": cmd[0],
                "args": cmd[1:],
                "env": _env_refs(url, "claude"),
            }
        }
    }
    return "claude.phone-mcp.json", json.dumps(cfg, indent=2) + "\n"


def render_codex(url: str, bridge: str = "phone-mcp") -> tuple[str, str]:
    """TOML with the stdio bridge (recommended LAN path) + remote alternative."""
    cmd = bridge_command(bridge)
    # TOML string escaping for the URL (no backslashes/quotes in practice).
    quoted = url.replace("\\", "\\\\").replace('"', '\\"')
    args_toml = ", ".join(f'"{a}"' for a in cmd[1:])
    lines = [
        "# Phone MCP — stdio bridge (handles self-signed TOFU; preferred on LAN).",
        "# Install: append to ~/.codex/config.toml, then `codex mcp list`.",
        "# Token/fingerprint via env — never baked in.",
        f"[mcp_servers.{PHONE_MCP_SERVER_NAME}]",
        f'command = "{cmd[0]}"',
        f"args = [{args_toml}]",
        f'url = "{quoted}"  # informational only; stdio entries ignore url',
        "",
        "[mcp_servers.phone-mcp.env]",
        f'PHONE_MCP_URL = "{quoted}"',
        'PHONE_MCP_TOKEN = "${PHONE_MCP_TOKEN}"',
        'PHONE_MCP_SHA256 = "${PHONE_MCP_SHA256}"',
        "",
        "# Remote Streamable-HTTP alternative (stock TLS-verify rejects the",
        "# phone self-signed cert — pin it first via `phone-mcp export-ca`,",
        "# then SSL_CERT_FILE=./phone-mcp-ca.pem):",
        "# [mcp_servers.phone-mcp-remote]",
        f'# url = "{quoted}"',
        '# bearer_token_env_var = "PHONE_MCP_TOKEN"',
        "",
    ]
    # NOTE: Codex ignores unknown keys like `url` on stdio entries; the line
    # above is informational. The remote block is commented out by default.
    return "codex.phone-mcp.toml", "\n".join(lines)


def render_vscode(url: str, bridge: str = "phone-mcp") -> tuple[str, str]:
    cmd = bridge_command(bridge)
    if cmd[0] == "phone-mcp":
        command, args = "phone-mcp", ["serve"]
    else:
        command, args = cmd[0], ["${workspaceFolder}/" + cmd[1]]
    cfg = {
        "servers": {
            PHONE_MCP_SERVER_NAME: {
                "type": "stdio",
                "command": command,
                "args": args,
                "env": {
                    "PHONE_MCP_URL": url,
                    "PHONE_MCP_TOKEN": "${input:phoneMcpToken}",
                    "PHONE_MCP_SHA256": "${input:phoneMcpSha256}",
                },
            }
        },
        "inputs": [
            {
                "id": "phoneMcpToken",
                "type": "promptString",
                "description": "Phone MCP bearer token (dashboard pairing card)",
                "password": True,
            },
            {
                "id": "phoneMcpSha256",
                "type": "promptString",
                "description": "Phone MCP TLS SHA-256 fingerprint (dashboard, colons ok)",
            },
        ],
    }
    return "vscode.mcp.json", json.dumps(cfg, indent=2) + "\n"


def render_cursor(url: str, bridge: str = "phone-mcp") -> tuple[str, str]:
    return _render_generic_mcp_servers(url, bridge, "cursor.phone-mcp.json")


def render_gemini(url: str, bridge: str = "phone-mcp") -> tuple[str, str]:
    cmd = bridge_command(bridge)
    cfg = {
        "mcpServers": {
            PHONE_MCP_SERVER_NAME: {
                "command": cmd[0],
                "args": cmd[1:],
                "env": _env_refs(url, "gemini"),
                "timeout": 35000,
                # Explicit no-auto-trust (safe default; mirrors
                # config/harness-examples/gemini-settings.json).
                "trust": False,
            }
        }
    }
    return "gemini.phone-mcp.json", json.dumps(cfg, indent=2) + "\n"


def render_cline(url: str, bridge: str = "phone-mcp") -> tuple[str, str]:
    return _render_generic_mcp_servers(url, bridge, "cline.phone-mcp.json")


def render_windsurf(url: str, bridge: str = "phone-mcp") -> tuple[str, str]:
    return _render_generic_mcp_servers(url, bridge, "windsurf.phone-mcp.json")


def render_generic(url: str, bridge: str = "phone-mcp") -> tuple[str, str]:
    """Generic mcpServers host with both Node + Python twins documented."""
    node = _BRIDGE_COMMANDS["node"][0]
    py = _BRIDGE_COMMANDS["python"][0]
    cfg = {
        "mcpServers": {
            PHONE_MCP_SERVER_NAME: {
                "command": node[0],
                "args": node[1:],
                "env": _env_refs(url, "generic"),
            },
            "phone-mcp-python": {
                "command": py[0],
                "args": py[1:],
                "env": _env_refs(url, "generic"),
            },
        }
    }
    if bridge == "phone-mcp":
        cli = _BRIDGE_COMMANDS["phone-mcp"][0]
        cfg["mcpServers"][PHONE_MCP_SERVER_NAME] = {
            "command": cli[0],
            "args": cli[1:],
            "env": _env_refs(url, "generic"),
        }
    return "generic.phone-mcp.json", json.dumps(cfg, indent=2) + "\n"


def _render_generic_mcp_servers(url: str, bridge: str, filename: str) -> tuple[str, str]:
    cmd = bridge_command(bridge)
    cfg = {
        "mcpServers": {
            PHONE_MCP_SERVER_NAME: {
                "command": cmd[0],
                "args": cmd[1:],
                "env": _env_refs(url, "generic"),
            }
        }
    }
    return filename, json.dumps(cfg, indent=2) + "\n"


RENDERERS = {
    "opencode": render_opencode,
    "claude": render_claude,
    "codex": render_codex,
    "vscode": render_vscode,
    "cursor": render_cursor,
    "gemini": render_gemini,
    "cline": render_cline,
    "windsurf": render_windsurf,
    "generic": render_generic,
}

HARNESS_IDS = sorted(RENDERERS)

# ---------------------------------------------------------------------------
# Install destinations
# ---------------------------------------------------------------------------


def default_install_path(harness: str, cwd: str = ".") -> str:
    home = os.path.expanduser("~")
    mapping = {
        "opencode": os.path.join(home, ".config", "opencode", "opencode.json"),
        "claude": os.path.join(cwd, ".mcp.json"),
        "codex": os.path.join(home, ".codex", "config.toml"),
        "vscode": os.path.join(cwd, ".vscode", "mcp.json"),
        "cursor": os.path.join(home, ".cursor", "mcp.json"),
        "gemini": os.path.join(home, ".gemini", "settings.json"),
        "cline": os.path.join(
            home, ".config", "Code", "User", "globalStorage",
            "saoudrizwan.claude-dev", "settings", "cline_mcp_settings.json",
        ),
        "windsurf": os.path.join(home, ".codeium", "windsurf", "mcp_config.json"),
        "generic": os.path.join(cwd, "phone-mcp.generic.json"),
    }
    return mapping[harness]


def cli_add_hint(harness: str, url: str, bridge: str = "phone-mcp") -> str:
    cmd = bridge_command(bridge)
    if harness == "claude":
        return f"claude mcp add {PHONE_MCP_SERVER_NAME} -- {' '.join(cmd)}"
    if harness == "codex":
        envs = f"--env PHONE_MCP_URL={url} --env PHONE_MCP_TOKEN=$PHONE_MCP_TOKEN --env PHONE_MCP_SHA256=$PHONE_MCP_SHA256"
        return (
            f"codex mcp add {PHONE_MCP_SERVER_NAME} --command {cmd[0]} "
            f"--args {' '.join(cmd[1:])} {envs}"
        )
    if harness == "gemini":
        return (
            f"# merge {default_install_path('gemini')} mcpServers, then `/mcp list` "
            "(Gemini uses `httpUrl`, not `url`, for direct entries — stdio needs neither)"
        )
    if harness == "opencode":
        return "# restart OpenCode after editing (servers connect at startup); verify with /mcp"
    return ""


INSPECTOR_GUIDE = (
    "MCP Inspector (manual probing): Streamable HTTP → URL + "
    "Authorization: Bearer $PHONE_MCP_TOKEN; accept the self-signed cert only "
    "AFTER verifying its SHA-256 against the dashboard (TOFU)."
)


# ---------------------------------------------------------------------------
# Install helpers (merge without clobbering)
# ---------------------------------------------------------------------------

def merge_json_file(dest: str, key: str, addition: dict) -> str:
    """Merge ``addition`` under top-level ``key`` in a JSON file. Returns dest."""
    base: dict = {}
    if os.path.exists(dest):
        with open(dest, encoding="utf-8") as fh:
            try:
                base = json.load(fh) or {}
            except json.JSONDecodeError as exc:
                raise RuntimeError(f"{dest} is not valid JSON: {exc}") from exc
    if not isinstance(base, dict):
        raise RuntimeError(f"{dest}: top-level JSON must be an object")
    section = base.get(key)
    if not isinstance(section, dict):
        section = {}
        base[key] = section
    section.update(addition)
    parent = os.path.dirname(dest)
    if parent:
        os.makedirs(parent, exist_ok=True)
    tmp = dest + ".tmp"
    with open(tmp, "w", encoding="utf-8") as fh:
        json.dump(base, fh, indent=2)
        fh.write("\n")
    os.replace(tmp, dest)
    return dest


def append_codex_toml(dest: str, block: str) -> str:
    """Idempotently append the phone-mcp TOML block (skips if name present)."""
    parent = os.path.dirname(dest)
    if parent:
        os.makedirs(parent, exist_ok=True)
    existing = ""
    if os.path.exists(dest):
        with open(dest, encoding="utf-8") as fh:
            existing = fh.read()
    if f"[mcp_servers.{PHONE_MCP_SERVER_NAME}]" in existing:
        return dest  # already installed — leave user edits alone
    with open(dest, "a", encoding="utf-8") as fh:
        if existing and not existing.endswith("\n"):
            fh.write("\n")
        fh.write("\n" + block)
        if not block.endswith("\n"):
            fh.write("\n")
    return dest


def install_rendered(harness: str, content: str, dest: str) -> str:
    """Install one rendered config file to its harness destination."""
    if harness == "opencode":
        addition = json.loads(content)["mcp"]
        return merge_json_file(dest, "mcp", addition)
    if harness in ("claude", "cursor", "gemini", "cline", "windsurf"):
        addition = json.loads(content)["mcpServers"]
        return merge_json_file(dest, "mcpServers", addition)
    if harness == "generic":
        # Generic has no canonical home — write the file as-is.
        parent = os.path.dirname(dest)
        if parent:
            os.makedirs(parent, exist_ok=True)
        with open(dest, "w", encoding="utf-8") as fh:
            fh.write(content)
        return dest
    if harness == "vscode":
        obj = json.loads(content)
        base: dict = {}
        if os.path.exists(dest):
            with open(dest, encoding="utf-8") as fh:
                base = json.load(fh) or {}
        servers = base.get("servers", {})
        servers.update(obj.get("servers", {}))
        base["servers"] = servers
        # Merge inputs by id (no duplicates).
        seen = {i.get("id") for i in base.get("inputs", []) if isinstance(i, dict)}
        base_inputs = list(base.get("inputs", []))
        for item in obj.get("inputs", []):
            if item.get("id") not in seen:
                base_inputs.append(item)
        base["inputs"] = base_inputs
        parent = os.path.dirname(dest)
        if parent:
            os.makedirs(parent, exist_ok=True)
        with open(dest, "w", encoding="utf-8") as fh:
            json.dump(base, fh, indent=2)
            fh.write("\n")
        return dest
    if harness == "codex":
        return append_codex_toml(dest, content)
    raise ValueError(f"unknown harness {harness!r}")


def render_all(url: str, bridge: str = "phone-mcp") -> dict[str, tuple[str, str]]:
    """Render every harness. Returns {harness_id: (filename, content)}."""
    return {hid: RENDERERS[hid](url, bridge) for hid in HARNESS_IDS}
