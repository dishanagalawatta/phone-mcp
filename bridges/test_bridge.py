"""Unit tests for the harness stdio bridges (stdlib only, no phone needed).

Covers the security-critical pure logic shared by both bridge twins:
fingerprint normalization/pinning, header construction (Bearer + version
gate parity with McpServerService), SSE envelope parsing, QR payload
parsing (Pairing.pairingPayload shape), and harness config rendering.

Run: python3 -m unittest discover -s bridges -p "test_*.py" -v
"""

import io
import json
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import importlib.util as _ilu

_spec = _ilu.spec_from_file_location(
    "phone_mcp_bridge", os.path.join(os.path.dirname(os.path.abspath(__file__)), "phone-mcp-bridge.py")
)
_mod = _ilu.module_from_spec(_spec)
_spec.loader.exec_module(_mod)
build_headers = _mod.build_headers
grouped_fingerprint = _mod.grouped_fingerprint
normalize_fingerprint = _mod.normalize_fingerprint
parse_sse_data = _mod.parse_sse_data
pin_matches = _mod.pin_matches
tls_options = _mod.tls_options
effective_timeout = _mod.effective_timeout
message_duration_ms = _mod.message_duration_ms


def parse_qr_payload(raw: str) -> dict:
    """Mirror of Pairing.pairingPayload {"url","token","sha256"} parsing."""
    obj = json.loads(raw)
    url, token, sha = obj.get("url", ""), obj.get("token", ""), obj.get("sha256", "")
    if not url or not token or not sha:
        raise ValueError("QR payload must contain url, token, sha256")
    if not url.startswith("https://"):
        raise ValueError("QR url must be https://")
    return {"url": url, "token": token, "sha256": normalize_fingerprint(sha)}


def render_opencode(url_env="$PHONE_MCP_URL", bridge="node") -> dict:
    cmd = (
        ["node", "bridges/phone-mcp-bridge.mjs"]
        if bridge == "node"
        else ["python3", "bridges/phone-mcp-bridge.py"]
    )
    return {
        "mcp": {
            "phone-mcp": {
                "type": "local",
                "command": cmd,
                "enabled": True,
                "environment": {
                    "PHONE_MCP_URL": url_env,
                    "PHONE_MCP_TOKEN": "{env:PHONE_MCP_TOKEN}",
                },
            }
        }
    }


def render_codex_remote() -> str:
    return (
        '[mcp_servers.phone-mcp]\n'
        'url = "https://192.168.1.50:8080/mcp"\n'
        'bearer_token_env_var = "PHONE_MCP_TOKEN"\n'
    )


class FingerprintTest(unittest.TestCase):
    def test_normalize_strips_colons_and_case(self):
        self.assertEqual(normalize_fingerprint("AA:bb:CC dd"), "aabbccdd")
        self.assertEqual(normalize_fingerprint(""), "")

    def test_grouped_roundtrip(self):
        self.assertEqual(grouped_fingerprint("aabbcc"), "AA:BB:CC")

    def test_pin_compare_ignores_formatting(self):
        presented = normalize_fingerprint("AA:BB:CC:DD")
        pinned = normalize_fingerprint("aabbccdd")
        self.assertEqual(presented, pinned)

    def test_mismatch_detected(self):
        self.assertNotEqual(normalize_fingerprint("AA:BB"), normalize_fingerprint("AA:BC"))

    def test_pin_matches_known_vector(self):
        import hashlib as _hl

        digest = _hl.sha256(b"abc").hexdigest()  # ba7816bf…
        self.assertTrue(pin_matches(b"abc", digest))
        self.assertTrue(pin_matches(b"abc", ":".join(
            digest[i : i + 2] for i in range(0, len(digest), 2)
        ).upper()))
        self.assertFalse(pin_matches(b"abd", digest))
        self.assertFalse(pin_matches(b"", digest))


class HeadersTest(unittest.TestCase):
    def test_bearer_and_accept_always(self):
        h = build_headers("tok123", "2025-06-18", "tools/call")
        self.assertEqual(h["Authorization"], "Bearer tok123")
        self.assertIn("text/event-stream", h["Accept"])

    def test_version_sent_for_tools(self):
        h = build_headers("t", "2025-06-18", "tools/call")
        self.assertEqual(h["MCP-Protocol-Version"], "2025-06-18")

    def test_version_skipped_for_initialize_parity(self):
        # McpServerService skips the version gate for initialize +
        # notifications/*; the bridge mirrors that so strict harnesses
        # replaying initialize verbatim still connect.
        self.assertNotIn("MCP-Protocol-Version", build_headers("t", "2025-06-18", "initialize"))
        self.assertNotIn(
            "MCP-Protocol-Version", build_headers("t", "2025-06-18", "notifications/initialized")
        )


class SseTest(unittest.TestCase):
    def test_extracts_data_payloads_ignores_comments(self):
        body = ': ping\n\ndata: {"a":1}\n\ndata: {"b":2}\n\n'
        self.assertEqual(parse_sse_data(body), ['{"a":1}', '{"b":2}'])

    def test_empty_body_yields_no_payloads(self):
        self.assertEqual(parse_sse_data(""), [])

    def test_notification_then_result_order_preserved(self):
        body = 'data: {"method":"notifications/x"}\n\ndata: {"id":8,"result":{}}\n\n'
        payloads = parse_sse_data(body)
        self.assertEqual(len(payloads), 2)
        self.assertIn("notifications/x", payloads[0])
        self.assertIn('"id":8', payloads[1])


