"""Run a paired, real HTTP benchmark for the current Feishu/teacher production retriever.

The dataset is intentionally positive-only and hand authored.  A case is counted as a document hit only when the
returned source path contains the current case target.  This makes the report useful across parser re-splits while
keeping the exact request/response rows for audit.  The two main modes use the same live corpus and differ only in
the production change being evaluated: the old mixed caller leaves the library unscoped; the production caller
passes the explicit logical library before document and block ranking.
"""

from __future__ import annotations

import argparse
import json
import os
import statistics
import subprocess
import sys
import time
from collections import Counter, defaultdict
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

if __package__ in {None, ""}:
    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from benchmarks.http_client import MathAgentClient


DEFAULT_BACKEND_URL = "http://127.0.0.1:8080"
DEFAULT_ADMIN_USERNAME = "admin"
DEFAULT_ADMIN_PASSWORD = "admin-123456"
DEFAULT_REQUEST_TIMEOUT_SECONDS = 180.0
DEFAULT_SEARCH_LIMIT = 5
DEFAULT_IMAGE_LIMIT = 5
DEFAULT_RESOURCE_SAMPLE_INTERVAL = 10
DEFAULT_SESSION_REFRESH_INTERVAL_SECONDS = 120.0
SUPPORTED_MAIN_MODES = ("before_unscoped", "after_scoped")
ABLATION_MODES = ("after_scoped_limit1", "after_scoped_limit3", "after_scoped_limit5", "image_route")
RESOURCE_SERVICES = ("backend", "ai-worker", "milvus", "mysql", "redis")
RECALL_CUTOFFS = (1, 3, 5)


@dataclass(frozen=True)
class ResourceSample:
    """One real host/container resource observation taken after a request."""

    phase: str
    case_id: str
    timestamp_utc: str
    gpu: dict[str, Any]
    docker: dict[str, Any]


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset", required=True, type=Path)
    parser.add_argument("--output-dir", required=True, type=Path)
    parser.add_argument("--backend-url", default=os.environ.get("MATH_AGENT_BENCHMARK_BACKEND_URL", DEFAULT_BACKEND_URL))
    parser.add_argument("--admin-username", default=os.environ.get("MATH_AGENT_BENCHMARK_ADMIN_USERNAME", DEFAULT_ADMIN_USERNAME))
    parser.add_argument("--admin-password", default=os.environ.get("MATH_AGENT_BENCHMARK_ADMIN_PASSWORD", DEFAULT_ADMIN_PASSWORD))
    parser.add_argument("--timeout", type=float, default=DEFAULT_REQUEST_TIMEOUT_SECONDS)
    parser.add_argument("--limit", type=int, default=DEFAULT_SEARCH_LIMIT)
    parser.add_argument("--resource-sample-interval", type=int, default=DEFAULT_RESOURCE_SAMPLE_INTERVAL)
    parser.add_argument(
        "--session-refresh-interval-seconds",
        type=float,
        default=DEFAULT_SESSION_REFRESH_INTERVAL_SECONDS,
        help="Re-authenticate during long real-API runs before the backend session activity window expires.",
    )
    parser.add_argument("--include-ablation", action="store_true")
    parser.add_argument(
        "--modes",
        nargs="+",
        choices=SUPPORTED_MAIN_MODES + ABLATION_MODES,
        help="Run only the selected modes for a targeted rerun; the default runs the full six-mode matrix.",
    )
    parser.add_argument(
        "--request-interval-seconds",
        type=float,
        default=0.0,
        help="Pause between requests when exercising a production rate-limited route.",
    )
    parser.add_argument("--sync-summary", type=Path, default=None)
    args = parser.parse_args()

    dataset = _load_dataset(args.dataset)
    cases = dataset["cases"]
    output_dir = args.output_dir
    output_dir.mkdir(parents=True, exist_ok=True)
    client = MathAgentClient(args.backend_url, timeout=args.timeout, max_retries=10)
    login_body = client.login(args.admin_username, args.admin_password)

    modes = list(dict.fromkeys(args.modes or (
        list(SUPPORTED_MAIN_MODES) + (list(ABLATION_MODES) if args.include_ablation else [])
    )))
    # The selected mode list is carried into metrics so a targeted recovery run cannot be mistaken for a
    # complete production matrix when its files are inspected later.
    args.executed_modes = modes
    rows: list[dict[str, Any]] = []
    resources: list[ResourceSample] = []
    partial_rows_path = output_dir / "query_rows.partial.jsonl"
    partial_resources_path = output_dir / "resource_samples.partial.jsonl"
    partial_rows_path.write_text("", encoding="utf-8")
    partial_resources_path.write_text("", encoding="utf-8")
    sample_interval = max(1, int(args.resource_sample_interval))
    session_refresh_interval = max(0.0, float(args.session_refresh_interval_seconds))
    session_started = time.perf_counter()
    request_index = 0
    for mode in modes:
        for case in cases:
            # The production session activity window is shorter than the cookie expiry. Re-login stays in the
            # benchmark client so a long image-route run measures the endpoint rather than anonymous 403 responses.
            if request_index > 0 and session_refresh_interval > 0.0 \
                    and time.perf_counter() - session_started >= session_refresh_interval:
                login_body = client.login(args.admin_username, args.admin_password)
                session_started = time.perf_counter()
            if request_index > 0 and args.request_interval_seconds > 0:
                time.sleep(args.request_interval_seconds)
            row = _run_one(client, case, mode, args.limit)
            rows.append(row)
            with partial_rows_path.open("a", encoding="utf-8") as partial_rows:
                partial_rows.write(json.dumps(row, ensure_ascii=False) + "\n")
            # Sampling every Nth completed request keeps the resource series real while preventing thousands of
            # sequential Docker CLI calls from dominating wall time and introducing unrelated tail noise.
            if request_index % sample_interval == 0 or request_index == len(modes) * len(cases) - 1:
                resource = _sample_resources(mode, case["id"])
                resources.append(resource)
                with partial_resources_path.open("a", encoding="utf-8") as partial_resources:
                    partial_resources.write(json.dumps(resource.__dict__, ensure_ascii=False) + "\n")
            request_index += 1
            if request_index % sample_interval == 0:
                print(f"benchmark_progress completed={request_index}/{len(modes) * len(cases)} mode={mode}", flush=True)

    inventory = _read_live_inventory(client)
    metrics = _build_metrics(dataset, args, login_body, rows, resources, inventory)
    _write_outputs(output_dir, dataset, metrics, rows, resources, inventory, args.sync_summary)
    print(json.dumps(metrics, ensure_ascii=False, indent=2))


