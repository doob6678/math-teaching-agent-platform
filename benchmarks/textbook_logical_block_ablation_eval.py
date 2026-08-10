"""Historical c2 Parent Document Retriever ablation for the immutable 46-case contract.

This is deliberately separate from production. The legacy c2 table ranks raw
children against a source-page label.  Here all 3317 immutable c2 children are
still the only BM25/BGE corpus, while an optional reference-only logical-heading
identity removes siblings before document admission.  Parent text is joined
only in the final rerank window and the child that triggered recall remains the
reported page.
"""

from __future__ import annotations

import argparse
import json
import os
import statistics
import threading
import time
from collections import defaultdict
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any

try:
    import psutil
except ImportError:  # pragma: no cover - the report remains usable without psutil.
    psutil = None

from benchmarks.build_textbook_independent_eval_set import corpus_fingerprint
from benchmarks.textbook_ablation_eval import PRODUCTION_PROFILE, WORKER_BASE_URL, WORKER_KEY_FILE
from benchmarks.textbook_independent_retrieval_eval import RealWorker, interleave_routes, score_or_negative_infinity
from benchmarks.textbook_section_block_prototype import (
    SectionBlockRetriever,
    block_rank,
    grouped,
    semantic_page_text,
    strict_block_rank,
    visible_title,
)
from benchmarks.textbook_independent_retrieval_eval import load_bm25_module


DEFAULT_LIBRARY_ROOT = Path(
    r"C:\Users\doob\Desktop\个人资料\高中数学\下载课本代码\tchMaterial-parser-main\tchMaterial-parser-main"
) / "processed_books_section_shadow_all_mini_c2"
DEFAULT_CASES = Path(
    "output/benchmarks/textbook-page-section-ablation-route-balanced-production-v3-report-audit-20260714/section_cases.json"
)
DEFAULT_OUTPUT = Path("output/benchmarks/textbook-logical-block-ablation-c2-parent-document-v1")
TOP_HITS = 10
METRIC_CUTOFFS = (1, 3, 5, 10)
PUBLIC_REQUEST_FIELDS = frozenset(("query", "limit"))
RESOURCE_SAMPLE_INTERVAL_SECONDS = 0.05


@dataclass(frozen=True)
class Pipeline:
    """A structural switch matrix, with no query-specific score fusion or tuning."""

    name: str
    use_bm25: bool
    use_title_bm25: bool
    use_bge: bool
    logical_blocks: bool
    use_rerank: bool
    child_evidence_rerank: bool = False


PIPELINES = (
    Pipeline("child_bm25", True, False, False, False, False),
    Pipeline("child_bge", False, False, True, False, False),
    Pipeline("child_hybrid", True, False, True, False, False),
    Pipeline("logical_bm25", True, False, False, True, False),
    Pipeline("logical_bge", False, False, True, True, False),
    Pipeline("logical_hybrid", True, False, True, True, False),
    Pipeline("logical_bm25_rerank", True, False, False, True, True),
    Pipeline("logical_bge_rerank", False, False, True, True, True),
    Pipeline("logical_hybrid_parent_rerank", True, False, True, True, True),
    Pipeline("logical_hybrid_child_evidence_rerank", True, False, True, True, True, True),
    # Fielded BM25 is a standard independent candidate route.  It indexes only
    # visible headings, then maps back to the unchanged c2 child reference.
    Pipeline("logical_title_hybrid_parent_rerank", True, True, True, True, True),
    Pipeline("logical_title_hybrid_child_evidence_rerank", True, True, True, True, True, True),
)


def validate_public_request_payload(payload: dict[str, Any]) -> bool:
    """Reject hidden corpus narrowing fields from recorded benchmark requests."""
    return set(payload.keys()) == PUBLIC_REQUEST_FIELDS


def logical_block_rank(hits: list[dict[str, Any]], expected: dict[str, Any] | None) -> int | None:
    """Score the parent small-heading identity, intentionally independent of child page."""
    return block_rank(hits, expected)


