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


def main() -> None:
    parser = argparse.ArgumentParser(
        description=(
            "Re-score existing live teacher-resource retrieval rows with library-targeted calls "
            "and current backend block ids."
        )
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
    cases = _resolve_cases(client, reference_cases)

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
                "alignment_method": case["alignment_method"],
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
        "alignment": _summarize_alignment(cases),
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
        expected_document_id = str(row.get("expected_document_id") or "").strip()
        expected_role = str(row.get("expected_role") or "").strip()
        expected_scope = str(row.get("expected_scope") or "").strip()
        expected_library = str(row.get("expected_library") or "").strip()
        query = str(row.get("query") or "").strip()
        if not all((expected_document_id, expected_role, expected_scope, expected_library, query)):
            continue
        cases_by_id[case_id] = {
            "case_id": case_id,
            "query": query,
            "reference_expected_document_id": expected_document_id,
            "reference_expected_block_id": str(row.get("expected_block_id") or "").strip(),
            "expected_scope": expected_scope,
            "expected_role": expected_role,
            "expected_library": expected_library,
        }
    if not cases_by_id:
        raise RuntimeError(f"No reusable query rows found in {path}")
    return list(cases_by_id.values())


def _resolve_cases(client: MathAgentClient, reference_cases: list[dict[str, Any]]) -> list[dict[str, Any]]:
    active_documents = client.get("/api/teacher/resources").body
    active_document_ids = {
        str(document.get("documentId") or document.get("id") or "")
        for document in (active_documents if isinstance(active_documents, list) else [])
        if isinstance(document, dict)
    }
    cases_by_document_role: dict[tuple[str, str], list[dict[str, Any]]] = defaultdict(list)
    for case in reference_cases:
        document_id = case["reference_expected_document_id"]
        if document_id not in active_document_ids:
            raise RuntimeError(
                f"Reference document is no longer active for case {case['case_id']}: {document_id}. "
                "Re-run ingestion first or use a newer reference run."
            )
        cases_by_document_role[(document_id, case["expected_role"])].append(case)

    reference_order = {case["case_id"]: index for index, case in enumerate(reference_cases)}
    resolved: list[dict[str, Any]] = []
    document_block_cache: dict[str, list[dict[str, Any]]] = {}
    for (document_id, expected_role), group in cases_by_document_role.items():
        document_blocks = document_block_cache.setdefault(document_id, _load_document_blocks(client, document_id))
        role_blocks = sorted(
            [block for block in document_blocks if str(block.get("blockRole") or "") == expected_role],
            key=_block_order,
        )
        if len(role_blocks) < len(group):
            raise RuntimeError(
                f"Not enough active {expected_role} blocks in document {document_id}: "
                f"need {len(group)}, found {len(role_blocks)}"
            )
        for ordinal, case in enumerate(group):
            matched_block = _match_reference_block(case, role_blocks, ordinal)
            expected_block_id = str(matched_block.get("blockId") or matched_block.get("id") or "")
            resolved.append({
                **case,
                "expected_document_id": document_id,
                "expected_block_id": expected_block_id,
                "alignment_method": "same_block_id"
                if expected_block_id == case["reference_expected_block_id"]
                else "role_order",
            })
    return sorted(resolved, key=lambda case: reference_order[case["case_id"]])


def _load_document_blocks(client: MathAgentClient, document_id: str) -> list[dict[str, Any]]:
    blocks_response = client.get(f"/api/teacher/resources/{document_id}/blocks")
    blocks = blocks_response.body if isinstance(blocks_response.body, list) else []
    return [block for block in blocks if isinstance(block, dict)]


def _match_reference_block(case: dict[str, Any], role_blocks: list[dict[str, Any]], ordinal: int) -> dict[str, Any]:
    reference_block_id = case["reference_expected_block_id"]
    for block in role_blocks:
        block_id = str(block.get("blockId") or block.get("id") or "")
        if block_id == reference_block_id:
            return block
    return role_blocks[ordinal]


def _block_order(block: dict[str, Any]) -> tuple[int, str]:
    try:
        order = int(block.get("blockOrder"))
    except (TypeError, ValueError):
        order = 1_000_000
    return order, str(block.get("blockId") or block.get("id") or "")


def _summarize_alignment(cases: list[dict[str, Any]]) -> dict[str, Any]:
    methods: dict[str, int] = defaultdict(int)
    for case in cases:
        methods[str(case["alignment_method"])] += 1
    return {
        "caseCount": len(cases),
        "methods": dict(sorted(methods.items())),
        "usesBlockText": False,
        "storesStaticCaseLocators": False,
        "notes": [
            "same_block_id means the historical expected block id still exists in the current real backend state.",
            "role_order is the strongest available non-text fallback when the historical block id no longer exists but the document id is unchanged.",
            "role_order matches only within the same source document, the same block role, and the active blockOrder ordering returned by the real backend.",
        ],
    }


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
