"""MCP Streamable-HTTP protocol layer (stdlib only).

One JSON-RPC envelope per POST; SSE ``data:`` envelopes fan out
(notifications first, final ``result``/``error`` last). Diagnostics go to
stderr — stdout carries only JSON-RPC in ``serve`` mode.
"""

from __future__ import annotations

import json
import ssl
import sys
import urllib.error
import urllib.parse
import urllib.request
from typing import Any, Callable

from . import tls as _tls
from . import usb as _usb

MCP_PROTOCOL_VERSION = "2025-06-18"


def log(msg: str) -> None:
    sys.stderr.write(f"[phone-mcp] {msg}\n")
    sys.stderr.flush()


def build_headers(token: str, protocol: str, method: str | None) -> dict:
    headers = {
        "Content-Type": "application/json",
        "Accept": "application/json, text/event-stream",
        "Authorization": f"Bearer {token}",
    }
    # Server skips the version gate for `initialize` + notifications/*;
    # sending it unconditionally is still valid — except strict harnesses
    # replay `initialize` verbatim, so keep parity with curl docs.
    if method != "initialize" and not str(method or "").startswith("notifications/"):
        headers["MCP-Protocol-Version"] = protocol
    return headers


def parse_sse_data(body: str) -> list[str]:
    out: list[str] = []
    for chunk in str(body).split("\n\n"):
        for line in chunk.split("\n"):
            t = line.strip()
            if t.startswith("data:"):
                payload = t[5:].strip()
                if payload:
                    out.append(payload)
    return out


def message_duration_ms(msg: Any) -> int:
    """durationMs/timeoutMs carried by capture/scan/stream args, else 0."""
    try:
        params = (msg or {}).get("params", {}) or {}
        args = params.get("arguments", params) or {}
        n = float(args.get("durationMs", args.get("timeoutMs")))
        return int(n) if n and n > 0 else 0
    except (TypeError, ValueError, AttributeError):
        return 0


def effective_timeout(msg: Any, ceiling_ms: int, fast_ms: int, margin_ms: int) -> int:
    """Per-call budget: duration+margin for captures/streams, fast otherwise.

    Always bounded by the ceiling so absurd durations fail fast.
    """
    dur = message_duration_ms(msg)
    want = dur + margin_ms if dur > 0 else fast_ms
    return max(1, min(ceiling_ms, want))


def _data_context(cfg) -> ssl.SSLContext:
    """SSL context for the data leg (TOFU-anchored when pinned)."""
    if cfg.sha256 and cfg.url.startswith("https://") and not cfg.insecure:
        # Authenticate via capture-then-anchor: hash-compare first, then
        # chain-verify the data leg against the exact leaf (or the CA file
        # when the user chained `export-ca`).
        timeout_s = max(1.0, cfg.timeout_ms / 1000.0)
        if cfg.cafile:
            der, _ = _tls.verify_pin_against_host(cfg.host, cfg.port, cfg.sha256, timeout_s)
            ctx = _tls.ssl_context(cfg.sha256, cfg.insecure, cfg.cafile)
            # Extra pin leg: the capture above already hash-compared; the
            # chain check below verifies against the exported leaf.
            _ = der
            return ctx
        der, _ = _tls.verify_pin_against_host(cfg.host, cfg.port, cfg.sha256, timeout_s)
        assert der
        return _tls.anchored_context(der)
    return _tls.ssl_context(cfg.sha256, cfg.insecure, cfg.cafile)


def post_mcp(
    cfg,
    payload: bytes,
    headers: dict,
    timeout_s: float,
    urlopen: Callable | None = None,
) -> tuple[int, str, str]:
    """POST one envelope; resolves raw (status, content_type, body).

    ``urlopen`` is injectable for unit tests (defaults to urllib).
    """
    req = urllib.request.Request(cfg.url, data=payload, headers=headers, method="POST")
    opener = urlopen or (lambda r, **kw: urllib.request.urlopen(r, timeout=timeout_s, context=_data_context(cfg)))  # noqa: E731
    try:
        # When a test injects urlopen, it takes (req, timeout) only.
        try:
            res = opener(req, timeout=timeout_s)
        except TypeError:
            res = opener(req)
        with res as response:
            status = getattr(response, "status", 200) or 200
            try:
                ctype = response.headers.get("Content-Type", "")
            except Exception:
                ctype = ""
            body = response.read().decode("utf-8", "replace")
            return status, ctype, body
    except urllib.error.HTTPError as e:
        try:
            body = e.read().decode("utf-8", "replace")
        except Exception:
            body = ""
        try:
            ctype = e.headers.get("Content-Type", "") if e.headers else ""
        except Exception:
            ctype = ""
        return e.code, ctype, body


