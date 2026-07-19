from benchmarks.deepseek_react_rag_eval import (
    _is_runtime_benchmark_resource,
    _derived_runtime_tags,
    _normalized_filter_tags,
    _runtime_topic_tags,
    _sample_size,
    _source_grounded_filter_variants,
    _first_rank,
    _preferred_template_code,
    _summarize_recall_rows,
    _summarize_source_grounded_rows,
    _teacher_filter_arguments,
)


def test_teacher_filter_arguments_accepts_deepseek_lists() -> None:
    assert _teacher_filter_arguments({
        "teacherResourceFilter": {
            "permissionScopes": ["math_vip", "TEACHER_PRIVATE"],
            "tags": "derivative, monotonicity",
            "documentIds": ["must-be-ignored"],
        }
    }, "Explain derivative monotonicity") == {
        "permissionScopes": ["math_vip", "TEACHER_PRIVATE"],
        "tags": ["derivative", "monotonicity"],
    }


def test_first_rank_supports_recall_at_1_and_3() -> None:
    document_ids = ["doc-a", "doc-b", "doc-c"]

    assert _first_rank(document_ids, "doc-a") == 1
    assert _first_rank(document_ids, "doc-c") == 3
    assert _first_rank(document_ids, "doc-x") is None


def test_summarize_recall_rows_keeps_non_perfect_rates() -> None:
    summary = _summarize_recall_rows([
        {"status": 200, "hitCount": 2, "recallAt1": True, "recallAt3": True, "recallAt5": True, "recallAt10": True, "elapsedMs": 10, "retrievalMode": "teacher_block_hybrid"},
        {"status": 200, "hitCount": 1, "recallAt1": False, "recallAt3": False, "recallAt5": True, "recallAt10": True, "elapsedMs": 20, "retrievalMode": "teacher_block_hybrid_filtered"},
        {"status": 200, "hitCount": 0, "recallAt1": False, "recallAt3": False, "recallAt5": False, "recallAt10": False, "elapsedMs": 30, "retrievalMode": "teacher_block_hybrid_filtered"},
    ])

    assert summary["recallAt1"] == 1 / 3
    assert summary["recallAt5"] == 2 / 3
    assert summary["filteredRetrievalRate"] == 2 / 3


def test_preferred_template_code_uses_teacher_solution_when_available() -> None:
    assert _preferred_template_code([
        {"code": "default_standard"},
        {"code": "teacher_solution_v1"},
    ]) == "teacher_solution_v1"


def test_normalized_filter_tags_drops_ascii_tags_for_cjk_query() -> None:
    assert _normalized_filter_tags(["tangent", "焦点", "minimum area"], "椭圆切线最值怎么讲") == ["焦点"]


def test_runtime_benchmark_resource_match_is_narrow() -> None:
    assert _is_runtime_benchmark_resource(
        "runtime-vector-123",
        r"C:\repo\output\benchmarks\deepseek-react-rag-20260707-150546\runtime-authored\02-vector",
    )
    assert not _is_runtime_benchmark_resource(
        "teacher-vector-notes",
        r"C:\repo\data\vector",
    )


def test_summarize_source_grounded_rows_has_llm_judge_metrics() -> None:
    summary = _summarize_source_grounded_rows([
        {"status": 200, "filterMode": "none", "blockRecallAt1": True, "blockRecallAt3": True, "blockRecallAt5": True, "documentRecallAt1": True, "documentRecallAt3": True, "documentRecallAt5": True, "judgePass": True, "judgeScore": 5, "elapsedMs": 10},
        {"status": 200, "filterMode": "scope", "blockRecallAt1": False, "blockRecallAt3": False, "blockRecallAt5": True, "documentRecallAt1": False, "documentRecallAt3": True, "documentRecallAt5": True, "judgePass": False, "judgeScore": 2, "elapsedMs": 20},
    ])

    assert summary["blockRecallAt1"] == 0.5
    assert summary["documentRecallAt3"] == 1.0
    assert summary["judgePassRate"] == 0.5
    assert summary["avgJudgeScore"] == 3.5
    assert summary["byFilterMode"]["none"]["blockRecallAt1"] == 1.0


def test_source_grounded_filter_variants_cover_none_scope_tag_and_combined() -> None:
    variants = _source_grounded_filter_variants({
        "query": "test",
        "scope": "MATH_VIP",
        "derivedTags": ["chapter-a", "section-b"],
    })

    assert [name for name, _ in variants] == ["none", "scope", "tag", "scope+tag"]


def test_runtime_topic_tags_expand_beyond_topic_and_knowledge_label() -> None:
    tags = _runtime_topic_tags({
        "id": "derivative",
        "title": "导数参数讨论",
        "knowledge_label": "导数与单调性",
        "question": "老师想讲为什么不能只看导数零点，还要检查定义域、端点和符号变化。",
        "goal": "说明参数讨论里单调性判断的入口和常见误区。",
        "notes": [
            "先看定义域，再看导数为零和不可导点。",
            "不要把中间式当成最终结论。",
        ],
    })

    assert "derivative" in tags
    assert "导数与单调性" in tags
    assert len(tags) >= 4
    assert any("定义域" in tag or "单调" in tag for tag in tags)


def test_derived_runtime_tags_include_structure_and_text_signals() -> None:
    tags = _derived_runtime_tags({
        "topicId": "vector",
        "chapter": "讲法碎片",
        "section": "板书顺序",
        "text": "先用法向量解释线面角入口，再回到建系后的数量关系，最后提醒学生不要只堆坐标。",
    })

    assert "vector" in tags
    assert "讲法碎片" in tags
    assert "板书顺序" in tags
    assert any("法向量" in tag or "线面角" in tag or "建系" in tag for tag in tags)


def test_sample_size_allows_zero_without_falling_back_to_default() -> None:
    assert _sample_size({"deepseekMcpSampleSize": 0}, "deepseekMcpSampleSize", 8) == 0
    assert _sample_size({}, "deepseekMcpSampleSize", 8) == 8
