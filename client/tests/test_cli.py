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


if __name__ == "__main__":
    unittest.main()
