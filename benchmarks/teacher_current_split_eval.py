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
import statistics
import subprocess
import sys
import time
from collections import defaultdict
from pathlib import Path
from typing import Any

if __package__ in {None, ""}:
    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from benchmarks.http_client import MathAgentClient


DEFAULT_BACKEND_URL = "http://127.0.0.1:8080"
DEFAULT_ADMIN_USERNAME = "admin"
DEFAULT_ADMIN_PASSWORD = "admin-123456"
DEFAULT_LIMIT = 5
RECALL_CUTOFFS = (1, 3, 5)
NVIDIA_QUERY = ["nvidia-smi", "--query-gpu=name,utilization.gpu,memory.used,memory.total", "--format=csv,noheader,nounits"]
DOCKER_SERVICES = ("backend", "ai-worker", "milvus", "mysql", "redis")


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
    args = parser.parse_args()

    config = _load_config(Path(args.config) if args.config else None)
    backend_url = os.environ.get("MATH_AGENT_BENCHMARK_BACKEND_URL", config.get("backendBaseUrl", DEFAULT_BACKEND_URL))
    username = os.environ.get("MATH_AGENT_BENCHMARK_ADMIN_USERNAME", config.get("adminUsername", DEFAULT_ADMIN_USERNAME))
    password = os.environ.get("MATH_AGENT_BENCHMARK_ADMIN_PASSWORD", config.get("adminPassword", DEFAULT_ADMIN_PASSWORD))
    cases = _load_cases(Path(args.cases_json), args.library)
    output_dir = Path(args.output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)
    client = MathAgentClient(backend_url, timeout=args.timeout)
    client.login(username, password)

    runtime_before = _runtime_snapshot()
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
            document_ids = [str(hit.get("documentId") or "") for hit in hits]
            block_ids = [str(hit.get("blockId") or "") for hit in hits]
            rows.append({
                "case_id": case["case_id"],
                "mode": mode,
                "query": case["query"],
                "query_variant": case.get("query_variant", ""),
                "expected_library": case["expected_library"],
                "expected_document_id": case["expected_document_id"],
                "expected_block_id": case["expected_block_id"],
                "split_group": case["split_group"],
                "split_fingerprint": case["split_fingerprint"],
                "requested_library": params.get("library", ""),
                "query_id": str(body.get("queryId") or ""),
                "retrieval_mode": str(body.get("retrievalMode") or ""),
                "http_status": attempt.status,
                "request_error": not attempt.ok or not body.get("retrievalMode"),
                "latency_ms": float(attempt.elapsed_ms),
                "document_rank": _first_rank(document_ids, case["expected_document_id"]),
                "block_rank": _first_rank(block_ids, case["expected_block_id"]),
                "hit_count": len(hits),
                "top_hits": [_compact_hit(hit) for hit in hits],
            })
            if args.request_delay_ms > 0:
                time.sleep(args.request_delay_ms / 1000.0)
    runtime_after = _runtime_snapshot()

    metrics = {
        "generatedAt": _now(),
        "evaluationRule": {
            "positiveOnly": True,
            "documentMetric": "Target document id in top K, reported by expected library and current split group.",
            "blockMetric": "Exact current block id in top K, never mixed across split_group.",
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
    (output_dir / "config_snapshot.json").write_text(json.dumps({"args": vars(args), "runtime": metrics["runtime"]}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (output_dir / "query_rows.jsonl").write_text("\n".join(json.dumps(row, ensure_ascii=False) for row in rows) + "\n", encoding="utf-8")
    (output_dir / "metrics.json").write_text(json.dumps(metrics, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (output_dir / "summary.md").write_text(_markdown_summary(metrics), encoding="utf-8")
    print(json.dumps(metrics, ensure_ascii=False, indent=2))


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
    }


def _group_summary(rows: list[dict[str, Any]]) -> dict[str, Any]:
    return {"sampleCount": len(rows), "recall": _recall(rows), "latencyMs": _latency([float(row["latency_ms"]) for row in rows])}


def _recall(rows: list[dict[str, Any]]) -> dict[str, float]:
    result: dict[str, float] = {}
    denominator = len(rows)
    for cutoff in RECALL_CUTOFFS:
        result[f"documentRecallAt{cutoff}"] = _rate(rows, lambda row: row["document_rank"] is not None and row["document_rank"] <= cutoff, denominator)
        result[f"blockRecallAt{cutoff}"] = _rate(rows, lambda row: row["block_rank"] is not None and row["block_rank"] <= cutoff, denominator)
    return result


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


def _runtime_snapshot() -> dict[str, Any]:
    snapshot: dict[str, Any] = {"timestamp": _now()}
    try:
        result = subprocess.run(NVIDIA_QUERY, capture_output=True, text=True, check=True, timeout=20)
        snapshot["gpu"] = result.stdout.strip()
    except Exception as exc:
        snapshot["gpuError"] = f"{type(exc).__name__}: {exc}"
    try:
        result = subprocess.run(
            ["docker", "stats", "--no-stream", "--format", "{{.Name}}|{{.CPUPerc}}|{{.MemUsage}}|{{.MemPerc}}"],
            capture_output=True, text=True, check=True, timeout=30,
        )
        stats = {}
        for line in result.stdout.splitlines():
            fields = line.split("|", 3)
            if len(fields) == 4 and any(service in fields[0] for service in DOCKER_SERVICES):
                stats[fields[0]] = {"cpu": fields[1], "memory": fields[2], "memoryPercent": fields[3]}
        snapshot["containers"] = stats
    except Exception as exc:
        snapshot["containerStatsError"] = f"{type(exc).__name__}: {exc}"
    return snapshot


def _compact_hit(hit: dict[str, Any]) -> dict[str, Any]:
    return {key: hit.get(key) for key in ("documentId", "blockId", "sourceType", "blockRole", "score")}


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
    required = ("case_id", "query", "expected_library", "expected_document_id", "expected_block_id", "split_group", "split_fingerprint")
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


def _markdown_summary(metrics: dict[str, Any]) -> str:
    lines = [
        "# 当前教师资料切分一致性评测",
        "",
        "本报告只统计当前真实库的正例。不同 library、document 和 parser split 不共享 block 指标；历史 documentId/blockId 不参与评分。",
        "",
        f"- 样本：{metrics['dataset']['caseCount']} 条；split group：{metrics['dataset']['splitGroupCount']} 个。",
        f"- 文档规则：{metrics['evaluationRule']['documentMetric']}",
        f"- block 规则：{metrics['evaluationRule']['blockMetric']}",
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
    lines.extend(["", "资源快照已写入 `metrics.json` 和 `config_snapshot.json`，GPU/容器值来自真实运行时采样。", ""])
    return "\n".join(lines)


def _now() -> str:
    return time.strftime("%Y-%m-%dT%H:%M:%S%z")


if __name__ == "__main__":
    main()
