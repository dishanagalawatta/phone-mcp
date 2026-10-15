#!/usr/bin/env python3
"""phone-mcp-bridge.py — stdio <-> Streamable HTTP bridge for Phone MCP.

Stdlib-only Python twin of phone-mcp-bridge.mjs (for hosts without Node).
Speaks MCP stdio on stdin/stdout, forwards each JSON-RPC envelope to
POST $PHONE_MCP_URL with Bearer auth + optional SHA-256 TOFU pin.

Env: PHONE_MCP_URL (required), PHONE_MCP_TOKEN (required),
     PHONE_MCP_SHA256 (optional hex, colons ok),
     PHONE_MCP_CAFILE (optional PEM from scripts/export-ca.sh — real chain
       verification against the pinned leaf; combines with SHA256 pin),
     PHONE_MCP_INSECURE=1 (optional, LAN-trusted only),
     PHONE_MCP_PROTOCOL (default 2025-06-18),
     PHONE_MCP_TIMEOUT_MS (default 35000, per-call ceiling),
     PHONE_MCP_FAST_MS (default 15000, budget for calls without duration args),
     PHONE_MCP_MARGIN_MS (default 10000, headroom over durationMs/timeoutMs).

Usage:
    PHONE_MCP_URL=https://192.168.1.50:8080/mcp PHONE_MCP_TOKEN=... \\
        python3 bridges/phone-mcp-bridge.py

USB (same TLS fingerprint + auth as LAN — `adb reverse` is wrong here):
    adb forward tcp:8080 tcp:8080  # then PHONE_MCP_URL=https://127.0.0.1:8080/mcp
"""

import hashlib
import json
import os
import ssl
import sys
import urllib.request
import urllib.error
import urllib.parse

PROTOCOL_DEFAULT = "2025-06-18"


def normalize_fingerprint(value: str) -> str:
    return "".join(c for c in (value or "").lower() if c in "0123456789abcdef")


def grouped_fingerprint(value: str) -> str:
    h = normalize_fingerprint(value)
    return ":".join(h[i : i + 2] for i in range(0, len(h), 2)).upper()


def build_headers(token: str, protocol: str, method) -> dict:
    headers = {
        "Content-Type": "application/json",
        "Accept": "application/json, text/event-stream",
        "Authorization": f"Bearer {token}",
    }
    if method != "initialize" and not str(method or "").startswith("notifications/"):
        headers["MCP-Protocol-Version"] = protocol
    return headers


def parse_sse_data(body: str) -> list:
    out = []
    for chunk in str(body).split("\n\n"):
        for line in chunk.split("\n"):
            t = line.strip()
            if t.startswith("data:"):
                payload = t[5:].strip()
                if payload:
                    out.append(payload)
    return out


def message_duration_ms(msg) -> int:
    """durationMs/timeoutMs carried by capture/scan/stream args, else 0."""
    try:
        params = (msg or {}).get("params", {}) or {}
        args = params.get("arguments", params) or {}
        n = float(args.get("durationMs", args.get("timeoutMs")))
        return int(n) if n > 0 else 0
    except (TypeError, ValueError, AttributeError):
        return 0


def effective_timeout(msg, ceiling_ms: int, fast_ms: int, margin_ms: int) -> int:
    """Per-call budget in ms (mirrors the Node twin's effectiveTimeout)."""
    dur = message_duration_ms(msg)
    want = dur + margin_ms if dur > 0 else fast_ms
    return max(1, min(ceiling_ms, want))


def emit(obj: dict) -> None:
    sys.stdout.write(json.dumps(obj) + "\n")
    sys.stdout.flush()


def fail(req_id, code: int, message: str) -> None:
    emit({"jsonrpc": "2.0", "id": req_id, "error": {"code": code, "message": message}})


def log(msg: str) -> None:
    sys.stderr.write(f"[phone-mcp-bridge] {msg}\n")
    sys.stderr.flush()


def _is_loopback_url(url: str) -> bool:
    """True when *url* targets loopback (USB `adb forward` endpoint)."""
    try:
        host = (urllib.parse.urlparse(url or "").hostname or "").lower()
    except Exception:
        return False
    return host in ("127.0.0.1", "localhost", "::1")


def _loopback_port(url: str) -> int:
    try:
        return urllib.parse.urlparse(url or "").port or 8080
    except ValueError:
        return 8080


_CONN_HINT_MARKERS = (
    "refused", "timed out", "timeout", "reset by peer", "connection reset",
    "closed", "unreachable", "failed to connect",
)


