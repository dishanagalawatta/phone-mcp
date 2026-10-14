"""Bundled agent skills for Phone MCP.

The 6 skill trees shipped here are byte-identical copies of the source of
truth in the repo at ``.agents/skills/phone-mcp*/``. Bundling (instead of
reading the repo checkout) keeps ``phone-mcp configure`` working from an
installed wheel anywhere. Edit the source trees, re-copy, and let the
sync tests prove identity — never edit these copies by hand.

Layout: ``skills/<skill-id>/{SKILL.md, references/*.md}``.
"""

from __future__ import annotations

import os
import shutil

try:
    from importlib.resources import files as _res_files
except ImportError:  # pragma: no cover — Python 3.10+ always has this.
    _res_files = None  # type: ignore[assignment]

SKILL_IDS = (
    "phone-mcp",
    "phone-mcp-sense",
    "phone-mcp-capture",
    "phone-mcp-wireless",
    "phone-mcp-ui",
    "phone-mcp-net",
)

# Project skills dir (agent-compatible discovery: OpenCode, Claude Code /
# Desktop, Cline, Windsurf, Cursor, Gemini CLI all read `.agents/skills/`).
PROJECT_SKILLS_DIRNAME = os.path.join(".agents", "skills")

# Staged copies carry static markdown only — never live secrets. stage_skills()
# takes the caller's live secrets (pairing token, TLS pin) and refuses to
# write when any staged file contains one. (Static words like "Bearer" in
# curl examples are fine — only live secret *values* are forbidden.)


def source_dir() -> str:
    """Absolute path of the bundled skills root."""
    if _res_files is not None:  # installed wheel or editable install
        root = _res_files(__package__ or "phone_mcp_client.skills")
        return os.fspath(root)
    here = os.path.dirname(os.path.abspath(__file__))
    return os.path.join(here, "skills")


def _iter_skill_files(skill_id: str) -> list[str]:
    root = os.path.join(source_dir(), skill_id)
    found = []
    for dirpath, _dirnames, filenames in os.walk(root):
        for name in sorted(filenames):
            found.append(os.path.join(dirpath, name))
    return sorted(found)


def _check_clean(paths: list[str], forbidden: tuple[str, ...]) -> None:
    # Substring match, but only for secret-shaped markers: single characters
    # or short words (e.g. test tokens like "t") occur in ordinary prose and
    # would false-positive on every file. Real pairing tokens are long.
    markers = [m for m in forbidden if m and len(m) >= 8]
    if not markers:
        return
    for path in paths:
        with open(path, encoding="utf-8") as fh:
            text = fh.read()
        for marker in markers:
            if marker in text:
                raise ValueError(f"refusing to stage {path}: contains a live secret")


def stage_skills(dest_dir: str, forbidden: tuple[str, ...] = ()) -> dict[str, int]:
    """Copy all skill trees verbatim into ``dest_dir``.

    Returns ``{skill_id: file_count}``. ``forbidden`` holds live secrets
    (e.g. the pairing token); any staged file containing one aborts the
    whole stage with ``ValueError`` before anything is reported complete.
    """
    staged: dict[str, int] = {}
    for skill_id in SKILL_IDS:
        src = os.path.join(source_dir(), skill_id)
        if not os.path.isdir(src):
            raise RuntimeError(f"bundled skill missing: {skill_id} (re-copy from .agents/skills/)")
        dst = os.path.join(dest_dir, skill_id)
        if os.path.isdir(dst):
            shutil.rmtree(dst)
        shutil.copytree(src, dst)
        files = _iter_skill_files(skill_id)
        # Re-scan the staged copies (not the source) for secrets.
        staged_paths = [os.path.join(dst, os.path.relpath(p, src)) for p in files]
        _check_clean(staged_paths, forbidden)
        staged[skill_id] = len(staged_paths)
    return staged


def project_skills_dir(cwd: str = ".") -> str:
    """Project skills dir for ``--install`` (global when cwd is $HOME)."""
    return os.path.join(cwd, PROJECT_SKILLS_DIRNAME)
