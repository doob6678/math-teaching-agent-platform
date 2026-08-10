"""Compare the existing page and section textbook indexes without re-ingestion.

The report deliberately keeps BM25, BGE cosine, and cross-encoder rerank results
separate.  It is a diagnostic comparison, not a training or test-set-specific
score adjustment.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any

import numpy as np


DEFAULT_LIBRARY_PARENT = Path(
    r"C:\Users\doob\Desktop\个人资料\高中数学\下载课本代码\tchMaterial-parser-main\tchMaterial-parser-main"
)
DEFAULT_BGE_MODEL = Path(r"D:\ModelScope\models\BAAI\bge-small-zh-v1.5")
DEFAULT_RERANK_MODEL = Path(r"D:\ModelScope\models\BAAI\bge-reranker-v2-m3")
BM25_CANDIDATE_COUNT = 10
SEMANTIC_CANDIDATE_COUNT = 10
RERANK_MAX_TOKENS = 512
REPORT_NAME = "textbook_page_vs_section_comparison.json"

FIXED_QUERIES = (
    "导数光学",
    "导数和光学",
    "利用导数来推导光的折射定律",
    "卡方与独立性检验",
    "双曲线定义",
    "数列平方和",
)


def read_jsonl(path: Path) -> list[dict[str, Any]]:
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def compact(value: str) -> str:
    return "".join(str(value or "").split()).lower()


def row_text(row: dict[str, Any]) -> str:
    chapter = row.get("chapter_path", [])
    if isinstance(chapter, list):
        chapter = " / ".join(str(value) for value in chapter)
    return "\n".join(filter(None, [
        str(row.get("book_name") or ""),
        str(row.get("volume") or ""),
        str(chapter or ""),
        str(row.get("section_title") or ""),
        str(row.get("text") or ""),
        str(row.get("formula_text") or ""),
    ]))


def row_summary(row: dict[str, Any], score: float | None = None) -> dict[str, Any]:
    page_no = row.get("page_no", row.get("pageNo", 0))
    chunk_id = row.get("chunk_id", row.get("chunkId", ""))
    section_id = row.get("section_id", row.get("sectionId", "")) or chunk_id
    chapter_path = row.get("chapter_path", row.get("chapterPath", []))
    section_title = row.get("section_title", row.get("sectionTitle", ""))
    chunk_type = row.get("chunk_type", row.get("chunkType", ""))
    text = row.get("text", "")
    return {
        "score": None if score is None else round(float(score), 6),
        "docId": row.get("doc_id", ""),
        "chunkId": chunk_id,
        "sectionId": section_id,
        "pageNo": page_no,
        "pageNos": row.get("page_nos") or row.get("source_page_nos") or row.get("pageNos") or [page_no],
        "chapterPath": chapter_path,
        "sectionTitle": section_title,
        "chunkType": chunk_type,
        "text": str(text or "")[:800],
    }


def load_bm25(rows: list[dict[str, Any]]):
    import OCR测试方案.bm25_index as bm25

    return bm25.build_bm25_index(rows)


def bm25_hits(index, query: str) -> list[dict[str, Any]]:
    hits, _ = index.search(query, limit=BM25_CANDIDATE_COUNT)
    ranked = sorted(hits, key=lambda item: item.score, reverse=True)
    return [row_summary(hit.row, hit.score) for hit in ranked[:BM25_CANDIDATE_COUNT]]


def load_embedding_index(index_dir: Path, vector_name: str) -> tuple[list[dict[str, Any]], np.ndarray]:
    rows = read_jsonl(index_dir / "metadata.jsonl")
    vectors = np.load(index_dir / vector_name, mmap_mode="r")
    if vectors.shape[0] != len(rows):
        raise RuntimeError(f"metadata/vector mismatch under {index_dir}: {len(rows)} != {vectors.shape[0]}")
    return rows, vectors


def semantic_hits(rows: list[dict[str, Any]], vectors: np.ndarray, query_vector: np.ndarray) -> list[dict[str, Any]]:
    scores = vectors @ query_vector
    indexes = np.argsort(-scores)[:SEMANTIC_CANDIDATE_COUNT]
    return [row_summary(rows[int(index)], float(scores[int(index)])) for index in indexes]


def dedupe_candidates(*groups: list[dict[str, Any]]) -> list[dict[str, Any]]:
    seen: set[str] = set()
    candidates: list[dict[str, Any]] = []
    for group in groups:
        for item in group:
            key = str(item.get("chunkId") or f"{item.get('docId')}#{item.get('pageNo')}#{item.get('sectionTitle')}")
            if key not in seen:
                seen.add(key)
                candidates.append(item)
    return candidates


def rerank(model, tokenizer, query: str, candidates: list[dict[str, Any]], torch_module: Any) -> list[dict[str, Any]]:
    if not candidates:
        return []
    documents = [str(item.get("sectionTitle") or "") + "\n" + str(item.get("chapterPath") or "") + "\n" + str(item.get("text") or "") for item in candidates]
    encoded = tokenizer(
        [query] * len(documents),
        documents,
        padding=True,
        truncation=True,
        max_length=RERANK_MAX_TOKENS,
        return_tensors="pt",
    )
    with torch_module.no_grad():
        logits = model(**encoded).logits.reshape(-1).cpu().numpy()
    order = np.argsort(-logits)
    return [row_summary(candidates[int(index)], float(logits[int(index)])) for index in order]


def expected_match(query: str, item: dict[str, Any]) -> bool:
    title = compact(str(item.get("sectionTitle") or ""))
    text = compact(str(item.get("text") or ""))
    if query in {"导数光学", "导数和光学", "利用导数来推导光的折射定律"}:
        return "利用导数来推导光的折射定律" in title or "利用导数来推导光的折射定律" in text
    if query == "卡方与独立性检验":
        return "独立性检验" in title or "卡方" in text
    if query == "双曲线定义":
        return "双曲线" in title and ("定义" in title or "定义" in text)
    if query == "数列平方和":
        return "数列" in title + text and ("平方和" in title + text or "前n项和" in title + text)
    return False


def main() -> None:
    parser = argparse.ArgumentParser(description="Compare existing textbook page and section retrieval indexes")
    parser.add_argument("--library-parent", type=Path, default=DEFAULT_LIBRARY_PARENT)
    parser.add_argument("--skip-reranker", action="store_true")
    args = parser.parse_args()
    parent = args.library_parent.expanduser().resolve()
    sys.path.insert(0, str(parent))
    import OCR测试方案.search_core as search_core

    # Compare the c2 page-evidence projection with its c2 section-child projection. Both are derived from the same
    # source pages, so a score difference measures chunk granularity rather than a hidden corpus-version change.
    page_root = parent / "processed_books_section_shadow_all_mini_c2"
    section_root = parent / "processed_books_section_shadow_all_mini_c2"
    page_rows = []
    for book_root in sorted(path for path in page_root.iterdir() if path.is_dir() and (path / "jsonl_ai" / "chunks.jsonl").exists()):
        page_rows.extend(read_jsonl(book_root / "jsonl_ai" / "chunks.jsonl"))
    section_rows = read_jsonl(section_root / "math_b_xuanze_bixiu_3" / "jsonl_ai" / "chunks.jsonl")
    page_bm25 = load_bm25(page_rows)
    section_bm25 = load_bm25(section_rows)
    section_metadata, section_vectors = load_embedding_index(section_root / "_section_bge_index", "embeddings.npy")
    page_metadata, page_vectors = load_embedding_index(page_root / "_page_text_index", "page_embeddings.npy")
    from sentence_transformers import SentenceTransformer

    bge_model = SentenceTransformer(str(args.library_parent / ".." / ".." / ".." / ".." / ".."), device="cpu") if False else SentenceTransformer(str(DEFAULT_BGE_MODEL), device="cpu")
    rerank_model = tokenizer = torch_module = None
    if not args.skip_reranker:
        from transformers import AutoModelForSequenceClassification, AutoTokenizer
        import torch

        tokenizer = AutoTokenizer.from_pretrained(str(DEFAULT_RERANK_MODEL), local_files_only=True)
        rerank_model = AutoModelForSequenceClassification.from_pretrained(str(DEFAULT_RERANK_MODEL), local_files_only=True)
        rerank_model.eval()
        torch_module = torch
    report: dict[str, Any] = {"kind": "existing_library_comparison", "pageRoot": str(page_root), "sectionRoot": str(section_root), "reranker": None if args.skip_reranker else str(DEFAULT_RERANK_MODEL), "queries": []}
    for query in FIXED_QUERIES:
        query_vector = bge_model.encode([query], normalize_embeddings=True)[0]
        page_bm = bm25_hits(page_bm25, query)
        section_bm = bm25_hits(section_bm25, query)
        page_bg = semantic_hits(page_metadata, page_vectors, query_vector)
        section_bg = semantic_hits(section_metadata, section_vectors, query_vector)
        section_candidates = dedupe_candidates(section_bm, section_bg)
        report["queries"].append({
            "query": query,
            "page": {"bm25": page_bm, "bge": page_bg},
            "section": {"bm25": section_bm, "bge": section_bg, "rerank": rerank(rerank_model, tokenizer, query, section_candidates, torch_module) if rerank_model else [], "expectedInTop5": any(expected_match(query, item) for item in section_candidates[:5])},
            "pageExpectedInTop5": any(expected_match(query, item) for item in page_bm[:5] + page_bg[:5]),
        })
    report_name = "textbook_page_vs_section_no_reranker.json" if args.skip_reranker else REPORT_NAME
    output = parent / "processed_books_section_shadow_b3" / report_name
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"output": str(output), "queries": len(report["queries"])}, ensure_ascii=False))


if __name__ == "__main__":
    main()
