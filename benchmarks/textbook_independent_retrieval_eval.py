"""Evaluate current and structurally expanded textbook retrieval on 110 cases.

This is a Python-first production-shape prototype. Public retrieval receives only
``query`` and ``limit``. Expected document/page/title labels are read only after
retrieval returns, inside the metric functions.
"""

from __future__ import annotations

import argparse
import importlib.util
import json
import math
import re
import statistics
import sys
import time
from collections import defaultdict
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable

import requests

if __package__ in {None, ""}:
    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from benchmarks.build_textbook_independent_eval_set import (
    DEFAULT_LIBRARY_ROOT,
    DEFAULT_OUTPUT as DEFAULT_CASE_ROOT,
    compact,
    corpus_fingerprint,
    corpus_rows,
    normalized_text,
)
from benchmarks.textbook_ablation_eval import (
    PRODUCTION_PROFILE,
    WORKER_BASE_URL,
    WORKER_KEY_FILE,
    semantic_page_text,
)


DEFAULT_OUTPUT = Path("output/benchmarks/textbook-independent-110-prototype-v1")
METRIC_CUTOFFS = (1, 3, 5, 10)

# This is a Chinese tokenization boundary, not a tuned relevance score. A
# one-character overlap is not independently auditable evidence; two or more
# characters form the smallest lexical anchor accepted by this prototype.
MIN_GROUNDING_ANCHOR_CHARACTERS = 2
QUERY_WRAPPER = re.compile(r"请查找教材中关于(.+?)的相关内容")
ANCHOR_SPLITTER = re.compile(r"[\s,，。；;：:！？!?()（）\[\]【】/与和及]+")


@dataclass(frozen=True)
class WorkerPageHit:
    score: float
    chunk_id: str
    section_id: str
    doc_id: str
    page_no: int
    section_title: str
    raw: dict[str, Any]


@dataclass(frozen=True)
class RetrievalResult:
    hits: list[dict[str, Any]]
    candidate_count: int
    elapsed_ms: float
    rerank_model: str
    abstained: bool


class RealWorker:
    """Authenticated BGE page search and cross-encoder client."""

    def __init__(self, base_url: str, key_file: Path) -> None:
        key = key_file.read_text(encoding="utf-8").strip()
        if not key:
            raise RuntimeError(f"worker key is empty: {key_file}")
        self.base_url = base_url.rstrip("/")
        self.session = requests.Session()
        self.headers = {"Authorization": "Bearer " + key}

    def page_search(self, query: str, limit: int) -> list[WorkerPageHit]:
        response = self.session.post(
            self.base_url + "/text/page-search",
            headers=self.headers,
            json={"query": query, "limit": limit},
            timeout=90,
        )
        response.raise_for_status()
        hits: list[WorkerPageHit] = []
        for item in response.json().get("hits", []):
            hits.append(WorkerPageHit(
                score=float(item.get("score") or 0.0),
                chunk_id=str(item.get("chunkId") or item.get("sourceChunkId") or ""),
                section_id=str(item.get("sectionId") or ""),
                doc_id=str(item.get("docId") or ""),
                page_no=int(item.get("pageNo") or 0),
                section_title=str(item.get("sectionTitle") or ""),
                raw=item,
            ))
        return hits

    def rerank(self, query: str, documents: list[str]) -> tuple[list[float], str]:
        if not documents:
            return [], ""
        response = self.session.post(
            self.base_url + "/rerank",
            headers=self.headers,
            json={"query": query, "documents": documents},
            timeout=180,
        )
        response.raise_for_status()
        body = response.json()
        scores = [float(item.get("score") or 0.0) for item in body.get("data", [])]
        if len(scores) != len(documents):
            raise RuntimeError(f"reranker returned {len(scores)} scores for {len(documents)} documents")
        return scores, str(body.get("model") or "")


def read_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def write_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def load_bm25_module(library_root: Path) -> Any:
    """Load the corpus owner's real BM25 implementation without copying it."""
    path = library_root.parent / "OCR测试方案" / "bm25_index.py"
    # bm25_index imports its sibling package by the repository-level package
    # name, so the parser repository root must be visible during dynamic load.
    parser_root = str(library_root.parent)
    if parser_root not in sys.path:
        sys.path.insert(0, parser_root)
    spec = importlib.util.spec_from_file_location("textbook_independent_bm25", path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"cannot load BM25 module: {path}")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


