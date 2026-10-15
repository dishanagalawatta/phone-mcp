#!/bin/bash
# Verify MCP endpoint conformance (requires phone on same LAN + server running).
# TLS is self-signed (TOFU): curl uses -k; verify the fingerprint against the dashboard once:
#   echo | openssl s_client -connect 192.168.1.50:8080 2>/dev/null | openssl x509 -fingerprint -sha256 -noout
# Auth: export TOKEN from the dashboard pairing card (or PHONE_MCP_TOKEN).
# Usage: TOKEN=<from-dashboard-qr> ./scripts/verify-mcp.sh 192.168.1.50:8080
set -euo pipefail
BASE="${1:-192.168.1.50:8080}"
TOKEN="${PHONE_MCP_TOKEN:-${TOKEN:-}}"
AUTH=()
if [ -n "$TOKEN" ]; then AUTH=(-H "Authorization: Bearer $TOKEN"); else echo "(no TOKEN set — expect 401s on data endpoints)"; fi

mcp_post() { # $1=json-body
  curl -sk -X POST "https://$BASE/mcp" \
    -H 'Content-Type: application/json' \
    -H 'Accept: application/json, text/event-stream' \
    -H 'MCP-Protocol-Version: 2025-06-18' \
    "${AUTH[@]}" \
    -d "$1"
}

echo "== TLS fingerprint (compare with dashboard once) =="
if command -v openssl >/dev/null; then
  echo | openssl s_client -connect "$BASE" 2>/dev/null | openssl x509 -fingerprint -sha256 -noout || echo "(fingerprint probe failed)"
else
  echo "(openssl missing — compare dashboard fingerprint manually)"
fi
echo "== healthz (open) =="
curl -sk "https://$BASE/healthz"
echo; echo "== initialize =="
mcp_post '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"verify","version":"0.1"}}}'
echo; echo "== tools/list =="
mcp_post '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}' | python3 -c "import json,sys; print('tools:', len(json.load(sys.stdin)['result']['tools']))"
echo "== tools/call vibrate =="
mcp_post '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"vibrate","arguments":{}}}'
echo; echo "== GET /mcp (expect 401, auth-first) =="
curl -sk -o /dev/null -w '%{http_code}\n' "https://$BASE/mcp"
echo "== GET /info (auth) =="
curl -sk "${AUTH[@]}" "https://$BASE/info"
echo; echo "== tools/call battery_read =="
mcp_post '{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"battery_read","arguments":{}}}'
echo; echo "== tools/call sensors_read =="
mcp_post '{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"sensors_read","arguments":{"types":["accelerometer","light"],"timeoutMs":1500}}}'
echo; echo "== audio_record 2s + /media range proof =="
RESP=$(mcp_post '{"jsonrpc":"2.0","id":6,"method":"tools/call","params":{"name":"audio_record","arguments":{"durationMs":2000}}}')
echo "$RESP"
if python3 -c "import json,sys; sys.exit(0 if json.load(sys.stdin)['result'].get('isError') else 1)" <<<"$RESP"; then
  echo "(skip media + PCM checks: audio_* kill-switch OFF — enable Microphone Audio on the dashboard to probe them)"
else
  MPATH=$(python3 -c "import json,sys; r=json.load(sys.stdin); t=json.loads(r['result']['content'][0]['text']); print(t['mediaUrl'])" <<<"$RESP")
  echo "media: $MPATH"
  curl -sk -o /dev/null -w 'full:%{http_code} %{size_download}B\n' "${AUTH[@]}" "https://$BASE$MPATH"
  curl -sk -o /dev/null -w 'range:%{http_code}\n' "${AUTH[@]}" -H 'Range: bytes=0-99' "https://$BASE$MPATH"
fi
echo "== tools/call nfc_status =="
mcp_post '{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"nfc_status","arguments":{}}}'
echo; echo "== tools/call net_status (needs net_ toggle ON; VPN off until dashboard consent) =="
mcp_post '{"jsonrpc":"2.0","id":12,"method":"tools/call","params":{"name":"net_status","arguments":{}}}'
echo; echo "== tools/call net_geo_lookup + net_blocklist_check (offline, no VPN needed) =="
mcp_post '{"jsonrpc":"2.0","id":13,"method":"tools/call","params":{"name":"net_geo_lookup","arguments":{"ip":"8.8.8.8"}}}'
echo
mcp_post '{"jsonrpc":"2.0","id":14,"method":"tools/call","params":{"name":"net_blocklist_check","arguments":{"host":"example.com"}}}'
echo "== ble_subscribe (optional; needs BLE_PEER=AA:BB:CC:DD:EE:FF on the LAN) =="
if [ -n "${BLE_PEER:-}" ]; then
  CONN=$(mcp_post "{\"jsonrpc\":\"2.0\",\"id\":10,\"method\":\"tools/call\",\"params\":{\"name\":\"ble_connect\",\"arguments\":{\"address\":\"$BLE_PEER\"}}}")
  echo "$CONN"
  SVC=$(python3 -c "import json,sys; r=json.load(sys.stdin); s=json.loads(r['result']['content'][0]['text'])['services']; print(s[0]['uuid'], s[0]['characteristics'][0])" <<<"$CONN")
  set -- $SVC
  echo "probing subscribe on $1 / $2 (2s)"
  mcp_post "{\"jsonrpc\":\"2.0\",\"id\":11,\"method\":\"tools/call\",\"params\":{\"name\":\"ble_subscribe\",\"arguments\":{\"address\":\"$BLE_PEER\",\"serviceUuid\":\"$1\",\"charUuid\":\"$2\",\"durationMs\":2000}}}"
  echo
  echo "disconnecting $BLE_PEER (leave no GATT session behind)"
  mcp_post "{\"jsonrpc\":\"2.0\",\"id\":15,\"method\":\"tools/call\",\"params\":{\"name\":\"ble_disconnect\",\"arguments\":{\"address\":\"$BLE_PEER\"}}}"
  echo
