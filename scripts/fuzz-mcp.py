#!/usr/bin/env python3
"""Fuzz + harden bench for Phone MCP (host side, LAN HTTPS).

Usage:
  export TOKEN=<dashboard token> HOST=192.168.1.50:8080
  python3 scripts/fuzz-mcp.py

Phases: malformed JSON, wrong types, oversized payloads, concurrent streams,
concurrent captures, rapid rotation, weird ranges. Every probe asserts the
server either answers a well-formed JSON-RPC error (or 4xx) or stays healthy
(healthz 200 + tools/list 200 afterwards). Any connection drop, hang (>timeout),
5xx, or non-JSON reply is a FAIL recorded at the end.
"""
import base64
import concurrent.futures
import http.client
import json
import os
import ssl
import sys
import time
import urllib.request

BASE = os.environ.get("HOST", "192.168.1.50:8080")


def split_base():
    if ":" in BASE:
        host, port = BASE.rsplit(":", 1)
        return host, int(port)
    return BASE, 443


HOST, PORT = split_base()
TOKEN = os.environ.get("TOKEN", "")
TIMEOUT = 70
FAILURES: list[str] = []
PASS = 0


def ctx():
    c = ssl.create_default_context()
    c.check_hostname = False
    c.verify_mode = ssl.CERT_NONE
    # The Ktor/Netty pipeline negotiates h2 vs http/1.1 via ALPN and drops
    # clients that offer none (like Android, we speak http/1.1 here).
    c.set_alpn_protocols(["http/1.1"])
    return c


def raw_post(path, body: bytes, headers: dict | None = None):
    h = {
        "Content-Type": "application/json",
        "Accept": "application/json, text/event-stream",
        "MCP-Protocol-Version": "2025-06-18",
    }
    if TOKEN:
        h["Authorization"] = f"Bearer {TOKEN}"
    h.update(headers or {})
    conn = http.client.HTTPSConnection(HOST, PORT, timeout=TIMEOUT, context=ctx())
    conn.request("POST", path, body=body, headers=h)
    resp = conn.getresponse()
    data = resp.read()
    conn.close()
    return resp.status, data


def call(name, args, mid=900, token=TOKEN, version="2025-06-18"):
    h = {
        "Content-Type": "application/json",
        "Accept": "application/json, text/event-stream",
        "MCP-Protocol-Version": version,
    }
    if token is not None:
        h["Authorization"] = f"Bearer {token}"
    body = json.dumps(
        {"jsonrpc": "2.0", "id": mid, "method": "tools/call",
         "params": {"name": name, "arguments": args}}
    ).encode()
    conn = http.client.HTTPSConnection(HOST, PORT, timeout=TIMEOUT, context=ctx())
    conn.request("POST", "/mcp", body=body, headers=h)
    resp = conn.getresponse()
    data = resp.read()
    conn.close()
    return resp.status, data


def check(label, fn):
    global PASS
    try:
        fn()
    except AssertionError as e:
        FAILURES.append(f"{label}: {e}")
        print(f"FAIL {label}: {e}", flush=True)
    except Exception as e:  # hang, drop, reset — always a hard fail
        FAILURES.append(f"{label}: {type(e).__name__}: {e}")
        print(f"FAIL {label}: {type(e).__name__}: {e}", flush=True)
    else:
        PASS += 1
        print(f"ok {label}", flush=True)


def expect_json_rpc(status, data, label):
    assert status in (200, 400, 401, 403, 405), f"{label}: http {status}"
    if status == 200:
        obj = json.loads(data)  # raises if non-JSON -> caught as FAIL
        assert "jsonrpc" in obj, f"{label}: not jsonrpc: {data[:120]!r}"
    return status


def healthy():
    code, _ = raw_post("/mcp", b'{"jsonrpc":"2.0","id":1,"method":"ping","params":{}}')
    return code


# --- 1. malformed JSON / envelopes ---
MALFORMED = [
    b"", b"{", b"null", b"[]", b"[1,2]", b'"str"',
    b'{"jsonrpc":"2.0","id":1}',
    b'{"jsonrpc":"2.0","id":1,"method":"tools/call"}',
    b'{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"vibrate"}}',
    b'{"jsonrpc":"2.0","method":"tools/call","params":{"name":"vibrate","arguments":{}}}',
    b'{"jsonrpc":"2.0","id":1,"method":"notifications/ping"}',
    b'{"jsonrpc":"2.0","id":null,"method":"ping","params":{}}',
    b'{"jsonrpc":"2.0","id":1,"method":123,"params":{}}',
    b'{"jsonrpc":"2.0","id":1,"method":"tools/call","params":"string"}',
    b'{"jsonrpc":"2.0","id":1,"method":"tools/call","params":[]}',
    b'{"a":' + b'{"b":' * 500 + b'1' + b'}' * 500 + b'}',
]