def rpc(
    cfg,
    body: dict,
    timeout_s: float | None = None,
    urlopen: Callable | None = None,
) -> tuple[dict | None, str | None]:
    """Single JSON-RPC round trip. Returns (response, error)."""
    budget_s = timeout_s if timeout_s is not None else max(1.0, cfg.timeout_ms / 1000.0)
    headers = build_headers(cfg.token, cfg.protocol, body.get("method"))
    payload = json.dumps(body).encode()
    try:
        status, ctype, raw = post_mcp(cfg, payload, headers, budget_s, urlopen=urlopen)
    except RuntimeError as e:  # fingerprint mismatch (fail-closed, no fallback)
        return None, str(e)
    except Exception as e:  # noqa: BLE001 — network / TLS / timeout
        detail = _actionable_transport_error(str(e), cfg.url)
        return None, detail
    if status in (401, 403):
        return None, "HTTP 401 Missing or invalid bearer token (check PHONE_MCP_TOKEN)"
    if status == 413:
        return None, "HTTP 413 Body too large"
    if status == 202 or not raw:
        return {"jsonrpc": "2.0", "id": body.get("id"), "result": {}}, None
    if "text/event-stream" in (ctype or ""):
        for chunk in raw.split("\n\n"):
            for line in chunk.split("\n"):
                if line.strip().startswith("data:"):
                    try:
                        return json.loads(line.strip()[5:].strip()), None
                    except json.JSONDecodeError:
                        continue
        return None, "empty SSE stream"
    try:
        return json.loads(raw), None
    except json.JSONDecodeError:
        return None, f"Upstream returned HTTP {status} (non-JSON)"


def _actionable_transport_error(detail: str, url: str = "") -> str:
    try:
        if url and _usb.is_loopback_url(url):
            return _usb.actionable_usb_error(detail, url)
    except Exception:  # noqa: BLE001 — loopback sniffing must never fail closed
        pass
    lowered = detail.lower()
    if "fingerprint mismatch" in lowered:
        return detail
    if "certificate" in lowered or "self-signed" in lowered or "verify failed" in lowered:
        return (
            f"{detail} — verify the dashboard SHA-256 once, then set "
            "PHONE_MCP_SHA256 (or PHONE_MCP_INSECURE=1 on trusted LAN)"
        )
    if "timed out" in lowered or "timeout" in lowered:
        return detail + (
            " — tool budget exceeded (raise PHONE_MCP_TIMEOUT_MS/FAST_MS/MARGIN_MS "
            "if legitimate)"
        )
    return f"Upstream unreachable: {detail}"


# ---------------------------------------------------------------------------
# High-level client operations (smoke / list / call / media)
# ---------------------------------------------------------------------------


def _next_id(start: int = 1):
    n = start
    while True:
        yield n
        n += 1


def smoke(cfg, urlopen: Callable | None = None) -> list[tuple[str, bool, str]]:
    """4-probe health check: initialize → tools/list → battery → device_info."""
    ids = _next_id(1)
    timeout_s = max(1.0, cfg.timeout_ms / 1000.0)
    checks: list[tuple[str, bool, str]] = []

    init = {
        "jsonrpc": "2.0",
        "id": next(ids),
        "method": "initialize",
        "params": {
            "protocolVersion": cfg.protocol,
            "capabilities": {},
            "clientInfo": {"name": "phone-mcp-client", "version": "0.1.0"},
        },
    }
    resp, err = rpc(cfg, init, timeout_s, urlopen=urlopen)
    ok = err is None and isinstance(resp, dict) and "result" in resp
    detail = err or f"server={(resp or {}).get('result', {}).get('serverInfo', {})}"
    checks.append(("initialize", ok, detail))

    resp2, err2 = rpc(
        cfg, {"jsonrpc": "2.0", "id": next(ids), "method": "tools/list", "params": {}},
        timeout_s, urlopen=urlopen,
    )
    tools = (resp2 or {}).get("result", {}).get("tools", []) if err2 is None else []
    ok2 = err2 is None and len(tools) >= 10
    checks.append(("tools/list", ok2, err2 or f"{len(tools)} tools"))

    resp3, err3 = rpc(
        cfg,
        {"jsonrpc": "2.0", "id": next(ids), "method": "tools/call",
         "params": {"name": "battery_read", "arguments": {}}},
        timeout_s, urlopen=urlopen,
    )
    ok3 = err3 is None and not (resp3 or {}).get("result", {}).get("isError", True)
    checks.append(("tools/call battery_read", ok3, err3 or "isError=false"))

    resp4, err4 = rpc(
        cfg,
        {"jsonrpc": "2.0", "id": next(ids), "method": "tools/call",
         "params": {"name": "device_info", "arguments": {}}},
        timeout_s, urlopen=urlopen,
    )
    tls_hint = ""
    if err4 is None:
        try:
            tls_val = json.loads(resp4["result"]["content"][0]["text"]).get("tlsSha256", "")
        except Exception:  # noqa: BLE001
            tls_val = ""
        if cfg.sha256 and tls_val and _tls.normalize_fingerprint(tls_val) != cfg.sha256:
            tls_hint = f"WARNING: device tlsSha256 != pinned ({tls_val[:16]}…)"
    checks.append(("tools/call device_info", err4 is None, err4 or tls_hint or "ok"))
    return checks


