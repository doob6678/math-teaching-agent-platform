from __future__ import annotations

import math
from collections.abc import Iterable, Mapping, Sequence
from statistics import mean


def compute_recall_summary(cases: Sequence[Mapping], cutoffs: Sequence[int] = (1, 3, 5, 10)) -> dict:
    """Compute strict document/block recall from real search result rows."""
    summary: dict[str, float | int] = {"sampleCount": len(cases)}
    for cutoff in cutoffs:
        hit_count = 0
        for case in cases:
            if _case_hits_expected(case, cutoff):
                hit_count += 1
        summary[f"hitCount@{cutoff}"] = hit_count
        summary[f"recall@{cutoff}"] = hit_count / len(cases) if cases else 0.0
    return summary


def compute_latency_summary(latencies_ms: Iterable[int | float]) -> dict:
    """Return stable latency statistics in milliseconds."""
    values = sorted(int(round(value)) for value in latencies_ms)
    if not values:
        return {"count": 0, "minMs": 0, "maxMs": 0, "avgMs": 0, "p95Ms": 0}
    return {
        "count": len(values),
        "minMs": values[0],
        "maxMs": values[-1],
        "avgMs": int(round(mean(values))),
        "p95Ms": _percentile_nearest_rank(values, 0.95),
    }


def count_agent_diagnostics(runs: Sequence[Mapping]) -> dict:
    """Summarize real Agent trace diagnostics without inspecting raw prompts or outputs."""
    success_count = sum(1 for run in runs if bool(run.get("ok")))
    event_counts: dict[str, int] = {}
    total_tokens = 0
    elapsed = []
    recovered = 0
    for run in runs:
        usage = run.get("actualUsage") or {}
        total_tokens += int(usage.get("totalTokens", 0) or 0)
        if run.get("elapsedMs") is not None:
            elapsed.append(int(run["elapsedMs"]))
        events = list(run.get("diagnosticEvents") or [])
        for event in events:
            event_type = str(event.get("eventType", ""))
            event_counts[event_type] = event_counts.get(event_type, 0) + 1
        if _has_json_repair_recovery(events):
            recovered += 1
    run_count = len(runs)
    return {
        "runCount": run_count,
        "successCount": success_count,
        "successRate": success_count / run_count if run_count else 0.0,
        "jsonParseFailureCount": event_counts.get("JSON_PARSE_FAILED", 0),
        "jsonRepairRecoveredCount": recovered,
        "providerFallbackCount": event_counts.get("PROVIDER_ROTATED", 0),
        "modelCallFailureCount": event_counts.get("MODEL_CALL_FAILED", 0),
        "totalTokens": total_tokens,
        "avgTotalTokens": int(round(total_tokens / run_count)) if run_count else 0,
        "latency": compute_latency_summary(elapsed),
    }


def summarize_security_results(results: Mapping[str, Sequence[Mapping]]) -> dict:
    """Summarize security benchmark HTTP attempts by scenario."""
    replay = list(results.get("capabilityReplay") or [])
    mismatch = list(results.get("requestHashMismatch") or [])
    rate_limit = list(results.get("rateLimit") or [])
    concurrency = list(results.get("agentConcurrency") or [])
    replay_success = _count_status(replay, 200)
    replay_rejected = len(replay) - replay_success
    return {
        "capabilityReplay": {
            "attemptCount": len(replay),
            "successCount": replay_success,
            "rejectedCount": replay_rejected,
            "rejectionRate": replay_rejected / len(replay) if replay else 0.0,
        },
        "requestHashMismatch": {
            "attemptCount": len(mismatch),
            "blockedCount": sum(1 for row in mismatch if int(row.get("status", 0) or 0) in {400, 403}),
        },
        "rateLimit": {
            "attemptCount": len(rate_limit),
            "rateLimitedCount": _count_status(rate_limit, 429),
        },
        "agentConcurrency": {
            "attemptCount": len(concurrency),
            "successCount": _count_status(concurrency, 200),
            "rejectedCount": sum(1 for row in concurrency if int(row.get("status", 0) or 0) in {403, 409, 429}),
        },
    }


def _case_hits_expected(case: Mapping, cutoff: int) -> bool:
    expected_doc = str(case.get("expectedDocumentId") or "")
    expected_block = str(case.get("expectedBlockId") or "")
    expected_chunk = str(case.get("expectedChunkId") or "")
    for hit in list(case.get("hits") or [])[:cutoff]:
        if expected_doc and str(hit.get("documentId") or hit.get("docId") or "") == expected_doc:
            return True
        if expected_block and str(hit.get("blockId") or "") == expected_block:
            return True
        if expected_chunk and str(hit.get("chunkId") or "") == expected_chunk:
            return True
    return False


def _percentile_nearest_rank(values: Sequence[int], percentile: float) -> int:
    if not values:
        return 0
    rank = max(1, math.ceil(percentile * len(values)))
    return values[min(rank - 1, len(values) - 1)]


def _has_json_repair_recovery(events: Sequence[Mapping]) -> bool:
    saw_retry = False
    for event in events:
        event_type = str(event.get("eventType", ""))
        if event_type == "RETRY_SCHEDULED":
            saw_retry = True
        if saw_retry and event_type == "JSON_PARSE_SUCCEEDED":
            return True
    return False


def _count_status(rows: Sequence[Mapping], status: int) -> int:
    return sum(1 for row in rows if int(row.get("status", 0) or 0) == status)
