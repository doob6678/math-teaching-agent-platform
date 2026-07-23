"""Production-worker textbook retrieval ablation across every page-library textbook.

The script uses the real local worker for BGE embeddings and BGE reranking, the real page corpus/BM25 index on disk,
and Luna only for query construction plus blind relevance auditing.  It intentionally keeps section-corpus results
separate because the available section library currently covers only the B-version selective compulsory third book.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import time
from collections import defaultdict
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass
from functools import lru_cache
from datetime import datetime
from pathlib import Path
from typing import Any

import numpy as np
import requests

if __package__ in {None, ""}:
    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from benchmarks.textbook_split_eval import (
    DEFAULT_BGE_MODEL,
    DEFAULT_LIBRARY_PARENT,
    DEFAULT_SECTION_BOOK,
    DEFAULT_SECTION_ROOT_NAME,
    DEFAULT_LLM_MODEL,
    DEFAULT_PAGE_ROOT_NAME,
    MAX_SOURCE_TEXT_LENGTH,
    compact,
    construct_queries,
    llm_config,
    luna_json,
    read_jsonl,
    row_text,
    source_is_valid,
    write_json,
)


CASE_COUNT = 40
CASES_PER_BOOK = 5
RECALL_CUTOFFS = (1, 3, 5)
WORKER_BASE_URL = os.environ.get("MATH_AGENT_EMBEDDING_BASE_URL", "http://127.0.0.1:8091/v1").rstrip("/")
WORKER_KEY_FILE = Path(".local-secrets/worker-api-key.txt")
TOP_HITS = 10
# One query per Luna call keeps the 9-config before/after evidence payload below the model context limit and makes
# the audit genuinely per-case rather than an aggregate approximation. Four independent calls reduce wall time while
# preserving one complete audit object per query; the value can be set to 1 for strict serial auditing.
AUDIT_BATCH_SIZE = 1
LUNA_AUDIT_WORKERS = max(1, int(os.environ.get("LUNA_AUDIT_WORKERS", "4")))

# Evaluation sampling policy: cover real正文 rather than allowing cover/目录 pages to dominate the benchmark.
# These are named corpus-quality gates, not relevance tuning knobs; fallback rows are still used for tiny books.
EVAL_MIN_PAGE_NO = 6
EVAL_PAGE_TEXT_MIN_CHARS = 200
EVAL_SECTION_TEXT_MIN_CHARS = 120
GRAPH_SPINE_RELATIVE_PATH = Path("backend-java/src/main/resources/knowledge/graph-spine-v0.1.md")
# Mirrors TextbookRetrievalProperties.queryFocus.maxGraphTags: graph expansion is bounded and never weighted into
# BM25/BGE scores, so graph presence cannot swamp the evidence routes.
GRAPH_EXPANSION_LIMIT = 4
RECALL_WORKERS = 2

# These are business regression queries reported during manual textbook search.  They are anchored to real rows
# from the filtered page corpus and are appended only when --include-business-cases is requested.  They are kept
# separate from the diverse body sample so a user-facing query cannot silently replace coverage for another book or
# be mistaken for a score-tuning keyword list.
BUSINESS_CASE_SPECS = (
    {
        "query": "导数光学",
        "doc_id": "math_b_xuanze_bixiu_3",
        "page_no": 113,
        "required_text": "利用导数来推导光的折射定律",
    },
    {
        "query": "导数和光学",
        "doc_id": "math_b_xuanze_bixiu_3",
        "page_no": 113,
        "required_text": "利用导数来推导光的折射定律",
    },
    {
        "query": "利用导数来推导光的折射定律",
        "doc_id": "math_b_xuanze_bixiu_3",
        "page_no": 113,
        "required_text": "利用导数来推导光的折射定律",
    },
    {
        "query": "卡方与独立性检验",
        "doc_id": "math_b_xuanze_bixiu_2",
        "page_no": 123,
        "required_text": "独立性检验",
    },
    {
        "query": "双曲线定义",
        "doc_id": "math_b_xuanze_bixiu_1",
        "page_no": 151,
        "required_text": "双曲线",
    },
    {
        "query": "指数函数与幂函数",
        "doc_id": "renjiao_bbixiu2math",
        "page_no": 11,
        "required_text": "指数函数、对数函数与幂函数",
    },
)


@dataclass(frozen=True)
class RetrievalProfile:
    """Candidate and payload limits copied from the production Java retrieval budget.

    The named profiles make every quality/latency tradeoff explicit in reports.  The production profile is the
    current Java default; speed and industry profiles are bounded alternatives, not hidden per-query tuning.
    """

    name: str
    max_document_candidates: int
    max_pages_per_document: int
    max_rerank_documents: int
    max_page_candidates: int
    page_text_chars: int
    formula_text_chars: int


# Keep this profile byte-for-byte aligned with application.yml and
# TextbookRetrievalProperties.defaults().  A prior three-document experiment
# was useful for diagnosis, but reporting it as production would compare a
# different candidate budget with the Java service that users actually call.
PRODUCTION_PROFILE = RetrievalProfile("production", 3, 3, 3, 9, 120, 40)
SPEED_PROFILE = RetrievalProfile("speed", 2, 2, 1, 3, 80, 24)
INDUSTRY_PROFILE = RetrievalProfile("industry_quality", 5, 3, 3, 8, 160, 60)


@dataclass(frozen=True)
class PipelineSpec:
    """One real ablation route; BM25 and BGE are independent stage-one evidence branches."""

    name: str
    use_bm25: bool
    use_bge: bool
    use_rerank: bool
    parallel_recall: bool
    profile: RetrievalProfile
    description: str
    graph_expand: bool = False


PIPELINE_SPECS = (
    PipelineSpec("bm25", True, False, False, False, PRODUCTION_PROFILE, "BM25 lexical baseline"),
    PipelineSpec("bge", False, True, False, False, PRODUCTION_PROFILE, "BGE page embedding baseline"),
    PipelineSpec("hybrid", True, True, False, False, PRODUCTION_PROFILE, "BM25+BGE serial candidate union"),
    PipelineSpec("hybrid_parallel", True, True, False, True, PRODUCTION_PROFILE, "BM25+BGE parallel candidate union"),
    PipelineSpec("bge_rerank", False, True, True, False, PRODUCTION_PROFILE, "BGE candidates with production final rerank"),
    PipelineSpec("bm25_rerank", True, False, True, False, PRODUCTION_PROFILE, "BM25 candidates with production final rerank"),
    PipelineSpec("hybrid_rerank", True, True, True, False, PRODUCTION_PROFILE, "Production Java-shaped serial two-stage route"),
    PipelineSpec("hybrid_rerank_parallel_speed", True, True, True, True, SPEED_PROFILE, "Parallel recall, speed-priority budget"),
    PipelineSpec("hybrid_rerank_parallel_industry", True, True, True, True, INDUSTRY_PROFILE, "Parallel recall, industry quality-priority budget"),
    PipelineSpec("graph_hybrid", True, True, False, False, PRODUCTION_PROFILE, "Graph-expanded BM25+BGE candidate union", True),
    PipelineSpec("graph_hybrid_rerank", True, True, True, False, PRODUCTION_PROFILE, "Graph-expanded production two-stage route", True),
)
CONFIGS = tuple(spec.name for spec in PIPELINE_SPECS)
SPEC_BY_NAME = {spec.name: spec for spec in PIPELINE_SPECS}
AUDIT_CONFIG_ALIASES = {
    # Luna occasionally shortens the human-readable industry profile name; normalize it before aggregation so a
    # valid case never contributes an implicit zero just because the model used an equivalent key.
    "hybrid_rerank_industry": "hybrid_rerank_parallel_industry",
}


def load_page_rows(parent: Path) -> list[dict[str, Any]]:
    root = parent / DEFAULT_PAGE_ROOT_NAME
    rows: list[dict[str, Any]] = []
    for book_root in sorted(path for path in root.iterdir() if path.is_dir()):
        path = book_root / "jsonl_ai" / "chunks.jsonl"
        if path.exists():
            rows.extend(read_jsonl(path))
    return rows


def load_index(root: Path, section: bool = False) -> tuple[list[dict[str, Any]], np.ndarray]:
    index_root = root / "_section_bge_index"
    metadata = read_jsonl(index_root / "metadata.jsonl")
    vectors = np.load(index_root / "embeddings.npy", mmap_mode="r")
    if len(metadata) != vectors.shape[0]:
        raise RuntimeError(f"BGE metadata/vector mismatch: {len(metadata)} != {vectors.shape[0]}")
    return metadata, vectors


def choose_all_book_sources(page_rows: list[dict[str, Any]], section_rows: list[dict[str, Any]], count_per_book: int) -> list[dict[str, Any]]:
    """Choose real content rows from every book, preferring section identities for the section-covered book.

    The page library contains one small textbook with fewer than ``count_per_book`` valid rows.  We therefore
    guarantee one source per book first, fill the normal per-book quota, and only then use spare rows from larger
    books to reach the requested total without silently dropping a textbook from the evaluation set.
    """
    by_doc: dict[str, list[dict[str, Any]]] = defaultdict(list)
    section_candidates = [row for row in section_rows if source_is_valid(row)]
    section_candidates.sort(key=lambda row: (int(row.get("page_no") or 0), str(row.get("chunk_id") or "")))
    section_by_doc: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for row in section_candidates:
        section_by_doc[str(row.get("doc_id") or "")].append(row)
    for row in page_rows:
        if source_is_valid(row):
            by_doc[str(row.get("doc_id") or "")].append(row)
    def diversified_rows(rows: list[dict[str, Any]], minimum_text_chars: int) -> list[dict[str, Any]]:
        """Round-robin chapter buckets so a book's first generic chapter cannot monopolize the cases."""
        preferred = [
            row for row in rows
            if int(row.get("page_no") or 0) >= EVAL_MIN_PAGE_NO
            and len(normalize_text(row.get("text"))) >= minimum_text_chars
            and body_source_is_natural(row, minimum_text_chars)
            and normalize_text(row.get("section_title")) not in {"目录", "前言", "本章小结"}
        ]
        fallback = [row for row in rows if row not in preferred]
        buckets: dict[str, list[dict[str, Any]]] = defaultdict(list)
        for row in preferred:
            chapter_path = row.get("chapter_path")
            chapter_key = str(chapter_path[0] if isinstance(chapter_path, list) and chapter_path else chapter_path or "")
            buckets[chapter_key].append(row)
        ordered_buckets = sorted(
            buckets.values(),
            key=lambda bucket: (int(bucket[0].get("page_no") or 0), str(bucket[0].get("chunk_id") or "")),
        )
        ordered: list[dict[str, Any]] = []
        cursor = 0
        while ordered_buckets:
            bucket = ordered_buckets[cursor % len(ordered_buckets)]
            ordered.append(bucket.pop(0))
            ordered_buckets = [item for item in ordered_buckets if item]
            if ordered_buckets:
                cursor = (cursor + 1) % len(ordered_buckets)
        return ordered + sorted(fallback, key=lambda row: (int(row.get("page_no") or 0), str(row.get("chunk_id") or "")))

    ordered_by_doc: dict[str, list[dict[str, Any]]] = {}
    for doc_id in sorted(by_doc):
        if section_by_doc.get(doc_id):
            ordered_by_doc[doc_id] = diversified_rows(section_by_doc[doc_id], EVAL_SECTION_TEXT_MIN_CHARS)
        else:
            ordered_by_doc[doc_id] = diversified_rows(by_doc[doc_id], EVAL_PAGE_TEXT_MIN_CHARS)

    selected: list[dict[str, Any]] = []
    selected_keys: set[str] = set()
    selected_counts: dict[str, int] = defaultdict(int)

    def add_first_distinct_pages(doc_id: str, limit: int) -> None:
        seen_pages: set[int] = set()
        for row in ordered_by_doc[doc_id]:
            if len(selected) >= CASE_COUNT or selected_counts[doc_id] >= limit:
                return
            row_key = key(row)
            if row_key in selected_keys:
                continue
            page_no = int(row.get("page_no") or 0)
            if page_no in seen_pages:
                continue
            seen_pages.add(page_no)
            selected.append(dict(row))
            selected_keys.add(row_key)
            selected_counts[doc_id] += 1

    # Preserve coverage even when a book has only one valid page/block.
    for doc_id in sorted(ordered_by_doc):
        add_first_distinct_pages(doc_id, 1)
    for doc_id in sorted(ordered_by_doc):
        add_first_distinct_pages(doc_id, count_per_book)

    # Use remaining real rows to reach CASE_COUNT; this only adds extra cases to books with enough material.
    for doc_id in sorted(ordered_by_doc):
        for row in ordered_by_doc[doc_id]:
            if len(selected) >= CASE_COUNT:
                break
            row_key = key(row)
            if row_key in selected_keys:
                continue
            selected.append(dict(row))
            selected_keys.add(row_key)
            selected_counts[doc_id] += 1
        if len(selected) >= CASE_COUNT:
            break
    if len(selected) < CASE_COUNT:
        raise RuntimeError(f"only {len(selected)} valid sources selected; expected {CASE_COUNT}")
    return selected[:CASE_COUNT]


