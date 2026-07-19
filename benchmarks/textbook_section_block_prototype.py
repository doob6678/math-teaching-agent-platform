"""Python-first section-block retrieval prototype.

Unlike the legacy page route, this module makes one logical block from all
chunks sharing a document, section identity, and visible heading. Pages remain
evidence inside the block; they are never separate ranking units.
"""

from __future__ import annotations

import argparse
import json
import math
import re
import statistics
import sys
from collections import defaultdict
from dataclasses import dataclass
from pathlib import Path
from typing import Any

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
    graph_expanded_query,
    semantic_page_text,
)
from benchmarks.textbook_independent_retrieval_eval import (
    RealWorker,
    grounded,
    interleave_routes,
    load_bm25_module,
    score_or_negative_infinity,
)


DEFAULT_OUTPUT = Path("output/benchmarks/textbook-independent-110-section-block-v1")
METRIC_CUTOFFS = (1, 3, 5, 10)


def resolve_evaluation_query(query: str, graph_expand: bool) -> tuple[str, list[str], list[str]]:
    """Expose the graph experiment explicitly while preserving the caller's original query text."""
    if not graph_expand:
        return query, [], []
    return graph_expanded_query(query)


def visible_title(value: Any) -> str:
    """Remove OCR spacing and only a CJK-attached printed-page suffix."""
    return re.sub(r"(?<=[\u4e00-\u9fff])\d{1,3}$", "", compact(value))


def block_key(row: dict[str, Any]) -> tuple[str, str, str]:
    section_id = str(row.get("section_id") or row.get("chunk_id") or "")
    return str(row.get("doc_id") or ""), section_id, visible_title(row.get("section_title"))


@dataclass(frozen=True)
class BlockReferenceIndex:
    """Lightweight block lookup that retains references to immutable corpus rows."""

    members_by_key: dict[tuple[str, str, str], list[dict[str, Any]]]
    key_by_chunk_id: dict[str, tuple[str, str, str]]
    keys_by_doc_section: dict[tuple[str, str], list[tuple[str, str, str]]]
    keys_by_doc_page: dict[tuple[str, int], list[tuple[str, str, str]]]
    representative_by_key: dict[tuple[str, str, str], dict[str, Any]]


def build_block_index(rows: list[dict[str, Any]]) -> BlockReferenceIndex:
    """Index small-heading members without copying or concatenating source text."""
    grouped: dict[tuple[str, str, str], list[dict[str, Any]]] = defaultdict(list)
    for row in rows:
        grouped[block_key(row)].append(row)
    members_by_key: dict[tuple[str, str, str], list[dict[str, Any]]] = {}
    key_by_chunk_id: dict[str, tuple[str, str, str]] = {}
    keys_by_doc_section: dict[tuple[str, str], list[tuple[str, str, str]]] = defaultdict(list)
    keys_by_doc_page: dict[tuple[str, int], list[tuple[str, str, str]]] = defaultdict(list)
    representative_by_key: dict[tuple[str, str, str], dict[str, Any]] = {}
    for key, members in grouped.items():
        ordered = sorted(members, key=lambda row: (int(row.get("page_no") or 0), str(row.get("chunk_id") or "")))
        members_by_key[key] = ordered
        representative_by_key[key] = max(
            ordered,
            key=lambda row: len(normalized_text(row.get("text"))) + len(normalized_text(row.get("formula_text"))),
        )
        for member in ordered:
            chunk_id = str(member.get("chunk_id") or "")
            if chunk_id:
                key_by_chunk_id[chunk_id] = key
            doc_id = str(member.get("doc_id") or "")
            section_id = str(member.get("section_id") or member.get("chunk_id") or "")
            doc_section = (doc_id, section_id)
            if key not in keys_by_doc_section[doc_section]:
                keys_by_doc_section[doc_section].append(key)
            doc_page = (doc_id, int(member.get("page_no") or 0))
            if key not in keys_by_doc_page[doc_page]:
                keys_by_doc_page[doc_page].append(key)
    return BlockReferenceIndex(
        dict(members_by_key),
        dict(key_by_chunk_id),
        dict(keys_by_doc_section),
        dict(keys_by_doc_page),
        dict(representative_by_key),
    )


