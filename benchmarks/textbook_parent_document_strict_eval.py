"""Run the immutable 46-case strict diagnostic through the parent-document prototype.

Children remain the BM25/BGE retrieval units. A logical small-heading parent is
the rerank payload, while the highest-scoring recalled child remains the result
page. This is the established parent-document retrieval pattern and avoids
turning a correct source-page hit into a longest-sibling page miss.
"""

from __future__ import annotations

import argparse
import json
import statistics
from datetime import datetime
from pathlib import Path
from typing import Any

from benchmarks.build_textbook_independent_eval_set import DEFAULT_LIBRARY_ROOT
from benchmarks.textbook_ablation_eval import WORKER_BASE_URL, WORKER_KEY_FILE
from benchmarks.textbook_independent_retrieval_eval import RealWorker
from benchmarks.textbook_section_block_prototype import (
    SectionBlockRetriever,
    block_rank,
    document_rank,
    strict_block_rank,
)


DEFAULT_CASES = Path(
    "output/benchmarks/textbook-page-section-ablation-route-balanced-production-v3-report-audit-20260714/section_cases.json"
)
DEFAULT_OUTPUT = Path("output/benchmarks/textbook-parent-document-strict-v1")
METRIC_CUTOFFS = (1, 3, 5)


def read_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def write_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def rss_bytes() -> int | None:
    """Return local evaluator RSS when psutil is available; never invent a resource value."""
    try:
        import os
        import psutil

        return int(psutil.Process(os.getpid()).memory_info().rss)
    except (ImportError, OSError):
        return None


def expected(case: dict[str, Any]) -> dict[str, Any]:
    source = case.get("source") if isinstance(case, dict) else None
    if not isinstance(source, dict):
        raise ValueError(f"section case has no immutable source: {case}")
    return {
        "docId": str(source.get("doc_id") or ""),
        "pageNo": int(source.get("page_no") or 0),
        "sectionId": str(source.get("section_id") or ""),
        "sectionTitle": str(source.get("section_title") or ""),
    }


def at(rank: int | None, cutoff: int) -> bool:
    return rank is not None and rank <= cutoff


def summarize(rows: list[dict[str, Any]]) -> dict[str, Any]:
    if not rows:
        raise ValueError("cannot summarize an empty strict evaluation")
    result: dict[str, Any] = {
        "caseCount": len(rows),
        "latencyMs": {
            "average": round(statistics.fmean(float(row["elapsedMs"]) for row in rows), 3),
            "p95": round(sorted(float(row["elapsedMs"]) for row in rows)[max(0, int(len(rows) * 0.95) - 1)], 3),
        },
        "candidateCount": {
            "average": round(statistics.fmean(int(row["candidateCount"]) for row in rows), 3),
            "maximum": max(int(row["candidateCount"]) for row in rows),
        },
    }
    for name, field in (("document", "documentRank"), ("strictBlock", "strictBlockRank"), ("logicalBlock", "logicalBlockRank")):
        for cutoff in METRIC_CUTOFFS:
            result[f"{name}Recall@{cutoff}"] = sum(at(row.get(field), cutoff) for row in rows) / len(rows)
    result["strictBlockMRR@10"] = statistics.fmean(
        1.0 / int(row["strictBlockRank"])
        if row["strictBlockRank"] is not None and int(row["strictBlockRank"]) <= 10 else 0.0
        for row in rows
    )
    return result


def main() -> None:
    parser = argparse.ArgumentParser(description="Evaluate parent-document textbook retrieval against immutable strict cases")
    parser.add_argument("--cases", type=Path, default=DEFAULT_CASES)
    parser.add_argument("--library-root", type=Path, default=DEFAULT_LIBRARY_ROOT)
    parser.add_argument("--output-dir", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--limit", type=int, default=10)
    parser.add_argument(
        "--child-evidence-rerank",
        action="store_true",
        help="rerank source-page children after parent admission while retaining the same final candidate limit",
    )
    args = parser.parse_args()
    if args.limit < max(METRIC_CUTOFFS):
        raise ValueError(f"limit must be at least {max(METRIC_CUTOFFS)}")
    cases = read_json(args.cases.expanduser().resolve())
    if not isinstance(cases, list) or len(cases) != 46:
        raise ValueError("strict parent-document evaluation requires the immutable 46 section cases")

    root = args.library_root.expanduser().resolve()
    retriever = SectionBlockRetriever(root, RealWorker(WORKER_BASE_URL, WORKER_KEY_FILE))
    rss_after_index = rss_bytes()
    peak_rss = rss_after_index
    rows: list[dict[str, Any]] = []
    for case in cases:
        target = expected(case)
        hits, abstained, elapsed_ms, model = retriever.retrieve(
            str(case.get("query") or ""),
            args.limit,
            child_evidence_rerank=args.child_evidence_rerank,
        )
        current_rss = rss_bytes()
        if current_rss is not None:
            peak_rss = max(peak_rss or 0, current_rss)
        rows.append({
            "caseId": case.get("caseId"),
            "query": case.get("query"),
            "requestPayload": {"query": case.get("query"), "limit": args.limit},
            "expected": target,
            "documentRank": document_rank(hits, target),
            "strictBlockRank": strict_block_rank(hits, target),
            "logicalBlockRank": block_rank(hits, target),
            "candidateCount": len(hits),
            "abstained": abstained,
            "elapsedMs": elapsed_ms,
            "rerankModel": model,
            "hits": [
                {
                    "rank": index,
                    "docId": hit.get("doc_id"),
                    "pageNo": hit.get("page_no"),
                    "sectionId": hit.get("section_id"),
                    "sectionTitle": hit.get("section_title"),
                    "chunkId": hit.get("chunk_id"),
                    "stage": hit.get("_stage"),
                    "rerankScore": hit.get("_rerank_score"),
                }
                for index, hit in enumerate(hits, 1)
            ],
        })
    output = args.output_dir.expanduser().resolve()
    report = {
        "kind": "parent_document_retriever_immutable_strict_46_evaluation",
        "generatedAt": datetime.now().isoformat(timespec="seconds"),
        "cases": str(args.cases.expanduser().resolve()),
        "libraryRoot": str(root),
        "publicRequestFields": ["query", "limit"],
        "retrievalContract": (
            "original child chunks retrieve; logical parent reranks; recalled child supplies returned page"
            if not args.child_evidence_rerank
            else "original child chunks retrieve; logical parent admits; child page evidence reranks with parent metadata"
        ),
        "resource": {
            "corpusRows": len(retriever.rows),
            "logicalBlockCount": len(retriever.block_index.members_by_key),
            "rssAfterIndexBytes": rss_after_index,
            "peakEvaluatorRssBytes": peak_rss,
            "maxRerankCandidates": 9,
        },
        "summary": summarize(rows),
    }
    write_json(output / "results.json", rows)
    write_json(output / "report.json", report)
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