def _load_dataset(path: Path) -> dict[str, Any]:
    payload = json.loads(path.read_text(encoding="utf-8"))
    cases = payload.get("cases") if isinstance(payload, dict) else None
    if not isinstance(cases, list) or len(cases) != 100:
        raise ValueError(f"manual dataset must contain exactly 100 cases: {path}")
    ids = [str(case.get("id") or "") for case in cases]
    if any(not case_id for case_id in ids) or len(set(ids)) != len(ids):
        raise ValueError("manual dataset case ids must be non-empty and unique")
    if any(not str(case.get("expectedPath") or "").strip() for case in cases):
        raise ValueError("every positive case must have an expectedPath")
    return payload


def _run_one(client: MathAgentClient, case: dict[str, Any], mode: str, default_limit: int) -> dict[str, Any]:
    query = str(case["query"])
    params: dict[str, Any] = {"query": query, "limit": _mode_limit(mode, default_limit)}
    if mode == "after_scoped" or mode.startswith("after_scoped_limit"):
        params["library"] = case["library"]
    started = time.perf_counter()
    if mode == "image_route":
        attempt = client.post("/api/teacher/resources/image-search", {
            "query": query,
            "image": "",
            "limit": DEFAULT_IMAGE_LIMIT,
            "documentIds": [],
        })
    else:
        attempt = client.get("/api/teacher/resources/search", params=params)
    elapsed_ms = round((time.perf_counter() - started) * 1000, 3)
    body = attempt.body if isinstance(attempt.body, dict) else {}
    hits = [hit for hit in body.get("hits", []) if isinstance(hit, dict)]
    expected_path = _normalize_path(case["expectedPath"])
    ranks = [index + 1 for index, hit in enumerate(hits) if _hit_matches(hit, expected_path)]
    returned_documents = [str(hit.get("documentId") or "") for hit in hits]
    return {
        "caseId": case["id"],
        "library": case["library"],
        "query": query,
        "expectedPath": case["expectedPath"],
        "mode": mode,
        "params": params,
        "status": attempt.status,
        "ok": attempt.status == 200,
        "latencyMs": elapsed_ms,
        "queryId": str(body.get("queryId") or ""),
        "retrievalMode": str(body.get("retrievalMode") or body.get("collectionName") or ""),
        "hitCount": len(hits),
        "targetRanks": ranks,
        "documentRank": min(ranks) if ranks else None,
        "documentRecallAt": {str(cutoff): bool(ranks and min(ranks) <= cutoff) for cutoff in RECALL_CUTOFFS},
        "returnedDocumentCount": len(set(returned_documents)),
        "imageAssetRefCount": sum(len(hit.get("imageAssetIds") or []) for hit in hits),
        "topHits": [_compact_hit(hit) for hit in hits],
        "rawBody": body,
    }


