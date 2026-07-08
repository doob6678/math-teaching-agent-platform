from __future__ import annotations

import argparse
import json
import sys
import time
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any

if __package__ in {None, ""}:
    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from benchmarks.http_client import MathAgentClient


DEFAULT_OUTPUT_ROOT = Path("output") / "benchmarks"
STRATEGY_LEGACY = "legacy_block_hybrid"
STRATEGY_TWO_STAGE = "two_stage_doc_block"


def main() -> None:
    parser = argparse.ArgumentParser(description="Evaluate runtime-authored teacher-resource JSON cases against the real backend.")
    parser.add_argument("--config", default=".tmp/grounded-compare-6.json")
    parser.add_argument("--cases-json", required=True)
    parser.add_argument("--output-dir", default="")
    parser.add_argument("--request-delay-ms", type=int, default=120)
    args = parser.parse_args()

    config = json.loads(Path(args.config).read_text(encoding="utf-8"))
    cases = _load_cases(Path(args.cases_json))
    output_dir = Path(args.output_dir) if args.output_dir else _default_output_dir()
    output_dir.mkdir(parents=True, exist_ok=True)

    client = MathAgentClient(config.get("backendBaseUrl", "http://127.0.0.1:8080"), timeout=120)
    client.login(config.get("adminUsername", "admin"), config.get("adminPassword", "admin-123456"))

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
            library_param = ""
            if mode["library"]:
                library_param = case.get("requested_library") or case.get("expected_library") or ""
                if library_param:
                    params["library"] = library_param
            attempt = client.get("/api/teacher/resources/search", params=params)
            body = attempt.body if isinstance(attempt.body, dict) else {}
            hits = [hit for hit in (body.get("hits") or []) if isinstance(hit, dict)]
            hit_document_ids = [str(hit.get("documentId") or "") for hit in hits]
            hit_block_ids = [str(hit.get("blockId") or "") for hit in hits]
            document_rank = _first_rank(hit_document_ids, case.get("expected_document_id", ""))
            block_rank = _first_rank(hit_block_ids, case.get("expected_block_id", ""))
            top_hit = hits[0] if hits else {}
            case_type = case.get("case_type", "positive")
            request_error = (not attempt.ok) or not str(body.get("retrievalMode") or "").strip()
            negative_pass = _negative_pass(case, hits) if case_type != "positive" and not request_error else False
            forbidden_library_leak_top1 = _library_leak(case.get("forbidden_library", ""), hits[:1])
            forbidden_library_leak_top5 = _library_leak(case.get("forbidden_library", ""), hits[:5])
            forbidden_role_leak_top1 = _role_leak(case.get("forbidden_role", ""), hits[:1])
            forbidden_role_leak_top5 = _role_leak(case.get("forbidden_role", ""), hits[:5])
            rows.append({
                "case_id": case["case_id"],
                "query": case["query"],
                "case_type": case_type,
                "topic": case.get("topic", ""),
                "difficulty": case.get("difficulty", ""),
                "user_type": case.get("user_type", ""),
                "fail_type": case.get("fail_type", ""),
                "mode": mode["name"],
                "query_id": str(body.get("queryId") or ""),
                "retrieval_mode": str(body.get("retrievalMode") or ""),
                "http_status": attempt.status,
                "request_error": request_error,
                "latency_ms": round(float(attempt.elapsed_ms or 0), 2),
                "expected_document_id": case.get("expected_document_id", ""),
                "expected_block_id": case.get("expected_block_id", ""),
                "expected_role": case.get("expected_role", ""),
                "expected_scope": case.get("expected_scope", ""),
                "expected_library": case.get("expected_library", ""),
                "requested_library": case.get("requested_library", ""),
                "forbidden_library": case.get("forbidden_library", ""),
                "forbidden_role": case.get("forbidden_role", ""),
                "expected_topk": case.get("expected_topk", ""),
                "library_param": library_param,
                "document_hit_ranks": _rank_flags(document_rank),
                "block_hit_ranks": _rank_flags(block_rank),
                "expected_topk_pass": _expected_topk_pass(block_rank, case.get("expected_topk", "")),
                "top_hit": {
                    "documentId": str(top_hit.get("documentId") or ""),
                    "blockId": str(top_hit.get("blockId") or ""),
                    "sourceType": str(top_hit.get("sourceType") or ""),
                    "permissionScope": str(top_hit.get("permissionScope") or ""),
                    "blockRole": str(top_hit.get("blockRole") or ""),
                    "score": top_hit.get("score"),
                } if top_hit else {},
                "scope_hit_top1": bool(top_hit) and bool(case.get("expected_scope")) and str(top_hit.get("permissionScope") or "") == case.get("expected_scope", ""),
                "role_hit_top1": bool(top_hit) and bool(case.get("expected_role")) and str(top_hit.get("blockRole") or "") == case.get("expected_role", ""),
                "library_hit_top1": bool(top_hit) and bool(case.get("expected_library")) and _same_library(
                    str(top_hit.get("sourceType") or ""),
                    case.get("expected_library", ""),
                ),
                "negative_pass": negative_pass,
                "false_positive": case_type != "positive" and bool(hits),
                "forbidden_library_leak_top1": forbidden_library_leak_top1,
                "forbidden_library_leak_top5": forbidden_library_leak_top5,
                "forbidden_role_leak_top1": forbidden_role_leak_top1,
                "forbidden_role_leak_top5": forbidden_role_leak_top5,
                "raw_hit_count": len(hits),
            })
            if args.request_delay_ms > 0:
                time.sleep(args.request_delay_ms / 1000)

    metrics = {
        "generatedAt": time.strftime("%Y-%m-%dT%H:%M:%S"),
        "generation": {
            "caseFile": str(Path(args.cases_json).resolve()),
            "queryCount": len(cases),
            "usesBlockText": False,
            "storesQueriesInRepo": False,
            "libraries": sorted({case.get("expected_library", "") for case in cases if case.get("expected_library")}),
            "roles": sorted({case.get("expected_role", "") for case in cases if case.get("expected_role")}),
            "caseTypes": dict(Counter(case.get("case_type", "positive") for case in cases)),
            "difficulties": dict(Counter(case.get("difficulty", "") for case in cases)),
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


def _load_cases(path: Path) -> list[dict[str, Any]]:
    payload = json.loads(path.read_text(encoding="utf-8-sig"))
    cases = payload.get("cases") if isinstance(payload, dict) else payload
    if not isinstance(cases, list):
        raise RuntimeError(f"cases JSON must contain a list under 'cases': {path}")
    normalized: list[dict[str, Any]] = []
    for index, case in enumerate(cases, start=1):
        if not isinstance(case, dict):
            raise RuntimeError(f"Case {index} is not an object: {path}")
        case_type = str(case.get("case_type") or "positive").strip()
        required = ["case_id", "query", "case_type", "topic", "difficulty", "user_type", "fail_type"]
        if case_type == "positive":
            required.extend([
                "expected_document_id",
                "expected_block_id",
                "expected_role",
                "expected_scope",
                "expected_library",
            ])
        elif case_type not in {"no_match", "forbidden_library", "wrong_role"}:
            raise RuntimeError(f"Case {index} has unsupported case_type={case_type!r}: {path}")
        missing = [field for field in required if not str(case.get(field) or "").strip()]
        if missing:
            raise RuntimeError(f"Case {index} missing required fields {missing}: {path}")
        normalized.append({field: str(value or "").strip() for field, value in case.items()})
    return normalized


def _default_output_dir() -> Path:
    timestamp = time.strftime("%Y%m%d-%H%M%S")
    return DEFAULT_OUTPUT_ROOT / f"live-two-stage-teacher-generated-100-{timestamp}"


def _summarize_rows(rows: list[dict[str, Any]]) -> dict[str, Any]:
    grouped: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for row in rows:
        grouped[str(row["mode"])].append(row)
    summary: dict[str, Any] = {}
    for mode, group in grouped.items():
        valid_group = [row for row in group if not row["request_error"]]
        positives = [row for row in valid_group if row["case_type"] == "positive"]
        negatives = [row for row in valid_group if row["case_type"] != "positive"]
        summary[mode] = {
            "count": len(group),
            "validCount": len(valid_group),
            "requestErrorCount": len(group) - len(valid_group),
            "positiveCount": len(positives),
            "negativeCount": len(negatives),
            "documentRecallAt1": _rate(positives, lambda row: bool(row["document_hit_ranks"]["top1"])),
            "documentRecallAt3": _rate(positives, lambda row: bool(row["document_hit_ranks"]["top3"])),
            "documentRecallAt5": _rate(positives, lambda row: bool(row["document_hit_ranks"]["top5"])),
            "blockRecallAt1": _rate(positives, lambda row: bool(row["block_hit_ranks"]["top1"])),
            "blockRecallAt3": _rate(positives, lambda row: bool(row["block_hit_ranks"]["top3"])),
            "blockRecallAt5": _rate(positives, lambda row: bool(row["block_hit_ranks"]["top5"])),
            "expectedTopkPassRate": _rate(positives, lambda row: bool(row["expected_topk_pass"])),
            "scopeHitRate": _rate(positives, lambda row: bool(row["scope_hit_top1"])),
            "roleHitRate": _rate(positives, lambda row: bool(row["role_hit_top1"])),
            "libraryHitRate": _rate(positives, lambda row: bool(row["library_hit_top1"])),
            "negativePassRate": _rate(negatives, lambda row: bool(row["negative_pass"])),
            "falsePositiveRate": _rate(negatives, lambda row: bool(row["false_positive"])),
            "forbiddenLibraryLeakTop1Rate": _rate(negatives, lambda row: bool(row["forbidden_library_leak_top1"])),
            "forbiddenLibraryLeakTop5Rate": _rate(negatives, lambda row: bool(row["forbidden_library_leak_top5"])),
            "forbiddenRoleLeakTop1Rate": _rate(negatives, lambda row: bool(row["forbidden_role_leak_top1"])),
            "forbiddenRoleLeakTop5Rate": _rate(negatives, lambda row: bool(row["forbidden_role_leak_top5"])),
            "avgLatencyMs": round(sum(float(row["latency_ms"]) for row in valid_group) / len(valid_group), 2) if valid_group else 0.0,
            "byDifficulty": _slice_summary(positives, "difficulty"),
            "byFailType": _slice_summary(valid_group, "fail_type"),
        }
    return summary


def _slice_summary(rows: list[dict[str, Any]], field: str) -> dict[str, Any]:
    grouped: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for row in rows:
        grouped[str(row.get(field) or "")].append(row)
    return {
        key: {
            "count": len(group),
            "documentRecallAt3": _rate(group, lambda row: bool(row["document_hit_ranks"]["top3"])),
            "blockRecallAt3": _rate(group, lambda row: bool(row["block_hit_ranks"]["top3"])),
            "negativePassRate": _rate([row for row in group if row["case_type"] != "positive"], lambda row: bool(row["negative_pass"])),
        }
        for key, group in sorted(grouped.items())
        if key
    }


def _rank_flags(rank: int | None) -> dict[str, bool]:
    return {
        "top1": bool(rank and rank <= 1),
        "top3": bool(rank and rank <= 3),
        "top5": bool(rank and rank <= 5),
    }


def _first_rank(values: list[str], expected: str) -> int | None:
    if not expected:
        return None
    for index, value in enumerate(values, start=1):
        if value == expected:
            return index
    return None


def _expected_topk_pass(rank: int | None, expected_topk: str) -> bool:
    if rank is None:
        return False
    try:
        return rank <= int(expected_topk)
    except (TypeError, ValueError):
        return rank <= 5


def _negative_pass(case: dict[str, str], hits: list[dict[str, Any]]) -> bool:
    case_type = case.get("case_type", "positive")
    if case_type == "no_match":
        return not hits
    if case_type == "forbidden_library":
        return not _library_leak(case.get("forbidden_library", ""), hits[:5])
    if case_type == "wrong_role":
        return not _role_leak(case.get("forbidden_role", ""), hits[:5])
    return False


def _library_leak(forbidden_library: str, hits: list[dict[str, Any]]) -> bool:
    if not forbidden_library:
        return False
    return any(_same_library(str(hit.get("sourceType") or ""), forbidden_library) for hit in hits)


def _role_leak(forbidden_role: str, hits: list[dict[str, Any]]) -> bool:
    if not forbidden_role:
        return False
    normalized = forbidden_role.strip().lower()
    return any(str(hit.get("blockRole") or "").strip().lower() == normalized for hit in hits)


def _same_library(actual: str, expected: str) -> bool:
    normalized_actual = (actual or "").strip().lower()
    normalized_expected = (expected or "").strip().lower()
    if normalized_expected == "textbook":
        return normalized_actual in {"textbook", "public_textbook"}
    if normalized_expected == "teacher_resource":
        return normalized_actual in {"teacher_resource", "local_path"}
    return normalized_actual == normalized_expected


def _rate(rows: list[dict[str, Any]], predicate) -> float:
    if not rows:
        return 0.0
    return sum(1 for row in rows if predicate(row)) / len(rows)


if __name__ == "__main__":
    main()
