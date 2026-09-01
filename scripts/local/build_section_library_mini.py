"""Build a resumable chapter/section textbook library with gpt-5.6-luna.

The source page library is immutable.  Each page is structurally annotated into
small titled sections, then only explicit cross-page continuations are joined.  A
separate output root and response cache make the page and section libraries
independently recoverable and make interrupted generation safe to resume.
"""

from __future__ import annotations

import argparse
import concurrent.futures
import hashlib
import json
import os
import re
import shutil
import time
from pathlib import Path
from typing import Any

import httpx

from section_hierarchy import enrich_chapter_paths, enrich_definition_titles
from normalize_existing_section_identity import section_id as stable_section_id


DEFAULT_SOURCE_ROOT = Path(
    r"C:\Users\doob\Desktop\个人资料\高中数学\下载课本代码\tchMaterial-parser-main\tchMaterial-parser-main\processed_books"
)
DEFAULT_TARGET_ROOT = DEFAULT_SOURCE_ROOT.parent / "processed_books_section_shadow_all_mini_c1"
DEFAULT_REUSE_BOOK_ROOT = DEFAULT_SOURCE_ROOT.parent / "processed_books_section_shadow_b3"
DEFAULT_MODEL = "gpt-5.6-luna"
DEFAULT_WORKERS = 4
DEFAULT_REQUEST_TIMEOUT_SECONDS = 180
DEFAULT_RETRY_COUNT = 3
DEFAULT_MAX_INPUT_CHARACTERS = 18_000
DEFAULT_MAX_OUTPUT_TOKENS = 6_000
DEFAULT_CONTINUATION_PAGE_GAP = 1
SECTION_CACHE_DIRECTORY = ".section_responses"
CONTENT_TYPES = {"section_prose", "section_example", "section_exercise", "section_formula", "section_definition", "section_theorem"}
FRONT_MATTER_PAGE_GATE = 5
DIRECTORY_PAGE_GATE = 10
FRONT_MATTER_MARKERS = ("版权", "出版社", "ISBN", "定价", "印刷")


