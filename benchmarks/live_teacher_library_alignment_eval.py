from __future__ import annotations

import argparse
import json
import sys
import time
from collections import defaultdict
from pathlib import Path
from typing import Any

if __package__ in {None, ""}:
    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from benchmarks.http_client import MathAgentClient


DEFAULT_REFERENCE_RUN = Path("output") / "benchmarks" / "live-two-stage-teacher-20260708-1"
DEFAULT_OUTPUT_ROOT = Path("output") / "benchmarks"

STRATEGY_LEGACY = "legacy_block_hybrid"
STRATEGY_TWO_STAGE = "two_stage_doc_block"

CASE_LOCATORS: dict[str, dict[str, str]] = {
    "textbook-interval-endpoint": {
        "title": "runtime-public-textbook-derivative",
        "library": "textbook",
        "scope": "PUBLIC_TEXTBOOK",
        "role": "reference",
        "sourcePathContains": "教材-导数参数讨论",
        "sectionContains": "闭区间单调性",
    },
    "textbook-sign-table": {
        "title": "runtime-public-textbook-derivative",
        "library": "textbook",
        "scope": "PUBLIC_TEXTBOOK",
        "role": "reference",
        "sourcePathContains": "教材-导数参数讨论",
        "sectionContains": "参数分类入口",
    },
    "qq-analysis-angle": {
        "title": "runtime-qq-bundle-vector",
        "library": "qq_bundle",
        "scope": "MATH_VIP",
        "role": "analysis",
        "sourcePathContains": "点评",
        "sectionContains": "",
    },
    "qq-solution-route": {
        "title": "runtime-qq-bundle-vector",
        "library": "qq_bundle",
        "scope": "MATH_VIP",
        "role": "analysis",
        "sourcePathContains": "答案解析",
        "sectionContains": "点积转角",
    },
    "feishu-boardwork-columns": {
        "title": "runtime-feishu-method-probability",
        "library": "feishu",
        "scope": "TEACHER_PRIVATE",
        "role": "boardwork",
        "sourcePathContains": "板书逻辑",
        "sectionContains": "三列板书",
    },
    "feishu-method-before-formula": {
        "title": "runtime-feishu-method-probability",
        "library": "feishu",
        "scope": "TEACHER_PRIVATE",
        "role": "method",
        "sourcePathContains": "讲法模板",
        "sectionContains": "先分模型",
    },
    "feishu-tip-without-replacement": {
        "title": "runtime-feishu-method-probability",
        "library": "feishu",
        "scope": "TEACHER_PRIVATE",
        "role": "tip",
        "sourcePathContains": "课堂提示",
        "sectionContains": "易错提醒",
    },
    "gaokao-conic-setup": {
        "title": "runtime-gaokao-conic",
        "library": "gaokao",
        "scope": "MATH_VIP",
        "role": "analysis",
        "sourcePathContains": "解析",
        "sectionContains": "变量怎么设",
    },
    "gaokao-question": {
        "title": "runtime-gaokao-conic",
        "library": "gaokao",
        "scope": "MATH_VIP",
        "role": "question",
        "sourcePathContains": "高考真题",
        "sectionContains": "椭圆切线题",
    },
    "mock-sequence-answer": {
        "title": "runtime-mock-sequence",
        "library": "mock_exam",
        "scope": "TEACHER_PRIVATE",
        "role": "analysis",
        "sourcePathContains": "答案",
        "sectionContains": "先转化再回代",
    },
    "mock-sequence-commentary": {
        "title": "runtime-mock-sequence",
        "library": "mock_exam",
        "scope": "TEACHER_PRIVATE",
        "role": "analysis",
        "sourcePathContains": "讲评",
        "sectionContains": "易错点",
    },
}


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Re-score the live teacher-resource retrieval cases with library-targeted calls and refreshed block ids."
    )
    parser.add_argument("--config", default=".tmp/grounded-compare-6.json")
    parser.add_argument("--reference-run", default=str(DEFAULT_REFERENCE_RUN))
    parser.add_argument("--output-dir", default="")
    parser.add_argument("--request-delay-ms", type=int, default=250)
    args = parser.parse_args()

    config = json.loads(Path(args.config).read_text(encoding="utf-8"))
    reference_run = Path(args.reference_run)
    output_dir = Path(args.output_dir) if args.output_dir else _default_output_dir()
    output_dir.mkdir(parents=True, exist_ok=True)

    client = MathAgentClient(config.get("backendBaseUrl", "http://127.0.0.1:8080"), timeout=120)
    client.login(config.get("adminUsername", "admin"), config.get("adminPassword", "admin-123456"))

    reference_cases = _load_reference_queries(reference_run / "query_rows.jsonl")
    active_documents = client.get("/api/teacher/resources").body
    documents_by_title = {
        str(document.get("title") or ""): document
        for document in (active_documents if isinstance(active_documents, list) else [])
        if isinstance(document, dict)
    }
    cases = _resolve_cases(client, reference_cases, documents_by_title)

    rows: list[dict[str, Any]] = []
    for mode in (
        {"name": "legacy_mixed", "strategy": STRATEGY_LEGACY, "library": False},
        {"name": "two_stage_mixed", "strategy": STRATEGY_TWO_STAGE, "library": False},
        {"name": "two_stage_specified_library", "strategy": STRATEGY_TWO_STAGE, "library": True},
    ):
        for case in cases:
            params: dict[str, Any] = {
                "query": case["query"],
                "limit": 5,
                "strategy": mode["strategy"],
            }
            if mode["library"]:
                params["library"] = case["expected_library"]
            attempt = client.get("/api/teacher/resources/search", params=params)
            body = attempt.body if isinstance(attempt.body, dict) else {}
            hits = [hit for hit in (body.get("hits") or []) if isinstance(hit, dict)]
            hit_document_ids = [str(hit.get("documentId") or "") for hit in hits]
            hit_block_ids = [str(hit.get("blockId") or "") for hit in hits]
            document_rank = _first_rank(hit_document_ids, case["expected_document_id"])
            block_rank = _first_rank(hit_block_ids, case["expected_block_id"])
            top_hit = hits[0] if hits else {}
            rows.append({
                "case_id": case["case_id"],
                "query": case["query"],
                "mode": mode["name"],
                "query_id": str(body.get("queryId") or ""),
                "retrieval_mode": str(body.get("retrievalMode") or ""),
                "latency_ms": round(float(attempt.elapsed_ms or 0), 2),
                "expected_document_id": case["expected_document_id"],
                "expected_block_id": case["expected_block_id"],
                "expected_role": case["expected_role"],
                "expected_scope": case["expected_scope"],
                "expected_library": case["expected_library"],
                "library_param": case["expected_library"] if mode["library"] else "",
                "document_hit_ranks": _rank_flags(document_rank),
                "block_hit_ranks": _rank_flags(block_rank),
                "top_hit": {
                    "documentId": str(top_hit.get("documentId") or ""),
                    "blockId": str(top_hit.get("blockId") or ""),
                    "sourceType": str(top_hit.get("sourceType") or ""),
                    "permissionScope": str(top_hit.get("permissionScope") or ""),
                    "blockRole": str(top_hit.get("blockRole") or ""),
                    "score": top_hit.get("score"),
                } if top_hit else {},
                "scope_hit_top1": bool(top_hit) and str(top_hit.get("permissionScope") or "") == case["expected_scope"],
                "role_hit_top1": bool(top_hit) and str(top_hit.get("blockRole") or "") == case["expected_role"],
                "library_hit_top1": bool(top_hit) and _same_library(
                    str(top_hit.get("sourceType") or ""),
                    case["expected_library"],
                ),
                "raw_hit_count": len(hits),
            })
            if args.request_delay_ms > 0:
                time.sleep(args.request_delay_ms / 1000)

    metrics = {
        "generatedAt": time.strftime("%Y-%m-%dT%H:%M:%S"),
        "reused": {
            "referenceRun": str(reference_run),
            "queryRows": str(reference_run / "query_rows.jsonl"),
        },
        "dataset": {
            "queryCount": len(cases),
            "modeCount": 3,
            "runDir": str(output_dir.resolve()),
        },
        "teacherDirectSearch": _summarize_rows(rows),
    }

    (output_dir / "query_rows.jsonl").write_text(
        "\n".join(json.dumps(row, ensure_ascii=False) for row in rows) + "\n",
        encoding="utf-8",
    )
    (output_dir / "metrics.json").write_text(json.dumps(metrics, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(metrics, ensure_ascii=False, indent=2))


def _default_output_dir() -> Path:
    timestamp = time.strftime("%Y%m%d-%H%M%S")
    return DEFAULT_OUTPUT_ROOT / f"live-two-stage-teacher-library-{timestamp}"


def _load_reference_queries(path: Path) -> list[dict[str, Any]]:
    rows = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]
    cases_by_id: dict[str, dict[str, Any]] = {}
    for row in rows:
        case_id = str(row.get("case_id") or "").strip()
        if not case_id or case_id in cases_by_id:
            continue
        locator = CASE_LOCATORS.get(case_id)
        if locator is None:
            continue
        cases_by_id[case_id] = {
            "case_id": case_id,
            "query": str(row.get("query") or ""),
            "expected_scope": locator["scope"],
            "expected_role": locator["role"],
            "expected_library": locator["library"],
            "document_title": locator["title"],
            "sourcePathContains": locator["sourcePathContains"],
            "sectionContains": locator["sectionContains"],
        }
    return list(cases_by_id.values())


