"""Build the current Feishu/teacher production retrieval handoff from real run artifacts.

The benchmark runner deliberately stores raw HTTP rows and raw resource samples.  This builder turns those
immutable observations into the operator-facing comparison without inventing labels, negative cases, or scores
from a different parser split.  Feishu synchronization evidence is read from the URL-based sync summaries passed
on the command line, so a host-side Markdown mirror can never silently become the source of truth.
"""

from __future__ import annotations

import argparse
import json
import math
import re
import statistics
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


SERVICE_NAMES = ("backend", "ai-worker", "milvus", "mysql", "redis")
BYTES_UNITS = {"B": 1, "KiB": 1024, "MiB": 1024**2, "GiB": 1024**3, "TiB": 1024**4}
MEMORY_RE = re.compile(r"(?P<used>[0-9.]+)(?P<used_unit>KiB|MiB|GiB|TiB|B)\s*/\s*(?P<total>[0-9.]+)(?P<total_unit>KiB|MiB|GiB|TiB|B)")


def read_json(path: Path) -> Any:
    """Read a UTF-8 artifact exactly as written by the real benchmark or sync runner."""
    return json.loads(path.read_text(encoding="utf-8"))


def percentile(values: list[float], fraction: float) -> float | None:
    """Use linear interpolation so HTTP/resource percentiles are reproducible for every sample count."""
    if not values:
        return None
    ordered = sorted(values)
    if len(ordered) == 1:
        return round(ordered[0], 3)
    position = (len(ordered) - 1) * fraction
    lower = math.floor(position)
    upper = min(lower + 1, len(ordered) - 1)
    return round(ordered[lower] + (ordered[upper] - ordered[lower]) * (position - lower), 3)


def summary(values: list[float]) -> dict[str, float | None]:
    """Return the same avg/P95/P99/peak view for a latency or resource series."""
    return {
        "avg": round(statistics.fmean(values), 3) if values else None,
        "p95": percentile(values, 0.95),
        "p99": percentile(values, 0.99),
        "peak": round(max(values), 3) if values else None,
    }


def parse_docker_stats(raw: str) -> tuple[float | None, float | None, float | None]:
    """Parse Docker's real ``CPUPerc`` and ``MemUsage`` text into CPU percent and byte counters."""
    cpu_match = re.search(r"([0-9.]+)%", raw or "")
    memory_match = MEMORY_RE.search(raw or "")
    cpu = float(cpu_match.group(1)) if cpu_match else None
    used = None
    total = None
    if memory_match:
        used = float(memory_match.group("used")) * BYTES_UNITS[memory_match.group("used_unit")]
        total = float(memory_match.group("total")) * BYTES_UNITS[memory_match.group("total_unit")]
    return cpu, used, total


def resource_summary(samples: list[dict[str, Any]]) -> dict[str, Any]:
    """Aggregate every raw NVIDIA/Docker observation, including tail percentiles per service."""
    gpu_util: list[float] = []
    gpu_memory: list[float] = []
    docker_values: dict[str, dict[str, list[float]]] = {
        service: {"cpuPercent": [], "memoryUsedBytes": [], "memoryUsedMiB": [], "memoryTotalBytes": []}
        for service in SERVICE_NAMES
    }
    for sample in samples:
        gpu = sample.get("gpu") or {}
        for card in gpu.get("gpus") or []:
            if isinstance(card.get("utilizationGpuPercent"), (int, float)):
                gpu_util.append(float(card["utilizationGpuPercent"]))
            if isinstance(card.get("memoryUsedMiB"), (int, float)):
                gpu_memory.append(float(card["memoryUsedMiB"]))
        for service, state in (sample.get("docker") or {}).items():
            if service not in docker_values or not state.get("available"):
                continue
            cpu, used, total = parse_docker_stats(str(state.get("raw") or ""))
            if cpu is not None:
                docker_values[service]["cpuPercent"].append(cpu)
            if used is not None:
                docker_values[service]["memoryUsedBytes"].append(used)
                docker_values[service]["memoryUsedMiB"].append(used / (1024**2))
            if total is not None:
                docker_values[service]["memoryTotalBytes"].append(total)
    docker_summary: dict[str, Any] = {}
    for service, values in docker_values.items():
        docker_summary[service] = {
            "samples": len(values["cpuPercent"]),
            "cpuPercent": summary(values["cpuPercent"]),
            "memoryUsedMiB": summary(values["memoryUsedMiB"]),
            "memoryTotalMiB": round(statistics.fmean(values["memoryTotalBytes"]) / (1024**2), 3)
            if values["memoryTotalBytes"] else None,
        }
    return {
        "sampleCount": len(samples),
        "gpu": {
            "utilizationPercent": summary(gpu_util),
            "memoryUsedMiB": summary(gpu_memory),
            "availableSamples": len(gpu_util),
        },
        "docker": docker_summary,
    }