class QrPayloadTest(unittest.TestCase):
    def test_valid_pairing_payload(self):
        raw = json.dumps(
            {"url": "https://192.168.1.50:8080/mcp", "token": "abc", "sha256": "AA:BB:CC"}
        )
        parsed = parse_qr_payload(raw)
        self.assertEqual(parsed["sha256"], "aabbcc")

    def test_rejects_missing_fields(self):
        with self.assertRaises(ValueError):
            parse_qr_payload(json.dumps({"url": "https://x/mcp"}))

    def test_rejects_non_tls_url(self):
        with self.assertRaises(ValueError):
            parse_qr_payload(json.dumps({"url": "http://x/mcp", "token": "t", "sha256": "aa"}))


class TlsOptionsTest(unittest.TestCase):
    """Mirrors resolveTls in phone-mcp-bridge.mjs — parity is enforced by CI."""

    def test_default_verifies_full_chain(self):
        import ssl as _ssl

        opts = tls_options("", False, "")
        self.assertEqual(opts["verify_mode"], _ssl.CERT_REQUIRED)
        self.assertTrue(opts["check_hostname"])

    def test_pin_skips_chain_verifies_hash_instead(self):
        import ssl as _ssl

        opts = tls_options("AA:BB", False, "")
        self.assertEqual(opts["verify_mode"], _ssl.CERT_NONE)
        self.assertFalse(opts["check_hostname"])

    def test_cafile_verifies_chain_against_pinned_leaf(self):
        import ssl as _ssl

        opts = tls_options("", False, "/tmp/ca.pem")
        self.assertEqual(opts["verify_mode"], _ssl.CERT_REQUIRED)
        self.assertFalse(opts["check_hostname"])  # DHCP churn breaks SAN matching
        self.assertEqual(opts["cafile"], "/tmp/ca.pem")

    def test_pin_plus_cafile_keeps_chain_and_hash(self):
        import ssl as _ssl

        opts = tls_options("aabb", False, "/tmp/ca.pem")
        self.assertEqual(opts["verify_mode"], _ssl.CERT_REQUIRED)
        self.assertEqual(opts["cafile"], "/tmp/ca.pem")

    def test_insecure_disables_everything(self):
        import ssl as _ssl

        opts = tls_options("", True, "")
        self.assertEqual(opts["verify_mode"], _ssl.CERT_NONE)
        # Explicit opt-in wins even with CA material present.
        self.assertNotIn("cafile", tls_options("", True, "/tmp/ca.pem"))


class TimeoutTest(unittest.TestCase):
    """Mirrors effectiveTimeout in phone-mcp-bridge.mjs — parity enforced by CI."""

    CEIL, FAST, MARGIN = 35000, 15000, 10000

    def test_fast_calls_get_fast_budget(self):
        for msg in (
            {"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {}},
            {"jsonrpc": "2.0", "id": 2, "method": "tools/list", "params": {}},
            {"jsonrpc": "2.0", "id": 3, "method": "tools/call",
             "params": {"name": "vibrate", "arguments": {}}},
            {"jsonrpc": "2.0", "method": "notifications/initialized"},
        ):
            self.assertEqual(
                effective_timeout(msg, self.CEIL, self.FAST, self.MARGIN), self.FAST
            )

    def test_duration_calls_get_duration_plus_margin(self):
        msg = {"jsonrpc": "2.0", "id": 5, "method": "tools/call",
               "params": {"name": "audio_record", "arguments": {"durationMs": 2000}}}
        self.assertEqual(effective_timeout(msg, self.CEIL, self.FAST, self.MARGIN), 12000)
        legacy = {"jsonrpc": "2.0", "id": 6, "method": "tools/call",
                  "params": {"name": "sensors_read", "arguments": {"timeoutMs": 1500}}}
        self.assertEqual(effective_timeout(legacy, self.CEIL, self.FAST, self.MARGIN), 11500)

    def test_ceiling_bounds_absurd_durations(self):
        msg = {"jsonrpc": "2.0", "id": 7, "method": "tools/call",
               "params": {"name": "x", "arguments": {"durationMs": 3600000}}}
        self.assertEqual(effective_timeout(msg, self.CEIL, self.FAST, self.MARGIN), self.CEIL)

    def test_malformed_durations_fall_back_to_fast(self):
        for args in ({"durationMs": -5}, {"durationMs": "soon"}, {"durationMs": 0}, None):
            msg = {"jsonrpc": "2.0", "id": 8, "method": "tools/call",
                   "params": {"name": "x", "arguments": args}}
            self.assertEqual(
                effective_timeout(msg, self.CEIL, self.FAST, self.MARGIN), self.FAST
            )
        self.assertEqual(effective_timeout(None, self.CEIL, self.FAST, self.MARGIN), self.FAST)


class ConfigRenderTest(unittest.TestCase):
    def test_opencode_uses_env_not_literal_token(self):
        cfg = render_opencode()
        env = cfg["mcp"]["phone-mcp"]["environment"]
        # Tokens must never be baked into checked-in config.
        self.assertIn("PHONE_MCP_TOKEN", env["PHONE_MCP_TOKEN"])
        self.assertNotIn("tok123", json.dumps(cfg))

    def test_opencode_bridge_command_points_at_repo(self):
        cfg = render_opencode(bridge="python")
        self.assertIn("phone-mcp-bridge.py", " ".join(cfg["mcp"]["phone-mcp"]["command"]))

    def test_codex_uses_env_var_ref(self):
        toml = render_codex_remote()
        self.assertIn('bearer_token_env_var = "PHONE_MCP_TOKEN"', toml)
        self.assertNotIn("Bearer ", toml)


if __name__ == "__main__":
    unittest.main()
