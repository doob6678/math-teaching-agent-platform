"""Contract tests for the Gaokao question-asset extraction binding rules.

These checks run against the real script without GPU models; only the pure anchor
selection and printed-number rules are locked here.
"""
from __future__ import annotations

import importlib.util
from pathlib import Path
import unittest


MODULE_PATH = Path(__file__).with_name("extract_gaokao_question_assets.py")
SPEC = importlib.util.spec_from_file_location("gaokao_question_assets", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC and SPEC.loader
SPEC.loader.exec_module(MODULE)


class GaokaoQuestionAssetBindingTest(unittest.TestCase):
    """题图必须绑定到全文阅读顺序中它上方最近的印刷题号，跨页续题也不例外。"""

    def test_binds_figure_on_continuation_page_to_previous_page_number(self):
        anchors = [(15, 200, 700, "19")]

        self.assertEqual("19", MODULE.select_question(anchors, 16, (200, 850, 600, 1200)))

    def test_same_page_number_above_beats_previous_page(self):
        anchors = [(15, 200, 700, "19"), (16, 150, 200, "20")]

        self.assertEqual("20", MODULE.select_question(anchors, 16, (150, 700, 500, 1100)))

    def test_figure_above_same_page_number_binds_to_previous_page(self):
        anchors = [(15, 200, 700, "19"), (16, 150, 1400, "20")]

        self.assertEqual("19", MODULE.select_question(anchors, 16, (150, 200, 500, 500)))

    def test_figure_before_any_number_stays_unbound(self):
        self.assertIsNone(MODULE.select_question([(3, 100, 400, "3")], 2, (100, 200, 400, 600)))

    def test_printed_question_number_requires_line_start_and_explicit_separator(self):
        self.assertEqual("19", MODULE.QUESTION_NUMBER.match("19. 如图，直三棱柱").group(1))
        self.assertEqual("5", MODULE.QUESTION_NUMBER.match("5.从2至8的7个整数").group(1))
        self.assertEqual("10", MODULE.QUESTION_NUMBER.match("10．已知函数").group(1))
        # 选项行“A. 1”、正文“共 7 种”、小问“（1）”、小数“1.6×10⁹m³”都不得再充当题号锚点。
        for text in ("A. 1", "B. 3", "共 7 种，", "（2）设D为", "第16页｜共23页", "故选：D", "1.6×109m3"):
            self.assertIsNone(MODULE.QUESTION_NUMBER.match(text))


if __name__ == "__main__":
    unittest.main()
