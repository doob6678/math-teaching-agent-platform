"""Paired significance tests for the 120-case teacher retrieval ablation runs.

Why this exists: the ablation chain (parent-child 0.675 -> +RRF 0.725 -> +window rerank 0.733 doc@1) reports
point estimates only. An interviewer (or the thesis committee) asking "is 0.058 beyond noise?" deserves a real
answer computed from the persisted per-query artifacts, not rhetoric. The runs already store results.jsonl with
one row per case_id containing document/block ranks, so every comparison below is reproducible offline from
`output/benchmarks/<run>/` without re-calling the backend.

Design decisions:
- McNemar exact test (two-sided binomial on discordant pairs) instead of a t-test: each case is a paired
  hit/miss observation, and only the discordant cells carry information about the difference.
- Percentile bootstrap over cases for the recall delta CI: distribution-free, and 120 paired samples are few.
- Pure stdlib (math.comb, random) because the benchmark interpreter has no scipy guarantee; see
  benchmark_requirements.txt. The binomial identity is exact — no approximation anywhere.
- The script re-derives each run's headline recall from results.jsonl and asserts it matches metrics.json
  (1e-9 tolerance). If a run directory was produced with different field semantics, we fail loudly instead of
  reporting a confident wrong p-value.

Usage (repo root):
    python benchmarks/significance_eval.py \
        --baseline output/benchmarks/teacher-120case-parent-child-collapse-20260830 \
        --candidate output/benchmarks/teacher-120case-parent-child-fusion-full-20260830 \
        --candidate output/benchmarks/teacher-120case-window-rerank-20260830 \
        --output output/benchmarks/significance-20260831.json
"""

from __future__ import annotations

import argparse
import json
import math
import random
from dataclasses import dataclass
from pathlib import Path
from typing import Any

# metric name -> (rank field in results.jsonl, cutoff K)
DEFAULT_METRICS: dict[str, tuple[str, int]] = {
    "doc@1": ("document_rank", 1),
    "doc@3": ("document_rank", 3),
    "block@1": ("block_rank", 1),
    "block@3": ("block_rank", 3),
    "exactBlock@3": ("exact_block_rank", 3),
}

# Mirrors the metrics.json key layout used to validate recomputed point estimates.
METRICS_JSON_PATH = {
    "doc@1": ("documentRecall", "doc@1"),
    "doc@3": ("documentRecall", "doc@3"),
    "block@1": ("blockRecall", "block@1"),
    "block@3": ("blockRecall", "block@3"),
    "exactBlock@3": ("blockRecall", "exactBlock@3"),
}


@dataclass(frozen=True)
class PairedMetric:
    name: str
    case_ids: tuple[str, ...]
    baseline_hits: tuple[bool, ...]
    candidate_hits: tuple[bool, ...]


def load_rows(run_dir: Path) -> dict[str, dict[str, Any]]:
    """Read one results.jsonl run directory into case_id -> row, rejecting duplicate case ids."""
    rows: dict[str, dict[str, Any]] = {}
    with (run_dir / "results.jsonl").open(encoding="utf-8") as handle:
        for line in handle:
            if not line.strip():
                continue
            row = json.loads(line)
            case_id = str(row["case_id"])
            if case_id in rows:
                raise ValueError(f"duplicate case_id {case_id} in {run_dir}")
            rows[case_id] = row
    return rows


def hit(row: dict[str, Any], field: str, k: int) -> bool:
    """A positive rank at or below the cutoff is a hit; 0/None/negative means the target was never retrieved."""
    rank = row.get(field)
    return isinstance(rank, (int, float)) and 0 < rank <= k


def recompute_recall(rows: dict[str, dict[str, Any]], field: str, k: int) -> float:
    ordered = sorted(rows.values(), key=lambda row: str(row["case_id"]))
    return sum(1 for row in ordered if hit(row, field, k)) / len(ordered)


def validate_against_metrics_json(run_dir: Path, rows: dict[str, dict[str, Any]], metrics: dict[str, tuple[str, int]]) -> None:
    stored = json.loads((run_dir / "metrics.json").read_text(encoding="utf-8"))
    for name, (field, k) in metrics.items():
        section, key = METRICS_JSON_PATH[name]
        expected = stored.get(section, {}).get(key)
        if expected is None:
            continue
        actual = recompute_recall(rows, field, k)
        # metrics.json stores values rounded to 6 decimals; 1e-5 still catches genuine field drift.
        if abs(expected - actual) > 1e-5:
            raise ValueError(
                f"{run_dir.name}: recomputed {name}={actual!r} disagrees with metrics.json {expected!r}; "
                "field semantics changed, refusing to compute significance on a mismatched baseline"
            )


