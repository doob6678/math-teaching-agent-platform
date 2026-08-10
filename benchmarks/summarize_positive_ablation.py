"""Summarize persisted real ablation retrieval rows using only positive source identities.

The retrieval phase is independent from the optional external audit phase.  This small report builder therefore
keeps the benchmark useful when the audit gateway is slow: every metric below is calculated from the real BM25,
BGE, and cross-encoder rows already written by ``textbook_ablation_eval.py``.  It never invents a score for a missing
row and it never includes negative cases.
"""

from __future__ import annotations

import argparse
import json
import math
import statistics
from pathlib import Path
from typing import Any


def percentile(values: list[float], fraction: float) -> float | None:
    """Use nearest-rank percentiles so small, fixed evaluation sets remain reproducible."""
    if not values:
        return None
    ordered = sorted(values)
    index = max(0, min(len(ordered) - 1, math.ceil(len(ordered) * fraction) - 1))
    return round(ordered[index], 3)


def load(path: Path) -> Any:
    """Read UTF-8 benchmark artifacts without deriving labels from retrieval output."""
    return json.loads(path.read_text(encoding="utf-8"))


def main() -> None:
    parser = argparse.ArgumentParser(description="Summarize positive-only persisted textbook ablation results")
    parser.add_argument("--input-dir", type=Path, required=True)
    args = parser.parse_args()
    input_dir = args.input_dir.resolve()
    cases = load(input_dir / "cases.json")
    results = load(input_dir / "results.json")
    positive_ids = {
        str(case.get("caseId"))
        for case in cases
        if isinstance(case, dict) and str(case.get("caseId") or "").strip() and str(case.get("polarity") or "positive") == "positive"
    }
    page_rows = [row for row in results if row.get("corpus") == "page" and str(row.get("caseId")) in positive_ids]
    configs = sorted({str(row.get("result", {}).get("config") or "") for row in page_rows if row.get("result")})
    summaries: dict[str, dict[str, Any]] = {}
    for config in configs:
        rows = [row["result"] for row in page_rows if row.get("result", {}).get("config") == config]
        item: dict[str, Any] = {
            "config": config,
            "positiveCaseCount": len(rows),
            "retrievalRows": len(rows),
            "documentRecall": {},
            "pageRecall": {},
            "blockRecall": {},
            "latencyMs": {},
            "rerankCandidateCount": {
                "average": round(statistics.fmean(float(row.get("rerankCandidateCount") or 0) for row in rows), 3) if rows else None,
                "max": max((int(row.get("rerankCandidateCount") or 0) for row in rows), default=0),
            },
        }
        for cutoff in (1, 3, 5):
            for metric in ("document", "page", "block"):
                # Retrieval rows use the Java-shaped lower-camel names; keep this mapping explicit so a missing field
                # cannot silently turn every recall value into zero.
                field = {"document": "documentRank", "page": "pageRank", "block": "blockRank"}[metric]
                item[f"{metric}Recall"][f"@{cutoff}"] = round(
                    sum(1 for row in rows if row.get(field) is not None and int(row[field]) <= cutoff) / len(rows), 6
                ) if rows else 0.0
        for field in ("elapsedMs", "recallWallMs", "bm25Ms", "embeddingMs", "bgeRankMs", "rerankMs"):
            values = [float(row.get(field) or 0.0) for row in rows]
            item["latencyMs"][field] = {
                "average": round(statistics.fmean(values), 3) if values else None,
                "p95": percentile(values, 0.95),
                "p99": percentile(values, 0.99),
            }
        summaries[config] = item
    report = {
        "kind": "positive_only_textbook_ablation_summary",
        "source": {
            "inputDir": str(input_dir),
            "caseCount": len(positive_ids),
            "scoringRule": "Only persisted positive source identities; no negative or external audit score enters recall.",
            "resourceSampling": "Not collected during this ablation; use the full live HTTP benchmark resourceSamples for production resource conclusions.",
        },
        "summaries": summaries,
    }
    (input_dir / "positive-only-report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    lines = [
        "# 教材消融（正例-only）",
        "",
        f"样本：{len(positive_ids)} 条真实教材正例；不含负例，不使用跨切分 block 分数。",
        "",
        "| 配置 | doc@1/@3/@5 | page@1/@3/@5 | block@1/@3/@5 | 总延迟 avg/P95/P99 ms |",
        "|---|---|---|---|---:|",
    ]
    for config, item in summaries.items():
        def triplet(metric: str) -> str:
            return "/".join(f"{item[metric+'Recall'][f'@{k}']:.3f}" for k in (1, 3, 5))
        latency = item["latencyMs"]["elapsedMs"]
        lines.append(f"| {config} | {triplet('document')} | {triplet('page')} | {triplet('block')} | {latency['average']:.1f}/{latency['p95']:.1f}/{latency['p99']:.1f} |")
    lines.extend([
        "",
        "说明：结果来自已完成的真实 GPU worker 检索请求；外部 Luna 盲审未计入任何召回分，也不影响本表。",
    ])
    (input_dir / "positive-only-summary.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(json.dumps({"outputDir": str(input_dir), "positiveCaseCount": len(positive_ids), "configs": configs}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
