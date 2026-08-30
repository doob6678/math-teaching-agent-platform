"""Evaluate current teacher-resource retrieval without mixing parser splits.

The evaluator intentionally reports one positive-only row for each current oracle
case.  Document recall is grouped by logical library and split group; block recall
is never aggregated across different document/chunk snapshots.  This makes a
change in chunking observable in the report instead of falsely looking like a
ranking regression.
"""

from __future__ import annotations

import argparse
import json
import os
import platform
import statistics
import subprocess
import sys
import time
from collections import defaultdict
from pathlib import Path
from typing import Any

import requests

if __package__ in {None, ""}:
    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from benchmarks.http_client import MathAgentClient


DEFAULT_BACKEND_URL = "http://127.0.0.1:8080"
DEFAULT_ADMIN_USERNAME = ""
DEFAULT_ADMIN_PASSWORD = ""
DEFAULT_LIMIT = 5
MAX_RERANK_CANDIDATES = 12
RECALL_CUTOFFS = (1, 3, 5)
NVIDIA_QUERY = ["nvidia-smi", "--query-gpu=name,utilization.gpu,memory.used,memory.total", "--format=csv,noheader,nounits"]
DOCKER_SERVICES = ("backend", "ai-worker", "milvus", "mysql", "redis")
WORKER_DEFAULT_URL = "http://127.0.0.1:8092"
WORKER_KEY_ENV = "MATH_AGENT_WORKER_API_KEY"


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cases-json", required=True)
    parser.add_argument("--output-dir", required=True)
    parser.add_argument("--config", default=os.environ.get("MATH_AGENT_BENCHMARK_CONFIG", ""))
    parser.add_argument("--limit", type=int, default=DEFAULT_LIMIT)
    parser.add_argument("--request-delay-ms", type=int, default=0)
    parser.add_argument("--timeout", type=float, default=120.0)
    parser.add_argument("--library", default="", help="Evaluate one logical library only; split families must not be mixed.")
    parser.add_argument("--mode", choices=("current_specified_library", "current_mixed", "both"), default="both")
    parser.add_argument("--worker-url", default=os.environ.get("MATH_AGENT_BENCHMARK_WORKER_URL", WORKER_DEFAULT_URL))
    parser.add_argument("--worker-api-key", default=os.environ.get(WORKER_KEY_ENV, ""), help=argparse.SUPPRESS)
    args = parser.parse_args()

    config = _load_config(Path(args.config) if args.config else None)
    backend_url = os.environ.get("MATH_AGENT_BENCHMARK_BACKEND_URL", config.get("backendBaseUrl", DEFAULT_BACKEND_URL))
    worker_url = os.environ.get("MATH_AGENT_BENCHMARK_WORKER_URL", config.get("workerBaseUrl", args.worker_url)).rstrip("/")
    username = os.environ.get("MATH_AGENT_BENCHMARK_ADMIN_USERNAME", config.get("adminUsername", DEFAULT_ADMIN_USERNAME)).strip()
    password = os.environ.get("MATH_AGENT_BENCHMARK_ADMIN_PASSWORD", config.get("adminPassword", DEFAULT_ADMIN_PASSWORD))
    if not username or not password:
        raise RuntimeError("missing benchmark credentials; set MATH_AGENT_BENCHMARK_ADMIN_USERNAME and MATH_AGENT_BENCHMARK_ADMIN_PASSWORD")
    worker_key = os.environ.get(WORKER_KEY_ENV, args.worker_api_key).strip()
    cases = _load_cases(Path(args.cases_json), args.library)
    output_dir = Path(args.output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)
    client = MathAgentClient(backend_url, timeout=args.timeout)
    client.login(username, password)

    runtime_before = _runtime_snapshot(worker_url, worker_key)
    rows: list[dict[str, Any]] = []
    modes = ("current_specified_library", "current_mixed") if args.mode == "both" else (args.mode,)
    for mode in modes:
        for case in cases:
            params: dict[str, Any] = {"query": case["query"], "limit": max(1, args.limit)}
            if mode == "current_specified_library" and case.get("requested_library"):
                params["library"] = case["requested_library"]
            attempt = client.get("/api/teacher/resources/search", params=params)
            body = attempt.body if isinstance(attempt.body, dict) else {}
            hits = [hit for hit in body.get("hits", []) if isinstance(hit, dict)]
            rows.append(_build_row(case, mode, params, attempt, body, hits))
            if args.request_delay_ms > 0:
                time.sleep(args.request_delay_ms / 1000.0)
    runtime_after = _runtime_snapshot(worker_url, worker_key)

    metrics = {
        "generatedAt": _now(),
        "sampleCount": sum(1 for row in rows if row["mode"] == modes[0]),
        "successfulCount": sum(1 for row in rows if row["mode"] == modes[0] and not row["request_error"]),
        "evaluationRule": {
            "positiveOnly": True,
            "documentMetric": "Physical FILE document id in top K; legacy ROOT-only hits are not physical-file evidence.",
            "blockMetric": "Exact current block id, or expected block inside a same-file hit evidenceBlockIds window, in top K; never mixed across split_group. Exact-only retained as exactBlockRecall.",
            "sameFileMetric": "A returned hit shares expected physical file identity; fail-closed when oracle or response has no FILE identity.",
            "evidenceWindowMetric": "Expected block id is present in a returned hit evidenceBlockIds window from the same physical file.",
            "historicalIds": "Excluded; a different parser split or database snapshot is not a valid A/B oracle.",
        },
        "dataset": {
            "caseFile": str(Path(args.cases_json).resolve()),
            "caseCount": len(cases),
            "caseCountByLibrary": _counts(cases, "expected_library"),
            "splitGroupCount": len({case["split_group"] for case in cases}),
        },
        "runtime": {
            "backendUrl": backend_url,
            "workerUrl": worker_url,
            "authenticatedAs": username,
            "requestLimit": max(1, args.limit),
            "before": runtime_before,
            "after": runtime_after,
        },
        "modes": {
            mode: _summarize_mode([row for row in rows if row["mode"] == mode])
            for mode in modes
        },
    }
    primary = metrics["modes"][modes[0]]
    primary_recall = primary["overallPositiveOnly"]
    metrics["documentRecall"] = {
        "doc@1": primary_recall["physicalFileRecallAt1"],
        "doc@3": primary_recall["physicalFileRecallAt3"],
        "doc@5": primary_recall["physicalFileRecallAt5"],
    }
    metrics["blockRecall"] = {
        "block@1": primary_recall["blockRecallAt1"],
        "block@3": primary_recall["blockRecallAt3"],
        "block@5": primary_recall["blockRecallAt5"],
        "exactBlock@1": primary_recall["exactBlockRecallAt1"],
        "exactBlock@3": primary_recall["exactBlockRecallAt3"],
        "exactBlock@5": primary_recall["exactBlockRecallAt5"],
    }
    metrics["latencyMs"] = primary["latencyMs"]
    metrics["candidateFunnel"] = primary["candidateFunnel"]
    metrics["memoryBoundary"] = _memory_boundary(runtime_before, runtime_after)
    metrics["sqlBoundedEvidence"] = {"status": "response_candidate_funnel", "bounded": primary["candidateFunnel"].get("sqlBoundedEvidence", False)}
    metrics["transportTelemetry"] = {
        "retryCount": sum(row["retryCount"] for row in rows),
        "rateLimit429Count": sum(row["rateLimit429Count"] for row in rows),
        "totalBackoffMs": sum(row["totalBackoffMs"] for row in rows),
    }
    metrics["gate"] = _gate(metrics)
    _write_required_artifacts(output_dir, metrics, rows)
    print(json.dumps(metrics, ensure_ascii=False, indent=2))


