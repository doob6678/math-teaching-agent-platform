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

    def test_merges_same_numbered_continuation(self):
        first = {"id": "first", "text": "3. 题干前半", "metadata": {"sourceFile": "paper.pdf", "page": 1, "questionNumber": "3", "latex": [], "continuesToNextPage": True}}
        continuation = {"id": "continuation", "text": "题干后半", "metadata": {"sourceFile": "paper.pdf", "page": 2, "questionNumber": "3", "latex": [], "continuesToNextPage": False}}

        merged = MODULE.merge_cross_page_questions([first, continuation])
        self.assertEqual(1, len(merged))
        self.assertIn("题干前半", merged[0]["text"])
        self.assertIn("题干后半", merged[0]["text"])
        self.assertEqual(2, merged[0]["metadata"]["pageEnd"])
    def test_normalizes_only_unambiguous_printed_question_numbers(self):
        self.assertEqual("1", MODULE.canonical_question_number("第 1 题"))
        self.assertEqual("12", MODULE.canonical_question_number("12．"))
        self.assertEqual("20", MODULE.canonical_question_number("20、"))

    def test_rejects_subquestions_and_descriptive_question_labels(self):
        for value in ("（1）", "小问3", "6（题干未显示）", "未显示（上一问续）", "题号不清"):
            self.assertEqual("", MODULE.canonical_question_number(value))
    def test_uses_first_visual_record_for_duplicate_paper_question_number(self):
        early = {"id": "early", "text": "first", "metadata": {"sourceFile": "paper.pdf", "sourceSha256": "a" * 64, "page": 1, "questionNumber": "7"}}
        late = {"id": "late", "text": "later", "metadata": {"sourceFile": "paper.pdf", "sourceSha256": "a" * 64, "page": 2, "questionNumber": "7"}}
        other = {"id": "other", "text": "other", "metadata": {"sourceFile": "paper.pdf", "sourceSha256": "a" * 64, "page": 2, "questionNumber": "8"}}
        self.assertEqual([early, other], MODULE.canonical_question_records([late, other, early]))
    def test_stable_question_id_ignores_page_text_and_solution_changes(self):
        source_hash = "a" * 64
        first = MODULE.canonical_question_id(source_hash, "7")
        second = MODULE.canonical_question_id(source_hash, "第 7 题")
        self.assertEqual(first, second)
        self.assertLessEqual(len(first), 64)

    def test_stable_question_id_separates_source_and_number(self):
        self.assertNotEqual(MODULE.canonical_question_id("a" * 64, "7"), MODULE.canonical_question_id("b" * 64, "7"))
        self.assertNotEqual(MODULE.canonical_question_id("a" * 64, "7"), MODULE.canonical_question_id("a" * 64, "8"))

    def test_stable_question_id_rejects_invalid_source_or_number(self):
        with self.assertRaises(ValueError):
            MODULE.canonical_question_id("paper", "7")
        with self.assertRaises(ValueError):
            MODULE.canonical_question_id("a" * 64, "（1）")

    def test_canonical_records_keep_same_source_different_numbers(self):
        source_hash = "a" * 64
        questions = [
            {"id": "late", "text": "later", "metadata": {"sourceFile": "paper.pdf", "sourceSha256": source_hash, "page": 2, "questionNumber": "7"}},
            {"id": "other", "text": "other", "metadata": {"sourceFile": "paper.pdf", "sourceSha256": source_hash, "page": 2, "questionNumber": "8"}},
            {"id": "early", "text": "first", "metadata": {"sourceFile": "paper.pdf", "sourceSha256": source_hash, "page": 1, "questionNumber": "7"}},
        ]
        selected, duplicate_count = MODULE.canonical_question_records(questions, with_stats=True)
        self.assertEqual(["early", "other"], [item["id"] for item in selected])
        self.assertEqual(1, duplicate_count)

    def test_repairs_one_unique_question_number_collision(self):
        questions = [
            {"id": "q1", "text": "一", "metadata": {"sourceFile": "paper.pdf", "pageStart": 1, "questionNumber": "1"}},
            {"id": "q2", "text": "二", "metadata": {"sourceFile": "paper.pdf", "pageStart": 2, "questionNumber": "2"}},
            {"id": "q3", "text": "误标三", "metadata": {"sourceFile": "paper.pdf", "pageStart": 3, "questionNumber": "2"}},
            {"id": "q4", "text": "四", "metadata": {"sourceFile": "paper.pdf", "pageStart": 4, "questionNumber": "4"}},
        ]
        repaired = MODULE.repair_question_number_collisions(questions)
        self.assertEqual(["1", "2", "3", "4"], [item["metadata"]["questionNumber"] for item in repaired])

    def test_repairs_one_unique_question_number_collision_with_stats(self):
        questions = [
            {"id": "q1", "text": "一", "metadata": {"sourceFile": "paper.pdf", "pageStart": 1, "questionNumber": "1"}},
            {"id": "q2", "text": "二", "metadata": {"sourceFile": "paper.pdf", "pageStart": 2, "questionNumber": "2"}},
            {"id": "q3", "text": "误标三", "metadata": {"sourceFile": "paper.pdf", "pageStart": 3, "questionNumber": "2"}},
            {"id": "q4", "text": "四", "metadata": {"sourceFile": "paper.pdf", "pageStart": 4, "questionNumber": "4"}},
        ]
        repaired, collision_count = MODULE.repair_question_number_collisions(questions, with_stats=True)
        self.assertEqual(["1", "2", "3", "4"], [item["metadata"]["questionNumber"] for item in repaired])
        self.assertEqual(1, collision_count)

    def test_collision_stats_count_removed_ambiguous_duplicates(self):
        questions = [
            {"id": "early", "text": "早题", "metadata": {"sourceFile": "paper.pdf", "pageStart": 1, "questionNumber": "2"}},
            {"id": "late", "text": "重复片段", "metadata": {"sourceFile": "paper.pdf", "pageStart": 5, "questionNumber": "2"}},
        ]
        retained, collision_count = MODULE.repair_question_number_collisions(questions, with_stats=True)
        self.assertEqual(["early"], [item["id"] for item in retained])
        self.assertEqual(1, collision_count)

    def test_solution_attachment_keeps_question_without_answer(self):
        source = MODULE.Path("2024年高考数学试卷（新课标Ⅱ卷）（解析卷）.pdf")
        question = {"id": "q1", "text": "没有解析的题干", "metadata": {"sourceFile": source.name, "pageStart": 1, "pageEnd": 1, "questionNumber": "1"}}
        MODULE.attach_solution_sections(source, {1: "1．没有解析的题干\n第1页｜共1页"}, [question])
        self.assertEqual("没有解析的题干", question["text"])
        self.assertFalse(question["metadata"]["solutionAttached"])
        source = MODULE.Path("2024年高考数学试卷（新课标Ⅱ卷）（解析卷）.pdf")
        question = {"id": "q19", "text": "已知双曲线 C", "metadata": {"sourceFile": source.name, "pageStart": 1, "pageEnd": 3, "questionNumber": "19"}}
        pages = {
            1: "19．已知双曲线 C\n【答案】见解析\n【解析】\n【分析】先建立关系。",
            2: "（1）求坐标。\n【详解】计算得到结果。",
            3: "（2）证明。\n【详解】继续证明。\n第3页｜共3页",
        }
        MODULE.attach_solution_sections(source, pages, [question])
        self.assertIn("【答案】", question["text"])
        self.assertIn("【详解】继续证明", question["text"])
        self.assertEqual([1, 2, 3], question["metadata"]["sourcePages"])
        self.assertTrue(question["metadata"]["solutionAttached"])

    def test_vector_metadata_omits_redundant_latex_but_keeps_safe_provenance(self):
        metadata = MODULE.vector_metadata({"metadata": {"sourceFile": "paper.pdf", "latex": ["\\frac{1}{2}"], "page": 1, "questionAssets": [{"assetId": "asset-1", "_sourceAssetPath": "/mnt/private.png"}]}})
        self.assertEqual({"sourceFile": "paper.pdf", "page": 1, "questionAssets": [{"assetId": "asset-1"}]}, metadata)


if __name__ == "__main__":
    unittest.main()
