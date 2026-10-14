#!/usr/bin/env python3
"""Paired eval for phone-mcp agent skills (static + live-optional).

Static (always runs, no phone needed): quality heuristics derived from
SkillsBench (Stanford/CMU/Berkeley/Oxford/BenchFlow, 2026) — focus over
monolith, branch clarity, checkable completion criteria, progressive
disclosure, single-source tool ownership.

Live (needs phone + `phone-mcp` CLI + PHONE_MCP_URL/TOKEN/SHA256 or a paired
store): 5 read-only/safe tasks, one per domain skill. Skipped gracefully
when transport is unavailable — static results still report.
"""
import json
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
SKILLS_DIR = REPO / ".agents" / "skills"
SKILL_IDS = [
    "phone-mcp",
    "phone-mcp-sense",
    "phone-mcp-capture",
    "phone-mcp-wireless",
    "phone-mcp-ui",
    "phone-mcp-net",
]

static_failures = []


def check(cond, msg):
    print(("PASS" if cond else "FAIL") + f": {msg}")
    if not cond:
        static_failures.append(msg)


print("== static eval (SkillsBench dimensions) ==")
bodies = {}
for sid in SKILL_IDS:
    md = SKILLS_DIR / sid / "SKILL.md"
    bodies[sid] = md.read_text() if md.exists() else ""
    words = len(bodies[sid].split())
    # Focus over monolith: SkillsBench monolithic single-doc skills -2.9pp.
    check(words <= 500, f"{sid} focused (SKILL.md {words} words <=500)")
    criteria = len(re.findall(r"[Cc]ompletion criterion", bodies[sid]))
    check(criteria >= 2, f"{sid} has >=2 completion criteria ({criteria})")
    ref_words = sum(len(f.read_text().split())
                    for f in (SKILLS_DIR / sid / "references").glob("*.md"))
    # Progressive disclosure: top stays legible (<=500 words, gated <=2000
    # in verify-skills.py) while substantive detail sits behind pointers.
    check(ref_words >= 100, f"{sid} discloses detail down (refs {ref_words} words >=100)")

# Single-source ownership: each tool owned by exactly one DOMAIN skill body.
# The phone-mcp router names tools to route (by design) and is excluded.
owners: dict[str, list[str]] = {}
for sid, body in bodies.items():
    if sid == "phone-mcp":
        continue
    for tool in re.findall(r"[a-z]+_[a-z_]+", body):
        owners.setdefault(tool, []).append(sid)
dupes = {t: s for t, s in owners.items()
         if len(set(s)) > 1 and t not in {"phone_mcp", "tool_call"}}
check(not dupes, f"single-source tool ownership across SKILL.md bodies"
      + (f" (dupes={dupes})" if dupes else ""))

print(f"\nstatic: {len(static_failures)} failures")
if static_failures:
    sys.exit(1)
print("static eval passed")

print("\n== live battery (safe tasks, skipped without transport) ==")
cli = shutil.which("phone-mcp")
has_env = bool(os.environ.get("PHONE_MCP_URL") and os.environ.get("PHONE_MCP_TOKEN"))
paired = (Path.home() / ".config" / "phone-mcp" / "config.json").exists()
if not cli:
    print("SKIP: `phone-mcp` CLI not on PATH (pip install ./client)")
    sys.exit(0)
if not (has_env or paired):
    print("SKIP: no PHONE_MCP_URL/TOKEN env and no paired store")
    sys.exit(0)

TASKS = [
    ("sense", "sensors_read", "{}"),
    ("sense", "battery_read", "{}"),
    ("capture", "vibrate", '{"durationMs": 200}'),
    ("wireless", "ble_scan", '{"durationMs": 3000}'),
    ("ui", "ui_dump", "{}"),
    ("net", "net_status", "{}"),
]
results = []
transport_dead = False
for domain, tool, args in TASKS:
    if transport_dead:
        results.append((domain, tool, "SKIP"))
        print(f"SKIP: [{domain}] {tool} — no transport (phone unreachable)")
        continue
    try:
        proc = subprocess.run(
            ["phone-mcp", "call", tool, "--args", args],
            capture_output=True, text=True, timeout=90)
        ok = proc.returncode == 0
        detail = (proc.stdout or proc.stderr or "")[:160].replace("\n", " ")
        if not ok and re.search(r"refused|unreachable|timed out|No route|network",
                                detail, re.IGNORECASE):
            transport_dead = True
            results.append((domain, tool, "SKIP"))
            print(f"SKIP: [{domain}] {tool} — no transport ({detail.strip()[:100]})")
            continue
        # Distinguish domain isError from transport failure. The CLI exits
        # nonzero on isError:true too, so also match guardrail-denial text.
        try:
            payload = json.loads(proc.stdout or "{}")
            is_error = isinstance(payload, dict) and payload.get("isError") is True
        except json.JSONDecodeError:
            is_error = False
        if not is_error and re.search(r"kill-switch|waiting_for_tap", detail):
            is_error = True
        status = "PASS" if ok and not is_error else ("DOMAIN-ERROR" if is_error else "FAIL")
        results.append((domain, tool, status))
        print(f"{status}: [{domain}] {tool} — {detail}")
    except subprocess.TimeoutExpired:
        results.append((domain, tool, "TIMEOUT"))
        print(f"TIMEOUT: [{domain}] {tool}")

failed = [r for r in results if r[2] not in ("PASS", "DOMAIN-ERROR")]
print(f"\nlive: {len(results) - len(failed)}/{len(results)} tasks answered "
      f"(domain-errors count as answered, transport answered at all)")