def _text(value: Any) -> str:
    return "" if value is None else str(value).strip()


def _build_row(
        case: dict[str, Any],
        mode: str,
        params: dict[str, Any],
        attempt: Any,
        body: dict[str, Any],
        hits: list[dict[str, Any]]) -> dict[str, Any]:
    expected_document_id = _text(case.get("expected_document_id"))
    expected_file_document_id = _text(case.get("expected_file_document_id"))
    expected_root_document_id = _text(case.get("expected_root_document_id") or expected_document_id)
    expected_block_id = _text(case.get("expected_block_id"))
    compact_hits = [_compact_hit(hit) for hit in hits]
    document_ids = [str(hit.get("documentId") or "") for hit in hits]
    physical_file_ids = [str(hit.get("fileDocumentId") or "") for hit in hits]
    exact_block_rank = _first_rank(
        [str(hit.get("blockId") or "") for hit in hits if expected_file_document_id and str(hit.get("fileDocumentId") or "") == expected_file_document_id],
        expected_block_id,
    ) if expected_file_document_id else None
    physical_file_rank = _first_rank(physical_file_ids, expected_file_document_id) if expected_file_document_id else None
    same_file_hits = [
        hit for hit in hits
        if expected_file_document_id
        and str(hit.get("fileDocumentId") or "") == expected_file_document_id
    ]
    evidence_window_hits = [
        hit for hit in same_file_hits
        if expected_block_id in [str(value) for value in (hit.get("evidenceBlockIds") or [])]
    ]
    # 20260830 口径放宽（老板指令）：block 命中 = 精确 blockId，或期望块出现在同文件返回
    # hit 的 evidenceBlockIds 证据窗口内。父块代表式返回时，代表块与期望块往往同窗不同 id，
    # exact-only 会系统性低估块级检索质量（提升rag/RAG升级计划.md 的权威口径第 3 条同此）。
    # exact-only 保留为 exactBlockRecall 诊断指标，不删。
    window_block_rank = None
    if expected_file_document_id and expected_block_id:
        for index, hit in enumerate(hits, start=1):
            if str(hit.get("fileDocumentId") or "") != expected_file_document_id:
                continue
            if str(hit.get("blockId") or "") == expected_block_id \
                    or expected_block_id in [str(value) for value in (hit.get("evidenceBlockIds") or [])]:
                window_block_rank = index
                break
    return {
        "case_id": case["case_id"],
        "mode": mode,
        "query": case["query"],
        "query_variant": case.get("query_variant", ""),
        "expected_library": case["expected_library"],
        "expected_document_id": expected_document_id,
        "expected_root_document_id": expected_root_document_id,
        "expected_file_document_id": expected_file_document_id,
        "expected_provider_item_id": _text(case.get("expected_provider_item_id")),
        "expected_source_path": _text(case.get("expected_source_path")),
        "expected_block_order": int(case.get("expected_block_order") or case.get("block_order") or 0),
        "expected_block_id": expected_block_id,
        "split_group": case["split_group"],
        "split_fingerprint": case["split_fingerprint"],
        "requested_library": params.get("library", ""),
        "query_id": str(body.get("queryId") or ""),
        "retrieval_mode": str(body.get("retrievalMode") or ""),
        "http_status": attempt.status,
        "request_error": not attempt.ok or not body.get("retrievalMode"),
        "latency_ms": float(attempt.elapsed_ms),
        "retryCount": attempt.retry_count,
        "rateLimit429Count": attempt.rate_limit_429_count,
        "totalBackoffMs": attempt.total_backoff_ms,

        "legacy_root_document_rank": _first_rank(document_ids, expected_root_document_id),
        "document_rank": physical_file_rank,
        "exact_block_rank": exact_block_rank,
        "window_block_rank": window_block_rank,
        "block_rank": window_block_rank,
        "physical_file_rank": physical_file_rank,
        "physical_file_hit": physical_file_rank is not None,
        "same_file_hit": bool(same_file_hits),
        "evidence_window_hit": bool(evidence_window_hits),
        "file_identity_complete": bool(expected_file_document_id) and all(
            bool(str(hit.get("fileDocumentId") or "")) for hit in hits
        ),
        "hit_count": len(hits),
        "top_hits": compact_hits,
        "candidate_funnel": _funnel_row(body.get("candidateFunnel"), hits),
    }