def candidate_key(row: dict[str, Any]) -> str:
    return "#".join((
        str(row.get("doc_id") or ""),
        str(int(row.get("page_no") or 0)),
        str(row.get("section_id") or row.get("chunk_id") or ""),
    ))


def visible_title(value: Any) -> str:
    """Normalize OCR spacing and a CJK-attached printed-page suffix."""
    return re.sub(r"(?<=[\u4e00-\u9fff])\d{1,3}$", "", compact(value))


def title_topic(value: Any) -> str:
    """Remove only a leading outline number while preserving title semantics."""
    title = visible_title(value)
    return re.sub(r"^(?:第[一二三四五六七八九十百]+[章节]|[0-9]+(?:\.[0-9]+)*[章节]?)", "", title)


def chunk_key(row: dict[str, Any]) -> str:
    return str(row.get("chunk_id") or candidate_key(row))


def with_stage(row: dict[str, Any], stage: str, score: float) -> dict[str, Any]:
    result = dict(row)
    result["_stage"] = stage
    result["_score"] = float(score)
    return result


def grouped_by_document(rows: Iterable[dict[str, Any]]) -> dict[str, list[dict[str, Any]]]:
    grouped: dict[str, list[dict[str, Any]]] = defaultdict(list)
    seen: dict[str, set[str]] = defaultdict(set)
    for row in rows:
        doc_id = str(row.get("doc_id") or "")
        key = chunk_key(row)
        if doc_id and key not in seen[doc_id]:
            grouped[doc_id].append(row)
            seen[doc_id].add(key)
    return dict(grouped)


def interleave_routes(routes: list[list[str]], limit: int) -> list[str]:
    """Fuse route-local ranks without adding incomparable BM25/BGE scores."""
    selected: list[str] = []
    seen: set[str] = set()
    offset = 0
    while len(selected) < limit and any(offset < len(route) for route in routes):
        for route in routes:
            if offset < len(route) and route[offset] and route[offset] not in seen:
                selected.append(route[offset])
                seen.add(route[offset])
                if len(selected) >= limit:
                    break
        offset += 1
    return selected


def evidence_representative(rows: list[dict[str, Any]]) -> dict[str, Any]:
    """Use real explanatory text instead of an empty sibling heading."""
    return max(
        rows,
        key=lambda row: (
            len(normalized_text(row.get("text"))) + len(normalized_text(row.get("formula_text"))),
            str(row.get("chunk_id") or ""),
        ),
    )


