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


if __name__ == "__main__":
    unittest.main()