def _funnel_row(raw: Any, hits: list[dict[str, Any]]) -> dict[str, Any]:
    funnel = raw if isinstance(raw, dict) else {}
    return {
        "vectorFileDocumentIds": list(funnel.get("vectorFileDocumentIds") or []),
        "lexicalFileDocumentIds": list(funnel.get("lexicalFileDocumentIds") or []),
        "tagFileDocumentIds": list(funnel.get("tagFileDocumentIds") or []),
        "fusedFileDocumentIds": list(funnel.get("fusedFileDocumentIds") or []),
        "finalFileDocumentIds": list(funnel.get("finalFileDocumentIds") or []),
        "representativeBlockIds": list(funnel.get("representativeBlockIds") or []),
        "rerankCandidateCount": funnel.get("rerankCandidateCount"),
        "sqlBoundedEvidence": funnel.get("sqlBoundedEvidence"),
        "fusedFileScores": dict(funnel.get("fusedFileScores") or {}),
        "fileCandidates": list(funnel.get("fileCandidates") or []),
        "blockEvidence": list(funnel.get("blockEvidence") or []),
        "failureType": str(funnel.get("failureType") or "unknown"),
        "finalRanks": [
            {"rank": index, "fileDocumentId": hit.get("fileDocumentId"), "blockId": hit.get("blockId")}
            for index, hit in enumerate(hits, start=1)
        ],
        "exactBlock": any(hit.get("blockId") for hit in hits),
        "physicalFile": all(bool(hit.get("fileDocumentId")) for hit in hits) if hits else False,
        "evidenceWindow": all(isinstance(hit.get("evidenceBlockIds"), list) for hit in hits),
        "status": "complete" if raw and funnel.get("rerankCandidateCount") is not None else "incomplete",
    }