else
  echo "(skip: export BLE_PEER=<mac> to probe ble_connect + ble_subscribe)"
fi
echo; echo "== sensors_stream SSE (8s, expect ~17 data: events) =="
curl -skN --max-time 12 -X POST "https://$BASE/mcp" \
  -H 'Content-Type: application/json' \
  -H 'Accept: text/event-stream' \
  -H 'MCP-Protocol-Version: 2025-06-18' \
  "${AUTH[@]}" \
  -d '{"jsonrpc":"2.0","id":8,"method":"tools/call","params":{"name":"sensors_stream","arguments":{"durationMs":8000,"intervalMs":500,"includeLocation":false}}}' \
  | grep -c '^data: ' | xargs echo 'sse events:'
echo "== audio_stream SSE (3s) + PCM decode =="
if python3 -c "import json,sys; sys.exit(0 if json.load(sys.stdin)['result'].get('isError') else 1)" <<<"$RESP"; then
  echo "(skip: audio_* kill-switch OFF — see above)"
else
  mkdir -p "${TMPDIR:-/tmp}/opencode"
  curl -skN --max-time 12 -X POST "https://$BASE/mcp" \
    -H 'Content-Type: application/json' \
    -H 'Accept: text/event-stream' \
    -H 'MCP-Protocol-Version: 2025-06-18' \
    "${AUTH[@]}" \
    -d '{"jsonrpc":"2.0","id":9,"method":"tools/call","params":{"name":"audio_stream","arguments":{"durationMs":3000,"chunkMs":1000}}}' \
    > "${TMPDIR:-/tmp}/opencode/audio.sse"
  grep -c '^data: ' "${TMPDIR:-/tmp}/opencode/audio.sse" | xargs echo 'sse events:'
  python3 - <<'EOF'
import json, os, tempfile
sse_path = os.path.join(os.environ.get('TMPDIR', '/tmp'), 'opencode/audio.sse')
for line in open(sse_path):
    if not line.startswith('data: '):
        continue
    o = json.loads(line[6:])
    if 'params' in o:
        p = o['params']
        raw = __import__('base64').b64decode(p['dataBase64'])
        print(f"chunk seq={p['seq']} {p['sampleRateHz']}Hz {p['encoding']} bytes={len(raw)} rms={p['rms']:.3f}")
        assert p['sampleRateHz'] == 16000 and p['encoding'] == 'pcm_s16le'
        assert len(raw) == 16000 * 2 and 0.0 <= p['rms'] <= 1.0
        break
print('PCM chunk OK')
EOF
fi
echo "== capabilities_list (dynamic registry; ext_* gated OFF by default) =="
mcp_post '{"jsonrpc":"2.0","id":20,"method":"tools/call","params":{"name":"capabilities_list","arguments":{}}}'
echo; echo "== tools/list carries capabilities_* management tools =="
mcp_post '{"jsonrpc":"2.0","id":21,"method":"tools/list","params":{}}' | python3 -c "import json,sys; names=[t['name'] for t in json.load(sys.stdin)['result']['tools']]; print('capabilities_*:', sorted(n for n in names if n.startswith('capabilities_')))"
echo "== capabilities approve round-trip =="
mcp_post '{"jsonrpc":"2.0","id":22,"method":"tools/call","params":{"name":"capabilities_register","arguments":{"capabilityId":"verify","packageName":"com.phonemcp.server","displayName":"verify probe","version":"1","tools":[{"name":"ping","description":"verify probe","inputSchema":{"type":"object"}}]}}}'
mcp_post '{"jsonrpc":"2.0","id":23,"method":"tools/call","params":{"name":"capabilities_approve","arguments":{"capabilityId":"verify"}}}'
mcp_post '{"jsonrpc":"2.0","id":24,"method":"tools/call","params":{"name":"capabilities_list","arguments":{}}}' | python3 -c "import json,sys; caps=json.loads(json.load(sys.stdin)['result']['content'][0]['text']); v=[c for c in caps['capabilities'] if c['capabilityId']=='verify']; assert v and v[0]['approved'], 'approve flag missing'; print('approved:true')"
echo "== GET /mcp/events push (expect 2x list_changed) =="
SSE_OUT="${TMPDIR:-/tmp}/phone-mcp-events.sse"
curl -sk -N -m 25 "https://$BASE/mcp/events" "${AUTH[@]}" -H 'Accept: text/event-stream' > "$SSE_OUT" 2>/dev/null &
EVTPID=$!
sleep 3
mcp_post '{"jsonrpc":"2.0","id":25,"method":"tools/call","params":{"name":"capabilities_revoke","arguments":{"capabilityId":"verify"}}}' > /dev/null
mcp_post '{"jsonrpc":"2.0","id":26,"method":"tools/call","params":{"name":"capabilities_unregister","arguments":{"capabilityId":"verify"}}}' > /dev/null
sleep 2
kill $EVTPID 2>/dev/null || true
wait $EVTPID 2>/dev/null || true
echo "list_changed events: $(grep -c 'notifications/tools/list_changed' "$SSE_OUT" || true)"
echo "== verify complete =="
