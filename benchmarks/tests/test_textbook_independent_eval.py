from __future__ import annotations

import json
from pathlib import Path

from benchmarks.build_textbook_independent_eval_set import compact
from benchmarks.textbook_independent_retrieval_eval import (
    PrototypeRetriever,
    grounded,
    interleave_routes,
    query_anchors,
    title_grounded,
)
from benchmarks.textbook_section_block_prototype import (
    SectionBlockRetriever,
    block_rank,
    build_block_index,
    resolve_evaluation_query,
    strict_block_rank,
)
from benchmarks.strict_textbook_label_audit import deterministic_upper_bounds
from benchmarks.textbook_independent_strict_page_audit import strict_page_rank
from benchmarks.textbook_logical_block_ablation_eval import (
    canonical_parent_key,
    logical_block_rank,
    validate_public_request_payload,
)


CASE_ROOT = Path("output/benchmarks/textbook-independent-110-v1")


def test_generated_independent_set_is_unique_balanced_and_label_separated() -> None:
    cases = json.loads((CASE_ROOT / "cases.json").read_text(encoding="utf-8"))

    assert len(cases) == 110
    assert len({compact(case["query"]) for case in cases}) == 110
    assert sum(case["polarity"] == "positive" for case in cases) == 100
    assert sum(case["polarity"] == "negative" for case in cases) == 10
    assert all(case["expected"] is None for case in cases if case["polarity"] == "negative")


def test_route_interleave_preserves_independent_rankings_without_score_fusion() -> None:
    assert interleave_routes(
        [["title-a", "title-b"], ["body-a", "body-b"], ["semantic-a"]],
        3,
    ) == ["title-a", "body-a", "semantic-a"]


def test_graph_evaluation_switch_preserves_the_original_query_and_is_explicit() -> None:
    query = "导数单调性参数范围"

    plain, plain_matched, plain_expanded = resolve_evaluation_query(query, False)
    graph, graph_matched, graph_expanded = resolve_evaluation_query(query, True)

    assert plain == query
    assert plain_matched == []
    assert plain_expanded == []
    assert query in graph
    assert graph_matched
    assert graph_expanded


def test_strict_label_audit_exposes_same_query_page_conflicts_without_changing_cases() -> None:
    cases = [
        {"query": "同一查询", "expected": ("book", 12, "同一标题")},
        {"query": "同一查询", "expected": ("book", 14, "同一标题")},
        {"query": "同一查询", "expected": ("book", 14, "同一标题")},
        {"query": "另一查询", "expected": ("book", 16, "另一标题")},
    ]

    bounds = deterministic_upper_bounds(cases)

    assert bounds.at_1 == 0.75
    assert bounds.at_3 == 1.0
    assert bounds.conflicting_query_count == 1


def test_parent_block_aggregation_keeps_the_recalled_child_page_as_the_result_evidence() -> None:
    first_page = row("p12", 12, "这是较长的背景正文，用来模拟同一小标题跨页时的其他内容。")
    matched_page = row("p14", 14, "命中术语")
    retriever = SectionBlockRetriever.__new__(SectionBlockRetriever)
    retriever.block_index = build_block_index([first_page, matched_page])

    candidates = retriever.collapse_candidates([
        dict(first_page, _stage="section_bm25", _score=1.0),
        dict(matched_page, _stage="section_bm25", _score=9.0),
    ])

    assert len(candidates) == 1
    assert candidates[0]["chunk_id"] == "p14"
    assert candidates[0]["page_no"] == 14


def test_strict_block_rank_requires_the_recalled_child_source_page() -> None:
    hits = [{"doc_id": "book", "page_no": 12, "section_title": "同一小标题"}]
    expected = {"docId": "book", "pageNo": 14, "sectionTitle": "同一小标题"}

    assert strict_block_rank(hits, expected) is None


def test_independent_strict_page_audit_requires_document_page_and_visible_title() -> None:
    expected = {"docId": "book", "pageNo": 14, "sectionTitle": "同一小标题"}

    assert strict_page_rank([
        {"docId": "book", "pageNo": 12, "sectionTitle": "同一小标题"},
        {"docId": "book", "pageNo": 14, "sectionTitle": "同一小标题"},
    ], expected) == 2


def test_parent_candidate_expansion_keeps_each_source_page_for_child_evidence_rerank() -> None:
    first_page = row("p12", 12, "定义背景")
    recalled_page = row("p14", 14, "查询命中的正文")
    retriever = SectionBlockRetriever.__new__(SectionBlockRetriever)
    retriever.block_index = build_block_index([first_page, recalled_page])

    expanded = retriever.child_evidence_candidates([
        dict(recalled_page, _block_key=("book", "section-a", "同一小标题"), _stage="section_bm25", _score=9.0),
    ])

    assert [candidate["chunk_id"] for candidate in expanded] == ["p14", "p12"]