def grouped(rows: list[dict[str, Any]]) -> dict[str, list[dict[str, Any]]]:
    result: dict[str, list[dict[str, Any]]] = defaultdict(list)
    seen: dict[str, set[str]] = defaultdict(set)
    for row in rows:
        doc_id = str(row.get("doc_id") or "")
        key = str(row.get("_block_key") or row.get("chunk_id") or "")
        if doc_id and key not in seen[doc_id]:
            result[doc_id].append(row)
            seen[doc_id].add(key)
    return dict(result)


class SectionBlockRetriever:
    def __init__(self, library_root: Path, worker: RealWorker) -> None:
        self.library_root = library_root
        self.worker = worker
        self.rows = corpus_rows(library_root)
        self.block_index = build_block_index(self.rows)
        module = load_bm25_module(library_root)
        # BM25 remains over the original c2 subheading records.  Logical blocks are candidate identities, not copies.
        self.index = module.build_bm25_index(self.rows)

    def lexical(self, query: str) -> list[dict[str, Any]]:
        hits, _ = self.index.search(query, limit=len(self.index.rows))
        return self.collapse_candidates([
            dict(hit.row, _stage="section_bm25", _score=float(hit.score))
            for hit in sorted(hits, key=lambda item: item.score, reverse=True)
        ])

    def collapse_candidates(self, rows: list[dict[str, Any]]) -> list[dict[str, Any]]:
        """Keep each logical block's best route score and its actual recalled child page.

        The parent block remains the rerank unit, but replacing a recalled child
        with the longest sibling would make a correct cross-page retrieval look
        like a page miss and would return an unrelated page image to callers.
        """
        best: dict[tuple[str, str, str], dict[str, Any]] = {}
        for row in rows:
            key = self.block_key_for_row(row)
            candidate = dict(row, _block_key=key, _stage=row.get("_stage"), _score=float(row.get("_score") or 0.0))
            previous = best.get(key)
            if previous is None or float(candidate["_score"]) > float(previous["_score"]):
                best[key] = candidate
        return sorted(best.values(), key=lambda row: (-float(row["_score"]), str(row.get("doc_id") or ""), str(row.get("chunk_id") or "")))

    def block_key_for_row(self, row: dict[str, Any]) -> tuple[str, str, str]:
        chunk_id = str(row.get("chunk_id") or "")
        if chunk_id in self.block_index.key_by_chunk_id:
            return self.block_index.key_by_chunk_id[chunk_id]
        doc_id = str(row.get("doc_id") or "")
        section_id = str(row.get("section_id") or "")
        title = visible_title(row.get("section_title"))
        for key in self.block_index.keys_by_doc_section.get((doc_id, section_id), []):
            if not title or key[-1] == title:
                return key
        return block_key(row)

    def semantic(self, query: str) -> list[dict[str, Any]]:
        hits = self.worker.page_search(
            query,
            PRODUCTION_PROFILE.max_document_candidates * PRODUCTION_PROFILE.max_pages_per_document,
        )
        route_rows: list[dict[str, Any]] = []
        for hit in hits:
            key = self.block_index.key_by_chunk_id.get(hit.chunk_id)
            candidates = [key] if key else [
                candidate for candidate in self.block_index.keys_by_doc_section.get((hit.doc_id, hit.section_id), [])
                if not hit.section_title or candidate[-1] == visible_title(hit.section_title)
            ]
            if not candidates:
                candidates = self.block_index.keys_by_doc_page.get((hit.doc_id, hit.page_no), [])
            for candidate in candidates:
                members = self.block_index.members_by_key[candidate]
                seed = next(
                    (member for member in members if int(member.get("page_no") or 0) == hit.page_no),
                    self.block_index.representative_by_key[candidate],
                )
                route_rows.append(dict(seed, _block_key=candidate, _stage="section_bge", _score=hit.score))
        return self.collapse_candidates(route_rows)

    @staticmethod
    def support(doc_id: str, routes: list[list[dict[str, Any]]], limit: int) -> list[dict[str, Any]]:
        selected: list[dict[str, Any]] = []
        seen: set[str] = set()
        offsets = [0] * len(routes)
        while len(selected) < limit:
            progressed = False
            for index, route in enumerate(routes):
                while offsets[index] < len(route):
                    row = route[offsets[index]]
                    offsets[index] += 1
                    if str(row.get("doc_id") or "") != doc_id:
                        continue
                    key = str(row.get("_block_key") or row.get("chunk_id") or "")
                    if key in seen:
                        continue
                    selected.append(row)
                    seen.add(key)
                    progressed = True
                    break
                if len(selected) >= limit:
                    break
            if not progressed:
                break
        return selected

    @staticmethod
    def matrix(doc_ids: list[str], support: dict[str, list[dict[str, Any]]]) -> list[dict[str, Any]]:
        result: list[dict[str, Any]] = []
        offset = 0
        while len(result) < PRODUCTION_PROFILE.max_page_candidates:
            progressed = False
            for doc_id in doc_ids:
                rows = support.get(doc_id, [])
                if offset < len(rows):
                    result.append(rows[offset])
                    progressed = True
                    if len(result) >= PRODUCTION_PROFILE.max_page_candidates:
                        break
            if not progressed:
                break
            offset += 1
        return result

    def members_for_candidate(self, row: dict[str, Any]) -> list[dict[str, Any]]:
        """Resolve a candidate to its source rows; this does not allocate a merged corpus object."""
        key = row.get("_block_key")
        if isinstance(key, tuple):
            return self.block_index.members_by_key.get(key, [])
        return self.block_index.members_by_key.get(self.block_key_for_row(row), [])

    def child_evidence_candidates(self, parent_candidates: list[dict[str, Any]]) -> list[dict[str, Any]]:
        """Expand admitted parents into page evidence while preserving the fixed rerank window.

        Parent-document retrieval avoids sibling chunks occupying every stage-one
        slot. The final strict diagnostic still needs a page-level evidence item,
        so each admitted parent contributes its recalled page first and then one
        strongest source record for each remaining page. No score is added or
        weighted during this structural expansion.
        """
        expanded: list[dict[str, Any]] = []
        seen_chunks: set[str] = set()
        for parent in parent_candidates:
            members = self.members_for_candidate(parent)
            by_page: dict[int, list[dict[str, Any]]] = defaultdict(list)
            for member in members:
                by_page[int(member.get("page_no") or 0)].append(member)
            seed_page = int(parent.get("page_no") or 0)
            page_order = [seed_page] + sorted(page for page in by_page if page != seed_page)
            for page_no in page_order:
                page_members = by_page.get(page_no, [])
                if not page_members:
                    continue
                child = parent if page_no == seed_page else max(
                    page_members,
                    key=lambda row: len(normalized_text(row.get("text"))) + len(normalized_text(row.get("formula_text"))),
                )
                chunk_id = str(child.get("chunk_id") or "")
                if not chunk_id or chunk_id in seen_chunks:
                    continue
                expanded.append(dict(child, _block_key=parent.get("_block_key"), _stage=parent.get("_stage"), _score=parent.get("_score")))
                seen_chunks.add(chunk_id)
                if len(expanded) >= PRODUCTION_PROFILE.max_page_candidates:
                    return expanded
        return expanded

    def rerank_document(self, query: str, row: dict[str, Any], child_evidence: bool = False) -> str:
        """Materialize cross-page text only for a final rerank candidate."""
        if child_evidence:
            # The parent title/chapter metadata is already on the child row; its
            # own text is what distinguishes two pages of the same subheading.
            return semantic_page_text(query, row, PRODUCTION_PROFILE)
        members = self.members_for_candidate(row)
        if not members:
            return semantic_page_text(query, row, PRODUCTION_PROFILE)
        payload = dict(row)
        payload["text"] = "\n".join(
            normalized_text(member.get("text")) for member in members if normalized_text(member.get("text"))
        )
        payload["formula_text"] = "\n".join(
            normalized_text(member.get("formula_text")) for member in members if normalized_text(member.get("formula_text"))
        )
        return semantic_page_text(query, payload, PRODUCTION_PROFILE)

    def grounded_candidate(self, query: str, row: dict[str, Any]) -> bool:
        """Use the complete logical block for evidence checks without retaining a copied text field."""
        members = self.members_for_candidate(row)
        if not members:
            return grounded(query, row)
        payload = dict(row)
        payload["text"] = "\n".join(normalized_text(member.get("text")) for member in members)
        payload["formula_text"] = "\n".join(normalized_text(member.get("formula_text")) for member in members)
        return grounded(query, payload)

    def retrieve(
        self,
        query: str,
        limit: int,
        child_evidence_rerank: bool = False,
    ) -> tuple[list[dict[str, Any]], bool, float, str]:
        started = time_ns()
        lexical = self.lexical(query)
        semantic = self.semantic(query)
        lexical_docs = grouped(lexical)
        semantic_docs = grouped(semantic)
        doc_ids = interleave_routes(
            [list(lexical_docs)[:PRODUCTION_PROFILE.max_document_candidates], list(semantic_docs)],
            PRODUCTION_PROFILE.max_rerank_documents,
        )
        support = {
            doc_id: self.support(
                doc_id,
                [lexical, semantic],
                PRODUCTION_PROFILE.max_pages_per_document,
            )
            for doc_id in doc_ids
        }
        parents = self.matrix(doc_ids, support)
        candidates = self.child_evidence_candidates(parents) if child_evidence_rerank else parents
        documents = [self.rerank_document(query, row, child_evidence_rerank) for row in candidates]
        scores, model = self.worker.rerank(query, documents)
        ranked = [dict(row, _rerank_score=scores[index]) for index, row in enumerate(candidates)]
        ranked.sort(key=lambda row: (
            -score_or_negative_infinity(row.get("_rerank_score")),
            str(row.get("doc_id") or ""),
            str(row.get("_block_key") or row.get("chunk_id") or ""),
        ))
        grounded_docs = {str(row.get("doc_id") or "") for row in ranked if self.grounded_candidate(query, row)}
        filtered = [row for row in ranked if str(row.get("doc_id") or "") in grounded_docs]
        return filtered[: max(1, limit)], not filtered, elapsed_ms(started), model


