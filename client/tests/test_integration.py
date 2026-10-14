"""End-to-end over loopback mocks (plain HTTP + real pinned TLS).

Exercises smoke → list → call → media against a fake phone that speaks
the same JSON-RPC + SSE + /media shapes as the real server, including the
security-critical capture-then-anchor TOFU path over genuine TLS.
"""

import hashlib
import json
import shutil
import ssl
import subprocess
import sys
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, HTTPServer

from phone_mcp_client import config as C
from phone_mcp_client import protocol as P
from phone_mcp_client import tls as T


class MockPhone(BaseHTTPRequestHandler):
    TOKEN = "test-token"

    def _send(self, code, body, ctype="application/json"):
        raw = body.encode() if isinstance(body, str) else body
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def do_POST(self):  # noqa: N802
        if self.headers.get("Authorization") != f"Bearer {self.TOKEN}":
            return self._send(401, "unauthorized", "text/plain")
        length = int(self.headers.get("Content-Length", 0))
        msg = json.loads(self.rfile.read(length) or b"{}")
        method = msg.get("method")
        mid = msg.get("id", 1)
        if method == "initialize":
            return self._send(200, json.dumps({
                "jsonrpc": "2.0", "id": mid,
                "result": {"protocolVersion": "2025-06-18",
                           "serverInfo": {"name": "mock-phone", "version": "0"}}}))
        if method == "tools/list":
            tools = [{"name": n, "description": f"{n} tool"}
                     for n in ["battery_read", "device_info", "vibrate",
                               "sensors_read", "location_read", "audio_record",
                               "camera_capture", "speaker_say", "torch",
                               "clipboard_get", "device_info", "net_status"]]
            return self._send(200, json.dumps({"jsonrpc": "2.0", "id": mid,
                                               "result": {"tools": tools}}))
        if method == "tools/call":
            name = (msg.get("params") or {}).get("name", "")
            if name == "device_info":
                text = json.dumps({"tlsSha256": "00" * 32, "model": "Mock"})
            elif name == "battery_read":
                text = json.dumps({"level": 87})
            else:
                text = json.dumps({"ok": True})
            return self._send(200, json.dumps({
                "jsonrpc": "2.0", "id": mid,
                "result": {"content": [{"type": "text", "text": text}],
                           "isError": False}}))
        if method == "ping":
            return self._send(200, json.dumps({"jsonrpc": "2.0", "id": mid,
                                               "result": {}}))
        return self._send(200, json.dumps(
            {"jsonrpc": "2.0", "id": mid,
             "error": {"code": -32601, "message": "unknown"}}))

    def do_GET(self):  # noqa: N802
        if self.path.startswith("/media/"):
            if self.headers.get("Authorization") != f"Bearer {self.TOKEN}":
                return self._send(401, "unauthorized", "text/plain")
            return self._send(200, b"\x89PNG-mock-bytes", "image/png")
        return self._send(404, "nope", "text/plain")

    def log_message(self, *a):  # silence test output
        pass


class IntegrationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = HTTPServer(("127.0.0.1", 0), MockPhone)
        cls.port = cls.server.server_address[1]
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.thread.join(timeout=5)

    def _cfg(self):
        return C.ClientConfig(url=f"http://127.0.0.1:{self.port}/mcp", token="test-token")

    def test_smoke_4pass(self):
        checks = P.smoke(self._cfg())
        self.assertEqual(len(checks), 4)
        for name, ok, detail in checks:
            self.assertTrue(ok, f"{name}: {detail}")

    def test_list_call(self):
        cfg = self._cfg()
        result = P.tools_list(cfg)
        self.assertGreaterEqual(len(result["tools"]), 10)
        called = P.tools_call(cfg, "vibrate", {})
        self.assertFalse(called.get("isError", True))

    def test_media(self):
        cfg = self._cfg()
        data, mime = P.fetch_media(cfg, "abc123")
        self.assertEqual(mime, "image/png")
        self.assertTrue(data.startswith(b"\x89PNG"))

    def test_auth_failure(self):
        cfg = C.ClientConfig(url=f"http://127.0.0.1:{self.port}/mcp", token="wrong")
        _resp, err = P.rpc(cfg, {"jsonrpc": "2.0", "id": 1, "method": "tools/list"})
        self.assertIn("401", err)


class NoPingServer(MockPhone):
    """Spec 2026-07-28 removes `ping` — the client must not require it."""

    def do_POST(self):  # noqa: N802
        length = int(self.headers.get("Content-Length", 0))
        raw = self.rfile.read(length) or b"{}"
        msg = json.loads(raw)
        if msg.get("method") == "ping":
            body = json.dumps({"jsonrpc": "2.0", "id": msg.get("id"),
                               "error": {"code": -32601, "message": "unknown"}})
            return self._send(200, body)
        # Re-dispatch: re-inject the consumed body for the parent handler.
        import io as _io

        self.rfile = _io.BytesIO(raw)
        self.headers["Content-Length"] = str(len(raw))
        return super().do_POST()