def test_query_anchors_extract_real_topic_from_ui_wrapper_and_conjunction() -> None:
    assert query_anchors("请查找教材中关于卡方与独立性检验的相关内容。") == ["独立性检验", "卡方"]


def test_grounding_keeps_title_evidence_even_when_rerank_logit_is_negative() -> None:
    row = {
        "section_title": "4.3.2 独立性检验",
        "text": "通过卡方统计量判断两个分类变量是否独立。",
        "formula_text": "",
        "_rerank_score": -1.92,
    }

    assert grounded("卡方与独立性检验", row)


def test_grounding_rejects_out_of_domain_nearest_neighbor_with_negative_logit() -> None:
    row = {
        "section_title": "6.3 利用导数解决实际问题",
        "text": "利用导数研究函数的最大值和最小值。",
        "formula_text": "",
        "_rerank_score": -6.31,
    }

    assert not grounded("量子色动力学夸克禁闭", row)


def test_title_route_requires_real_query_anchor_in_visible_title() -> None:
    assert title_grounded("请查找教材中关于独立性检验的相关内容", {"section_title": "4.3.2 独立性检验"})
    assert not title_grounded("卡方与独立性检验", {"section_title": "4.3.2 独立性检验"})
    assert not title_grounded("已知棱长为1的正方体", {"section_title": "空间向量及其运算"})


def test_section_expansion_exposes_each_real_page_without_target_page_input() -> None:
    retriever = PrototypeRetriever.__new__(PrototypeRetriever)
    retriever.rows_by_section = {
        ("book", "section-a"): [
            row("p14", 14, "正文一"),
            row("p12", 12, "定义"),
            row("p16", 16, "正文二"),
            row("other-title", 18, "不应跨标题展开", "另一个小标题"),
        ]
    }

    title_pages = retriever.expand_section_pages(row("hit", 14, "命中"), True)
    body_pages = retriever.expand_section_pages(row("hit", 14, "命中"), False)

    assert [item["page_no"] for item in title_pages] == [12, 14, 16]
    assert [item["page_no"] for item in body_pages] == [14, 12, 16]


def test_section_block_index_keeps_cross_page_evidence_by_reference_but_not_another_heading() -> None:
    first = row("p12", 12, "定义部分")
    second = row("p14", 14, "证明部分")
    other = row("p14-other", 14, "另一块正文", "另一小标题")

    index = build_block_index([first, second, other])

    assert len(index.members_by_key) == 2
    target = next(members for key, members in index.members_by_key.items() if key[-1] == "同一小标题")
    assert target == [first, second]
    assert target[0] is first
    assert target[1] is second


def test_section_block_metric_uses_logical_heading_not_source_page() -> None:
    hits = [{
        "doc_id": "book",
        "section_id": "section-a",
        "section_title": "同一小标题",
        "page_nos": [12, 14],
    }]
    expected = {
        "docId": "book",
        "sectionId": "section-a",
        "sectionTitle": "同一小标题",
        "pageNo": 14,
    }

    assert block_rank(hits, expected) == 1


def test_logical_ablation_scores_cross_page_heading_without_relaxing_strict_page_identity() -> None:
    """The new c2 ablation must report both identities rather than conflate them."""
    expected = {
        "docId": "book",
        "sectionId": "section-a",
        "sectionTitle": "同一小标题",
        "pageNo": 14,
    }
    hits = [{
        "doc_id": "book",
        "section_id": "section-a",
        "section_title": "同一小标题",
        "page_no": 12,
    }]

    assert logical_block_rank(hits, expected) == 1
    assert strict_block_rank(hits, expected) is None


def test_logical_ablation_rejects_hidden_scope_fields_in_recorded_request() -> None:
    """Benchmark requests are limited to public query and limit fields."""
    assert validate_public_request_payload({"query": "独立性检验", "limit": 10})
    assert not validate_public_request_payload({
        "query": "独立性检验",
        "limit": 10,
        "pageNo": 123,
    })


def test_canonical_parent_key_repairs_repeated_numbered_heading_without_merging_generic_headings() -> None:
    """A stable outline title can repair c2 section-id fragmentation; generic text cannot."""
    numbered_first = row("p12", 12, "正文", "1.1 空间向量及其运算")
    numbered_second = dict(numbered_first, chunk_id="p24", section_id="section-later")
    generic_first = row("solve-1", 30, "解答", "解")
    generic_second = dict(generic_first, chunk_id="solve-2", section_id="section-other")

    assert canonical_parent_key(numbered_first) == canonical_parent_key(numbered_second)
    assert canonical_parent_key(generic_first) != canonical_parent_key(generic_second)


def row(chunk_id: str, page_no: int, text: str, title: str = "同一小标题") -> dict:
    return {
        "chunk_id": chunk_id,
        "section_id": "section-a",
        "doc_id": "book",
        "page_no": page_no,
        "section_title": title,
        "text": text,
        "formula_text": "",
        "_stage": "title_bm25",
        "_score": 1.0,
    }
