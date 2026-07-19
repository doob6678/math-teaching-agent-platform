from __future__ import annotations

import argparse
import json
import sys
import time
from pathlib import Path
from typing import Any

if __package__ in {None, ""}:
    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from benchmarks.http_client import MathAgentClient, stable_request_hash
from benchmarks.metrics import count_agent_diagnostics


def run_agent_stability_eval(client: MathAgentClient, config: dict[str, Any], run_count: int) -> dict[str, Any]:
    """Run real model-backed Agent executions and summarize trace diagnostics."""
    runs = []
    for index in range(run_count):
        started = time.perf_counter()
        plan_body = _plan_body(index, config)
        plan = client.post("/api/agents/run-plan", plan_body)
        if plan.status != 200 or not isinstance(plan.body, dict):
            runs.append(_failed_run(index, plan, started, "plan_failed"))
            continue
        execute_body = {
            "plan": plan.body,
            "userInputSummary": _agent_prompt(index),
            "evidenceRefs": [],
            "dryRun": False,
        }
        headers = {}
        if bool(plan.body.get("capabilityRequired")):
            request_hash = stable_request_hash(execute_body)
            capability = client.post("/api/security/capabilities", {
                "action": plan.body.get("capabilityAction") or f"agent-run:{plan.body.get('agentCode')}",
                "path": "/api/agents/execute",
                "requestHash": request_hash,
                "idempotencyKey": f"agent-stability-{int(time.time() * 1000)}-{index}",
                "maxCost": plan.body.get("estimatedCost", 0),
            })
            if capability.status != 200 or not isinstance(capability.body, dict):
                runs.append(_failed_run(index, capability, started, "capability_failed", plan.body))
                continue
            headers = {
                "X-Capability-Token": str(capability.body.get("token", "")),
                "X-Request-Hash": request_hash,
            }
        execution = client.post("/api/agents/execute", execute_body, headers=headers)
        elapsed_ms = int(round((time.perf_counter() - started) * 1000))
        if execution.status != 200 or not isinstance(execution.body, dict):
            runs.append(_failed_run(index, execution, started, "execute_failed", plan.body))
            continue
        trace = client.get(f"/api/agents/traces/{execution.body.get('traceId')}")
        trace_body = trace.body if isinstance(trace.body, dict) else {}
        runs.append({
            "index": index,
            "ok": True,
            "elapsedMs": elapsed_ms,
            "traceId": execution.body.get("traceId", ""),
            "providerName": execution.body.get("providerName", ""),
            "modelCode": execution.body.get("modelCode", ""),
            "actualUsage": execution.body.get("actualUsage") or {},
            "diagnosticEvents": trace_body.get("diagnosticEvents") or execution.body.get("diagnosticEvents") or [],
            "stageTimings": execution.body.get("stageTimings") or [],
        })
    summary = count_agent_diagnostics(runs)
    summary["runs"] = runs
    return summary


def _plan_body(index: int, config: dict[str, Any]) -> dict[str, Any]:
    preferred_provider = str(config.get("preferredProviderName", ""))
    preferred_model = str(config.get("preferredModelCode", ""))
    return {
        "agentCode": "CoursewareAgent",
        "taskType": "courseware_generation",
        "userVipLevel": "vip",
        "estimatedInputTokens": 1200,
        "estimatedOutputTokens": 800,
        "hasImage": False,
        "hasFormula": True,
        "difficulty": "medium",
        "latencyRequirement": "normal",
        "costBudget": 5.0,
        "previousFailureCount": 0,
        "requiredJsonSchema": True,
        "requestedToolScopes": ["tool:courseware:generate", "tool:search:textbook"],
        "disabledToolScopes": [],
        "requestedDataScopes": ["PUBLIC_TEXTBOOK", "TEACHER_PRIVATE"],
        "highValueOperation": True,
        "preferredProviderName": preferred_provider,
        "preferredModelCode": preferred_model,
    }


def _agent_prompt(index: int) -> str:
    return (
        "请基于高中人教版数学内容生成一个严格 JSON 对象，字段包含 markdown、key_points、risks。"
        f"主题序号 {index + 1}：导数、空间向量、圆锥曲线三选一，内容要简短。"
    )


def _failed_run(index: int, attempt, started: float, reason: str, plan: dict[str, Any] | None = None) -> dict[str, Any]:
    return {
        "index": index,
        "ok": False,
        "reason": reason,
        "status": attempt.status,
        "elapsedMs": int(round((time.perf_counter() - started) * 1000)),
        "plan": plan or {},
        "actualUsage": {"promptTokens": 0, "completionTokens": 0, "totalTokens": 0},
        "diagnosticEvents": [],
        "body": attempt.body,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description="Run real model-backed MathAgent stability benchmark.")
    parser.add_argument("--config", default="benchmarks/config.example.json")
    parser.add_argument("--output", default="output/benchmarks/manual-agent.json")
    parser.add_argument("--runs", type=int, default=10)
    args = parser.parse_args()
    config = json.loads(Path(args.config).read_text(encoding="utf-8"))
    client = MathAgentClient(config.get("backendBaseUrl", "http://127.0.0.1:8080"), timeout=120)
    client.login(config.get("teacherUsername", "teacher"), config.get("teacherPassword", "teacher-123456"))
    metrics = run_agent_stability_eval(client, config, args.runs)
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(metrics, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(metrics, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
