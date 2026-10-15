#!/bin/bash
# export-ca.sh — pin the phone's self-signed leaf cert as a local CA file.
# Enables direct-remote harness mode (Codex/Codex Desktop, Cursor, Claude
# --transport http) with real chain verification instead of INSECURE=1:
# the fetched cert is hashed and compared against the dashboard SHA-256
# fingerprint (TOFU) BEFORE anything is written — fail closed on mismatch.
#
# Usage:
#   ./scripts/export-ca.sh --host 192.168.1.50 --port 8080 \
#     --sha256 '<dashboard-fingerprint>' [--out ./phone-mcp-ca.pem]
#   ./scripts/export-ca.sh --usb --sha256 '<dashboard-fingerprint>'
#     (USB cable via `adb forward`; HOST defaults to 127.0.0.1 — run
#     scripts/usb-mcp.sh setup first so the mapping exists)
#   # then: export PHONE_MCP_CAFILE="$PWD/phone-mcp-ca.pem"
#   # direct harnesses: NODE_EXTRA_CA_CERTS / SSL_CERT_FILE (see docs/harness.md)
set -euo pipefail

HOST=""; PORT="8080"; SHA=""; OUT="./phone-mcp-ca.pem"; USB=0

while [ $# -gt 0 ]; do
  case "$1" in
    --host) HOST="$2"; shift 2 ;;
    --port) PORT="$2"; shift 2 ;;
    --sha256) SHA="$2"; shift 2 ;;
    --out) OUT="$2"; shift 2 ;;
    --usb) USB=1; shift ;;
    -h|--help) sed -n '2,11p' "$0"; exit 0 ;;
    *) echo "unknown flag $1" >&2; exit 1 ;;
  esac
done

if [ "$USB" = 1 ] && [ -z "$HOST" ]; then HOST="127.0.0.1"; fi

if [ -z "$HOST" ] || [ -z "$SHA" ]; then
  echo "error: need --host <lan-ip> and --sha256 <dashboard-fingerprint>" >&2
  echo "hint: dashboard pairing card shows the SHA-256 grouping (AA:BB:…)" >&2
  exit 1
fi
command -v openssl >/dev/null || { echo "error: openssl not found" >&2; exit 1; }

norm() { tr -d ': \t\r\n' | tr 'A-F' 'a-f'; }
EXPECTED=$(printf '%s' "$SHA" | norm)
if [ "${#EXPECTED}" != 64 ]; then
  echo "error: --sha256 must be 64 hex chars (got ${#EXPECTED})" >&2
  exit 1
fi

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

echo "== fetching leaf cert from $HOST:$PORT =="
if ! timeout 15 openssl s_client -connect "$HOST:$PORT" -showcerts </dev/null >"$TMP/chain.pem" 2>"$TMP/err.log"; then
  echo "error: TLS handshake failed (server running? LAN reachable? try adb forward (scripts/usb-mcp.sh setup))" >&2
  tail -3 "$TMP/err.log" >&2 || true
  exit 1
fi
# First certificate block is the leaf (server) identity.
awk '/BEGIN CERTIFICATE/{n++} n==1' "$TMP/chain.pem" > "$TMP/leaf.pem"
if ! grep -q 'BEGIN CERTIFICATE' "$TMP/leaf.pem"; then
  echo "error: no certificate presented by $HOST:$PORT" >&2
  exit 1
fi

ACTUAL=$(openssl x509 -in "$TMP/leaf.pem" -noout -fingerprint -sha256 | sed 's/.*=//' | norm)
if [ "$ACTUAL" != "$EXPECTED" ]; then
  echo "error: fingerprint MISMATCH — refusing to write CA file" >&2
  echo "  expected: $EXPECTED" >&2
  echo "  presented: $ACTUAL" >&2
  echo "hint: the phone rotated its identity (reinstall/reset) — re-verify the dashboard value" >&2
  exit 1
fi

openssl x509 -in "$TMP/leaf.pem" -outform PEM > "$OUT"
chmod 644 "$OUT"
echo "OK: pinned $HOST:$PORT leaf cert → $OUT"
openssl x509 -in "$OUT" -noout -subject -issuer -dates | sed 's/^/  /'
echo "use: export PHONE_MCP_CAFILE=\"$OUT\"  (bridge + direct harnesses, see docs/harness.md)"
