"""Focused pure-function tests for the enterprise recall evaluator."""
from __future__ import annotations

import importlib.util
import json
from pathlib import Path
import sys

import pytest


SCRIPT_PATH = Path(__file__).with_name("knowledge_point_recall_acceptance.py")
SPEC = importlib.util.spec_from_file_location("knowledge_point_recall_acceptance", SCRIPT_PATH)
assert SPEC and SPEC.loader
MODULE = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)


ASSET_ID = "11111111-1111-4111-8111-111111111111"
OTHER_ASSET_ID = "22222222-2222-4222-8222-222222222222"
PAGE_ASSET_ID = "33333333-3333-4333-8333-333333333333"
SOURCE_FILE = "2024年高考数学试卷（测试卷）（解析卷）.pdf"
SOURCE_SHA = "a" * 64


def canonical_fixture(tmp_path: Path) -> tuple[Path, dict[str, object], dict[str, object]]:
    paper_root = tmp_path / SOURCE_FILE
    figure = paper_root / "figures" / "q-001-01.png"
    question_path = paper_root / "questions" / "q-001.md"
    figure.parent.mkdir(parents=True)
    question_path.parent.mkdir(parents=True)
    figure.write_bytes(b"real canonical figure")
    question_path.write_text(
        "# 测试卷 第 1 题\n\n"
        "- 来源页：1 至 1\n"
        "- 来源题目：1\n"
        "- 跨页连续：否\n\n"
        "已知抛物线 C，求其焦点。\n\n"
        "![第 1 题图](figures/q-001-01.png)\n\n"
        "【答案】A\n\n【解析】不应进入 query。\n",
        encoding="utf-8",
    )
    question_entry: dict[str, object] = {
        "questionNumber": "1",
        "questionId": "question-1",
        "questionMarkdown": "questions/q-001.md",
        "questionMarkdownSha256": MODULE.sha256_file(question_path),
        "assetIds": [ASSET_ID, PAGE_ASSET_ID],
        "assets": [{
            "assetId": ASSET_ID,
            "assetSha256": MODULE.sha256_file(figure),
            "sourceSha256": SOURCE_SHA,
            "canonicalAssetPath": "figures/q-001-01.png",
        }],
    }
    manifest: dict[str, object] = {
        "documentFullName": SOURCE_FILE,
        "sourceSha256": SOURCE_SHA,
        "questions": [question_entry],
    }
    (paper_root / "source-manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
    return paper_root, question_entry, manifest


def canonical_item(source: str, number: str, question_id: str, stem: str, source_sha: str = SOURCE_SHA) -> dict[str, object]:
    return {
        "sourceFile": source,
        "sourceSha256": source_sha,
        "questionNumber": number,
        "questionId": question_id,
        "documentRef": MODULE.document_ref(source, source_sha),
        "stem": stem,
        "stemSummary": stem,
        "question": {"questionNumber": number, "questionId": question_id, "assetIds": [], "assets": []},
    }


def test_extract_question_stem_excludes_answer_solution_and_image() -> None:
    stem = MODULE.extract_question_stem(
        "# 试卷 第 1 题\n\n- 来源页：1 至 1\n- 来源题目：1\n- 跨页连续：否\n\n"
        "题干与选项\n![题图](figures/q.png)\nA．甲\nB．乙\n\n【答案】A\n【解析】答案依据"
    )

    assert "题干与选项" in stem
    assert "A．甲" in stem
    assert "答案" not in stem
    assert "解析" not in stem
    assert "figures/q.png" not in stem


def test_seed_mapping_is_neutral_and_preserves_1_3_5() -> None:
    mapping = MODULE._parse_seed_mapping(json.dumps({
        "seed_1": {"sourceFile": "a.pdf", "questionNumber": "1"},
        "seed_3": {"sourceFile": "b.pdf", "questionNumber": "3"},
        "seed_5": {"sourceFile": "c.pdf", "questionNumber": "5"},
    }))

    assert set(mapping) == {"seed_1", "seed_3", "seed_5"}
    assert {item["questionNumber"] for item in mapping.values()} == {"1", "3", "5"}
    assert all(not key in MODULE.TOPIC_ORDER for key in mapping)


def test_seed_topic_mismatch_is_explicit_and_not_gold_truth() -> None:
    seed = canonical_item(SOURCE_FILE, "1", "seed-id", "已知集合 A 与 B，求交集。")
    seed["seedSlot"] = "seed_1"
    seed["observedContentLabels"] = MODULE._observed_content_labels(seed["stem"])
    seed["topicAlignment"] = {topic: MODULE._topic_alignment(seed["stem"], topic) for topic in MODULE.TOPIC_ORDER}

    assert seed["topicAlignment"]["parabola"] == {
        "aligned": False,
        "evidenceTerms": [],
        "decision": "sample_topic_mismatch",
    }
    assert seed["topicAlignment"]["probability_statistics"]["aligned"] is False
    assert seed["topicAlignment"]["spatial_vector"]["aligned"] is False


def test_select_gold_sets_excludes_seeds_and_prefers_source_diversity() -> None:
    seeds = [canonical_item("seed.pdf", "1", "seed-id", "集合题")]
    catalog = {
        ("seed.pdf", "1"): seeds[0],
        ("a.pdf", "11"): canonical_item("a.pdf", "11", "p-a", "已知抛物线 C。"),
        ("b.pdf", "10"): canonical_item("b.pdf", "10", "p-b", "抛物线的准线为 l。"),
        ("c.pdf", "12"): canonical_item("c.pdf", "12", "p-c", "抛物线焦点为 F。"),
        ("d.pdf", "3"): canonical_item("d.pdf", "3", "s-d", "已知二面角。"),
        ("e.pdf", "4"): canonical_item("e.pdf", "4", "s-e", "已知三棱锥。"),
        ("f.pdf", "5"): canonical_item("f.pdf", "5", "s-f", "已知四棱柱。"),
        ("g.pdf", "7"): canonical_item("g.pdf", "7", "r-g", "随机抽样的概率。"),
        ("h.pdf", "8"): canonical_item("h.pdf", "8", "r-h", "频率分布。"),
        ("i.pdf", "9"): canonical_item("i.pdf", "9", "r-i", "正态分布。"),
    }

    gold_sets, audits = MODULE.select_gold_sets(catalog, seeds, gold_per_topic=3)

    assert all(len(items) == 3 for items in gold_sets.values())
    assert all("seed-id" not in {item["questionId"] for item in items} for items in gold_sets.values())
    assert {item["sourceFile"] for item in gold_sets["parabola"]} == {"a.pdf", "b.pdf", "c.pdf"}
    assert audits["parabola"]["authoritativeKnowledgeLabelsPresent"] is False
    assert audits["parabola"]["supervision"] == "weak_supervision_manual_audit"


def test_build_query_has_explicit_modes_and_no_answer_text() -> None:
    stem = "已知抛物线 C，求焦点。\n【答案】A"

    assert MODULE.build_query("parabola", stem, mode="topic_only") == "抛物线 焦点 准线"
    assert MODULE.build_query("parabola", stem, mode="seed_stem_only") == "已知抛物线 C，求焦点。"
    assert "答案" not in MODULE.build_query("parabola", "已知抛物线 C，求焦点。", mode="aligned_topic_plus_stem")
    assert "抛物线" in MODULE.build_query("parabola", "已知抛物线 C，求焦点。", mode="aligned_topic_plus_stem")


def test_score_gold_recall_deduplicates_hits_and_reports_metrics() -> None:
    gold = [
        {"sourceFile": "a.pdf", "questionNumber": "1", "questionId": "gold-a"},
        {"sourceFile": "b.pdf", "questionNumber": "2", "questionId": "gold-b"},
        {"sourceFile": "c.pdf", "questionNumber": "3", "questionId": "gold-c"},
    ]
    hits = [
        {"id": "gold-a", "distance": 0.99, "metadata": {"sourceFile": "a.pdf", "questionNumber": "1"}},
        {"id": "gold-a", "distance": 0.98, "metadata": {"sourceFile": "a.pdf", "questionNumber": "1"}},
        {"id": "wrong-2", "distance": 0.85, "metadata": {"sourceFile": "wrong-2.pdf", "questionNumber": "8"}},
        {"id": "gold-b", "distance": 0.80, "metadata": {"sourceFile": "b.pdf", "questionNumber": "2"}},
        {"id": "wrong-3", "distance": 0.75, "metadata": {"sourceFile": "wrong-3.pdf", "questionNumber": "7"}},
        {"id": "gold-c", "distance": 0.70, "metadata": {"sourceFile": "c.pdf", "questionNumber": "3"}},
        {"id": "outside-top-k", "distance": 0.60, "metadata": {"sourceFile": "outside.pdf", "questionNumber": "4"}},
    ]

    score, row_matches = MODULE.score_gold_recall(hits, gold, top_k=4)

    assert score["goldCount"] == 3
    assert score["relevantHitCount"] == 2
    assert score["returnedUniqueHitCount"] == 4
    assert score["falsePositiveCount"] == 2
    assert score["recallAtK"] == pytest.approx(2 / 3)
    assert score["precisionAtK"] == pytest.approx(0.5)
    assert score["firstGoldHitRank"] == 1
    assert score["mrr"] == 1.0
    assert score["grade"] == "A"
    assert row_matches[0]["rawRank"] == 1


def test_hit_key_prioritizes_source_and_question_over_legacy_uuid() -> None:
    hits = [
        {"id": "legacy-a", "metadata": {"sourceFile": "paper.pdf", "questionNumber": "11"}},
        {"id": "legacy-b", "metadata": {"sourceFile": "paper.pdf", "questionNumber": "11"}},
    ]

    unique = MODULE.deduplicate_hits(hits)

    assert len(unique) == 1
    assert unique[0]["rawRank"] == 1

    hits = [
        {"id": "same", "metadata": {"sourceFile": "paper.pdf", "questionNumber": "11"}},
        {"id": "same", "metadata": {"sourceFile": "paper.pdf", "questionNumber": "11"}},
        {"id": "other", "metadata": {"sourceFile": "paper.pdf", "questionNumber": "12"}},
    ]

    observation = MODULE.raw_duplicate_observation(hits, top_k=3)

    assert observation["rawTopKDuplicateKeyCount"] == 1
    assert observation["rawTopKDuplicateRowCount"] == 1
    assert observation["rawTopKLargestRepeatedKeyOccupancy"] == 2
    assert observation["rawTopKRepeatedKeys"][0]["rawRanks"] == [1, 2]

    gold = [{"sourceFile": "a.pdf", "questionNumber": "1", "questionId": "gold-a"}]
    score, _ = MODULE.score_gold_recall(
        [{"id": "wrong", "metadata": {"sourceFile": "b.pdf", "questionNumber": "2"}}],
        gold,
        top_k=5,
    )

    assert score["recallAtK"] == 0.0
    assert score["mrr"] == 0.0
    assert score["firstGoldHitRank"] is None
    assert score["grade"] == "F"


def test_resolve_assets_accepts_opaque_id_and_canonical_figure(tmp_path: Path) -> None:
    paper_root, question_entry, _manifest = canonical_fixture(tmp_path)
    hit = {"id": "question-1", "metadata": {
        "sourceFile": SOURCE_FILE,
        "questionNumber": "1",
        "questionAssets": [{"assetId": ASSET_ID}],
    }}

    result = MODULE.resolve_question_assets(paper_root, question_entry, hit, SOURCE_SHA)

    assert result["status"] == "pass"
    assert result["vectorMetadataHasAssetIds"] is True
    assert result["controlledManifestResolution"] == "pass"
    assert result["canonicalFigureReferences"] == ["figures/q-001-01.png"]
    assert result["forbiddenReferenceDetected"] is False


@pytest.mark.parametrize(
    ("metadata", "expected_reason"),
    [
        ({"questionAssets": [{"assetId": ASSET_ID, "canonicalAssetPath": "page-images/page-001.png"}]}, "forbidden_asset_reference"),
        ({"questionAssets": [{"assetId": ASSET_ID, "canonicalAssetPath": "https://example.invalid/a.png"}]}, "forbidden_asset_reference"),
        ({"questionAssets": [{"assetId": OTHER_ASSET_ID}]}, "metadata_asset_not_bound_to_manifest_figure"),
        ({"assetIds": [ASSET_ID], "assetPath": "figures/q-001-01.png"}, "forbidden_asset_reference"),
    ],
)
def test_resolve_assets_rejects_page_url_or_unbound_references(
    tmp_path: Path, metadata: dict[str, object], expected_reason: str
) -> None:
    paper_root, question_entry, _manifest = canonical_fixture(tmp_path)
    hit = {"id": "question-1", "metadata": {
        "sourceFile": SOURCE_FILE,
        "questionNumber": "1",
        **metadata,
    }}

    result = MODULE.resolve_question_assets(paper_root, question_entry, hit, SOURCE_SHA)

    assert result["status"] == "fail"
    assert expected_reason in result["reasons"]


def test_audit_hit_contract_surfaces_missing_document_ref(tmp_path: Path) -> None:
    paper_root, question_entry, manifest = canonical_fixture(tmp_path)
    manifests = {SOURCE_FILE: (paper_root, manifest)}
    item = canonical_item(SOURCE_FILE, "1", "question-1", "已知抛物线 C，求其焦点。")
    item["question"] = question_entry
    catalog = {(SOURCE_FILE, "1"): item}
    gold = [{"sourceFile": SOURCE_FILE, "questionNumber": "1", "questionId": "question-1"}]

    detail, errors = MODULE.audit_hit_contract(
        {"id": "question-1", "metadata": {"sourceFile": SOURCE_FILE, "questionNumber": "1"}},
        1,
        1,
        manifests,
        catalog,
        gold,
    )

    assert detail["documentRef"] == ""
    assert "document_ref_missing" in errors
    assert detail["assetContract"]["status"] == "fail"


def test_hit_text_summary_excludes_solution_content() -> None:
    summary = MODULE._safe_hit_text_summary({
        "text": "题干内容\n【答案】A\n【解析】不得进入报告摘要",
    })

    assert summary == "题干内容"
    assert "答案" not in summary
    assert "解析" not in summary


def test_search_milvus_payload_is_read_only(monkeypatch: pytest.MonkeyPatch) -> None:
    calls: list[tuple[str, dict[str, object]]] = []

    def fake_post_json(uri: str, token: str, path: str, payload: dict[str, object], timeout: int) -> dict[str, object]:
        calls.append((path, payload))
        return {"code": 0, "data": [[{"id": "row", "metadata": {"questionNumber": "1"}}]]}

    monkeypatch.setattr(MODULE, "_post_json", fake_post_json)
    hits = MODULE.search_milvus([0.1] * MODULE.VECTOR_DIMENSION, "gaokao_math", "http://milvus", "secret", 3, 1)

    assert hits == [{"id": "row", "metadata": {"questionNumber": "1"}}]
    assert calls[0][0] == "/v2/vectordb/entities/search"
    assert calls[0][1]["collectionName"] == "gaokao_math"
    assert calls[0][1]["searchParams"] == {"metricType": "COSINE", "params": {}}
    assert calls[0][1]["outputFields"] == ["id", "metadata", "text"]
    assert "upsert" not in calls[0][0]


if __name__ == "__main__":
    raise SystemExit(pytest.main([__file__]))
