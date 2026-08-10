"""Evaluate the live textbook HTTP endpoint against the fixed 46-source set.

This runner deliberately does no local retrieval.  Every row is one real POST
request to the running Java backend, and every metric is computed only from the
returned JSON.  The expected document/page/section identities are persisted in
the evaluation set before the run, so changing the retrieval code cannot alter
what counts as a hit.
"""

from __future__ import annotations

import argparse
import json
import math
import os
import re
import statistics
import subprocess
import sys
import threading
import time
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Any

import requests


DEFAULT_CASES = Path("output/benchmarks/textbook-page-section-ablation-final/section_cases.json")
DEFAULT_OUTPUT_ROOT = Path("output/benchmarks")
DEFAULT_BACKEND_URL = "http://127.0.0.1:8080"
# This evaluator exercises the same c2 section-child contract used by production. It is not imported by the backend;
# the live service receives the same c2 root through the Compose mount and application.yml.
DEFAULT_CORPUS_ROOT = Path(os.environ.get(
    "MATH_AGENT_PROCESSED_BOOKS_ROOT",
    r"C:\Users\doob\Desktop\个人资料\高中数学\下载课本代码\tchMaterial-parser-main\tchMaterial-parser-main\processed_books_section_shadow_all_mini_c2",
))
DEFAULT_LIMIT = 10
EXPECTED_CASE_COUNT = 46
DEFAULT_TIMEOUT_SECONDS = 45
# Production acceptance reports expose only the requested cutoffs. The HTTP limit may remain ten so the
# rank calculation has enough evidence, but higher-cutoff recall is deliberately not emitted as an acceptance metric.
METRIC_CUTOFFS = (1, 3, 5)
TARGET_DOCUMENT_AT_1 = 0.80
TARGET_DOCUMENT_AT_3 = 0.90
TARGET_BLOCK_AT_1 = 0.70
TARGET_BLOCK_AT_3 = 0.85
DOCKER_STATS_TIMEOUT_SECONDS = 8
GPU_STATS_TIMEOUT_SECONDS = 5
MEMORY_UNIT_MULTIPLIERS = {
    "b": 1,
    "kb": 1000,
    "kib": 1024,
    "mb": 1000**2,
    "mib": 1024**2,
    "gb": 1000**3,
    "gib": 1024**3,
    "tb": 1000**4,
    "tib": 1024**4,
}


@dataclass(frozen=True)
class ExpectedEvidence:
    """Immutable source identity used to judge one backend response."""

    case_id: str
    query: str
    doc_id: str
    page_nos: frozenset[int]
    section_id: str
    section_title: str


def read_json(path: Path) -> Any:
    """Read persisted evaluation inputs as UTF-8; never derive labels at runtime."""
    return json.loads(path.read_text(encoding="utf-8"))


def write_json(path: Path, value: Any) -> None:
    """Write reproducible machine-readable evidence without dropping Chinese text."""
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def source_page_nos(source: dict[str, Any]) -> frozenset[int]:
    """Preserve a cross-page section's complete source-page set when available."""
    values = source.get("source_page_nos") or source.get("page_nos") or [source.get("page_no") or source.get("pageNo")]
    pages: set[int] = set()
    for value in values:
        try:
            pages.add(int(value))
        except (TypeError, ValueError):
            continue
    if not pages:
        raise ValueError(f"source lacks a usable page number: {source}")
    return frozenset(pages)


def load_cases(path: Path, expected_count: int) -> list[ExpectedEvidence]:
    """Validate that the fixed set contains only section-labelled real textbook rows."""
    raw_cases = read_json(path)
    if not isinstance(raw_cases, list) or (expected_count > 0 and len(raw_cases) != expected_count):
        raise ValueError(f"expected exactly {expected_count} persisted cases, got {len(raw_cases) if isinstance(raw_cases, list) else 'non-list'}")
    cases: list[ExpectedEvidence] = []
    case_ids: set[str] = set()
    for row in raw_cases:
        if not isinstance(row, dict) or row.get("polarity", "positive") != "positive":
            continue
        # The legacy 46-case fixture stores labels under source; the existing independent 110-case builder stores
        # exactly the same immutable identities under expected. Supporting both lets the real backend runner cover
        # the complete generated corpus instead of silently falling back to a five-query smoke check.
        source = row.get("source") or row.get("expected")
        if not isinstance(source, dict):
            raise ValueError(f"case has no persisted source: {row}")
        case_id = str(row.get("caseId") or row.get("case_id") or "").strip()
        query = str(row.get("query") or "").strip()
        doc_id = str(source.get("doc_id") or source.get("docId") or "").strip()
        section_id = str(source.get("section_id") or source.get("sectionId") or source.get("blockId") or "").strip()
        if not case_id or case_id in case_ids or not query or not doc_id or not section_id:
            raise ValueError(f"case requires unique id, query, doc_id and section_id: {row}")
        case_ids.add(case_id)
        cases.append(ExpectedEvidence(
            case_id=case_id,
            query=query,
            doc_id=doc_id,
            page_nos=source_page_nos(source),
            section_id=section_id,
            section_title=str(source.get("section_title") or source.get("sectionTitle") or ""),
        ))
    return cases


