#!/bin/bash
# configure-harness.sh — generate (and optionally install) harness configs for Phone MCP.
# Reads the dashboard QR payload {"url","token","sha256"} (Pairing.pairingPayload)
# or explicit flags, then renders per-harness configs that launch the stdio
# bridge (self-signed TOFU safe) with secrets passed via env, never baked in.
#
# Usage:
#   ./scripts/configure-harness.sh --from-qr '{"url":"https://192.168.1.50:8080/mcp","token":"...","sha256":"..."}' --harness all
#   ./scripts/configure-harness.sh --url https://192.168.1.50:8080/mcp --token <t> --sha256 <hex> --harness opencode --install
#   ./scripts/configure-harness.sh --harness "claude codex" --bridge python --out ./generated
#
# Flags: --from-qr JSON | --url U --token T --sha256 H [--bridge node|python]
#        [--harness all|opencode|claude|codex|vscode|cursor|gemini|cline|windsurf|generic]
#        [--out DIR (default ./generated-harness)] [--install] [--print-only] [--help]
#        [--no-skills] [--skills-dir DIR (default OUT/skills)]: stage the 6
#        phone-mcp agent skills next to the configs (and into ./.agents/skills
#        on --install) so agents never miss them
#        [--export-ca] [--ca-out PATH (default ./phone-mcp-ca.pem)]: chain
#        export-ca.sh after rendering (needs --sha256: QR or explicit).
set -euo pipefail

BRIDGE=node
HARNESS=all
OUT=./generated-harness
INSTALL=0
PRINT_ONLY=0
NO_SKILLS=0
SKILLS_DIR=""
EXPORT_CA=0
CA_OUT=./phone-mcp-ca.pem
URL=""
TOKEN=""
SHA=""

usage() { sed -n '2,14p' "$0"; exit "${1:-0}"; }

while [ $# -gt 0 ]; do
  case "$1" in
    --from-qr) shift; QR="$1"; shift
      URL=$(python3 -c "import json,sys;print(json.loads(sys.argv[1])['url'])" "$QR")
      TOKEN=$(python3 -c "import json,sys;print(json.loads(sys.argv[1])['token'])" "$QR")
      SHA=$(python3 -c "import json,sys;print(json.loads(sys.argv[1]).get('sha256',''))" "$QR") ;;
    --url) URL="$2"; shift 2 ;;
    --token) TOKEN="$2"; shift 2 ;;
    --sha256) SHA="$2"; shift 2 ;;
    --bridge) BRIDGE="$2"; shift 2 ;;
    --harness) HARNESS="$2"; shift 2 ;;
    --out) OUT="$2"; shift 2 ;;
    --install) INSTALL=1; shift ;;
    --print-only) PRINT_ONLY=1; shift ;;
    --no-skills) NO_SKILLS=1; shift ;;
    --skills-dir) SKILLS_DIR="$2"; shift 2 ;;
    --export-ca) EXPORT_CA=1; shift ;;
    --ca-out) CA_OUT="$2"; shift 2 ;;
    -h|--help) usage 0 ;;
    *) echo "unknown flag $1" >&2; usage 1 ;;
  esac
done

if [ -z "$URL" ] || [ -z "$TOKEN" ]; then
  echo "error: need --from-qr '<qr-json>' or --url + --token (+ --sha256)" >&2
  echo "hint: scan the dashboard QR / copy the pairing card; .env holds PHONE_MCP_TOKEN" >&2
  exit 1
fi
case "$BRIDGE" in node|python) ;; *) echo "error: --bridge must be node|python" >&2; exit 1 ;; esac

REPO="$(cd "$(dirname "$0")/.." && pwd)"
BRIDGE_CMD_NODE='["node", "bridges/phone-mcp-bridge.mjs"]'
BRIDGE_CMD_PY='["python3", "bridges/phone-mcp-bridge.py"]'
if [ "$BRIDGE" = python ]; then BRIDGE_JSON="$BRIDGE_CMD_PY"; BRIDGE_TOML_CMD="python3"; else BRIDGE_JSON="$BRIDGE_CMD_NODE"; BRIDGE_TOML_CMD="node"; fi

mkdir -p "$OUT"
echo "== phone-mcp harness config =="
echo "url:   $URL"
echo "sha:   ${SHA:-"(none — pass --sha256 after verifying dashboard fingerprint once)"}"
echo "bridge:$BRIDGE   out:$OUT"

want() { case " $HARNESS " in *" all "*|*" $1 "*) return 0;; *) return 1;; esac; }

