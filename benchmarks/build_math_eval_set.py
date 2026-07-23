from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path
from typing import Any, Iterable

if __package__ in {None, ""}:
    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))


MATH_KEYWORDS = (
    "函数",
    "导数",
    "圆锥",
    "椭圆",
    "双曲线",
    "抛物线",
    "数列",
    "三角",
    "向量",
    "概率",
    "统计",
    "立体几何",
    "空间",
    "证明",
    "求",
    "已知",
    "人教",
)

MATH_FILE_HINTS = (
    "数学",
    "高考",
    "高三",
    "高中",
    "联考",
    "模拟",
    "试题",
    "试卷",
    "答案",
    "人教",
    "必修",
    "选择性必修",
)


def build_eval_set(config: dict[str, Any], limit: int) -> list[dict[str, Any]]:
    """Build query cases from configured seeds and real local high-school math files."""
    cases: list[dict[str, Any]] = []
    seen_queries: set[str] = set()
    for seed in config.get("querySeeds", []):
        _append_case(cases, seen_queries, {
            "id": f"seed-{len(cases) + 1}",
            "query": str(seed),
            "sourceType": "seed",
        }, limit)
    roots = list(config.get("textbookRoots") or []) + list(config.get("questionRoots") or [])
    for root in roots:
        path = Path(root)
        if not path.exists():
            cases.append({
                "id": f"missing-{len(cases) + 1}",
                "query": "",
                "sourceType": "missingRoot",
                "path": str(path),
            })
            continue
        for file_path in _iter_math_files(path):
            for query in _extract_queries(file_path):
                if _append_case(cases, seen_queries, {
                    "id": f"file-{len(cases) + 1}",
                    "query": query,
                    "sourceType": _source_type_for_path(file_path),
                    "path": str(file_path),
                }, limit):
                    return [case for case in cases if case.get("query")][:limit]
    return [case for case in cases if case.get("query")][:limit]


def write_eval_set(cases: list[dict[str, Any]], output_path: Path) -> Path:
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(
        "\n".join(json.dumps(case, ensure_ascii=False) for case in cases) + ("\n" if cases else ""),
        encoding="utf-8",
    )
    return output_path


def _append_case(
        cases: list[dict[str, Any]],
        seen_queries: set[str],
        case: dict[str, Any],
        limit: int) -> bool:
    query = _clean_text(str(case.get("query") or ""))
    if not query or query in seen_queries:
        return len([item for item in cases if item.get("query")]) >= limit
    seen_queries.add(query)
    case["query"] = query
    cases.append(case)
    return len([item for item in cases if item.get("query")]) >= limit


def _iter_math_files(root: Path) -> Iterable[Path]:
    for path in sorted(root.rglob("*")):
        if not path.is_file():
            continue
        if path.suffix.lower() not in {".txt", ".md", ".docx", ".pdf"}:
            continue
        haystack = f"{path} {path.name} {path.parent.name}"
        if "数学" not in haystack:
            continue
        if any(word in haystack for word in MATH_FILE_HINTS):
            yield path


def _extract_queries(path: Path) -> list[str]:
    text = _read_text(path)
    if not text:
        return []
    normalized = re.sub(r"\s+", " ", text)
    candidates = re.split(r"(?:\d{1,2}[．.、)]|第\s*\d+\s*题)", normalized)
    queries: list[str] = []
    for candidate in candidates:
        value = _clean_text(candidate)
        if 18 <= len(value) <= 220 and any(keyword in value for keyword in MATH_KEYWORDS):
            queries.append(value[:180])
        if len(queries) >= 5:
            break
    if queries:
        return queries
    fallback = _first_math_window(normalized)
    return [fallback] if fallback else []


def _first_math_window(text: str) -> str:
    for keyword in MATH_KEYWORDS:
        index = text.find(keyword)
        if index >= 0:
            start = max(0, index - 40)
            window = _clean_text(text[start:start + 180])
            if len(window) >= 18:
                return window
    return ""


def _read_text(path: Path) -> str:
    suffix = path.suffix.lower()
    if suffix in {".txt", ".md"}:
        return path.read_text(encoding="utf-8", errors="ignore")
    if suffix == ".docx":
        try:
            from docx import Document
        except Exception:
            return ""
        document = Document(str(path))
        return "\n".join(paragraph.text for paragraph in document.paragraphs)
    if suffix == ".pdf":
        try:
            from pypdf import PdfReader
        except Exception:
            return ""
        try:
            reader = PdfReader(str(path))
            return "\n".join((page.extract_text() or "") for page in reader.pages[:5])
        except Exception:
            return ""
    return ""


def _clean_text(value: str) -> str:
    utf8_safe = value.encode("utf-8", errors="ignore").decode("utf-8", errors="ignore")
    return re.sub(r"\s+", " ", utf8_safe).strip()


def _source_type_for_path(path: Path) -> str:
    path_text = str(path)
    if "高中数学课本" in path_text or "人教" in path.name or "必修" in path.name:
        return "localTextbookFile"
    if "高考真题" in path_text:
        return "localGaokaoFile"
    return "localQuestionFile"


def main() -> None:
    parser = argparse.ArgumentParser(description="Build real MathAgent RAG evaluation JSONL from local math files.")
    parser.add_argument("--config", default="benchmarks/config.example.json")
    parser.add_argument("--output", default="output/benchmarks/eval-set.jsonl")
    parser.add_argument("--limit", type=int, default=50)
    args = parser.parse_args()
    config = json.loads(Path(args.config).read_text(encoding="utf-8"))
    cases = build_eval_set(config, args.limit)
    output = write_eval_set(cases, Path(args.output))
    print(f"wrote {len(cases)} cases to {output}")


if __name__ == "__main__":
    main()