def load_sync(path: Path) -> dict[str, Any]:
    """Keep only auditable URL sync fields and preserve the raw artifact path in the final report."""
    payload = read_json(path)
    discovered = payload.get("discovered_items") or []
    type_counts: dict[str, int] = {}
    for item in discovered:
        file_type = str(item.get("file_type") or item.get("type") or "unknown")
        type_counts[file_type] = type_counts.get(file_type, 0) + 1
    timings = payload.get("item_timings") or []
    timing_groups: dict[str, list[float]] = {"all": [], "changed": [], "docx": [], "image": [], "file": []}
    for item in timings:
        elapsed = item.get("elapsed_ms")
        if not isinstance(elapsed, (int, float)):
            continue
        value = float(elapsed)
        timing_groups["all"].append(value)
        status = str(item.get("status") or "")
        if status in timing_groups:
            timing_groups[status].append(value)
        item_type = str(item.get("type") or "")
        if item_type in timing_groups:
            timing_groups[item_type].append(value)
    return {
        "artifact": str(path.resolve()),
        "url": payload.get("url"),
        "token": payload.get("token"),
        "folderName": payload.get("folder_name"),
        "elapsedMs": payload.get("elapsed_ms"),
        "stats": payload.get("stats") or {},
        "discoveredCount": len(discovered),
        "fileTypeCounts": type_counts,
        "failedCount": len(payload.get("failed_items") or []),
        "incremental": bool(payload.get("incremental")),
        "itemTimingMs": {key: summary(values) for key, values in timing_groups.items() if values},
    }


def compare_modes(metrics: dict[str, Any]) -> dict[str, Any]:
    """Create an explicit before/after delta while keeping recall as the primary selection objective."""
    modes = metrics.get("modes") or {}
    before = modes.get("before_unscoped") or {}
    after = modes.get("after_scoped") or {}
    before_recall = before.get("documentRecall") or {}
    after_recall = after.get("documentRecall") or {}
    before_latency = before.get("latencyMs") or {}
    after_latency = after.get("latencyMs") or {}
    return {
        "before": before,
        "after": after,
        "delta": {
            "documentRecall": {
                key: round(float(after_recall.get(key, 0.0)) - float(before_recall.get(key, 0.0)), 4)
                for key in ("1", "3", "5")
            },
            "latencyMs": {
                key: round(float(after_latency.get(key, 0.0)) - float(before_latency.get(key, 0.0)), 3)
                for key in ("avg", "p95", "p99")
            },
        },
        "selectionReason": (
            "生产采用 after_scoped：先按逻辑 library 隔离资料库，再做 document/block 排序；"
            "在正例-only 口径下优先保证召回，且不允许未指定库的混合调用方成为生产默认。"
        ),
    }