def library_search_payload(query: str, limit: int) -> dict[str, Any]:
    """Build the only payload allowed in the full-library benchmark.

    Expected document, page, and small-heading identities belong to the scoring
    oracle only. They must never cross this boundary into an HTTP request,
    otherwise the benchmark would evaluate a narrowed search rather than the
    configured textbook library.
    """
    return {"query": query, "limit": limit}


def post_search(
    session: requests.Session,
    endpoint: str,
    query: str,
    limit: int,
    timeout_seconds: int,
) -> tuple[int, dict[str, Any], float, dict[str, Any]]:
    """Issue one real full-library request without any document or page scope."""
    request_payload = library_search_payload(query, limit)
    started = time.perf_counter()
    response = session.post(endpoint, json=request_payload, timeout=timeout_seconds)
    elapsed_ms = (time.perf_counter() - started) * 1000
    try:
        response_body = response.json()
    except ValueError:
        response_body = {"nonJsonBody": response.text}
    response_payload = response_body if isinstance(response_body, dict) else {"body": response_body}
    return response.status_code, response_payload, elapsed_ms, request_payload


def first_distinct_document_rank(hits: list[dict[str, Any]], expected_doc_id: str) -> int | None:
    """Rank textbooks by unique document identity, not by repeated sibling blocks."""
    seen: set[str] = set()
    rank = 0
    for hit in hits:
        doc_id = str(hit.get("docId") or "")
        if not doc_id or doc_id in seen:
            continue
        seen.add(doc_id)
        rank += 1
        if doc_id == expected_doc_id:
            return rank
    return None


def first_page_rank(hits: list[dict[str, Any]], expected: ExpectedEvidence) -> int | None:
    """Keep raw response order for page recall because each result is an evidence block."""
    for rank, hit in enumerate(hits, 1):
        if str(hit.get("docId") or "") != expected.doc_id:
            continue
        try:
            page_no = int(hit.get("pageNo"))
        except (TypeError, ValueError):
            continue
        if page_no in expected.page_nos:
            return rank
    return None


def compact_section_title(value: Any) -> str:
    """Remove OCR spacing and only a CJK-attached printed-page suffix from a heading."""
    compacted = re.sub(r"\s+", "", str(value or "")).lower()
    return re.sub(r"(?<=[\u4e00-\u9fff])\d{1,3}$", "", compacted)


def first_block_rank(hits: list[dict[str, Any]], expected: ExpectedEvidence) -> int | None:
    """Match the visible small heading within its textbook, including continuation pages.

    The c2 corpus currently has duplicate legacy section IDs for some one-page
    headings, so ID equality is not a trustworthy definition of the user-facing
    block.  The visible heading is what the splitting contract exposes. Page
    correctness stays a separate metric and retains its strict source-page rule.
    """
    expected_title = compact_section_title(expected.section_title)
    for rank, hit in enumerate(hits, 1):
        if str(hit.get("docId") or "") == expected.doc_id and compact_section_title(hit.get("sectionTitle")) == expected_title:
            return rank
    return None


def at_cutoff(rank: int | None, cutoff: int) -> bool:
    """Avoid treating a missing identity as rank zero."""
    return rank is not None and rank <= cutoff


def percentile(values: list[float], fraction: float) -> float | None:
    """Return a deterministic nearest-rank percentile for auditable small benchmark sets."""
    if not values:
        return None
    ordered = sorted(values)
    index = max(0, min(len(ordered) - 1, math.ceil(len(ordered) * fraction) - 1))
    return round(ordered[index], 3)


