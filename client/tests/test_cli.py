"""CLI tests (mocked transport; no phone needed)."""

import io
import json
import os
import tempfile
import unittest
from contextlib import redirect_stdout
from unittest import mock

from phone_mcp_client import cli as CLI


class ConfigureTest(unittest.TestCase):
    def test_renders_all_to_out(self):
        with tempfile.TemporaryDirectory() as td:
            rc = CLI.main(["--url", "https://192.168.1.50:8080/mcp",
                           "--token", "t", "configure",
                           "--harness", "all", "--out", td, "--print-only"])
            self.assertEqual(rc, 0)
            files = os.listdir(td)
            self.assertTrue(any("opencode" in f for f in files))
            self.assertTrue(any("codex" in f for f in files))
            self.assertTrue(any("vscode" in f for f in files))

    def test_unknown_harness_fails(self):
        with tempfile.TemporaryDirectory() as td:
            rc = CLI.main(["--url", "https://x/mcp", "--token", "t",
                           "configure", "--harness", "bogus", "--out", td])
            self.assertNotEqual(rc, 0)

    def test_install_project_scoped(self):
        with tempfile.TemporaryDirectory() as td:
            rc = CLI.main(["--url", "https://192.168.1.50:8080/mcp", "--token", "t",
                           "configure", "--harness", "claude vscode",
                           "--out", os.path.join(td, "gen"),
                           "--install", "--cwd", td])
            self.assertEqual(rc, 0)
            self.assertTrue(os.path.exists(os.path.join(td, ".mcp.json")))
            self.assertTrue(os.path.exists(os.path.join(td, ".vscode", "mcp.json")))


class PairTest(unittest.TestCase):
    def test_pair_qr_saves_0600(self):
        raw = json.dumps({"url": "https://192.168.1.50:8080/mcp", "token": "abc",
                          "sha256": "a" * 64})
        with tempfile.TemporaryDirectory() as td:
            out = os.path.join(td, "config.json")
            with redirect_stdout(io.StringIO()):
                rc = CLI.main(["pair", "--qr", raw, "--out", out])
            self.assertEqual(rc, 0)
            self.assertEqual(oct(os.stat(out).st_mode & 0o777), "0o600")

    def test_pair_rejects_bad_qr(self):
        with redirect_stdout(io.StringIO()):
            rc = CLI.main(["pair", "--qr", '{"url":"http://x"}', "--out",
                           os.path.join(tempfile.gettempdir(), "never.json")])
        self.assertNotEqual(rc, 0)


class CallListTest(unittest.TestCase):
    def test_list_json(self):
        with mock.patch("phone_mcp_client.protocol.tools_list",
                        return_value={"tools": [{"name": "vibrate"}]}):
            buf = io.StringIO()
            with redirect_stdout(buf):
                rc = CLI.main(["--url", "https://x/mcp", "--token", "t",
                               "list", "--json"])
            self.assertEqual(rc, 0)
            self.assertIn("vibrate", buf.getvalue())

    def test_call_text(self):
        with mock.patch("phone_mcp_client.protocol.tools_call",
                        return_value={"content": [{"type": "text", "text": "ok"}],
                                      "isError": False}):
            buf = io.StringIO()
            with redirect_stdout(buf):
                rc = CLI.main(["--url", "https://x/mcp", "--token", "t",
                               "call", "vibrate", "--args", "{}"])
            self.assertEqual(rc, 0)
            self.assertIn("ok", buf.getvalue())

    def test_call_iserror_exits_fail(self):
        with mock.patch("phone_mcp_client.protocol.tools_call",
                        return_value={"content": [{"type": "text", "text": "denied"}],
                                      "isError": True}):
            with redirect_stdout(io.StringIO()):
                rc = CLI.main(["--url", "https://x/mcp", "--token", "t",
                               "call", "camera_capture"])
            self.assertNotEqual(rc, 0)

    def test_bad_args_rejected(self):
        with redirect_stdout(io.StringIO()):
            rc = CLI.main(["--url", "https://x/mcp", "--token", "t",
                           "call", "vibrate", "--args", "not-json"])
        self.assertNotEqual(rc, 0)


