"""Run the real teacher-resource boundary evaluation against a live Windows backend.

This evaluator deliberately has no mock transport and never provisions users or touches source data.  Cases declare
their expected category, file, block (when stable), or empty/denied outcome.  Authentication profiles are supplied
only by environment so the repository never stores credentials.
"""

from __future__ import annotations

import argparse
import json
import math
import os
import statistics
import sys
import time
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

if __package__ in {None, ""}:
    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from benchmarks.http_client import MathAgentClient


DEFAULT_BACKEND_URL = "http://127.0.0.1:8080"
DEFAULT_LIMIT = 3
REQUIRED_CASE_TYPES = {
    "feishu_correct_file",
    "feishu_wrong_file",
    "mixed_source_type",
    "unrelated",
    "empty_result",
    "cross_tenant",
    "no_permission",
}
RECALL_CUTOFFS = (1, 3)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, default=Path("output/benchmarks"))
    parser.add_argument("--backend-url", default=os.environ.get("MATH_AGENT_BENCHMARK_BACKEND_URL", DEFAULT_BACKEND_URL))
    parser.add_argument("--profiles-json", default=os.environ.get("MATH_AGENT_BENCHMARK_PROFILES", ""))
    parser.add_argument("--limit", type=int, default=DEFAULT_LIMIT)
    parser.add_argument("--timeout", type=float, default=120.0)
    args = parser.parse_args()

    dataset = read_dataset(args.dataset)
    profiles = read_profiles(args.profiles_json)
    missing_profiles = sorted({str(case["authProfile"]) for case in dataset["cases"] if str(case["authProfile"]) not in profiles})
    if missing_profiles:
        raise RuntimeError(
            "configuration_error component=boundary_eval stage=authentication "
            f"message=missing profiles {missing_profiles}; set MATH_AGENT_BENCHMARK_PROFILES with real credentials")

    sessions = {name: login(args.backend_url, profile, args.timeout) for name, profile in profiles.items()}
    rows = [run_case(case, sessions[str(case["authProfile"])], args.backend_url, args.timeout, max(1, args.limit))
            for case in dataset["cases"]]
    report = build_report(dataset, args, rows)
    write_report(args.output_dir, report, rows)
    print(json.dumps(report["summary"], ensure_ascii=False, indent=2))


def read_dataset(path: Path) -> dict[str, Any]:
    payload = json.loads(path.read_text(encoding="utf-8-sig"))
    cases = payload.get("cases") if isinstance(payload, dict) else None
    if not isinstance(cases, list) or not cases:
        raise RuntimeError(f"dataset_error stage=load message=cases must be a non-empty list: {path}")
    case_types = {str(case.get("caseType") or "") for case in cases if isinstance(case, dict)}
    missing_types = sorted(REQUIRED_CASE_TYPES - case_types)
    if missing_types:
        raise RuntimeError(f"dataset_error stage=coverage message=missing required case types {missing_types}")
    for case in cases:
        required = ("caseId", "caseType", "query", "authProfile", "expected")
        missing = [name for name in required if not case.get(name)]
        if missing:
            raise RuntimeError(f"dataset_error stage=validation case={case.get('caseId')} message=missing {missing}")
    return {"datasetVersion": payload.get("datasetVersion", ""), "cases": cases, "path": str(path.resolve())}


def read_profiles(raw: str) -> dict[str, dict[str, str]]:
    if not raw.strip():
        return {}
    value = json.loads(raw)
    if not isinstance(value, dict):
        raise RuntimeError("configuration_error component=boundary_eval stage=profiles message=profiles must be a JSON object")
    profiles: dict[str, dict[str, str]] = {}
    for name, profile in value.items():
        if not isinstance(profile, dict) or not str(profile.get("username") or "").strip() or not str(profile.get("password") or "").strip():
            raise RuntimeError(f"configuration_error component=boundary_eval stage=profiles message=profile {name} needs username and password")
        profiles[str(name)] = {"username": str(profile["username"]), "password": str(profile["password"])}
    return profiles


def login(base_url: str, profile: dict[str, str], timeout: float) -> MathAgentClient:
    client = MathAgentClient(base_url, timeout=timeout)
    client.login(profile["username"], profile["password"])
    return client