def load_graph_terms(repo_root: Path) -> list[str]:
    """Read canonical graph labels/knowledge/method terms from the checked-in spine for deterministic test anchors."""
    path = repo_root / GRAPH_SPINE_RELATIVE_PATH
    if not path.exists():
        return []
    terms: list[str] = []
    for line in path.read_text(encoding="utf-8").splitlines():
        value = line.strip()
        if value.startswith("### "):
            terms.append(re.sub(r"^\d+(?:\.\d+)*\s+", "", value[4:]).strip())
        elif value.startswith("- 知识点：") or value.startswith("- 题型方法："):
            terms.extend(item.strip() for item in value.split("：", 1)[1].split("、"))
    return sorted({term for term in terms if len(term) >= 2}, key=len, reverse=True)


@lru_cache(maxsize=4)
def graph_term_context(repo_root_text: str) -> tuple[list[str], dict[str, list[str]]]:
    """Load canonical graph labels plus one-hop relation neighbors used by the graph-aware ablation."""
    repo_root = Path(repo_root_text)
    terms = load_graph_terms(repo_root)
    neighbors: dict[str, list[str]] = defaultdict(list)
    path = repo_root / GRAPH_SPINE_RELATIVE_PATH
    if path.exists():
        for line in path.read_text(encoding="utf-8").splitlines():
            value = line.strip()
            if not value.startswith("- ") or "->" not in value:
                continue
            left, right = (item.strip() for item in value[2:].split("->", 1))
            if left and right:
                neighbors[left].append(right)
                neighbors[right].append(left)
    return terms, dict(neighbors)


