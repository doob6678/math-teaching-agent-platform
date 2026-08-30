"""Tests for the real-run image placement audit."""
from __future__ import annotations

import importlib.util
import json
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).parents[1] / "audit_handout_image_placement.py"
SPEC = importlib.util.spec_from_file_location("handout_image_audit", SCRIPT)
assert SPEC and SPEC.loader
module = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = module
SPEC.loader.exec_module(module)

ASSET = "e142a7bf-a43b-41af-aeaf-90e7f3bd909b"


class PlacementAuditTest(unittest.TestCase):
    def test_heading_parser_accepts_writer_heading_shapes(self) -> None:
        lines = ["### 题 $7$", "## 题目6 焦点弦", "## 7. Focus chord"]
        self.assertEqual(module.numbered_heading(lines, 7), [(0, "### 题 $7$", 3), (2, "## 7. Focus chord", 2)])
        self.assertEqual(module.numbered_heading(lines, 6), [(1, "## 题目6 焦点弦", 2)])

    def test_writer_audit_reports_insertion_line_and_rejects_duplicate_heading(self) -> None:
        stage = {
            "stageCode": "teacher_writer",
            "generatedContent": "## 7. Focus chord\n正文\n",
            "assetPlacements": [{
                "questionNumber": 7,
                "assetIds": [ASSET],
                "anchor": "question",
                "layout": "single",
                "variants": ["teacher_writer"],
                "caption": "图",
            }],
        }
        audit = module.writer_audit(stage, "teacher_writer")
        self.assertEqual(audit["placements"][0]["insertionLine"], 2)
        self.assertEqual(audit["errors"], [])

        stage["generatedContent"] = "## 7. First\n正文\n## 7. Duplicate\n正文\n"
        self.assertEqual(module.writer_audit(stage, "teacher_writer")["errors"], ["duplicate-heading"])

    def test_source_audit_prefers_transparent_reference_and_filename(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            run_dir = Path(temporary)
            (run_dir / "retrieval-source-original.json").write_text(json.dumps({
                "teacherResourceHits": [
                    {"documentId": "doc-1", "imageAssetIds": [ASSET]},
                    {"documentId": "doc-1", "fileName": "source.md", "transparentReference": "feishu://block-1",
                     "assetRefs": [{"assetId": ASSET}]},
                ]
            }), encoding="utf-8")
            result = module.source_audit(run_dir, {ASSET})
            self.assertEqual(result["errors"], [])
            self.assertEqual(result["matches"][0]["evidenceRef"], "feishu://block-1")
            self.assertEqual(result["matches"][0]["fileName"], "source.md")


if __name__ == "__main__":
    unittest.main()