def _mode_limit(mode: str, default_limit: int) -> int:
    if mode == "after_scoped_limit1":
        return 1
    if mode == "after_scoped_limit3":
        return 3
    if mode == "after_scoped_limit5":
        return 5
    return max(1, default_limit)


def _hit_matches(hit: dict[str, Any], expected_path: str) -> bool:
    source_path = _normalize_path(str(hit.get("sourcePath") or ""))
    title = _normalize_path(str(hit.get("documentTitle") or hit.get("title") or ""))
    return expected_path in source_path or expected_path in title


def _compact_hit(hit: dict[str, Any]) -> dict[str, Any]:
    keys = ("documentId", "documentTitle", "sourceType", "blockId", "sourcePath", "chapter", "section", "score", "distance", "assetId")
    return {key: hit.get(key) for key in keys if key in hit}


def _build_metrics(
        dataset: dict[str, Any],
        args: argparse.Namespace,
        login_body: dict[str, Any],
        rows: list[dict[str, Any]],
        resources: list[ResourceSample],
        inventory: dict[str, Any]) -> dict[str, Any]:
    mode_metrics = {mode: _summarize_mode(mode_rows) for mode, mode_rows in _group_rows(rows).items()}
    return {
        "generatedAt": _now(),
        "evaluation": {
            "positiveOnly": True,
            "negativeCaseCount": 0,
            "caseCount": len(dataset["cases"]),
            "documentMetric": "Top-K returned sourcePath/documentTitle contains the current hand-authored expectedPath.",
            "splitSafety": "No historical block id is compared; block metrics are intentionally omitted across parser splits.",
            "beforeVersion": "before_unscoped: same live endpoint without logical-library filter, representing the old mixed caller.",
            "afterVersion": "after_scoped: same live endpoint with library filter before document/block ranking.",
        },
        "runtime": {
            "backendUrl": args.backend_url,
            "authenticatedAs": login_body.get("username", args.admin_username),
            "requestLimit": max(1, args.limit),
            "requestIntervalSeconds": max(0.0, args.request_interval_seconds),
            "sessionRefreshIntervalSeconds": max(0.0, args.session_refresh_interval_seconds),
            "mainModes": list(SUPPORTED_MAIN_MODES),
            "ablationModes": list(ABLATION_MODES) if args.include_ablation else [],
            "executedModes": list(getattr(args, "executed_modes", SUPPORTED_MAIN_MODES)),
        },
        "dataset": {
            "path": str(args.dataset.resolve()),
            "version": dataset.get("datasetVersion", ""),
            "caseCountByLibrary": dict(sorted(Counter(case["library"] for case in dataset["cases"]).items())),
            "sourceUrls": dataset.get("sourceUrls", []),
        },
        "modes": mode_metrics,
        "inventory": inventory,
        "resourceSampling": _summarize_resources(resources),
    }


def _group_rows(rows: list[dict[str, Any]]) -> dict[str, list[dict[str, Any]]]:
    grouped: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for row in rows:
        grouped[row["mode"]].append(row)
    return dict(grouped)


def _summarize_mode(rows: list[dict[str, Any]]) -> dict[str, Any]:
    successful = [row for row in rows if row["ok"]]
    latency = [float(row["latencyMs"]) for row in successful]
    summary: dict[str, Any] = {
        "sampleCount": len(rows),
        "successfulCount": len(successful),
        "httpStatus": dict(sorted(Counter(str(row["status"]) for row in rows).items())),
        "latencyMs": _latency_summary(latency),
        "documentRecall": {str(cutoff): _rate(successful, lambda row, c=cutoff: row["documentRecallAt"][str(c)]) for cutoff in RECALL_CUTOFFS},
        "documentRecallByLibrary": {},
        "imageAssetRefs": {
            "avgPerRequest": round(sum(row["imageAssetRefCount"] for row in successful) / len(successful), 3) if successful else 0.0,
            "requestsWithRefs": sum(1 for row in successful if row["imageAssetRefCount"] > 0),
        },
    }
    by_library: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for row in successful:
        by_library[row["library"]].append(row)
    for library, library_rows in sorted(by_library.items()):
        summary["documentRecallByLibrary"][library] = {
            "sampleCount": len(library_rows),
            **{f"doc@{cutoff}": _rate(library_rows, lambda row, c=cutoff: row["documentRecallAt"][str(c)]) for cutoff in RECALL_CUTOFFS},
            "latencyMs": _latency_summary([float(row["latencyMs"]) for row in library_rows]),
        }
    return summary