def run_case(case: dict[str, Any], client: MathAgentClient, base_url: str, timeout: float, limit: int) -> dict[str, Any]:
    expectation = dict(case["expected"])
    if expectation.get("outcome") == "denied":
        # A separate client is intentional: it proves the protected route rejects an unauthenticated caller and does
        # not accidentally reuse a privileged cookie from another case.
        if case["caseType"] == "no_permission":
            client = MathAgentClient(base_url, timeout=timeout)
        response = client.get("/api/teacher/resources/search", params={"query": case["query"], "limit": limit})
        allowed_statuses = {int(value) for value in expectation.get("statusCodes", [401, 403])}
        return result_row(case, response.status, response.elapsed_ms, [], response.body, response.status in allowed_statuses)

    params: dict[str, Any] = {"query": case["query"], "limit": limit}
    source_type = str(expectation.get("sourceType") or "").strip()
    if source_type:
        params["library"] = source_type
    response = client.get("/api/teacher/resources/search", params=params)
    body = response.body if isinstance(response.body, dict) else {}
    hits = [hit for hit in body.get("hits", []) if isinstance(hit, dict)]
    return result_row(case, response.status, response.elapsed_ms, hits, body, evaluate_hits(expectation, response.status, hits))


def result_row(case: dict[str, Any], status: int, elapsed_ms: float, hits: list[dict[str, Any]], body: Any, passed: bool) -> dict[str, Any]:
    return {
        "caseId": case["caseId"],
        "caseType": case["caseType"],
        "query": case["query"],
        "authProfile": case["authProfile"],
        "expected": case["expected"],
        "httpStatus": status,
        "latencyMs": round(float(elapsed_ms), 3),
        "queryId": str(body.get("queryId") or "") if isinstance(body, dict) else "",
        "passed": passed,
        "hits": [compact_hit(hit) for hit in hits],
    }


def evaluate_hits(expected: dict[str, Any], status: int, hits: list[dict[str, Any]]) -> bool:
    if status != 200:
        return False
    outcome = str(expected.get("outcome") or "hits")
    if outcome == "empty":
        return not hits
    source_type = str(expected.get("sourceType") or "")
    if source_type and any(str(hit.get("sourceType") or "") != source_type for hit in hits):
        return False
    forbidden_types = {str(value) for value in expected.get("forbiddenSourceTypes", [])}
    if forbidden_types and any(str(hit.get("sourceType") or "") in forbidden_types for hit in hits):
        return False
    forbidden_paths = {normalize_path(str(value)) for value in expected.get("forbiddenPaths", [])}
    if forbidden_paths and any(normalize_path(str(hit.get("sourcePath") or "")) in forbidden_paths for hit in hits):
        return False
    expected_path = normalize_path(str(expected.get("sourcePath") or ""))
    expected_block = str(expected.get("blockId") or "")
    if expected_path and not any(normalize_path(str(hit.get("sourcePath") or "")) == expected_path for hit in hits):
        return False
    if expected_block and not any(str(hit.get("blockId") or "") == expected_block for hit in hits):
        return False
    return bool(hits) or outcome == "allow_empty"


def compact_hit(hit: dict[str, Any]) -> dict[str, Any]:
    return {key: hit.get(key) for key in ("documentId", "documentTitle", "fileName", "sourceType", "sourcePath", "pageNo", "blockId", "score")}


def build_report(dataset: dict[str, Any], args: argparse.Namespace, rows: list[dict[str, Any]]) -> dict[str, Any]:
    positives = [row for row in rows if row["caseType"] in {"feishu_correct_file", "mixed_source_type"}]
    latencies = [row["latencyMs"] for row in rows if row["httpStatus"] == 200]
    expected_empty = [row for row in rows if row["caseType"] in {"unrelated", "empty_result"}]
    return {
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "dataset": {"path": dataset["path"], "version": dataset["datasetVersion"], "caseCount": len(rows)},
        "runtime": {"backendUrl": args.backend_url, "limit": max(1, args.limit), "realHttpOnly": True},
        "summary": {
            "fileRecallAt1": recall(positives, 1),
            "fileRecallAt3": recall(positives, 3),
            "blockRecallAt1": block_recall(positives, 1),
            "blockRecallAt3": block_recall(positives, 3),
            "latencyMs": latency_summary(latencies),
            "crossTenantErrorCount": sum(1 for row in rows if row["caseType"] == "cross_tenant" and not row["passed"]),
            "wrongSourceTypeHitCount": wrong_source_type_hits(rows),
            "unauthorizedHitCount": sum(1 for row in rows if row["caseType"] == "no_permission" and not row["passed"]),
            "emptyResultAccuracy": rate(expected_empty, lambda row: row["passed"]),
            "passedCaseCount": sum(1 for row in rows if row["passed"]),
            "failedCaseCount": sum(1 for row in rows if not row["passed"]),
        },
        "cases": rows,
    }