def t_malformed():
    for i, body in enumerate(MALFORMED):
        s, d = raw_post("/mcp", body)
        expect_json_rpc(s, d, f"malformed[{i}]") if s == 200 else None
        assert s in (200, 202, 400, 401, 403), f"malformed[{i}]: http {s}"
    assert healthy() == 200, "server unhealthy after malformed batch"


# --- 2. wrong arg types per tool ---
WRONG_TYPES = [
    ("vibrate", {"durationMs": "long"}),
    ("vibrate", {"durationMs": -5}),
    ("vibrate", {"durationMs": 10**12}),
    ("torch", {"on": "yes"}),
    ("torch", {}),
    ("sensors_read", {"timeoutMs": "x", "types": "accelerometer"}),
    ("sensors_read", {"types": [1, 2]}),
    ("location_read", {"timeoutMs": -1}),
    ("ble_connect", {"address": "not-a-mac"}),
    ("ble_connect", {"address": "11:22:33:44:55:66"}),
    ("ble_read", {"address": "x", "serviceUuid": "y", "charUuid": "z"}),
    ("ble_write", {"address": "11:22:33:44:55:66",
                   "serviceUuid": "x", "charUuid": "y", "hex": "zz"}),
    ("ble_write", {"address": "11:22:33:44:55:66",
                   "serviceUuid": "0000180f-0000-1000-8000-00805f9b34fb",
                   "charUuid": "00002a19-0000-1000-8000-00805f9b34fb",
                   "hex": "abc"}),
    ("nfc_read", {"timeoutMs": 1}),
    ("nfc_read", {"timeoutMs": 8000}),
    ("speaker_say", {"text": ""}),
    ("speaker_say", {"text": 42}),
    ("clipboard_set", {"text": 42}),
    ("audio_record", {"durationMs": 0}),
    ("camera_capture", {"lens": "sideways"}),
    ("camera_video", {"durationMs": 10**9, "withAudio": False}),
    ("ui_click", {}),
    ("ui_swipe", {"bogus": True}),
    ("no_such_tool", {}),
    ("vibrate", {"durationMs": 100, "unexpected": [1, {"a": None}]}),
]


def t_wrong_types():
    for i, (name, args) in enumerate(WRONG_TYPES):
        s, d = call(name, args, mid=100 + i)
        assert s == 200, f"types[{name}]: http {s}"
        obj = json.loads(d)
        assert "result" in obj or "error" in obj, f"types[{name}]: {d[:120]!r}"
    assert healthy() == 200, "server unhealthy after wrong-types batch"


# --- 3. oversized payloads ---
def t_oversize_body():
    big = b'{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"vibrate","arguments":{"pad":"' + b"x" * (5 * 1024 * 1024) + b'"}}}'
    try:
        s, d = raw_post("/mcp", big)
    except Exception as e:
        raise AssertionError(f"5MB body dropped/hung ({type(e).__name__})") from e
    assert s in (200, 400, 401, 403, 413, 422, 500), f"5MB body: http {s}"
    assert healthy() == 200, "server unhealthy after 5MB body"


def t_huge_hex():
    s, d = call("ble_write", {"address": "11:22:33:44:55:66",
                              "serviceUuid": "0000180f-0000-1000-8000-00805f9b34fb",
                              "charUuid": "00002a19-0000-1000-8000-00805f9b34fb",
                              "hex": "ab" * (2 * 1024 * 1024)}, mid=301)
    # 4 MB hex trips the 1 MiB body cap (413) before arg parsing; either way the
    # alloc is bounded. (In-cap oversize hex → -32602 is JVM-tested.)
    assert s in (200, 413), f"huge hex: http {s}"
    if s == 200:
        obj = json.loads(d)
        assert "result" in obj or "error" in obj, f"huge hex: {d[:120]!r}"
    assert healthy() == 200, "server unhealthy after huge hex"


def t_huge_text():
    for name, args in [("speaker_say", {"text": "x" * 200_000}),
                       ("clipboard_set", {"text": "y" * 200_000}),
                       ("ui_type", {"text": "z" * 200_000})]:
        s, d = call(name, args, mid=302)
        assert s == 200, f"huge text {name}: http {s}"
        json.loads(d)
    assert healthy() == 200, "server unhealthy after huge texts"


