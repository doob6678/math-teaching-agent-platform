#!/usr/bin/env python3
"""Aggregate durable handout metrics from MySQL without treating absent timings as zero.

Run this inside the Linux Worker container (where PyMySQL is already installed) or another approved runtime with the
same restricted read credentials. The query is read-only and groups by workflow/result and provider/model so a
single fast probe cannot be mistaken for a production latency distribution.
"""

from __future__ import annotations

import json
import os
from collections import defaultdict
from math import ceil
from typing import Any


def percentile(values: list[float], fraction: float) -> float | None:
    """Returns nearest-rank percentile while preserving ``None`` for an empty evidence set."""
    if not values:
        return None
    ordered = sorted(values)
    return ordered[max(0, min(len(ordered) - 1, ceil(len(ordered) * fraction) - 1))]


def distribution(values: list[float]) -> dict[str, float | None]:
    """Reports only observed values; missing queue/PDF timings never become fabricated zeroes."""
    return {
        "count": len(values),
        "p50": percentile(values, 0.50),
        "p95": percentile(values, 0.95),
        "p99": percentile(values, 0.99),
    }


def sample_count(raw: Any) -> int:
    """Counts persisted load samples without converting malformed or absent JSON into observed zero-load evidence."""
    if raw is None:
        return 0
    try:
        decoded = json.loads(raw) if isinstance(raw, str) else raw
    except (TypeError, ValueError, json.JSONDecodeError):
        return 0
    return len(decoded) if isinstance(decoded, list) else 0


def _connection():
    import pymysql

    return pymysql.connect(
        host=os.getenv("MATH_AGENT_DB_HOST", "mysql"),
        port=int(os.getenv("MATH_AGENT_DB_PORT", "3306")),
        user=os.getenv("MATH_AGENT_DB_USERNAME", "ai_runtime"),
        password=os.getenv("MATH_AGENT_DB_PASSWORD", ""),
        database=os.getenv("MATH_AGENT_DB_NAME", "math_agent_rag"),
        autocommit=True,
        charset="utf8mb4",
        cursorclass=pymysql.cursors.DictCursor,
        read_timeout=10,
        connect_timeout=5,
    )


def select_known_columns(cursor: Any, table: str, columns: tuple[str, ...]) -> list[dict[str, Any]]:
    """Selects an explicit null for a not-yet-migrated metric column instead of rejecting the whole report.

    The table names and requested column names are module constants, so this helper cannot receive user-controlled SQL.
    A null remains missing evidence in the aggregation; it is never coerced to a token count, cost, or timing value.
    """
    cursor.execute(f"SHOW COLUMNS FROM {table}")
    present = {str(row["Field"]) for row in cursor.fetchall()}
    projection = ",".join(name if name in present else f"NULL AS {name}" for name in columns)
    cursor.execute(f"SELECT {projection} FROM {table}")
    return list(cursor.fetchall())


