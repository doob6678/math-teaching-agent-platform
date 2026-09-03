import json
import math
import tempfile
import unittest
from pathlib import Path

from benchmarks.significance_eval import (
    DEFAULT_METRICS,
    PairedMetric,
    bootstrap_delta_ci,
    evaluate_pair,
    hit,
    load_rows,
    mcnemar_exact,
)


def _paired(baseline_hits, candidate_hits):
    n = len(baseline_hits)
    return PairedMetric(
        name="synthetic",
        case_ids=tuple(f"c{i}" for i in range(n)),
        baseline_hits=tuple(baseline_hits),
        candidate_hits=tuple(candidate_hits),
    )


class SignificanceEvalTest(unittest.TestCase):
    def test_hit_requires_positive_rank_within_cutoff(self):
        self.assertTrue(hit({"document_rank": 3}, "document_rank", 3))
        self.assertFalse(hit({"document_rank": 4}, "document_rank", 3))
        # 0/None/negative mean the target was never retrieved, not "rank zero".
        self.assertFalse(hit({"document_rank": 0}, "document_rank", 3))
        self.assertFalse(hit({"document_rank": None}, "document_rank", 3))
        self.assertFalse(hit({}, "document_rank", 3))

    def test_mcnemar_exact_matches_closed_form_binomial(self):
        # b=8 gains, c=1 loss: two-sided p = 2 * P[X <= 1] for X ~ Bin(9, 0.5) = (9 + 1) / 2^8.
        result = mcnemar_exact(_paired([False] * 8 + [True], [True] * 8 + [False]))
        self.assertEqual(result["discordant_gain"], 8)
        self.assertEqual(result["discordant_loss"], 1)
        self.assertAlmostEqual(result["p_value"], 2 * (math.comb(9, 0) + math.comb(9, 1)) / 2**9, places=12)

    def test_mcnemar_no_discordance_is_not_significant(self):
        result = mcnemar_exact(_paired([True, False], [True, False]))
        self.assertEqual(result["p_value"], 1.0)

    def test_bootstrap_ci_is_seed_deterministic_and_brackets_delta(self):
        paired = _paired([False] * 8 + [True] * 12, [True] * 8 + [False] * 4 + [True] * 8)
        first = bootstrap_delta_ci(paired, samples=2000, seed=7)
        second = bootstrap_delta_ci(paired, samples=2000, seed=7)
        self.assertEqual(first, second)
        self.assertLessEqual(first["ci95_low"], first["delta"])
        self.assertGreaterEqual(first["ci95_high"], first["delta"])

    @staticmethod
    def _write_run(root: Path, name: str, ranks: list[tuple[str, int]], metrics: dict) -> Path:
        run = root / name
        run.mkdir(parents=True)
        with (run / "results.jsonl").open("w", encoding="utf-8") as handle:
            for case_id, document_rank in ranks:
                row = {
                    "case_id": case_id,
                    "document_rank": document_rank,
                    "block_rank": document_rank,
                    "exact_block_rank": document_rank,
                }
                handle.write(json.dumps(row) + "\n")
        (run / "metrics.json").write_text(json.dumps(metrics), encoding="utf-8")
        return run

    def test_evaluate_pair_recomputes_and_validates_against_metrics_json(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            metrics_a = {"documentRecall": {"doc@1": 0.5, "doc@3": 0.5}, "blockRecall": {"block@1": 0.5, "block@3": 0.5, "exactBlock@3": 0.5}}
            metrics_b = {"documentRecall": {"doc@1": 1.0, "doc@3": 1.0}, "blockRecall": {"block@1": 1.0, "block@3": 1.0, "exactBlock@3": 1.0}}
            baseline = self._write_run(root, "a", [("c1", 1), ("c2", 4)], metrics_a)
            candidate = self._write_run(root, "b", [("c1", 1), ("c2", 1)], metrics_b)
            report = evaluate_pair(baseline, candidate, {"doc@1": ("document_rank", 1)}, samples=500, seed=1)
            self.assertEqual(report["paired_cases"], 2)
            self.assertEqual(report["metrics"]["doc@1"]["discordant_gain"], 1)
            self.assertEqual(report["metrics"]["doc@1"]["discordant_loss"], 0)

    def test_evaluate_pair_rejects_run_whose_metrics_disagree_with_rows(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            metrics_a = {"documentRecall": {"doc@1": 0.9, "doc@3": 0.9}, "blockRecall": {"block@1": 0.9, "block@3": 0.9, "exactBlock@3": 0.9}}
            baseline = self._write_run(root, "a", [("c1", 1), ("c2", 4)], metrics_a)
            candidate = self._write_run(root, "b", [("c1", 1), ("c2", 1)], metrics_a)
            with self.assertRaises(ValueError):
                evaluate_pair(baseline, candidate, DEFAULT_METRICS, samples=100, seed=1)

    def test_load_rows_rejects_duplicate_case_ids(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            run = root / "dup"
            run.mkdir()
            (run / "results.jsonl").write_text(
                json.dumps({"case_id": "c1", "document_rank": 1}) + "\n" + json.dumps({"case_id": "c1", "document_rank": 2}) + "\n",
                encoding="utf-8",
            )
            with self.assertRaises(ValueError):
                load_rows(run)


if __name__ == "__main__":
    unittest.main()
