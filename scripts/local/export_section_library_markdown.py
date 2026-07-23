"""Export an existing section JSONL library into human-readable Markdown views.

This is a read-only presentation step: it never changes chunks, indexes, or the
original processed_books pages.  The generated files keep chunk identity and page
image links visible for manual retrieval-quality inspection.
"""

from __future__ import annotations

import argparse
import json
import re
from collections import defaultdict
from pathlib import Path


DEFAULT_LIBRARY_ROOT = Path(
    r"C:\Users\doob\Desktop\个人资料\高中数学\下载课本代码\tchMaterial-parser-main\tchMaterial-parser-main\processed_books_section_shadow_b3"
)
# Keep the generated path below Windows MAX_PATH even when the library root is deeply nested.
MAX_SECTION_FILENAME_LENGTH = 40


def safe_slug(value: str, fallback: str) -> str:
    cleaned = re.sub(r"[^\w\-]+", "_", value.strip(), flags=re.UNICODE).strip("_")
    return (cleaned or fallback)[:MAX_SECTION_FILENAME_LENGTH]


def read_rows(path: Path) -> list[dict]:
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def page_image_markdown(row: dict, source_book_root: Path) -> str:
    image = str(row.get("source_page_image") or "").strip()
    if not image:
        return ""
    image_path = (source_book_root / image).resolve().as_posix()
    return f"![p{int(row.get('page_no') or 0):03d}]({image_path})"


def render_row(row: dict, source_book_root: Path) -> str:
    chapter = " / ".join(str(value) for value in row.get("chapter_path", []) if str(value).strip())
    lines = [
        f"### {row.get('section_title') or '未命名小节'}",
        "",
        f"- chunkId: `{row.get('chunk_id', '')}`",
        f"- sectionId: `{row.get('section_id') or row.get('chunk_id', '')}`",
        f"- chunkType: `{row.get('chunk_type', '')}`",
        f"- chapterPath: {chapter}",
        f"- PDF 页码: {row.get('page_no', '')}",
        f"- 原始页块: `{row.get('source_chunk_id', '')}`",
        "",
    ]
    image = page_image_markdown(row, source_book_root)
    if image:
        lines.extend([image, ""])
    text = str(row.get("text") or "").strip()
    formula = str(row.get("formula_text") or "").strip()
    if text:
        lines.extend(["#### 正文", "", text, ""])
    if formula:
        lines.extend(["#### 公式", "", formula, ""])
    return "\n".join(lines).rstrip()


def render_section_group(rows: list[dict], source_book_root: Path) -> str:
    """Render one small-heading group while retaining every source block in order."""
    first = rows[0]
    title = first.get("section_title") or "未命名小节"
    section_id = first.get("section_id") or first.get("chunk_id") or ""
    chapter = " / ".join(str(value) for value in first.get("chapter_path", []) if str(value).strip())
    lines = [
        f"## {title}",
        "",
        f"- sectionId: `{section_id}`",
        f"- chapterPath: {chapter}",
        f"- 块数量: `{len(rows)}`",
        "",
    ]
    for index, row in enumerate(sorted(rows, key=lambda item: (int(item.get("page_no") or 0), str(item.get("chunk_id") or ""))), start=1):
        lines.extend([f"### 块 {index}: {row.get('chunk_type') or 'section_prose'}", ""])
        lines.append(render_row(row, source_book_root))
        lines.append("")
    return "\n".join(lines).rstrip()


def main() -> None:
    parser = argparse.ArgumentParser(description="Export an existing section JSONL library to Markdown")
    parser.add_argument("--library-root", type=Path, default=DEFAULT_LIBRARY_ROOT)
    args = parser.parse_args()
    root = args.library_root.expanduser().resolve()
    for book_root in sorted(path for path in root.iterdir() if path.is_dir() and (path / "jsonl_ai" / "chunks.jsonl").exists()):
        rows = read_rows(book_root / "jsonl_ai" / "chunks.jsonl")
        manifest_path = book_root / "manifest.json"
        manifest = json.loads(manifest_path.read_text(encoding="utf-8")) if manifest_path.exists() else {}
        source_book_root = Path(str(manifest.get("source_book_root") or "")).expanduser()
        output_root = book_root / "markdown_sections"
        pages_root = output_root / "pages"
        sections_root = output_root / "sections"
        pages_root.mkdir(parents=True, exist_ok=True)
        sections_root.mkdir(parents=True, exist_ok=True)
        by_page: dict[int, list[dict]] = defaultdict(list)
        for row in rows:
            by_page[int(row.get("page_no") or 0)].append(row)
        by_section: dict[str, list[dict]] = defaultdict(list)
        for row in rows:
            by_section[str(row.get("section_id") or row.get("chunk_id") or "")].append(row)
        index_lines = [
            f"# {book_root.name} 章节/小标题库",
            "",
            f"共 `{len(by_section)}` 个小标题，`{len(rows)}` 个原始块。",
            "",
        ]
        for page_no in sorted(by_page):
            page_rows = by_page[page_no]
            page_file = pages_root / f"p{page_no:03d}.md"
            page_content = [f"# PDF p{page_no:03d}", ""] + [render_row(row, source_book_root) for row in page_rows]
            page_file.write_text("\n\n".join(page_content) + "\n", encoding="utf-8")
            index_lines.append(f"- [PDF p{page_no:03d}](pages/p{page_no:03d}.md)：{len(page_rows)} 个块")
        for group_index, (_, group_rows) in enumerate(sorted(by_section.items()), start=1):
            # One file per small heading makes all heading-owned blocks inspectable together.
            title_slug = safe_slug(str(group_rows[0].get("section_title") or "section"), f"section_{group_index:04d}")
            section_file = sections_root / f"section_{group_index:04d}_{title_slug}.md"
            section_file.write_text(
                f"# {book_root.name}\n\n{render_section_group(group_rows, source_book_root)}\n",
                encoding="utf-8",
            )
        (output_root / "index.md").write_text("\n".join(index_lines) + "\n", encoding="utf-8")
        print(json.dumps({"book": book_root.name, "rows": len(rows), "index": str((output_root / 'index.md').resolve())}, ensure_ascii=False))


if __name__ == "__main__":
    main()
