"""Focused contract tests for direct Gaokao knowledge-point recall reporting."""
from __future__ import annotations

import importlib.util
from pathlib import Path
import sys

import pytest


SCRIPT_PATH = Path(__file__).with_name("gaokao_knowledge_point_recall_report.py")
SPEC = importlib.util.spec_from_file_location("gaokao_knowledge_point_recall_report", SCRIPT_PATH)
assert SPEC and SPEC.loader
MODULE = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)


def test_requested_knowledge_point_queries_are_fixed_and_unmodified() -> None:
    assert [case["query"] for case in MODULE.KNOWLEDGE_POINT_CASES] == [
        "概率统计", "三角函数", "解三角形", "空间向量", "导数", "隐零点", "切线函数", "椭圆", "双曲线",
    ]


def test_stem_evidence_uses_answer_free_canonical_stems() -> None:
    catalog = {
        ("paper.pdf", "1"): {"questionId": "q-1", "stem": "已知椭圆的焦点。"},
        ("paper.pdf", "2"): {"questionId": "q-2", "stem": "求导函数的单调性。"},
    }
    hits = [{"id": "q-1", "metadata": {"sourceFile": "paper.pdf", "questionNumber": "1", "questionId": "q-1"}}]

    score = MODULE._score_stem_evidence(catalog, hits, ("椭圆",), 10)

    assert score["label"] == "answer_free_stem_evidence_coverage_not_authoritative_labels"
    assert score["candidateCount"] == 1
    assert score["matchedTop10Count"] == 1
    assert score["coverageAt10"] == 1.0


def test_public_hit_keeps_full_stem_and_reports_evidence_without_relevance_claim() -> None:
    catalog = {("paper.pdf", "1"): {"stem": "已知双曲线的离心率。"}}
    detail = {"recordType": "question", "sourceFile": "paper.pdf", "questionNumber": "1"}

    result = MODULE._public_hit(detail, catalog, ("双曲线",))

    assert result["fullStem"] == "已知双曲线的离心率。"
    assert result["stemEvidence"] == "matched"
    assert "relevance" not in result


def test_query_case_uses_only_read_only_search_and_marks_duplicate_gate(monkeypatch: pytest.MonkeyPatch) -> None:
    calls: list[tuple[str, str, int]] = []

    def fake_search(vector: list[float], collection: str, uri: str, token: str, top_k: int, timeout: int) -> list[dict[str, object]]:
        calls.append((collection, uri, top_k))
        return [{"id": "q-1", "metadata": {"sourceFile": "paper.pdf", "questionNumber": "1", "questionId": "q-1"}}]

    monkeypatch.setattr(MODULE.recall, "search_milvus", fake_search)
    monkeypatch.setattr(MODULE.recall, "audit_hit_contract", lambda *args: ({"recordType": "question", "sourceFile": "paper.pdf", "questionNumber": "1", "textSummary": "题干", "assetContract": {"status": "pass"}}, []))
    monkeypatch.setattr(MODULE.recall, "deduplicate_hits", lambda hits: [{"hit": hits[0], "rawRank": 1}])
    monkeypatch.setattr(MODULE.recall, "raw_duplicate_observation", lambda hits, top_k: {"rawTopKDuplicateKeyCount": 0, "rawTopKDuplicateRowCount": 0, "rawTopKLargestRepeatedKeyOccupancy": 1, "rawTopKRepeatedKeys": []})
    case = {"id": "ellipse", "name": "椭圆", "query": "椭圆", "evidenceTerms": ("椭圆",)}
    catalog = {("paper.pdf", "1"): {"questionId": "q-1", "stem": "已知椭圆的焦点。"}}

    result = MODULE._query_case(vector=[0.1], case=case, collection="gaokao_math", uri="http://milvus", token="secret", timeout=1, manifests={}, catalog=catalog)

    assert calls == [("gaokao_math", "http://milvus", 10)]
    assert result["query"] == "椭圆"
    assert result["querySentUnchanged"] is True
    assert result["queryStatus"] == "passed_duplicate_gate"
    assert result["hits"][0]["stemEvidence"] == "matched"


