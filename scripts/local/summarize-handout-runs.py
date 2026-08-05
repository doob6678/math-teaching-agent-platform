#!/usr/bin/env python3
"""Summarize real handout acceptance evidence without manufacturing absent telemetry.

The script accepts either one run directory or a directory containing several runs.
It deliberately treats every file as evidence: values are reported only when they
exist in a response, event, metadata, or host-load sample.  This keeps release
reports honest while the Java task metrics migration is being rolled out.
"""

from __future__ import annotations

import argparse
from collections.abc import Iterable
from dataclasses import dataclass
from hashlib import sha256
import json
from pathlib import Path
import re
from statistics import median
from typing import Any


HTTP_METADATA_PATTERN = re.compile(r"\b(status|bytes|elapsed_ms)=([^\s]+)")
TERMINAL_EVENTS = frozenset({"completed", "failed", "cancelled", "timed_out"})
REQUIRED_OPERATIONAL_FIELDS = {
    "queueWaitMilliseconds",
    "leaseWaitMilliseconds",
    "ackMilliseconds",
    "pdfMilliseconds",
    "cpuSamples",
    "rssSamples",
    "gpuSamples",
}


@dataclass(frozen=True)
class AttemptEvidence:
    """Normalized facts for one observed run; optional values stay ``None`` when absent."""

    directory: Path
    status: str | None
    elapsed_ms: int | None
    provider_successes: int | None
    provider_failures: int | None
    prompt_tokens: int | None
    completion_tokens: int | None
    total_tokens: int | None
    estimated_cost: float | None
    cost_known: bool | None
    operational: dict[str, Any]
    missing_fields: frozenset[str]


def _read_json(path: Path) -> Any | None:
    """Reads a JSON evidence file only when it is complete and valid."""
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError):
        return None


def _as_integer(value: Any) -> int | None:
    """Avoids coercing malformed telemetry into a zero that would bias percentiles."""
    if isinstance(value, bool):
        return None
    try:
        number = int(value)
    except (TypeError, ValueError):
        return None
    return number if number >= 0 else None


def _as_float(value: Any) -> float | None:
    """Accepts finite non-negative accounting values and leaves unknown price unset."""
    if isinstance(value, bool):
        return None
    try:
        number = float(value)
    except (TypeError, ValueError):
        return None
    return number if number >= 0.0 else None


def _response_file(directory: Path) -> Path | None:
    """Prefers the stable full graph response rather than an unrelated API artifact."""
    direct = directory / "request-response.json"
    if direct.is_file():
        return direct
    candidates = sorted(directory.rglob("request-response.json"))
    return candidates[0] if candidates else None


def _metadata_elapsed(directory: Path) -> int | None:
    """Returns the recorded HTTP elapsed time from the nearest metadata capture."""
    for metadata in [directory / "http-metadata.txt", *sorted(directory.rglob("http-metadata.txt"))]:
        if not metadata.is_file():
            continue
        try:
            fields = dict(HTTP_METADATA_PATTERN.findall(metadata.read_text(encoding="utf-8")))
        except (OSError, UnicodeDecodeError):
            continue
        elapsed = _as_integer(fields.get("elapsed_ms"))
        if elapsed is not None:
            return elapsed
    return None


def _event_terminal_status(directory: Path) -> str | None:
    """Uses durable event output as a fallback when a response did not contain its terminal state."""
    for event_file in sorted(directory.rglob("events*.json")):
        payload = _read_json(event_file)
        rows = payload.get("events") if isinstance(payload, dict) else payload
        if not isinstance(rows, list):
            continue
        for row in reversed(rows):
            if not isinstance(row, dict):
                continue
            name = str(row.get("event", row.get("status", ""))).strip().lower()
            if name in TERMINAL_EVENTS:
                return name.upper()
    return None