def parse_memory_quantity(value: str) -> float | None:
    """Convert Docker's human-readable memory quantity to bytes for aggregation.

    Docker may emit binary units such as MiB/GiB or decimal units such as MB/GB.
    Keeping the original display string and adding normalized bytes makes the
    saved report both operator-readable and numerically comparable.
    """
    match = re.fullmatch(r"\s*([0-9]+(?:\.[0-9]+)?)\s*([kmgt]?i?b)\s*", value, re.IGNORECASE)
    if not match:
        return None
    number, unit = match.groups()
    multiplier = MEMORY_UNIT_MULTIPLIERS.get(unit.lower())
    return float(number) * multiplier if multiplier is not None else None


def collect_resource_sample() -> dict[str, Any]:
    """Capture real GPU and Docker resource state without changing service configuration."""
    sample: dict[str, Any] = {"timestamp": datetime.now().isoformat(timespec="milliseconds"), "gpu": None, "containers": []}
    try:
        completed = subprocess.run(
            ["nvidia-smi", "--query-gpu=name,utilization.gpu,memory.used,memory.total", "--format=csv,noheader,nounits"],
            capture_output=True,
            text=True,
            timeout=GPU_STATS_TIMEOUT_SECONDS,
            check=True,
        )
        values = [part.strip() for part in completed.stdout.strip().splitlines()[0].split(",")]
        if len(values) == 4:
            sample["gpu"] = {
                "name": values[0],
                "utilizationPercent": float(values[1]),
                "memoryUsedMb": float(values[2]),
                "memoryTotalMb": float(values[3]),
            }
    except (OSError, subprocess.SubprocessError, ValueError, IndexError):
        pass
    try:
        completed = subprocess.run(
            # Use a real tab delimiter so each Docker stats row can be parsed
            # into name, CPU and memory without depending on column spacing.
            ["docker", "stats", "--no-stream", "--format", "{{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}"],
            capture_output=True,
            text=True,
            timeout=DOCKER_STATS_TIMEOUT_SECONDS,
            check=True,
        )
        for line in completed.stdout.splitlines():
            parts = line.split("\t", 2)
            if len(parts) == 3:
                cpu = parts[1].strip().rstrip("%")
                try:
                    cpu_percent = float(cpu)
                except ValueError:
                    cpu_percent = None
                memory_display = parts[2].strip()
                memory_used_bytes = None
                memory_limit_bytes = None
                memory_parts = [part.strip() for part in memory_display.split("/", 1)]
                if len(memory_parts) == 2:
                    memory_used_bytes = parse_memory_quantity(memory_parts[0])
                    memory_limit_bytes = parse_memory_quantity(memory_parts[1])
                sample["containers"].append({
                    "name": parts[0].strip(),
                    "cpuPercent": cpu_percent,
                    "memory": memory_display,
                    "memoryUsedBytes": memory_used_bytes,
                    "memoryLimitBytes": memory_limit_bytes,
                })
    except (OSError, subprocess.SubprocessError):
        pass
    return sample


def resource_sampler(stop_event: threading.Event, samples: list[dict[str, Any]], interval_seconds: float) -> None:
    """Sample while real requests are in flight so GPU and container usage is not a post-hoc guess."""
    while not stop_event.is_set():
        samples.append(collect_resource_sample())
        stop_event.wait(interval_seconds)


