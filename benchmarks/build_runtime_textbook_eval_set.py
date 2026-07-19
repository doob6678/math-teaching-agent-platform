from __future__ import annotations

import argparse
import json
import re
import time
from pathlib import Path
from typing import Any


DEFAULT_PROCESSED_BOOKS_ROOT = Path(
    r"C:\Users\doob\Desktop\个人资料\高中数学\下载课本代码\tchMaterial-parser-main\tchMaterial-parser-main\processed_books"
)
DEFAULT_OUTPUT_ROOT = Path("output") / "benchmarks"

QUERY_TEMPLATES = [
    "备课时只查公共教材库，我想找{topic}这一页里关于{anchor}的课本原文依据。",
    "请在textbook里定位{topic}相关页块，我要核对教材里提到{anchor}的表述。",
    "课堂讲到{topic}时卡住了，去公共教材库找出现{anchor}的那一页原文，不要其他资料。",
    "只做教材检索，帮我找{topic}这一节里和{anchor}有关的正文证据。",
    "我现在只看教材原文，定位{topic}页块，重点核对{anchor}这部分怎么写。",
    "请在公共教材页里找{topic}对应内容，我要看和{anchor}有关的教材原句。",
]

GENERIC_SECTION_TITLES = {
    "未识别章节",
    "目录",
    "前言",
}

GENERIC_TEXT_MARKERS = (
    "目录",
    "前言",
    "编著",
    "版权所有",
    "普通高中教科书",
    "课程标准",
)

NOISY_SENTENCE_MARKERS = (
    "页图",
    "PDF页码",
    "印刷页码",
    "未识别",
)


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Build a runtime-authored textbook-only evaluation set from real processed_books chunks."
    )
    parser.add_argument(
        "--processed-books-root",
        default=str(DEFAULT_PROCESSED_BOOKS_ROOT),
        help="processed_books root used by the live Java textbook retriever",
    )
    parser.add_argument(
        "--case-count",
        type=int,
        default=12,
        help="maximum number of textbook cases to emit",
    )
    parser.add_argument(
        "--output-path",
        default="",
        help="optional output JSON path; defaults under output/benchmarks/<run>/runtime-authored/",
    )
    args = parser.parse_args()

    processed_books_root = Path(args.processed_books_root).expanduser().resolve()
    output_path = (
        Path(args.output_path)
        if args.output_path
        else _default_output_path()
    )
    output_path.parent.mkdir(parents=True, exist_ok=True)

    cases, selected_count = build_cases(processed_books_root, max(1, args.case_count))
    payload = {
        "datasetVersion": f"textbook-runtime-authored-{time.strftime('%Y%m%d-%H%M%S')}",
        "generatedAt": time.strftime("%Y-%m-%dT%H:%M:%S"),
        "policy": {
            "authoredAtRuntime": True,
            "notCommittedToRepo": True,
            "source": "processed_books",
            "sourceRoot": str(processed_books_root),
            "caseCount": len(cases),
            "selectedBookCount": selected_count,
            "usesBlockText": False,
            "maxQueriesPerPositiveTarget": 1,
            "negativeRatio": 0.0,
        },
        "schema": {
            "case_type": "positive",
            "expected_library": "textbook",
            "expected_role": "reference",
            "expected_scope": "PUBLIC_TEXTBOOK",
        },
        "cases": cases,
    }
    output_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({"outputPath": str(output_path.resolve()), "caseCount": len(cases)}, ensure_ascii=False, indent=2))


