"""Negative cases for the current-document and generated-catalog guard."""
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import check_agent_governance as governance


class GovernanceCheckTest(unittest.TestCase):
    def test_primary_entry_links_are_checked_and_catalog_drift_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            docs = Path(directory) / "agent/docs"
            docs.mkdir(parents=True)
            names = ["配置目录.md", "开发与维护规范.md", "运维与验收手册.md",
                     "Agent架构与工程治理技术设计.md", "项目进度.md"]
            for name in names:
                (docs / name).write_text("# Current\n", encoding="utf-8")
            (docs / "README.md").write_text("\n".join(f"[doc]({name})" for name in names), encoding="utf-8")
            (docs.parent / "README.md").write_text("# Agent\n", encoding="utf-8")
            with patch.object(governance, "DOCS", docs), patch.object(governance, "CATALOG", docs / "配置目录.md"), \
                    patch.object(governance, "catalog", return_value=("# Current\n", [])):
                self.assertEqual([], governance.check())
                for path in [docs / "Agent架构与工程治理技术设计.md", docs / "项目进度.md", docs.parent / "README.md"]:
                    original = path.read_text(encoding="utf-8")
                    path.write_text(original + "\n[broken](missing-file.md)", encoding="utf-8")
                    self.assertTrue(any("Broken local link" in e for e in governance.check()), path)
                    path.write_text(original, encoding="utf-8")
                (docs / "配置目录.md").write_text("stale", encoding="utf-8")
                self.assertTrue(any("catalog drift" in e for e in governance.check()))
                (docs / "unlisted.md").write_text("# New", encoding="utf-8")
                self.assertTrue(any("Unclassified document" in e for e in governance.check()))


if __name__ == "__main__":
    unittest.main()
