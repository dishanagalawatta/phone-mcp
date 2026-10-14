"""Shared-vector consumers: client/vectors.json is the single source of truth.

Every implementation of the harness-side logic asserts against the same
vectors here: the Python client, bridges/phone-mcp-bridge.py, (and via
client/node/test/test-vectors.mjs) the Node client + bridges/*.mjs.
Add a vector to vectors.json — never duplicate expected values across suites.

Run: PYTHONPATH=client/src python3 -m unittest discover -s client/tests -p "test_vectors.py" -v
"""

import importlib.util
import json
import os
import ssl
import unittest

from phone_mcp_client import protocol as P
from phone_mcp_client import tls as T

VECTORS = json.load(open(os.path.join(os.path.dirname(__file__), "..", "vectors.json")))

BRIDGE_PY = os.path.join(os.path.dirname(__file__), "..", "..", "bridges", "phone-mcp-bridge.py")


def _load_bridge():
    spec = importlib.util.spec_from_file_location("phone_mcp_bridge_py", BRIDGE_PY)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


class TlsModeVectorsTest(unittest.TestCase):
    def test_client_and_bridge_match_vectors(self):
        bridge = _load_bridge()
        for case in VECTORS["tlsModes"]:
            pin, insecure, cafile = (case["input"]["pin"], case["input"]["insecure"],
                                     case["input"]["cafile"])
            exp = case["expected"]
            with self.subTest(case=case["input"]):
                for opts in (T.tls_options(pin, insecure, cafile),
                             bridge.tls_options(pin, insecure, cafile)):
                    self.assertEqual(opts["verify_mode"] == ssl.CERT_REQUIRED,
                                     exp["rejectUnauthorized"])
                    if insecure:
                        # Insecure verifies NOTHING in either runtime, so the
                        # hostname flag is unobservable. Python additionally
                        # cannot represent CERT_NONE + check_hostname=True
                        # (ssl raises) — it reports check_hostname=False.
                        self.assertEqual(opts["verify_mode"], ssl.CERT_NONE)
                    else:
                        self.assertEqual(not opts["check_hostname"],
                                         exp["skipHostnameCheck"])


class HeaderVectorsTest(unittest.TestCase):
    def test_client_and_bridge_match_vectors(self):
        bridge = _load_bridge()
        for case in VECTORS["headers"]:
            with self.subTest(method=case["method"]):
                self.assertEqual(P.build_headers(case["token"], case["protocol"], case["method"]),
                                 case["expected"])
                self.assertEqual(bridge.build_headers(case["token"], case["protocol"], case["method"]),
                                 case["expected"])


class SseVectorsTest(unittest.TestCase):
    def test_client_and_bridge_match_vectors(self):
        bridge = _load_bridge()
        for i, case in enumerate(VECTORS["sse"]):
            with self.subTest(case=i):
                self.assertEqual(P.parse_sse_data(case["body"]), case["expected"])
                self.assertEqual(bridge.parse_sse_data(case["body"]), case["expected"])


class BudgetVectorsTest(unittest.TestCase):
    def test_client_and_bridge_match_vectors(self):
        bridge = _load_bridge()
        params = VECTORS["budgets"]["params"]
        for case in VECTORS["budgets"]["cases"]:
            with self.subTest(message=(case["message"] or {}).get("method")):
                self.assertEqual(
                    P.effective_timeout(case["message"], params["ceiling"],
                                        params["fast"], params["margin"]),
                    case["expectedMs"])
                self.assertEqual(
                    bridge.effective_timeout(case["message"], params["ceiling"],
                                             params["fast"], params["margin"]),
                    case["expectedMs"])


class FingerprintVectorsTest(unittest.TestCase):
    def test_normalize_grouped_pin(self):
        bridge = _load_bridge()
        for raw, expected in VECTORS["fingerprints"]["normalize"]:
            self.assertEqual(T.normalize_fingerprint(raw), expected)
            self.assertEqual(bridge.normalize_fingerprint(raw), expected)
        for raw, expected in VECTORS["fingerprints"]["grouped"]:
            self.assertEqual(T.grouped_fingerprint(raw), expected)
            self.assertEqual(bridge.grouped_fingerprint(raw), expected)
        digest = VECTORS["fingerprints"]["sha256abc"]
        self.assertTrue(T.pin_matches(b"abc", digest))
        self.assertFalse(T.pin_matches(b"abd", digest))
        self.assertTrue(bridge.pin_matches(b"abc", digest))
        self.assertFalse(bridge.pin_matches(b"abd", digest))


if __name__ == "__main__":
    unittest.main()
