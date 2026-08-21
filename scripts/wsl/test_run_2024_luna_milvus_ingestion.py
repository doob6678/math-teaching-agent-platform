"""Contract tests for the auditable Luna ingestion helpers.

The module is intentionally dependency-light so these checks run against the real script
without requiring a model, Docker, or Milvus service.
"""
import importlib.util
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).with_name("run_2024_luna_milvus_ingestion.py")
SPEC = importlib.util.spec_from_file_location("luna_ingestion", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC and SPEC.loader
SPEC.loader.exec_module(MODULE)


class LunaIngestionContractTest(unittest.TestCase):
    """Locks the source-safe transformations that precede any vector write."""

    def test_rejects_slash_fraction_in_latex(self):
        with self.assertRaisesRegex(RuntimeError, "\\\\frac"):
            MODULE.recognized_questions(
                {"choices": [{"message": {"content": '{"questions":[{"number":"1","text":"求值","latex":["x/2"]}]}'}}]},
                "paper.pdf",
                1,
                "terra",
                {},
            )

    def test_merges_continuation_with_next_page_fragment(self):
        first = {"id": "first", "text": "17. 已知函数", "metadata": {"sourceFile": "paper.pdf", "page": 3, "questionNumber": "17", "latex": [], "continuesToNextPage": True}}
        continuation = {"id": "second", "text": "求其最大值", "metadata": {"sourceFile": "paper.pdf", "page": 4, "questionNumber": "", "latex": ["\\frac{1}{2}"], "continuesToNextPage": False}}

        merged = MODULE.merge_cross_page_questions([first, continuation])

        self.assertEqual(1, len(merged))
        self.assertEqual("17. 已知函数\n求其最大值\n\\frac{1}{2}", merged[0]["text"])
        self.assertEqual(3, merged[0]["metadata"]["pageStart"])
        self.assertEqual(4, merged[0]["metadata"]["pageEnd"])
        self.assertFalse(merged[0]["metadata"]["continuesToNextPage"])

    def test_does_not_merge_different_numbered_question(self):
        first = {"id": "first", "text": "17. 题干", "metadata": {"sourceFile": "paper.pdf", "page": 3, "questionNumber": "17", "latex": [], "continuesToNextPage": True}}
        next_question = {"id": "second", "text": "18. 新题", "metadata": {"sourceFile": "paper.pdf", "page": 4, "questionNumber": "18", "latex": [], "continuesToNextPage": False}}

        self.assertEqual([first, next_question], MODULE.merge_cross_page_questions([first, next_question]))
    def test_normalizes_only_unambiguous_printed_question_numbers(self):
        self.assertEqual("1", MODULE.canonical_question_number("第 1 题"))
        self.assertEqual("12", MODULE.canonical_question_number("12．"))
        self.assertEqual("20", MODULE.canonical_question_number("20、"))

    def test_rejects_subquestions_and_descriptive_question_labels(self):
        for value in ("（1）", "小问3", "6（题干未显示）", "未显示（上一问续）", "题号不清"):
            self.assertEqual("", MODULE.canonical_question_number(value))
    def test_uses_first_visual_record_for_duplicate_paper_question_number(self):
        early = {"id": "early", "text": "first", "metadata": {"sourceFile": "paper.pdf", "page": 1, "questionNumber": "7"}}
        late = {"id": "late", "text": "later", "metadata": {"sourceFile": "paper.pdf", "page": 2, "questionNumber": "7"}}
        other = {"id": "other", "text": "other", "metadata": {"sourceFile": "paper.pdf", "page": 2, "questionNumber": "8"}}
        self.assertEqual([early, other], MODULE.canonical_question_records([late, other, early]))
    def test_vector_metadata_omits_redundant_latex_but_keeps_safe_provenance(self):
        metadata = MODULE.vector_metadata({"metadata": {"sourceFile": "paper.pdf", "latex": ["\\frac{1}{2}"], "page": 1, "questionAssets": [{"assetId": "asset-1", "_sourceAssetPath": "/mnt/private.png"}]}})
        self.assertEqual({"sourceFile": "paper.pdf", "page": 1, "questionAssets": [{"assetId": "asset-1"}]}, metadata)


if __name__ == "__main__":
    unittest.main()
