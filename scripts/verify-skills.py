#!/usr/bin/env python3
"""Conformance gate for phone-mcp agent skills.

Checks Anthropic spec frontmatter (name/description), word budgets,
progressive-disclosure layout, full 41-tool coverage, and supply-chain
hygiene (markdown-only, no injection patterns, no remote-code execution,
token/redaction discipline).
"""
import re
import sys
from pathlib import Path

import yaml

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
ALL_TOOLS = [
    "sensors_read", "sensors_stream", "location_read", "battery_read",
    "device_info", "audio_record", "audio_stream", "camera_capture",
    "camera_video", "speaker_say", "vibrate", "torch", "clipboard_get",
    "clipboard_set", "ble_scan", "ble_connect", "ble_read", "ble_write",
    "ble_disconnect", "ble_subscribe", "nfc_status", "nfc_read",
    "ui_screenshot", "ui_dump", "ui_click", "ui_swipe", "ui_type",
    "ui_key", "net_status", "net_connections", "net_usage",
    "net_capture_start", "net_capture_stop", "net_export_pcap",
    "net_export_har", "net_firewall_get", "net_firewall_set",
    "net_blocklist_check", "net_geo_lookup", "net_tls_info", "net_stream",
]
ALLOWED_KEYS = {"name", "description", "license", "allowed-tools", "metadata"}

failures = []


def check(cond, msg):
    print(("PASS" if cond else "FAIL") + f": {msg}")
    if not cond:
        failures.append(msg)


for sid in SKILL_IDS:
    d = SKILLS_DIR / sid
    check(d.is_dir(), f"{sid} directory exists")
    md = d / "SKILL.md"
    check(md.exists(), f"{sid}/SKILL.md exists")
    if not md.exists():
        continue
    text = md.read_text()
    m = re.match(r"^---\n(.*?)\n---", text, re.DOTALL)
    check(m is not None, f"{sid} has YAML frontmatter")
    if not m:
        continue
    fm = yaml.safe_load(m.group(1))
    check(isinstance(fm, dict), f"{sid} frontmatter parses")
    check(set(fm) <= ALLOWED_KEYS, f"{sid} frontmatter keys {sorted(fm)} allowed")
    name = str(fm.get("name", ""))
    check(re.fullmatch(r"[a-z0-9-]+", name) is not None, f"{sid} name hyphen-case")
    check(len(name) <= 64, f"{sid} name <=64 chars ({len(name)})")
    desc = str(fm.get("description", ""))
    check(len(desc) <= 1024, f"{sid} description <=1024 chars ({len(desc)})")
    check("<" not in desc and ">" not in desc, f"{sid} description has no angle brackets")
    words = len(text.split())
    check(words <= 2000, f"{sid} SKILL.md <=2000 words ({words})")
    check((d / "references").is_dir(), f"{sid}/references/ exists")
    refs = list((d / "references").glob("*.md")) if (d / "references").is_dir() else []
    check(len(refs) >= 1, f"{sid} has >=1 reference file")

corpus = ""
for sid in SKILL_IDS:
    d = SKILLS_DIR / sid
    if (d / "SKILL.md").exists():
        corpus += (d / "SKILL.md").read_text()
    for f in (d / "references").glob("*.md") if (d / "references").is_dir() else []:
        corpus += f.read_text()
missing = [t for t in ALL_TOOLS if t not in corpus]
check(not missing, f"41-tool coverage (missing={missing})")
check("kill-switch" in corpus or "kill-switch" in corpus, "kill-switch guardrails documented")
check("ALPN" in corpus, "ALPN mandate documented")
check("/media/" in corpus, "/media download path documented")

# Supply-chain hygiene (Snyk ToxicSkills, 2026: 36% of audited skills carry
# prompt injection; 100% of confirmed-malicious skills pair it with code).
INJECTION_RES = [
    r"ignore (previous|all|above) instructions",
    r"system prompt",
    r"jailbreak",
    r"exfiltrat",
    r"base64.*decode|decode.*base64",
    r"BEGIN [A-Z ]*PRIVATE KEY",
]
for sid in SKILL_IDS:
    d = SKILLS_DIR / sid
    sid_corpus = ""
    if (d / "SKILL.md").exists():
        sid_corpus += (d / "SKILL.md").read_text()
    for f in (d / "references").glob("*.md") if (d / "references").is_dir() else []:
        sid_corpus += f.read_text()
    for pat in INJECTION_RES:
        check(re.search(pat, sid_corpus, re.IGNORECASE) is None,
              f"{sid} has no injection pattern /{pat}/")
    executables = [p for p in d.rglob("*")
                   if p.is_file() and p.suffix in {".py", ".sh", ".js", ".mjs", ".exe"}
                   and p.name != "verify-skills.py"]
    check(not executables, f"{sid} markdown-only (no executables: {[p.name for p in executables]})")
    check(re.search(r"curl[^\n]*\|\s*(ba)?sh|wget[^\n]*\|\s*(ba)?sh", sid_corpus) is None,
          f"{sid} has no curl|sh / wget|sh remote execution")
    check(re.search(r"pip install\s+https?://|npm install\s+https?://", sid_corpus) is None,
          f"{sid} has no URL-based package installs")

check(re.search(r"never log tokens?", corpus, re.IGNORECASE) is not None,
      "token-logging prohibition documented")
ui_corpus = ((SKILLS_DIR / "phone-mcp-ui" / "SKILL.md").read_text()
             if (SKILLS_DIR / "phone-mcp-ui" / "SKILL.md").exists() else "")
check("redact" in ui_corpus.lower(), "ui skill documents password redaction")

print(f"\n{len(ALL_TOOLS) - len(missing)}/{len(ALL_TOOLS)} tools covered")
if failures:
    print(f"{len(failures)} FAILURES")
    sys.exit(1)
print("all skill checks passed")
