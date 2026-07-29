"""Create an immutable review queue from failed Golden checks and non-unique solution-pair candidates."""
from __future__ import annotations

import json
from datetime import datetime, timezone
from pathlib import Path


PROJECT_ROOT = Path(__file__).resolve().parents[2]
EVIDENCE = PROJECT_ROOT / "output" / "gaokao-evidence" / "2024"


def main() -> None:
    """Keeps reviewer tasks explicit; Luna output becomes evidence, never a reviewer identity or automatic decision."""
    golden = json.loads((EVIDENCE / "region-golden-rule-report.json").read_text(encoding="utf-8"))
    pairs = json.loads((EVIDENCE / "2024-pair-candidates.json").read_text(encoding="utf-8"))
    database_snapshot = json.loads(next(EVIDENCE.glob("database-run-*.json")).read_text(encoding="utf-8"))
    tasks: list[dict] = []
    for failure in (entry for entry in golden["outcomes"] if not entry["passed"]):
        tasks.append({
            "taskType": "REGION_GOLDEN_MISMATCH",
            "state": "PENDING_HUMAN_REVIEW",
            "sourceEvidence": failure["file"],
            "expectedQuestionNumbers": failure["expectedQuestionNumbers"],
            "actualQuestionNumbers": failure["actualQuestionNumbers"],
            "modelEvidence": "luna-2024-new1-page-2-visual-audit.json",
            "suggestion": "Reject the page-2 false top-level '2' anchor only after reviewer confirms the original page image."
        })
    for candidate in (item for item in pairs["candidates"] if item["solutionCandidateCount"] > 1):
        tasks.append({
            "taskType": "SOLUTION_PAIR_AMBIGUITY",
            "state": "PENDING_HUMAN_REVIEW",
            "paper": candidate["paper"],
            "questionNumber": candidate["blank"]["questionNumber"],
            "blankRegion": candidate["blank"],
            "solutionCandidates": candidate["solutionCandidates"],
            "suggestion": "Select the official explanatory occurrence by content, formula and diagram; do not merge merely by number."
        })
    output = {
        "timestampUtc": datetime.now(timezone.utc).isoformat(),
        "importRunId": database_snapshot["importRunId"],
        "taskCount": len(tasks),
        "regionGoldenMismatchCount": sum(task["taskType"] == "REGION_GOLDEN_MISMATCH" for task in tasks),
        "solutionPairAmbiguityCount": sum(task["taskType"] == "SOLUTION_PAIR_AMBIGUITY" for task in tasks),
        "automaticReviewDecisionCount": 0,
        "tasks": tasks
    }
    (EVIDENCE / "2024-review-queue.json").write_text(json.dumps(output, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({key: output[key] for key in ("taskCount", "regionGoldenMismatchCount", "solutionPairAmbiguityCount", "automaticReviewDecisionCount")}, ensure_ascii=False))


if __name__ == "__main__":
    main()
