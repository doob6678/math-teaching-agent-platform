from __future__ import annotations

from benchmarks import textbook_backend_46_eval as backend_eval


def test_library_payload_contains_no_scoring_or_scope_fields() -> None:
    payload = backend_eval.library_search_payload("任意教材查询", 10)

    assert payload == {"query": "任意教材查询", "limit": 10}


def test_target_gate_requires_all_document_and_block_metrics() -> None:
    passing = {
        "requestErrorCount": 0,
        "documentRecall@1": 0.81,
        "documentRecall@3": 0.91,
        "blockRecall@1": 0.71,
        "blockRecall@3": 0.86,
    }

    target_gate = getattr(backend_eval, "targets_satisfied", None)
    assert callable(target_gate), "the evaluator must expose one auditable four-metric gate"
    assert target_gate(passing)
    for metric in ("documentRecall@1", "documentRecall@3", "blockRecall@1", "blockRecall@3"):
        failing = dict(passing)
        failing[metric] = 0.0
        assert not target_gate(failing), metric


def test_target_gate_uses_strict_greater_than() -> None:
    boundary = {
        "requestErrorCount": 0,
        "documentRecall@1": 0.80,
        "documentRecall@3": 0.90,
        "blockRecall@1": 0.70,
        "blockRecall@3": 0.85,
    }

    target_gate = getattr(backend_eval, "targets_satisfied", None)
    assert callable(target_gate), "the evaluator must expose one auditable four-metric gate"
    assert not target_gate(boundary)
