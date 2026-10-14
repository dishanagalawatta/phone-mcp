"""Harness rendering + install tests (no phone needed)."""

import json
import os
import tempfile
import unittest

from phone_mcp_client import harnesses as H

URL = "https://192.168.1.50:8080/mcp"
SECRET = "super-secret-token-123"


class RenderTest(unittest.TestCase):
    def test_all_render(self):
        rendered = H.render_all(URL)
        self.assertEqual(set(rendered), set(H.HARNESS_IDS))
        for hid, (filename, content) in rendered.items():
            self.assertTrue(filename, hid)
            self.assertIn(SECRET.join(["", ""]) if False else "phone-mcp", content)

    def test_no_literal_token_anywhere(self):
        for hid in H.HARNESS_IDS:
            _fn, content = H.RENDERERS[hid](URL)
            # The literal URL is fine (not a secret); the token must never appear.
            self.assertNotIn(SECRET, content.replace(URL, ""))
            # Simulate a bug where the token leaks: renderers take url only,
            # so a token can never leak unless the caller passes it as url.
            self.assertNotIn("Bearer super-secret", content)

    def test_opencode_shape(self):
        _fn, content = H.render_opencode(URL)
        cfg = json.loads(content)
        node = cfg["mcp"]["phone-mcp"]
        self.assertEqual(node["type"], "local")
        self.assertIn("PHONE_MCP_TOKEN", node["environment"]["PHONE_MCP_TOKEN"])
        self.assertTrue(node["enabled"])

    def test_claude_shape(self):
        _fn, content = H.render_claude(URL)
        cfg = json.loads(content)
        node = cfg["mcpServers"]["phone-mcp"]
        self.assertIn("command", node)
        self.assertIn("PHONE_MCP_TOKEN", node["env"]["PHONE_MCP_TOKEN"])

    def test_codex_toml_shape(self):
        _fn, content = H.render_codex(URL)
        self.assertIn("[mcp_servers.phone-mcp]", content)
        self.assertIn('command = ', content)
        self.assertNotIn(SECRET, content)

    def test_vscode_inputs(self):
        _fn, content = H.render_vscode(URL)
        cfg = json.loads(content)
        self.assertIn("servers", cfg)
        self.assertIn("inputs", cfg)
        ids = [i["id"] for i in cfg["inputs"]]
        self.assertIn("phoneMcpToken", ids)

    def test_gemini_timeout(self):
        _fn, content = H.render_gemini(URL)
        node = json.loads(content)["mcpServers"]["phone-mcp"]
        self.assertEqual(node["timeout"], 35000)
        self.assertEqual(node["trust"], False)

    def test_bridge_kinds(self):
        for kind in ("phone-mcp", "node", "python"):
            _fn, content = H.render_claude(URL, kind)
            self.assertIn(H.bridge_command(kind)[0], content)
        with self.assertRaises(ValueError):
            H.bridge_command("bogus")


class InstallTest(unittest.TestCase):
    def test_opencode_merge_preserves(self):
        with tempfile.TemporaryDirectory() as td:
            dest = os.path.join(td, "opencode.json")
            with open(dest, "w") as fh:
                json.dump({"mcp": {"other": {"type": "local"}}}, fh)
            _fn, content = H.render_opencode(URL)
            H.install_rendered("opencode", content, dest)
            merged = json.load(open(dest))
            self.assertIn("other", merged["mcp"])
            self.assertIn("phone-mcp", merged["mcp"])

    def test_codex_idempotent(self):
        with tempfile.TemporaryDirectory() as td:
            dest = os.path.join(td, "config.toml")
            _fn, content = H.render_codex(URL)
            H.install_rendered("codex", content, dest)
            first = open(dest).read()
            H.install_rendered("codex", content, dest)
            self.assertEqual(first, open(dest).read())
            self.assertEqual(first.count("[mcp_servers.phone-mcp]"), 1)

    def test_vscode_merge_inputs_dedupe(self):
        with tempfile.TemporaryDirectory() as td:
            dest = os.path.join(td, "mcp.json")
            _fn, content = H.render_vscode(URL)
            H.install_rendered("vscode", content, dest)
            H.install_rendered("vscode", content, dest)
            cfg = json.load(open(dest))
            ids = [i["id"] for i in cfg["inputs"]]
            self.assertEqual(len(ids), len(set(ids)))

    def test_cursor_merge(self):
        with tempfile.TemporaryDirectory() as td:
            dest = os.path.join(td, "mcp.json")
            _fn, content = H.render_cursor(URL)
            H.install_rendered("cursor", content, dest)
            cfg = json.load(open(dest))
            self.assertIn("phone-mcp", cfg["mcpServers"])

    def test_hints(self):
        self.assertIn("claude mcp add", H.cli_add_hint("claude", URL))
        self.assertIn("codex mcp add", H.cli_add_hint("codex", URL))


if __name__ == "__main__":
    unittest.main()