def canonical_parent_key(row: dict[str, Any]) -> tuple[str, str, str]:
    """Diagnose c2 parent fragmentation using stable numbered headings only.

    Numbered textbook headings are stable across a section's continuation
    pages, while generic labels such as ``解`` and ``练习`` are intentionally
    still isolated by section id.  This helper is audit-only until a separate
    end-to-end metric establishes that it improves retrieval without merging
    distinct lessons.
    """
    title = visible_title(row.get("section_title"))
    normalized = "".join(title.split())
    import re

    number = re.match(r"^(?:第[一二三四五六七八九十百]+[章节]|\d+(?:\.\d+)+)", normalized)
    parent_id = "heading:" + normalized if number else "section:" + str(row.get("section_id") or row.get("chunk_id") or "")
    return str(row.get("doc_id") or ""), parent_id, title


def child_key(row: dict[str, Any]) -> str:
    return str(row.get("chunk_id") or "")


def stable_unique(rows: list[dict[str, Any]], identity: str) -> list[dict[str, Any]]:
    """Keep route order and preserve the child that actually earned the candidate slot."""
    selected: list[dict[str, Any]] = []
    seen: set[str] = set()
    for row in rows:
        key = str(row.get("_block_key") if identity == "logical" else child_key(row))
        if not key or key in seen:
            continue
        selected.append(row)
        seen.add(key)
    return selected


class PeakRssSampler:
    """Measure the evaluator process while worker-model memory stays explicitly separate."""

    def __init__(self) -> None:
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None
        self._peak = 0
        self._start = 0

    def start(self) -> None:
        if psutil is None:
            return
        process = psutil.Process(os.getpid())
        self._start = process.memory_info().rss
        self._peak = self._start

        def sample() -> None:
            while not self._stop.wait(RESOURCE_SAMPLE_INTERVAL_SECONDS):
                self._peak = max(self._peak, process.memory_info().rss)

        self._thread = threading.Thread(target=sample, daemon=True)
        self._thread.start()

    def finish(self) -> dict[str, int | None]:
        if psutil is None:
            return {"rssAfterIndexBytes": None, "rssPeakBytes": None}
        self._stop.set()
        if self._thread is not None:
            self._thread.join(timeout=RESOURCE_SAMPLE_INTERVAL_SECONDS * 2)
        return {"rssAfterIndexBytes": self._start, "rssPeakBytes": self._peak}