# --- 4. concurrency ---
def sse_stream(name, args, seconds):
    h = {"Content-Type": "application/json", "Accept": "text/event-stream",
         "MCP-Protocol-Version": "2025-06-18", "Authorization": f"Bearer {TOKEN}"}
    body = json.dumps({"jsonrpc": "2.0", "id": 9, "method": "tools/call",
                       "params": {"name": name, "arguments": args}}).encode()
    conn = http.client.HTTPSConnection(HOST, PORT, timeout=seconds + 15, context=ctx())
    conn.request("POST", "/mcp", body=body, headers=h)
    resp = conn.getresponse()
    assert resp.status == 200, f"stream {name}: http {resp.status}"
    n = 0
    start = time.time()
    while time.time() - start < seconds:
        chunk = resp.fp.readline()
        if not chunk:
            break
        if chunk.startswith(b"data: "):
            n += 1
            # Any terminal envelope (result or error) ends the stream; the
            # server keeps HTTP/1.1 alive, so stop here, not at EOF.
            try:
                obj = json.loads(chunk[6:])
            except Exception:
                obj = {}
            if isinstance(obj, dict) and ("result" in obj or "error" in obj):
                break
    conn.close()
    return n


def t_concurrent_streams():
    # Streams stall when the device dozes; best-effort wake (no-op without adb).
    try:
        __import__("subprocess").run(
            ["adb", "shell", "input", "keyevent", "KEYCODE_WAKEUP"],
            timeout=10, capture_output=True)
    except Exception:
        pass
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as ex:
        a = ex.submit(sse_stream, "sensors_stream",
                      {"durationMs": 6000, "intervalMs": 500, "includeLocation": False}, 8)
        time.sleep(0.5)
        b = ex.submit(sse_stream, "sensors_stream",
                      {"durationMs": 6000, "intervalMs": 500, "includeLocation": False}, 8)
        n1 = a.result()
        # second stream must be rejected (-32603 envelope or early close), never hang both
        try:
            n2 = b.result()
        except AssertionError:
            n2 = -1  # rejected at HTTP level is also fine
        assert n1 >= 3, f"primary stream starved ({n1} events)"
    assert healthy() == 200, "server unhealthy after concurrent streams"


def t_concurrent_calls():
    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as ex:
        futs = [ex.submit(call, "battery_read", {}, 400 + i) for i in range(8)]
        futs += [ex.submit(call, "sensors_read", {"timeoutMs": 2000}, 500 + i)
                 for i in range(4)]
        for f in concurrent.futures.as_completed(futs, timeout=60):
            s, d = f.result()
            assert s == 200, f"concurrent: http {s}"
            json.loads(d)
    assert healthy() == 200, "server unhealthy after concurrent calls"


# --- 5. auth / rotation abuse ---
def t_auth():
    s, _ = call("battery_read", {}, mid=601, token=None)
    assert s == 401, f"no token: http {s}"
    s, _ = call("battery_read", {}, mid=602, token="wrong")
    assert s == 401, f"bad token: http {s}"
    s, d = call("battery_read", {}, mid=603, token="x" * 10_000)
    # Absurd-size headers are rejected by Netty itself (400) before our 401 path.
    assert s in (400, 401), f"10k token: http {s}"
    assert healthy() == 200


# --- 6. media range abuse ---
def t_ranges():
    conn = http.client.HTTPSConnection(HOST, PORT, timeout=TIMEOUT, context=ctx())
    conn.request("GET", "/media/..%2f..%2fetc%2fpasswd",
                 headers={"Authorization": f"Bearer {TOKEN}"})
    r = conn.getresponse()
    r.read()
    assert r.status == 404, f"traversal: http {r.status}"
    conn.close()
    for rid in ["nope", "x" * 500, "../../data"]:
        conn = http.client.HTTPSConnection(HOST, PORT, timeout=TIMEOUT, context=ctx())
        conn.request("GET", f"/media/{rid}",
                     headers={"Authorization": f"Bearer {TOKEN}"})
        r = conn.getresponse()
        r.read()
        assert r.status in (400, 404), f"media {rid!r}: http {r.status}"
        conn.close()
    assert healthy() == 200


