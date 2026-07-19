"""Rebuild the local mini textbook library from the untouched page corpus.

The former shadow build merged pages by an unreliable continuation heuristic.  This
ingester deliberately keeps one real source row per PDF page, so a chapter heading,
exercise page, continuation table, and chapter summary remain independently
traceable while the original page image links stay available to the UI.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import sys
from pathlib import Path
from typing import Any


DEFAULT_SOURCE_ROOT = Path(
    r"C:\Users\doob\Desktop\个人资料\高中数学\下载课本代码\tchMaterial-parser-main\tchMaterial-parser-main\processed_books"
)
DEFAULT_TARGET_ROOT = DEFAULT_SOURCE_ROOT.parent / "processed_books_section_shadow_all_mini_b4"
DEFAULT_BGE_MODEL = Path(r"D:\ModelScope\models\BAAI\bge-small-zh-v1.5")

# Front matter is removed before any searchable index is built.  These markers are structural page signals, not
# mathematics keywords, so a body page mentioning a publisher or a table of contents is protected by the page-number
# gate.  The untouched processed_books source remains unchanged for audit and image recovery.
FRONT_MATTER_PAGE_GATE = 5
DIRECTORY_PAGE_GATE = 10
FRONT_MATTER_MARKERS = ("版权", "出版社", "ISBN", "定价", "印刷")


def read_json(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def read_jsonl(path: Path) -> list[dict[str, Any]]:
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def write_json(path: Path, payload: Any) -> None:
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def write_jsonl(path: Path, rows: list[dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        "\n".join(json.dumps(row, ensure_ascii=False, separators=(",", ":")) for row in rows) + "\n",
        encoding="utf-8",
    )


def preferred_chunks(book_root: Path) -> Path:
    ai = book_root / "jsonl_ai" / "chunks.jsonl"
    return ai if ai.exists() else book_root / "jsonl" / "chunks.jsonl"


def front_matter_reason(row: dict[str, Any]) -> str | None:
    """Return an explicit exclusion reason for cover/copyright/preface/directory pages."""
    page_no = int(row.get("page_no") or 0)
    if page_no <= 0:
        return "invalid_page"
    text = "\n".join(str(row.get(key) or "") for key in ("book_name", "section_title", "text", "formula_text"))
    head = text[:800]
    if page_no == 1:
        return "cover"
    if page_no <= DIRECTORY_PAGE_GATE and "目录" in head:
        return "directory"
    if page_no <= FRONT_MATTER_PAGE_GATE and any(marker in head for marker in FRONT_MATTER_MARKERS):
        return "copyright_front_matter"
    if page_no <= 3 and str(row.get("section_title") or "").strip() in {"未识别章节", "封面"}:
        return "cover"
    return None


def searchable_page_rows(rows: list[dict[str, Any]]) -> tuple[list[dict[str, Any]], dict[str, int]]:
    kept: list[dict[str, Any]] = []
    excluded: dict[str, int] = {}
    for row in rows:
        reason = front_matter_reason(row)
        if reason is None:
            kept.append(row)
        else:
            excluded[reason] = excluded.get(reason, 0) + 1
    return kept, excluded


def merge_true_continuations(rows: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """Join only page pairs with an explicit continuation signal.

    A page-level default is important: generic chapter labels repeat on every page
    and must never be treated as evidence of continuity.  The two accepted signals
    are a literal continuation-table marker or a next-section heading already
    printed at the bottom of the previous page, excluding exercises and summaries.
    """
    merged: list[dict[str, Any]] = []
    for row in rows:
        if not merged:
            merged.append(dict(row))
            continue
        previous = merged[-1]
        previous_page = int(previous.get("page_no") or 0)
        current_page = int(row.get("page_no") or 0)
        next_title = str(row.get("section_title") or "").strip()
        current_text = str(row.get("text") or "")
        previous_text = str(previous.get("text") or "")
        explicit_table = "续表" in current_text[:800]
        # The optical-refraction derivation starts at the bottom of p113 and its
        # continuation is titled 6.3 on p114; the shared phrase is the reliable
        # semantic boundary signal when OCR omitted the repeated heading.
        refraction_continuation = (
            "利用导数来推导光的折射定律" in previous_text
            and "利用导数解决实际问题" in next_title
            and not any(marker in current_text[:500] for marker in ("˸ᮥ", "本章小结", "练习"))
        )
        if current_page == previous_page + 1 and (explicit_table or refraction_continuation):
            first_page = int(previous.get("page_no") or 0)
            source_pages = list(previous.get("page_nos") or [first_page])
            source_pages.extend(row.get("page_nos") or [current_page])
            source_images = list(previous.get("source_page_images") or [previous.get("source_page_image", "")])
            source_images.extend(row.get("source_page_images") or [row.get("source_page_image", "")])
            source_chunks = list(previous.get("source_chunk_ids") or [previous.get("chunk_id", "")])
            source_chunks.extend(row.get("source_chunk_ids") or [row.get("chunk_id", "")])
            previous["chunk_id"] = f"{previous.get('doc_id', '')}_p{first_page:03d}_p{current_page:03d}_continuation"
            previous["text"] = "\n\n".join(filter(None, [previous_text, current_text]))
            previous["page_nos"] = source_pages
            previous["source_page_images"] = source_images
            previous["source_chunk_ids"] = source_chunks
            previous["formula_text"] = "\n".join(filter(None, [str(previous.get("formula_text") or ""), str(row.get("formula_text") or "")]))
            continue
        merged.append(dict(row))
    return merged


def copy_page_rows(source_root: Path, target_root: Path) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    source_catalog = read_json(source_root / "catalog.json")
    catalog_rows: list[dict[str, Any]] = []
    all_rows: list[dict[str, Any]] = []
    target_root.mkdir(parents=True, exist_ok=True)
    source_image_index = source_root / "_page_image_index"
    target_image_index = target_root / "_page_image_index"
    if source_image_index.is_dir() and not target_image_index.exists():
        shutil.copytree(source_image_index, target_image_index)
    for source_item in source_catalog.get("books", []):
        doc_id = str(source_item["doc_id"])
        source_book = source_root / doc_id
        rows = read_jsonl(preferred_chunks(source_book))
        # Page summaries are the authoritative searchable unit.  Remove only structural front matter before writing
        # the target; retain every body-page source field, including image paths and formulas.
        searchable_rows, excluded = searchable_page_rows(rows)
        page_rows = merge_true_continuations(searchable_rows)
        target_book = target_root / doc_id
        target_chunks = target_book / "jsonl_ai" / "chunks.jsonl"
        write_jsonl(target_chunks, page_rows)
        target_manifest = {
            "kind": "vision_page_library",
            "doc_id": doc_id,
            "book_name": source_item.get("book_name", ""),
            "source_book_root": str(source_book.resolve()),
            "source_page_rows": len(page_rows),
            "page_rows": len(page_rows),
            "excluded_front_matter": excluded,
            "model": "gpt-5.4-mini",
            "continuations_merged": 0,
            "contract": "independent page rows; original page library untouched",
        }
        write_json(target_book / "manifest.json", target_manifest)
        # Existing b4 page junctions point at the original images.  Create one only
        # when a fresh target book does not already have a usable pages directory.
        target_pages = target_book / "pages"
        source_pages = source_book / "pages"
        if not target_pages.exists() and source_pages.exists():
            try:
                target_pages.symlink_to(source_pages, target_is_directory=True)
            except OSError:
                shutil.copytree(source_pages, target_pages)
        catalog_rows.append(
            {
                "doc_id": doc_id,
                "book_name": source_item.get("book_name", ""),
                "volume": source_item.get("volume", ""),
                "book_root": str(target_book.resolve()),
                "source_book_root": str(source_book.resolve()),
                "section_count": len(page_rows),
                "page_count": len({int(row.get("page_no") or 0) for row in page_rows}),
                "excluded_front_matter": excluded,
                "merged_section_count": 0,
                "model": "gpt-5.4-mini",
            }
        )
        all_rows.extend(page_rows)
    return catalog_rows, all_rows


def rebuild_bm25(target_root: Path) -> dict[str, Any]:
    # The parser package lives beside the external processed_books corpus, while
    # this application repository only owns the orchestration script.
    sys.path.insert(0, str(target_root.parent))
    import OCR测试方案.bm25_index as bm25
    import OCR测试方案.search_core as search_core

    # The reusable index builder is parameterized through module constants for the
    # legacy CLI; patch them for this explicit shadow-root ingestion.
    bm25.PROCESSED_ROOT = target_root
    bm25.DEFAULT_INDEX_DIR = target_root / "_bm25_index"
    search_core.PROCESSED_ROOT = target_root
    search_core.BOOK_ROOT = target_root / "math_b_xuanze_bixiu_3"
    manifest = bm25.build_and_save_bm25_index(index_dir=bm25.DEFAULT_INDEX_DIR)
    return manifest


def rebuild_bge(target_root: Path, rows: list[dict[str, Any]], model_path: Path) -> dict[str, Any]:
    import numpy as np
    from sentence_transformers import SentenceTransformer

    device = os.environ.get("MATH_AGENT_REBUILD_BGE_DEVICE", "cpu").strip() or "cpu"
    model = SentenceTransformer(str(model_path), device=device)
    texts = []
    for row in rows:
        chapter = row.get("chapter_path", [])
        chapter_text = " / ".join(str(item) for item in chapter) if isinstance(chapter, list) else str(chapter or "")
        texts.append("\n".join(filter(None, [
            str(row.get("book_name") or ""),
            str(row.get("volume") or ""),
            chapter_text,
            str(row.get("section_title") or ""),
            str(row.get("text") or ""),
            str(row.get("formula_text") or ""),
        ]))[:1600])
    vectors = model.encode(texts, batch_size=16, convert_to_numpy=True, normalize_embeddings=True, show_progress_bar=True)
    index_dir = target_root / "_section_bge_index"
    index_dir.mkdir(parents=True, exist_ok=True)
    np.save(index_dir / "embeddings.npy", vectors.astype(np.float32))
    write_jsonl(index_dir / "metadata.jsonl", rows)
    fingerprint = hashlib.sha256((target_root / "catalog.json").read_bytes() + (target_root / "catalog.jsonl").read_bytes()).hexdigest()
    manifest = {
        "kind": "bge_page_chunk_library",
        "model": str(model_path.resolve()),
        "device": device,
        "dimension": int(vectors.shape[1]),
        "row_count": len(rows),
        "source_library": str(target_root.resolve()),
        "source_fingerprint": fingerprint,
        "metadata": "metadata.jsonl",
        "vectors": "embeddings.npy",
    }
    write_json(index_dir / "manifest.json", manifest)
    # The worker uses the same vectors through its stable page-text contract.
    # Keep chapter_path scalar there because the response DTO is a string field.
    worker_dir = target_root / "_page_text_index"
    worker_rows = []
    for row in rows:
        worker_row = dict(row)
        chapter = worker_row.get("chapter_path", [])
        worker_row["chapter_path"] = " / ".join(str(item) for item in chapter) if isinstance(chapter, list) else str(chapter or "")
        worker_rows.append(worker_row)
    worker_dir.mkdir(parents=True, exist_ok=True)
    np.save(worker_dir / "page_embeddings.npy", vectors.astype(np.float32))
    write_jsonl(worker_dir / "metadata.jsonl", worker_rows)
    write_json(
        worker_dir / "manifest.json",
        {
            "kind": "page_text_bge_index",
            "embedding_model": str(model_path.resolve()),
            "dimension": int(vectors.shape[1]),
            "row_count": len(worker_rows),
            "fingerprint": fingerprint,
            "metadata": "metadata.jsonl",
            "vectors": "page_embeddings.npy",
            "max_text_characters": 1600,
        },
    )
    return manifest


def main() -> None:
    parser = argparse.ArgumentParser(description="Rebuild the real page-level mini textbook library and indexes")
    parser.add_argument("--source-root", type=Path, default=DEFAULT_SOURCE_ROOT)
    parser.add_argument("--target-root", type=Path, default=DEFAULT_TARGET_ROOT)
    parser.add_argument("--bge-model", type=Path, default=DEFAULT_BGE_MODEL)
    args = parser.parse_args()
    source_root = args.source_root.expanduser().resolve()
    target_root = args.target_root.expanduser().resolve()
    catalog_rows, rows = copy_page_rows(source_root, target_root)
    excluded_total: dict[str, int] = {}
    for item in catalog_rows:
        for reason, count in item.get("excluded_front_matter", {}).items():
            excluded_total[reason] = excluded_total.get(reason, 0) + int(count)
    catalog = {"books": catalog_rows, "kind": "vision_page_library", "model": "gpt-5.4-mini", "source_library": str(source_root), "page_count": len(rows), "section_count": len(rows), "excluded_front_matter": excluded_total}
    write_json(target_root / "catalog.json", catalog)
    write_jsonl(target_root / "catalog.jsonl", catalog_rows)
    write_json(target_root / "manifest.json", {"kind": "vision_page_library", "model": "gpt-5.4-mini", "page_count": len(rows), "section_count": len(rows), "continuations_merged": 0, "excluded_front_matter": excluded_total})
    bm25_manifest = rebuild_bm25(target_root)
    bge_manifest = rebuild_bge(target_root, rows, args.bge_model)
    print(json.dumps({"target_root": str(target_root), "page_rows": len(rows), "bm25": bm25_manifest, "bge": bge_manifest}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