class PrototypeRetriever:
    """Current-shape baseline and the proposed structure-aware candidate path."""

    def __init__(self, library_root: Path, worker: RealWorker) -> None:
        self.library_root = library_root
        self.worker = worker
        self.rows = corpus_rows(library_root)
        self.bm25_module = load_bm25_module(library_root)
        self.body_index = self.bm25_module.build_bm25_index(self.rows)

        # A separate title field is a standard independent retrieval route. It
        # shares the corpus and BM25 implementation but carries no body text.
        self.title_rows = [dict(
            row,
            book_name="",
            chapter_path=[],
            text="",
            formula_text="",
        ) for row in self.rows]
        self.title_index = self.bm25_module.build_bm25_index(self.title_rows)

        self.rows_by_chunk = {
            str(row.get("chunk_id") or ""): row
            for row in self.rows
            if str(row.get("chunk_id") or "")
        }
        self.rows_by_doc_page: dict[tuple[str, int], list[dict[str, Any]]] = defaultdict(list)
        self.rows_by_section: dict[tuple[str, str], list[dict[str, Any]]] = defaultdict(list)
        for row in self.rows:
            doc_id = str(row.get("doc_id") or "")
            page_no = int(row.get("page_no") or 0)
            section_id = str(row.get("section_id") or "")
            self.rows_by_doc_page[(doc_id, page_no)].append(row)
            if section_id:
                self.rows_by_section[(doc_id, section_id)].append(row)

    def bm25_rows(self, index: Any, query: str, stage: str) -> list[dict[str, Any]]:
        hits, _ = index.search(query, limit=len(index.rows))
        ranked: list[dict[str, Any]] = []
        for hit in sorted(hits, key=lambda item: item.score, reverse=True):
            source = hit.row
            if stage == "title_bm25":
                # The title-only index intentionally stores blank body fields.
                # Map the winning identity back to the untouched corpus row so
                # the cross-encoder receives real explanatory evidence.
                source = self.rows_by_chunk.get(str(hit.row.get("chunk_id") or ""), hit.row)
            ranked.append(with_stage(source, stage, float(hit.score)))
        return ranked

    def resolve_worker_hit(self, hit: WorkerPageHit) -> list[dict[str, Any]]:
        """Map worker metadata back to current corpus rows on the hit page."""
        if hit.chunk_id and hit.chunk_id in self.rows_by_chunk:
            exact = self.rows_by_chunk[hit.chunk_id]
            if int(exact.get("page_no") or 0) == hit.page_no:
                return [with_stage(exact, "bge", hit.score)]
        page_rows = self.rows_by_doc_page.get((hit.doc_id, hit.page_no), [])
        section_rows = [
            row for row in page_rows
            if hit.section_id and str(row.get("section_id") or "") == hit.section_id
        ]
        candidates = section_rows or page_rows
        if not candidates:
            return []
        return [with_stage(evidence_representative(candidates), "bge", hit.score)]

    def semantic_rows(self, query: str) -> list[dict[str, Any]]:
        hits = self.worker.page_search(
            query,
            PRODUCTION_PROFILE.max_document_candidates * PRODUCTION_PROFILE.max_pages_per_document,
        )
        rows: list[dict[str, Any]] = []
        for hit in hits:
            rows.extend(self.resolve_worker_hit(hit))
        return rows

    def expand_section_pages(self, row: dict[str, Any], title_route: bool) -> list[dict[str, Any]]:
        """Expose every real page of an admitted logical section to stage two.

        Title hits start at the section's first page. Body/BGE hits keep their
        matched page first, followed by nearest continuation pages. No target
        page is provided to this method.
        """
        doc_id = str(row.get("doc_id") or "")
        section_id = str(row.get("section_id") or "")
        page_no = int(row.get("page_no") or 0)
        admitted_title = visible_title(row.get("section_title"))
        section_rows = self.rows_by_section.get((doc_id, section_id), [row]) if section_id else [row]
        # Some legacy section ids cover multiple visible headings. Expansion is
        # valid only inside the user-visible logical heading, otherwise a real
        # hit is silently replaced by a different block on the same page.
        source = [
            sibling for sibling in section_rows
            if visible_title(sibling.get("section_title")) == admitted_title
        ] or [row]
        by_page: dict[int, list[dict[str, Any]]] = defaultdict(list)
        for sibling in source:
            by_page[int(sibling.get("page_no") or 0)].append(sibling)
        pages = sorted(by_page) if title_route else sorted(by_page, key=lambda value: (abs(value - page_no), value))
        expanded: list[dict[str, Any]] = []
        for sibling_page in pages:
            # Preserve the admitted hit on its own page. Other continuation
            # pages use their strongest real evidence row under the same title.
            representative = row if sibling_page == page_no else evidence_representative(by_page[sibling_page])
            expanded.append(with_stage(
                representative,
                str(row.get("_stage") or "") + "_section_page",
                float(row.get("_score") or 0.0),
            ))
        return expanded

    def route_pages(self, rows: list[dict[str, Any]], doc_id: str, title_route: bool) -> list[dict[str, Any]]:
        selected: list[dict[str, Any]] = []
        seen: set[str] = set()
        for row in rows:
            if str(row.get("doc_id") or "") != doc_id:
                continue
            # A body or BGE page hit is evidence for that page only. Cross-page
            # expansion is reserved for a strong full-title match.
            candidates = self.expand_section_pages(row, True) if title_route else [row]
            for candidate in candidates:
                key = candidate_key(candidate)
                if key not in seen:
                    selected.append(candidate)
                    seen.add(key)
        return selected

    @staticmethod
    def take_round_robin(routes: list[list[dict[str, Any]]], limit: int) -> list[dict[str, Any]]:
        selected: list[dict[str, Any]] = []
        seen: set[str] = set()
        offsets = [0 for _ in routes]
        while len(selected) < limit:
            progressed = False
            for route_index, route in enumerate(routes):
                while offsets[route_index] < len(route):
                    candidate = route[offsets[route_index]]
                    offsets[route_index] += 1
                    key = candidate_key(candidate)
                    if key in seen:
                        continue
                    selected.append(candidate)
                    seen.add(key)
                    progressed = True
                    break
                if len(selected) >= limit:
                    break
            if not progressed:
                break
        return selected

    def page_matrix(
        self,
        doc_ids: list[str],
        support_by_doc: dict[str, list[dict[str, Any]]],
    ) -> list[dict[str, Any]]:
        selected: list[dict[str, Any]] = []
        offset = 0
        while len(selected) < PRODUCTION_PROFILE.max_page_candidates:
            progressed = False
            for doc_id in doc_ids:
                rows = support_by_doc.get(doc_id, [])
                if offset < len(rows):
                    selected.append(rows[offset])
                    progressed = True
                    if len(selected) >= PRODUCTION_PROFILE.max_page_candidates:
                        break
            if not progressed:
                break
            offset += 1
        return selected

    def baseline_candidates(
        self,
        body_rows: list[dict[str, Any]],
        semantic_rows: list[dict[str, Any]],
    ) -> list[dict[str, Any]]:
        body_docs = grouped_by_document(body_rows)
        semantic_docs = grouped_by_document(semantic_rows)
        lexical_doc_ids = list(body_docs)[: PRODUCTION_PROFILE.max_document_candidates]
        semantic_doc_ids = list(semantic_docs)
        doc_ids = interleave_routes(
            [lexical_doc_ids, semantic_doc_ids],
            PRODUCTION_PROFILE.max_rerank_documents,
        )
        support: dict[str, list[dict[str, Any]]] = {}
        for doc_id in doc_ids:
            support[doc_id] = self.take_round_robin(
                [body_docs.get(doc_id, []), semantic_docs.get(doc_id, [])],
                PRODUCTION_PROFILE.max_pages_per_document,
            )
        return self.page_matrix(doc_ids, support)

    def optimized_candidates(
        self,
        query: str,
        title_rows: list[dict[str, Any]],
        body_rows: list[dict[str, Any]],
        semantic_rows: list[dict[str, Any]],
    ) -> list[dict[str, Any]]:
        title_rows = [row for row in title_rows if title_grounded(query, row)]
        title_docs = grouped_by_document(title_rows)
        body_docs = grouped_by_document(body_rows)
        semantic_docs = grouped_by_document(semantic_rows)
        doc_ids = interleave_routes(
            [
                list(body_docs)[: PRODUCTION_PROFILE.max_document_candidates],
                list(semantic_docs),
            ],
            PRODUCTION_PROFILE.max_rerank_documents,
        )
        support: dict[str, list[dict[str, Any]]] = {}
        for doc_id in doc_ids:
            support[doc_id] = self.take_round_robin(
                [
                    self.route_pages(body_rows, doc_id, False),
                    self.route_pages(semantic_rows, doc_id, False),
                    self.route_pages(title_rows, doc_id, True),
                ],
                PRODUCTION_PROFILE.max_pages_per_document,
            )
        return self.page_matrix(doc_ids, support)

    def rerank_union(
        self,
        query: str,
        baseline: list[dict[str, Any]],
        optimized: list[dict[str, Any]],
    ) -> tuple[dict[str, float], str]:
        union: dict[str, dict[str, Any]] = {}
        for row in baseline + optimized:
            union.setdefault(candidate_key(row), row)
        keys = list(union)
        documents = [semantic_page_text(query, union[key], PRODUCTION_PROFILE) for key in keys]
        scores, model = self.worker.rerank(query, documents)
        return {key: scores[index] for index, key in enumerate(keys)}, model

    def retrieve(self, query: str, limit: int) -> dict[str, RetrievalResult]:
        """Retrieve without any expected document, page, or block identity."""
        started = time.perf_counter_ns()
        title_rows = self.bm25_rows(self.title_index, query, "title_bm25")
        body_rows = self.bm25_rows(self.body_index, query, "body_bm25")
        semantic_rows = self.semantic_rows(query)
        baseline_candidates = self.baseline_candidates(body_rows, semantic_rows)
        optimized_candidates = self.optimized_candidates(query, title_rows, body_rows, semantic_rows)
        scores, model = self.rerank_union(query, baseline_candidates, optimized_candidates)

        def ranked(candidates: list[dict[str, Any]], abstain: bool) -> list[dict[str, Any]]:
            rows = [dict(row, _rerank_score=scores.get(candidate_key(row), float("-inf"))) for row in candidates]
            rows.sort(key=lambda row: (
                -score_or_negative_infinity(row.get("_rerank_score")),
                str(row.get("doc_id") or ""),
                int(row.get("page_no") or 0),
            ))
            if abstain:
                # A single evidence-bearing block validates the textbook, but
                # sibling pages from that same book remain eligible for final
                # ordering. Filtering each sibling independently would turn a
                # negative rerank logit on one page into a false document miss.
                grounded_documents = {
                    str(row.get("doc_id") or "")
                    for row in rows
                    if grounded(query, row)
                }
                rows = [
                    row for row in rows
                    if str(row.get("doc_id") or "") in grounded_documents
                ]
            return rows[: max(1, limit)]

        elapsed_ms = (time.perf_counter_ns() - started) / 1_000_000
        baseline_hits = ranked(baseline_candidates, False)
        optimized_hits = ranked(optimized_candidates, True)
        return {
            "baseline": RetrievalResult(baseline_hits, len(baseline_candidates), elapsed_ms, model, False),
            "optimized": RetrievalResult(optimized_hits, len(optimized_candidates), elapsed_ms, model, not optimized_hits),
        }