def summarize_resources(samples: list[dict[str, Any]]) -> dict[str, Any]:
    """Aggregate raw samples while retaining the complete sample stream in report.json."""
    gpu_rows = [sample["gpu"] for sample in samples if sample.get("gpu")]
    container_rows: dict[str, list[dict[str, Any]]] = {}
    for sample in samples:
        for row in sample.get("containers", []):
            container_rows.setdefault(str(row.get("name") or ""), []).append(row)
    return {
        "sampleCount": len(samples),
        "gpu": {
            "sampleCount": len(gpu_rows),
            "utilizationAvgPercent": round(statistics.fmean(row["utilizationPercent"] for row in gpu_rows), 2) if gpu_rows else None,
            "utilizationMaxPercent": round(max(row["utilizationPercent"] for row in gpu_rows), 2) if gpu_rows else None,
            "memoryUsedMaxMb": round(max(row["memoryUsedMb"] for row in gpu_rows), 2) if gpu_rows else None,
            "memoryTotalMb": round(max(row["memoryTotalMb"] for row in gpu_rows), 2) if gpu_rows else None,
            "name": gpu_rows[0]["name"] if gpu_rows else None,
        },
        "containers": {
            name: {
                "sampleCount": len(rows),
                "cpuAvgPercent": round(statistics.fmean(row["cpuPercent"] for row in rows if row.get("cpuPercent") is not None), 2)
                if any(row.get("cpuPercent") is not None for row in rows) else None,
                "cpuMaxPercent": round(max(row["cpuPercent"] for row in rows if row.get("cpuPercent") is not None), 2)
                if any(row.get("cpuPercent") is not None for row in rows) else None,
                "memoryUsedAvgMb": round(
                    statistics.fmean(row["memoryUsedBytes"] for row in rows if row.get("memoryUsedBytes") is not None) / 1024**2,
                    2,
                ) if any(row.get("memoryUsedBytes") is not None for row in rows) else None,
                "memoryUsedMaxMb": round(
                    max(row["memoryUsedBytes"] for row in rows if row.get("memoryUsedBytes") is not None) / 1024**2,
                    2,
                ) if any(row.get("memoryUsedBytes") is not None for row in rows) else None,
                "memoryLimitMaxMb": round(
                    max(row["memoryLimitBytes"] for row in rows if row.get("memoryLimitBytes") is not None) / 1024**2,
                    2,
                ) if any(row.get("memoryLimitBytes") is not None for row in rows) else None,
            }
            for name, rows in container_rows.items()
        },
    }


def summarize(rows: list[dict[str, Any]]) -> dict[str, Any]:
    """Calculate all requested metrics from successful real endpoint responses only."""
    successful = [row for row in rows if row.get("status") == 200]
    summary: dict[str, Any] = {
        "requestCount": len(rows),
        "successfulRequestCount": len(successful),
        "requestErrorCount": len(rows) - len(successful),
        "latencyMs": {
            "average": round(statistics.fmean(float(row["elapsedMs"]) for row in successful), 3) if successful else None,
            "p50": percentile([float(row["elapsedMs"]) for row in successful], 0.50),
            "p95": percentile([float(row["elapsedMs"]) for row in successful], 0.95),
            "p99": percentile([float(row["elapsedMs"]) for row in successful], 0.99),
        },
    }
    for metric, rank_field in (("document", "documentRank"), ("page", "pageRank"), ("block", "blockRank")):
        for cutoff in METRIC_CUTOFFS:
            summary[f"{metric}Recall@{cutoff}"] = (
                sum(1 for row in successful if at_cutoff(row.get(rank_field), cutoff)) / len(successful)
                if successful else 0.0
            )
        summary[f"{metric}MRR"] = (
            sum(1.0 / int(row[rank_field]) for row in successful if row.get(rank_field)) / len(successful)
            if successful else 0.0
        )
    return summary


def targets_satisfied(summary: dict[str, Any]) -> bool:
    """Enforce every agreed full-library quality gate with strict greater-than semantics."""
    return (
        int(summary.get("requestErrorCount") or 0) == 0
        and float(summary.get("documentRecall@1") or 0.0) > TARGET_DOCUMENT_AT_1
        and float(summary.get("documentRecall@3") or 0.0) > TARGET_DOCUMENT_AT_3
        and float(summary.get("blockRecall@1") or 0.0) > TARGET_BLOCK_AT_1
        and float(summary.get("blockRecall@3") or 0.0) > TARGET_BLOCK_AT_3
    )