def _resolve_cases(
    client: MathAgentClient,
    reference_cases: list[dict[str, Any]],
    documents_by_title: dict[str, dict[str, Any]],
) -> list[dict[str, Any]]:
    resolved = []
    for case in reference_cases:
        document = documents_by_title.get(case["document_title"])
        if document is None:
            raise RuntimeError(f"Active document not found for case {case['case_id']}: {case['document_title']}")
        document_id = str(document.get("documentId") or document.get("id") or "")
        if not document_id:
            raise RuntimeError(f"Missing document id for case {case['case_id']}")
        blocks_response = client.get(f"/api/teacher/resources/{document_id}/blocks")
        blocks = blocks_response.body if isinstance(blocks_response.body, list) else []
        matched_block = None
        for block in blocks:
            if not isinstance(block, dict):
                continue
            if str(block.get("blockRole") or "") != case["expected_role"]:
                continue
            if case["sourcePathContains"] and case["sourcePathContains"] not in str(block.get("sourcePath") or ""):
                continue
            if case["sectionContains"] and case["sectionContains"] not in str(block.get("section") or ""):
                continue
            matched_block = block
            break
        if matched_block is None:
            raise RuntimeError(f"Could not align current block id for case {case['case_id']}")
        resolved.append({
            **case,
            "expected_document_id": document_id,
            "expected_block_id": str(matched_block.get("blockId") or matched_block.get("id") or ""),
        })
    return resolved


