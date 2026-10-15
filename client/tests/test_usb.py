"""Unit tests for USB-loopback helpers (pure logic, no adb/phone needed).

Run: python3 -m unittest discover -s client/tests -p "test_usb.py" -v
"""

import unittest
from unittest import mock

from phone_mcp_client import usb as U


class LoopbackDetectTest(unittest.TestCase):
    def test_loopback_hosts(self):
        for url in (
            "https://127.0.0.1:8080/mcp",
            "http://127.0.0.1:8080/mcp",
            "https://localhost:8080/mcp",
            "https://[::1]:8080/mcp",
        ):
            self.assertTrue(U.is_loopback_url(url), url)

    def test_lan_not_loopback(self):
        for url in (
            "https://192.168.1.50:8080/mcp",
            "https://10.0.0.5:8080/mcp",
            "https://phone.local:8080/mcp",
            "",
            "not-a-url",
        ):
            self.assertFalse(U.is_loopback_url(url), url)


class RewriteTest(unittest.TestCase):
    def test_rewrite_keeps_port_and_path(self):
        out = U.rewrite_url_to_loopback("https://192.168.1.50:9090/mcp", None)
        self.assertEqual(out, "https://192.168.1.50:9090/mcp".replace("192.168.1.50", "127.0.0.1"))

    def test_rewrite_explicit_port(self):
        self.assertEqual(
            U.rewrite_url_to_loopback("https://192.168.1.50:8080/mcp", 9090),
            "https://127.0.0.1:9090/mcp",
        )

    def test_rewrite_127_already(self):
        out = U.rewrite_url_to_loopback("https://127.0.0.1:8080/mcp", 8080)
        self.assertEqual(out, "https://127.0.0.1:8080/mcp")

    def test_rewrite_empty_defaults(self):
        self.assertEqual(U.rewrite_url_to_loopback("", 8080), "https://127.0.0.1:8080/mcp")

    def test_loopback_url_for_port(self):
        self.assertEqual(U.loopback_url_for_port(8080), "https://127.0.0.1:8080/mcp")
        self.assertEqual(U.loopback_url_for_port("9090"), "https://127.0.0.1:9090/mcp")


class ParseDevicesTest(unittest.TestCase):
    SAMPLE = (
        "List of devices attached\n"
        "emulator-5554\tdevice product:sdk_phone_x86 model:sdk_phone device:generic\n"
        "ABC123\tunauthorized usb:1-1 transport_id:2\n"
        "XYZ999\toffline\n"
        "\n"
    )

    def test_parses_states(self):
        devs = U.parse_adb_devices(self.SAMPLE)
        by_serial = {d["serial"]: d for d in devs}
        self.assertEqual(by_serial["emulator-5554"]["state"], "device")
        self.assertIn("product:", by_serial["emulator-5554"]["detail"])
        self.assertEqual(by_serial["ABC123"]["state"], "unauthorized")
        self.assertEqual(by_serial["XYZ999"]["state"], "offline")

    def test_skips_header_and_blank(self):
        self.assertEqual(U.parse_adb_devices("List of devices attached\n\n"), [])
        self.assertEqual(U.parse_adb_devices(""), [])

    def test_no_device_state(self):
        devs = U.parse_adb_devices("ABC123\tno device\n")
        self.assertEqual(devs[0]["state"], "no device")


class ParseForwardListTest(unittest.TestCase):
    def test_parses(self):
        out = "ABC123 tcp:8080 tcp:8080\nXYZ999 tcp:8081 tcp:8080\n"
        fwds = U.parse_adb_forward_list(out)
        self.assertEqual(fwds[0], {"serial": "ABC123", "local": "tcp:8080", "remote": "tcp:8080"})
        self.assertEqual(len(fwds), 2)

    def test_skips_blank_and_malformed(self):
        self.assertEqual(U.parse_adb_forward_list("\n"), [])
        self.assertEqual(U.parse_adb_forward_list("only-two parts\n"), [])


class AdbArgsTest(unittest.TestCase):
    def test_forward_with_serial(self):
        self.assertEqual(
            U.adb_forward_args("ABC123", 8080),
            ["adb", "-s", "ABC123", "forward", "tcp:8080", "tcp:8080"],
        )

    def test_forward_without_serial(self):
        self.assertEqual(U.adb_forward_args("", 8080), ["adb", "forward", "tcp:8080", "tcp:8080"])

    def test_forward_uses_forward_not_reverse(self):
        args = U.adb_forward_args("S", 9090)
        self.assertIn("forward", args)
        self.assertNotIn("reverse", args)


