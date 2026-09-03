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

    def test_vector_metadata_omits_layout_text_segments(self):
        metadata = MODULE.vector_metadata({"metadata": {"sourceFile": "paper.pdf", "textSegments": [{"page": 1, "text": "正文"}]}})
        self.assertEqual({"sourceFile": "paper.pdf"}, metadata)

    def test_recognized_questions_track_page_text_segments(self):
        record = MODULE.recognized_questions(
            {"choices": [{"message": {"content": '{"questions":[{"number":"19","text":"19. 如图，直三棱柱","latex":["\\\\frac{1}{2}"]}]}'}}]},
            "paper.pdf",
            15,
            "terra",
            {},
        )
        self.assertEqual([{"page": 15, "text": "19. 如图，直三棱柱\n\\frac{1}{2}"}], record[0]["metadata"]["textSegments"])

    def test_merge_cross_page_questions_concatenate_text_segments(self):
        first = {"id": "first", "text": "19. 题干", "metadata": {"sourceFile": "paper.pdf", "page": 15, "questionNumber": "19", "latex": [], "continuesToNextPage": True, "textSegments": [{"page": 15, "text": "19. 题干"}]}}
        continuation = {"id": "second", "text": "解析续页", "metadata": {"sourceFile": "paper.pdf", "page": 16, "questionNumber": "", "latex": [], "continuesToNextPage": False, "textSegments": [{"page": 16, "text": "解析续页"}]}}

        merged = MODULE.merge_cross_page_questions([first, continuation])

        self.assertEqual([{"page": 15, "text": "19. 题干"}, {"page": 16, "text": "解析续页"}], merged[0]["metadata"]["textSegments"])

    def test_merge_same_number_deduplicates_shared_question_assets(self):
        shared_asset = {"assetId": "asset-1", "assetSha256": "aa"}
        other_asset = {"assetId": "asset-2", "assetSha256": "bb"}
        first = {"id": "first", "text": "3. 题干", "metadata": {"sourceFile": "paper.pdf", "page": 1, "questionNumber": "3", "latex": [], "continuesToNextPage": True, "questionAssets": [shared_asset, other_asset]}}
        continuation = {"id": "second", "text": "解析续页", "metadata": {"sourceFile": "paper.pdf", "page": 2, "questionNumber": "3", "latex": [], "continuesToNextPage": False, "questionAssets": [shared_asset, other_asset]}}

        merged = MODULE.merge_cross_page_questions([first, continuation])

        # 同号续页合并不得把整卷挂载的资产列表拼两遍（历史缺陷：manifest 出现重复题图）。
        self.assertEqual([shared_asset, other_asset], merged[0]["metadata"]["questionAssets"])

    def test_reconcile_repairs_mislabeled_number_from_printed_anchor(self):
        source = "paper.pdf"
        cone_stem = "已知圆锥的顶点为P，底面圆心为O，AB为底面直径，二面角为四十五度，则下列正确的是（　　）。"
        next_stem = "设O为坐标原点，直线l过抛物线C的焦点，且与C交于两点，则下列正确的是（　　）。"
        mislabeled = {"id": "x", "text": cone_stem, "metadata": {"sourceFile": source, "page": 5, "pageStart": 5, "questionNumber": "7"}}
        correct = {"id": "y", "text": next_stem, "metadata": {"sourceFile": source, "page": 6, "pageStart": 6, "questionNumber": "10"}}
        pages = {
            5: "故选：C。\n9." + cone_stem + "\n【答案】AC",
            6: "10." + next_stem + "\n【答案】AC",
        }
        MODULE.reconcile_question_numbers_from_page_text([mislabeled, correct], {source: pages})
        # 印刷题号 9 优先于视觉误标：误标 7 的圆锥题必须被纠正，紧随其后各自带题号的 10 题保持不变。
        self.assertEqual("9", mislabeled["metadata"]["questionNumber"])
        self.assertEqual("10", correct["metadata"]["questionNumber"])

    def test_reconcile_number_fallback_keeps_own_printed_number(self):
        source = "paper.pdf"
        # 签名与页文本有差异（模型对题干做了改写），只能走题号回退定位：
        # 回退定位停在题干自己的印刷题号上，锚点读取必须包含定位点，否则会被改成上一题的号。
        question = {"id": "x", "text": "设\\(f(x)=x^2\\)求其最小值与取等的条件。", "metadata": {"sourceFile": source, "page": 4, "pageStart": 4, "questionNumber": "5"}}
        page_text = "故选：C。\n5. 设 f(x)=x^2 求其最小值。"
        MODULE.reconcile_question_numbers_from_page_text([question], {source: {4: page_text}})
        self.assertEqual("5", question["metadata"]["questionNumber"])

    def test_solution_attachment_splits_solution_into_page_segments(self):
        source = MODULE.Path("2022年高考数学试卷（新高考Ⅰ卷）（解析卷）.pdf")
        question = {"id": "q19", "text": "19. 如图，直三棱柱", "metadata": {"sourceFile": source.name, "pageStart": 15, "pageEnd": 16, "questionNumber": "19", "textSegments": [{"page": 15, "text": "19. 如图，直三棱柱"}]}}
        pages = {
            15: "19. 如图，直三棱柱\n【答案】（1）√2\n第15页｜共2页",
            16: "【详解】建立坐标系。\n第16页｜共2页",
        }
        MODULE.attach_solution_sections(source, pages, [question])
        self.assertTrue(question["metadata"]["solutionAttached"])
        segments = question["metadata"]["textSegments"]
        self.assertEqual([15, 15, 16], [segment["page"] for segment in segments])
        # 每个分段必须逐字出现在最终正文里，发布阶段的插图定位依赖这一契约。
        body = question["text"]
        cursor = 0
        for segment in segments:
            position = body.find(segment["text"], cursor)
            self.assertGreaterEqual(position, 0)
            cursor = position + len(segment["text"])

    def test_place_question_figures_after_page_matched_reference_paragraph(self):
        body = "19. 如图，直三棱柱\n\n（1）求距离；\n\n【答案】\n\n【小问2详解】\n\n连接AE，如图\n\n坐标系如图\n\n解析收尾段落"
        segments = [
            {"page": 15, "text": "19. 如图，直三棱柱"},
            {"page": 16, "text": "【小问2详解】\n\n连接AE，如图\n\n坐标系如图\n\n解析收尾段落"},
        ]
        stem_figure = ({"pageNumber": 15, "pageHeightPixels": 1000, "bboxPixels": {"top": 400}}, "![stem](figures/q-019-01.png)")
        axis_figure = ({"pageNumber": 16, "pageHeightPixels": 1000, "bboxPixels": {"top": 850}}, "![axis](figures/q-019-02.png)")

        placed = MODULE.place_question_figures(body, segments, [stem_figure, axis_figure])

        # 页 15 的图紧随题干“19. 如图”段；页 16 的图贴近页中位置，落在“坐标系如图”段后。
        stem_position = placed.index("![stem]")
        self.assertIn("19. 如图，直三棱柱\n\n![stem]", placed)
        axis_position = placed.index("![axis]")
        self.assertIn("坐标系如图\n\n![axis]", placed)
        self.assertLess(stem_position, axis_position)

    def test_place_question_figures_falls_back_to_append_without_segments(self):
        body = "19. 题干正文"
        figure = ({"pageNumber": 15, "bboxPixels": {"top": 100}}, "![图](figures/q-019-01.png)")

        placed = MODULE.place_question_figures(body, [], [figure])

        self.assertEqual("19. 题干正文\n\n![图](figures/q-019-01.png)", placed)

    def test_locator_folds_notation_variants_by_generic_rules(self):
        # 记号折叠是 Unicode 通用规则（NFKC + 记号原子 + Pd 破折号类别），不是逐字形枚举表：
        # TeX ASCII 与预组合上下标、全角减号与各类破折号必须两侧收敛到同一规范形。
        self.assertEqual("x12+y2", MODULE._normalize_locator_text("x^{12}+y_2"))
        self.assertEqual("x12+y2", MODULE._normalize_locator_text("x¹²+y₂"))
        for dash in ("\u2010", "\u2013", "\u2014", "\u2015", "\u2212", "\uff0d"):
            self.assertEqual("a-1", MODULE._normalize_locator_text(f"a{dash}1"), repr(dash))
        compact, offsets = MODULE._compact_with_offsets("面积S=x^2+1")
        self.assertEqual("面积S=x2+1", compact)
        # 原子折叠后数字偏移指向原子起点（^ 处），供题号锚点邻域扫描使用。
        self.assertEqual(5, offsets[5])

    def test_solution_offset_uses_generic_bracket_heading(self):
        # 通用结构信号：任意行首【…】小节标题都命中，不依赖“答案”字样；
        # 行内【 】填空与整行无标题的段不误判为解析区。
        segment = "5. 求最小值\n设t=x+1。\n【考点】函数值域\n【解析】换元法"
        self.assertEqual(segment.index("【考点】"), MODULE._solution_segment_offset(segment))
        self.assertEqual(-1, MODULE._solution_segment_offset("6. 填空【 】后作答"))
        self.assertEqual(-1, MODULE._solution_segment_offset("7. 见行内【答案】混排"))
        # 转写模型把整页压成单行时，行边界是字面 \n 转义序列，同样必须是解析区起点信号。
        escaped = "3. 题干\\n\\n【答案】B"
        self.assertEqual(escaped.index("【答案】"), MODULE._solution_segment_offset(escaped))

    def test_solution_attachment_falls_back_to_structured_fields(self):
        # 页段没有小节标题行时，按输出契约的结构化字段组解析区（发布层格式化，不是猜文本）。
        source = MODULE.Path("2024年高考数学试卷（新课标Ⅱ卷）（解析卷）.pdf")
        question = {"id": "q1", "text": "3. 某题题干", "metadata": {
            "sourceFile": source.name, "pageStart": 1, "pageEnd": 1, "questionNumber": "3",
            "_transcriptionFields": {"answer": "B", "analysis": "因为条件成立"}}}
        MODULE.attach_solution_sections(source, {1: "3. 某题题干\n故选B因为条件成立"}, [question])
        self.assertTrue(question["metadata"]["solutionAttached"])
        self.assertIn("【答案】B", question["text"])
        self.assertIn("【解析】因为条件成立", question["text"])

    def test_contract_fields_never_reach_vector_metadata(self):
        # 学生版隔离红线：契约答案字段是发布临时量，必须被 vector_metadata 整体剔除。
        record = MODULE.recognized_questions(
            {"choices": [{"message": {"content": '{"questions":[{"number":"3","text":"3. 题干","answer":"B","analysis":"提示文字","figureAnchor":"如图"}]}'}}]},
            "paper.pdf", 1, "terra", {})
        self.assertEqual("B", record[0]["metadata"]["_transcriptionFields"]["answer"])
        vector_md = MODULE.vector_metadata(record[0])
        self.assertNotIn("_transcriptionFields", vector_md)
        self.assertNotIn("提示文字", str(vector_md))

    def test_place_question_figures_matches_non_listed_figure_wording(self):
        # 旧“如图|见图|…”词表命中不了“折线图”这类表述；单字信号“图”+ 几何比例必须插对位置。
        body = "8. 观察折线图\n\n求中位数\n\n由折线图读数得中位数为3"
        segments = [{"page": 1, "text": body}]
        figure = ({"pageNumber": 1, "pageHeightPixels": 1000, "bboxPixels": {"top": 950}}, "![t](figures/q-008-01.png)")

        placed = MODULE.place_question_figures(body, segments, [figure])

        self.assertIn("由折线图读数得中位数为3\n\n![t]", placed)


if __name__ == "__main__":
    unittest.main()