def graph_expanded_query(query: str) -> tuple[str, list[str], list[str]]:
    """Apply the graph's one-hop canonical expansion in the same normalization lane as Java focusedTextbookQuery."""
    terms, neighbors = graph_term_context(str(Path(__file__).resolve().parents[1]))
    matched = [term for term in terms if term in query]
    expanded: list[str] = []
    for term in matched:
        for candidate in [term, *neighbors.get(term, [])]:
            if candidate not in expanded:
                expanded.append(candidate)
            if len(expanded) >= GRAPH_EXPANSION_LIMIT:
                break
        if len(expanded) >= GRAPH_EXPANSION_LIMIT:
            break
    if not expanded:
        return query, matched, expanded
    return normalize_text(query + " " + " ".join(expanded)), matched, expanded


def deterministic_graph_cases(sources: list[dict[str, Any]], repo_root: Path) -> list[dict[str, Any]]:
    """Build natural graph-topic queries from real source text without copying whole source paragraphs."""
    graph_terms = load_graph_terms(repo_root)
    cases: list[dict[str, Any]] = []
    for index, row in enumerate(sources, 1):
        source_text = normalize_text(row.get("text"))
        anchor = next((term for term in graph_terms if term in source_text), "")
        if not anchor:
            title = normalize_text(row.get("section_title"))
            anchor = title if len(re.findall(r"[\u4e00-\u9fff]", title)) >= 2 else "教材正文"
        cases.append({
            "caseId": f"case-{index:03d}",
            "query": f"请查找教材中关于{anchor}的正文内容。",
            "source": row,
            "constructionModel": "graph_spine_anchor_v1",
            "constructionFallback": False,
            "graphAnchor": anchor,
        })
    return cases