def pin_matches(der: bytes, pin: str) -> bool:
    """True when the SHA-256 of the presented leaf DER equals the pinned hex."""
    if not der:
        return False
    return hashlib.sha256(der).hexdigest() == normalize_fingerprint(pin)


def preflight_der(host: str, port: int, timeout_s: float) -> bytes | None:
    """Capture the presented leaf cert via a throwaway TLS handshake.

    Stdlib-only TOFU capture: CERT_NONE here is safe because the caller
    hash-compares the DER against the dashboard pin before trusting it.
    """
    import socket as _socket

    ctx = ssl.create_default_context()
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE
    ctx.set_alpn_protocols(["http/1.1"])  # see _ssl_context: Netty needs ALPN
    with _socket.create_connection((host, port), timeout=timeout_s) as sock:
        with ctx.wrap_socket(sock, server_hostname=host) as tls:
            return tls.getpeercert(binary_form=True)
    return None


def _ssl_context(pin: str, insecure: bool, cafile: str = "") -> ssl.SSLContext:
    """Mirror of the Node twin's resolveTls (see tls_options)."""
    opts = tls_options(pin, insecure, cafile)
    ctx = ssl.create_default_context()
    ctx.check_hostname = opts["check_hostname"]
    ctx.verify_mode = opts["verify_mode"]
    # The phone's Netty engine drops ALPN-less connections on first bytes
    # (curl only works because it offers h2,http/1.1) — always negotiate
    # plain HTTP/1.1. Harmless against servers that ignore ALPN.
    ctx.set_alpn_protocols(["http/1.1"])
    if opts.get("cafile"):
        ctx.load_verify_locations(cafile=opts["cafile"])
    return ctx


def tls_options(pin: str, insecure: bool, cafile: str = "") -> dict:
    """Pure TLS-mode resolution (unit-testable; Node twin mirrors it)."""
    has_pin = bool(normalize_fingerprint(pin))
    has_ca = bool(cafile)
    if insecure:
        return {"verify_mode": ssl.CERT_NONE, "check_hostname": False}
    if has_ca:
        # Chain verifies against the exported leaf (TOFU trust anchor);
        # hostname check skipped — DHCP churn breaks SAN matching.
        return {"verify_mode": ssl.CERT_REQUIRED, "check_hostname": False, "cafile": cafile}
    if has_pin:
        return {"verify_mode": ssl.CERT_NONE, "check_hostname": False}
    return {"verify_mode": ssl.CERT_REQUIRED, "check_hostname": True}


def post_mcp(url: str, payload: bytes, headers: dict, timeout_s: float, pin: str, insecure: bool,
             cafile: str = ""):
    req = urllib.request.Request(url, data=payload, headers=headers, method="POST")
    ctx = _ssl_context(pin, insecure, cafile)
    if pin and url.startswith("https://"):
        # TOFU pin check via a capture handshake: the presented leaf hash must
        # equal the dashboard pin. Without a cafile the data connection below
        # is then chain-verified against that exact leaf (in-memory anchor),
        # so both legs are authenticated; with a cafile the chain check
        # against the export-verified leaf covers the data leg.
        parts = urllib.parse.urlparse(url)
        host, port = parts.hostname or "", parts.port or 443
        der = preflight_der(host, port, timeout_s)
        if not pin_matches(der or b"", pin):
            actual = hashlib.sha256(der).hexdigest() if der else ""
            raise RuntimeError(
                "TLS fingerprint mismatch: expected "
                f"{grouped_fingerprint(pin)}, got {grouped_fingerprint(actual) or '(none)'}"
            )
        if not cafile:
            ctx.load_verify_locations(cadata=ssl.DER_cert_to_PEM_cert(der))
    try:
        with urllib.request.urlopen(req, timeout=timeout_s, context=ctx) as res:  # noqa: S310
            body = res.read().decode("utf-8", "replace")
            return res.status, res.headers.get("Content-Type", ""), body
    except urllib.error.HTTPError as e:
        try:
            body = e.read().decode("utf-8", "replace")
        except Exception:
            body = ""
        return e.code, e.headers.get("Content-Type", ""), body