def query_anchors(query: str) -> list[str]:
    """Extract auditable lexical evidence spans from a natural Chinese query."""
    normalized = normalized_text(query)
    wrapper = QUERY_WRAPPER.search(normalized)
    if wrapper:
        normalized = wrapper.group(1)
    anchors: list[str] = []
    for part in ANCHOR_SPLITTER.split(normalized):
        value = compact(part)
        if len(value) >= MIN_GROUNDING_ANCHOR_CHARACTERS and value not in anchors:
            anchors.append(value)
    return sorted(anchors, key=lambda value: (-len(value), value))


def grounded(query: str, row: dict[str, Any]) -> bool:
    """Keep a result only with lexical evidence or non-negative model evidence.

    BGE reranker logits are not calibrated probabilities, so a negative logit
    alone cannot reject a candidate such as a title-exact independence-test
    page. Conversely, an out-of-domain semantic nearest neighbour with no query
    phrase and a negative logit is not real evidence and must not be returned.
    """
    surface = compact(" ".join((
        str(row.get("section_title") or ""),
        str(row.get("text") or ""),
        str(row.get("formula_text") or ""),
    )))
    lexical_evidence = any(anchor in surface for anchor in query_anchors(query))
    return lexical_evidence or score_or_negative_infinity(row.get("_rerank_score")) >= 0.0