class SpecCompatTest(unittest.TestCase):
    """Guard: smoke/serve/list work against a ping-less (2026-07-28-style) server."""

    @classmethod
    def setUpClass(cls):
        from http.server import HTTPServer as _HTTPServer
        import threading as _threading

        cls.server = _HTTPServer(("127.0.0.1", 0), NoPingServer)
        cls.port = cls.server.server_address[1]
        cls.thread = _threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.thread.join(timeout=5)

    def test_smoke_without_ping(self):
        cfg = C.ClientConfig(url=f"http://127.0.0.1:{self.port}/mcp", token="test-token")
        checks = P.smoke(cfg)
        self.assertEqual(len(checks), 4)
        for name, ok, detail in checks:
            self.assertTrue(ok, f"{name}: {detail}")

    def test_client_source_never_requires_ping(self):
        import os as _os

        src = _os.path.join(_os.path.dirname(_os.path.dirname(_os.path.abspath(__file__))),
                            "src", "phone_mcp_client")
        hits = []
        for fn in ("cli.py", "protocol.py", "config.py", "harnesses.py", "tls.py"):
            body = open(_os.path.join(src, fn), encoding="utf-8").read()
            for i, line in enumerate(body.splitlines(), 1):
                if '"ping"' in line or "'ping'" in line:
                    hits.append(f"{fn}:{i}")
        self.assertEqual(hits, [], f"client must not depend on ping: {hits}")


def _gen_localhost_cert(tmpdir: str) -> tuple[str, str]:
    """Self-signed 127.0.0.1 cert via openssl CLI (skip-guarded by caller)."""
    key = f"{tmpdir}/t.key"
    crt = f"{tmpdir}/t.crt"
    subprocess.run(["openssl", "req", "-x509", "-newkey", "rsa:2048",
                    "-keyout", key, "-out", crt, "-days", "1", "-nodes",
                    "-subj", "/CN=127.0.0.1",
                    "-addext", "subjectAltName=IP:127.0.0.1"],
                   check=True, capture_output=True)
    return key, crt


class QuietTlsServer(HTTPServer):
    """Mock TLS server that stays silent on TOFU capture handshakes.

    preflight_der opens a handshake and closes without HTTP; the resulting
    BrokenPipe/SSLError server-side is expected mock noise, not a failure.
    """

    def handle_error(self, request, client_address):
        exc = sys.exc_info()[1]
        if isinstance(exc, (BrokenPipeError, ConnectionResetError,
                            ConnectionAbortedError, ssl.SSLError)):
            return
        super().handle_error(request, client_address)


class TlsRoundTripTest(unittest.TestCase):
    """Pinned-TLS data leg end-to-end: capture-then-anchor over real TLS.

    The plain-HTTP suites never touch this path (the most security-critical
    one); these tests run the full smoke through genuine TLS handshakes.
    """

    @classmethod
    def setUpClass(cls):
        if shutil.which("openssl") is None:
            raise unittest.SkipTest("openssl CLI not available")
        cls._tmp = tempfile.TemporaryDirectory(prefix="phone-mcp-tls-")
        key, crt = _gen_localhost_cert(cls._tmp.name)
        der = subprocess.run(["openssl", "x509", "-in", crt, "-outform", "DER"],
                             check=True, capture_output=True).stdout
        cls.pin = hashlib.sha256(der).hexdigest()
        cls.server = QuietTlsServer(("127.0.0.1", 0), MockPhone)
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        ctx.load_cert_chain(crt, key)
        cls.server.socket = ctx.wrap_socket(cls.server.socket, server_side=True)
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.thread.join(timeout=5)
        cls.server.server_close()
        cls._tmp.cleanup()

    def _url(self) -> str:
        return f"https://127.0.0.1:{self.server.server_address[1]}/mcp"

    def test_smoke_pinned_pass_with_pin_warning(self):
        # Mock device_info reports tlsSha256=00*32 ≠ pin: probes PASS, but the
        # cross-check must surface a WARNING (proves the pin comparison ran).
        cfg = C.ClientConfig(url=self._url(), token="test-token", sha256=self.pin,
                             timeout_ms=15000, fast_ms=8000, margin_ms=3000)
        checks = P.smoke(cfg)
        self.assertEqual(len(checks), 4)
        for name, ok, detail in checks:
            self.assertTrue(ok, f"{name}: {detail}")
        _name, _ok, device_detail = checks[3]
        self.assertIn("WARNING", device_detail)

    def test_smoke_wrong_pin_fails_mismatch(self):
        wrong = "00" + self.pin[2:]
        cfg = C.ClientConfig(url=self._url(), token="test-token", sha256=wrong,
                             timeout_ms=15000, fast_ms=8000, margin_ms=3000)
        checks = P.smoke(cfg)
        self.assertTrue(all(not ok for _n, ok, _d in checks))
        for _n, _ok, detail in checks:
            self.assertIn("fingerprint mismatch", detail)

    def test_smoke_cafile_pass(self):
        ca = f"{self._tmp.name}/phone-mcp-ca.pem"
        T.export_ca_pem("127.0.0.1", self.server.server_address[1], self.pin, ca, 10.0)
        cfg = C.ClientConfig(url=self._url(), token="test-token", cafile=ca,
                             timeout_ms=15000, fast_ms=8000, margin_ms=3000)
        checks = P.smoke(cfg)
        for name, ok, detail in checks:
            self.assertTrue(ok, f"{name}: {detail}")

    def test_smoke_insecure_pass(self):
        cfg = C.ClientConfig(url=self._url(), token="test-token", insecure=True,
                             timeout_ms=15000, fast_ms=8000, margin_ms=3000)
        checks = P.smoke(cfg)
        for name, ok, detail in checks:
            self.assertTrue(ok, f"{name}: {detail}")


if __name__ == "__main__":
    unittest.main()