class ActionableErrorTest(unittest.TestCase):
    LOOPBACK = "https://127.0.0.1:8080/mcp"
    LAN = "https://192.168.1.50:8080/mcp"
    TOKEN = "s3cr3t-token-value-abc123"

    def test_loopback_refused_suggests_forward(self):
        msg = U.actionable_usb_error("Connection refused", self.LOOPBACK)
        self.assertIn("adb forward tcp:8080 tcp:8080", msg)

    def test_loopback_timeout_suggests_forward(self):
        msg = U.actionable_usb_error("connection timed out", self.LOOPBACK)
        self.assertIn("adb forward", msg)

    def test_lan_refused_has_no_forward_hint(self):
        msg = U.actionable_usb_error("Connection refused", self.LAN)
        self.assertNotIn("adb forward", msg)

    def test_unauthorized_mentions_adb_devices(self):
        msg = U.actionable_usb_error("device unauthorized", self.LOOPBACK)
        self.assertIn("adb devices", msg)

    def test_offline_mentions_reauthorize(self):
        msg = U.actionable_usb_error("device offline", self.LOOPBACK)
        self.assertIn("adb devices", msg)
        self.assertIn("RSA", msg)

    def test_redacts_bearer_token(self):
        msg = U.actionable_usb_error(f"Upstream unreachable: Bearer {self.TOKEN}", self.LOOPBACK)
        self.assertNotIn(self.TOKEN, msg)
        self.assertIn("<redacted>", msg)

    def test_redacts_token_kv(self):
        msg = U.actionable_usb_error(f"detail token={self.TOKEN} end", self.LOOPBACK)
        self.assertNotIn(self.TOKEN, msg)

    def test_loopback_cert_mentions_forward_not_trusted_lan(self):
        msg = U.actionable_usb_error("self-signed certificate", self.LOOPBACK)
        self.assertIn("adb forward", msg)
        self.assertNotIn("trusted LAN", msg)

    def test_fingerprint_mismatch_passthrough(self):
        detail = "TLS fingerprint mismatch: expected AA, got BB"
        self.assertEqual(U.actionable_usb_error(detail, self.LOOPBACK), detail)

    def test_setup_hint_format(self):
        hint = U.usb_setup_hint(8080)
        self.assertIn("adb forward tcp:8080 tcp:8080", hint)
        self.assertIn("PHONE_MCP_URL=https://127.0.0.1:8080/mcp", hint)
        self.assertIn("scripts/usb-mcp.sh setup", hint)


class PickDeviceTest(unittest.TestCase):
    ONE = [{"serial": "ABC123", "state": "device", "detail": ""}]

    def test_auto_picks_single_device(self):
        serial, err = U.pick_usb_device(self.ONE, "")
        self.assertEqual(serial, "ABC123")
        self.assertEqual(err, "")

    def test_explicit_serial_ok(self):
        devs = self.ONE + [{"serial": "XYZ", "state": "device", "detail": ""}]
        serial, err = U.pick_usb_device(devs, "XYZ")
        self.assertEqual((serial, err), ("XYZ", ""))

    def test_multi_without_serial_needs_flag(self):
        devs = self.ONE + [{"serial": "XYZ", "state": "device", "detail": ""}]
        serial, err = U.pick_usb_device(devs, "")
        self.assertEqual(serial, "")
        self.assertIn("--serial", err)

    def test_none_attached(self):
        serial, err = U.pick_usb_device([], "")
        self.assertEqual(serial, "")
        self.assertIn("no adb devices", err)

    def test_unauthorized_hints_rsa(self):
        devs = [{"serial": "A", "state": "unauthorized", "detail": ""}]
        _, err = U.pick_usb_device(devs, "")
        self.assertIn("RSA", err)
        _, err2 = U.pick_usb_device(devs, "A")
        self.assertIn("RSA", err2)

    def test_offline_hints_reconnect(self):
        devs = [{"serial": "A", "state": "offline", "detail": ""}]
        _, err = U.pick_usb_device(devs, "A")
        self.assertIn("reconnect", err)

    def test_unknown_serial_names_known(self):
        _, err = U.pick_usb_device(self.ONE, "NOPE")
        self.assertIn("not attached", err)
        self.assertIn("ABC123", err)


class RunAdbTest(unittest.TestCase):
    def test_passthrough(self):
        with mock.patch("subprocess.run") as run:
            run.return_value = mock.Mock(returncode=0, stdout="ok\n", stderr="")
            self.assertEqual(U.run_adb(["adb", "devices"], 5), (0, "ok\n", ""))

    def test_missing_adb(self):
        with mock.patch("subprocess.run", side_effect=FileNotFoundError):
            rc, _, err = U.run_adb(["adb"], 5)
            self.assertEqual(rc, 127)
            self.assertIn("adb", err)


if __name__ == "__main__":
    unittest.main()