def title_grounded(query: str, row: dict[str, Any]) -> bool:
    """Admit the title route only for a complete visible-title topic match."""
    topic = title_topic(row.get("section_title"))
    query_text = compact(query)
    return (
        len(topic) >= MIN_GROUNDING_ANCHOR_CHARACTERS
        and topic in query_text
        and title_only_query(query, row)
    )


def title_only_query(query: str, row: dict[str, Any]) -> bool:
    """Recognize a title lookup without classifying a topic-specific evidence query."""
    normalized = normalized_text(query)
    wrapper = QUERY_WRAPPER.search(normalized)
    if wrapper:
        normalized = wrapper.group(1)
    query_text = compact(normalized)
    full_title = compact(row.get("section_title"))
    topic = title_topic(row.get("section_title"))
    return query_text in {full_title, visible_title(row.get("section_title")), topic}


def score_or_negative_infinity(value: Any) -> float:
    """Preserve a real zero logit while treating only missing scores as absent."""
    if value is None:
        return float("-inf")
    try:
        return float(value)
    except (TypeError, ValueError):
        return float("-inf")


def hit_payload(rank: int, row: dict[str, Any]) -> dict[str, Any]:
    return {
        "rank": rank,
        "docId": row.get("doc_id"),
        "pageNo": int(row.get("page_no") or 0),
        "sectionTitle": row.get("section_title"),
        "sectionId": row.get("section_id"),
        "chunkId": row.get("chunk_id"),
        "stage": row.get("_stage"),
        "rerankScore": row.get("_rerank_score"),
        "text": normalized_text(row.get("text"))[:240],
    }


