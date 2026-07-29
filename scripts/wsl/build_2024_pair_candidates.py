"""Build deterministic blank/solution pairing candidates from real coordinate evidence, never an automatic merge."""
from __future__ import annotations

import json
from datetime import datetime, timezone
from pathlib import Path


PROJECT_ROOT = Path(__file__).resolve().parents[2]
EVIDENCE = PROJECT_ROOT / "output" / "gaokao-evidence" / "2024"
PAIRS = {
    "beijing": ("regions-beijing-blank.jsonl", "regions-beijing-solution.jsonl"),
    "new1": ("regions-new1-blank.jsonl", "regions-new1-solution.jsonl"),
    "new2": ("regions-new2-blank.jsonl", "regions-new2-solution.jsonl"),
}


def rows(name: str) -> list[dict]:
    """Loads the original coordinate evidence without normalizing away duplicate solution references."""
    return [json.loads(line) for line in (EVIDENCE / name).read_text(encoding="utf-8").splitlines() if line.strip()]


def main() -> None:
    """Writes every candidate and ambiguity so a reviewer can select the true official solution occurrence."""
    result: list[dict] = []
    for paper, (blank_name, solution_name) in PAIRS.items():
        blank = rows(blank_name)
        solution_by_number: dict[str, list[dict]] = {}
        for occurrence in rows(solution_name):
            solution_by_number.setdefault(occurrence["questionNumber"], []).append(occurrence)
        for occurrence in blank:
            options = solution_by_number.get(occurrence["questionNumber"], [])
            result.append({
                "paper": paper,
                "blank": occurrence,
                "solutionCandidates": options,
                "solutionCandidateCount": len(options),
                "relationship": "SAME_QUESTION_CANDIDATE",
                "decision": "PENDING_REVIEW",
                "reason": "Matched only by configured blank/solution paper pair and printed question number; content, formula, figure and answer are not auto-confirmed."
            })
    output = {
        "timestampUtc": datetime.now(timezone.utc).isoformat(),
        "method": "configured paper pair + printed question number",
        "candidateCount": len(result),
        "noSolutionCandidateCount": sum(not item["solutionCandidates"] for item in result),
        "ambiguousSolutionCandidateCount": sum(item["solutionCandidateCount"] > 1 for item in result),
        "automaticMergeCount": 0,
        "automaticPublicationCount": 0,
        "candidates": result
    }
    (EVIDENCE / "2024-pair-candidates.json").write_text(json.dumps(output, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({key: output[key] for key in ("candidateCount", "noSolutionCandidateCount", "ambiguousSolutionCandidateCount", "automaticMergeCount")}, ensure_ascii=False))


if __name__ == "__main__":
    main()