def time_ns() -> int:
    return __import__("time").perf_counter_ns()


def elapsed_ms(started: int) -> float:
    return (time_ns() - started) / 1_000_000


def read_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def write_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def document_rank(hits: list[dict[str, Any]], expected: dict[str, Any] | None) -> int | None:
    if expected is None:
        return None
    seen: set[str] = set()
    rank = 0
    for hit in hits:
        doc_id = str(hit.get("doc_id") or "")
        if doc_id in seen:
            continue
        seen.add(doc_id)
        rank += 1
        if doc_id == str(expected["docId"]):
            return rank
    return None


def block_rank(hits: list[dict[str, Any]], expected: dict[str, Any] | None) -> int | None:
    if expected is None:
        return None
    target = (str(expected["docId"]), str(expected.get("sectionId") or ""), visible_title(expected.get("sectionTitle")))
    for rank, hit in enumerate(hits, 1):
        identity = (str(hit.get("doc_id") or ""), str(hit.get("section_id") or ""), visible_title(hit.get("section_title")))
        if identity == target:
            return rank
    return None


def strict_block_rank(hits: list[dict[str, Any]], expected: dict[str, Any] | None) -> int | None:
    """Score the legacy diagnostic identity without substituting a logical-block hit."""
    if expected is None:
        return None
    target = (
        str(expected["docId"]),
        int(expected["pageNo"]),
        visible_title(expected.get("sectionTitle")),
    )
    for rank, hit in enumerate(hits, 1):
        identity = (
            str(hit.get("doc_id") or ""),
            int(hit.get("page_no") or 0),
            visible_title(hit.get("section_title")),
        )
        if identity == target:
            return rank
    return None