def build_paired(baseline: dict[str, dict[str, Any]], candidate: dict[str, dict[str, Any]], field: str, k: int) -> PairedMetric:
    common = sorted(set(baseline) & set(candidate))
    if not common:
        raise ValueError("baseline and candidate share no case_id; the runs are not paired")
    return PairedMetric(
        name=f"{field}@{k}",
        case_ids=tuple(common),
        baseline_hits=tuple(hit(baseline[cid], field, k) for cid in common),
        candidate_hits=tuple(hit(candidate[cid], field, k) for cid in common),
    )


def mcnemar_exact(paired: PairedMetric) -> dict[str, Any]:
    """Two-sided exact McNemar p-value: tail probability of Bin(b+c, 0.5) beyond the smaller discordant cell."""
    b = sum(1 for base, cand in zip(paired.baseline_hits, paired.candidate_hits) if not base and cand)
    c = sum(1 for base, cand in zip(paired.baseline_hits, paired.candidate_hits) if base and not cand)
    n = b + c
    if n == 0:
        return {"discordant_gain": b, "discordant_loss": c, "p_value": 1.0}
    smaller = min(b, c)
    tail = sum(math.comb(n, x) for x in range(smaller + 1)) / (2 ** n)
    p_value = min(1.0, 2.0 * tail)
    return {"discordant_gain": b, "discordant_loss": c, "p_value": p_value}


def bootstrap_delta_ci(paired: PairedMetric, samples: int, seed: int) -> dict[str, Any]:
    """Percentile bootstrap (resample paired cases) for candidate_minus_baseline recall delta."""
    rng = random.Random(seed)
    size = len(paired.case_ids)
    deltas: list[float] = []
    for _ in range(samples):
        gain = loss = 0
        for _ in range(size):
            idx = rng.randrange(size)
            if not paired.baseline_hits[idx] and paired.candidate_hits[idx]:
                gain += 1
            elif paired.baseline_hits[idx] and not paired.candidate_hits[idx]:
                loss += 1
        deltas.append((gain - loss) / size)
    deltas.sort()
    return {
        "delta": (sum(paired.candidate_hits) - sum(paired.baseline_hits)) / size,
        "ci95_low": deltas[int(0.025 * samples)],
        "ci95_high": deltas[min(samples - 1, int(0.975 * samples))],
        "bootstrap_samples": samples,
        "seed": seed,
    }


def evaluate_pair(baseline_dir: Path, candidate_dir: Path, metrics: dict[str, tuple[str, int]], samples: int, seed: int) -> dict[str, Any]:
    baseline = load_rows(baseline_dir)
    candidate = load_rows(candidate_dir)
    validate_against_metrics_json(baseline_dir, baseline, metrics)
    validate_against_metrics_json(candidate_dir, candidate, metrics)
    report: dict[str, Any] = {
        "baseline": baseline_dir.name,
        "candidate": candidate_dir.name,
        "paired_cases": len(set(baseline) & set(candidate)),
        "metrics": {},
    }
    for name, (field, k) in metrics.items():
        paired = build_paired(baseline, candidate, field, k)
        entry = {
            "baseline_recall": sum(paired.baseline_hits) / len(paired.case_ids),
            "candidate_recall": sum(paired.candidate_hits) / len(paired.case_ids),
            **mcnemar_exact(paired),
            "bootstrap": bootstrap_delta_ci(paired, samples, seed),
        }
        report["metrics"][name] = entry
    return report


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--baseline", required=True, type=Path)
    parser.add_argument("--candidate", required=True, type=Path, action="append")
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--bootstrap-samples", type=int, default=10000)
    parser.add_argument("--seed", type=int, default=20260831)
    args = parser.parse_args()
    pairs = [evaluate_pair(args.baseline, candidate, DEFAULT_METRICS, args.bootstrap_samples, args.seed) for candidate in args.candidate]
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps({"pairs": pairs}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"pairs": pairs, "output": str(args.output)}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