def tools_list(cfg, urlopen: Callable | None = None) -> dict:
    resp, err = rpc(
        cfg, {"jsonrpc": "2.0", "id": 1, "method": "tools/list", "params": {}},
        urlopen=urlopen,
    )
    if err:
        raise RuntimeError(err)
    if resp is None or "error" in resp:
        raise RuntimeError(json.dumps((resp or {}).get("error", {})))
    return resp.get("result", {})


def tools_call(cfg, name: str, arguments: dict | None = None, urlopen: Callable | None = None) -> dict:
    msg = {
        "jsonrpc": "2.0",
        "id": 1,
        "method": "tools/call",
        "params": {"name": name, "arguments": arguments or {}},
    }
    budget_ms = effective_timeout(msg, cfg.timeout_ms, cfg.fast_ms, cfg.margin_ms)
    resp, err = rpc(cfg, msg, budget_ms / 1000.0, urlopen=urlopen)
    if err:
        raise RuntimeError(err)
    if resp is None or "error" in resp:
        raise RuntimeError(json.dumps((resp or {}).get("error", {})))
    return resp.get("result", {})


def fetch_media(cfg, media_id: str, timeout_s: float | None = None) -> tuple[bytes, str]:
    """GET /media/{id} with Bearer auth. Returns (bytes, mime_type)."""
    base = cfg.url.rsplit("/mcp", 1)[0].rstrip("/")
    url = f"{base}/media/{urllib.parse.quote(media_id, safe='')}"
    req = urllib.request.Request(
        url,
        headers={"Authorization": f"Bearer {cfg.token}"},
        method="GET",
    )
    ctx = _data_context(cfg)
    try:
        with urllib.request.urlopen(req, timeout=timeout_s or 30.0, context=ctx) as res:  # noqa: S310
            return res.read(), res.headers.get("Content-Type", "application/octet-stream")
    except urllib.error.HTTPError as e:
        raise RuntimeError(f"media fetch HTTP {e.code}") from e
    except Exception as e:  # noqa: BLE001 — network / TLS / timeout
        raise RuntimeError(_actionable_transport_error(str(e), cfg.url)) from e


# ---------------------------------------------------------------------------
# stdio bridge loop (harness ↔ phone)
# ---------------------------------------------------------------------------


def emit(obj: dict) -> None:
    sys.stdout.write(json.dumps(obj) + "\n")
    sys.stdout.flush()


def fail(req_id: Any, code: int, message: str) -> None:
    emit({"jsonrpc": "2.0", "id": req_id, "error": {"code": code, "message": message}})


def handle_stdio_line(line: str, cfg) -> None:
    """Forward one harness-side JSON-RPC line to the phone; emit response(s)."""
    try:
        msg = json.loads(line)
    except json.JSONDecodeError:
        fail(None, -32700, "Parse error")
        return
    req_id = msg.get("id") if isinstance(msg, dict) else None
    method = msg.get("method", "") if isinstance(msg, dict) else ""
    headers = build_headers(cfg.token, cfg.protocol, method)
    budget_ms = effective_timeout(msg, cfg.timeout_ms, cfg.fast_ms, cfg.margin_ms)
    try:
        status, ctype, raw = post_mcp(cfg, line.encode(), headers, budget_ms / 1000.0)
    except RuntimeError as e:
        fail(req_id, -32603, str(e))
        return
    except Exception as e:  # noqa: BLE001
        fail(req_id, -32603, _actionable_transport_error(str(e), getattr(cfg, "url", "")))
        return
    if status in (401, 403):
        fail(req_id, -32001, "Missing or invalid bearer token (check PHONE_MCP_TOKEN)")
        return
    if status == 413:
        fail(req_id, -32600, "Body too large")
        return
    if status == 202 or not raw:
        return  # notification ack — nothing to emit on stdio
    if "text/event-stream" in (ctype or ""):
        payloads = parse_sse_data(raw)
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
        emit(json.loads(raw))
    except json.JSONDecodeError:
        fail(req_id, -32603, f"Upstream returned HTTP {status} (non-JSON)")


def serve_forever(cfg) -> int:
    """stdio bridge main loop. stdin: harness; stdout: JSON-RPC only."""
    problems = cfg.validate()
    if problems:
        fail(None, -32603, "phone-mcp: " + "; ".join(problems))
        return 1
    red = cfg.redacted()
    log(f"bridge → {cfg.url} (pin={red['pin']}, ca={red['ca']}, insecure={cfg.insecure})")
    try:
        for line in sys.stdin:
            if line.strip():
                handle_stdio_line(line.strip(), cfg)
    except BrokenPipeError:
        return 0
    except KeyboardInterrupt:
        return 0
    return 0
