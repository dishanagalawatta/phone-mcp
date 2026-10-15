#!/usr/bin/env python3
"""phone-mcp-smoke.py — harness-side smoke test for Phone MCP (stdlib only).

Validates Bearer auth + TLS handling from the agent host without any
harness installed: initialize → tools/list → tools/call battery_read →
tools/call device_info. Exits 0 on PASS, 1 on FAIL.

Usage:
    python3 scripts/phone-mcp-smoke.py --url https://192.168.1.50:8080/mcp \\
        --token <from-dashboard> [--sha256 <hex>] [--insecure] [--timeout 35]

    # USB fallback first: adb forward tcp:8080 tcp:8080
    # then --url https://127.0.0.1:8080/mcp (or just pass --usb)
"""

import argparse
import hashlib
import json
import ssl
import sys
import urllib.request
import urllib.error
import urllib.parse

PROTOCOL = "2025-06-18"


def normalize_fp(value: str) -> str:
    return "".join(c for c in (value or "").lower() if c in "0123456789abcdef")


_USB_HOSTS = ("127.0.0.1", "localhost", "::1")
_USB_CONN_MARKERS = (
    "refused", "timed out", "timeout", "reset by peer", "connection reset",
    "closed", "unreachable", "failed to connect",
)


def _is_loopback_url(url: str) -> bool:
    try:
        host = (urllib.parse.urlparse(url or "").hostname or "").lower()
    except Exception:
        return False
    return host in _USB_HOSTS


def _usb_port(url: str) -> int:
    try:
        return urllib.parse.urlparse(url or "").port or 8080
    except ValueError:
        return 8080


def _rewrite_url_to_loopback(url: str, port: int = 8080) -> str:
    """Rewrite *url*'s host to 127.0.0.1, keeping scheme/port/path."""
    try:
        parts = urllib.parse.urlparse(url or "")
    except Exception:
        return f"https://127.0.0.1:{port}/mcp"
    if not parts.hostname:
        return f"https://127.0.0.1:{port}/mcp"
    new_port = port or parts.port or 8080
    scheme = parts.scheme if parts.scheme in ("https", "http") else "https"
    path = parts.path or "/mcp"
    return urllib.parse.urlunparse(
        parts._replace(scheme=scheme, netloc=f"127.0.0.1:{new_port}", path=path)
    )


def rpc(url, body, token, protocol, timeout, ctx):
    payload = json.dumps(body).encode()
    headers = {
        "Content-Type": "application/json",
        "Accept": "application/json, text/event-stream",
        "Authorization": f"Bearer {token}",
    }
    if body.get("method") not in ("initialize",) and not str(body.get("method", "")).startswith(
        "notifications/"
    ):
        headers["MCP-Protocol-Version"] = protocol
    req = urllib.request.Request(url, data=payload, headers=headers, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=timeout, context=ctx) as res:  # noqa: S310
            raw = res.read().decode("utf-8", "replace")
            ctype = res.headers.get("Content-Type", "")
            if "text/event-stream" in ctype:
                for chunk in raw.split("\n\n"):
                    for line in chunk.split("\n"):
                        if line.strip().startswith("data:"):
                            try:
                                return json.loads(line.strip()[5:].strip()), None
                            except json.JSONDecodeError:
                                continue
                return None, "empty SSE stream"
            return json.loads(raw), None
    except urllib.error.HTTPError as e:
        try:
            detail = e.read().decode()[:300]
        except Exception:
            detail = ""
        return None, f"HTTP {e.code} {detail}"
    except Exception as e:  # noqa: BLE001
        msg = str(e)
        if _is_loopback_url(url) and any(m in msg.lower() for m in _USB_CONN_MARKERS):
            port = _usb_port(url)
            msg += (f" — USB loopback unreachable (run: adb forward tcp:{port} "
                    f"tcp:{port} (or scripts/usb-mcp.sh setup))")
        return None, msg


