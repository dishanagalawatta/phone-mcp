"""Config tests: env/QR/validation/paired-file (no phone needed)."""

import json
import os
import tempfile
import unittest

from phone_mcp_client import config as C


class FromEnvTest(unittest.TestCase):
    def test_defaults(self):
        cfg = C.from_env({})
        self.assertEqual(cfg.protocol, "2025-06-18")
        self.assertEqual(cfg.timeout_ms, 35000)

    def test_normalizes_sha(self):
        cfg = C.from_env({"PHONE_MCP_SHA256": "AA:BB:cc"})
        self.assertEqual(cfg.sha256, "aabbcc")

    def test_insecure_flag(self):
        self.assertTrue(C.from_env({"PHONE_MCP_INSECURE": "1"}).insecure)
        self.assertFalse(C.from_env({}).insecure)


class ValidateTest(unittest.TestCase):
    def test_missing_url_token(self):
        cfg = C.from_env({})
        problems = cfg.validate()
        self.assertTrue(any("URL" in p for p in problems))
        self.assertTrue(any("TOKEN" in p for p in problems))

    def test_http_non_loopback_rejected(self):
        cfg = C.ClientConfig(url="http://192.168.1.50:8080/mcp", token="t")
        self.assertTrue(any("loopback" in p for p in cfg.validate()))

    def test_http_loopback_ok(self):
        cfg = C.ClientConfig(url="http://127.0.0.1:8080/mcp", token="t")
        self.assertFalse([p for p in cfg.validate() if "loopback" in p])

    def test_bad_sha_length(self):
        cfg = C.ClientConfig(url="https://x/mcp", token="t", sha256="aabb")
        self.assertTrue(any("64" in p for p in cfg.validate()))


class QrTest(unittest.TestCase):
    def test_valid(self):
        raw = json.dumps({"url": "https://192.168.1.50:8080/mcp", "token": "abc",
                          "sha256": "AB:" * 31 + "CD"})
        parsed = C.parse_qr_payload(raw)
        self.assertEqual(parsed["url"], "https://192.168.1.50:8080/mcp")
        self.assertEqual(len(parsed["sha256"]), 64)

    def test_rejects_missing(self):
        with self.assertRaises(ValueError):
            C.parse_qr_payload(json.dumps({"url": "https://x/mcp"}))

    def test_rejects_http(self):
        with self.assertRaises(ValueError):
            C.parse_qr_payload(json.dumps({"url": "http://x/mcp", "token": "t",
                                           "sha256": "a" * 64}))

    def test_rejects_garbage(self):
        with self.assertRaises(ValueError):
            C.parse_qr_payload("not json")


class PairedFileTest(unittest.TestCase):
    def test_save_load_roundtrip_0600(self):
        cfg = C.ClientConfig(url="https://192.168.1.50:8080/mcp", token="secret",
                             sha256="a" * 64)
        with tempfile.TemporaryDirectory() as td:
            path = os.path.join(td, "sub", "config.json")
            C.save_paired_config(cfg, path)
            mode = oct(os.stat(path).st_mode & 0o777)
            self.assertEqual(mode, "0o600")
            loaded = C.load_paired_config(path)
            self.assertEqual(loaded.url, cfg.url)
            self.assertEqual(loaded.token, "secret")


if __name__ == "__main__":
    unittest.main()