def markdown(report: dict[str, Any], path: Path) -> None:
    """Produce a concise human-readable handoff while the JSON preserves every hit."""
    summary = report["summary"]
    latency = summary.get("latencyMs", {})
    resource_summary = report.get("resourceSummary", {})

    def display(value: Any, suffix: str = "") -> str:
        """Render optional benchmark values without turning a measured zero into a dash."""
        return "-" if value is None else f"{value}{suffix}"

    lines = [
        "# 真实后端教材检索评测（46 条）",
        "",
        f"- 端点：`{report['backend']['endpoint']}`；每条用例一次真实 POST 请求；成功 {summary['successfulRequestCount']}/{summary['requestCount']}。",
        f"- 策略：`{', '.join(report['observedStrategies']) or '无成功响应'}`。",
        f"- 延迟：平均 {display(latency.get('average'), ' ms')}；P50 {display(latency.get('p50'), ' ms')}；P95 {display(latency.get('p95'), ' ms')}；P99 {display(latency.get('p99'), ' ms')}。",
        "- `doc@K` 按唯一 `docId`，`page@K` 按真实命中块顺序，`block@K` 要求同书且命中规范化后的可见小标题（可覆盖同一标题的连续页）。稳定 sectionId 的重复问题另列入数据审计。",
        "",
        "| 指标 | @1 | @3 | @5 | MRR |",
        "|---|---:|---:|---:|---:|",
        f"| 文档 | {summary['documentRecall@1']:.3f} | {summary['documentRecall@3']:.3f} | {summary['documentRecall@5']:.3f} | {summary['documentMRR']:.3f} |",
        f"| 页面 | {summary['pageRecall@1']:.3f} | {summary['pageRecall@3']:.3f} | {summary['pageRecall@5']:.3f} | {summary['pageMRR']:.3f} |",
        f"| 小标题块 | {summary['blockRecall@1']:.3f} | {summary['blockRecall@3']:.3f} | {summary['blockRecall@5']:.3f} | {summary['blockMRR']:.3f} |",
        "",
        "## 真实运行资源",
        "",
        f"- GPU：{resource_summary.get('gpu', {}).get('name') or '-'}；平均利用率 {display(resource_summary.get('gpu', {}).get('utilizationAvgPercent'), '%')}；峰值利用率 {display(resource_summary.get('gpu', {}).get('utilizationMaxPercent'), '%')}；峰值显存 {display(resource_summary.get('gpu', {}).get('memoryUsedMaxMb'), ' MB')} / {display(resource_summary.get('gpu', {}).get('memoryTotalMb'), ' MB')}。",
        "",
        "| 容器 | CPU 平均 | CPU 峰值 | 内存平均 | 内存峰值 |",
        "|---|---:|---:|---:|---:|",
    ]
    for name, values in sorted(resource_summary.get("containers", {}).items()):
        lines.append(
            f"| {name} | {display(values.get('cpuAvgPercent'), '%')} | {display(values.get('cpuMaxPercent'), '%')} | "
            f"{display(values.get('memoryUsedAvgMb'), ' MB')} | {display(values.get('memoryUsedMaxMb'), ' MB')} |"
        )
    lines.extend([
        "",
        "## 未命中小标题块",
        "",
        "| caseId | 查询 | 目标小标题 | 文档 rank | 页 rank | 块 rank | Top-3 sectionId |",
        "|---|---|---|---:|---:|---:|---|",
    ])
    for row in report["rows"]:
        if at_cutoff(row.get("blockRank"), 3):
            continue
        top_three = ", ".join(str(hit.get("sectionId") or "") for hit in row.get("hits", [])[:3])
        lines.append(
            f"| {row['caseId']} | {row['query'].replace('|', '\\|')} | {row['expected']['sectionTitle'].replace('|', '\\|')} | "
            f"{row.get('documentRank') or '-'} | {row.get('pageRank') or '-'} | {row.get('blockRank') or '-'} | {top_three} |"
        )
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser(description="Run exactly 46 real textbook retrieval requests through the Java backend")
    parser.add_argument("--cases", type=Path, default=DEFAULT_CASES)
    parser.add_argument("--expected-case-count", type=int, default=EXPECTED_CASE_COUNT,
                        help="exact case count before optional negative filtering; use 0 only for ad-hoc fixtures")
    parser.add_argument("--backend-url", default=DEFAULT_BACKEND_URL)
    parser.add_argument("--limit", type=int, default=DEFAULT_LIMIT)
    parser.add_argument("--timeout-seconds", type=int, default=DEFAULT_TIMEOUT_SECONDS)
    parser.add_argument("--output-dir", type=Path, default=None)
    parser.add_argument("--resource-sample-interval-seconds", type=float, default=0.25)
    parser.add_argument("--enforce-targets", action="store_true", help="exit non-zero unless requested block@1/@3 thresholds are met")
    args = parser.parse_args()
    if args.limit < max(METRIC_CUTOFFS):
        raise ValueError(f"limit must be at least {max(METRIC_CUTOFFS)} to compute all declared cutoffs")
    cases = load_cases(args.cases.expanduser().resolve(), args.expected_case_count)
    if not cases:
        raise ValueError("no positive labelled textbook cases remain after filtering")
    endpoint = args.backend_url.rstrip("/") + "/api/retrieval/textbooks/search"
    output = args.output_dir or DEFAULT_OUTPUT_ROOT / f"textbook-backend-46-{datetime.now():%Y%m%d-%H%M%S}"
    output = output.expanduser().resolve()
    output.mkdir(parents=True, exist_ok=True)

    rows: list[dict[str, Any]] = []
    resource_samples: list[dict[str, Any]] = []
    stop_sampling = threading.Event()
    sampler = threading.Thread(
        target=resource_sampler,
        args=(stop_sampling, resource_samples, max(0.05, args.resource_sample_interval_seconds)),
        daemon=True,
    )
    sampler.start()
    try:
        with requests.Session() as session:
            session.headers.update({"Accept": "application/json"})
            for case in cases:
                try:
                    status, response, elapsed_ms, request_payload = post_search(
                        session,
                        endpoint,
                        case.query,
                        args.limit,
                        args.timeout_seconds,
                    )
                    hits = response.get("hits") if isinstance(response.get("hits"), list) else []
                    normalized_hits = [hit for hit in hits if isinstance(hit, dict)]
                    rows.append({
                    "caseId": case.case_id,
                    "query": case.query,
                    "expected": {
                        "docId": case.doc_id,
                        "pageNos": sorted(case.page_nos),
                        "sectionId": case.section_id,
                        "sectionTitle": case.section_title,
                    },
                    "status": status,
                    "elapsedMs": round(elapsed_ms, 3),
                    # Persist the exact wire payload so every evaluation row
                    # can be audited for forbidden doc/page scope fields.
                    "requestPayload": request_payload,
                    "strategy": str(response.get("retrievalStrategy") or ""),
                    "stages": response.get("retrievalStages") if isinstance(response.get("retrievalStages"), list) else [],
                    "documentRank": first_distinct_document_rank(normalized_hits, case.doc_id),
                    "pageRank": first_page_rank(normalized_hits, case),
                    "blockRank": first_block_rank(normalized_hits, case),
                    "hits": normalized_hits,
                    "response": response if status != 200 else None,
                    })
                except requests.RequestException as exc:
                    rows.append({
                    "caseId": case.case_id,
                    "query": case.query,
                    "expected": {"docId": case.doc_id, "pageNos": sorted(case.page_nos), "sectionId": case.section_id, "sectionTitle": case.section_title},
                    "status": None,
                    "elapsedMs": None,
                    "requestError": f"{type(exc).__name__}: {exc}",
                    "hits": [],
                    "documentRank": None,
                    "pageRank": None,
                    "blockRank": None,
                    })
    finally:
        stop_sampling.set()
        sampler.join(timeout=5)

    report = {
        "kind": "real_backend_textbook_46_evaluation",
        "generatedAt": datetime.now().isoformat(timespec="seconds"),
        "backend": {"endpoint": endpoint, "limit": args.limit, "timeoutSeconds": args.timeout_seconds},
        "caseFile": str(args.cases.expanduser().resolve()),
        "corpusRoot": str(DEFAULT_CORPUS_ROOT.expanduser().resolve()),
        "metricDefinitions": {
            "document": "rank among distinct docId values",
            "page": "rank among returned evidence blocks matching expected docId and source page",
            "block": "rank among returned evidence blocks matching expected docId and normalized visible small-heading title; continuous pages of the same heading are one block",
        },
        "observedStrategies": sorted({str(row.get("strategy") or "") for row in rows if row.get("status") == 200 and row.get("strategy")} ),
        "summary": summarize(rows),
        "resourceSummary": summarize_resources(resource_samples),
        "resourceSamples": resource_samples,
        "rows": rows,
    }
    write_json(output / "report.json", report)
    markdown(report, output / "summary.md")
    print(json.dumps({"outputDir": str(output), "summary": report["summary"]}, ensure_ascii=False, indent=2))
    if args.enforce_targets:
        summary = report["summary"]
        if not targets_satisfied(summary):
            sys.exit(1)


if __name__ == "__main__":
    main()
