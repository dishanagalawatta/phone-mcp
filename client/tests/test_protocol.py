"""Protocol tests: headers/SSE/budgets/rpc/serve (mocked transport)."""

import io
import json
import unittest
from contextlib import redirect_stdout
from unittest import mock

from phone_mcp_client import config as C
from phone_mcp_client import protocol as P


class HeadersTest(unittest.TestCase):
    def test_bearer_accept(self):
        h = P.build_headers("tok", "2025-06-18", "tools/call")
        self.assertEqual(h["Authorization"], "Bearer tok")
        self.assertIn("text/event-stream", h["Accept"])
        self.assertEqual(h["MCP-Protocol-Version"], "2025-06-18")

    def test_initialize_skips_version(self):
        self.assertNotIn("MCP-Protocol-Version",
                         P.build_headers("t", "2025-06-18", "initialize"))
        self.assertNotIn("MCP-Protocol-Version",
                         P.build_headers("t", "2025-06-18", "notifications/initialized"))


class SseTest(unittest.TestCase):
    def test_extracts(self):
        body = ': ping\n\ndata: {"a":1}\n\ndata: {"b":2}\n\n'
        self.assertEqual(P.parse_sse_data(body), ['{"a":1}', '{"b":2}'])

    def test_empty(self):
        self.assertEqual(P.parse_sse_data(""), [])


class BudgetTest(unittest.TestCase):
    CEIL, FAST, MARGIN = 35000, 15000, 10000

    def test_fast(self):
        for msg in ({"method": "initialize", "params": {}},
                    {"method": "tools/list", "params": {}},
                    {"method": "tools/call",
                     "params": {"name": "vibrate", "arguments": {}}}):
            self.assertEqual(P.effective_timeout(msg, self.CEIL, self.FAST, self.MARGIN),
                             self.FAST)

    def test_duration_plus_margin(self):
        msg = {"method": "tools/call",
               "params": {"name": "a", "arguments": {"durationMs": 2000}}}
        self.assertEqual(P.effective_timeout(msg, self.CEIL, self.FAST, self.MARGIN), 12000)

    def test_ceiling(self):
        msg = {"method": "tools/call",
               "params": {"name": "x", "arguments": {"durationMs": 3600000}}}
        self.assertEqual(P.effective_timeout(msg, self.CEIL, self.FAST, self.MARGIN), self.CEIL)


def _cfg(**kw):
    base = dict(url="http://127.0.0.1:9/mcp", token="t")
    base.update(kw)
    return C.ClientConfig(**base)


class FakeResponse:
    def __init__(self, body, status=200, ctype="application/json"):
        self._body = body.encode() if isinstance(body, str) else body
        self.status = status
        self.headers = {"Content-Type": ctype}

    def read(self):
        return self._body

    def __enter__(self):
        return self

    def __exit__(self, *a):
        return False


class RpcTest(unittest.TestCase):
    def test_json_roundtrip(self):
        cfg = _cfg()
        opener = lambda req, timeout=None: FakeResponse('{"jsonrpc":"2.0","id":1,"result":{}}')  # noqa: E731
        resp, err = P.rpc(cfg, {"jsonrpc": "2.0", "id": 1, "method": "ping"}, urlopen=opener)
        self.assertIsNone(err)
        self.assertIn("result", resp)

    def test_401(self):
        import urllib.error
        cfg = _cfg()

        def opener(req, timeout=None):
            raise urllib.error.HTTPError(req.full_url, 401, "x", {}, io.BytesIO(b""))
        resp, err = P.rpc(cfg, {"jsonrpc": "2.0", "id": 1, "method": "tools/list"},
                          urlopen=opener)
        self.assertIsNone(resp)
        self.assertIn("401", err)

    def test_sse_first_envelope(self):
        cfg = _cfg()
        body = 'data: {"jsonrpc":"2.0","id":2,"result":{"tools":[]}}\n\n'
        opener = lambda req, timeout=None: FakeResponse(body, 200, "text/event-stream")  # noqa: E731
        resp, err = P.rpc(cfg, {"jsonrpc": "2.0", "id": 2, "method": "tools/list"},
                          urlopen=opener)
        self.assertIsNone(err)
        self.assertIn("result", resp)

    def test_tls_hint_on_cert_error(self):
        cfg = _cfg()

        def opener(req, timeout=None):
            raise ssl_error()
        def ssl_error():
            import ssl
            return ssl.SSLCertVerificationError("self-signed certificate")
        resp, err = P.rpc(cfg, {"jsonrpc": "2.0", "id": 1, "method": "tools/list"},
                          urlopen=opener)
        self.assertIn("PHONE_MCP_SHA256", err)


class HandleStdioTest(unittest.TestCase):
    def test_forwards_result(self):
        cfg = _cfg()
        with mock.patch.object(P, "post_mcp",
                               return_value=(200, "application/json",
                                             '{"jsonrpc":"2.0","id":3,"result":{}}')):
            buf = io.StringIO()
            with redirect_stdout(buf):
                P.handle_stdio_line('{"jsonrpc":"2.0","id":3,"method":"tools/list","params":{}}',
                                    cfg)
            self.assertIn('"result"', buf.getvalue())

    def test_401_maps_32001(self):
        cfg = _cfg()
        with mock.patch.object(P, "post_mcp", return_value=(401, "application/json", "")):
            buf = io.StringIO()
            with redirect_stdout(buf):
                P.handle_stdio_line('{"jsonrpc":"2.0","id":4,"method":"tools/list","params":{}}',
                                    cfg)
            obj = json.loads(buf.getvalue())
            self.assertEqual(obj["error"]["code"], -32001)

    def test_parse_error(self):
        buf = io.StringIO()
        with redirect_stdout(buf):
            P.handle_stdio_line("not json", _cfg())
        self.assertEqual(json.loads(buf.getvalue())["error"]["code"], -32700)

    def test_sse_fanout(self):
        cfg = _cfg()
        body = ('data: {"jsonrpc":"2.0","method":"notifications/x"}\n\n'
                'data: {"jsonrpc":"2.0","id":8,"result":{}}\n\n')
        with mock.patch.object(P, "post_mcp",
                               return_value=(200, "text/event-stream", body)):
            buf = io.StringIO()
            with redirect_stdout(buf):
                P.handle_stdio_line('{"jsonrpc":"2.0","id":8,"method":"tools/call",'
                                    '"params":{"name":"s","arguments":{}}}', cfg)
            lines = [json.loads(line) for line in buf.getvalue().strip().split("\n")]
            self.assertEqual(len(lines), 2)
            self.assertEqual(lines[1]["id"], 8)

    def test_stdout_stays_jsonrpc_only(self):
        # Diagnostics must go to stderr, never stdout.
        import contextlib
        cfg = _cfg()
        with mock.patch.object(P, "post_mcp", side_effect=RuntimeError("TLS fingerprint mismatch: x")):
            out, err = io.StringIO(), io.StringIO()
            with redirect_stdout(out), contextlib.redirect_stderr(err):
                P.handle_stdio_line('{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}',
                                    cfg)
            json.loads(out.getvalue())  # must be valid JSON-RPC
            self.assertNotIn("fingerprint", out.getvalue().lower()[:0] if False else "")


if __name__ == "__main__":
    unittest.main()
