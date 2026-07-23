"""Audit whether immutable strict textbook labels are reachable by one full-library request.

The strict diagnostic treats ``docId + source pageNo + visible title`` as a
hit. A public retrieval request deliberately contains only a query and limit,
so equal normalized queries receive the same deterministic ranking. This module
computes the resulting mathematical ceiling before ranking changes are credited
with or blamed for a label conflict.
"""

from __future__ import annotations

import argparse
import json
import re
from collections import Counter, defaultdict
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any, Iterable


DEFAULT_CASES = Path("output/benchmarks/textbook-page-section-ablation-route-balanced-production-v3-report-audit-20260714/section_cases.json")
DEFAULT_CUTOFFS = (1, 3)
TRAILING_PRINTED_PAGE = re.compile(r"(?<=[\u4e00-\u9fff])\d{1,3}$")


@dataclass(frozen=True)
class StrictLabelBounds:
    """Upper bounds implied by duplicate query labels, before any retrieval model runs."""

    at_1: float
    at_3: float
    conflicting_query_count: int
    total_case_count: int
    conflicts: tuple[dict[str, Any], ...]


def compact(value: object) -> str:
    """Normalize display text while preserving actual section numbers such as ``2.6.1``."""
    normalized = re.sub(r"\s+", "", str(value or "")).lower()
    return TRAILING_PRINTED_PAGE.sub("", normalized)


def deterministic_upper_bounds(cases: Iterable[dict[str, Any]]) -> StrictLabelBounds:
    """Return @1/@3 ceilings when identical queries require different strict targets.

    For one normalized query, one deterministic rank-1 result can satisfy only
    the most frequently repeated expected identity. Rank-3 can satisfy at most
    the three most frequent expected identities. This is a label-contract fact,
    not a relevance or ranking score.
    """
    grouped: dict[str, Counter[tuple[str, int, str]]] = defaultdict(Counter)
    total = 0
    for case in cases:
        query = compact(case.get("query"))
        expected = case.get("expected")
        if not query or not isinstance(expected, tuple) or len(expected) != 3:
            raise ValueError(f"case requires normalized query and (docId, pageNo, title) target: {case}")
        doc_id, page_no, title = expected
        identity = (str(doc_id), int(page_no), compact(title))
        grouped[query][identity] += 1
        total += 1
    if total == 0:
        raise ValueError("strict-label audit requires at least one case")

    at_1_hits = 0
    at_3_hits = 0
    conflicts: list[dict[str, Any]] = []
    for query, targets in sorted(grouped.items()):
        frequencies = sorted(targets.values(), reverse=True)
        at_1_hits += frequencies[0]
        at_3_hits += sum(frequencies[: DEFAULT_CUTOFFS[1]])
        if len(targets) > 1:
            conflicts.append({
                "query": query,
                "targets": [
                    {
                        "docId": identity[0],
                        "pageNo": identity[1],
                        "sectionTitle": identity[2],
                        "caseCount": count,
                    }
                    for identity, count in sorted(targets.items())
                ],
            })
    return StrictLabelBounds(
        at_1=at_1_hits / total,
        at_3=at_3_hits / total,
        conflicting_query_count=len(conflicts),
        total_case_count=total,
        conflicts=tuple(conflicts),
    )


def cases_from_section_file(path: Path) -> list[dict[str, Any]]:
    """Read immutable section cases without deriving or modifying their expected labels."""
    raw = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(raw, list):
        raise ValueError(f"expected a list of section cases: {path}")
    cases: list[dict[str, Any]] = []
    for row in raw:
        source = row.get("source") if isinstance(row, dict) else None
        if not isinstance(source, dict):
            raise ValueError(f"case has no persisted source: {row}")
        cases.append({
            "query": row.get("query"),
            "expected": (
                source.get("doc_id"),
                source.get("page_no"),
                source.get("section_title"),
            ),
        })
    return cases


def main() -> None:
    parser = argparse.ArgumentParser(description="Audit immutable strict textbook-label reachability")
    parser.add_argument("--cases", type=Path, default=DEFAULT_CASES)
    parser.add_argument("--output", type=Path, default=None)
    args = parser.parse_args()
    bounds = deterministic_upper_bounds(cases_from_section_file(args.cases.expanduser().resolve()))
    payload = asdict(bounds)
    text = json.dumps(payload, ensure_ascii=False, indent=2) + "\n"
    if args.output is None:
        print(text, end="")
    else:
        output = args.output.expanduser().resolve()
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(text, encoding="utf-8")


if __name__ == "__main__":
    main()