gen_json() { # $1=harness — renders stdio-bridge JSON via python3 (no jq needed)
  python3 - "$1" <<EOF
import json, sys
h = sys.argv[1]
bridge = ["python3", "bridges/phone-mcp-bridge.py"] if "$BRIDGE" == "python" else ["node", "bridges/phone-mcp-bridge.mjs"]
if h == "opencode":
    cfg = {"mcp": {"phone-mcp": {"type": "local", "command": bridge, "enabled": True,
           "environment": {"PHONE_MCP_URL": "$URL", "PHONE_MCP_TOKEN": "{env:PHONE_MCP_TOKEN}",
                           "PHONE_MCP_SHA256": "{env:PHONE_MCP_SHA256}"}}}}
elif h == "gemini":
    cfg = {"mcpServers": {"phone-mcp": {"command": bridge[0], "args": [bridge[1]],
           "env": {"PHONE_MCP_URL": "$URL", "PHONE_MCP_TOKEN": "\$PHONE_MCP_TOKEN",
                   "PHONE_MCP_SHA256": "\$PHONE_MCP_SHA256"}, "timeout": 35000}}}
else:
    cfg = {"mcpServers": {"phone-mcp": {"command": bridge[0], "args": [bridge[1]],
           "env": {"PHONE_MCP_URL": "$URL", "PHONE_MCP_TOKEN": "\${PHONE_MCP_TOKEN}",
                   "PHONE_MCP_SHA256": "\${PHONE_MCP_SHA256}"}}}}
print(json.dumps(cfg, indent=2))
EOF
}

if want opencode; then
  gen_json opencode > "$OUT/opencode.phone-mcp.json"
  echo "wrote $OUT/opencode.phone-mcp.json (merge into ~/.config/opencode/opencode.json under \"mcp\")"
  if [ "$INSTALL" = 1 ]; then
    DEST="$HOME/.config/opencode/opencode.json"
    mkdir -p "$(dirname "$DEST")"
    URL="$URL" python3 - "$DEST" "$OUT/opencode.phone-mcp.json" <<'EOF'
import json, os, sys
dest, src = sys.argv[1], sys.argv[2]
base = json.load(open(dest)) if os.path.exists(dest) else {}
add = json.load(open(src))["mcp"]
base.setdefault("mcp", {}).update(add)
json.dump(base, open(dest, "w"), indent=2)
print(f"merged phone-mcp into {dest}")
EOF
  fi
fi

if want claude; then
  gen_json claude > "$OUT/claude.phone-mcp.json"
  echo "wrote $OUT/claude.phone-mcp.json (project .mcp.json or claude_desktop_config.json)"
  echo 'cli: claude mcp add phone-mcp -- node bridges/phone-mcp-bridge.mjs'
fi

if want codex; then
  {
    echo "# Phone MCP — remote Streamable HTTP (token via env ref, never baked in)."
    echo "[mcp_servers.phone-mcp]"
    echo "url = \"$URL\""
    echo 'bearer_token_env_var = "PHONE_MCP_TOKEN"'
    echo "# NOTE: stock Codex TLS-verify rejects the phone self-signed TOFU cert."
    echo "# Prefer the stdio bridge on LAN instead:"
    echo "#   codex mcp add phone-mcp --command $BRIDGE_TOML_CMD \\"
    if [ "$BRIDGE" = python ]; then echo '#     --args bridges/phone-mcp-bridge.py \'; else echo '#     --args bridges/phone-mcp-bridge.mjs \'; fi
    echo "#     --env PHONE_MCP_URL=$URL \\"
    echo '#     --env PHONE_MCP_TOKEN=$PHONE_MCP_TOKEN --env PHONE_MCP_SHA256=$PHONE_MCP_SHA256'
  } > "$OUT/codex.phone-mcp.toml"
  echo "wrote $OUT/codex.phone-mcp.toml (append to ~/.codex/config.toml)"
fi

if want vscode; then
  python3 - > "$OUT/vscode.mcp.json" <<EOF
import json
print(json.dumps({"servers": {"phone-mcp": {"type": "stdio", "command": "node",
  "args": ["\${workspaceFolder}/bridges/phone-mcp-bridge.mjs"],
  "env": {"PHONE_MCP_URL": "$URL", "PHONE_MCP_TOKEN": "\${input:phoneMcpToken}",
          "PHONE_MCP_SHA256": "\${input:phoneMcpSha256}"}}},
  "inputs": [{"id": "phoneMcpToken", "type": "promptString",
    "description": "Phone MCP bearer token", "password": True},
   {"id": "phoneMcpSha256", "type": "promptString",
    "description": "Phone MCP TLS fingerprint"}]}, indent=2))