def _summarize_rows(rows: list[dict[str, Any]]) -> dict[str, Any]:
    grouped: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for row in rows:
        grouped[str(row["mode"])].append(row)
    summary: dict[str, Any] = {}
    for mode, group in grouped.items():
        summary[mode] = {
            "count": len(group),
            "documentRecallAt1": _rate(group, lambda row: bool(row["document_hit_ranks"]["top1"])),
            "documentRecallAt3": _rate(group, lambda row: bool(row["document_hit_ranks"]["top3"])),
            "documentRecallAt5": _rate(group, lambda row: bool(row["document_hit_ranks"]["top5"])),
            "blockRecallAt1": _rate(group, lambda row: bool(row["block_hit_ranks"]["top1"])),
            "blockRecallAt3": _rate(group, lambda row: bool(row["block_hit_ranks"]["top3"])),
            "blockRecallAt5": _rate(group, lambda row: bool(row["block_hit_ranks"]["top5"])),
            "scopeHitRate": _rate(group, lambda row: bool(row["scope_hit_top1"])),
            "roleHitRate": _rate(group, lambda row: bool(row["role_hit_top1"])),
            "libraryHitRate": _rate(group, lambda row: bool(row["library_hit_top1"])),
            "avgLatencyMs": round(sum(float(row["latency_ms"]) for row in group) / len(group), 2) if group else 0.0,
        }
    return summary


def _rank_flags(rank: int | None) -> dict[str, bool]:
    return {
        "top1": bool(rank and rank <= 1),
        "top3": bool(rank and rank <= 3),
        "top5": bool(rank and rank <= 5),
    }


def _first_rank(values: list[str], expected: str) -> int | None:
    for index, value in enumerate(values, start=1):
        if value == expected:
            return index
    return None


def _same_library(actual: str, expected: str) -> bool:
    normalized_actual = (actual or "").strip().lower()
    normalized_expected = (expected or "").strip().lower()
    if normalized_expected == "textbook":
        return normalized_actual in {"textbook", "public_textbook"}
    return normalized_actual == normalized_expected


def _rate(rows: list[dict[str, Any]], predicate) -> float:
    if not rows:
        return 0.0
    return sum(1 for row in rows if predicate(row)) / len(rows)


if __name__ == "__main__":
    main()
