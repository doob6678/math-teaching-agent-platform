from __future__ import annotations

import argparse
import json
import math
import statistics
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path

import requests


def percentile(values: list[float], ratio: float) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    index = max(0, math.ceil(len(ordered) * ratio) - 1)
    return round(ordered[index], 2)


def request_once(base_url: str, query: str, limit: int, timeout: float) -> dict:
    started = time.perf_counter()
    try:
        response = requests.get(
            f"{base_url.rstrip('/')}/api/retrieval/textbooks/search",
            params={"query": query, "limit": limit},
            timeout=timeout,
        )
        return {
            "status": response.status_code,
            "latency_ms": round((time.perf_counter() - started) * 1000, 2),
            "bytes": len(response.content),
        }
    except requests.RequestException as error:
        return {
            "status": "network_error",
            "latency_ms": round((time.perf_counter() - started) * 1000, 2),
            "bytes": 0,
            "error": str(error),
        }


def run_scenario(base_url: str, name: str, queries: list[str], requests_count: int, concurrency: int, limit: int, timeout: float) -> dict:
    started = time.perf_counter()
    results = []
    with ThreadPoolExecutor(max_workers=concurrency) as executor:
        futures = [
            executor.submit(request_once, base_url, queries[index % len(queries)], limit, timeout)
            for index in range(requests_count)
        ]
        for future in as_completed(futures):
            results.append(future.result())
    elapsed = time.perf_counter() - started
    latencies = [item["latency_ms"] for item in results]
    status_counts: dict[str, int] = {}
    for item in results:
        key = str(item["status"])
        status_counts[key] = status_counts.get(key, 0) + 1
    successful = sum(count for status, count in status_counts.items() if status.startswith("2"))
    return {
        "scenario": name,
        "requests": requests_count,
        "concurrency": concurrency,
        "elapsed_seconds": round(elapsed, 3),
        "qps": round(requests_count / elapsed, 2) if elapsed else 0.0,
        "success_rate": round(successful / requests_count, 4) if requests_count else 0.0,
        "status_counts": status_counts,
        "latency_ms": {
            "p50": percentile(latencies, 0.50),
            "p95": percentile(latencies, 0.95),
            "p99": percentile(latencies, 0.99),
            "mean": round(statistics.mean(latencies), 2) if latencies else 0.0,
        },
        "response_bytes_mean": round(statistics.mean(item["bytes"] for item in results), 2) if results else 0.0,
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://127.0.0.1:8080")
    parser.add_argument("--requests", type=int, default=100)
    parser.add_argument("--concurrency", type=int, default=10)
    parser.add_argument("--limit", type=int, default=10)
    parser.add_argument("--timeout", type=float, default=30.0)
    parser.add_argument("--output", default="")
    args = parser.parse_args()

    if args.requests < 1 or args.concurrency < 1:
        raise SystemExit("--requests and --concurrency must be positive")

    scenarios = [
        run_scenario(
            args.base_url,
            "hot_key",
            ["双曲线定义与渐近线"],
            args.requests,
            args.concurrency,
            args.limit,
            args.timeout,
        ),
        run_scenario(
            args.base_url,
            "high_cardinality",
            [f"双曲线定义与渐近线 变体{i}" for i in range(args.requests)],
            args.requests,
            args.concurrency,
            args.limit,
            args.timeout,
        ),
    ]
    report = {
        "base_url": args.base_url,
        "requests_per_scenario": args.requests,
        "concurrency": args.concurrency,
        "limit": args.limit,
        "scenarios": scenarios,
    }
    payload = json.dumps(report, ensure_ascii=False, indent=2)
    print(payload)
    if args.output:
        Path(args.output).write_text(payload + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
