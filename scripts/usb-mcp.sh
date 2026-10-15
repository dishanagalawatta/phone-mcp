#!/bin/bash
# usb-mcp.sh — USB-cable hardening for Phone MCP via `adb forward`.
#
# VERIFIED (Oct 15 2026, SM_G991B, adb 1.0.41 v34.0.5-debian):
#   `adb forward tcp:8080 tcp:8080` + https://127.0.0.1:8080/healthz WORKS
#   (same TLS fingerprint + auth gate as LAN).
#   `adb reverse tcp:8080 tcp:8080` ALWAYS FAILS with
#   "cannot bind listener: Address already in use" because device:8080 is
#   already held by the Ktor server. Docs saying `adb reverse` are WRONG —
#   the host-side forward is the correct direction.
#   (adb docs: `forward --list | [--no-rebind] LOCAL REMOTE | --remove LOCAL |
#    --remove-all`, `reverse --list | ... REMOTE LOCAL | --remove REMOTE |
#    --remove-all` — see adb.1.md.)
#
# Usage:
#   ./scripts/usb-mcp.sh [setup|check|status|remove] [--port PORT] [--serial SER|-s SER]
#                        [--timeout SECS] [--json] [-h|--help]
#   setup  (default): create forward mapping (idempotent) + verify healthz
#   check:           verify mapping + healthz WITHOUT changing anything (quiet, for scripts)
#   status:          verbose human report of mapping + healthz
#   remove:          drop the forward mapping for --port (idempotent)
#
# Flags: --port PORT (default 8080), --serial/-s SER (pick device when several
#        are attached), --timeout SECS (default 10, curl/openssl waits),
#        --json (machine-readable status object on stdout; ALL human logs go
#        to stderr so stdout stays parseable), -h/--help.
# Exit codes: 0 ok · 1 runtime failure (no device, forward/healthz failed) ·
#        2 usage error.
set -euo pipefail

CMD="setup"; PORT="8080"; SERIAL=""; TIMEOUT="10"; JSON=0

usage() { sed -n '2,21p' "$0"; exit "${1:-0}"; }

while [ $# -gt 0 ]; do
  case "$1" in
    setup|check|status|remove) CMD="$1"; shift ;;
    --port) [ $# -ge 2 ] || { echo "[usb-mcp] error: --port needs a value" >&2; exit 2; }; PORT="$2"; shift 2 ;;
    --serial|-s) [ $# -ge 2 ] || { echo "[usb-mcp] error: --serial needs a value" >&2; exit 2; }; SERIAL="$2"; shift 2 ;;
    --timeout) [ $# -ge 2 ] || { echo "[usb-mcp] error: --timeout needs a value" >&2; exit 2; }; TIMEOUT="$2"; shift 2 ;;
    --json) JSON=1; shift ;;
    -h|--help) usage 0 ;;
    *) echo "[usb-mcp] error: unknown arg $1" >&2; echo "[usb-mcp] hint: $0 --help" >&2; exit 2 ;;
  esac
done

case "$PORT" in ''|*[!0-9]*) echo "[usb-mcp] error: --port must be numeric (got '$PORT')" >&2; exit 2 ;; esac
case "$TIMEOUT" in ''|*[!0-9]*) echo "[usb-mcp] error: --timeout must be numeric (got '$TIMEOUT')" >&2; exit 2 ;; esac

log() { echo "[usb-mcp] $*" >&2; }

command -v adb >/dev/null || { log "error: adb not found (install Android platform-tools)"; exit 1; }

DEVICES_RAW="$(adb devices 2>/dev/null || true)"
# Lines after the header look like "<serial>\t<state>[ ...]".
DEVICE_ROWS="$(printf '%s\n' "$DEVICES_RAW" | tail -n +2 | grep -v '^[[:space:]]*$' || true)"

row_state() { # $1=serial → prints state, exit 1 if absent
  printf '%s\n' "$DEVICE_ROWS" | awk -v s="$1" '$1==s {print $2; found=1} END {exit !found}'
}

