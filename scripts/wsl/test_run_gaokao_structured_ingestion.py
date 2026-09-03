"""v2 结构化协议管线的契约单测（run_gaokao_structured_ingestion.py）。

与 test_run_2024_luna_milvus_ingestion.py 同风格：依赖轻、纯离线，不起 Docker/Milvus/模型，
importlib 直接加载被测脚本。测试锁定的是“协议 -> 确定性装配”的契约：
模型按约定输出结构，管线只处理自己签发的 FIGURE token 与协议字段，绝不匹配自然语言正文。
"""
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).with_name("run_gaokao_structured_ingestion.py")
SPEC = importlib.util.spec_from_file_location("gaokao_structured_ingestion", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC and SPEC.loader
SPEC.loader.exec_module(MODULE)


def parse_questions(content: str, page: int = 1, assets: dict | None = None) -> list[dict]:
    """按 v2 契约解析一页模型响应，返回结构化记录列表。"""
    return MODULE.recognized_questions(
        {"choices": [{"message": {"content": content}}]},
        "paper.pdf", page, "terra", assets or {},
    )


class StructuredProtocolContractTest(unittest.TestCase):
    """锁定 v2 输出协议解析与装配行为。"""

    def test_accepts_pure_digit_number(self):
        records = parse_questions('{"questions":[{"number":"19","stem":"如图，直三棱柱","answer":"","analysis":"","figureCount":1}]}')
        self.assertEqual(1, len(records))
        self.assertEqual("19", records[0]["metadata"]["questionNumber"])

    def test_rejects_printed_number_variants_as_untrusted_structure(self):
        # v2 契约规定 number 只能是打印数字；"19."/"第19题" 是违规输出，不再走
        # CANONICAL_QUESTION_NUMBER_PATTERN 归一化（那是被废弃的对正文/字段的正则猜测），
        # 非纯数字且非续页片段 -> 丢弃，与旧管线“非规范编号不建立独立发布身份”一致。
        for number in ("19.", "第19题", "19、"):
            records = parse_questions(json.dumps({"questions": [
                {"number": number, "stem": "题干", "answer": "", "analysis": "", "figureCount": 0}]}))
            self.assertEqual([], records, number)

    def test_non_numeric_number_kept_only_as_continuation_fragment(self):
        records = parse_questions('{"questions":[{"number":"","stem":"接上一页的题干后半","answer":"","analysis":"","figureCount":0,"continuesToNextPage":true}]}')
        self.assertEqual(1, len(records))
        self.assertEqual("", records[0]["metadata"]["questionNumber"])
        self.assertTrue(records[0]["metadata"]["continuesToNextPage"])

    def test_analysis_only_continuation_fragment_is_kept(self):
        # v2 删除了从 pageText 反推解析的路径：解析卷续页片段若只把正文落在 analysis
        # （stem 为空）也必须保留，否则解析续文会被静默丢失（q19 第 16-17 页即此形态）。
        records = parse_questions('{"questions":[{"number":"","stem":"","answer":"","analysis":"建立坐标系，[[FIGURE1]]如图。","figureCount":1,"continuesToNextPage":false}]}')
        self.assertEqual(1, len(records))
        self.assertEqual("【解析】建立坐标系，[[FIGURE1]]如图。", records[0]["text"])

    def test_empty_number_is_protocol_fragment_even_without_continuation_flag(self):
        # number 为空串是协议规定的“本页无打印题号”片段证据；解析尾段不再往后翻页，
        # 若照旧要求 continuesToNextPage 会丢掉最后一段解析。
        records = parse_questions('{"questions":[{"number":"","stem":"","answer":"","analysis":"解析收尾段落","figureCount":0}]}')
        self.assertEqual(1, len(records))

    def test_all_empty_record_is_skipped(self):
        # 三方向全空的空壳记录没有可发布内容，静默跳过（对齐旧管线“空 text 即跳”）。
        records = parse_questions('{"questions":[{"number":"5","stem":"","answer":"","analysis":"","figureCount":0}]}')
        self.assertEqual([], records)

    def test_assembles_answer_and_analysis_with_three_branches(self):
        base = {"number": "3", "figureCount": 0, "latex": []}
        plain = parse_questions(json.dumps({"questions": [dict(base, stem="求表达式值", answer="", analysis="")]}))
        self.assertEqual("求表达式值", plain[0]["text"])
        answer_only = parse_questions(json.dumps({"questions": [dict(base, stem="求表达式值", answer="B", analysis="")]}))
        self.assertEqual("求表达式值\n\n【答案】B", answer_only[0]["text"])
        both = parse_questions(json.dumps({"questions": [dict(base, stem="求表达式值", answer="B", analysis="换元法")]}))
        self.assertEqual("求表达式值\n\n【答案】B\n\n【解析】换元法", both[0]["text"])

    def test_assembled_body_never_contains_printed_number(self):
        # 题号由发布标题（# ... 第 N 题）承载；“19题/19.”重复正是旧管线让模型把打印
        # 题号混进 text 再由正则反推造成的，v2 协议保证正文不含题号。
        records = parse_questions('{"questions":[{"number":"19","stem":"求该表达式的值","answer":"","analysis":"","figureCount":0}]}')
        self.assertNotIn("19", records[0]["text"])

    def test_solution_attached_flag_reflects_protocol_fields(self):
        without = parse_questions('{"questions":[{"number":"1","stem":"题干","answer":"","analysis":"","figureCount":0}]}')
        self.assertFalse(without[0]["metadata"]["solutionAttached"])
        with_answer = parse_questions('{"questions":[{"number":"1","stem":"题干","answer":"B","analysis":"","figureCount":0}]}')
        self.assertTrue(with_answer[0]["metadata"]["solutionAttached"])

    def test_latex_stays_out_of_assembled_body(self):
        # 规则 2 的装配模板只有 stem/答案/解析；旧管线“text 尾拼 latex 行”在多段
        # 跨页解析合并后会把整批公式重复堆成裸行（q19 实测回归防护）。latex 仍存
        # 元数据并继续被斜杠分数门禁校验。
        records = parse_questions('{"questions":[{"number":"2","stem":"求 \\\\frac{1}{2} 的值","answer":"B","analysis":"","figureCount":0,"latex":["\\\\frac{1}{2}"]}]}')
        self.assertEqual("求 \\frac{1}{2} 的值\n\n【答案】B", records[0]["text"])
        self.assertEqual(["\\frac{1}{2}"], records[0]["metadata"]["latex"])

    def test_figure_count_mismatch_is_recorded_not_guessed(self):
        # figureCount 只是协议自检字段：装配以 stem 中确定性 token 实数为准，
        # 不一致时记录告警供人工回看，绝不做“猜图”兜底。
        records = parse_questions('{"questions":[{"number":"1","stem":"[[FIGURE1]]图甲，[[FIGURE2]]图乙","answer":"","analysis":"","figureCount":5}]}')
        self.assertEqual(2, records[0]["metadata"]["figureCount"])
        self.assertEqual({"declared": 5, "markers": 2}, records[0]["metadata"]["figureCountMismatch"])

    def test_rejects_slash_fraction_in_latex(self):
        # 质量门禁保留：斜杠分数是书写规范问题，不是结构匹配。
        with self.assertRaisesRegex(RuntimeError, "\\\\frac"):
            parse_questions('{"questions":[{"number":"1","stem":"求值","answer":"","analysis":"","figureCount":0,"latex":["x/2"]}]}')

    def test_vector_text_strips_figure_markers(self):
        records = parse_questions('{"questions":[{"number":"19","stem":"[[FIGURE1]]如图，直三棱柱","answer":"B","analysis":"","figureCount":1}]}')
        vector_text = MODULE.vector_text_of(records[0])
        self.assertNotIn("[[FIGURE", vector_text)
        self.assertIn("如图，直三棱柱", vector_text)
        self.assertIn("【答案】B", vector_text)

    def test_contract_fields_never_reach_vector_metadata(self):
        # 学生版隔离红线：协议答案/解析字段是发布临时量，必须被 vector_metadata 整体剔除。
        records = parse_questions('{"questions":[{"number":"3","stem":"题干","answer":"B","analysis":"提示文字","figureCount":0}]}')
        self.assertEqual("B", records[0]["metadata"]["_transcriptionFields"]["answer"])
        vector_md = MODULE.vector_metadata(records[0])
        self.assertNotIn("_transcriptionFields", vector_md)
        self.assertNotIn("提示文字", str(vector_md))


class FigureMarkerEmbeddingTest(unittest.TestCase):
    """FIGURE token 的确定性替换（取代“图”字+bbox 比例猜测）。"""

    def test_single_figure_marker_replaced_in_place(self):
        text = "[[FIGURE1]]如图，直三棱柱，求体积"
        placed = MODULE.embed_figure_markers(text, ["![第 19 题图](figures/q-019-01.png)"])
        self.assertEqual("![第 19 题图](figures/q-019-01.png)如图，直三棱柱，求体积", placed)

    def test_double_figure_markers_replaced_in_reading_order(self):
        # q19 双图锚点：两图各插在自己的“如图”位置（模型标记处），不再事后猜段落。
        text = "[[FIGURE1]]如图，直三棱柱…（1）求距离；（2）设D为中点，连接AE，[[FIGURE2]]如图，建立坐标系，求正弦值"
        placed = MODULE.embed_figure_markers(text, [
            "![第 19 题图](figures/q-019-01.png)",
            "![第 19 题图](figures/q-019-02.png)",
        ])
        first = placed.index("q-019-01.png")
        second = placed.index("q-019-02.png")
        self.assertLess(first, second)
        self.assertIn("![第 19 题图](figures/q-019-01.png)如图，直三棱柱", placed)
        self.assertIn("连接AE，![第 19 题图](figures/q-019-02.png)如图", placed)
        self.assertNotIn("[[FIGURE", placed)

    def test_extra_markers_are_dropped_when_assets_are_fewer(self):
        text = "[[FIGURE1]]图甲之后[[FIGURE2]]图乙之后"
        placed = MODULE.embed_figure_markers(text, ["![A](figures/q-001-01.png)"])
        # 无有效对应资产即不显示图片（讲义架构），且不残留 token 污染正文。
        self.assertNotIn("[[FIGURE", placed)
        self.assertNotIn("图乙之后![", placed)
        self.assertEqual("![A](figures/q-001-01.png)图甲之后图乙之后", placed)

    def test_extra_assets_are_appended_never_lost(self):
        text = "[[FIGURE1]]如图"
        placed = MODULE.embed_figure_markers(text, [
            "![A](figures/q-001-01.png)",
            "![B](figures/q-001-02.png)",
        ])
        # 多余资产按序追加文末，绝不丢图；已有标记仍在原位替换。
        self.assertTrue(placed.endswith("\n\n![B](figures/q-001-02.png)"))
        self.assertTrue(placed.startswith("![A](figures/q-001-01.png)如图"))

    def test_zero_assets_publishes_no_image(self):
        text = "[[FIGURE1]]如图"
        placed = MODULE.embed_figure_markers(text, [])
        self.assertEqual("如图", placed)

    def test_renumber_shifts_every_marker(self):
        # 续页重编号：前一页累计 1 个标记时，续页的 [[FIGURE1]] 必须变成 [[FIGURE2]]。
        renumbered = MODULE.renumber_figure_markers("坐标系见[[FIGURE1]]，另图[[FIGURE1]]", 1)
        self.assertEqual("坐标系见[[FIGURE2]]，另图[[FIGURE2]]", renumbered)

    def test_ignores_malformed_marker_tokens(self):
        # 非协议形态（[[FIGURE0]]、[[FIGUREx]]、未闭合）不当作标记吞正文，保持确定性边界。
        self.assertEqual([], MODULE.find_figure_markers("[[FIGURE0]]a[[FIGUREx]]b[[FIGURE1"))
        self.assertEqual(1, MODULE.count_figure_markers("[[FIGURE0]]a[[FIGURE12]]b"))

    def test_assets_ordered_by_page_then_top(self):
        first = {"pageNumber": 16, "bboxPixels": {"top": 850}}
        second = {"pageNumber": 15, "bboxPixels": {"top": 400}}
        third = {"pageNumber": 15, "bboxPixels": {"top": 100}}
        ordered = MODULE.order_assets_for_reading([first, second, third])
        self.assertEqual([third, second, first], ordered)


class CrossPageMergeTest(unittest.TestCase):
    """跨页合并保留，并新增续页标记全局重编号。"""

    def test_merges_continuation_and_renumbers_figure_markers(self):
        first = {"id": "first", "text": "[[FIGURE1]]如图，直三棱柱", "metadata": {
            "sourceFile": "paper.pdf", "page": 15, "questionNumber": "19", "latex": [],
            "continuesToNextPage": True, "figureCount": 1}}
        continuation = {"id": "second", "text": "连接AE，[[FIGURE1]]如图，建立坐标系", "metadata": {
            "sourceFile": "paper.pdf", "page": 16, "questionNumber": "", "latex": [],
            "continuesToNextPage": False, "figureCount": 1}}

        merged = MODULE.merge_cross_page_questions([first, continuation])

        self.assertEqual(1, len(merged))
        body = merged[0]["text"]
        self.assertIn("[[FIGURE1]]如图，直三棱柱", body)
        # 续页标记按前一页累计标记数重编号，整题内全局连续。
        self.assertIn("连接AE，[[FIGURE2]]如图", body)
        self.assertEqual(2, merged[0]["metadata"]["figureCount"])
        self.assertEqual(16, merged[0]["metadata"]["pageEnd"])

    def test_does_not_merge_different_numbered_question(self):
        first = {"id": "first", "text": "题干", "metadata": {"sourceFile": "paper.pdf", "page": 3, "questionNumber": "17", "latex": [], "continuesToNextPage": True}}
        next_question = {"id": "second", "text": "新题", "metadata": {"sourceFile": "paper.pdf", "page": 4, "questionNumber": "18", "latex": [], "continuesToNextPage": False}}
        self.assertEqual(2, len(MODULE.merge_cross_page_questions([first, next_question])))

    def test_fragment_with_parent_number_merges_past_wrong_neighbor(self):
        # 2022Ⅰ 实测形态：q17 解析尾段（number ""、parentNumber "17"）出现在新一页，
        # 上一条记录（q18）并未标续页。旧 merged[-1] 规则会丢段或错挂到 q18；
        # parentNumber 的“同卷同号 + 页邻接”纯字段匹配把它正确接回 q17。
        q17 = {"id": "a", "text": "q17题干", "metadata": {
            "sourceFile": "paper.pdf", "page": 14, "questionNumber": "17", "latex": [], "continuesToNextPage": False, "pageSequence": 0}}
        fragment = {"id": "b", "text": "q17解析尾段", "metadata": {
            "sourceFile": "paper.pdf", "page": 15, "questionNumber": "", "parentQuestionNumber": "17", "latex": [],
            "continuesToNextPage": False, "pageSequence": 0}}
        q18 = {"id": "c", "text": "q18题干", "metadata": {
            "sourceFile": "paper.pdf", "page": 15, "questionNumber": "18", "latex": [], "continuesToNextPage": False, "pageSequence": 1}}
        merged = MODULE.merge_cross_page_questions([q17, fragment, q18])
        self.assertEqual(2, len(merged))
        self.assertEqual("q17题干\nq17解析尾段", merged[0]["text"])
        self.assertEqual("17", merged[0]["metadata"]["questionNumber"])
        self.assertEqual(15, merged[0]["metadata"]["pageEnd"])
        self.assertEqual("q18题干", merged[1]["text"])

    def test_parent_number_merge_renumbers_figure_markers(self):
        q19 = {"id": "a", "text": "[[FIGURE1]]如图，直三棱柱", "metadata": {
            "sourceFile": "paper.pdf", "page": 15, "questionNumber": "19", "latex": [], "continuesToNextPage": False, "pageSequence": 0}}
        fragment = {"id": "b", "text": "连接AE，[[FIGURE1]]如图", "metadata": {
            "sourceFile": "paper.pdf", "page": 16, "questionNumber": "", "parentQuestionNumber": "19", "latex": [],
            "continuesToNextPage": False, "pageSequence": 0}}
        merged = MODULE.merge_cross_page_questions([q19, fragment])
        self.assertEqual(1, len(merged))
        self.assertIn("连接AE，[[FIGURE2]]如图", merged[0]["text"])
        self.assertEqual(2, merged[0]["metadata"]["figureCount"])

    def test_orphan_fragment_falls_back_to_reading_thread(self):
        # 模型漏标 parentNumber（2022Ⅰ 第 16 页 q19 解析多段实测）：无编号、无父号、
        # 无续页标记的片段按“最近编号记录 + 同页/下一页邻接”的版面线程兜底归属，
        # 纯协议字段比较；页距超过 1 仍拒绝，宁丢勿错挂。
        q19 = {"id": "a", "text": "19题干", "metadata": {
            "sourceFile": "paper.pdf", "page": 15, "questionNumber": "19", "latex": [], "continuesToNextPage": False, "pageSequence": 0}}
        same_page = {"id": "b", "text": "页内解析段", "metadata": {
            "sourceFile": "paper.pdf", "page": 15, "questionNumber": "", "latex": [], "continuesToNextPage": False, "pageSequence": 1}}
        next_page = {"id": "c", "text": "跨页解析段", "metadata": {
            "sourceFile": "paper.pdf", "page": 16, "questionNumber": "", "latex": [], "continuesToNextPage": False, "pageSequence": 0}}
        far = {"id": "d", "text": "两页之外", "metadata": {
            "sourceFile": "paper.pdf", "page": 18, "questionNumber": "", "latex": [], "continuesToNextPage": False, "pageSequence": 0}}
        merged = MODULE.merge_cross_page_questions([q19, same_page, next_page, far])
        # “两页之外”的片段不并入（页距>1），保留为未合并记录由 main 告警过滤。
        self.assertEqual(2, len(merged))
        self.assertEqual(16, merged[0]["metadata"]["pageEnd"])
        self.assertEqual("19题干\n页内解析段\n跨页解析段", merged[0]["text"])
        self.assertEqual("两页之外", merged[1]["text"])

    def test_wrong_parent_number_degrades_to_thread_with_log(self):
        # 实测：Terra 把解析正文里的区间 "[18,27]" 读成了 parentNumber "18"。
        # 声明匹配不到邻接编号记录时降级为线程归属（挂回 q8），并打日志暴露错配。
        q8 = {"id": "a", "text": "8题干", "metadata": {
            "sourceFile": "paper.pdf", "page": 4, "questionNumber": "8", "latex": [], "continuesToNextPage": False, "pageSequence": 0}}
        fragment = {"id": "b", "text": "8解析尾段", "metadata": {
            "sourceFile": "paper.pdf", "page": 5, "questionNumber": "", "parentQuestionNumber": "18", "latex": [],
            "continuesToNextPage": False, "pageSequence": 0}}
        q9 = {"id": "c", "text": "9题干", "metadata": {
            "sourceFile": "paper.pdf", "page": 5, "questionNumber": "9", "latex": [], "continuesToNextPage": False, "pageSequence": 1}}
        merged = MODULE.merge_cross_page_questions([q8, fragment, q9])
        self.assertEqual(2, len(merged))
        self.assertEqual("8题干\n8解析尾段", merged[0]["text"])
        self.assertEqual("9题干", merged[1]["text"])

    def test_conflicting_parent_claim_yields_to_reading_thread(self):
        # 实测模型会给错误的 parentNumber（第 10/19 页）。当声明与版面线程都能匹配到
        # 不同记录时，取线程（版面物理事实）并留日志，声明只在线程缺失时兜底。
        q17 = {"id": "a", "text": "17题干", "metadata": {
            "sourceFile": "paper.pdf", "page": 3, "questionNumber": "17", "latex": [], "continuesToNextPage": False, "pageSequence": 0}}
        q17_tail = {"id": "b", "text": "17页尾", "metadata": {
            "sourceFile": "paper.pdf", "page": 4, "questionNumber": "", "parentQuestionNumber": "17", "latex": [],
            "continuesToNextPage": False, "pageSequence": 0}}
        q18 = {"id": "c", "text": "18题干", "metadata": {
            "sourceFile": "paper.pdf", "page": 4, "questionNumber": "18", "latex": [], "continuesToNextPage": False, "pageSequence": 1}}
        bad_claim = {"id": "d", "text": "18解析续段", "metadata": {
            "sourceFile": "paper.pdf", "page": 5, "questionNumber": "", "parentQuestionNumber": "17", "latex": [],
            "continuesToNextPage": False, "pageSequence": 0}}
        merged = MODULE.merge_cross_page_questions([q17, q17_tail, q18, bad_claim])
        self.assertEqual(2, len(merged))
        # bad_claim 声明 parent 17（页距也满足），但线程 q18 胜出。
        self.assertEqual("18题干\n18解析续段", merged[1]["text"])
        self.assertIn("17页尾", merged[0]["text"])

    def test_merged_analysis_prefix_is_not_stacked(self):
        # 跨页片段各自带模板 【解析】 前缀，合并后只保留一次（“前缀堆叠”是本次
        # 验收点名问题；剥前缀是对自家模板 token 的确定性处理）。
        q19 = {"id": "a", "text": "19题干\n\n【解析】第一段", "metadata": {
            "sourceFile": "paper.pdf", "page": 15, "questionNumber": "19", "latex": [], "continuesToNextPage": True, "pageSequence": 0}}
        fragment = {"id": "b", "text": "【解析】第二段", "metadata": {
            "sourceFile": "paper.pdf", "page": 16, "questionNumber": "", "latex": [], "continuesToNextPage": False, "pageSequence": 0}}
        merged = MODULE.merge_cross_page_questions([q19, fragment])
        self.assertEqual(1, len(merged))
        self.assertEqual("19题干\n\n【解析】第一段\n第二段", merged[0]["text"])

    def test_mid_fragment_template_prefixes_are_deduped_too(self):
        # 2022Ⅰ q10 实测：片段开头是题干续句，其 【答案】/【解析】 前缀出现在正文中段
        # 的节点头位置（\n\n 之后），同样要去重；印刷小节标题【详解】保留。
        previous = "题干\n\n【答案】AC\n\n【解析】利用平移可判"
        fragment = "断 C。\n\n【答案】AC\n\n【解析】【详解】由题，f'(x)=3x^2-1。"
        deduped = MODULE.dedupe_merged_template_prefixes(previous, fragment)
        self.assertEqual("断 C。\n\nAC\n\n【详解】由题，f'(x)=3x^2-1。", deduped)
        # previous 没有该前缀时不能误删（去重只针对“重复”）。
        self.assertEqual(
            fragment,
            MODULE.dedupe_merged_template_prefixes("题干只有正文", fragment),
        )

    def test_parent_number_requires_adjacent_open_page_span(self):
        # 页不邻接（q17 结束于第 12 页，片段在第 15 页）时不做猜测式归属：保持未合并，
        # 由 main 的 isdigit 过滤告警兜底，宁缺勿错挂。
        q17 = {"id": "a", "text": "q17", "metadata": {
            "sourceFile": "paper.pdf", "page": 12, "questionNumber": "17", "latex": [], "continuesToNextPage": False, "pageSequence": 0}}
        far_fragment = {"id": "b", "text": "孤段", "metadata": {
            "sourceFile": "paper.pdf", "page": 15, "questionNumber": "", "parentQuestionNumber": "17", "latex": [],
            "continuesToNextPage": False, "pageSequence": 0}}
        merged = MODULE.merge_cross_page_questions([q17, far_fragment])
        self.assertEqual(2, len(merged))

    def test_parent_number_and_cross_field_figure_markers_are_parsed(self):
        # 解析卷题图引用常落在 analysis（“连接AE，如图”）；figureCount 统计跨三字段。
        records = parse_questions('{"questions":[{"number":"","parentNumber":"19","stem":"","answer":"","analysis":"连接AE，[[FIGURE1]]如图。","figureCount":1,"continuesToNextPage":false}]}')
        self.assertEqual(1, len(records))
        self.assertEqual("19", records[0]["metadata"]["parentQuestionNumber"])
        self.assertEqual(1, records[0]["metadata"]["figureCount"])
        self.assertNotIn("figureCountMismatch", records[0]["metadata"])
        # 空号片段（协议片段）直接保留，不依赖 continuesToNextPage。
        self.assertEqual("", records[0]["metadata"]["questionNumber"])


class NumberConflictAndIdentityTest(unittest.TestCase):
    """同号冲突保留首条、不改号；发布身份只认纯数字。"""

    def test_duplicate_number_keeps_first_without_renumbering(self):
        # v2 删除 repair_question_number_collisions：用缺号集合反推打印题号等于伪造来源
        # 证据。冲突只保留最早页记录、计入 duplicateSkippedCount，编号绝不自动修改。
        source_hash = "a" * 64
        early = {"id": "early", "text": "早题", "metadata": {"sourceFile": "paper.pdf", "sourceSha256": source_hash, "pageStart": 1, "questionNumber": "2"}}
        late = {"id": "late", "text": "晚题", "metadata": {"sourceFile": "paper.pdf", "sourceSha256": source_hash, "pageStart": 5, "questionNumber": "2"}}
        other = {"id": "other", "text": "另题", "metadata": {"sourceFile": "paper.pdf", "sourceSha256": source_hash, "pageStart": 5, "questionNumber": "4"}}
        retained, duplicates = MODULE.canonical_question_records([late, other, early], with_stats=True)
        self.assertEqual(["early", "other"], [item["id"] for item in retained])
        self.assertEqual(1, duplicates)
        self.assertEqual(["2", "4"], [item["metadata"]["questionNumber"] for item in retained])

    def test_canonical_question_id_requires_pure_digits(self):
        # 身份构建不再归一化“第 7 题”：协议违规输出应在解析阶段被拒绝，而不是被洗白。
        self.assertEqual(MODULE.canonical_question_id("a" * 64, "7"), MODULE.canonical_question_id("a" * 64, " 7 "))
        with self.assertRaises(ValueError):
            MODULE.canonical_question_id("a" * 64, "第 7 题")


class PipelineEventLogTest(unittest.TestCase):
    """结构化留痕：SCAN 复核依赖 pipeline-events.jsonl，事件必须可机读且与 stderr 同源。"""

    def test_drop_event_is_appended_as_jsonl(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "pipeline-events.jsonl"
            MODULE.set_pipeline_event_log(path)
            try:
                parse_questions('{"questions":[{"number":"19.","stem":"题干","answer":"","analysis":"","figureCount":0}]}')
            finally:
                MODULE.set_pipeline_event_log(None)
            events = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines()]
            self.assertEqual(1, len(events))
            self.assertEqual("v2-drop", events[0]["kind"])
            self.assertEqual("non-numeric-number", events[0]["reason"])
            self.assertEqual("paper.pdf", events[0]["sourceFile"])

    def test_events_are_stderr_only_without_run_context(self):
        # 单测/离线复用脚本时未挂 run 目录，不得凭空创建事件文件。
        MODULE.set_pipeline_event_log(None)
        parse_questions('{"questions":[{"number":"19.","stem":"题干","answer":"","analysis":"","figureCount":0}]}')


class PromptSnapshotTest(unittest.TestCase):
    """请求体快照：结构契约必须写进提示词本身。"""

    def _prompt_text(self) -> tuple[dict, str]:
        with tempfile.TemporaryDirectory() as tmp:
            image = Path(tmp) / "page-1.jpg"
            image.write_bytes(b"\xff\xd8\xff\xd9")
            request = MODULE.vision_request(image, "paper.pdf", 1, "gpt-5.6-terra")
        prompt_text = request["messages"][1]["content"][0]["text"]
        return request, prompt_text

    def test_prompt_declares_v2_protocol(self):
        request, prompt_text = self._prompt_text()
        self.assertEqual(0, request["temperature"])
        self.assertEqual({"type": "json_object"}, request["response_format"])
        # 题号纯数字、FIGURE 占位、figureCount 自检、answer/analysis 无题号无前缀——逐条锁定。
        for keyword in ("ONLY the printed digits", "[[FIGURE", "figureCount", "parentNumber",
                        "reading order they appear on the page",
                        "must not start with or contain the printed question number",
                        "WITHOUT question numbers", "continuesToNextPage"):
            self.assertIn(keyword, prompt_text, keyword)
        # figureAnchor 契约字段已被 FIGURE 标记取代。
        self.assertNotIn("figureAnchor", prompt_text)
        # v2 协议字段取代旧 text 字段。
        self.assertIn('"stem"', prompt_text)

    def test_prompt_requires_stripping_section_labels(self):
        _request, prompt_text = self._prompt_text()
        self.assertIn("【", prompt_text)


class DeletedRegexHeuristicsTest(unittest.TestCase):
    """防回归：被删除的正文正则启发式不得回流到 v2；质量门禁与基建必须保留。"""

    REMOVED_NAMES = (
        "CANONICAL_QUESTION_NUMBER_PATTERN", "NOTATION_ATOM", "DASH_FOLD", "SOLUTION_HEADING",
        "FIGURE_REFERENCE_PATTERN",
        "_fold_output", "_normalize_locator_text", "_compact_with_offsets", "_question_signature",
        "_locate_question_in_pages", "_solution_segment_offset", "attach_solution_sections",
        "reconcile_question_numbers_from_page_text", "repair_question_number_collisions",
        "_page_footer_only", "_paragraph_bounds", "place_question_figures",
    )
    RETAINED_NAMES = (
        "FRACTION_SLASH_PATTERN", "load_question_assets", "canonical_question_id",
        "canonical_question_records", "merge_cross_page_questions", "publish_canonical_paper",
        "vector_metadata", "recognized_page_text",
    )

    def test_regex_heuristics_are_absent(self):
        for name in self.REMOVED_NAMES:
            self.assertFalse(hasattr(MODULE, name), name)

    def test_quality_gates_and_infrastructure_are_retained(self):
        for name in self.RETAINED_NAMES:
            self.assertTrue(hasattr(MODULE, name), name)

    def test_v2_refuses_protected_collection(self):
        # 红线固化：v2 的 cleanup 只允许 test collection，生产 collection 名直接拒绝。
        with self.assertRaises(ValueError):
            MODULE.cleanup_source_records("http://x", "", MODULE.PROTECTED_COLLECTION, ["paper.pdf"], 5)
        self.assertEqual("gaokao_math_structured_test", MODULE.DEFAULT_COLLECTION)


if __name__ == "__main__":
    unittest.main()
