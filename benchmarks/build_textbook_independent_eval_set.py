"""Build an independent, auditable textbook retrieval set from the real c2 corpus.

The builder never changes textbook data and never emits target document/page fields
into a retrieval request. Positive labels are derived from current corpus rows and
kept only in the case oracle. Negative cases are verified absent from the complete
corpus before they are written.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
from collections import Counter, defaultdict
from dataclasses import dataclass
from pathlib import Path
from typing import Any


DEFAULT_LIBRARY_ROOT = Path(
    r"C:\Users\doob\Desktop\个人资料\高中数学\下载课本代码"
    r"\tchMaterial-parser-main\tchMaterial-parser-main"
    r"\processed_books_section_shadow_all_mini_c2"
)
DEFAULT_OUTPUT = Path("output/benchmarks/textbook-independent-110-v1")

# These are evaluation-set composition constraints, not retrieval weights. They
# are persisted in the manifest so a future set cannot silently use looser data.
POSITIVE_CASE_COUNT = 100
NEGATIVE_CASE_COUNT = 10
MIN_EVIDENCE_CHARACTERS = 80
DISTINCTIVE_PHRASE_CHARACTERS = 24
DIMENSION_QUOTA = 25

GENERIC_TITLES = {
    "",
    "目录",
    "前言",
    "后记",
    "本章小结",
    "解",
    "例",
    "练习",
}

# Exactly ten negative cases keep the unanswerable share below ten percent of
# the 110-case set. Their absence is rechecked against every corpus row at build
# time; these strings never appear in production ranking code.
NEGATIVE_CASES = (
    ("physics_out_of_domain", "量子色动力学夸克禁闭"),
    ("chemistry_out_of_domain", "苯环亲电取代反应机理"),
    ("literature_out_of_domain", "唐诗近体诗平仄格律"),
    ("biology_out_of_domain", "线粒体有氧呼吸电子传递链"),
    ("history_out_of_domain", "罗马帝国戴克里先改革"),
    ("computer_science_out_of_domain", "Java虚拟机垃圾回收算法"),
    ("geography_out_of_domain", "板块构造与海底扩张"),
    ("law_out_of_domain", "民法典善意取得制度"),
    ("language_out_of_domain", "法语虚拟式过去时"),
    ("music_out_of_domain", "爵士和声三全音替代"),
)


@dataclass(frozen=True)
class QueryCandidate:
    """One query variant grounded in one current-corpus logical page block."""

    dimension: str
    query: str
    row: dict[str, Any]


def read_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def read_jsonl(path: Path) -> list[dict[str, Any]]:
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def write_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def compact(value: Any) -> str:
    return re.sub(r"\s+", "", str(value or "")).lower()


def normalized_text(value: Any) -> str:
    return re.sub(r"\s+", " ", str(value or "")).strip()


def row_identity(row: dict[str, Any]) -> tuple[str, int, str]:
    return (
        str(row.get("doc_id") or ""),
        int(row.get("page_no") or 0),
        compact(row.get("section_title")),
    )


def corpus_rows(root: Path) -> list[dict[str, Any]]:
    """Load every catalogued textbook so no case is generated from a narrow scope."""
    catalog = read_json(root / "catalog.json")
    rows: list[dict[str, Any]] = []
    for book in catalog.get("books", []):
        path = root / str(book["doc_id"]) / "jsonl_ai" / "chunks.jsonl"
        rows.extend(read_jsonl(path))
    return rows


def corpus_fingerprint(root: Path) -> str:
    """Bind labels to the exact immutable source files used during generation."""
    digest = hashlib.sha256()
    catalog = read_json(root / "catalog.json")
    digest.update((root / "catalog.json").read_bytes())
    for book in catalog.get("books", []):
        digest.update((root / str(book["doc_id"]) / "jsonl_ai" / "chunks.jsonl").read_bytes())
    return digest.hexdigest()


def representative_rows(rows: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """Keep the strongest real evidence row for each strict doc/page/title identity."""
    selected: dict[tuple[str, int, str], dict[str, Any]] = {}
    for row in rows:
        identity = row_identity(row)
        previous = selected.get(identity)
        if previous is None or evidence_length(row) > evidence_length(previous):
            selected[identity] = row
    return list(selected.values())


def evidence_length(row: dict[str, Any]) -> int:
    return len(normalized_text(row.get("text"))) + len(normalized_text(row.get("formula_text")))


def valid_source(row: dict[str, Any]) -> bool:
    title = compact(row.get("section_title"))
    return (
        bool(str(row.get("doc_id") or ""))
        and int(row.get("page_no") or 0) > 0
        and title not in GENERIC_TITLES
        and len(normalized_text(row.get("text"))) >= MIN_EVIDENCE_CHARACTERS
    )


def candidate_phrases(text: str) -> list[str]:
    """Return deterministic source spans suitable for body-evidence queries."""
    cleaned = re.sub(r"[#*_`$]", " ", normalized_text(text))
    segments = [part.strip() for part in re.split(r"[。！？；;\n]", cleaned) if part.strip()]
    phrases: list[str] = []
    for segment in segments:
        compacted = compact(segment)
        if len(compacted) < DISTINCTIVE_PHRASE_CHARACTERS:
            continue
        for offset in range(0, len(compacted) - DISTINCTIVE_PHRASE_CHARACTERS + 1, DISTINCTIVE_PHRASE_CHARACTERS):
            phrase = compacted[offset : offset + DISTINCTIVE_PHRASE_CHARACTERS]
            if contains_cjk(phrase) and phrase not in phrases:
                phrases.append(phrase)
    return phrases


def contains_cjk(value: str) -> bool:
    return any("\u4e00" <= char <= "\u9fff" for char in value)


def first_unique_phrase(row: dict[str, Any], corpus_surfaces: list[str]) -> str:
    """Choose a phrase that occurs in exactly one logical block surface."""
    for phrase in candidate_phrases(str(row.get("text") or "")):
        if sum(phrase in surface for surface in corpus_surfaces) == 1:
            return phrase
    return ""


def formula_query(row: dict[str, Any]) -> str:
    """Build a formula-bearing query from real source text without inventing math."""
    formula = normalized_text(row.get("formula_text"))
    if not formula:
        match = re.search(r"[^。；\n]{0,28}(?:\\[A-Za-z]+|[_^={}])[^。；\n]{0,28}", str(row.get("text") or ""))
        formula = normalized_text(match.group(0)) if match else ""
    if not formula:
        return ""
    return normalized_text(f"{row.get('section_title')} {formula[:64]}")


def query_candidates(rows: list[dict[str, Any]]) -> dict[str, dict[str, list[QueryCandidate]]]:
    """Create four independent query dimensions while keeping labels hidden."""
    logical = [row for row in representative_rows(rows) if valid_source(row)]
    title_counts = Counter(compact(row.get("section_title")) for row in logical)
    surfaces = [compact(f"{row.get('section_title')} {row.get('text')} {row.get('formula_text')}") for row in logical]
    result: dict[str, dict[str, list[QueryCandidate]]] = defaultdict(lambda: defaultdict(list))
    for row in logical:
        doc_id = str(row.get("doc_id") or "")
        title = normalized_text(row.get("section_title"))
        phrase = first_unique_phrase(row, surfaces)
        formula = formula_query(row)
        variants: list[tuple[str, str]] = []
        if title_counts[compact(title)] == 1:
            variants.append(("heading_exact", title))
        if title and phrase:
            variants.append(("heading_with_context", f"{title} {phrase}"))
            variants.append(("body_evidence", phrase))
        if formula:
            variants.append(("formula_evidence", formula))
        for dimension, query in variants:
            result[dimension][doc_id].append(QueryCandidate(dimension, normalized_text(query), row))
    return result


def deterministic_order(candidates: list[QueryCandidate], fingerprint: str) -> list[QueryCandidate]:
    """Hash-order candidates so source file order cannot bias one chapter or edition."""
    return sorted(
        candidates,
        key=lambda candidate: hashlib.sha256(
            (fingerprint + "|" + candidate.dimension + "|" + candidate.query + "|" + repr(row_identity(candidate.row))).encode("utf-8")
        ).hexdigest(),
    )


def select_positive_cases(
    candidates: dict[str, dict[str, list[QueryCandidate]]],
    fingerprint: str,
) -> list[QueryCandidate]:
    """Select 25 cases per dimension by round-robin textbook coverage."""
    selected: list[QueryCandidate] = []
    used_queries: set[str] = set()
    used_identities: set[tuple[str, int, str]] = set()
    dimensions = ("heading_exact", "heading_with_context", "body_evidence", "formula_evidence")
    for dimension in dimensions:
        by_doc = {
            doc_id: deterministic_order(items, fingerprint)
            for doc_id, items in candidates.get(dimension, {}).items()
        }
        offsets = {doc_id: 0 for doc_id in by_doc}
        docs = sorted(by_doc)
        dimension_selected = 0
        while dimension_selected < DIMENSION_QUOTA:
            progressed = False
            for doc_id in docs:
                items = by_doc[doc_id]
                while offsets[doc_id] < len(items):
                    candidate = items[offsets[doc_id]]
                    offsets[doc_id] += 1
                    identity = row_identity(candidate.row)
                    query_key = compact(candidate.query)
                    if query_key in used_queries or identity in used_identities:
                        continue
                    selected.append(candidate)
                    used_queries.add(query_key)
                    used_identities.add(identity)
                    dimension_selected += 1
                    progressed = True
                    break
                if dimension_selected >= DIMENSION_QUOTA:
                    break
            if not progressed:
                raise RuntimeError(f"insufficient unique real candidates for dimension {dimension}: {dimension_selected}")
    if len(selected) != POSITIVE_CASE_COUNT:
        raise RuntimeError(f"expected {POSITIVE_CASE_COUNT} positives, selected {len(selected)}")
    return selected


def positive_case(index: int, candidate: QueryCandidate) -> dict[str, Any]:
    row = candidate.row
    return {
        "caseId": f"positive-{index:03d}",
        "polarity": "positive",
        "dimension": candidate.dimension,
        "query": candidate.query,
        "expected": {
            "docId": row.get("doc_id"),
            "pageNo": int(row.get("page_no") or 0),
            "sectionTitle": row.get("section_title"),
            "sectionId": row.get("section_id"),
            "chunkId": row.get("chunk_id"),
        },
        "evidence": normalized_text(row.get("text"))[:240],
    }


def negative_case(index: int, dimension: str, query: str) -> dict[str, Any]:
    return {
        "caseId": f"negative-{index:03d}",
        "polarity": "negative",
        "dimension": dimension,
        "query": query,
        "expected": None,
        "evidence": "verified_absent_from_complete_c2_corpus",
    }


def validate_cases(cases: list[dict[str, Any]], rows: list[dict[str, Any]]) -> None:
    """Fail closed if labels, uniqueness, or positive/negative ratios drift."""
    positives = [case for case in cases if case["polarity"] == "positive"]
    negatives = [case for case in cases if case["polarity"] == "negative"]
    if len(positives) != POSITIVE_CASE_COUNT or len(negatives) != NEGATIVE_CASE_COUNT:
        raise RuntimeError("case polarity counts do not match the immutable evaluation contract")
    queries = [compact(case["query"]) for case in cases]
    if len(queries) != len(set(queries)):
        raise RuntimeError("duplicate queries are forbidden in the independent set")
    identities = {row_identity(row) for row in rows}
    for case in positives:
        expected = case["expected"]
        identity = (str(expected["docId"]), int(expected["pageNo"]), compact(expected["sectionTitle"]))
        if identity not in identities:
            raise RuntimeError(f"positive label is absent from corpus: {case['caseId']} {identity}")
    corpus_surface = compact("\n".join(
        f"{row.get('section_title')} {row.get('text')} {row.get('formula_text')}" for row in rows
    ))
    for case in negatives:
        if compact(case["query"]) in corpus_surface:
            raise RuntimeError(f"negative query appears in corpus: {case['caseId']}")
    if len(negatives) / len(cases) > 0.10:
        raise RuntimeError("negative case share exceeds ten percent")


def main() -> None:
    parser = argparse.ArgumentParser(description="Build a 110-case independent full-library textbook benchmark")
    parser.add_argument("--library-root", type=Path, default=DEFAULT_LIBRARY_ROOT)
    parser.add_argument("--output-dir", type=Path, default=DEFAULT_OUTPUT)
    args = parser.parse_args()

    root = args.library_root.expanduser().resolve()
    output = args.output_dir.expanduser().resolve()
    rows = corpus_rows(root)
    fingerprint = corpus_fingerprint(root)
    positives = select_positive_cases(query_candidates(rows), fingerprint)
    cases = [positive_case(index, candidate) for index, candidate in enumerate(positives, 1)]
    cases.extend(
        negative_case(index, dimension, query)
        for index, (dimension, query) in enumerate(NEGATIVE_CASES, 1)
    )
    validate_cases(cases, rows)

    manifest = {
        "kind": "independent_full_library_textbook_retrieval_set",
        "version": 1,
        "libraryRoot": str(root),
        "corpusFingerprint": fingerprint,
        "corpusRows": len(rows),
        "bookCount": len({str(row.get("doc_id") or "") for row in rows}),
        "caseCount": len(cases),
        "positiveCount": len([case for case in cases if case["polarity"] == "positive"]),
        "negativeCount": len([case for case in cases if case["polarity"] == "negative"]),
        "negativeShare": NEGATIVE_CASE_COUNT / len(cases),
        "dimensionCounts": dict(Counter(case["dimension"] for case in cases)),
        "documentCounts": dict(Counter(
            str(case["expected"]["docId"])
            for case in cases
            if case["polarity"] == "positive"
        )),
        "samplingContract": {
            "minimumEvidenceCharacters": MIN_EVIDENCE_CHARACTERS,
            "distinctivePhraseCharacters": DISTINCTIVE_PHRASE_CHARACTERS,
            "positiveQueriesAreUnique": True,
            "positiveIdentitiesExistInCorpus": True,
            "negativeQueriesVerifiedAbsent": True,
            "retrievalRequestFields": ["query", "limit"],
        },
    }
    write_json(output / "cases.json", cases)
    write_json(output / "manifest.json", manifest)
    print(json.dumps(manifest, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