def recall(rows: list[dict[str, Any]], cutoff: int) -> float:
    return rate(rows, lambda row: path_rank(row, str(row["expected"].get("sourcePath") or "")) <= cutoff)


def block_recall(rows: list[dict[str, Any]], cutoff: int) -> float:
    rows_with_block = [row for row in rows if str(row["expected"].get("blockId") or "")]
    return rate(rows_with_block, lambda row: block_rank(row, str(row["expected"].get("blockId"))) <= cutoff)


def path_rank(row: dict[str, Any], expected_path: str) -> int:
    normalized = normalize_path(expected_path)
    for index, hit in enumerate(row["hits"], start=1):
        if normalize_path(str(hit.get("sourcePath") or "")) == normalized:
            return index
    return math.inf


def block_rank(row: dict[str, Any], expected_block: str) -> int:
    for index, hit in enumerate(row["hits"], start=1):
        if str(hit.get("blockId") or "") == expected_block:
            return index
    return math.inf


def wrong_source_type_hits(rows: list[dict[str, Any]]) -> int:
    count = 0
    for row in rows:
        expected_type = str(row["expected"].get("sourceType") or "")
        if expected_type:
            count += sum(1 for hit in row["hits"] if str(hit.get("sourceType") or "") != expected_type)
    return count


def rate(rows: list[dict[str, Any]], predicate) -> float:
    return round(sum(1 for row in rows if predicate(row)) / len(rows), 6) if rows else 0.0


def latency_summary(values: list[float]) -> dict[str, float]:
    if not values:
        return {"average": 0.0, "p95": 0.0, "p99": 0.0}
    values = sorted(values)
    return {"average": round(statistics.fmean(values), 3), "p95": percentile(values, .95), "p99": percentile(values, .99)}


def percentile(values: list[float], fraction: float) -> float:
    return round(values[min(len(values) - 1, max(0, math.ceil(len(values) * fraction) - 1))], 3)


def normalize_path(value: str) -> str:
    return value.replace("\\", "/").strip().casefold()


def write_report(output_dir: Path, report: dict[str, Any], rows: list[dict[str, Any]]) -> None:
    output_dir.mkdir(parents=True, exist_ok=True)
    stamp = datetime.now().date().isoformat()
    base = output_dir / f"teacher-resource-boundary-eval-{stamp}"
    (base.with_suffix(".json")).write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    lines = ["# Teacher Resource Boundary Evaluation", "", f"Generated: `{report['generatedAt']}`", "", "| file@1 | file@3 | block@1 | block@3 | avg/P95/P99 ms | cross tenant errors | category errors | unauthorized hits | empty accuracy |", "|---:|---:|---:|---:|---:|---:|---:|---:|---:|"]
    summary = report["summary"]
    latency = summary["latencyMs"]
    lines.append(f"| {summary['fileRecallAt1']:.3f} | {summary['fileRecallAt3']:.3f} | {summary['blockRecallAt1']:.3f} | {summary['blockRecallAt3']:.3f} | {latency['average']:.1f}/{latency['p95']:.1f}/{latency['p99']:.1f} | {summary['crossTenantErrorCount']} | {summary['wrongSourceTypeHitCount']} | {summary['unauthorizedHitCount']} | {summary['emptyResultAccuracy']:.3f} |")
    lines.extend(["", "## Cases", "", "| Case | Type | HTTP | Passed | Query ID |", "|---|---|---:|---:|---|"])
    lines.extend(f"| {row['caseId']} | {row['caseType']} | {row['httpStatus']} | {row['passed']} | {row['queryId']} |" for row in rows)
    (base.with_suffix(".md")).write_text("\n".join(lines) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