def main() -> int:
    ap = argparse.ArgumentParser(description="Phone MCP harness-side smoke test")
    ap.add_argument("--url", default="", help="e.g. https://192.168.1.50:8080/mcp")
    ap.add_argument("--usb", action="store_true",
                    help="use USB loopback (rewrite --url host to 127.0.0.1, "
                         "or default to https://127.0.0.1:8080/mcp when --url is omitted)")
    ap.add_argument("--port", type=int, default=8080,
                    help="USB port for --usb default URL / forward hint (default: 8080)")
    ap.add_argument("--token", required=True, help="Bearer token from dashboard pairing card")
    ap.add_argument("--sha256", default="", help="Pinned TLS fingerprint (hex, colons ok)")
    ap.add_argument("--insecure", action="store_true", help="Skip TLS verify (trusted LAN only)")
    ap.add_argument("--timeout", type=float, default=20.0)
    args = ap.parse_args()

    url = (args.url or "").strip()
    if args.usb:
        url = _rewrite_url_to_loopback(url, args.port) if url else f"https://127.0.0.1:{args.port}/mcp"
    if not url:
        ap.error("--url is required (or pass --usb for the USB loopback default)")
        return 2
    args.url = url

    pin = normalize_fp(args.sha256)
    ctx = ssl.create_default_context()
    ctx.set_alpn_protocols(["http/1.1"])  # phone Netty drops ALPN-less connections
    if args.url.startswith("https://") and (pin or args.insecure):
        # A pin must authenticate the connection, not merely be compared
        # afterwards: capture-then-verify via the shared bridge helpers, then
        # chain-verify every probe against the pinned leaf (in-memory anchor).
        import importlib.util as _ilu
        import os as _os

        _p = _os.path.join(
            _os.path.dirname(_os.path.abspath(__file__)), "..", "bridges", "phone-mcp-bridge.py"
        )
        _spec = _ilu.spec_from_file_location("phone_mcp_bridge", _p)
        _bridge = _ilu.module_from_spec(_spec)
        _spec.loader.exec_module(_bridge)
        if pin and not args.insecure:
            parts = urllib.parse.urlparse(args.url)
            try:
                _der = _bridge.preflight_der(parts.hostname or "", parts.port or 443, args.timeout)
            except Exception as e:  # noqa: BLE001
                print(f"[FAIL] tls capture: {e}")
                return 1
            if not _bridge.pin_matches(_der or b"", pin):
                _actual = hashlib.sha256(_der).hexdigest() if _der else ""
                print(f"[FAIL] fingerprint mismatch (got {_actual[:16]}…)")
                print("re-verify the dashboard SHA-256 (phone may have rotated its identity).")
                return 1
            ctx.check_hostname = False
            ctx.verify_mode = ssl.CERT_REQUIRED
            ctx.load_verify_locations(cadata=ssl.DER_cert_to_PEM_cert(_der))
        else:
            ctx.check_hostname = False
            ctx.verify_mode = ssl.CERT_NONE

    checks = []
    init = {
        "jsonrpc": "2.0",
        "id": 1,
        "method": "initialize",
        "params": {
            "protocolVersion": PROTOCOL,
            "capabilities": {},
            "clientInfo": {"name": "phone-mcp-smoke", "version": "0.1"},
        },
    }
    resp, err = rpc(args.url, init, args.token, PROTOCOL, args.timeout, ctx)
    ok = err is None and isinstance(resp, dict) and "result" in resp
    checks.append(("initialize", ok, err or f"server={resp.get('result', {}).get('serverInfo', {})}" if ok else err))

    resp2, err2 = rpc(
        args.url, {"jsonrpc": "2.0", "id": 2, "method": "tools/list", "params": {}},
        args.token, PROTOCOL, args.timeout, ctx,
    )
    tools = (resp2 or {}).get("result", {}).get("tools", []) if err2 is None else []
    ok2 = err2 is None and len(tools) >= 10
    checks.append(("tools/list", ok2, err2 or f"{len(tools)} tools"))

    resp3, err3 = rpc(
        args.url,
        {"jsonrpc": "2.0", "id": 3, "method": "tools/call",
         "params": {"name": "battery_read", "arguments": {}}},
        args.token, PROTOCOL, args.timeout, ctx,
    )
    ok3 = err3 is None and not (resp3 or {}).get("result", {}).get("isError", True)
    checks.append(("tools/call battery_read", ok3, err3 or "isError=false"))

    resp4, err4 = rpc(
        args.url,
        {"jsonrpc": "2.0", "id": 4, "method": "tools/call",
         "params": {"name": "device_info", "arguments": {}}},
        args.token, PROTOCOL, args.timeout, ctx,
    )
    tls = ""
    if err4 is None:
        try:
            tls = json.loads(resp4["result"]["content"][0]["text"]).get("tlsSha256", "")
        except Exception:  # noqa: BLE001
            tls = ""
    ok4 = err4 is None
    hint = ""
    if ok4 and pin and tls and normalize_fp(tls) != pin:
        hint = f"WARNING: device tlsSha256 != pinned ({tls[:16]}…)"
    checks.append(("tools/call device_info", ok4, err4 or hint or "ok"))

    failed = False
    for name, ok, detail in checks:
        print(f"[{'PASS' if ok else 'FAIL'}] {name}: {detail}")
        failed = failed or not ok
    if failed:
        print("SMOKE FAIL — check URL/token/fingerprint, dashboard server state, "
              "and LAN/adb forward (scripts/usb-mcp.sh setup).")
        return 1
    print("SMOKE PASS — harness configs in config/harness-examples/ will connect.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