def _summarize_mode(rows: list[dict[str, Any]]) -> dict[str, Any]:
    successful = [row for row in rows if not row["request_error"]]
    grouped_library: dict[str, list[dict[str, Any]]] = defaultdict(list)
    grouped_split: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for row in successful:
        grouped_library[row["expected_library"]].append(row)
        grouped_split[row["split_group"]].append(row)
    return {
        "sampleCount": len(rows),
        "successfulCount": len(successful),
        "requestErrorCount": len(rows) - len(successful),
        "latencyMs": _latency([float(row["latency_ms"]) for row in successful]),
        "overallPositiveOnly": _recall(successful),
        "byLibrary": {key: _group_summary(group) for key, group in sorted(grouped_library.items())},
        "bySplitGroup": {key: _group_summary(group) for key, group in sorted(grouped_split.items())},
        "retrievalModes": _counts(successful, "retrieval_mode"),
        "candidateFunnel": _funnel_summary(successful),
    }


def _group_summary(rows: list[dict[str, Any]]) -> dict[str, Any]:
    return {"sampleCount": len(rows), "recall": _recall(rows), "latencyMs": _latency([float(row["latency_ms"]) for row in rows])}


def _recall(rows: list[dict[str, Any]]) -> dict[str, float]:
    result: dict[str, float] = {}
    denominator = len(rows)
    for cutoff in RECALL_CUTOFFS:
        result[f"documentRecallAt{cutoff}"] = _rate(rows, lambda row: row["physical_file_rank"] is not None and row["physical_file_rank"] <= cutoff, denominator)
        result[f"exactBlockRecallAt{cutoff}"] = _rate(rows, lambda row: row["exact_block_rank"] is not None and row["exact_block_rank"] <= cutoff, denominator)
        # 20260830：blockRecall 采用窗口口径（精确命中或证据窗口命中），exact-only 降级为诊断指标。
        result[f"blockRecallAt{cutoff}"] = _rate(rows, lambda row: row["window_block_rank"] is not None and row["window_block_rank"] <= cutoff, denominator)
        result[f"physicalFileRecallAt{cutoff}"] = _rate(rows, lambda row: row["physical_file_rank"] is not None and row["physical_file_rank"] <= cutoff, denominator)
        result[f"sameFileRecallAt{cutoff}"] = _rate(rows, lambda row: _rank_for_flag(row, "same_file_hit") is not None and _rank_for_flag(row, "same_file_hit") <= cutoff, denominator)
        result[f"evidenceWindowRecallAt{cutoff}"] = _rate(rows, lambda row: _rank_for_flag(row, "evidence_window_hit") is not None and _rank_for_flag(row, "evidence_window_hit") <= cutoff, denominator)
    result["exactBlockHitRate"] = _rate(rows, lambda row: row["exact_block_rank"] is not None, denominator)
    result["physicalFileHitRate"] = _rate(rows, lambda row: row["physical_file_hit"], denominator)
    result["sameFileHitRate"] = _rate(rows, lambda row: row["same_file_hit"], denominator)
    result["evidenceWindowHitRate"] = _rate(rows, lambda row: row["evidence_window_hit"], denominator)
    result["fileIdentityCompleteRate"] = _rate(rows, lambda row: row["file_identity_complete"], denominator)
    return result