def _rate(rows: list[dict[str, Any]], predicate) -> float:
    return round(sum(1 for row in rows if predicate(row)) / len(rows), 4) if rows else 0.0


def _latency_summary(values: list[float]) -> dict[str, float]:
    if not values:
        return {key: 0.0 for key in ("avg", "p50", "p95", "p99", "min", "max")}
    ordered = sorted(values)
    return {
        "avg": round(statistics.fmean(ordered), 3),
        "p50": round(_percentile(ordered, 0.50), 3),
        "p95": round(_percentile(ordered, 0.95), 3),
        "p99": round(_percentile(ordered, 0.99), 3),
        "min": round(ordered[0], 3),
        "max": round(ordered[-1], 3),
    }


def _percentile(values: list[float], fraction: float) -> float:
    if len(values) == 1:
        return values[0]
    position = (len(values) - 1) * fraction
    lower = int(position)
    upper = min(lower + 1, len(values) - 1)
    weight = position - lower
    return values[lower] + (values[upper] - values[lower]) * weight


def _read_live_inventory(client: MathAgentClient) -> dict[str, Any]:
    """Read current production counts through authenticated APIs, never from the forbidden host mirror."""
    resources_attempt = client.get("/api/teacher/resources")
    if resources_attempt.status != 200 or not isinstance(resources_attempt.body, list):
        return {"status": resources_attempt.status, "error": str(resources_attempt.body)}
    documents: list[dict[str, Any]] = []
    for resource in resources_attempt.body:
        if not isinstance(resource, dict):
            continue
        document_id = str(resource.get("documentId") or "")
        if not document_id:
            continue
        blocks_attempt = client.get(f"/api/teacher/resources/{document_id}/blocks")
        assets_attempt = client.get(f"/api/teacher/resources/{document_id}/assets")
        blocks = blocks_attempt.body if blocks_attempt.status == 200 and isinstance(blocks_attempt.body, list) else []
        assets = assets_attempt.body if assets_attempt.status == 200 and isinstance(assets_attempt.body, list) else []
        documents.append({
            "documentId": document_id,
            "sourceType": resource.get("sourceType"),
            "title": resource.get("title"),
            "originalUrl": resource.get("originalUrl"),
            "syncStatus": resource.get("syncStatus"),
            "parseStatus": resource.get("parseStatus"),
            "embeddingStatus": resource.get("embeddingStatus"),
            "indexStatus": resource.get("indexStatus"),
            "blockCount": len(blocks),
            "assetCount": len(assets),
            "blocksHttpStatus": blocks_attempt.status,
            "assetsHttpStatus": assets_attempt.status,
        })
    vector_status = client.get("/api/vector-index/status")
    return {
        "resourceCount": len(documents),
        "documents": documents,
        "textVectorStatus": vector_status.body if vector_status.status == 200 else {"status": vector_status.status},
    }


def _sample_resources(phase: str, case_id: str) -> ResourceSample:
    """Collect actual NVIDIA and Docker counters without starting or emulating any model process."""
    return ResourceSample(
        phase=phase,
        case_id=case_id,
        timestamp_utc=_now(),
        gpu=_nvidia_snapshot(),
        docker=_docker_snapshot(),
    )


def _nvidia_snapshot() -> dict[str, Any]:
    command = ["nvidia-smi", "--query-gpu=name,utilization.gpu,memory.used,memory.total", "--format=csv,noheader,nounits"]
    try:
        completed = subprocess.run(command, capture_output=True, text=True, timeout=5, check=False)
        rows = []
        for line in completed.stdout.splitlines():
            values = [value.strip() for value in line.split(",")]
            if len(values) == 4:
                rows.append({"name": values[0], "utilizationGpuPercent": _number(values[1]), "memoryUsedMiB": _number(values[2]), "memoryTotalMiB": _number(values[3])})
        return {"available": completed.returncode == 0, "gpus": rows, "stderr": completed.stderr.strip()[:300]}
    except (OSError, subprocess.SubprocessError) as exception:
        return {"available": False, "gpus": [], "error": str(exception)}


def _docker_snapshot() -> dict[str, Any]:
    result: dict[str, Any] = {}
    for service in RESOURCE_SERVICES:
        try:
            completed = subprocess.run(
                ["docker", "stats", "--no-stream", "--format", "{{.CPUPerc}}\\t{{.MemUsage}}", f"math-agent-rag-{service}-1"],
                capture_output=True, text=True, timeout=8, check=False)
            line = completed.stdout.strip().splitlines()[0] if completed.stdout.strip() else ""
            result[service] = {"raw": line, "available": completed.returncode == 0}
        except (OSError, subprocess.SubprocessError) as exception:
            result[service] = {"available": False, "error": str(exception)}
    return result