def test_query_case_fails_when_raw_window_has_same_source_question_duplicate(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(MODULE.recall, "search_milvus", lambda *args: [])
    monkeypatch.setattr(MODULE.recall, "deduplicate_hits", lambda hits: [])
    monkeypatch.setattr(MODULE.recall, "raw_duplicate_observation", lambda hits, top_k: {"rawTopKDuplicateKeyCount": 1, "rawTopKDuplicateRowCount": 1, "rawTopKLargestRepeatedKeyOccupancy": 2, "rawTopKRepeatedKeys": [{"sourceFile": "paper.pdf", "questionNumber": "1"}]})
    case = {"id": "ellipse", "name": "椭圆", "query": "椭圆", "evidenceTerms": ("椭圆",)}

    result = MODULE._query_case(vector=[0.1], case=case, collection="gaokao_math", uri="http://milvus", token="secret", timeout=1, manifests={}, catalog={})

    assert result["queryStatus"] == "duplicate_fail"


def test_deepseek_json_extraction_removes_reasoning_wrapper() -> None:
    parsed = MODULE._extract_json_object("<think>internal reasoning</think>\n```json\n{\"judgments\": []}\n```")

    assert parsed == {"judgments": []}


def test_deepseek_review_validation_requires_exact_returned_rows() -> None:
    case = {"id": "ellipse", "name": "椭圆", "query": "椭圆"}
    hits = [
        {"rank": 1, "questionId": "q-1", "fullStem": "椭圆题"},
        {"rank": 2, "questionId": "q-2", "fullStem": "另一道椭圆题"},
    ]
    accepted = MODULE._validate_review(case, hits, {"judgments": [
        {"rank": 2, "questionId": "q-2", "label": "uncertain", "reason": "题干条件不足以确认范围"},
        {"rank": 1, "questionId": "q-1", "label": "relevant", "reason": "题干直接研究椭圆性质"},
    ]})

    assert [item["rank"] for item in accepted] == [1, 2]
    with pytest.raises(MODULE.KnowledgePointReportError):
        MODULE._validate_review(case, hits, {"judgments": [
            {"rank": 1, "questionId": "q-1", "label": "relevant", "reason": "缺少第二题"},
        ]})


def test_apply_llm_review_adds_decisive_precision_without_changing_hits() -> None:
    case = {"hits": [{"rank": 1, "questionId": "q-1"}, {"rank": 2, "questionId": "q-2"}, {"rank": 3, "questionId": "q-3"}]}
    review = {"provider": "deepseek", "model": "deepseek-v4-flash", "reviewAttempts": 1, "reviewLatencyMs": 12.5, "usage": {"prompt_tokens": 1, "completion_tokens": 2, "total_tokens": 3}, "judgments": [
        {"rank": 1, "questionId": "q-1", "label": "relevant", "reason": "题干直接匹配"},
        {"rank": 2, "questionId": "q-2", "label": "not_relevant", "reason": "研究对象不属于该知识点"},
        {"rank": 3, "questionId": "q-3", "label": "uncertain", "reason": "题干信息不足以判断"},
    ]}

    MODULE.apply_llm_review(case, review)

    assert case["llmReview"]["relevantCount"] == 1
    assert case["llmReview"]["notRelevantCount"] == 1
    assert case["llmReview"]["uncertainCount"] == 1
    assert case["llmReview"]["decisivePrecisionAt10"] == 0.5
    assert case["hits"][0]["llmReview"]["label"] == "relevant"


def test_review_prompt_contains_only_answer_free_returned_stems() -> None:
    case = {"knowledgePoint": "解三角形", "query": "解三角形"}
    messages = MODULE._review_prompt(case, [{"rank": 1, "questionId": "q-1", "fullStem": "在三角形ABC中，求tanB。"}])

    assert "解三角形" in messages[1]["content"]
    assert "在三角形ABC中，求tanB。" in messages[1]["content"]
    assert "答案" not in messages[1]["content"]
    assert "解析" not in messages[1]["content"]


def test_report_construction_has_no_graph_expansion_terms() -> None:
    assert "graph_expansion" not in Path(SCRIPT_PATH).read_text(encoding="utf-8")
    assert all(case["query"] == case["name"] for case in MODULE.KNOWLEDGE_POINT_CASES)