def _rank_for_flag(row: dict[str, Any], flag: str) -> int | None:
    for index, hit in enumerate(row.get("top_hits", []), start=1):
        same_file = bool(row.get("expected_file_document_id")) and hit.get("fileDocumentId") == row.get("expected_file_document_id")
        if flag == "same_file_hit" and same_file:
            return index
        if flag == "evidence_window_hit" and same_file and row.get("expected_block_id") in (hit.get("evidenceBlockIds") or []):
            return index
    return None


def _funnel_summary(rows: list[dict[str, Any]]) -> dict[str, Any]:
    funnels = [row["candidate_funnel"] for row in rows]
    return {
        "vectorFileDocumentIds": sorted({value for funnel in funnels for value in funnel["vectorFileDocumentIds"]}),
        "lexicalFileDocumentIds": sorted({value for funnel in funnels for value in funnel["lexicalFileDocumentIds"]}),
        "tagFileDocumentIds": sorted({value for funnel in funnels for value in funnel["tagFileDocumentIds"]}),
        "fusedFileDocumentIds": sorted({value for funnel in funnels for value in funnel["fusedFileDocumentIds"]}),
        "finalFileDocumentIds": sorted({value for funnel in funnels for value in funnel["finalFileDocumentIds"]}),
        "representativeBlockIds": sorted({value for funnel in funnels for value in funnel["representativeBlockIds"]}),
        "rerankCandidateCount": max((funnel["rerankCandidateCount"] or 0 for funnel in funnels), default=0),
        "sqlBoundedEvidence": all(funnel["sqlBoundedEvidence"] is True for funnel in funnels),
        "exactBlock": all(funnel["exactBlock"] for funnel in funnels),
        "physicalFile": all(funnel["physicalFile"] for funnel in funnels),
        "evidenceWindow": all(funnel["evidenceWindow"] for funnel in funnels),
        "status": "complete" if funnels and all(funnel["status"] == "complete" for funnel in funnels) else "incomplete",
    }


def _rate(rows: list[dict[str, Any]], predicate, denominator: int) -> float:
    return round(sum(1 for row in rows if predicate(row)) / denominator, 6) if denominator else 0.0


def _latency(values: list[float]) -> dict[str, float]:
    if not values:
        return {"average": 0.0, "p50": 0.0, "p95": 0.0, "p99": 0.0}
    ordered = sorted(values)
    return {
        "average": round(statistics.fmean(ordered), 3),
        "p50": round(_nearest_rank(ordered, 0.50), 3),
        "p95": round(_nearest_rank(ordered, 0.95), 3),
        "p99": round(_nearest_rank(ordered, 0.99), 3),
    }


def _nearest_rank(values: list[float], fraction: float) -> float:
    index = max(0, min(len(values) - 1, int((len(values) * fraction + 0.999999999) - 1)))
    return values[index]


def _runtime_snapshot(worker_url: str, worker_key: str) -> dict[str, Any]:
    snapshot: dict[str, Any] = {"timestamp": _now(), "host": platform.platform()}
    try:
        result = subprocess.run(NVIDIA_QUERY, capture_output=True, text=True, check=True, timeout=20)
        snapshot["gpu"] = result.stdout.strip()
    except Exception as exc:
        snapshot["gpuError"] = f"{type(exc).__name__}: {exc}"
    snapshot["worker"] = _worker_snapshot(worker_url, worker_key)
    try:
        result = subprocess.run(
            ["wsl.exe", "-d", "Ubuntu", "--", "bash", "-lc", "docker compose ps --format '{{.Name}}|{{.Service}}|{{.State}}|{{.Health}}|{{.ID}}'"],
            capture_output=True, text=True, check=True, timeout=30,
        )
        containers = {}
        for line in result.stdout.splitlines():
            fields = line.strip().split("|", 4)
            if len(fields) == 5:
                containers[fields[1]] = {"name": fields[0], "state": fields[2], "health": fields[3], "id": fields[4]}
        snapshot["containers"] = containers
        snapshot["containerCount"] = len(containers)
    except Exception as exc:
        snapshot["containerStatsError"] = f"{type(exc).__name__}: {exc}"
    return snapshot


