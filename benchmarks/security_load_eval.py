from __future__ import annotations

import argparse
import concurrent.futures
import json
import sys
import time
from pathlib import Path
from typing import Any, Callable

if __package__ in {None, ""}:
    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from benchmarks.http_client import MathAgentClient
from benchmarks.metrics import summarize_security_results


def run_security_load_eval(client: MathAgentClient, config: dict[str, Any], concurrency: int) -> dict[str, Any]:
    """Run real security checks against authenticated subject, rate-limit, and Agent concurrency paths."""
    timings: dict[str, int] = {}

    def timed(name: str, fn: Callable[[], list[dict[str, Any]]]) -> list[dict[str, Any]]:
        started = time.perf_counter()
        rows = fn()
        timings[name] = int(round((time.perf_counter() - started) * 1000))
        return rows

    results = {
        "authenticatedExecution": timed("authenticatedExecution", lambda: _authenticated_execution(client)),
        "agentConcurrency": timed(
            "agentConcurrency",
            lambda: _agent_concurrency_probe(client, min(3, max(1, concurrency))),
        ),
        "duplicateSubmission": timed("duplicateSubmission", lambda: _duplicate_submission(client, concurrency)),
        "rateLimit": timed("rateLimit", lambda: _rate_limit_probe(client)),
    }
    summary = summarize_security_results(results)
    for name, elapsed_ms in timings.items():
        bucket = summary.get(name)
        if isinstance(bucket, dict):
            attempts = int(bucket.get("attemptCount", 0) or 0)
            bucket["elapsedMs"] = elapsed_ms
            bucket["qps"] = round(attempts / (elapsed_ms / 1000), 3) if elapsed_ms > 0 else 0
    summary["rawAttempts"] = results
    return summary


def _duplicate_submission(client: MathAgentClient, concurrency: int) -> list[dict[str, Any]]:
    _, execute_body, plan = _prepared_agent_execution(client)
    if plan.status != 200:
        return [_attempt_row(plan)]
    with concurrent.futures.ThreadPoolExecutor(max_workers=min(concurrency, 20)) as executor:
        futures = [executor.submit(client.post, "/api/agents/execute", execute_body) for _ in range(concurrency)]
        return [_attempt_row(future.result()) for future in concurrent.futures.as_completed(futures)]


def _authenticated_execution(client: MathAgentClient) -> list[dict[str, Any]]:
    _, execute_body, plan = _prepared_agent_execution(client)
    if plan.status != 200:
        return [_attempt_row(plan)]
    return [_attempt_row(client.post("/api/agents/execute", execute_body))]


def _rate_limit_probe(client: MathAgentClient) -> list[dict[str, Any]]:
    attempts = []
    # The run-plan endpoint has a 30/minute policy; all attempts share the logged-in user subject.
    for index in range(35):
        body = {
            "action": "agent-run:CoursewareAgent",
            "path": "/api/agents/execute",
            "requestHash": f"rate-limit-probe-{int(time.time())}-{index}",
            "idempotencyKey": f"rate-limit-probe-{int(time.time() * 1000)}-{index}",
            "maxCost": 0.01,
        }
        attempts.append(_attempt_row(client.post("/api/agents/run-plan", body)))
    return attempts


def _agent_concurrency_probe(client: MathAgentClient, concurrency: int) -> list[dict[str, Any]]:
    prepared = [_prepared_agent_execution(client) for _ in range(concurrency)]

    def execute(item):
        _, execute_body, plan = item
        if plan.status != 200:
            return plan
        return client.post("/api/agents/execute", execute_body)

    with concurrent.futures.ThreadPoolExecutor(max_workers=concurrency) as executor:
        futures = [executor.submit(execute, item) for item in prepared]
        return [_attempt_row(future.result()) for future in concurrent.futures.as_completed(futures)]


def _prepared_agent_execution(client: MathAgentClient):
    plan_body = {
        "agentCode": "CoursewareAgent",
        "taskType": "courseware_generation",
        "userVipLevel": "vip",
        "estimatedInputTokens": 600,
        "estimatedOutputTokens": 300,
        "hasImage": False,
        "hasFormula": True,
        "difficulty": "medium",
        "latencyRequirement": "normal",
        "costBudget": 5.0,
        "previousFailureCount": 0,
        "requiredJsonSchema": True,
        "requestedToolScopes": ["tool:courseware:generate"],
        "disabledToolScopes": [],
        "requestedDataScopes": ["PUBLIC_TEXTBOOK"],
        "highValueOperation": True,
        "preferredProviderName": "",
        "preferredModelCode": "",
    }
    plan = client.post("/api/agents/run-plan", plan_body)
    if plan.status != 200 or not isinstance(plan.body, dict):
        empty_body = {"plan": {}, "userInputSummary": "", "evidenceRefs": [], "dryRun": False}
        return plan_body, empty_body, plan
    execute_body = {
        "plan": plan.body,
        "userInputSummary": "请返回严格 JSON 对象，字段 markdown/key_points/risks，内容为高中数学微型讲义。",
        "evidenceRefs": [],
        "dryRun": False,
    }
    return plan_body, execute_body, plan


def _attempt_row(attempt) -> dict[str, Any]:
    return {
        "status": attempt.status,
        "elapsedMs": attempt.elapsed_ms,
        "ok": attempt.ok,
        "body": _safe_body(attempt.body),
    }


def _safe_body(body) -> dict[str, Any] | str:
    """Keep benchmark evidence useful without persisting raw model outputs."""
    if not isinstance(body, dict):
        return str(body)[:300]
    safe_keys = {
        "code",
        "message",
        "status",
        "error",
        "path",
        "traceId",
        "agentCode",
        "providerName",
        "modelCode",
        "actualUsage",
        "stageTimings",
        "limit",
        "used",
    }
    return {key: value for key, value in body.items() if key in safe_keys}


def main() -> None:
    parser = argparse.ArgumentParser(description="Run real MathAgent security/load benchmark probes.")
    parser.add_argument("--config", default="benchmarks/config.example.json")
    parser.add_argument("--output", default="output/benchmarks/manual-security.json")
    parser.add_argument("--concurrency", type=int, default=20)
    args = parser.parse_args()
    config = json.loads(Path(args.config).read_text(encoding="utf-8"))
    client = MathAgentClient(config.get("backendBaseUrl", "http://127.0.0.1:8080"), timeout=120)
    client.login(config.get("teacherUsername", "teacher"), config.get("teacherPassword", "teacher-123456"))
    metrics = run_security_load_eval(client, config, args.concurrency)
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(metrics, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(metrics, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