def distinct_document_rank(hits: list[dict[str, Any]], expected_doc_id: str) -> int | None:
    seen: set[str] = set()
    rank = 0
    for hit in hits:
        doc_id = str(hit.get("doc_id") or "")
        if not doc_id or doc_id in seen:
            continue
        seen.add(doc_id)
        rank += 1
        if doc_id == expected_doc_id:
            return rank
    return None


def strict_block_rank(hits: list[dict[str, Any]], expected: dict[str, Any]) -> int | None:
    identity = (str(expected["docId"]), int(expected["pageNo"]), compact(expected["sectionTitle"]))
    for rank, hit in enumerate(hits, 1):
        candidate = (
            str(hit.get("doc_id") or ""),
            int(hit.get("page_no") or 0),
            compact(hit.get("section_title")),
        )
        if candidate == identity:
            return rank
    return None


def summarize(rows: list[dict[str, Any]], route: str) -> dict[str, Any]:
    positives = [row for row in rows if row["polarity"] == "positive"]
    negatives = [row for row in rows if row["polarity"] == "negative"]
    summary: dict[str, Any] = {
        "route": route,
        "positiveCount": len(positives),
        "negativeCount": len(negatives),
    }
    for metric, field in (("document", "documentRank"), ("block", "blockRank")):
        for cutoff in METRIC_CUTOFFS:
            summary[f"{metric}Recall@{cutoff}"] = sum(
                row[field] is not None and int(row[field]) <= cutoff for row in positives
            ) / len(positives)
    summary["blockMRR@10"] = statistics.fmean(
        1.0 / int(row["blockRank"])
        if row["blockRank"] is not None and int(row["blockRank"]) <= 10 else 0.0
        for row in positives
    )
    summary["negativeEmptyRate"] = sum(row["hitCount"] == 0 for row in negatives) / len(negatives)
    summary["negativeFalsePositiveRate"] = 1.0 - summary["negativeEmptyRate"]
    summary["latencyMs"] = {
        "average": statistics.fmean(float(row["elapsedMs"]) for row in rows),
        "p95": sorted(float(row["elapsedMs"]) for row in rows)[max(0, math.ceil(len(rows) * 0.95) - 1)],
    }
    summary["byDimension"] = {}
    for dimension in sorted({row["dimension"] for row in positives}):
        items = [row for row in positives if row["dimension"] == dimension]
        summary["byDimension"][dimension] = {
            "count": len(items),
            "documentRecall@1": sum(row["documentRank"] == 1 for row in items) / len(items),
            "blockRecall@1": sum(row["blockRank"] == 1 for row in items) / len(items),
            "blockRecall@3": sum(
                row["blockRank"] is not None and int(row["blockRank"]) <= 3 for row in items
            ) / len(items),
        }
    return summary


def markdown(report: dict[str, Any]) -> str:
    lines = [
        "# 独立 110 条全库教材检索评测",
        "",
        "100 条正例、10 条经全语料确认不存在的负例；所有公开检索调用只包含 query 与 limit。",
        "",
        "| 路线 | doc@1 | doc@3 | block@1 | block@3 | block MRR@10 | 负例空返回 | 平均/P95 ms |",
        "|---|---:|---:|---:|---:|---:|---:|---:|",
    ]
    for route in ("baseline", "optimized"):
        item = report["summaries"][route]
        lines.append(
            f"| {route} | {item['documentRecall@1']:.3f} | {item['documentRecall@3']:.3f} "
            f"| {item['blockRecall@1']:.3f} | {item['blockRecall@3']:.3f} | {item['blockMRR@10']:.3f} "
            f"| {item['negativeEmptyRate']:.3f} | {item['latencyMs']['average']:.1f}/{item['latencyMs']['p95']:.1f} |"
        )
    lines.extend(["", "## 维度", "", "| 路线 | 维度 | 数量 | doc@1 | block@1 | block@3 |", "|---|---|---:|---:|---:|---:|"])
    for route in ("baseline", "optimized"):
        for dimension, item in report["summaries"][route]["byDimension"].items():
            lines.append(
                f"| {route} | {dimension} | {item['count']} | {item['documentRecall@1']:.3f} "
                f"| {item['blockRecall@1']:.3f} | {item['blockRecall@3']:.3f} |"
            )
    return "\n".join(lines) + "\n"


