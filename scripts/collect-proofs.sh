#!/bin/bash
# Collect per-feature proof artifacts into build/test-proofs/.
# Usage: TOKEN=<dashboard-token> ./scripts/collect-proofs.sh 192.168.1.50:8080
set -euo pipefail
BASE="${1:-192.168.1.50:8080}"
TOKEN="${TOKEN:?set TOKEN from the dashboard pairing card}"
OUT="build/test-proofs"
rm -rf "$OUT"
mkdir -p "$OUT"/{protocol,tools,media,sse,tls,auth,ui,device}

H=(-H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' -H 'MCP-Protocol-Version: 2025-06-18' -H "Authorization: Bearer $TOKEN")
post() { curl -sk -X POST "https://$BASE/mcp" "${H[@]}" -d "$1"; }

echo "== protocol =="
echo | openssl s_client -connect "$BASE" 2>/dev/null | openssl x509 -fingerprint -sha256 -noout > "$OUT/tls/fingerprint.txt"
curl -sk "https://$BASE/healthz" > "$OUT/protocol/healthz.json"
post '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"proofs","version":"1"}}}' > "$OUT/protocol/initialize.json"
post '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}' > "$OUT/protocol/tools-list.json"
post '{"jsonrpc":"2.0","id":3,"method":"ping","params":{}}' > "$OUT/protocol/ping.json"
curl -sk -o /dev/null -w '%{http_code}' "https://$BASE/mcp" > "$OUT/protocol/get-mcp-405.txt"
curl -sk "${H[@]}" "https://$BASE/info" > "$OUT/protocol/info.json"

echo "== auth gates =="
curl -sk -X POST "https://$BASE/mcp" -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":0,"method":"tools/list","params":{}}' > "$OUT/auth/no-token-401.json"
curl -sk -X POST "https://$BASE/mcp" -H 'Content-Type: application/json' \
  -H "Authorization: Bearer wrong" \
  -d '{"jsonrpc":"2.0","id":0,"method":"tools/list","params":{}}' > "$OUT/auth/bad-token-401.json"
post '{"jsonrpc":"2.0","id":4,"method":"tools/list","params":{}}' -H 'MCP-Protocol-Version: 1999' 2>/dev/null || true
curl -sk -X POST "https://$BASE/mcp" -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"jsonrpc":"2.0","id":5,"method":"tools/list","params":{}}' > "$OUT/auth/no-version-400.json"
post '{"jsonrpc":"2.0","id":6,"method":"resources/list","params":{}}' > "$OUT/auth/unknown-method-32601.json"
post '{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"nope","arguments":{}}}' > "$OUT/auth/unknown-tool-32602.json"

echo "== tools =="
call() { post "{\"jsonrpc\":\"2.0\",\"id\":$1,\"method\":\"tools/call\",\"params\":{\"name\":\"$2\",\"arguments\":$3}}" > "$OUT/tools/$2.json"; }
call 10 vibrate '{}'
call 11 torch '{"on":true}'
call 12 torch '{"on":false}'
call 13 speaker_say '{"text":"proof run"}'
call 14 clipboard_set '{"text":"proof-789"}'
call 15 clipboard_get '{}'
call 16 sensors_read '{"timeoutMs":3000}'
call 17 battery_read '{}'
call 18 device_info '{}'
call 19 location_read '{"timeoutMs":8000}'
call 20 nfc_status '{}'
call 21 ble_scan '{"durationMs":5000}'

echo "== media =="
call 30 audio_record '{"durationMs":2000}'
MPATH=$(python3 -c "import json; print(json.load(open('$OUT/tools/audio_record.json'))['result']['content'][0]['text'])" | python3 -c "import json,sys; print(json.load(sys.stdin)['mediaUrl'])")
curl -sk -o "$OUT/media/clip.m4a" -w '%{http_code} %{size_download}B' -H "Authorization: Bearer $TOKEN" "https://$BASE$MPATH" > "$OUT/media/clip-fetch.txt"
curl -sk -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $TOKEN" -H 'Range: bytes=0-99' "https://$BASE$MPATH" > "$OUT/media/clip-range-206.txt"
call 31 camera_capture '{"lens":"back"}'
PPATH=$(python3 -c "import json; print(json.load(open('$OUT/tools/camera_capture.json'))['result']['content'][0]['text'])" | python3 -c "import json,sys; print(json.load(sys.stdin)['mediaUrl'])")
curl -sk -o "$OUT/media/photo.jpg" -H "Authorization: Bearer $TOKEN" "https://$BASE$PPATH"
call 32 camera_video '{"lens":"back","durationMs":3000,"withAudio":false}'
VPATH=$(python3 -c "import json; print(json.load(open('$OUT/tools/camera_video.json'))['result']['content'][0]['text'])" | python3 -c "import json,sys; print(json.load(sys.stdin)['mediaUrl'])")
curl -sk -o "$OUT/media/clip.mp4" -H "Authorization: Bearer $TOKEN" "https://$BASE$VPATH"

echo "== sse =="
curl -skN --max-time 14 -X POST "https://$BASE/mcp" \
  -H 'Content-Type: application/json' -H 'Accept: text/event-stream' \
  -H 'MCP-Protocol-Version: 2025-06-18' -H "Authorization: Bearer $TOKEN" \
  -d '{"jsonrpc":"2.0","id":33,"method":"tools/call","params":{"name":"sensors_stream","arguments":{"durationMs":8000,"intervalMs":500,"includeLocation":false}}}' \
  > "$OUT/sse/stream.sse"
grep -c '^data: ' "$OUT/sse/stream.sse" > "$OUT/sse/event-count.txt"

echo "== device artifacts =="
adb exec-out screencap -p > "$OUT/ui/dashboard.png"
cp app/build/test-results/testDebugUnitTest/*.xml "$OUT/device/" 2>/dev/null || true
{
  echo "# Proof manifest"
  echo "- date: $(date -u +%FT%TZ), device: $(adb shell getprop ro.product.model 2>/dev/null | tr -d '\r') API $(adb shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r')"
  echo "- tools declared: $(python3 -c "import json; print(len(json.load(open('$OUT/protocol/tools-list.json'))['result']['tools']))")"
  echo "- sse events: $(cat "$OUT/sse/event-count.txt")"
  echo "- media: $(ls -la "$OUT/media" | awk '{print $5, $9}' | tr '\n' ';')"
} > "$OUT/README.md"
echo "saved to $OUT"
find "$OUT" -type f | sort