def _operational_metrics(metrics: dict[str, Any]) -> tuple[dict[str, Any], frozenset[str]]:
    """Extracts only known end-to-end fields; absence remains a release-report finding."""
    aliases = {
        "queueWaitMilliseconds": ("queueWaitMilliseconds", "queueWaitMs"),
        "leaseWaitMilliseconds": ("leaseWaitMilliseconds", "leaseWaitMs"),
        "ackMilliseconds": ("ackMilliseconds", "ackLatencyMilliseconds", "ackLatencyMs"),
        "pdfMilliseconds": ("pdfMilliseconds", "pdfElapsedMilliseconds", "xelatexMilliseconds"),
        "cpuSamples": ("cpuSamples",),
        "rssSamples": ("rssSamples",),
        "gpuSamples": ("gpuSamples", "systemLoad"),
    }
    observed: dict[str, Any] = {}
    missing: set[str] = set()
    for canonical, options in aliases.items():
        value = next((metrics[name] for name in options if metrics.get(name) is not None), None)
        if value is None:
            missing.add(canonical)
        else:
            observed[canonical] = value
    return observed, frozenset(missing)


def _attempt_from_directory(directory: Path) -> AttemptEvidence:
    """Builds one immutable observed record from a single real acceptance directory."""
    response_path = _response_file(directory)
    response = _read_json(response_path) if response_path else None
    root = response if isinstance(response, dict) else {}
    metrics = root.get("metrics") if isinstance(root.get("metrics"), dict) else {}
    elapsed = _metadata_elapsed(directory) or _as_integer(metrics.get("elapsedMs"))
    status = str(root.get("status", "")).strip().upper() or _event_terminal_status(directory)
    operational, operational_missing = _operational_metrics(metrics)
    missing = set(operational_missing)
    fields = {
        "providerSuccessCount": _as_integer(metrics.get("providerSuccesses")),
        "providerFailureCount": _as_integer(metrics.get("providerFailures")),
        "promptTokens": _as_integer(metrics.get("promptTokens")),
        "completionTokens": _as_integer(metrics.get("completionTokens")),
        "totalTokens": _as_integer(metrics.get("totalTokens")),
    }
    for field, value in fields.items():
        if value is None:
            missing.add(field)
    if elapsed is None:
        missing.add("elapsedMilliseconds")
    cost = _as_float(metrics.get("estimatedCost"))
    reported_cost_known = metrics.get("costKnown")
    cost_known = reported_cost_known if isinstance(reported_cost_known, bool) else cost is not None
    if not cost_known:
        # Historic runs used zero as a placeholder. Keep unknown price absent so aggregate cost is never fabricated.
        cost = None
        missing.add("estimatedCost")
    if not status:
        missing.add("terminalStatus")
    return AttemptEvidence(
        directory=directory,
        status=status,
        elapsed_ms=elapsed,
        provider_successes=fields["providerSuccessCount"],
        provider_failures=fields["providerFailureCount"],
        prompt_tokens=fields["promptTokens"],
        completion_tokens=fields["completionTokens"],
        total_tokens=fields["totalTokens"],
        estimated_cost=cost,
        cost_known=cost_known,
        operational=operational,
        missing_fields=frozenset(missing),
    )


def _attempt_directories(root: Path) -> list[Path]:
    """Finds real run folders without mistaking an event-only subdirectory for an attempt."""
    if (root / "request-response.json").is_file():
        return [root]
    directories = sorted({path.parent for path in root.rglob("request-response.json")})
    return directories


def _percentile(values: Iterable[int], percentile: float) -> int | None:
    """Uses nearest-rank percentile so sparse evidence never implies interpolation precision."""
    ordered = sorted(values)
    if not ordered:
        return None
    index = max(0, min(len(ordered) - 1, int((len(ordered) - 1) * percentile)))
    return ordered[index]


def _manifest(directory: Path) -> dict[str, str]:
    """Hashes observed artifacts so a later report can be tied to the exact evidence set."""
    manifest: dict[str, str] = {}
    for path in sorted(file for file in directory.rglob("*") if file.is_file()):
        digest = sha256(path.read_bytes()).hexdigest()
        manifest[str(path.relative_to(directory))] = digest
    return manifest