def build_cases(processed_books_root: Path, case_count: int) -> tuple[list[dict[str, Any]], int]:
    catalog_path = processed_books_root / "catalog.jsonl"
    catalog_rows = [json.loads(line) for line in catalog_path.read_text(encoding="utf-8").splitlines() if line.strip()]
    cases: list[dict[str, Any]] = []
    selected_books = 0
    for book_index, book in enumerate(catalog_rows):
        if len(cases) >= case_count:
            break
        chunk_path = preferred_chunk_path(Path(book["book_root"]))
        if chunk_path is None:
            continue
        chunks = [json.loads(line) for line in chunk_path.read_text(encoding="utf-8").splitlines() if line.strip()]
        candidates = candidate_chunks(chunks)
        if not candidates:
            continue
        selected = select_chunk(candidates)
        if selected is None:
            continue
        selected_books += 1
        query = build_query(book_index, book, selected)
        topic = build_topic(selected)
        difficulty = classify_difficulty(selected)
        expected_topk = 1 if difficulty == "easy" else 3
        cases.append({
            "case_id": f"textbook-runtime-{len(cases) + 1:02d}",
            "case_type": "positive",
            "query": query,
            "topic": topic,
            "difficulty": difficulty,
            "user_type": "teacher",
            "fail_type": "sibling_block",
            "expected_topk": expected_topk,
            "expected_library": "textbook",
            "expected_document_id": str(selected["doc_id"]),
            "expected_block_id": str(selected["chunk_id"]),
            "expected_role": "reference",
            "expected_scope": "PUBLIC_TEXTBOOK",
            "expected_page_no": int(selected.get("page_no") or 0),
            "expected_source_path": f"textbook://{selected['doc_id']}/page/{selected.get('page_no') or 0}#chunk={selected['chunk_id']}",
            "book_name": str(selected.get("book_name") or ""),
            "section_title": str(selected.get("section_title") or ""),
            "chapter_path": list(selected.get("chapter_path") or []),
        })
    return cases, selected_books


def preferred_chunk_path(book_root: Path) -> Path | None:
    ai_chunks = book_root / "jsonl_ai" / "chunks.jsonl"
    if ai_chunks.exists():
        return ai_chunks
    plain_chunks = book_root / "jsonl" / "chunks.jsonl"
    if plain_chunks.exists():
        return plain_chunks
    return None


def candidate_chunks(chunks: list[dict[str, Any]]) -> list[dict[str, Any]]:
    candidates: list[dict[str, Any]] = []
    for chunk in chunks:
        if str(chunk.get("chunk_type") or "") != "page_summary":
            continue
        page_no = int(chunk.get("page_no") or 0)
        if page_no <= 8:
            continue
        section_title = clean_text(str(chunk.get("section_title") or ""))
        if section_title in GENERIC_SECTION_TITLES:
            continue
        body = extract_body_text(chunk)
        if len(body) < 80:
            continue
        if any(marker in body[:120] for marker in GENERIC_TEXT_MARKERS):
            continue
        anchor = anchor_phrase(body)
        if len(anchor) < 6:
            continue
        normalized = dict(chunk)
        normalized["_body"] = body
        normalized["_anchor"] = anchor
        candidates.append(normalized)
    return candidates