def _summarize_resources(samples: list[ResourceSample]) -> dict[str, Any]:
    gpu_util = []
    gpu_memory = []
    docker_counts: Counter[str] = Counter()
    for sample in samples:
        for gpu in sample.gpu.get("gpus", []):
            if isinstance(gpu.get("utilizationGpuPercent"), (int, float)):
                gpu_util.append(float(gpu["utilizationGpuPercent"]))
            if isinstance(gpu.get("memoryUsedMiB"), (int, float)):
                gpu_memory.append(float(gpu["memoryUsedMiB"]))
        for service, value in sample.docker.items():
            if value.get("available"):
                docker_counts[service] += 1
    return {
        "sampleCount": len(samples),
        "gpu": {
            "availableSamples": len(gpu_util),
            "avgUtilizationGpuPercent": round(statistics.fmean(gpu_util), 3) if gpu_util else None,
            "peakUtilizationGpuPercent": max(gpu_util) if gpu_util else None,
            "avgMemoryUsedMiB": round(statistics.fmean(gpu_memory), 3) if gpu_memory else None,
            "peakMemoryUsedMiB": max(gpu_memory) if gpu_memory else None,
        },
        "dockerStatsAvailableSamples": dict(sorted(docker_counts.items())),
    }


def _write_outputs(
        output_dir: Path,
        dataset: dict[str, Any],
        metrics: dict[str, Any],
        rows: list[dict[str, Any]],
        resources: list[ResourceSample],
        inventory: dict[str, Any],
        sync_summary_path: Path | None) -> None:
    (output_dir / "dataset_snapshot.json").write_text(json.dumps(dataset, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (output_dir / "metrics.json").write_text(json.dumps(metrics, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (output_dir / "corpus_inventory.json").write_text(json.dumps(inventory, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (output_dir / "query_rows.jsonl").write_text("\n".join(json.dumps(row, ensure_ascii=False) for row in rows) + "\n", encoding="utf-8")
    (output_dir / "resource_samples.jsonl").write_text("\n".join(json.dumps(sample.__dict__, ensure_ascii=False) for sample in resources) + "\n", encoding="utf-8")
    if sync_summary_path and sync_summary_path.exists():
        summary = json.loads(sync_summary_path.read_text(encoding="utf-8"))
        sanitized = {"resource_type": summary.get("resource_type"), "token": summary.get("token"), "url": summary.get("url"), "folder_name": summary.get("folder_name"), "stats": summary.get("stats"), "elapsed_ms": summary.get("elapsed_ms"), "failed_count": len(summary.get("failed_items") or [])}
        (output_dir / "feishu_sync_evidence.json").write_text(json.dumps(sanitized, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (output_dir / "summary.md").write_text(_markdown_summary(metrics), encoding="utf-8")


def _markdown_summary(metrics: dict[str, Any]) -> str:
    lines = [
        "# Feishu 与教师资料生产检索基准（人工正例 100 条）",
        "",
        "本报告只使用当前生产 API 和当前数据库切分；没有负例，也没有跨切分 block 分数。",
        "",
        "## 版本对比",
        "",
        "| 版本/模式 | avg ms | P95 ms | P99 ms | doc@1 | doc@3 | doc@5 | 成功请求 |",
        "|---|---:|---:|---:|---:|---:|---:|---:|",
    ]
    for mode, summary in metrics["modes"].items():
        latency = summary["latencyMs"]
        recall = summary["documentRecall"]
        lines.append(f"| {mode} | {latency['avg']:.3f} | {latency['p95']:.3f} | {latency['p99']:.3f} | {recall['1']:.4f} | {recall['3']:.4f} | {recall['5']:.4f} | {summary['successfulCount']}/{summary['sampleCount']} |")
    lines.extend(["", "## 按资料库分桶", "", "所有库的分桶结果保存在 `metrics.json`；不同资料库不合并 block 指标。", "", "## 资源采样", "", f"```json\n{json.dumps(metrics['resourceSampling'], ensure_ascii=False, indent=2)}\n```", ""])
    return "\n".join(lines)


def _normalize_path(value: str) -> str:
    return " ".join(value.replace("\\", "/").casefold().split())


def _number(value: str) -> float | None:
    try:
        return float(value.strip().replace("%", ""))
    except (TypeError, ValueError):
        return None


def _now() -> str:
    return datetime.now(timezone.utc).isoformat()


if __name__ == "__main__":
    main()