def summarize(rows: list[dict[str, Any]]) -> dict[str, Any]:
    positives = [row for row in rows if row["polarity"] == "positive"]
    negatives = [row for row in rows if row["polarity"] == "negative"]
    result: dict[str, Any] = {
        "positiveCount": len(positives),
        "negativeCount": len(negatives),
        "negativeEmptyRate": sum(row["hitCount"] == 0 for row in negatives) / len(negatives),
    }
    result["negativeFalsePositiveRate"] = 1.0 - result["negativeEmptyRate"]
    for name, field in (("document", "documentRank"), ("block", "blockRank")):
        for cutoff in METRIC_CUTOFFS:
            result[f"{name}Recall@{cutoff}"] = sum(
                row[field] is not None and int(row[field]) <= cutoff for row in positives
            ) / len(positives)
    result["blockMRR@10"] = statistics.fmean(
        1.0 / int(row["blockRank"])
        if row["blockRank"] is not None and int(row["blockRank"]) <= 10 else 0.0
        for row in positives
    )
    return result


def main() -> None:
    parser = argparse.ArgumentParser(description="Evaluate textbook retrieval on logical small-heading blocks")
    parser.add_argument("--library-root", type=Path, default=DEFAULT_LIBRARY_ROOT)
    parser.add_argument("--cases", type=Path, default=DEFAULT_CASE_ROOT / "cases.json")
    parser.add_argument("--manifest", type=Path, default=DEFAULT_CASE_ROOT / "manifest.json")
    parser.add_argument("--output-dir", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--graph-expand", action="store_true", help="evaluate the existing one-hop graph expansion without changing cases")
    args = parser.parse_args()
    root = args.library_root.expanduser().resolve()
    cases = read_json(args.cases.expanduser().resolve())
    manifest = read_json(args.manifest.expanduser().resolve())
    if manifest.get("corpusFingerprint") != corpus_fingerprint(root):
        raise RuntimeError("independent cases do not match the current corpus fingerprint")
    retriever = SectionBlockRetriever(root, RealWorker(WORKER_BASE_URL, WORKER_KEY_FILE))
    rows: list[dict[str, Any]] = []
    for case in cases:
        effective_query, graph_matched, graph_expanded = resolve_evaluation_query(case["query"], args.graph_expand)
        hits, abstained, elapsed, model = retriever.retrieve(effective_query, 10)
        expected = case.get("expected")
        rows.append({
            "caseId": case["caseId"],
            "polarity": case["polarity"],
            "dimension": case["dimension"],
            "query": case["query"],
            "effectiveQuery": effective_query,
            "graphMatchedTerms": graph_matched,
            "graphExpandedTerms": graph_expanded,
            "requestPayload": {"query": case["query"], "limit": 10},
            "documentRank": document_rank(hits, expected),
            "blockRank": block_rank(hits, expected),
            "hitCount": len(hits),
            "abstained": abstained,
            "elapsedMs": elapsed,
            "rerankModel": model,
            "hits": [
                {
                    "rank": rank,
                    "docId": hit.get("doc_id"),
                    "sectionId": hit.get("section_id"),
                    "sectionTitle": hit.get("section_title"),
                    # pageNo is the actual child that earned recall; pageNos
                    # retains the parent small-heading's complete cross-page scope.
                    "pageNo": int(hit.get("page_no") or 0),
                    "pageNos": sorted({
                        int(member.get("page_no") or 0)
                        for member in retriever.members_for_candidate(hit)
                        if int(member.get("page_no") or 0) > 0
                    }),
                    "rerankScore": hit.get("_rerank_score"),
                    "text": normalized_text(hit.get("text"))[:240],
                }
                for rank, hit in enumerate(hits, 1)
            ],
        })
    report = {
        "kind": "independent_full_library_section_block_evaluation",
        "caseManifest": manifest,
        "corpusRows": len(corpus_rows(root)),
        "logicalBlockCount": len(retriever.block_index.members_by_key),
        "logicalBlockKey": "docId + sectionId + visibleSectionTitle",
        "pagesAreEvidenceInsideBlock": True,
        "publicRequestFields": ["query", "limit"],
        "graphExpansion": args.graph_expand,
        "profile": {
            "maxDocumentCandidates": PRODUCTION_PROFILE.max_document_candidates,
            "maxBlocksPerDocument": PRODUCTION_PROFILE.max_pages_per_document,
            "maxRerankDocuments": PRODUCTION_PROFILE.max_rerank_documents,
            "maxBlockCandidates": PRODUCTION_PROFILE.max_page_candidates,
        },
        "summary": summarize(rows),
    }
    output = args.output_dir.expanduser().resolve()
    write_json(output / "results.json", rows)
    write_json(output / "report.json", report)
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