EOF
  echo "wrote $OUT/vscode.mcp.json (copy to .vscode/mcp.json)"
fi

if want cursor; then gen_json cursor > "$OUT/cursor.phone-mcp.json"; echo "wrote $OUT/cursor.phone-mcp.json (~/.cursor/mcp.json)"; fi
if want cline; then gen_json cline > "$OUT/cline.phone-mcp.json"; echo "wrote $OUT/cline.phone-mcp.json (cline_mcp_settings.json)"; fi
if want windsurf; then gen_json windsurf > "$OUT/windsurf.phone-mcp.json"; echo "wrote $OUT/windsurf.phone-mcp.json (mcp_config.json)"; fi
if want gemini; then gen_json gemini > "$OUT/gemini.phone-mcp.json"; echo "wrote $OUT/gemini.phone-mcp.json (merge under mcpServers in ~/.gemini/settings.json)"; fi
if want generic; then gen_json generic > "$OUT/generic.phone-mcp.json"; echo "wrote $OUT/generic.phone-mcp.json"; fi

if [ "$NO_SKILLS" = 0 ]; then
  SKILLS_DEST="${SKILLS_DIR:-$OUT/skills}"
  if [ -d "$REPO/.agents/skills" ]; then
    n=0
    for s in "$REPO"/.agents/skills/phone-mcp*; do
      [ -d "$s" ] || continue
      rm -rf "$SKILLS_DEST/$(basename "$s")"
      mkdir -p "$SKILLS_DEST"
      cp -r "$s" "$SKILLS_DEST/"
      n=$((n+1))
    done
    # Static markdown must never carry the live pairing token.
    if [ "${#TOKEN}" -ge 8 ] && grep -rFq "$TOKEN" "$SKILLS_DEST"; then
      echo "error: live token found in staged skills — refusing" >&2
      exit 1
    fi
    echo "skills staged → $SKILLS_DEST ($n skills; --no-skills to skip)"
    if [ "$INSTALL" = 1 ]; then
      mkdir -p ./.agents/skills
      for s in "$SKILLS_DEST"/phone-mcp*; do
        [ -d "$s" ] || continue
        rm -rf "./.agents/skills/$(basename "$s")"
        cp -r "$s" ./.agents/skills/
      done
      echo "  skills installed → ./.agents/skills (agents discover .agents/skills/ automatically)"
    fi
  else
    echo "warning: .agents/skills not found — skills not staged" >&2
  fi
fi

if [ "$EXPORT_CA" = 1 ]; then
  if [ -z "$SHA" ]; then
    echo "error: --export-ca needs --sha256 (or --from-qr carrying it) — refusing to pin blind" >&2
    exit 1
  fi
  CA_HOST=$(python3 -c "import sys,urllib.parse; print(urllib.parse.urlparse(sys.argv[1]).hostname)" "$URL")
  CA_PORT=$(python3 -c "import sys,urllib.parse; print(urllib.parse.urlparse(sys.argv[1]).port or 443)" "$URL")
  "$REPO/scripts/export-ca.sh" --host "$CA_HOST" --port "$CA_PORT" --sha256 "$SHA" --out "$CA_OUT"
  echo "export PHONE_MCP_CAFILE=\"$CA_OUT\""
fi

cat <<EOF

-- next --
export PHONE_MCP_URL="$URL"
export PHONE_MCP_TOKEN='<paste-from-pairing-card>'
export PHONE_MCP_SHA256="${SHA:-<dashboard-fingerprint>}"
python3 scripts/phone-mcp-smoke.py --url "\$PHONE_MCP_URL" --token "\$PHONE_MCP_TOKEN" ${SHA:+--sha256 "$SHA"}
# Verified direct-remote mode (no bridge): ./scripts/export-ca.sh --host <lan-ip> --port 8080 --sha256 "\$PHONE_MCP_SHA256"
# USB fallback: adb reverse tcp:8080 tcp:8080  →  PHONE_MCP_URL=https://127.0.0.1:8080/mcp
# Full guide: docs/harness.md · examples: config/harness-examples/
EOF

if [ "$PRINT_ONLY" = 1 ]; then
  echo "== rendered ($HARNESS) =="
  for f in "$OUT"/*phone-mcp* "$OUT"/vscode.mcp.json; do [ -f "$f" ] && { echo "--- $f"; cat "$f"; }; done
fi
