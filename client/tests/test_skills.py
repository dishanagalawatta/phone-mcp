"""Bundled-skills staging + configure integration (no phone needed)."""

import os
import tempfile
import unittest
from pathlib import Path

from phone_mcp_client import cli as CLI
from phone_mcp_client import skills as S

URL = "https://192.168.1.50:8080/mcp"
TOKEN = "tok-live-secret-abc-123"


def _repo_skills_root() -> Path:
    here = Path(__file__).resolve()
    for parent in (here,) + tuple(here.parents):
        candidate = parent / ".agents" / "skills"
        if candidate.is_dir():
            return candidate
    raise RuntimeError("repo .agents/skills/ not found above client/tests/")


def _tree_files(root: Path) -> dict[str, bytes]:
    out = {}
    for dirpath, _dirnames, filenames in os.walk(root):
        for name in filenames:
            full = Path(dirpath) / name
            out[str(full.relative_to(root))] = full.read_bytes()
    return out


class BundleSyncTest(unittest.TestCase):
    def test_bundle_matches_repo_source(self):
        """The shipped copies must be byte-identical to .agents/skills/."""
        repo = _repo_skills_root()
        for skill_id in S.SKILL_IDS:
            with self.subTest(skill=skill_id):
                self.assertEqual(
                    _tree_files(Path(S.source_dir()) / skill_id),
                    _tree_files(repo / skill_id),
                )

    def test_all_six_bundled(self):
        self.assertEqual(len(S.SKILL_IDS), 6)
        for skill_id in S.SKILL_IDS:
            src = Path(S.source_dir()) / skill_id
            self.assertTrue((src / "SKILL.md").is_file(), skill_id)
            self.assertTrue(list(src.glob("references/*.md")), skill_id)


class StageTest(unittest.TestCase):
    def test_stage_copies_all_trees(self):
        with tempfile.TemporaryDirectory() as td:
            staged = S.stage_skills(os.path.join(td, "skills"))
            self.assertEqual(set(staged), set(S.SKILL_IDS))
            for skill_id in S.SKILL_IDS:
                base = Path(td) / "skills" / skill_id
                self.assertTrue((base / "SKILL.md").is_file(), skill_id)
                self.assertEqual(staged[skill_id], len(_tree_files(base)))

    def test_stage_ignores_static_bearer_examples(self):
        # Curl examples mention "Bearer" — only live secret *values* refuse.
        with tempfile.TemporaryDirectory() as td:
            staged = S.stage_skills(td, forbidden=("tok-live-secret-abc-123",))
            self.assertEqual(len(staged), 6)

    def test_stage_refuses_live_secret(self):
        with tempfile.TemporaryDirectory() as td:
            planted = Path(td) / "SKILL.md"
            planted.write_text("leaked tok-live-secret-abc-123\n", encoding="utf-8")
            with self.assertRaises(ValueError):
                S._check_clean([str(planted)], ("tok-live-secret-abc-123",))
            # ...but passes when no live secrets are configured.
            S._check_clean([str(planted)], ())

    def test_project_skills_dir(self):
        self.assertEqual(
            S.project_skills_dir("/proj"),
            os.path.join("/proj", ".agents", "skills"),
        )


class ConfigureSkillsTest(unittest.TestCase):
    def _configure(self, td, *extra):
        return CLI.main(["--url", URL, "--token", TOKEN,
                         "configure", "--harness", "opencode",
                         "--out", os.path.join(td, "gen"), *extra])

    def test_configure_stages_skills_by_default(self):
        with tempfile.TemporaryDirectory() as td:
            self.assertEqual(self._configure(td), 0)
            base = Path(td) / "gen" / "skills"
            for skill_id in S.SKILL_IDS:
                skill_file = base / skill_id / "SKILL.md"
                self.assertTrue(skill_file.is_file(), skill_id)
                text = skill_file.read_text(encoding="utf-8")
                self.assertNotIn(TOKEN, text)  # static markdown, never secrets

    def test_configure_no_skills_skips(self):
        with tempfile.TemporaryDirectory() as td:
            self.assertEqual(self._configure(td, "--no-skills"), 0)
            self.assertFalse((Path(td) / "gen" / "skills").exists())

    def test_configure_skills_dir_override(self):
        with tempfile.TemporaryDirectory() as td:
            custom = os.path.join(td, "elsewhere")
            self.assertEqual(self._configure(td, "--skills-dir", custom), 0)
            self.assertTrue((Path(custom) / "phone-mcp" / "SKILL.md").is_file())
            self.assertFalse((Path(td) / "gen" / "skills").exists())

    def test_install_syncs_project_skills(self):
        with tempfile.TemporaryDirectory() as td:
            rc = CLI.main(["--url", URL, "--token", TOKEN,
                           "configure", "--harness", "claude",
                           "--out", os.path.join(td, "gen"),
                           "--install", "--cwd", td])
            self.assertEqual(rc, 0)
            for skill_id in S.SKILL_IDS:
                self.assertTrue(
                    (Path(td) / ".agents" / "skills" / skill_id / "SKILL.md").is_file(),
                    skill_id)


if __name__ == "__main__":
    unittest.main()