def handle_line(line: str, cfg: dict) -> None:
    try:
        msg = json.loads(line)
    except json.JSONDecodeError:
        fail(None, -32700, "Parse error")
        return
    req_id = msg.get("id")
    method = msg.get("method", "")
    headers = build_headers(cfg["token"], cfg["protocol"], method)
    budget_s = effective_timeout(msg, cfg["ceiling_ms"], cfg["fast_ms"], cfg["margin_ms"]) / 1000.0
    try:
        status, content_type, body = post_mcp(
            cfg["url"], line.encode(), headers, budget_s, cfg["pin"], cfg["insecure"],
            cfg.get("cafile", ""),
        )
    except RuntimeError as e:
        fail(req_id, -32603, str(e))
        return
    except Exception as e:  # network / TLS / timeout
        detail = str(e)
        lowered = detail.lower()
        loopback = _is_loopback_url(cfg["url"])
        port = _loopback_port(cfg["url"])
        if "unauthorized" in lowered or "offline" in lowered:
            detail += (" — phone is unauthorized/offline: run `adb devices`, "
                       "accept the on-phone RSA authorization prompt, then retry")
        elif "certificate" in lowered or "self-signed" in lowered or "verify failed" in lowered:
            if loopback:
                detail += (
                    " — verify the dashboard SHA-256 once, then set PHONE_MCP_SHA256 "
                    f"(USB loopback via `adb forward tcp:{port} tcp:{port}` uses the "
                    "same TLS fingerprint + auth as LAN)"
                )
            else:
                detail += (
                    " — verify the dashboard SHA-256 once, then set PHONE_MCP_SHA256 "
                    "(or PHONE_MCP_INSECURE=1 on trusted LAN)"
                )
        elif loopback and any(m in lowered for m in _CONN_HINT_MARKERS):
            detail += (f" — USB loopback unreachable (run: adb forward tcp:{port} "
                       f"tcp:{port} (or scripts/usb-mcp.sh setup))")
        elif "timed out" in lowered or "timeout" in lowered:
            detail += (
                " — tool budget exceeded (raise PHONE_MCP_TIMEOUT_MS/FAST_MS/MARGIN_MS "
                "if legitimate)"
            )
        fail(req_id, -32603, f"Upstream unreachable: {detail}")
        return
    if status in (401, 403):
        fail(req_id, -32001, "Missing or invalid bearer token (check PHONE_MCP_TOKEN)")
        return
    if status == 413:
        fail(req_id, -32600, "Body too large")
        return
    if status == 202 or not body:
        return  # notification ack — nothing to emit on stdio
    if "text/event-stream" in content_type:
        payloads = parse_sse_data(body)
        if not payloads:
            fail(req_id, -32603, "Empty SSE stream from server")
            return
        for payload in payloads:
            try:
                emit(json.loads(payload))
            except json.JSONDecodeError:
                continue
        return
    try:
        emit(json.loads(body))
    except json.JSONDecodeError:
        fail(req_id, -32603, f"Upstream returned HTTP {status} (non-JSON)")


def main() -> int:
    url = os.environ.get("PHONE_MCP_URL", "")
    token = os.environ.get("PHONE_MCP_TOKEN", "")
    pin = normalize_fingerprint(os.environ.get("PHONE_MCP_SHA256", ""))
    cafile = os.environ.get("PHONE_MCP_CAFILE", "")
    insecure = os.environ.get("PHONE_MCP_INSECURE", "") == "1"
    protocol = os.environ.get("PHONE_MCP_PROTOCOL", PROTOCOL_DEFAULT)

    def _ms(name: str, default: float) -> int:
        try:
            return int(float(os.environ.get(name, str(default))))
        except ValueError:
            return int(default)

    ceiling_ms = _ms("PHONE_MCP_TIMEOUT_MS", 35000)
    fast_ms = _ms("PHONE_MCP_FAST_MS", 15000)
    margin_ms = _ms("PHONE_MCP_MARGIN_MS", 10000)
    if not url or not token:
        fail(None, -32603, "phone-mcp-bridge: set PHONE_MCP_URL and PHONE_MCP_TOKEN")
        return 1
    if cafile:
        # Fail fast on unreadable CA material (never its contents in output).
        try:
            _ssl_context(pin, insecure, cafile)
        except Exception as e:  # noqa: BLE001
            fail(None, -32603, f"phone-mcp-bridge: unreadable PHONE_MCP_CAFILE ({e})")
            return 1
    if url.startswith("http://") and True:
        host = url.split("/")[2].split(":")[0].strip("[]").lower()
        if host not in ("localhost", "127.0.0.1", "::1"):
            fail(None, -32603, "phone-mcp-bridge: http:// only allowed for loopback")
            return 1
    cfg = {"url": url, "token": token, "pin": pin, "insecure": insecure,
           "cafile": cafile, "protocol": protocol,
           "ceiling_ms": ceiling_ms, "fast_ms": fast_ms, "margin_ms": margin_ms}
    log(f"bridge → {url} (pin={'sha256:' + pin[:12] + '…' if pin else 'none'}, "
        f"ca={cafile or 'none'}, insecure={insecure})")
    for line in sys.stdin:
        if line.strip():
            handle_line(line.strip(), cfg)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