class LogicalBlockAblation:
    """Runs child and parent-document routes against one immutable c2 snapshot."""

    def __init__(self, retriever: SectionBlockRetriever) -> None:
        self.retriever = retriever
        self.rows_by_chunk = {str(row.get("chunk_id") or ""): row for row in retriever.rows}
        # This index stores only title strings and row metadata.  It deliberately
        # does not make a second body-text corpus or alter the c2 source files.
        title_rows = [dict(row, text=visible_title(row.get("section_title")), formula_text="") for row in retriever.rows]
        self.title_index = load_bm25_module(retriever.library_root).build_bm25_index(title_rows)

    def lexical(self, query: str, logical_blocks: bool) -> list[dict[str, Any]]:
        hits, _ = self.retriever.index.search(query, limit=len(self.retriever.index.rows))
        rows = [
            dict(hit.row, _stage="bm25", _score=float(hit.score))
            for hit in sorted(hits, key=lambda item: item.score, reverse=True)
        ]
        if logical_blocks:
            rows = self.retriever.collapse_candidates(rows)
        return stable_unique(rows, "logical" if logical_blocks else "child")

    def semantic(self, query: str, logical_blocks: bool) -> list[dict[str, Any]]:
        # Production's BGE page endpoint has one public query+limit request.  It
        # owns the c2 vector index and returns original child identities.
        direct_hits = self.retriever.worker.page_search(query, TOP_HITS)
        rows: list[dict[str, Any]] = []
        for hit in direct_hits:
            source = self.rows_by_chunk.get(hit.chunk_id)
            if source is None:
                continue
            rows.append(dict(source, _stage="bge", _score=float(hit.score)))
        if logical_blocks:
            rows = self.retriever.collapse_candidates(rows)
        return stable_unique(rows, "logical" if logical_blocks else "child")

    def title_lexical(self, query: str, logical_blocks: bool) -> list[dict[str, Any]]:
        """Retrieve title evidence independently, then restore immutable child rows."""
        hits, _ = self.title_index.search(query, limit=len(self.title_index.rows))
        rows = [
            dict(self.rows_by_chunk[str(hit.row.get("chunk_id") or "")], _stage="title_bm25", _score=float(hit.score))
            for hit in sorted(hits, key=lambda item: item.score, reverse=True)
            if str(hit.row.get("chunk_id") or "") in self.rows_by_chunk
        ]
        if logical_blocks:
            rows = self.retriever.collapse_candidates(rows)
        return stable_unique(rows, "logical" if logical_blocks else "child")

    def admitted_candidates(self, lexical: list[dict[str, Any]], title: list[dict[str, Any]], semantic: list[dict[str, Any]], pipeline: Pipeline) -> list[dict[str, Any]]:
        routes = [
            route
            for enabled, route in (
                (pipeline.use_bm25, lexical),
                (pipeline.use_title_bm25, title),
                (pipeline.use_bge, semantic),
            )
            if enabled
        ]
        if not routes:
            return []
        identity = "logical" if pipeline.logical_blocks else "child"
        by_doc = [grouped(route) if pipeline.logical_blocks else self.group_children(route) for route in routes]
        doc_ids = interleave_routes(
            [list(route)[:PRODUCTION_PROFILE.max_document_candidates] for route in by_doc],
            PRODUCTION_PROFILE.max_rerank_documents,
        )
        support: dict[str, list[dict[str, Any]]] = {}
        for doc_id in doc_ids:
            support[doc_id] = self.support_for_document(doc_id, routes, identity)
        return self.matrix(doc_ids, support)

    @staticmethod
    def group_children(rows: list[dict[str, Any]]) -> dict[str, list[dict[str, Any]]]:
        grouped_rows: dict[str, list[dict[str, Any]]] = defaultdict(list)
        seen: dict[str, set[str]] = defaultdict(set)
        for row in rows:
            doc_id = str(row.get("doc_id") or "")
            key = child_key(row)
            if doc_id and key and key not in seen[doc_id]:
                grouped_rows[doc_id].append(row)
                seen[doc_id].add(key)
        return dict(grouped_rows)

    def support_for_document(self, doc_id: str, routes: list[list[dict[str, Any]]], identity: str) -> list[dict[str, Any]]:
        selected: list[dict[str, Any]] = []
        seen: set[str] = set()
        offsets = [0] * len(routes)
        while len(selected) < PRODUCTION_PROFILE.max_pages_per_document:
            progressed = False
            for position, route in enumerate(routes):
                while offsets[position] < len(route):
                    row = route[offsets[position]]
                    offsets[position] += 1
                    if str(row.get("doc_id") or "") != doc_id:
                        continue
                    key = str(row.get("_block_key") if identity == "logical" else child_key(row))
                    if not key or key in seen:
                        continue
                    selected.append(row)
                    seen.add(key)
                    progressed = True
                    break
                if len(selected) >= PRODUCTION_PROFILE.max_pages_per_document:
                    break
            if not progressed:
                break
        return selected

    @staticmethod
    def matrix(doc_ids: list[str], support: dict[str, list[dict[str, Any]]]) -> list[dict[str, Any]]:
        candidates: list[dict[str, Any]] = []
        offset = 0
        while len(candidates) < PRODUCTION_PROFILE.max_page_candidates:
            progressed = False
            for doc_id in doc_ids:
                rows = support.get(doc_id, [])
                if offset < len(rows):
                    candidates.append(rows[offset])
                    progressed = True
                    if len(candidates) >= PRODUCTION_PROFILE.max_page_candidates:
                        break
            if not progressed:
                break
            offset += 1
        return candidates

    def rank(self, query: str, pipeline: Pipeline) -> tuple[list[dict[str, Any]], dict[str, Any]]:
        started = time.perf_counter_ns()
        lexical = self.lexical(query, pipeline.logical_blocks) if pipeline.use_bm25 else []
        title = self.title_lexical(query, pipeline.logical_blocks) if pipeline.use_title_bm25 else []
        semantic = self.semantic(query, pipeline.logical_blocks) if pipeline.use_bge else []
        candidates = self.admitted_candidates(lexical, title, semantic, pipeline)
        rerank_model = ""
        rerank_ms = 0.0
        output = list(candidates)
        if pipeline.use_rerank and candidates:
            rerank_candidates = (
                self.retriever.child_evidence_candidates(candidates)
                if pipeline.child_evidence_rerank else candidates
            )
            documents = [
                self.retriever.rerank_document(query, row, pipeline.child_evidence_rerank)
                for row in rerank_candidates
            ]
            rerank_started = time.perf_counter_ns()
            scores, rerank_model = self.retriever.worker.rerank(query, documents)
            rerank_ms = (time.perf_counter_ns() - rerank_started) / 1_000_000
            output = [dict(row, _rerank_score=scores[index]) for index, row in enumerate(rerank_candidates)]
            output.sort(key=lambda row: (
                -score_or_negative_infinity(row.get("_rerank_score")),
                str(row.get("doc_id") or ""),
                str(row.get("chunk_id") or ""),
            ))
        return output[:TOP_HITS], {
            "elapsedMs": (time.perf_counter_ns() - started) / 1_000_000,
            "candidateCount": len(candidates),
            "rerankCandidateCount": len(output) if pipeline.use_rerank else 0,
            "rerankMs": rerank_ms,
            "rerankModel": rerank_model,
        }


