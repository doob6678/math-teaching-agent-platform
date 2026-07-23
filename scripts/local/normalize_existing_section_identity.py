"""Add deterministic section identities to an existing generated section library.

This only rewrites metadata and copies no model data.  All chunk text, chunkId,
page numbers, and vectors remain unchanged; blocks sharing the same chapter path
and subheading receive one stable sectionId for grouping and display.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
from pathlib import Path

from section_hierarchy import enrich_chapter_paths, enrich_definition_titles


DEFAULT_ROOT = Path(
    r"C:\Users\doob\Desktop\个人资料\高中数学\下载课本代码\tchMaterial-parser-main\tchMaterial-parser-main\processed_books_section_shadow_b3"
)


def normalize(value: object) -> str:
    return "".join(str(value or "").split()).lower()


TRAILING_PRINTED_PAGE_PATTERN = re.compile(r"(?<=[\u4e00-\u9fff])\d{1,3}$")


def canonical_heading(value: object) -> str:
    """Remove only a CJK-attached printed-page suffix from a visible heading.

    Textbook extractors often emit ``5.3 等比数列35`` on one PDF page and
    ``5.3 等比数列`` on the next.  The final digits are a printed page number,
    while dotted labels such as ``4.3.2`` must remain intact.  Requiring a Han
    character before the suffix avoids incorrectly truncating section numbers.
    """
    return TRAILING_PRINTED_PAGE_PATTERN.sub("", normalize(value))


def section_id(row: dict) -> str:
    chapter = row.get("chapter_path") if isinstance(row.get("chapter_path"), list) else [row.get("chapter_path", "")]
    source_pages = row.get("source_page_nos") or row.get("page_nos") or [row.get("page_no")]
    normalized_pages = ",".join(str(page) for page in source_pages if page is not None)
    # A post-processing tool cannot safely infer where a repeatedly emitted
    # chapter heading stops. Keep source-page identity here; only the extractor
    # itself may create a multi-page source group after it has proved an actual
    # continuation from layout/text structure. This prevents a generic title
    # such as “第二章 平面解析几何” from merging an entire chapter.
    identity = (
        " / ".join(canonical_heading(item) for item in chapter if str(item).strip())
        + " / "
        + canonical_heading(row.get("section_title"))
        + " / pages="
        + normalized_pages
    )
    digest = hashlib.sha1(identity.encode("utf-8")).hexdigest()[:12]
    return f"{row.get('doc_id', 'book')}__section_{digest}"


def read_jsonl(path: Path) -> list[dict]:
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def write_jsonl(path: Path, rows: list[dict]) -> None:
    path.write_text("\n".join(json.dumps(row, ensure_ascii=False, separators=(",", ":")) for row in rows) + "\n", encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser(description="Normalize stable sectionId fields in an existing section library")
    parser.add_argument("--root", type=Path, default=DEFAULT_ROOT)
    parser.add_argument("--restore-legacy", action="store_true", help="restore section_id from the audit-preserved legacy_section_id field")
    args = parser.parse_args()
    root = args.root.expanduser().resolve()
    total = 0
    hierarchy_enriched = 0
    definition_titles_enriched = 0
    changed_section_ids = 0
    all_rows: list[dict] = []
    for book_root in sorted(path for path in root.iterdir() if path.is_dir() and (path / "jsonl_ai" / "chunks.jsonl").exists()):
        chunks_path = book_root / "jsonl_ai" / "chunks.jsonl"
        rows = read_jsonl(chunks_path)
        if not args.restore_legacy:
            # Backfill only parent labels that are proven by an adjacent numbered
            # heading.  This repairs OCR page-boundary structure without changing
            # the extracted source text or introducing query-specific metadata.
            hierarchy_enriched += enrich_chapter_paths(rows)
            definition_titles_enriched += enrich_definition_titles(rows)
        for row in rows:
            if args.restore_legacy:
                legacy_id = str(row.get("legacy_section_id") or "")
                if not legacy_id:
                    raise ValueError(f"cannot restore a row without legacy_section_id: {row.get('chunk_id')}")
                row["section_id"] = legacy_id
                row.pop("legacy_section_id", None)
                changed_section_ids += 1
                total += 1
                continue
            canonical_id = section_id(row)
            legacy_id = str(row.get("section_id") or "")
            if legacy_id and legacy_id != canonical_id:
                # Keep the prior identity for data lineage. Runtime retrieval
                # reads section_id only, so this audit field cannot affect rank.
                row.setdefault("legacy_section_id", legacy_id)
                changed_section_ids += 1
            row["section_id"] = canonical_id
            total += 1
        write_jsonl(chunks_path, rows)
        all_rows.extend(rows)

    # The BGE metadata spans every book, so rewrite it once after collecting
    # all chunk identities. Updating it inside the book loop previously made
    # later books fall back to stale/generated identities.
    metadata_path = root / "_section_bge_index" / "metadata.jsonl"
    if metadata_path.exists():
        metadata = read_jsonl(metadata_path)
        by_chunk = {str(row.get("chunk_id") or ""): row for row in all_rows}
        for item in metadata:
            source = by_chunk.get(str(item.get("chunk_id") or ""))
            item["section_id"] = source.get("section_id") if source else section_id(item)
        write_jsonl(metadata_path, metadata)
    print(json.dumps({
        "root": str(root),
        "chunk_count": total,
        "changed_section_ids": changed_section_ids,
        "hierarchy_enriched": hierarchy_enriched,
        "definition_titles_enriched": definition_titles_enriched,
    }, ensure_ascii=False))


if __name__ == "__main__":
    main()