def summarize_run_directory(directory: Path) -> dict[str, Any]:
    """Returns a JSON-serializable, non-fabricating report for one or more real attempts."""
    root = directory.resolve()
    if not root.is_dir():
        raise FileNotFoundError(f"handout run directory does not exist: {root}")
    attempts = [_attempt_from_directory(path) for path in _attempt_directories(root)]
    if not attempts:
        raise ValueError(f"no request-response.json evidence found below: {root}")
    elapsed_values = [attempt.elapsed_ms for attempt in attempts if attempt.elapsed_ms is not None]
    prompt_values = [attempt.prompt_tokens for attempt in attempts if attempt.prompt_tokens is not None]
    completion_values = [attempt.completion_tokens for attempt in attempts if attempt.completion_tokens is not None]
    total_values = [attempt.total_tokens for attempt in attempts if attempt.total_tokens is not None]
    cost_values = [attempt.estimated_cost for attempt in attempts if attempt.estimated_cost is not None]
    missing = sorted({field for attempt in attempts for field in attempt.missing_fields})
    completed = sum(attempt.status == "COMPLETED" for attempt in attempts)
    failed = sum(attempt.status in {"FAILED", "CANCELLED", "TIMED_OUT"} for attempt in attempts)
    return {
        "sourceDirectory": str(root),
        "attemptCount": len(attempts),
        "successCount": completed,
        "failedCount": failed,
        "providerSuccessCount": sum(value for value in (item.provider_successes for item in attempts) if value is not None),
        "providerFailureCount": sum(value for value in (item.provider_failures for item in attempts) if value is not None),
        "elapsedMilliseconds": {
            "p50": _percentile(elapsed_values, 0.50),
            "p95": _percentile(elapsed_values, 0.95),
            "p99": _percentile(elapsed_values, 0.99),
            "median": int(median(elapsed_values)) if elapsed_values else None,
        },
        "tokens": {
            "prompt": sum(prompt_values) if len(prompt_values) == len(attempts) else None,
            "completion": sum(completion_values) if len(completion_values) == len(attempts) else None,
            "total": sum(total_values) if len(total_values) == len(attempts) else None,
        },
        "estimatedCost": sum(cost_values) if len(cost_values) == len(attempts) else None,
        "costKnown": bool(attempts) and all(attempt.cost_known for attempt in attempts),
        "missingFields": missing,
        "attempts": [
            {
                "directory": str(attempt.directory),
                "status": attempt.status,
                "elapsedMilliseconds": attempt.elapsed_ms,
                "providerSuccessCount": attempt.provider_successes,
                "providerFailureCount": attempt.provider_failures,
                "promptTokens": attempt.prompt_tokens,
                "completionTokens": attempt.completion_tokens,
                "totalTokens": attempt.total_tokens,
                "estimatedCost": attempt.estimated_cost,
                "costKnown": attempt.cost_known,
                "operational": attempt.operational,
                "missingFields": sorted(attempt.missing_fields),
                "sha256Manifest": _manifest(attempt.directory),
            }
            for attempt in attempts
        ],
    }


def main() -> int:
    """Prints a compact human-readable report or full JSON for CI and acceptance archives."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("run_directory", type=Path, help="real acceptance run directory or its parent")
    parser.add_argument("--json", action="store_true", dest="as_json", help="emit full machine-readable evidence")
    arguments = parser.parse_args()
    summary = summarize_run_directory(arguments.run_directory)
    if arguments.as_json:
        print(json.dumps(summary, ensure_ascii=False, indent=2, sort_keys=True))
        return 0
    elapsed = summary["elapsedMilliseconds"]
    print(f"attempts={summary['attemptCount']} success={summary['successCount']} failed={summary['failedCount']}")
    print(f"provider_success={summary['providerSuccessCount']} provider_failure={summary['providerFailureCount']}")
    print(f"elapsed_ms_p50={elapsed['p50']} p95={elapsed['p95']} p99={elapsed['p99']}")
    print(f"tokens={summary['tokens']} estimated_cost={summary['estimatedCost']} cost_known={summary['costKnown']}")
    print("missing_fields=" + (",".join(summary["missingFields"]) or "none"))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