def select_chunk(candidates: list[dict[str, Any]]) -> dict[str, Any] | None:
    if not candidates:
        return None
    ordered = sorted(candidates, key=lambda item: int(item.get("page_no") or 0))
    target_index = min(len(ordered) - 1, max(0, (len(ordered) * 3) // 5))
    return ordered[target_index]


def build_query(book_index: int, book: dict[str, Any], chunk: dict[str, Any]) -> str:
    topic = build_topic(chunk)
    anchor = query_anchor(topic, str(chunk.get("_anchor") or ""), str(chunk.get("_body") or ""))
    template = QUERY_TEMPLATES[book_index % len(QUERY_TEMPLATES)]
    return template.format(
        topic=topic,
        anchor=anchor,
        book=compact_book_name(str(book.get("book_name") or "")),
    )


def build_topic(chunk: dict[str, Any]) -> str:
    section_title = clean_text(str(chunk.get("section_title") or ""))
    chapter_path = [clean_text(str(value)) for value in (chunk.get("chapter_path") or []) if clean_text(str(value))]
    if section_title and section_title not in GENERIC_SECTION_TITLES and contains_cjk(section_title):
        return section_title
    for value in reversed(chapter_path):
        if contains_cjk(value) and value not in GENERIC_SECTION_TITLES:
            return value
    if chapter_path:
        return chapter_path[-1]
    return compact_book_name(str(chunk.get("book_name") or "教材内容"))


def classify_difficulty(chunk: dict[str, Any]) -> str:
    anchor = str(chunk.get("_anchor") or "")
    formula = clean_text(str(chunk.get("formula_text") or ""))
    if formula and len(formula) > 80:
        return "hard"
    if len(anchor) >= 12:
        return "easy"
    return "medium"


def extract_body_text(chunk: dict[str, Any]) -> str:
    text = str(chunk.get("text") or "")
    body = text.split("## 正文", 1)[1] if "## 正文" in text else text
    lines = []
    for raw_line in body.splitlines():
        line = raw_line.strip()
        if not line:
            continue
        if line.startswith("- 书名：") or line.startswith("- 章节：") or line.startswith("- PDF页码："):
            continue
        if line.startswith("- 页图：") or line.startswith("!["):
            continue
        if line.startswith("$$") and len(line) <= 4:
            continue
        if any(marker in line for marker in NOISY_SENTENCE_MARKERS):
            continue
        if symbol_ratio(line) > 0.38 and cjk_ratio(line) < 0.2:
            continue
        lines.append(line)
    return clean_text(" ".join(lines))


def anchor_phrase(body: str) -> str:
    sentences = [
        clean_sentence(part)
        for part in split_sentences(body)
        if is_informative_sentence(part)
    ]
    if sentences:
        return trim_anchor(sentences[0])
    return trim_anchor(body[:20])


def split_sentences(text: str) -> list[str]:
    normalized = text
    for separator in ["。", "；", "!", "！", "?", "？", "\n"]:
        normalized = normalized.replace(separator, "|")
    return [part for part in normalized.split("|") if part.strip()]


def trim_anchor(text: str) -> str:
    cleaned = clean_sentence(text).replace(" ", "")
    cjk_segments = re.findall(r"[\u4e00-\u9fff]{4,18}", cleaned)
    if cjk_segments:
        longest = max(cjk_segments, key=len)
        return longest[:14]
    if len(cleaned) <= 14:
        return cleaned
    return cleaned[:14]


def query_anchor(topic: str, anchor: str, body: str) -> str:
    normalized_topic = clean_sentence(topic).replace(" ", "")
    anchor_candidate = trim_anchor(anchor)
    if is_good_anchor(anchor_candidate, normalized_topic):
        return anchor_candidate
    for sentence in split_sentences(body):
        candidate = trim_anchor(sentence)
        if is_good_anchor(candidate, normalized_topic):
            return candidate
    fallback = anchor_candidate or trim_anchor(body[:20])
    return fallback if fallback else normalized_topic[:10]


def is_good_anchor(anchor: str, normalized_topic: str) -> bool:
    if len(anchor) < 4:
        return False
    if cjk_ratio(anchor) < 0.6:
        return False
    if normalized_topic and anchor in normalized_topic:
        return False
    if normalized_topic and normalized_topic in anchor and len(anchor) <= len(normalized_topic) + 2:
        return False
    return True


def clean_text(text: str) -> str:
    return " ".join(str(text or "").replace("\u3000", " ").split()).strip()


def clean_sentence(text: str) -> str:
    cleaned = clean_text(text)
    for marker in ("#", "*", "$", "·", "|"):
        cleaned = cleaned.replace(marker, " ")
    cleaned = " ".join(cleaned.split())
    return cleaned.strip(" ,，。；;：:！？!?()（）[]【】")


def is_informative_sentence(text: str) -> bool:
    cleaned = clean_sentence(text)
    if len(cleaned) < 10 or len(cleaned) > 42:
        return False
    if any(marker in cleaned for marker in NOISY_SENTENCE_MARKERS):
        return False
    return cjk_ratio(cleaned) >= 0.45 and symbol_ratio(cleaned) <= 0.28


def contains_cjk(text: str) -> bool:
    return any(is_cjk(char) for char in text)


def cjk_ratio(text: str) -> float:
    cleaned = clean_text(text)
    if not cleaned:
        return 0.0
    cjk_count = sum(1 for char in cleaned if is_cjk(char))
    return cjk_count / max(1, len(cleaned))


def symbol_ratio(text: str) -> float:
    cleaned = clean_text(text)
    if not cleaned:
        return 1.0
    symbol_count = sum(1 for char in cleaned if not char.isalnum() and not is_cjk(char))
    return symbol_count / max(1, len(cleaned))


def is_cjk(char: str) -> bool:
    return "\u4e00" <= char <= "\u9fff"


def compact_book_name(book_name: str) -> str:
    cleaned = clean_text(book_name)
    return cleaned.replace("普通高中教科书·", "").replace("数学（B版）", "数B")


def _default_output_path() -> Path:
    run_dir = DEFAULT_OUTPUT_ROOT / f"live-textbook-runtime-authored-{time.strftime('%Y%m%d-%H%M%S')}"
    return run_dir / "runtime-authored" / "generated_textbook_eval_cases.json"


if __name__ == "__main__":
    main()