def _worker_snapshot(worker_url: str, worker_key: str) -> dict[str, Any]:
    if not worker_url:
        return {"status": "not_configured"}
    headers = {"Authorization": f"Bearer {worker_key}"} if worker_key else {}
    result: dict[str, Any] = {"url": worker_url, "health": None, "capabilities": None}
    for name, path in (("health", "/health"), ("capabilities", "/v1/capabilities")):
        try:
            response = requests.get(worker_url + path, headers=headers, timeout=20)
            result[name] = {"status": response.status_code, "body": response.json()}
        except Exception as exc:
            result[name] = {"error": f"{type(exc).__name__}: {exc}"}
    return result


def _memory_boundary(before: dict[str, Any], after: dict[str, Any]) -> dict[str, Any]:
    values: list[int] = []
    for snapshot in (before, after):
        gpu = str(snapshot.get("gpu") or "")
        for line in gpu.splitlines():
            fields = [field.strip() for field in line.split(",")]
            if len(fields) >= 3:
                try:
                    values.append(int(fields[2]) * 1024 * 1024)
                except ValueError:
                    pass
    return {"source": "nvidia-smi", "peakBytes": max(values) if values else None, "sampleCount": len(values)}


def _gate(metrics: dict[str, Any]) -> dict[str, Any]:
    docs = metrics["documentRecall"]
    blocks = metrics["blockRecall"]
    latency = metrics["latencyMs"]
    funnel = metrics["candidateFunnel"]
    checks = {
        "sampleCount": metrics["sampleCount"] >= 100,
        "successfulCount": metrics["successfulCount"] == metrics["sampleCount"],
        # 20260830 门槛按老板指令调整：doc@3 >= 0.80、block@3 >= 0.60（窗口口径）。
        "docAt1": docs["doc@1"] >= 0.70,
        "docAt3": docs["doc@3"] >= 0.80,
        "blockAt3": blocks["block@3"] >= 0.60,
        "p95Recorded": latency.get("p95", 0) > 0,
        "p99Recorded": latency.get("p99", 0) > 0,
        "memoryBoundary": metrics["memoryBoundary"].get("peakBytes") is not None,
        "sqlBoundedEvidence": bool(metrics["sqlBoundedEvidence"].get("bounded")),
        "candidateFunnel": funnel.get("status") == "complete" and funnel.get("rerankCandidateCount") is not None,
    }
    return {"passed": all(checks.values()), "checks": checks, "failedChecks": [name for name, passed in checks.items() if not passed]}


def _compact_hit(hit: dict[str, Any]) -> dict[str, Any]:
    return {key: hit.get(key) for key in (
        "documentId", "rootDocumentId", "fileDocumentId", "providerItemId", "splitFingerprint",
        "blockId", "blockOrder", "sourcePath", "sourceType", "blockRole", "evidenceBlockIds", "score",
    )}


def _first_rank(values: list[str], expected: str) -> int | None:
    for index, value in enumerate(values, start=1):
        if value == expected:
            return index
    return None


def _load_cases(path: Path, library: str = "") -> list[dict[str, Any]]:
    payload = json.loads(path.read_text(encoding="utf-8-sig"))
    cases = payload.get("cases") if isinstance(payload, dict) else payload
    if not isinstance(cases, list) or not cases:
        raise RuntimeError(f"Cases file must contain a non-empty list: {path}")
    all_positive = [case for case in cases if isinstance(case, dict) and case.get("case_type", "positive") == "positive"]
    if len(all_positive) != len(cases):
        raise RuntimeError("This evaluator accepts positive-only cases; negative cases are deliberately excluded from recall.")
    positive = [
        case for case in all_positive
        if not library or str(case.get("expected_library") or "").strip() == library.strip()
    ]
    required = (
        "case_id", "query", "expected_library", "expected_document_id", "expected_file_document_id",
        "expected_block_id", "split_group", "split_fingerprint",
    )
    for case in positive:
        missing = [field for field in required if not str(case.get(field) or "").strip()]
        if missing:
            raise RuntimeError(f"Positive case {case.get('case_id')} missing {missing}")
    if not positive:
        raise RuntimeError(f"No positive cases match library={library!r}")
    return positive


def _counts(rows: list[dict[str, Any]], field: str) -> dict[str, int]:
    result: dict[str, int] = defaultdict(int)
    for row in rows:
        result[str(row.get(field) or "unknown")] += 1
    return dict(sorted(result.items()))


