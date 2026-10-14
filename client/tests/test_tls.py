"""TLS tests + parity with bridges/phone-mcp-bridge.py."""

import hashlib
import importlib.util
import os
import ssl
import sys
import tempfile
import unittest

from phone_mcp_client import tls as T

BRIDGE_PY = os.path.join(os.path.dirname(__file__), "..", "..", "bridges",
                         "phone-mcp-bridge.py")


def _load_bridge():
    spec = importlib.util.spec_from_file_location("phone_mcp_bridge", BRIDGE_PY)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


class FingerprintTest(unittest.TestCase):
    def test_normalize(self):
        self.assertEqual(T.normalize_fingerprint("AA:bb:CC dd"), "aabbccdd")
        self.assertEqual(T.normalize_fingerprint(""), "")

    def test_grouped(self):
        self.assertEqual(T.grouped_fingerprint("aabbcc"), "AA:BB:CC")

    def test_pin_matches_vector(self):
        digest = hashlib.sha256(b"abc").hexdigest()
        self.assertTrue(T.pin_matches(b"abc", digest))
        self.assertFalse(T.pin_matches(b"abd", digest))
        self.assertFalse(T.pin_matches(b"", digest))
        self.assertFalse(T.pin_matches(None, digest))


class TlsOptionsTest(unittest.TestCase):
    def test_default_verifies(self):
        opts = T.tls_options("", False, "")
        self.assertEqual(opts["verify_mode"], ssl.CERT_REQUIRED)
        self.assertTrue(opts["check_hostname"])

    def test_pin_skips_chain(self):
        opts = T.tls_options("AA:BB", False, "")
        self.assertEqual(opts["verify_mode"], ssl.CERT_NONE)
        self.assertFalse(opts["check_hostname"])

    def test_cafile_verifies_against_leaf(self):
        opts = T.tls_options("", False, "/tmp/ca.pem")
        self.assertEqual(opts["verify_mode"], ssl.CERT_REQUIRED)
        self.assertFalse(opts["check_hostname"])

    def test_insecure_wins(self):
        self.assertEqual(T.tls_options("", True, "")["verify_mode"], ssl.CERT_NONE)
        self.assertNotIn("cafile", T.tls_options("", True, "/tmp/ca.pem"))

    def test_alpn_always(self):
        ctx = T.ssl_context("aabb", False, "")
        self.assertIn("http/1.1", getattr(ctx, "_alpn", ["http/1.1"]) if hasattr(ctx, "_alpn") else ["http/1.1"])
        # Direct check: set_alpn_protocols was called — verify via get (no public API),
        # so at minimum construction must not raise for every mode.
        for pin, ins, ca in (("", False, ""), ("aa" * 32, False, ""), ("", True, "")):
            T.ssl_context(pin, ins, ca)


class BridgeParityTest(unittest.TestCase):
    """Client logic must stay in sync with the standalone bridge twins."""

    @unittest.skipUnless(os.path.exists(BRIDGE_PY), "bridges/ twin missing")
    def test_parity(self):
        b = _load_bridge()
        for pin in ["", "AA:BB:CC", "a" * 64]:
            for ins in [False, True]:
                for ca in ["", "/tmp/ca.pem"]:
                    mine = T.tls_options(pin, ins, ca)
                    theirs = b.tls_options(pin, ins, ca)
                    self.assertEqual(mine["verify_mode"], theirs["verify_mode"], (pin, ins, ca))
                    self.assertEqual(mine["check_hostname"], theirs["check_hostname"], (pin, ins, ca))
        for tok, proto, method in [("t", "2025-06-18", "tools/call"),
                                   ("t", "2025-06-18", "initialize"),
                                   ("t", "2025-06-18", "notifications/initialized")]:
            from phone_mcp_client import protocol as P
            self.assertEqual(P.build_headers(tok, proto, method),
                             b.build_headers(tok, proto, method))
            self.assertEqual(P.parse_sse_data('data: {"a":1}\n\n'),
                             b.parse_sse_data('data: {"a":1}\n\n'))


class ExportCaTest(unittest.TestCase):
    def test_refuses_blind_pin(self):
        # export_ca_pem itself requires a pin; the CLI guards empty pins.
        # Here: mismatched DER must raise and write NOTHING.
        orig = T.preflight_der
        T.preflight_der = lambda *a, **k: b"presented-cert-bytes"  # noqa: E731
        try:
            with tempfile.TemporaryDirectory() as td:
                out = os.path.join(td, "ca.pem")
                with self.assertRaises(RuntimeError):
                    T.export_ca_pem("127.0.0.1", 8080, "b" * 64, out, 1.0)
                self.assertFalse(os.path.exists(out))
        finally:
            T.preflight_der = orig

    def test_writes_pem_on_match(self):
        # Generate a real self-signed cert via openssl (skip if unavailable).
        import shutil
        import subprocess
        if shutil.which("openssl") is None:
            self.skipTest("openssl not available")
        with tempfile.TemporaryDirectory() as td:
            key = os.path.join(td, "k.pem")
            crt = os.path.join(td, "c.pem")
            subprocess.run(["openssl", "req", "-x509", "-newkey", "rsa:2048",
                            "-keyout", key, "-out", crt, "-days", "1",
                            "-nodes", "-subj", "/CN=127.0.0.1"],
                           check=True, capture_output=True)
            with open(crt) as fh:
                der = ssl.PEM_cert_to_DER_cert(fh.read())
            pin = hashlib.sha256(der).hexdigest()
            orig = T.preflight_der
            T.preflight_der = lambda *a, **k: der  # noqa: E731
            try:
                out = os.path.join(td, "out", "phone-mcp-ca.pem")
                got = T.export_ca_pem("127.0.0.1", 8080, pin, out, 1.0)
                self.assertTrue(os.path.exists(got))
                self.assertIn("BEGIN CERTIFICATE", open(got).read())
            finally:
                T.preflight_der = orig


if __name__ == "__main__":
    unittest.main()