def summarize() -> dict[str, Any]:
    """Reads both durable metric tables using SELECT-only statements and returns JSON-safe aggregates."""
    runs: list[dict[str, Any]]
    nodes: list[dict[str, Any]]
    attempts: list[dict[str, Any]]
    with _connection() as connection:
        with connection.cursor() as cursor:
            runs = select_known_columns(cursor, "handout_run_metrics", (
                "workflow_code", "result_status", "queue_wait_ms", "lease_wait_ms", "ack_latency_ms",
                "pdf_elapsed_ms", "request_bytes", "response_bytes", "retry_count", "dlq_count",
                "cpu_samples_json", "rss_samples_json", "gpu_samples_json",
            ))
            nodes = select_known_columns(cursor, "handout_node_metrics", (
                "provider", "model_code", "elapsed_ms", "provider_calls", "prompt_tokens",
                "cached_prompt_tokens", "completion_tokens", "total_tokens", "estimated_cost", "cost_known",
            ))
            attempts = select_known_columns(cursor, "ai_usage_event", (
                "provider", "model_code", "status", "prompt_tokens", "cached_prompt_tokens",
                "completion_tokens", "total_tokens", "estimated_cost", "cost_known", "usage_source", "error_code",
            ))

    by_run_group: dict[str, dict[str, Any]] = defaultdict(lambda: {"status": [], "timings": defaultdict(list)})
    for row in runs:
        key = str(row.get("workflow_code") or "unknown")
        group = by_run_group[key]
        group["status"].append(str(row.get("result_status") or "UNKNOWN"))
        for field in ("queue_wait_ms", "lease_wait_ms", "ack_latency_ms", "pdf_elapsed_ms"):
            value = row.get(field)
            if value is not None:
                group["timings"][field].append(float(value))

    by_provider: dict[str, dict[str, Any]] = defaultdict(
        lambda: {"attemptStatus": [], "timings": [], "tokens": 0, "cachedTokens": 0, "knownCosts": [],
                 "external": [], "externalTokens": 0, "externalCachedTokens": 0, "runtimeTokens": 0}
    )
    for row in nodes:
        key = f"{row.get('provider') or 'unknown'}/{row.get('model_code') or 'unknown'}"
        group = by_provider[key]
        if row.get("elapsed_ms") is not None:
            group["timings"].append(float(row["elapsed_ms"]))

    for row in attempts:
        key = f"{row.get('provider') or 'unknown'}/{row.get('model_code') or 'unknown'}"
        group = by_provider[key]
        group["attemptStatus"].append(str(row.get("status") or "UNKNOWN"))
        # Runtime node entries are immutable audit events, but only an external model provider is a billable call.
        external = (
            str(row.get("provider") or "").lower() not in {"runtime", "contract"}
            and str(row.get("usage_source") or "").lower() != "runtime"
        )
        group["external"].append(external)
        total_tokens = int(row.get("total_tokens") or 0)
        cached_tokens = int(row.get("cached_prompt_tokens") or 0)
        group["tokens"] += total_tokens
        group["cachedTokens"] += cached_tokens
        if external:
            group["externalTokens"] += total_tokens
            group["externalCachedTokens"] += cached_tokens
        else:
            group["runtimeTokens"] += total_tokens
        if row.get("cost_known") and row.get("estimated_cost") is not None:
            group["knownCosts"].append(float(row["estimated_cost"]))

    load_sample_count = sum(sample_count(row.get("cpu_samples_json")) for row in runs)

    return {
        "runCount": len(runs),
        "nodeCount": len(nodes),
        "workflow": {
            key: {
                "resultStatus": dict((status, group["status"].count(status)) for status in sorted(set(group["status"]))),
                "timings": {field: distribution(values) for field, values in group["timings"].items()},
                "missingTimingFields": [field for field in ("queue_wait_ms", "lease_wait_ms", "ack_latency_ms", "pdf_elapsed_ms") if not group["timings"].get(field)],
            }
            for key, group in by_run_group.items()
        },
        "providerModel": {
            key: {
                "providerCallCount": len(group["attemptStatus"]),
                "providerSuccessCount": group["attemptStatus"].count("SUCCESS"),
                "providerFailureCount": group["attemptStatus"].count("FAILED"),
                "costKnownCount": len(group["knownCosts"]),
                "costUnknownCount": sum(1 for status in group["attemptStatus"] if status in {"SUCCESS", "FAILED"}) - len(group["knownCosts"]),
                "elapsedMs": distribution(group["timings"]),
                "tokens": {"total": group["tokens"], "cachedPrompt": group["cachedTokens"]},
                "externalProviderTokens": {"total": group["externalTokens"], "cachedPrompt": group["externalCachedTokens"]},
                "knownEstimatedCost": sum(group["knownCosts"]) if group["knownCosts"] else None,
            }
            for key, group in by_provider.items()
        },
        "providerCallCount": sum(sum(group["external"]) for group in by_provider.values()),
        "providerSuccessCount": sum(
            sum(status == "SUCCESS" and external for status, external in zip(group["attemptStatus"], group["external"]))
            for group in by_provider.values()
        ),
        "providerFailureCount": sum(
            sum(status == "FAILED" and external for status, external in zip(group["attemptStatus"], group["external"]))
            for group in by_provider.values()
        ),
        "runtimeEventCount": sum(sum(not external for external in group["external"]) for group in by_provider.values()),
        "providerTokens": {
            "total": sum(group["externalTokens"] for group in by_provider.values()),
            "cachedPrompt": sum(group["externalCachedTokens"] for group in by_provider.values()),
        },
        "runtimeEventTokens": sum(group["runtimeTokens"] for group in by_provider.values()),
        "systemLoadSampleCount": load_sample_count,
        "missingGlobalFields": [field for field in ("queue_wait_ms", "lease_wait_ms", "ack_latency_ms", "pdf_elapsed_ms") if not any(row.get(field) is not None for row in runs)],
    }


if __name__ == "__main__":
    print(json.dumps(summarize(), ensure_ascii=False, indent=2, sort_keys=True))