def expected(case: dict[str, Any]) -> dict[str, Any]:
    source = case["source"]
    return {
        "docId": source.get("doc_id"),
        "pageNo": source.get("page_no"),
        "sectionId": source.get("section_id"),
        "sectionTitle": source.get("section_title"),
    }


def document_rank(hits: list[dict[str, Any]], expected_doc: Any) -> int | None:
    seen: set[str] = set()
    for rank, hit in enumerate(hits, 1):
        doc_id = str(hit.get("doc_id") or "")
        if doc_id in seen:
            continue
        seen.add(doc_id)
        if doc_id == str(expected_doc or ""):
            return len(seen)
    return None


def percentile95(values: list[float]) -> float:
    if not values:
        return 0.0
    return sorted(values)[max(0, int(len(values) * 0.95) - 1)]


def summarize(rows: list[dict[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {"caseCount": len(rows)}
    rank_fields = {
        "document": "documentRank",
        "logicalBlock": "logicalBlockRank",
        "strictSourcePageBlock": "strictSourcePageBlockRank",
    }
    for name, field in rank_fields.items():
        for cutoff in METRIC_CUTOFFS:
            result[f"{name}Recall@{cutoff}"] = sum(
                row[field] is not None and int(row[field]) <= cutoff for row in rows
            ) / len(rows) if rows else 0.0
        result[f"{name}MRR@10"] = statistics.fmean(
            1.0 / int(row[field]) if row[field] is not None and int(row[field]) <= 10 else 0.0
            for row in rows
        ) if rows else 0.0
    for field in ("elapsedMs", "rerankMs", "candidateCount", "rerankCandidateCount"):
        values = [float(row[field]) for row in rows]
        result[field] = {"average": statistics.fmean(values) if values else 0.0, "p95": percentile95(values)}
    return result


def serialize_hit(rank: int, hit: dict[str, Any], retriever: SectionBlockRetriever) -> dict[str, Any]:
    members = retriever.members_for_candidate(hit)
    return {
        "rank": rank,
        "docId": hit.get("doc_id"),
        "chunkId": hit.get("chunk_id"),
        "pageNo": hit.get("page_no"),
        "sectionId": hit.get("section_id"),
        "sectionTitle": hit.get("section_title"),
        "parentPageNos": sorted({int(row.get("page_no") or 0) for row in members}),
        "stage": hit.get("_stage"),
        "rerankScore": hit.get("_rerank_score"),
    }


def run(cases: list[dict[str, Any]], ablation: LogicalBlockAblation) -> tuple[dict[str, Any], list[dict[str, Any]]]:
    all_rows: list[dict[str, Any]] = []
    summaries: dict[str, Any] = {}
    for pipeline in PIPELINES:
        rows: list[dict[str, Any]] = []
        for case in cases:
            hits, timing = ablation.rank(str(case["query"]), pipeline)
            target = expected(case)
            payload = {"query": case["query"], "limit": TOP_HITS}
            if not validate_public_request_payload(payload):
                raise AssertionError("benchmark request includes a hidden scope field")
            row = {
                "caseId": case["caseId"],
                "pipeline": pipeline.name,
                "requestPayload": payload,
                "documentRank": document_rank(hits, target["docId"]),
                "logicalBlockRank": logical_block_rank(hits, target),
                "strictSourcePageBlockRank": strict_block_rank(hits, target),
                **timing,
                "hits": [serialize_hit(rank, hit, ablation.retriever) for rank, hit in enumerate(hits, 1)],
            }
            rows.append(row)
        summaries[pipeline.name] = summarize(rows)
        all_rows.extend(rows)
    return summaries, all_rows


def read_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def main() -> None:
    parser = argparse.ArgumentParser(description="Run real c2 logical-heading Parent Document Retriever ablations")
    parser.add_argument("--library-root", type=Path, default=DEFAULT_LIBRARY_ROOT)
    parser.add_argument("--cases", type=Path, default=DEFAULT_CASES)
    parser.add_argument("--output-dir", type=Path, default=DEFAULT_OUTPUT)
    args = parser.parse_args()
    cases = read_json(args.cases)
    if len(cases) != 46:
        raise ValueError(f"immutable contract requires 46 cases, got {len(cases)}")
    if len({str(case.get('caseId') or '') for case in cases}) != len(cases):
        raise ValueError("case ids must be unique")
    worker = RealWorker(WORKER_BASE_URL, WORKER_KEY_FILE)
    sampler = PeakRssSampler()
    retriever = SectionBlockRetriever(args.library_root, worker)
    sampler.start()
    summaries, rows = run(cases, LogicalBlockAblation(retriever))
    resources = sampler.finish()
    output = args.output_dir
    output.mkdir(parents=True, exist_ok=True)
    report = {
        "kind": "immutable_46_c2_logical_heading_parent_document_ablation",
        "corpus": {
            "root": str(args.library_root.resolve()),
            "rows": len(retriever.rows),
            "logicalBlockCount": len(retriever.block_index.members_by_key),
            "fingerprint": corpus_fingerprint(args.library_root),
        },
        "caseContract": {"path": str(args.cases.resolve()), "caseCount": len(cases), "caseIds": [case["caseId"] for case in cases]},
        "publicRequestFields": sorted(PUBLIC_REQUEST_FIELDS),
        "graphExpansion": False,
        "pipelineDefinitions": [asdict(pipeline) for pipeline in PIPELINES],
        "productionBudget": {
            "maxDocumentCandidates": PRODUCTION_PROFILE.max_document_candidates,
            "maxBlocksPerDocument": PRODUCTION_PROFILE.max_pages_per_document,
            "maxRerankDocuments": PRODUCTION_PROFILE.max_rerank_documents,
            "maxRerankCandidates": PRODUCTION_PROFILE.max_page_candidates,
        },
        "resources": resources,
        "summaries": summaries,
    }
    (output / "results.json").write_text(json.dumps(rows, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (output / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