def deterministic_body_cases(sources: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """Build natural body-page queries from the selected real section/chapter labels without forcing graph terms."""
    cases: list[dict[str, Any]] = []
    for index, row in enumerate(sources, 1):
        anchor = body_anchor(row)
        cases.append({
            "caseId": f"case-{index:03d}",
            "query": f"请查找教材中关于{anchor}的相关内容。",
            "source": row,
            "constructionModel": "body_section_anchor_v1",
            "constructionFallback": False,
        })
    return cases


def body_source_is_natural(row: dict[str, Any], minimum_text_chars: int = EVAL_PAGE_TEXT_MIN_CHARS) -> bool:
    """Reject OCR headings that are not supported by the text on the same page before sampling a case."""
    title = normalize_text(row.get("section_title"))
    text = body_text(row.get("text"))
    if len(text) < minimum_text_chars or not title or any(marker in title for marker in ("?", "!", ";", "%", "�")):
        return False
    if not re.search(r"(?:第[一二三四五六七八九十百]+章)|(?:^\d+(?:\.\d+)+\s*[\u4e00-\u9fff])", title):
        return False
    title_terms = [term for term in re.findall(r"[\u4e00-\u9fff]{2,}", title) if term not in {"章节", "正文"}]
    return bool(title_terms) and any(term in text for term in title_terms)


def body_anchor(row: dict[str, Any]) -> str:
    """Choose a visible title supported by the row, falling back to a clean chapter label."""
    text = body_text(row.get("text"))
    candidates = [normalize_text(row.get("section_title"))]
    chapter_path = row.get("chapter_path")
    if isinstance(chapter_path, list):
        candidates.extend(normalize_text(item) for item in reversed(chapter_path))
    else:
        candidates.append(normalize_text(chapter_path))
    for candidate in candidates:
        if not candidate or any(marker in candidate for marker in ("?", "!", ";", "%", "�")):
            continue
        if not re.search(r"(?:第[一二三四五六七八九十百]+章)|(?:^\d+(?:\.\d+)+\s*[\u4e00-\u9fff])", candidate):
            continue
        terms = [term for term in re.findall(r"[\u4e00-\u9fff]{2,}", candidate) if term not in {"章节", "正文"}]
        if terms and any(term in text for term in terms):
            return candidate
    return "教材正文"


def body_text(value: Any) -> str:
    """Remove page metadata before testing whether a heading is actually supported by page正文."""
    text = normalize_text(value)
    return text.split("## 正文", 1)[-1].strip()


def append_business_cases(cases: list[dict[str, Any]], page_rows: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """Append fixed user-facing regression queries while resolving each expected result from the real corpus."""
    existing_queries = {str(case.get("query") or "") for case in cases}
    appended = list(cases)
    for offset, spec in enumerate(BUSINESS_CASE_SPECS, len(appended) + 1):
        query = str(spec["query"])
        if query in existing_queries:
            continue
        matches = [
            row for row in page_rows
            if str(row.get("doc_id") or "") == spec["doc_id"]
            and int(row.get("page_no") or 0) == int(spec["page_no"])
            and spec["required_text"] in normalize_text(row.get("text"))
        ]
        if not matches:
            raise RuntimeError(
                f"business regression source missing: query={query}, doc={spec['doc_id']}, page={spec['page_no']}"
            )
        appended.append({
            "caseId": f"business-{offset:03d}",
            "query": query,
            "source": dict(matches[0]),
            "constructionModel": "business_regression_v1",
            "constructionFallback": False,
            "businessRegression": True,
        })
        existing_queries.add(query)
    return appended


class ProductionWorker:
    def __init__(self, base_url: str, key_file: Path) -> None:
        self.base_url = base_url.rstrip("/")
        self.key = key_file.read_text(encoding="utf-8").strip()
        if not self.key:
            raise RuntimeError(f"worker key is empty: {key_file}")
        self.session = requests.Session()
        self.headers = {"Authorization": "Bearer " + self.key, "Content-Type": "application/json"}

    def capabilities(self) -> dict[str, Any]:
        response = self.session.get(self.base_url + "/capabilities", headers=self.headers, timeout=30)
        response.raise_for_status()
        return response.json()

    def embed(self, query: str) -> tuple[np.ndarray, float, str]:
        started = time.perf_counter_ns()
        response = self.session.post(
            self.base_url + "/embeddings",
            headers=self.headers,
            json={"input": [query]},
            timeout=180,
        )
        response.raise_for_status()
        body = response.json()
        raw_embedding = body["data"][0]["embedding"]
        # The local worker can serialize vectors as either JSON arrays or a
        # whitespace-delimited string; accept both without changing the model
        # contract used by the Java service.
        if isinstance(raw_embedding, str):
            raw_embedding = [float(value) for value in raw_embedding.split()]
        vector = np.asarray(raw_embedding, dtype=np.float32)
        return vector, (time.perf_counter_ns() - started) / 1_000_000, str(body.get("model") or "")

    def rerank(self, query: str, documents: list[str]) -> tuple[list[float], float, str]:
        started = time.perf_counter_ns()
        response = self.session.post(
            self.base_url + "/rerank",
            headers=self.headers,
            json={"query": query, "documents": documents},
            timeout=180,
        )
        response.raise_for_status()
        body = response.json()
        scores = [float(item.get("score", 0.0)) for item in body.get("data", [])]
        return scores, (time.perf_counter_ns() - started) / 1_000_000, str(body.get("model") or "")


def bm25_rank(index: Any, query: str) -> tuple[list[dict[str, Any]], float]:
    started = time.perf_counter_ns()
    # Java calls the lexical engine with the complete visible chunk set, then limits document/page buckets before
    # the expensive stage-two call.  Keeping the broad lexical order here preserves the same admission semantics.
    hits, _ = index.search(query, limit=len(index.rows))
    rows = [dict(hit.row, _score=float(hit.score), _stage="bm25") for hit in sorted(hits, key=lambda item: item.score, reverse=True)]
    return rows, (time.perf_counter_ns() - started) / 1_000_000


def bge_rank(
    metadata: list[dict[str, Any]],
    vectors: np.ndarray,
    query_vector: np.ndarray,
    profile: RetrievalProfile,
) -> tuple[list[dict[str, Any]], float]:
    started = time.perf_counter_ns()
    scores = vectors @ query_vector
    semantic_page_limit = profile.max_document_candidates * profile.max_pages_per_document
    indexes = np.argsort(-scores)[:semantic_page_limit]
    rows = [dict(metadata[int(index)], _score=float(scores[int(index)]), _stage="bge") for index in indexes]
    return rows, (time.perf_counter_ns() - started) / 1_000_000


def key(row: dict[str, Any]) -> str:
    return str(row.get("chunk_id") or f"{row.get('doc_id')}#{row.get('page_no')}#{row.get('section_title')}")


def grouped_rows(rows: list[dict[str, Any]]) -> dict[str, list[dict[str, Any]]]:
    """Preserve source-local ordering while grouping stage-one evidence by textbook document."""
    grouped: dict[str, list[dict[str, Any]]] = defaultdict(list)
    seen_by_doc: dict[str, set[str]] = defaultdict(set)
    for row in rows:
        doc_id = str(row.get("doc_id") or "")
        row_key = key(row)
        if not doc_id or row_key in seen_by_doc[doc_id]:
            continue
        grouped[doc_id].append(row)
        seen_by_doc[doc_id].add(row_key)
    return dict(grouped)


def top_lexical_documents(rows: list[dict[str, Any]], profile: RetrievalProfile) -> dict[str, list[dict[str, Any]]]:
    grouped = grouped_rows(rows)
    ordered = sorted(grouped.items(), key=lambda item: float(item[1][0].get("_score", 0.0)), reverse=True)
    return dict(ordered[:profile.max_document_candidates])


def merge_document_candidates(
    lexical: dict[str, list[dict[str, Any]]],
    semantic: dict[str, list[dict[str, Any]]],
) -> dict[str, list[dict[str, Any]]]:
    """Union independent routes without pretending their scores share one numeric scale."""
    merged: dict[str, list[dict[str, Any]]] = {}
    # Keep lexical document admission stable; semantic evidence remains an
    # independent rescue route inside each admitted document.
    for source in (lexical, semantic):
        for doc_id, rows in source.items():
            target = merged.setdefault(doc_id, [])
            seen = {key(row) for row in target}
            target.extend(row for row in rows if key(row) not in seen)
    return merged


def capped_support_hits(
    document_candidates: dict[str, list[dict[str, Any]]],
    profile: RetrievalProfile,
    lexical_candidates: dict[str, list[dict[str, Any]]] | None = None,
    semantic_candidates: dict[str, list[dict[str, Any]]] | None = None,
) -> dict[str, list[dict[str, Any]]]:
    capped: dict[str, list[dict[str, Any]]] = {}
    for doc_id, rows in document_candidates.items():
        limit = profile.max_pages_per_document
        # Preserve an independent lexical and semantic witness before applying
        # the per-document cap.  A lexical-first concatenation can otherwise
        # discard the BGE page that found the actual section on the same book.
        lexical = (
            lexical_candidates.get(doc_id, [])
            if lexical_candidates is not None
            else [row for row in rows if row.get("_stage") == "bm25"]
        )
        semantic = (
            semantic_candidates.get(doc_id, [])
            if semantic_candidates is not None
            else [row for row in rows if row.get("_stage") == "bge"]
        )
        selected: list[dict[str, Any]] = []
        seen: set[str] = set()
        for group in (lexical[:1], semantic[:1], lexical[1:], semantic[1:], rows):
            for row in group:
                # Java uses sectionId as the support-unit identity.  A heading,
                # caption and prose block under one extracted subheading are
                # alternative evidence for the same section, not three pages
                # that should consume the bounded rerank window.
                row_key = str(row.get("section_id") or row.get("chunk_id") or key(row))
                if row_key in seen:
                    continue
                selected.append(row)
                seen.add(row_key)
                if len(selected) >= limit:
                    break
            if len(selected) >= limit:
                break
        capped[doc_id] = selected[:limit]
    return capped


def page_candidates(
    document_candidates: dict[str, list[dict[str, Any]]], profile: RetrievalProfile, limit: int
) -> list[dict[str, Any]]:
    """Use the production round-robin page admission so one edition cannot consume the whole rerank budget."""
    ranked_doc_ids = list(document_candidates)[:profile.max_rerank_documents]
    selected: list[dict[str, Any]] = []
    offset = 0
    while len(selected) < limit:
        appended = False
        for doc_id in ranked_doc_ids:
            rows = document_candidates.get(doc_id, [])
            if offset < len(rows):
                selected.append(rows[offset])
                appended = True
                if len(selected) >= limit:
                    break
        if not appended:
            break
        offset += 1
    return selected


def normalize_text(value: Any) -> str:
    return re.sub(r"\s+", " ", str(value or "")).strip()


def query_evidence_units(query: str) -> list[str]:
    """Match the Java evidence-window behavior without a topic-specific keyword dictionary."""
    compact_query = re.sub(r"\s+", "", query or "")
    units: list[str] = []
    for token in re.findall(r"[\u4e00-\u9fffA-Za-z0-9.]+", query or ""):
        if len(token) >= 2 and token not in units:
            units.append(token)
    for offset in range(max(0, len(compact_query) - 1)):
        bigram = compact_query[offset:offset + 2]
        if bigram not in units:
            units.append(bigram)
    return units


def evidence_window(query: str, text: Any, max_chars: int) -> str:
    normalized = normalize_text(text)
    if not normalized or len(normalized) <= max_chars:
        return normalized
    units = query_evidence_units(query)
    best_start = 0
    best_score = -1
    for start in range(0, len(normalized), max_chars):
        window = normalized[start:start + max_chars]
        score = sum(len(unit) for unit in units if unit in window)
        if score > best_score:
            best_start, best_score = start, score
    return normalized[best_start:best_start + max_chars].strip()


def truncate_for_rerank(value: Any, max_chars: int) -> str:
    normalized = normalize_text(value)
    return normalized if len(normalized) <= max_chars else normalized[:max_chars].strip() + "..."


def semantic_page_text(query: str, row: dict[str, Any], profile: RetrievalProfile) -> str:
    """Build the same bounded book/chapter/section/evidence payload used by TextbookRetrievalService."""
    chapter_path = row.get("chapter_path")
    if isinstance(chapter_path, list):
        chapter = " / ".join(str(item) for item in chapter_path)
    else:
        chapter = str(chapter_path or "")
    return "\n".join(
        (
            normalize_text(row.get("book_name")),
            normalize_text(row.get("volume")),
            normalize_text(chapter),
            normalize_text(row.get("section_title")),
            normalize_text(row.get("printed_page_no")),
            evidence_window(query, row.get("text"), profile.page_text_chars),
            truncate_for_rerank(row.get("formula_text"), profile.formula_text_chars),
        )
    )


def hit_payload(rank: int, hit: dict[str, Any]) -> dict[str, Any]:
    """Keep the same inspectable evidence fields for both pre-rerank and post-rerank snapshots."""
    return {
        "rank": rank,
        "docId": hit.get("doc_id"),
        "chunkId": hit.get("chunk_id"),
        "sectionId": hit.get("section_id"),
        "pageNo": hit.get("page_no"),
        "sourcePageNos": hit.get("source_page_nos") or hit.get("page_nos") or [hit.get("page_no")],
        "sectionTitle": hit.get("section_title"),
        "chunkType": hit.get("chunk_type"),
        "stage": hit.get("_stage"),
        "rerankScore": hit.get("_rerank_score"),
        "text": normalize_text(hit.get("text"))[:500],
    }


def expected_flags(hit: dict[str, Any], source: dict[str, Any], section_mode: bool) -> tuple[bool, bool, bool]:
    doc = str(hit.get("doc_id") or "") == str(source.get("doc_id") or "")
    source_pages = source.get("source_page_nos") or source.get("page_nos") or [source.get("page_no")]
    try:
        source_page_set = {int(value) for value in source_pages if value is not None}
    except (TypeError, ValueError):
        source_page_set = {int(source.get("page_no") or 0)}
    page = doc and int(hit.get("page_no") or 0) in source_page_set
    section = page and compact(hit.get("section_title")) == compact(source.get("section_title"))
    return doc, page, section if section_mode else page


def distinct_document_rank(rows: list[dict[str, Any]], expected_doc_id: Any) -> int | None:
    """Return the rank of a textbook after collapsing repeated section/page hits.

    Page and small-heading corpora both yield several chunks from one textbook.
    A document metric therefore ranks unique ``docId`` values, whereas page and
    block metrics deliberately keep the original chunk order.  Without this
    separation, three chunks from a wrong book can consume doc@3 by themselves.
    """
    expected = str(expected_doc_id or "")
    seen: set[str] = set()
    document_rank = 0
    for row in rows:
        # Retrieval rows use snake_case; persisted audit payloads use camelCase.
        doc_id = str(row.get("doc_id") or row.get("docId") or "")
        if not doc_id or doc_id in seen:
            continue
        seen.add(doc_id)
        document_rank += 1
        if doc_id == expected:
            return document_rank
    return None


def rank_result(
    config: str,
    query: str,
    source: dict[str, Any],
    index: Any,
    metadata: list[dict[str, Any]],
    vectors: np.ndarray,
    worker: ProductionWorker,
    section_mode: bool,
) -> dict[str, Any]:
    spec = SPEC_BY_NAME[config]
    profile = spec.profile
    search_query, graph_matched, graph_expanded = (
        graph_expanded_query(query) if spec.graph_expand else (query, [], [])
    )
    started = time.perf_counter_ns()
    embedding_ms = 0.0
    rerank_ms = 0.0
    embedding_model = ""
    rerank_model = ""
    bm_rows: list[dict[str, Any]] = []
    bge_rows: list[dict[str, Any]] = []
    bm_ms = 0.0
    bge_ms = 0.0

    def run_bm25() -> tuple[list[dict[str, Any]], float]:
        return bm25_rank(index, search_query)

    def run_bge() -> tuple[list[dict[str, Any]], float, float, str]:
        vector, embed_elapsed, model = worker.embed(search_query)
        rows, rank_elapsed = bge_rank(metadata, vectors, vector, profile)
        return rows, rank_elapsed, embed_elapsed, model

    recall_started = time.perf_counter_ns()
    if spec.parallel_recall and spec.use_bm25 and spec.use_bge:
        # This is an explicit experimental parallel route. The production Java route remains the serial control.
        with ThreadPoolExecutor(max_workers=RECALL_WORKERS) as executor:
            bm_future = executor.submit(run_bm25)
            bge_future = executor.submit(run_bge)
            bm_rows, bm_ms = bm_future.result()
            bge_rows, bge_ms, embedding_ms, embedding_model = bge_future.result()
    else:
        if spec.use_bm25:
            bm_rows, bm_ms = run_bm25()
        if spec.use_bge:
            bge_rows, bge_ms, embedding_ms, embedding_model = run_bge()
    recall_wall_ms = (time.perf_counter_ns() - recall_started) / 1_000_000

    if spec.name == "bm25":
        candidates = bm_rows[:TOP_HITS]
    elif spec.name == "bge":
        candidates = bge_rows[:TOP_HITS]
    else:
        lexical_docs = top_lexical_documents(bm_rows, profile) if spec.use_bm25 else {}
        semantic_docs = grouped_rows(bge_rows) if spec.use_bge else {}
        merged_docs = merge_document_candidates(lexical_docs, semantic_docs)
        support_docs = capped_support_hits(merged_docs, profile, lexical_docs, semantic_docs)
        if spec.use_rerank:
            candidates = page_candidates(support_docs, profile, profile.max_page_candidates)
        else:
            # Keep ablation baselines visible through Top-10 while using the same bounded document/page admission.
            candidates = page_candidates(support_docs, profile, TOP_HITS)

    pre_rerank_candidates = list(candidates)
    pre_rerank_hits = pre_rerank_candidates[:TOP_HITS]
    rerank_candidate_count = len(pre_rerank_candidates) if spec.use_rerank else 0
    if spec.use_rerank and candidates:
        texts = [semantic_page_text(search_query, row, profile) for row in candidates]
        scores, rerank_ms, rerank_model = worker.rerank(search_query, texts)
        candidates = [
            dict(row, _rerank_score=scores[position] if position < len(scores) else float("-inf"))
            for position, row in enumerate(candidates)
        ]
        candidates.sort(key=lambda row: float(row.get("_rerank_score", float("-inf"))), reverse=True)

    hits = candidates[:TOP_HITS]

    def first(rows: list[dict[str, Any]], position: int) -> int | None:
        flags = [expected_flags(hit, source, section_mode) for hit in rows]
        for rank, row_flags in enumerate(flags, 1):
            if row_flags[position]:
                return rank
        return None

    # Document recall answers a different question from page/block recall: did
    # the correct textbook survive among the top unique documents?  Deduplicate
    # only this metric, so result rendering still preserves every relevant block.
    before_ranks = {
        "document": distinct_document_rank(pre_rerank_hits, source.get("doc_id")),
        "page": first(pre_rerank_hits, 1),
        "block": first(pre_rerank_hits, 2),
    }
    after_ranks = {
        "document": distinct_document_rank(hits, source.get("doc_id")),
        "page": first(hits, 1),
        "block": first(hits, 2),
    }
    elapsed_ms = (time.perf_counter_ns() - started) / 1_000_000
    return {
        "config": config,
        "originalQuery": query,
        "effectiveQuery": search_query,
        "graphMatchedTerms": graph_matched,
        "graphExpandedTerms": graph_expanded,
        "documentRank": after_ranks["document"],
        "pageRank": after_ranks["page"],
        "blockRank": after_ranks["block"],
        "beforeRerankDocumentRank": before_ranks["document"],
        "beforeRerankPageRank": before_ranks["page"],
        "beforeRerankBlockRank": before_ranks["block"],
        "rerankApplied": spec.use_rerank,
        "rerankChanged": spec.use_rerank and before_ranks != after_ranks,
        "elapsedMs": round(elapsed_ms, 3),
        "bm25Ms": round(bm_ms, 3),
        "embeddingMs": round(embedding_ms, 3),
        "bgeRankMs": round(bge_ms, 3),
        "rerankMs": round(rerank_ms, 3),
        "recallWallMs": round(recall_wall_ms, 3),
        "candidateCount": len(candidates),
        "rerankCandidateCount": rerank_candidate_count,
        "pipelineDescription": spec.description,
        "parallelRecall": spec.parallel_recall,
        "profile": {
            "name": profile.name,
            "maxDocumentCandidates": profile.max_document_candidates,
            "maxPagesPerDocument": profile.max_pages_per_document,
            "maxRerankDocuments": profile.max_rerank_documents,
            "maxPageCandidates": profile.max_page_candidates,
            "pageTextChars": profile.page_text_chars,
            "formulaTextChars": profile.formula_text_chars,
        },
        "embeddingModel": embedding_model,
        "rerankModel": rerank_model,
        "preRerankHits": [hit_payload(rank, hit) for rank, hit in enumerate(pre_rerank_hits, 1)],
        "hits": [hit_payload(rank, hit) for rank, hit in enumerate(hits, 1)],
    }


def audit_results(cases: list[dict[str, Any]], results: list[dict[str, Any]], endpoint: str, api_key: str, model: str) -> list[dict[str, Any]]:
    by_case: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for result in results:
        by_case[str(result["caseId"])].append(result)
    system = (
        "你是严格的高中数学教材检索消融评审员。只返回JSON对象 {\"audits\":[...]}。"
        "先判断query是否由source原文明确支持(validScore 0/1/2)，再对每个配置的beforeRerank和afterRerank分别评分。"
        "每个阶段都返回documentScore、pageScore、blockScore（0/1/2；2明确正确，1部分命中，0错误）。"
        "对启用rerank的配置必须返回rerankChanged（true/false）和misjudgment（字符串数组，逐条指出错误发生在候选召回、页码、书籍、"
        "小标题还是rerank排序；没有误判返回空数组）。不要因为词面相似就给2，必须看页码、书籍和小标题/正文是否对应。"
        "输出结构：{audits:[{caseId,validScore,configs:{配置名:{before:{documentScore,pageScore,blockScore},"
        "after:{documentScore,pageScore,blockScore},rerankChanged,misjudgment:[]}}}]}。"
    )
    audits: list[dict[str, Any]] = []

    def audit_hits(rows: list[dict[str, Any]]) -> list[dict[str, Any]]:
        # Top-5 metadata supports every reported cutoff; only Top-3 carries a bounded text window to keep the audit
        # prompt small enough for one-query reasoning while retaining evidence for the highest scored cutoff.
        compact_rows = []
        for row in rows[:5]:
            compact_rows.append({
                "rank": row.get("rank"),
                "docId": row.get("docId"),
                "pageNo": row.get("pageNo"),
                "sectionTitle": row.get("sectionTitle"),
                "chunkType": row.get("chunkType"),
                "text": normalize_text(row.get("text"))[:120] if int(row.get("rank") or 0) <= 3 else "",
            })
        return compact_rows

    def audit_one(case: dict[str, Any]) -> list[dict[str, Any]]:
        payload = {
            "caseId": case["caseId"],
            "query": case["query"],
            "source": {
                key: normalize_text(case["source"].get(key))[:800]
                if key == "text" else case["source"].get(key)
                for key in ("doc_id", "page_no", "section_title", "chunk_type", "text")
            },
            "results": {
                row["result"]["config"]: {
                    "beforeRerank": audit_hits(row["result"].get("preRerankHits", [])),
                    "afterRerank": audit_hits(row["result"].get("hits", [])),
                    "rerankApplied": row["result"].get("rerankApplied", False),
                    "rerankChanged": row["result"].get("rerankChanged", False),
                    "timingMs": {
                        "recallWall": row["result"].get("recallWallMs", 0),
                        "rerank": row["result"].get("rerankMs", 0),
                        "total": row["result"].get("elapsedMs", 0),
                    },
                }
                for row in by_case[case["caseId"]]
            },
        }
        try:
            body = luna_json(endpoint, api_key, model, system, json.dumps({"cases": [payload]}, ensure_ascii=False), 1200)
        except Exception as exc:
            # A malformed model envelope is retried once with a shorter, single-object instruction; a real retrieval
            # error is never replaced by a heuristic score and remains an explicit fallback in the audit file.
            retry_system = system + "上一次输出格式错误。此次只返回一个完整JSON对象，不要Markdown代码围栏，不要解释文字。"
            try:
                body = luna_json(endpoint, api_key, model, retry_system, json.dumps({"cases": [payload]}, ensure_ascii=False), 900)
            except Exception as retry_exc:
                return [{"caseId": case["caseId"], "validScore": 0, "fallback": True, "reason": f"Luna error: {type(exc).__name__}: {exc}; retry={type(retry_exc).__name__}: {retry_exc}"}]
        generated = body.get("audits") if isinstance(body.get("audits"), list) else []
        generated = [item for item in generated if isinstance(item, dict)]
        if not generated:
            return generated

        # A compatible gateway may return a valid JSON object but omit one long configuration block.  Ask only for
        # the missing blocks and merge them into the same case; this keeps every before/after score attributable to
        # Luna while avoiding an implicit zero or a second full 46-case audit.
        audit_item = generated[0]
        configs = audit_item.get("configs") if isinstance(audit_item.get("configs"), dict) else {}
        for alias, canonical in AUDIT_CONFIG_ALIASES.items():
            if alias in configs and canonical not in configs:
                configs[canonical] = configs.pop(alias)
        expected_configs = {str(row["result"]["config"]) for row in by_case[case["caseId"]]}
        missing_configs = sorted(expected_configs - set(configs))
        if missing_configs:
            missing_payload = dict(payload)
            missing_payload["results"] = {
                name: payload["results"][name]
                for name in missing_configs
                if name in payload["results"]
            }
            retry_system = (
                system
                + "上一条JSON缺少指定配置。此次只返回同一case的完整JSON，并且configs必须包含这些配置名："
                + ",".join(missing_configs)
                + "。不要输出其他配置或解释文字。"
            )
            try:
                supplement = luna_json(endpoint, api_key, model, retry_system, json.dumps({"cases": [missing_payload]}, ensure_ascii=False), 900)
                supplement_items = supplement.get("audits") if isinstance(supplement.get("audits"), list) else []
                if supplement_items and isinstance(supplement_items[0], dict):
                    supplement_configs = supplement_items[0].get("configs")
                    if isinstance(supplement_configs, dict):
                        for alias, canonical in AUDIT_CONFIG_ALIASES.items():
                            if alias in supplement_configs and canonical not in supplement_configs:
                                supplement_configs[canonical] = supplement_configs.pop(alias)
                        configs.update(supplement_configs)
            except Exception as supplement_exc:
                audit_item["supplementError"] = f"{type(supplement_exc).__name__}: {supplement_exc}"
        audit_item["configs"] = configs
        return [audit_item]

    with ThreadPoolExecutor(max_workers=LUNA_AUDIT_WORKERS) as executor:
        futures = [executor.submit(audit_one, case) for case in cases]
        for future in futures:
            audits.extend(future.result())
    return audits


def score_audit(value: Any) -> int:
    if isinstance(value, dict):
        value = value.get("score", 0)
    try:
        return max(0, min(2, int(value)))
    except (TypeError, ValueError):
        return 0


def audit_phase(item: Any, phase: str) -> dict[str, Any]:
    """Normalize Luna's phase object while accepting the compact legacy score shape."""
    if not isinstance(item, dict):
        return {}
    phase_item = item.get(phase)
    if isinstance(phase_item, dict):
        return phase_item
    return item if phase == "after" else {}


def summarize(config: str, result_rows: list[dict[str, Any]], audits: dict[str, dict[str, Any]], valid_ids: set[str]) -> dict[str, Any]:
    rows = [row for row in result_rows if row["caseId"] in valid_ids]
    def recall(field: str, cutoff: int) -> float:
        return sum(1 for row in rows if row["result"].get(field) is not None and int(row["result"][field]) <= cutoff) / len(rows) if rows else 0.0
    summary: dict[str, Any] = {"config": config, "totalRows": len(result_rows), "commonValidCases": len(rows)}
    for cutoff in RECALL_CUTOFFS:
        summary[f"documentRecall@{cutoff}"] = recall("documentRank", cutoff)
        summary[f"pageRecall@{cutoff}"] = recall("pageRank", cutoff)
        summary[f"blockRecall@{cutoff}"] = recall("blockRank", cutoff)
    for field in ("elapsedMs", "recallWallMs", "bm25Ms", "embeddingMs", "bgeRankMs", "rerankMs"):
        values = [float(row["result"].get(field, 0.0)) for row in rows]
        summary[field] = {"avg": round(sum(values) / len(values), 3) if values else 0.0, "p95": round(sorted(values)[max(0, int(len(values) * 0.95) - 1)], 3) if values else 0.0}
    config_audits = [audits[row["caseId"]].get("configs", {}).get(config, {}) for row in rows if row["caseId"] in audits]
    phase_scores: dict[str, dict[str, list[int]]] = {}
    for phase in ("before", "after"):
        phase_scores[phase] = {
            field: [score_audit(audit_phase(item, phase).get(field)) for item in config_audits]
            for field in ("documentScore", "pageScore", "blockScore")
        }
    phase_avgs = {
        phase: {field: round(sum(values) / len(values), 3) if values else 0.0 for field, values in scores.items()}
        for phase, scores in phase_scores.items()
    }
    summary["lunaScoreAvgBefore"] = phase_avgs["before"]
    summary["lunaScoreAvg"] = phase_avgs["after"]
    summary["rerankChangedCases"] = sum(1 for row in rows if row["result"].get("rerankChanged"))
    summary["misjudgmentCount"] = sum(
        len(item.get("misjudgment", [])) if isinstance(item.get("misjudgment"), list) else int(bool(item.get("misjudgment")))
        for config_item in config_audits
        for item in [config_item]
    )
    before_total = sum(phase_avgs["before"].values())
    after_total = sum(phase_avgs["after"].values())
    summary["lunaRerankDelta100"] = round((after_total - before_total) / 6 * 100, 2)
    retrieval = sum(summary[f"blockRecall@{cutoff}"] for cutoff in RECALL_CUTOFFS) / len(RECALL_CUTOFFS) * 100
    luna = sum(summary["lunaScoreAvg"].values()) / 6 * 100
    summary["scoreComponents"] = {"retrievalRecall100": round(retrieval, 2), "lunaAudit100": round(luna, 2)}
    summary["score100"] = round((retrieval + luna) / 2, 2)
    return summary


def markdown(report: dict[str, Any], cases: list[dict[str, Any]], output: Path) -> None:
    lines = ["# 全教材检索消融评测", "", f"样本 `{len(cases)}` 条，Luna共同有效 `{report['commonValidCases']}` 条；覆盖书数 `{report['bookCount']}`；业务回归用例 `{report.get('businessCaseCount', 0)}` 条。", "", "| 配置 | 文档@1/@3/@5 | 块@1/@3/@5 | 总耗时均值/P95ms | 召回墙钟均值ms | rerank前后块分 | Luna后文档/页/块 | Luna变化 | 归一分 |", "|---|---|---|---:|---:|---|---|---:|---:|"]
    for config, item in report["summaries"].items():
        lines.append(f"| {config} | {item['documentRecall@1']:.3f}/{item['documentRecall@3']:.3f}/{item['documentRecall@5']:.3f} | {item['blockRecall@1']:.3f}/{item['blockRecall@3']:.3f}/{item['blockRecall@5']:.3f} | {item['elapsedMs']['avg']:.1f}/{item['elapsedMs']['p95']:.1f} | {item['recallWallMs']['avg']:.1f} | {item['lunaScoreAvgBefore']['blockScore']:.2f} -> {item['lunaScoreAvg']['blockScore']:.2f} | {item['lunaScoreAvg']['documentScore']:.2f}/{item['lunaScoreAvg']['pageScore']:.2f}/{item['lunaScoreAvg']['blockScore']:.2f} | {item['lunaRerankDelta100']:+.2f} | {item['score100']:.2f} |")
    lines.extend(["", "## 覆盖", "", "| caseId | docId | page | sectionTitle | query |", "|---|---|---:|---|---|"])
    for case in cases:
        source = case["source"]
        lines.append(f"| {case['caseId']} | {source.get('doc_id')} | {source.get('page_no')} | {str(source.get('section_title') or '').replace('|','\\|')} | {str(case['query']).replace('|','\\|')} |")
    lines.extend(["", "## 配置说明", "", "- BM25 与 BGE 是独立的一阶段证据路线；`hybrid` 系列只做文档/页候选并集，不混合不可比的原始分数。", "- 生产 Java 默认预算：最多 3 本候选书、每书 3 页、最终最多 2 本书参与一次 5 页 rerank，页文本 120 字、公式 40 字。", "- `speed` profile 将候选与 payload 缩小为 2/2/1/3、80/24；`industry_quality` profile 扩大为 5/3/3/8、160/60，所有值均在结果 profile 中逐条记录。", "- `*_parallel*` 只并行独立的 BM25/BGE 一阶段召回；最终 rerank 仍是一个串行 Worker 请求，保持线上阶段边界。", "- Luna 只负责 query 有效性和候选正确性审查，不参与检索排序；retrieval 与 Luna 分数分别归一到 0-100 后等权汇总。", ""])
    (output / "summary.md").write_text("\n".join(lines) + "\n", encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser(description="Run all-textbook production-worker retrieval ablations")
    parser.add_argument("--library-parent", type=Path, default=DEFAULT_LIBRARY_PARENT)
    parser.add_argument("--output-dir", type=Path, default=Path("output/benchmarks") / f"textbook-ablation-{datetime.now():%Y%m%d-%H%M%S}")
    parser.add_argument("--reuse-cases", type=Path, default=None)
    parser.add_argument("--reuse-results", type=Path, default=None, help="skip retrieval and audit an existing real results.json")
    parser.add_argument("--prepare-cases-only", action="store_true", help="write a diverse real-source cases.json and stop")
    parser.add_argument("--graph-anchor-cases", action="store_true", help="construct cases from real source text and graph-spine anchors")
    parser.add_argument("--body-cases", action="store_true", help="construct cases from real body section/chapter labels without graph forcing")
    parser.add_argument(
        "--include-business-cases",
        action="store_true",
        help="append fixed user-facing regression queries resolved against real textbook rows",
    )
    args = parser.parse_args()
    parent = args.library_parent.expanduser().resolve()
    output = args.output_dir
    output.mkdir(parents=True, exist_ok=True)
    page_rows = load_page_rows(parent)
    section_root = parent / DEFAULT_SECTION_ROOT_NAME
    section_path = section_root / DEFAULT_SECTION_BOOK / "jsonl_ai" / "chunks.jsonl"
    section_rows = read_jsonl(section_path) if section_path.exists() else []
    sources = choose_all_book_sources(page_rows, section_rows, CASES_PER_BOOK)
    endpoint, api_key, llm_model = llm_config()
    if args.reuse_cases:
        cases = json.loads(args.reuse_cases.read_text(encoding="utf-8"))
    elif args.graph_anchor_cases:
        cases = deterministic_graph_cases(sources, Path(__file__).resolve().parents[1])
    elif args.body_cases:
        cases = deterministic_body_cases(sources)
    else:
        cases = construct_queries(sources, endpoint, api_key, llm_model)
    if args.include_business_cases:
        cases = append_business_cases(cases, page_rows)
    write_json(output / "cases.json", cases)
    if args.prepare_cases_only:
        print(json.dumps({"outputDir": str(output), "caseCount": len(cases), "bookIds": sorted({str(c["source"].get("doc_id") or "") for c in cases})}, ensure_ascii=False))
        return
    sys.path.insert(0, str(parent))
    import OCR测试方案.bm25_index as bm25

    page_root = parent / DEFAULT_PAGE_ROOT_NAME
    page_index = bm25.build_bm25_index(page_rows)
    page_metadata, page_vectors = load_index(page_root)
    section_index = bm25.build_bm25_index(section_rows) if section_rows else None
    section_metadata, section_vectors = load_index(section_root) if section_rows else ([], np.empty((0, 0), dtype=np.float32))
    worker = ProductionWorker(WORKER_BASE_URL, WORKER_KEY_FILE)
    capabilities = worker.capabilities()
    write_json(output / "worker_capabilities.json", capabilities)
    if args.reuse_results:
        results = json.loads(args.reuse_results.read_text(encoding="utf-8"))
    else:
        results = []
        for case in cases:
            source = case["source"]
            is_section_case = str(source.get("doc_id") or "") == DEFAULT_SECTION_BOOK and bool(source.get("section_id"))
            for config in CONFIGS:
                result = rank_result(config, case["query"], source, page_index, page_metadata, page_vectors, worker, False)
                results.append({"caseId": case["caseId"], "corpus": "page", "result": result})
            if is_section_case and section_index is not None:
                for config in CONFIGS:
                    result = rank_result(config, case["query"], source, section_index, section_metadata, section_vectors, worker, True)
                    results.append({"caseId": case["caseId"], "corpus": "section", "result": result})
        write_json(output / "results.json", results)
    # Audit the page corpus used for the all-book comparison. Section rows are reported separately and must not
    # overwrite the same case/config keys in the page audit payload.
    page_results = [row for row in results if row["corpus"] == "page"]
    audits_raw = audit_results(cases, page_results, endpoint, api_key, llm_model)
    audits: dict[str, dict[str, Any]] = {}
    for item in audits_raw:
        normalized = dict(item)
        normalized["validScore"] = score_audit(item.get("validScore", item.get("validCase", 0)))
        normalized["validCase"] = normalized["validScore"] >= 1
        normalized_configs = normalized.get("configs")
        if isinstance(normalized_configs, dict):
            for alias, canonical in AUDIT_CONFIG_ALIASES.items():
                if alias in normalized_configs and canonical not in normalized_configs:
                    normalized_configs[canonical] = normalized_configs.pop(alias)
        missing_configs = [config for config in CONFIGS if not isinstance(normalized.get("configs", {}).get(config), dict)]
        if missing_configs:
            normalized["auditIncomplete"] = True
            normalized["missingConfigs"] = missing_configs
        audits[str(item.get("caseId"))] = normalized
    write_json(output / "luna_audits.json", audits)
    valid_ids = {case_id for case_id, item in audits.items() if item.get("validCase")}
    summaries = {config: summarize(config, [row for row in page_results if row["result"]["config"] == config], audits, valid_ids) for config in CONFIGS}
    section_results = [row for row in results if row["corpus"] == "section"]
    section_case_ids = {row["caseId"] for row in section_results}
    section_valid_ids = valid_ids & section_case_ids
    section_summaries = {config: summarize(config, [row for row in section_results if row["result"]["config"] == config], audits, section_valid_ids) for config in CONFIGS}
    report = {
        "kind": "production_worker_textbook_ablation",
        "generatedAt": datetime.now().isoformat(timespec="seconds"),
        "llm": {"model": llm_model, "purpose": "query construction and blind audit"},
        "worker": {"baseUrl": WORKER_BASE_URL, "capabilities": capabilities},
        "caseCount": len(cases),
        "businessCaseCount": sum(1 for case in cases if case.get("businessRegression")),
        "commonValidCases": len(valid_ids),
        "auditCompleteness": {
            "auditedCases": len(audits),
            "incompleteCases": [case_id for case_id, item in audits.items() if item.get("auditIncomplete")],
            "requiredConfigs": list(CONFIGS),
        },
        "bookCount": len({str(case["source"].get("doc_id") or "") for case in cases}),
        "bookIds": sorted({str(case["source"].get("doc_id") or "") for case in cases}),
        "pageCorpus": {"rows": len(page_rows), "books": len({str(row.get("doc_id") or "") for row in page_rows})},
        "sectionCorpus": {"rows": len(section_rows), "books": len({str(row.get("doc_id") or "") for row in section_rows}), "evaluatedCases": len(section_case_ids)},
        "businessAlignment": {
            "javaClass": "backend-java/src/main/java/com/doob/mathagent/retrieval/TextbookRetrievalService.java",
            "stageOneMethods": ["rerankedHits", "semanticPageDocumentCandidates", "topLexicalDocumentCandidates", "mergeDocumentCandidates", "cappedSupportHitsByDocId", "rankedDocumentIds", "pageCandidates"],
            "stageTwoMethod": "semanticScoreByKey",
            "candidatePolicy": "BM25 and BGE are independent admission routes; their raw scores are never added.",
            "productionEquivalent": ["hybrid_rerank"],
            "experimentalVariants": ["hybrid_parallel", "hybrid_rerank_parallel_speed", "hybrid_rerank_parallel_industry"],
        },
        "pipelineSpecs": [{
            "name": spec.name,
            "useBm25": spec.use_bm25,
            "useBge": spec.use_bge,
            "useRerank": spec.use_rerank,
            "parallelRecall": spec.parallel_recall,
            "graphExpand": spec.graph_expand,
            "description": spec.description,
            "profile": {
                "name": spec.profile.name,
                "maxDocumentCandidates": spec.profile.max_document_candidates,
                "maxPagesPerDocument": spec.profile.max_pages_per_document,
                "maxRerankDocuments": spec.profile.max_rerank_documents,
                "maxPageCandidates": spec.profile.max_page_candidates,
                "pageTextChars": spec.profile.page_text_chars,
                "formulaTextChars": spec.profile.formula_text_chars,
            },
        } for spec in PIPELINE_SPECS],
        "summaries": summaries,
        "sectionSummaries": section_summaries,
        "files": {"cases": str(output / "cases.json"), "results": str(output / "results.json"), "audits": str(output / "luna_audits.json"), "gpuProbe": str(output / "gpu_probe.json")},
        "limitations": ["Section corpus currently covers only one book; all-book ablation uses the complete page corpus.", "The host py_12 interpreter is CPU-only, while the production worker endpoint reports CUDA; the report records both states and the model calls were made through the worker endpoint.", "Each result keeps embedding/rerank/total latency separately."],
    }
    write_json(output / "report.json", report)
    markdown(report, cases, output)
    print(json.dumps({"outputDir": str(output), "caseCount": len(cases), "commonValidCases": len(valid_ids), "books": report["bookIds"], "summaries": summaries}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