def markdown(report: dict[str, Any]) -> str:
    """Render a concise but complete Chinese handoff that links every machine-readable artifact."""
    comparison = report["comparison"]
    lines = [
        "# 当前生产检索链路最终报告（Feishu + 教师资料，2026-08-04）",
        "",
        "> 本报告只使用真实 Feishu URL 同步结果、当前生产 HTTP API、当前数据库/Milvus 和 CUDA worker。100 条样本全部为人工编写正例，不含负例；不跨 parser split 比较 block 分数。",
        "",
        "## 结论与生产选择",
        "",
        "- 生产版本：`after_scoped`。调用方先传入逻辑 `library`，再执行资料库内召回和排序，避免 Feishu、教师资料、真题和模拟题混库竞争。",
        "- 评价优先级：正例文档召回 > 页面/块可比性 > 尾延迟；不同切分不混算 block 分数。",
        "- 写作配置保持 `luan`；本次检索 benchmark 只调用检索、向量和图片检索接口，不调用写作模型。",
        "",
        "## 修改前后真实 HTTP 对比",
        "",
        "| 版本 | avg ms | P95 ms | P99 ms | doc@1 | doc@3 | doc@5 | 成功 |",
        "|---|---:|---:|---:|---:|---:|---:|---:|",
    ]
    for label, mode in (("修改前：before_unscoped", "before_unscoped"), ("修改后：after_scoped", "after_scoped")):
        value = report["metrics"]["modes"].get(mode) or {}
        latency = value.get("latencyMs") or {}
        recall = value.get("documentRecall") or {}
        lines.append(
            f"| {label} | {latency.get('avg', 0):.3f} | {latency.get('p95', 0):.3f} | {latency.get('p99', 0):.3f} | "
            f"{recall.get('1', 0):.4f} | {recall.get('3', 0):.4f} | {recall.get('5', 0):.4f} | "
            f"{value.get('successfulCount', 0)}/{value.get('sampleCount', 0)} |"
        )
    after_by_library = ((report["metrics"].get("modes") or {}).get("after_scoped") or {}).get("documentRecallByLibrary") or {}
    lines.extend([
        "",
        "## 修改后按资料库",
        "",
        "| 资料库 | 样本 | doc@1 | doc@3 | doc@5 | avg ms | P95 ms | P99 ms |",
        "|---|---:|---:|---:|---:|---:|---:|---:|",
    ])
    for library, value in sorted(after_by_library.items()):
        latency = value.get("latencyMs") or {}
        lines.append(
            f"| {library} | {value.get('sampleCount', 0)} | {value.get('doc@1', 0):.4f} | "
            f"{value.get('doc@3', 0):.4f} | {value.get('doc@5', 0):.4f} | "
            f"{latency.get('avg', 0):.3f} | {latency.get('p95', 0):.3f} | {latency.get('p99', 0):.3f} |"
        )
    lines.extend([
        "",
        f"修改后相对修改前 doc@1/doc@3/doc@5 差值：{comparison['delta']['documentRecall']}；延迟差值 ms：{comparison['delta']['latencyMs']}。完整逐请求数据见 `query_rows.jsonl`。",
        "",
        "## 消融结果",
        "",
        "| 模式 | avg ms | P95 ms | P99 ms | doc@1 | doc@3 | doc@5 | 成功 |",
        "|---|---:|---:|---:|---:|---:|---:|---:|",
    ])
    for mode in ("after_scoped_limit1", "after_scoped_limit3", "after_scoped_limit5", "image_route"):
        value = report["metrics"]["modes"].get(mode) or {}
        latency = value.get("latencyMs") or {}
        recall = value.get("documentRecall") or {}
        lines.append(
            f"| {mode} | {latency.get('avg', 0):.3f} | {latency.get('p95', 0):.3f} | {latency.get('p99', 0):.3f} | "
            f"{recall.get('1', 0):.4f} | {recall.get('3', 0):.4f} | {recall.get('5', 0):.4f} | "
            f"{value.get('successfulCount', 0)}/{value.get('sampleCount', 0)} |"
        )
    lines.extend([
        "",
        "## 完整最终召回链路",
        "",
        "1. Feishu URL 同步 → 下载 docx/普通文件与图片资产 → 解析为当前生产 document/block/asset；按 source identity 去重，归档资源同步清理 CLIP 残留。",
        "2. Agent/MCP 调用规范化 query，并明确传入逻辑 `library`、权限范围和 document 过滤。",
        "3. 资料库内执行 BGE/Milvus document coarse recall；教师资料继续做 tenant/role/permission 可见性过滤。",
        "4. 候选 document 载入真实 block；标题/正文词法证据与语义证据共同进入 document/block 排序，候选窗口受配置上限控制。",
        "5. 需要精排时，将受限候选送入 CUDA BGE reranker；最终按 document、block、asset 组装 evidence response。",
        "6. 图片查询单独走 CLIP page-asset 路由；文本查询只有在文本页召回为空时才启用图片 fallback，避免每次查询重复调用 CLIP。",
        "",
        "## Feishu URL 同步与入库证据",
        "",
    ])
    lines.extend([
        "| documentId | sourceType | 状态 | blocks | assets |",
        "|---|---|---|---:|---:|",
    ])
    for document in report["inventory"].get("documents") or []:
        lines.append(
            f"| {document.get('documentId', '')} | {document.get('sourceType', '')} | "
            f"{document.get('syncStatus', '')}/{document.get('parseStatus', '')}/{document.get('embeddingStatus', '')}/{document.get('indexStatus', '')} | "
            f"{document.get('blockCount', 0)} | {document.get('assetCount', 0)} |"
        )
    vector_status = report["inventory"].get("textVectorStatus") or {}
    lines.extend([
        "",
        f"Milvus：collection=`{vector_status.get('collectionName', '')}`，model=`{vector_status.get('embeddingModel', '')}`，"
        f"dimension={vector_status.get('dimension', '')}，rowCount={vector_status.get('rowCount', '')}，"
        f"index={vector_status.get('indexState', '')}，load={vector_status.get('loadState', '')}，status={vector_status.get('status', '')}。",
        "",
    ])
    verification = report.get("ingestionVerification") or {}
    if verification:
        staging = verification.get("stagingCounts") or {}
        lines.extend([
            "## 新增文档实证",
            "",
            f"`{verification.get('sourcePath', '')}` 已在生产 staging 中，staging 文件数={staging.get('files', 0)}，"
            f"正文/文档文件={staging.get('contentFiles', 0)}，图片文件={staging.get('imageFiles', 0)}；"
            f"API 返回 blocks={verification.get('apiCounts', {}).get('blocks', 0)}、"
            f"assets={verification.get('apiCounts', {}).get('assets', 0)}。",
            f"短语 `{verification.get('phrase', '')}` 命中 blocks={len(verification.get('phraseBlocks') or [])}，"
            f"生产检索接口 HTTP={verification.get('search', {}).get('status', '')}，"
            f"返回命中={len(verification.get('search', {}).get('hits') or [])}；Milvus rowCount="
            f"{(verification.get('milvus') or {}).get('rowCount', '')}。",
            "",
        ])
    for item in report["sync"]:
        stats = item["stats"]
        lines.append(
            f"- `{item['url']}`：发现 {item['discoveredCount']} 项，类型 {item['fileTypeCounts']}，"
            f"文件夹 {stats.get('folders', 0)}，图片资产 {stats.get('assets', 0)}，"
            f"变更 {stats.get('changed_files', stats.get('files', 0))}，未变更 {stats.get('unchanged_files', stats.get('skipped', 0))}，"
            f"失败 {item['failedCount']}，耗时 {item['elapsedMs']} ms。"
        )
        lines.append(f"  - 单项耗时统计：{json.dumps(item['itemTimingMs'], ensure_ascii=False)}")
    lines.extend([
        "",
        "## 资源消耗",
        "",
        "```json",
        json.dumps(report["resources"], ensure_ascii=False, indent=2),
        "```",
        "",
        "## 留盘文件",
        "",
    ])
    for key, value in report["artifacts"].items():
        lines.append(f"- {key}: `{value}`")
    lines.append("")
    return "\n".join(lines)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--benchmark-dir", required=True, type=Path)
    parser.add_argument("--output-dir", required=True, type=Path)
    parser.add_argument("--sync-summary", action="append", required=True, type=Path)
    parser.add_argument(
        "--supplement-benchmark-dir",
        action="append",
        default=[],
        type=Path,
        help="Additional real benchmark directories whose modes, rows, and resource samples complete the primary run.",
    )
    parser.add_argument("--ingestion-verification", type=Path, default=None)
    args = parser.parse_args()
    benchmark_dir = args.benchmark_dir.resolve()
    output_dir = args.output_dir.resolve()
    output_dir.mkdir(parents=True, exist_ok=True)
    metrics = read_json(benchmark_dir / "metrics.json")
    benchmark_dirs = [benchmark_dir] + [path.resolve() for path in args.supplement_benchmark_dir]
    inventory = read_json(benchmark_dir / "corpus_inventory.json")
    mode_rows: dict[str, list[dict[str, Any]]] = {}
    samples: list[dict[str, Any]] = []
    for directory in benchmark_dirs:
        supplemental_metrics = read_json(directory / "metrics.json")
        metrics.setdefault("modes", {}).update(supplemental_metrics.get("modes") or {})
        rows_path = directory / "query_rows.jsonl"
        samples_path = directory / "resource_samples.jsonl"
        source_rows = [json.loads(line) for line in rows_path.read_text(encoding="utf-8").splitlines() if line.strip()]
        for mode, mode_summary in (supplemental_metrics.get("modes") or {}).items():
            # A mode is eligible for the handoff only when that source run completed every request. This excludes
            # interrupted sessions and rate-limited leftovers while preserving the raw source directories separately.
            if mode in mode_rows or mode_summary.get("successfulCount") != mode_summary.get("sampleCount"):
                continue
            mode_rows[mode] = [row for row in source_rows if row.get("mode") == mode]
        samples.extend(json.loads(line) for line in samples_path.read_text(encoding="utf-8").splitlines() if line.strip())
        if not inventory.get("resourceCount") and supplemental_metrics.get("inventory", {}).get("resourceCount"):
            inventory = supplemental_metrics["inventory"]
        elif supplemental_metrics.get("inventory", {}).get("resourceCount"):
            inventory = supplemental_metrics["inventory"]
    selected_mode_order = (
        "before_unscoped",
        "after_scoped",
        "after_scoped_limit1",
        "after_scoped_limit3",
        "after_scoped_limit5",
        "image_route",
    )
    rows = [row for mode in selected_mode_order for row in mode_rows.get(mode, [])]
    selected_rows_path = output_dir / "query_rows.jsonl"
    selected_rows_path.write_text(
        "".join(json.dumps(row, ensure_ascii=False) + "\n" for row in rows),
        encoding="utf-8",
    )
    selected_samples_path = output_dir / "resource_samples.jsonl"
    selected_samples_path.write_text(
        "".join(json.dumps(sample, ensure_ascii=False) + "\n" for sample in samples),
        encoding="utf-8",
    )
    metrics["inventory"] = inventory
    metrics.setdefault("runtime", {})["supplementBenchmarkDirs"] = [str(path) for path in benchmark_dirs[1:]]
    ingestion_verification = read_json(args.ingestion_verification.resolve()) if args.ingestion_verification else {}
    report = {
        "kind": "feishu_teacher_production_retrieval_final",
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "dataset": metrics.get("dataset"),
        "evaluation": metrics.get("evaluation"),
        "runtime": metrics.get("runtime"),
        "metrics": metrics,
        "comparison": compare_modes(metrics),
        "inventory": inventory,
        "ingestionVerification": ingestion_verification,
        "sync": [load_sync(path.resolve()) for path in args.sync_summary],
        "resources": resource_summary(samples),
        "artifacts": {
            "benchmarkDirs": [str(path) for path in benchmark_dirs],
            "benchmarkMetrics": str((benchmark_dir / "metrics.json").resolve()),
            "queryRows": str(selected_rows_path.resolve()),
            "resourceSamples": str(selected_samples_path.resolve()),
            "corpusInventory": str((benchmark_dirs[-1] / "corpus_inventory.json").resolve()),
            "syncEvidence": str((benchmark_dir / "feishu_sync_evidence.json").resolve()),
            "ingestionVerification": str(args.ingestion_verification.resolve()) if args.ingestion_verification else None,
            "manualDataset": str((Path(metrics["dataset"]["path"])).resolve()),
            "rowCount": len(rows),
        },
    }
    (output_dir / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (output_dir / "summary.md").write_text(markdown(report), encoding="utf-8")
    print(json.dumps({"report": str((output_dir / "report.json").resolve()), "summary": str((output_dir / "summary.md").resolve()), "rows": len(rows)}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
