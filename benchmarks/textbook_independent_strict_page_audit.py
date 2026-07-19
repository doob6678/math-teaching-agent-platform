"""Audit actual child-page evidence without changing independent retrieval results.

The logical-heading product metric deliberately allows a matching heading to
span pages.  This companion audit keeps the stricter historical identity
(``docId + pageNo + visible title``) separate so reports cannot accidentally
claim one metric as the other.
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any

from benchmarks.textbook_section_block_prototype import visible_title


DEFAULT_CASES = Path("output/benchmarks/textbook-independent-110-v1/cases.json")
DEFAULT_RESULTS = Path(
    "output/benchmarks/textbook-independent-110-section-block-child-identity-v4-strict-page/results.json"
)
DEFAULT_OUTPUT = Path("output/benchmarks/textbook-independent-110-strict-page-child-identity-v1/report.json")
METRIC_CUTOFFS = (1, 3, 5, 10)
PUBLIC_REQUEST_FIELDS = frozenset(("query", "limit"))


def strict_page_rank(hits: list[dict[str, Any]], expected: dict[str, Any] | None) -> int | None:
    """Return the first rank that preserves document, actual child page and title."""
    if expected is None:
        return None
    target = (
        str(expected.get("docId") or ""),
        int(expected.get("pageNo") or 0),
        visible_title(expected.get("sectionTitle")),
    )
    for rank, hit in enumerate(hits, 1):
        identity = (
            str(hit.get("docId") or hit.get("doc_id") or ""),
            int(hit.get("pageNo") or hit.get("page_no") or 0),
            visible_title(hit.get("sectionTitle") or hit.get("section_title")),
        )
        if identity == target:
            return rank
    return None


def load_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def audit(cases: list[dict[str, Any]], results: list[dict[str, Any]]) -> dict[str, Any]:
    """Re-score persisted public retrieval output; no retrieval call occurs here."""
    cases_by_id = {str(case.get("caseId") or ""): case for case in cases}
    results_by_id = {str(row.get("caseId") or ""): row for row in results}
    if set(cases_by_id) != set(results_by_id):
        raise ValueError("cases and results must contain exactly the same case ids")

    positive = [case for case in cases if case.get("polarity") == "positive"]
    negative = [case for case in cases if case.get("polarity") == "negative"]
    ranks = {
        str(case["caseId"]): strict_page_rank(results_by_id[str(case["caseId"])].get("hits", []), case.get("expected"))
        for case in positive
    }
    payload_violations = [
        str(row.get("caseId") or "")
        for row in results
        if set((row.get("requestPayload") or {}).keys()) != PUBLIC_REQUEST_FIELDS
    ]
    report: dict[str, Any] = {
        "kind": "independent_strict_child_page_audit",
        "publicRequestFields": sorted(PUBLIC_REQUEST_FIELDS),
        "requestPayloadViolationCaseIds": payload_violations,
        "positiveCount": len(positive),
        "negativeCount": len(negative),
        "negativeEmptyRate": (
            sum(not results_by_id[str(case["caseId"])].get("hits", []) for case in negative) / len(negative)
            if negative else 0.0
        ),
        "strictPageRanks": ranks,
    }
    for cutoff in METRIC_CUTOFFS:
        report[f"strictPageRecall@{cutoff}"] = (
            sum(rank is not None and rank <= cutoff for rank in ranks.values()) / len(positive)
            if positive else 0.0
        )
    report["strictPageMRR@10"] = (
        sum(1.0 / rank if rank is not None and rank <= 10 else 0.0 for rank in ranks.values()) / len(positive)
        if positive else 0.0
    )
    return report


def main() -> None:
    parser = argparse.ArgumentParser(description="Audit strict child-page identity from persisted independent results")
    parser.add_argument("--cases", type=Path, default=DEFAULT_CASES)
    parser.add_argument("--results", type=Path, default=DEFAULT_RESULTS)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    args = parser.parse_args()
    report = audit(load_json(args.cases), load_json(args.results))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