def _load_config(path: Path | None) -> dict[str, Any]:
    if path is None or not path.exists():
        return {}
    payload = json.loads(path.read_text(encoding="utf-8-sig"))
    return payload if isinstance(payload, dict) else {}


def _write_required_artifacts(output_dir: Path, metrics: dict[str, Any], rows: list[dict[str, Any]]) -> None:
    output_dir.mkdir(parents=True, exist_ok=True)
    (output_dir / "config_snapshot.json").write_text(json.dumps(metrics["runtime"], ensure_ascii=True, indent=2) + "\n", encoding="utf-8")
    (output_dir / "results.jsonl").write_text("\n".join(json.dumps(row, ensure_ascii=True) for row in rows) + "\n", encoding="utf-8")
    (output_dir / "candidate-funnel.jsonl").write_text("\n".join(json.dumps({"case_id": row["case_id"], **row["candidate_funnel"]}, ensure_ascii=True) for row in rows) + "\n", encoding="utf-8")
    (output_dir / "metrics.json").write_text(json.dumps(metrics, ensure_ascii=True, indent=2) + "\n", encoding="utf-8")
    (output_dir / "summary.json").write_text(json.dumps({"generatedAt": metrics["generatedAt"], "documentRecall": metrics["documentRecall"], "blockRecall": metrics["blockRecall"], "latencyMs": metrics["latencyMs"], "transportTelemetry": metrics["transportTelemetry"]}, ensure_ascii=True, indent=2) + "\n", encoding="utf-8")
    (output_dir / "gate.json").write_text(json.dumps(metrics["gate"], ensure_ascii=True, indent=2) + "\n", encoding="utf-8")
    (output_dir / "runtime.json").write_text(json.dumps({"runtime": metrics["runtime"], "memoryBoundary": metrics["memoryBoundary"], "transportTelemetry": metrics["transportTelemetry"]}, ensure_ascii=True, indent=2) + "\n", encoding="utf-8")
    (output_dir / "boundary-results.json").write_text(json.dumps({"status": "run_separately", "sqlBoundedEvidence": metrics["sqlBoundedEvidence"]}, ensure_ascii=True, indent=2) + "\n", encoding="utf-8")
    (output_dir / "README.md").write_text(_markdown_summary(metrics) + "\n\nThis report was generated from the live HTTP backend and is retained even when gates fail.\n", encoding="utf-8")


def _markdown_summary(metrics: dict[str, Any]) -> str:
    lines = [
        "# 当前教师资料切分一致性评测",
        "",
        "本报告只统计当前真实库的正例。不同 library、document 和 parser split 不共享 block 指标；历史 documentId/blockId 不参与评分。",
        "",
        f"- 样本：{metrics['dataset']['caseCount']} 条；split group：{metrics['dataset']['splitGroupCount']} 个。",
        f"- 文档规则：{metrics['evaluationRule']['documentMetric']}",
        f"- block 规则：{metrics['evaluationRule']['blockMetric']}",
        "- 20260830：blockRecall 为窗口口径（精确块或同文件证据窗口命中），exactBlockRecall 另存于 metrics.json。",
        "",
        "| 模式 | library | 样本 | doc@1 | doc@3 | doc@5 | block@1 | block@3 | block@5 | avg/P95/P99 ms |",
        "|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|",
    ]
    for mode, mode_data in metrics["modes"].items():
        for library, group in mode_data["byLibrary"].items():
            recall = group["recall"]
            latency = group["latencyMs"]
            lines.append(
                f"| {mode} | {library} | {group['sampleCount']} | {recall['documentRecallAt1']:.3f} | {recall['documentRecallAt3']:.3f} | {recall['documentRecallAt5']:.3f} | "
                f"{recall['blockRecallAt1']:.3f} | {recall['blockRecallAt3']:.3f} | {recall['blockRecallAt5']:.3f} | "
                f"{latency['average']:.1f}/{latency['p95']:.1f}/{latency['p99']:.1f} |"
            )
    lines.extend(["", "资源快照已写入 `metrics.json` 和 `runtime.json`，GPU/容器值来自真实运行时采样。", ""])
    return "\n".join(lines)


def _now() -> str:
    return time.strftime("%Y-%m-%dT%H:%M:%S%z")


if __name__ == "__main__":
    main()
