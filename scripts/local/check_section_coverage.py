"""Fail loudly when a section (shadow) library silently under-covers its source page library.

2026-09-01 事故复盘：选必二的上游 AI 页级步骤只完成 4/142 页，旧构建"jsonl_ai 存在即优先"
把残缺静默继承进影子库，运行期表现为整章"查无此料"却没有任何环节报错。本脚本是建库流程的
覆盖度门禁：逐本比较源正文页集合与影子 chunk 覆盖页集合，低于阈值即非零退出，供
build_section_library_mini.py 之后、build_section_indexes.py 之前强制运行。
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any

DEFAULT_SOURCE_ROOT = Path(
    r"C:\Users\doob\Desktop\个人资料\高中数学\下载课本代码\tchMaterial-parser-main\tchMaterial-parser-main\processed_books"
)
DEFAULT_TARGET_ROOT = DEFAULT_SOURCE_ROOT.parent / "processed_books_section_shadow_all_mini_c2"
# 扉页/目录/版权页不参与覆盖度统计，与构建脚本的 FRONT_MATTER/DIRECTORY gate 保持一致。
BODY_PAGE_START = 11


def read_jsonl(path: Path) -> list[dict[str, Any]]:
    if not path.exists():
        return []
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def page_set(rows: list[dict[str, Any]]) -> set[int]:
    pages: set[int] = set()
    for row in rows:
        value = row.get("page_no")
        if isinstance(value, int) and value > 0:
            pages.add(value)
    return pages


def source_body_pages(book_root: Path) -> set[int]:
    pages = page_set(read_jsonl(book_root / "jsonl" / "chunks.jsonl")) | page_set(
        read_jsonl(book_root / "jsonl_ai" / "chunks.jsonl")
    )
    return {page for page in pages if page >= BODY_PAGE_START}


def shadow_body_pages(book_root: Path) -> set[int]:
    pages: set[int] = set()
    for row in read_jsonl(book_root / "jsonl_ai" / "chunks.jsonl"):
        values = row.get("source_page_nos")
        if isinstance(values, list):
            pages.update(int(value) for value in values if isinstance(value, int))
        elif isinstance(row.get("page_no"), int):
            pages.add(int(row["page_no"]))
    return {page for page in pages if page >= BODY_PAGE_START}


def main() -> int:
    parser = argparse.ArgumentParser(description="check section-library page coverage against the source page library")
    parser.add_argument("--source-root", type=Path, default=DEFAULT_SOURCE_ROOT)
    parser.add_argument("--target-root", type=Path, default=DEFAULT_TARGET_ROOT)
    parser.add_argument("--min-coverage", type=float, default=0.95)
    args = parser.parse_args()
    catalog = json.loads((args.source_root / "catalog.json").read_text(encoding="utf-8"))
    failures = 0
    for item in catalog.get("books", []):
        doc_id = str(item["doc_id"])
        src = source_body_pages(args.source_root / doc_id)
        tgt = shadow_body_pages(args.target_root / doc_id)
        ratio = len(src & tgt) / len(src) if src else 0.0
        status = "OK" if ratio >= args.min_coverage else "FAIL"
        if status == "FAIL":
            failures += 1
        missing = sorted(src - tgt)
        print(f"{status} {doc_id} source_body={len(src)} shadow_body={len(tgt)} coverage={ratio:.2%}"
              + (f" missing_pages={missing[:10]}{'...' if len(missing) > 10 else ''}" if missing else ""))
    if failures:
        print(f"coverage gate failed for {failures} book(s); rebuild the section library before indexing", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
