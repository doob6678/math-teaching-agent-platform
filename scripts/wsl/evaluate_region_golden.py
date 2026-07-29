"""Compare actual JSONL region candidates with the explicit blank-paper Golden sequences."""
from __future__ import annotations

import json
from datetime import datetime, timezone
from pathlib import Path


PROJECT_ROOT = Path(__file__).resolve().parents[2]
CONFIG = PROJECT_ROOT / "config" / "gaokao-ingestion-2024.json"
EVIDENCE = PROJECT_ROOT / "output" / "gaokao-evidence" / "2024"


def main() -> None:
    """Writes one immutable-looking snapshot; comparison failures remain evidence instead of being repaired silently."""
    config = json.loads(CONFIG.read_text(encoding="utf-8"))
    outcomes = []
    for name, expectation in config["blankPaperGoldenExpectations"].items():
        rows = [json.loads(line) for line in (EVIDENCE / name).read_text(encoding="utf-8").splitlines() if line.strip()]
        actual = [row["questionNumber"] for row in rows]
        expected = expectation["expectedQuestionNumbers"]
        outcomes.append({
            "file": name,
            "expectedQuestionCount": len(expected),
            "actualRegionCount": len(actual),
            "expectedQuestionNumbers": expected,
            "actualQuestionNumbers": actual,
            "passed": actual == expected,
            "failureReason": None if actual == expected else "Question-number sequence differs; keep all candidates pending visual review."
        })
    output = {
        "timestampUtc": datetime.now(timezone.utc).isoformat(),
        "check": "blank-paper top-level number sequence",
        "outcomes": outcomes,
        "passedCount": sum(item["passed"] for item in outcomes),
        "failedCount": sum(not item["passed"] for item in outcomes),
        "publicationGate": "All candidates remain PENDING_VISUAL_REVIEW; a failed Golden check blocks publication."
    }
    (EVIDENCE / "region-golden-rule-report.json").write_text(json.dumps(output, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({"passedCount": output["passedCount"], "failedCount": output["failedCount"]}, ensure_ascii=False))


if __name__ == "__main__":
    main()