def main() -> None:
    parser = argparse.ArgumentParser(description="Run one independent Python-first textbook retrieval evaluation")
    parser.add_argument("--library-root", type=Path, default=DEFAULT_LIBRARY_ROOT)
    parser.add_argument("--cases", type=Path, default=DEFAULT_CASE_ROOT / "cases.json")
    parser.add_argument("--manifest", type=Path, default=DEFAULT_CASE_ROOT / "manifest.json")
    parser.add_argument("--output-dir", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--limit", type=int, default=10)
    args = parser.parse_args()

    library_root = args.library_root.expanduser().resolve()
    cases = read_json(args.cases.expanduser().resolve())
    manifest = read_json(args.manifest.expanduser().resolve())
    if manifest.get("corpusFingerprint") != corpus_fingerprint(library_root):
        raise RuntimeError("case-set corpus fingerprint does not match the current complete library")
    if len(cases) != 110 or len({compact(case["query"]) for case in cases}) != 110:
        raise RuntimeError("independent evaluation requires exactly 110 unique queries")

    retriever = PrototypeRetriever(library_root, RealWorker(WORKER_BASE_URL, WORKER_KEY_FILE))
    result_rows: list[dict[str, Any]] = []
    for case in cases:
        # This is the only public retrieval boundary. Expected labels stay below
        # this call and therefore cannot influence candidate generation.
        route_results = retriever.retrieve(str(case["query"]), max(1, args.limit))
        for route, result in route_results.items():
            expected = case.get("expected")
            document_rank = distinct_document_rank(result.hits, str(expected["docId"])) if expected else None
            block_rank = strict_block_rank(result.hits, expected) if expected else None
            result_rows.append({
                "caseId": case["caseId"],
                "polarity": case["polarity"],
                "dimension": case["dimension"],
                "query": case["query"],
                "requestPayload": {"query": case["query"], "limit": max(1, args.limit)},
                "route": route,
                "documentRank": document_rank,
                "blockRank": block_rank,
                "hitCount": len(result.hits),
                "candidateCount": result.candidate_count,
                "abstained": result.abstained,
                "elapsedMs": round(result.elapsed_ms, 3),
                "rerankModel": result.rerank_model,
                "hits": [hit_payload(rank, hit) for rank, hit in enumerate(result.hits, 1)],
            })

    report = {
        "kind": "independent_full_library_python_prototype_evaluation",
        "caseManifest": manifest,
        "caseCount": len(cases),
        "publicRequestFields": ["query", "limit"],
        "profile": {
            "maxDocumentCandidates": PRODUCTION_PROFILE.max_document_candidates,
            "maxPagesPerDocument": PRODUCTION_PROFILE.max_pages_per_document,
            "maxRerankDocuments": PRODUCTION_PROFILE.max_rerank_documents,
            "maxPageCandidates": PRODUCTION_PROFILE.max_page_candidates,
            "pageTextChars": PRODUCTION_PROFILE.page_text_chars,
            "formulaTextChars": PRODUCTION_PROFILE.formula_text_chars,
        },
        "groundingContract": {
            "minimumAnchorCharacters": MIN_GROUNDING_ANCHOR_CHARACTERS,
            "acceptWhen": "real lexical anchor exists in returned evidence OR cross-encoder logit is non-negative",
            "negativeQueriesVerifiedAbsentFromCorpus": True,
        },
        "summaries": {
            route: summarize([row for row in result_rows if row["route"] == route], route)
            for route in ("baseline", "optimized")
        },
    }
    output = args.output_dir.expanduser().resolve()
    write_json(output / "results.json", result_rows)
    write_json(output / "report.json", report)
    (output / "summary.md").write_text(markdown(report), encoding="utf-8")
    print(json.dumps(report["summaries"], ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