pick_device() { # prints chosen serial; returns 1 with hint on stderr on failure
  local state devs n
  if [ -n "$SERIAL" ]; then
    if ! state="$(row_state "$SERIAL")"; then
      log "error: device '$SERIAL' not attached"
      log "hint: $(printf '%s' "$DEVICE_ROWS" | tr '\n' '; ' | sed 's/; $//')"
      log "hint: check USB cable / 'adb devices'"
      return 1
    fi
    case "$state" in
      device) printf '%s' "$SERIAL"; return 0 ;;
      unauthorized) log "error: device '$SERIAL' is unauthorized — accept the RSA prompt on the phone"; return 1 ;;
      offline) log "error: device '$SERIAL' is offline — replug USB / 'adb reconnect'"; return 1 ;;
      *) log "error: device '$SERIAL' in state '$state' — replug USB"; return 1 ;;
    esac
  fi
  devs="$(printf '%s\n' "$DEVICE_ROWS" | awk '$2=="device" {print $1}')"
  n="$(printf '%s\n' "$devs" | grep -c . || true)"
  if [ "$n" = "1" ]; then printf '%s' "$devs"; return 0; fi
  if [ "$n" = "0" ]; then
    if printf '%s\n' "$DEVICE_ROWS" | grep -q .; then
      log "error: no usable device (attached rows: $(printf '%s' "$DEVICE_ROWS" | tr '\n' ';' | sed 's/;$//'))"
      printf '%s\n' "$DEVICE_ROWS" | grep -q unauthorized && log "hint: accept the USB-debugging RSA prompt on the phone"
      printf '%s\n' "$DEVICE_ROWS" | grep -q offline && log "hint: replug USB / run 'adb reconnect'"
    else
      log "error: no adb devices attached — plug in USB + enable USB debugging"
    fi
    return 1
  fi
  log "error: $n devices in 'device' state — pass --serial/-s to pick one:"
  printf '%s\n' "$DEVICE_ROWS" | sed 's/^/[usb-mcp]   /' >&2
  return 1
}

forward_present() { # $1=serial $2=port → 0 if a mapping touching tcp:PORT exists
  adb forward --list 2>/dev/null | grep -q "^$1[[:space:]].*tcp:$2" || return 1
}

probe_healthz() { # $1=port → 0 if {"ok":true}
  curl -sk --max-time "$TIMEOUT" "https://127.0.0.1:$1/healthz" 2>/dev/null \
    | grep -q '"ok"[[:space:]]*:[[:space:]]*true'
}

probe_fingerprint() { # $1=port → prints fingerprint line or empty
  command -v openssl >/dev/null || return 0
  if command -v timeout >/dev/null; then
    timeout "$TIMEOUT" sh -c "echo | openssl s_client -connect '127.0.0.1:$1' 2>/dev/null" \
      | openssl x509 -fingerprint -sha256 -noout 2>/dev/null || true
  else
    echo | openssl s_client -connect "127.0.0.1:$1" 2>/dev/null \
      | openssl x509 -fingerprint -sha256 -noout 2>/dev/null || true
  fi
}

emit_json() { # $1=ok $2=forward $3=healthz ("true"/"false") $4=fp $5=serial $6=url
  python3 - "$1" "$2" "$3" "$4" "$5" "$6" "$PORT" <<'EOF'
import json, sys
ok, fwd, hzt, fp, ser, url, port = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4], sys.argv[5], sys.argv[6], int(sys.argv[7])
print(json.dumps({"ok": ok == "true", "forward": fwd == "true", "healthz": hzt == "true",
                  "fingerprint": fp, "serial": ser, "port": port, "url": url}))
EOF
}

report_exports() { # $1=port → copy-paste exports on stderr (stdout stays machine-clean)
  log "exports (USB path):"
  log "  export PHONE_MCP_URL=\"https://127.0.0.1:$1/mcp\""
  log "  export PHONE_MCP_TOKEN='<paste-from-dashboard-pairing-card>'"
}