class FlagPositionTest(unittest.TestCase):
    """Connection flags work before AND after the subcommand."""

    def test_configure_flags_after_subcommand(self):
        with tempfile.TemporaryDirectory() as td:
            buf = io.StringIO()
            with redirect_stdout(buf):
                rc = CLI.main(["configure", "--harness", "opencode",
                               "--out", td,
                               "--url", "https://192.168.1.50:8080/mcp",
                               "--token", "t"])
            self.assertEqual(rc, 0)
            self.assertTrue(os.path.exists(os.path.join(td, "opencode.phone-mcp.json")))

    def test_configure_flags_before_subcommand(self):
        with tempfile.TemporaryDirectory() as td:
            with redirect_stdout(io.StringIO()):
                rc = CLI.main(["--url", "https://192.168.1.50:8080/mcp",
                               "--token", "t",
                               "configure", "--harness", "generic", "--out", td])
            self.assertEqual(rc, 0)
            self.assertTrue(os.path.exists(os.path.join(td, "generic.phone-mcp.json")))

    def test_no_args_prints_help_usage(self):
        with redirect_stdout(io.StringIO()):
            rc = CLI.main([])
        self.assertEqual(rc, CLI.EXIT_USAGE)


class UsbCliTest(unittest.TestCase):
    """`phone-mcp usb` with mocked adb (no device needed)."""

    def _run_main(self, argv):
        buf = io.StringIO()
        with redirect_stdout(buf):
            rc = CLI.main(argv)
        return rc, buf.getvalue()

    def test_setup_explicit_serial_forwards(self):
        with mock.patch("phone_mcp_client.usb.run_adb",
                        return_value=(0, "", "")) as run, \
             mock.patch("phone_mcp_client.cli._usb_health_check",
                        return_value=(True, "https://127.0.0.1:8080/healthz → HTTP 200")):
            rc, out = self._run_main(["usb", "setup", "--serial", "ABC123"])
        self.assertEqual(rc, 0)
        self.assertIn("USB ready", out)
        argv = run.call_args[0][0]
        self.assertEqual(argv, ["adb", "-s", "ABC123", "forward", "tcp:8080", "tcp:8080"])
        self.assertNotIn("reverse", argv)

    def test_setup_auto_picks_single_device(self):
        calls = []

        def fake_run(args, timeout):
            calls.append(args)
            if args[:2] == ["adb", "devices"]:
                return (0, "List of devices attached\nONLY1\tdevice\n", "")
            return (0, "", "")

        with mock.patch("phone_mcp_client.usb.run_adb", side_effect=fake_run), \
             mock.patch("phone_mcp_client.cli._usb_health_check",
                        return_value=(True, "ok")):
            rc, _ = self._run_main(["usb", "setup"])
        self.assertEqual(rc, 0)
        self.assertIn(["adb", "-s", "ONLY1", "forward", "tcp:8080", "tcp:8080"], calls)

    def test_setup_multi_device_needs_serial(self):
        def fake_run(args, timeout):
            if args[:2] == ["adb", "devices"]:
                return (0, "List of devices attached\nA\tdevice\nB\tdevice\n", "")
            raise AssertionError(f"forward must not run: {args}")

        with mock.patch("phone_mcp_client.usb.run_adb", side_effect=fake_run):
            rc, _ = self._run_main(["usb", "setup"])
        self.assertNotEqual(rc, 0)

    def test_remove_absent_mapping_is_clean(self):
        with mock.patch("phone_mcp_client.usb.run_adb",
                        return_value=(1, "", "adb: error: listener 'tcp:8080' not found")):
            rc, out = self._run_main(["usb", "remove", "--serial", "ABC123"])
        self.assertEqual(rc, 0)
        self.assertIn("already clean", out)

    def test_status_lists_devices_and_forwards(self):
        def fake_run(args, timeout):
            if args == ["adb", "devices", "-l"]:
                return (0, "List of devices attached\nABC123\tdevice\n", "")
            if args == ["adb", "forward", "--list"]:
                return (0, "ABC123 tcp:8080 tcp:8080\n", "")
            raise AssertionError(f"unexpected adb: {args}")

        with mock.patch("phone_mcp_client.usb.run_adb", side_effect=fake_run):
            rc, out = self._run_main(["usb", "status"])
        self.assertEqual(rc, 0)
        self.assertIn("ABC123", out)
        self.assertIn("tcp:8080", out)

    def test_check_uses_health_only(self):
        with mock.patch("phone_mcp_client.usb.run_adb",
                        side_effect=AssertionError("adb must not run on check")), \
             mock.patch("phone_mcp_client.cli._usb_health_check",
                        return_value=(True, "https://127.0.0.1:8080/healthz → HTTP 200")):
            rc, out = self._run_main(["usb", "check"])
        self.assertEqual(rc, 0)
        self.assertIn("PASS", out)


if __name__ == "__main__":
    unittest.main()
