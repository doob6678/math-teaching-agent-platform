import tempfile
import unittest
from pathlib import Path

from benchmarks.build_math_eval_set import build_eval_set, write_eval_set


class BuildMathEvalSetTest(unittest.TestCase):
    def test_build_eval_set_extracts_real_math_question_files_and_textbooks(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            root = Path(tmpdir)
            question_dir = root / "高中试卷"
            textbook_dir = root / "高中数学课本"
            question_dir.mkdir()
            textbook_dir.mkdir()
            (question_dir / "2026届高三数学模拟试题.md").write_text(
                "1. 已知函数 f(x)=x^2-2x，求函数的单调区间，并证明结论。",
                encoding="utf-8",
            )
            (question_dir / "2026届高三化学模拟试题.md").write_text(
                "1. 已知反应物 X，求化学平衡常数，并说明理由。",
                encoding="utf-8",
            )
            (textbook_dir / "人教A版高中数学必修第一册.md").write_text(
                "集合与函数概念：已知集合 A，求函数定义域并说明理由。",
                encoding="utf-8",
            )
            config = {
                "querySeeds": [],
                "questionRoots": [str(question_dir)],
                "textbookRoots": [str(textbook_dir)],
            }

            cases = build_eval_set(config, 5)

            self.assertGreaterEqual(len(cases), 2)
            self.assertTrue(any(case["sourceType"] == "localQuestionFile" for case in cases))
            self.assertTrue(any(case["sourceType"] == "localTextbookFile" for case in cases))
            self.assertTrue(any("函数" in case["query"] for case in cases))
            self.assertFalse(any("化学平衡" in case["query"] for case in cases))

    def test_write_eval_set_keeps_chinese_readable(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            output = Path(tmpdir) / "eval.jsonl"

            write_eval_set([{"id": "case-1", "query": "空间向量", "sourceType": "seed"}], output)

            self.assertIn("空间向量", output.read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