case "$CMD" in
  setup)
    SER="$(pick_device)" || exit 1
    log "device: $SER"
    if forward_present "$SER" "$PORT"; then
      log "forward tcp:$PORT -> device tcp:$PORT already mapped (idempotent, kept)"
    else
      log "creating forward: host tcp:$PORT -> device $SER tcp:$PORT"
      adb -s "$SER" forward "tcp:$PORT" "tcp:$PORT" \
        || { log "error: 'adb forward tcp:$PORT tcp:$PORT' failed"; exit 1; }
    fi
    if probe_healthz "$PORT"; then
      log "healthz OK: https://127.0.0.1:$PORT/healthz"
    else
      log "error: healthz FAILED at https://127.0.0.1:$PORT/healthz"
      log "hint: is the MCP server RUNNING on the phone (dashboard toggle)?"
      exit 1
    fi
    FP="$(probe_fingerprint "$PORT")"
    if [ -n "$FP" ]; then log "tls: $FP (compare with dashboard once)"; else log "tls: (openssl probe skipped/failed — compare dashboard fingerprint manually)"; fi
    report_exports "$PORT"
    if [ "$JSON" = 1 ]; then emit_json true true true "$FP" "$SER" "https://127.0.0.1:$PORT/mcp"; fi
    ;;
  check)
    # Quiet verify for scripts (e.g. verify-mcp.sh --usb): no state changes.
    SER="$(pick_device)" || { [ "$JSON" = 1 ] && emit_json false false false "" "" "https://127.0.0.1:$PORT/mcp"; exit 1; }
    FWD=false; forward_present "$SER" "$PORT" && FWD=true
    HZ=false; probe_healthz "$PORT" && HZ=true
    if [ "$FWD" = true ] && [ "$HZ" = true ]; then
      [ "$JSON" = 0 ] && log "OK: forward + healthz live (device $SER, tcp:$PORT)"
      [ "$JSON" = 1 ] && emit_json true true true "" "$SER" "https://127.0.0.1:$PORT/mcp"
    else
      [ "$JSON" = 0 ] && log "FAIL: forward=$FWD healthz=$HZ — run '$0 setup --port $PORT'"
      [ "$JSON" = 1 ] && emit_json false "$FWD" "$HZ" "" "$SER" "https://127.0.0.1:$PORT/mcp"
      exit 1
    fi
    ;;
  status)
    SER="$(pick_device)" || { SER=""; log "(no usable device — probing loopback only)"; }
    FWD=false; [ -n "${SER:-}" ] && forward_present "$SER" "$PORT" && FWD=true
    HZ=false; probe_healthz "$PORT" && HZ=true
    FP="$(probe_fingerprint "$PORT")"
    log "device:    ${SER:-(none)}"
    log "forward:   $([ "$FWD" = true ] && echo "tcp:$PORT -> $SER tcp:$PORT" || echo "(no adb forward mapping for tcp:$PORT — run '$0 setup')")"
    log "healthz:   $([ "$HZ" = true ] && echo "OK https://127.0.0.1:$PORT/healthz" || echo "UNREACHABLE (server running? mapping present?)")"
    log "tls:       ${FP:-(no fingerprint; openssl missing or no listener)}"
    report_exports "$PORT"
    if [ "$JSON" = 1 ]; then
      if [ "$FWD" = true ] && [ "$HZ" = true ]; then emit_json true true true "$FP" "$SER" "https://127.0.0.1:$PORT/mcp";
      elif [ "$FWD" = true ]; then emit_json false true false "$FP" "$SER" "https://127.0.0.1:$PORT/mcp";
      elif [ "$HZ" = true ]; then emit_json false false true "$FP" "$SER" "https://127.0.0.1:$PORT/mcp";
      else emit_json false false false "$FP" "$SER" "https://127.0.0.1:$PORT/mcp"; fi
    fi
    { [ "$FWD" = true ] && [ "$HZ" = true ]; } || exit 1
    ;;
  remove)
    SER="$(pick_device)" || exit 1
    # `adb forward --remove LOCAL` drops the host-side spec (idempotent: ignore absent).
    adb -s "$SER" forward --remove "tcp:$PORT" 2>/dev/null \
      && log "removed forward tcp:$PORT (device $SER)" \
      || log "(no forward tcp:$PORT to remove — already clean)"
    if forward_present "$SER" "$PORT"; then log "error: mapping still listed after remove"; exit 1; fi
    ;;
esac
exit 0