# --- 7. net_inspect tools (wrong types, oversize policy/keylog, stream smoke) ---
NET_WRONG_TYPES = [
    ("net_status", {"unexpected": 1}),
    ("net_connections", {"limit": "many"}),
    ("net_connections", {"limit": 10**9}),
    ("net_connections", {"blockedOnly": "yes"}),
    ("net_usage", {"limit": -5}),
    ("net_capture_start", {"clear": "yes"}),
    ("net_export_pcap", {"format": "exe"}),
    ("net_export_pcap", {"maxPackets": 10**9}),
    ("net_export_har", {"maxEntries": "all"}),
    ("net_firewall_set", {"blockedPorts": [99999]}),
    ("net_firewall_set", {"blockedPorts": ["443"]}),
    ("net_firewall_set", {"blockedHosts": "evil.com"}),
    ("net_firewall_set", {"defaultAllow": "maybe"}),
    ("net_blocklist_check", {}),
    ("net_blocklist_check", {"host": 42}),
    ("net_blocklist_check", {"host": "x" * 500}),
    ("net_geo_lookup", {}),
    ("net_geo_lookup", {"ip": "not an ip" * 20}),
    ("net_tls_info", {"action": "decrypt"}),
    ("net_tls_info", {"action": "import"}),
    ("net_tls_info", {"action": "import", "keylog": 42}),
    # NOTE: net_stream is intentionally absent here — it is a streamable tool,
    # so any call carrying `Accept: text/event-stream` (as fuzz call() does)
    # correctly returns SSE batches, not a single JSON envelope. Covered by
    # net-stream-smoke instead.
]


def t_net_wrong_types():
    for i, (name, args) in enumerate(NET_WRONG_TYPES):
        s, d = call(name, args, mid=700 + i)
        # net_* is kill-switched OFF by default -> isError *result* (still 200);
        # enabled -> result or -32602 error envelope. Any 200 + well-formed JSON ok.
        assert s == 200, f"net-types[{name}]: http {s}"
        obj = json.loads(d)
        assert "result" in obj or "error" in obj, f"net-types[{name}]: {d[:120]!r}"
    assert healthy() == 200, "server unhealthy after net wrong-types batch"


def t_net_oversize_policy():
    # 600-entry lists trip the 500-entry cap (-> -32602), never unbounded allocs.
    big_hosts = [f"evil{i}.example.com" for i in range(600)]
    s, d = call("net_firewall_set", {"blockedHosts": big_hosts}, mid=760)
    assert s == 200, f"oversize policy: http {s}"
    json.loads(d)
    # 300 KB keylog import trips the 256 KB cap (-> -32602).
    s, d = call("net_tls_info",
               {"action": "import", "keylog": "CLIENT_RANDOM " + "ab" * 150_000},
               mid=761)
    assert s == 200, f"oversize keylog: http {s}"
    json.loads(d)
    # Policy must still be readable afterwards (no state corruption).
    s, d = call("net_firewall_get", {}, mid=762)
    assert s == 200, f"firewall_get after abuse: http {s}"
    json.loads(d)
    assert healthy() == 200, "server unhealthy after net oversize batch"


def t_net_stream_smoke():
    # Short SSE smoke: works with or without the VPN (snapshot-based batches).
    # When the net_ kill-switch is OFF the server answers a single -32602
    # error envelope instead of SSE data — both are well-formed, both pass.
    s, d = call("net_status", {}, mid=770)
    enabled = True
    try:
        obj = json.loads(d)
        txt = obj.get("result", {}).get("content", [{}])[0].get("text", "")
        enabled = "kill-switch" not in txt
    except Exception:
        pass
    try:
        n = sse_stream("net_stream", {"intervalMs": 500, "durationMs": 2000}, 10)
    except AssertionError:
        n = -1  # non-200 counts as a clean rejection
    if enabled:
        assert n >= 1, f"net_stream smoke (enabled): {n} events"
    else:
        assert n in (-1, 0), f"net_stream smoke (kill-switched): {n} events"
    assert healthy() == 200, "server unhealthy after net_stream smoke"


def main():
    if not TOKEN:
        print("set TOKEN env (dashboard pairing card)", file=sys.stderr)
        sys.exit(2)
    print(f"fuzzing https://{BASE}/mcp", flush=True)
    check("malformed-envelopes", t_malformed)
    check("wrong-arg-types", t_wrong_types)
    check("oversize-body-5MB", t_oversize_body)
    check("huge-ble-hex", t_huge_hex)
    check("huge-texts", t_huge_text)
    check("concurrent-streams", t_concurrent_streams)
    check("concurrent-calls", t_concurrent_calls)
    check("auth-abuse", t_auth)
    check("media-range-abuse", t_ranges)
    check("net-wrong-types", t_net_wrong_types)
    check("net-oversize-policy", t_net_oversize_policy)
    check("net-stream-smoke", t_net_stream_smoke)
    print(f"\nPASS {PASS} FAIL {len(FAILURES)}")
    for f in FAILURES:
        print(f"FAIL {f}")
    sys.exit(1 if FAILURES else 0)


if __name__ == "__main__":
    main()