def read_json(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def read_jsonl(path: Path) -> list[dict[str, Any]]:
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def write_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def write_jsonl(path: Path, rows: list[dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("\n".join(json.dumps(row, ensure_ascii=False, separators=(",", ":")) for row in rows) + "\n", encoding="utf-8")


def preferred_chunks(book_root: Path) -> Path:
    """Pick the page-chunk source with real coverage, not just presence.

    上游 AI 页级步骤可能中断后只留下部分 jsonl_ai（2026-09-01 选必二事故：4/142 页），
    旧实现"存在即优先"会让影子库静默继承残缺覆盖，检索侧表现为整章查无此料。
    当 jsonl_ai 覆盖页号少于机械 jsonl 时回退并告警，保证影子构建拿到完整页集。
    """
    ai = book_root / "jsonl_ai" / "chunks.jsonl"
    text = book_root / "jsonl" / "chunks.jsonl"
    if not ai.exists():
        return text
    if text.exists():
        ai_pages = {row.get("page_no") for row in read_jsonl(ai) if row.get("page_no")}
        text_pages = {row.get("page_no") for row in read_jsonl(text) if row.get("page_no")}
        if ai_pages < text_pages:
            print(
                f"warning: {book_root.name} jsonl_ai covers {len(ai_pages)}/{len(text_pages)} pages; "
                "falling back to jsonl for section build",
                flush=True,
            )
            return text
    return ai


def front_matter_reason(row: dict[str, Any]) -> str | None:
    """Keep section extraction focused on body pages while retaining the immutable source library."""
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


def normalize_base_url(value: str) -> str:
    base = value.rstrip("/")
    return base if base.endswith("/chat/completions") else base + "/chat/completions"


def compact(value: str) -> str:
    return re.sub(r"\s+", "", value or "").lower()


def source_body(row: dict[str, Any]) -> str:
    text = str(row.get("text") or "")
    formula = str(row.get("formula_text") or "")
    return "\n\n".join(part for part in (text, formula) if part.strip())


def clean_model_content(content: str) -> str:
    value = content.strip()
    if value.startswith("```"):
        value = re.sub(r"^```(?:json)?\s*", "", value, flags=re.IGNORECASE)
        value = re.sub(r"\s*```$", "", value)
    start = value.find("{")
    end = value.rfind("}")
    return value[start : end + 1] if start >= 0 and end > start else value


def section_prompt(row: dict[str, Any], max_input_characters: int, max_output_tokens: int) -> dict[str, Any]:
    page = {
        key: row.get(key)
        for key in ("doc_id", "book_name", "volume", "chapter_path", "section_title", "page_no", "printed_page_no", "text", "formula_text")
    }
    body = json.dumps(page, ensure_ascii=False)
    if len(body) > max_input_characters:
        page["text"] = str(page.get("text") or "")[:max_input_characters]
        page["formula_text"] = str(page.get("formula_text") or "")[: max_input_characters // 4]
    return {
        "model": DEFAULT_MODEL,
        "temperature": 0,
        "stream": False,
        "max_tokens": max_output_tokens,
        "messages": [
            {
                "role": "system",
                "content": "你是高中数学教材章节结构化入库助手。只输出严格JSON，不要Markdown，不要解释。",
            },
            {
                "role": "user",
                "content": (
                    "从下面一页教材中识别所有真实的小标题、例题、练习、定义、定理、公式和图注，并把正文归到最近的小标题。"
                    "不要改写、总结、补充或编造原文；公式保留在formula_text；本页没有标题时沿用页面已有章节上下文。"
                    "一个标题跨页时只返回本页实际正文。返回格式："
                    "{\"sections\":[{\"section_title\":\"\",\"chapter_path\":[],\"chunk_type\":\"section_heading|section_prose|section_example|section_exercise|section_figure_caption|section_formula|section_definition|section_theorem\",\"text\":\"\",\"formula_text\":\"\"}]}\n\n"
                    + json.dumps(page, ensure_ascii=False)
                ),
            },
        ],
    }


def fallback_sections(row: dict[str, Any]) -> list[dict[str, Any]]:
    title = str(row.get("section_title") or "").strip()
    chapter = row.get("chapter_path") if isinstance(row.get("chapter_path"), list) else [str(row.get("chapter_path") or "")]
    chapter = [str(item).strip() for item in chapter if str(item).strip()]
    return [{
        "section_title": title or (chapter[-1] if chapter else "未识别章节"),
        "chapter_path": chapter,
        "chunk_type": "section_prose",
        "text": str(row.get("text") or ""),
        "formula_text": str(row.get("formula_text") or ""),
    }]


def call_page(row: dict[str, Any], args: argparse.Namespace) -> tuple[dict[str, Any], dict[str, Any]]:
    doc_id = str(row.get("doc_id") or "book")
    page_no = int(row.get("page_no") or 0)
    cache_path = args.target_root / SECTION_CACHE_DIRECTORY / doc_id / f"p{page_no:04d}.json"
    source_hash = hashlib.sha256(source_body(row).encode("utf-8")).hexdigest()
    if cache_path.exists():
        cached = read_json(cache_path)
        if cached.get("source_hash") == source_hash and isinstance(cached.get("sections"), list):
            return row, cached

    payload = section_prompt(row, args.max_input_characters, args.max_output_tokens)
    headers = {"Authorization": f"Bearer {args.api_key}", "Content-Type": "application/json"}
    last_error = ""
    for attempt in range(1, args.retry_count + 1):
        try:
            response = httpx.post(
                args.endpoint,
                headers=headers,
                json=payload,
                timeout=args.timeout_seconds,
            )
            response.raise_for_status()
            provider = response.json()
            content = provider["choices"][0]["message"]["content"]
            parsed = json.loads(clean_model_content(str(content)))
            sections = parsed.get("sections") if isinstance(parsed, dict) else None
            if not isinstance(sections, list) or not sections:
                raise ValueError("model returned no sections")
            result = {"source_hash": source_hash, "model": args.model, "sections": sections, "provider_id": provider.get("id", "")}
            write_json(cache_path, result)
            return row, result
        except Exception as exc:  # noqa: PERF203：重试属于外部 API 调用约定的一部分。
            last_error = f"{type(exc).__name__}: {exc}"
            if attempt < args.retry_count:
                time.sleep(min(2 ** (attempt - 1), 8))
    result = {"source_hash": source_hash, "model": args.model, "sections": fallback_sections(row), "fallback": True, "error": last_error}
    write_json(cache_path, result)
    return row, result


def clean_section(section: dict[str, Any], row: dict[str, Any]) -> dict[str, Any] | None:
    if not isinstance(section, dict):
        return None
    title = str(section.get("section_title") or "").strip()
    text = str(section.get("text") or "").strip()
    formula = str(section.get("formula_text") or "").strip()
    chunk_type = str(section.get("chunk_type") or "section_prose").strip()
    if chunk_type == "section_section_prose":
        chunk_type = "section_prose"
    if chunk_type not in CONTENT_TYPES and chunk_type not in {"section_heading", "section_figure_caption"}:
        chunk_type = "section_prose"
    inherited = row.get("chapter_path") if isinstance(row.get("chapter_path"), list) else [str(row.get("chapter_path") or "")]
    chapter = section.get("chapter_path") if isinstance(section.get("chapter_path"), list) else inherited
    chapter = [str(item).strip() for item in chapter if str(item).strip()]
    if not title and chapter:
        title = chapter[-1]
    if not title and not text and not formula:
        return None
    return {"section_title": title or "未识别章节", "chapter_path": chapter or ["未识别章节"], "chunk_type": chunk_type, "text": text, "formula_text": formula}


def section_rows_for_page(row: dict[str, Any], result: dict[str, Any]) -> list[dict[str, Any]]:
    # 一页教材可能同时包含定义、正文、例题等多个结构单元；这里将每个单元保留为独立 child，
    # 以便后续检索能够精确返回其页码、片段和原始页面图片。
    cleaned = [item for item in (clean_section(section, row) for section in result.get("sections", [])) if item]
    if not cleaned:
        cleaned = fallback_sections(row)
    source_chunk_id = str(row.get("chunk_id") or f"{row.get('doc_id')}_p{row.get('page_no')}")
    base = {key: row.get(key) for key in ("doc_id", "book_name", "volume", "page_no", "printed_page_no", "source_pdf", "source_page_image", "image_rel_paths")}
    output: list[dict[str, Any]] = []
    for index, section in enumerate(cleaned, start=1):
        # 初始 section_id 只在页面内唯一；跨页合并与层级修复完成后会重算稳定语义 ID。
        section_id = f"{source_chunk_id}__section_{index:03d}"
        item = {
            **base,
            **section,
            "chunk_id": section_id,
            "section_id": section_id,
            "source_chunk_id": source_chunk_id,
            "source_page_nos": [int(row.get("page_no") or 0)],
            "source_page_images": [str(row.get("source_page_image") or "")],
            "source_library": "page_library",
            "layout_role": "heading" if section["chunk_type"] == "section_heading" else "prose",
            "section_extractor_model": DEFAULT_MODEL,
        }
        output.append(item)
    return output


def title_key(value: str) -> str:
    value = re.sub(r"\d+\s*$", "", value or "")
    value = re.sub(r"^[第\d\.\-\s]+", "", value)
    return compact(value)


def merge_continuations(rows: list[dict[str, Any]]) -> list[dict[str, Any]]:
    # child 默认按页保留；仅在标题/内容类型明确连续、页码相邻或属于续表时合并，
    # 防止同一小节被跨页截断，也避免把相邻但无关的教材内容拼接在一起。
    merged: list[dict[str, Any]] = []
    for row in rows:
        if not merged:
            merged.append(row)
            continue
        previous = merged[-1]
        prev_page = max(previous.get("source_page_nos") or [previous.get("page_no", 0)])
        current_page = int(row.get("page_no") or 0)
        same_title = title_key(previous.get("section_title", "")) == title_key(row.get("section_title", ""))
        current_text = str(row.get("text") or "")
        explicit_table = "续表" in current_text[:400]
        same_content_type = previous.get("chunk_type") in CONTENT_TYPES and row.get("chunk_type") in CONTENT_TYPES
        refraction = "利用导数来推导光的折射定律" in str(previous.get("section_title") or "") and "利用导数解决实际问题" in str(row.get("section_title") or "")
        if current_page <= prev_page + DEFAULT_CONTINUATION_PAGE_GAP and same_content_type and (same_title or explicit_table or refraction):
            # 合并后仍保留所有来源页，保证展示引用和教材页图能够追溯到真实原始证据。
            previous["text"] = "\n\n".join(item for item in (str(previous.get("text") or ""), current_text) if item)
            previous["formula_text"] = "\n".join(item for item in (str(previous.get("formula_text") or ""), str(row.get("formula_text") or "")) if item)
            previous["source_page_nos"] = list(previous.get("source_page_nos") or []) + list(row.get("source_page_nos") or [current_page])
            previous["source_page_images"] = list(previous.get("source_page_images") or []) + list(row.get("source_page_images") or [])
            previous["page_nos"] = previous["source_page_nos"]
            previous["source_page_image"] = previous["source_page_images"][0] if previous["source_page_images"] else previous.get("source_page_image", "")
            previous["chunk_id"] = f"{previous['doc_id']}_p{previous['source_page_nos'][0]:03d}_p{current_page:03d}__{title_key(previous.get('section_title', 'section'))[:48]}"
            previous["section_id"] = previous["chunk_id"]
            continue
        merged.append(row)
    return merged


def reuse_existing_book(source_root: Path, target_root: Path, doc_id: str) -> list[dict[str, Any]] | None:
    source = source_root / doc_id / "jsonl_ai" / "chunks.jsonl"
    if not source.exists():
        return None
    rows, _ = searchable_page_rows(read_jsonl(source))
    manifest = source_root / doc_id / "manifest.json"
    manifest_model = str(read_json(manifest).get("model") or "") if manifest.exists() else ""
    row_model = any(str(row.get("section_extractor_model") or "") == DEFAULT_MODEL for row in rows)
    if not rows or not (row_model or manifest_model == DEFAULT_MODEL):
        return None
    target_book = target_root / doc_id
    target_book.mkdir(parents=True, exist_ok=True)
    write_jsonl(target_book / "jsonl_ai" / "chunks.jsonl", rows)
    return rows


def build_book(book_id: str, source_book: Path, target_root: Path, args: argparse.Namespace) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    # Reuse a completed target book first so an interrupted all-book build can be resumed without re-calling the
    # section extractor.  The page library remains the source of truth and is never modified by this shortcut.
    completed_target = reuse_existing_book(target_root, target_root, book_id)
    if completed_target is not None:
        return completed_target, {"reused": True, "fallback_pages": 0, "source_page_rows": len({row.get("source_chunk_id") for row in completed_target})}
    reused = reuse_existing_book(DEFAULT_REUSE_BOOK_ROOT, target_root, book_id)
    if reused is not None:
        return reused, {"reused": True, "fallback_pages": 0, "source_page_rows": len({row.get('source_chunk_id') for row in reused})}
    pages = read_jsonl(preferred_chunks(source_book))
    page_rows, excluded = searchable_page_rows(pages)
    section_rows: list[dict[str, Any]] = []
    fallback_pages = 0
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.workers) as executor:
        futures = [executor.submit(call_page, row, args) for row in page_rows]
        for future in concurrent.futures.as_completed(futures):
            row, result = future.result()
            if result.get("fallback"):
                fallback_pages += 1
            section_rows.extend(section_rows_for_page(row, result))
    section_rows.sort(key=lambda item: (int(item.get("page_no") or 0), item.get("chunk_id", "")))
    merged = merge_continuations(section_rows)
    # Preserve parent subject context when the OCR split a numbered child and
    # its real parent heading across neighboring pages.  The helper is generic
    # to every book and never receives a retrieval query.
    enrich_chapter_paths(merged)
    # Number-only OCR headings otherwise hide an explicit definition from the
    # index.  The helper derives a display/search suffix solely from the block
    # text and retains the source title separately for audit and rendering.
    enrich_definition_titles(merged)
    # Assign one semantic identity after hierarchy repair and continuation merge.
    # Page-local provisional ids are useful while extracting, but the serving
    # corpus must group the same visible subheading across its source pages.
    for row in merged:
        row["section_id"] = stable_section_id(row)
    target_book = target_root / book_id
    target_book.mkdir(parents=True, exist_ok=True)
    write_jsonl(target_book / "jsonl_ai" / "chunks.jsonl", merged)
    return merged, {"reused": False, "fallback_pages": fallback_pages, "source_page_rows": len(page_rows), "excluded_front_matter": excluded}


def main() -> None:
    parser = argparse.ArgumentParser(description="Build all-book chapter/section textbook chunks with gpt-5.6-luna")
    parser.add_argument("--source-root", type=Path, default=DEFAULT_SOURCE_ROOT)
    parser.add_argument("--target-root", type=Path, default=DEFAULT_TARGET_ROOT)
    parser.add_argument("--model", default=DEFAULT_MODEL, choices=[DEFAULT_MODEL])
    parser.add_argument("--workers", type=int, default=DEFAULT_WORKERS)
    parser.add_argument("--timeout-seconds", type=int, default=DEFAULT_REQUEST_TIMEOUT_SECONDS)
    parser.add_argument("--retry-count", type=int, default=DEFAULT_RETRY_COUNT)
    parser.add_argument("--max-input-characters", type=int, default=DEFAULT_MAX_INPUT_CHARACTERS)
    parser.add_argument("--max-output-tokens", type=int, default=DEFAULT_MAX_OUTPUT_TOKENS)
    # 单本重建：影子库历史模型（gpt-5.4-mini）不满足 reuse 的 luna 校验，全量跑会重做所有书；
    # 事故修复只需要坏掉的那本（2026-09-01 选必二）。可重复传，缺省保持旧的全量行为。
    parser.add_argument("--book", action="append", default=None, help="仅构建指定 doc_id（可重复）；缺省构建全部")
    args = parser.parse_args()
    args.source_root = args.source_root.expanduser().resolve()
    args.target_root = args.target_root.expanduser().resolve()
    args.workers = max(1, args.workers)
    args.timeout_seconds = max(30, args.timeout_seconds)
    args.retry_count = max(1, args.retry_count)
    args.max_input_characters = max(2000, args.max_input_characters)
    args.max_output_tokens = max(1000, args.max_output_tokens)
    args.model = args.model
    args.api_key = os.environ.get("OPENAI_API_KEY", "").strip()
    if not args.api_key:
        raise SystemExit("OPENAI_API_KEY is required for real mini section extraction")
    args.endpoint = normalize_base_url(os.environ.get("OPENAI_BASE_URL", "https://api.openai.com/v1"))
    args.target_root.mkdir(parents=True, exist_ok=True)
    catalog = read_json(args.source_root / "catalog.json")
    # --book 单本模式下，未重建的书必须原样保留目标 catalog 条目，否则整库 catalog 会被缩成一本。
    existing_target_catalog: dict[str, dict[str, Any]] = {}
    target_catalog_path = args.target_root / "catalog.json"
    if args.book and target_catalog_path.exists():
        existing_target_catalog = {
            str(item.get("doc_id")): item for item in read_json(target_catalog_path).get("books", [])
        }
    catalog_rows: list[dict[str, Any]] = []
    total_rows = 0
    for item in catalog.get("books", []):
        doc_id = str(item["doc_id"])
        if args.book and doc_id not in args.book:
            kept = existing_target_catalog.get(doc_id)
            if kept is None:
                raise SystemExit(f"--book mode: target catalog has no entry for untouched book {doc_id}")
            catalog_rows.append(kept)
            total_rows += int(kept.get("section_count") or 0)
            continue
        rows, stats = build_book(doc_id, args.source_root / doc_id, args.target_root, args)
        target_book = args.target_root / doc_id
        source_pages = args.source_root / doc_id / "pages"
        target_pages = target_book / "pages"
        if source_pages.exists() and not target_pages.exists():
            try:
                target_pages.symlink_to(source_pages, target_is_directory=True)
            except OSError:
                shutil.copytree(source_pages, target_pages)
        write_json(target_book / "manifest.json", {
            "kind": "vision_section_library",
            "doc_id": doc_id,
            "book_name": item.get("book_name", ""),
            "source_book_root": item.get("source_book_root", ""),
            "source_page_rows": stats["source_page_rows"],
            "section_rows": len(rows),
            "model": DEFAULT_MODEL,
            "fallback_pages": stats["fallback_pages"],
            "reused_existing_book": stats["reused"],
            "contract": "section-level chunks with explicit source pages; page library untouched",
        })
        total_rows += len(rows)
        catalog_rows.append({
            "doc_id": doc_id,
            "book_name": item.get("book_name", ""),
            "volume": item.get("volume", ""),
            "book_root": str(target_book.resolve()),
            "source_book_root": item.get("source_book_root", ""),
            "source_page_rows": stats["source_page_rows"],
            "section_count": len(rows),
            "fallback_pages": stats["fallback_pages"],
            "reused_existing_book": stats["reused"],
        })
        print(json.dumps({"doc_id": doc_id, "sections": len(rows), **stats}, ensure_ascii=False), flush=True)
    write_json(args.target_root / "catalog.json", {"kind": "vision_section_library", "model": DEFAULT_MODEL, "source_library": str(args.source_root), "book_count": len(catalog_rows), "section_count": total_rows, "books": catalog_rows})
    write_jsonl(args.target_root / "catalog.jsonl", catalog_rows)
    write_json(args.target_root / "manifest.json", {"kind": "vision_section_library", "model": DEFAULT_MODEL, "source_library": str(args.source_root), "book_count": len(catalog_rows), "section_count": total_rows, "page_library_untouched": True})


if __name__ == "__main__":
    main()
