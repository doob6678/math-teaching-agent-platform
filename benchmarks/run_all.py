from __future__ import annotations

import argparse
import json
import sys
from datetime import datetime
from pathlib import Path
from typing import Any

if __package__ in {None, ""}:
    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from benchmarks.agent_stability_eval import run_agent_stability_eval
from benchmarks.http_client import MathAgentClient
from benchmarks.rag_eval import run_rag_eval
from benchmarks.report import write_benchmark_report
from benchmarks.security_load_eval import run_security_load_eval


def run_all(config: dict[str, Any], output_dir: Path, rag_limit: int, agent_runs: int, security_concurrency: int) -> dict[str, Any]:
    output_dir.mkdir(parents=True, exist_ok=True)
    client = MathAgentClient(config.get("backendBaseUrl", "http://127.0.0.1:8080"), timeout=120)
    login = client.login(config.get("teacherUsername", "teacher"), config.get("teacherPassword", "teacher-123456"))
    health = client.get("/api/system/health")
    runtime = client.get("/api/system/runtime")
    metrics = {
        "generatedAt": datetime.now().isoformat(timespec="seconds"),
        "environment": {
            "backendBaseUrl": config.get("backendBaseUrl", "http://127.0.0.1:8080"),
            "loginUserId": login.get("userId"),
            "loginRole": login.get("role"),
            "healthStatus": health.status,
            "runtimeStatus": runtime.status,
            "missingSourceRoots": _missing_roots(config),
        },
        "rag": run_rag_eval(client, config, rag_limit, output_dir),
        "agent": run_agent_stability_eval(client, config, agent_runs),
        "security": run_security_load_eval(client, config, security_concurrency),
    }
    write_benchmark_report(metrics, output_dir)
    (output_dir / "all_metrics.json").write_text(json.dumps(metrics, ensure_ascii=False, indent=2), encoding="utf-8")
    return metrics


def _missing_roots(config: dict[str, Any]) -> list[str]:
    roots = list(config.get("questionRoots") or []) + list(config.get("textbookRoots") or [])
    return [str(root) for root in roots if not Path(root).exists()]


def main() -> None:
    parser = argparse.ArgumentParser(description="Run all real MathAgent quantitative benchmarks.")
    parser.add_argument("--config", default="benchmarks/config.example.json")
    parser.add_argument("--output-dir", default="")
    parser.add_argument("--rag-limit", type=int, default=0)
    parser.add_argument("--agent-runs", type=int, default=0)
    parser.add_argument("--security-concurrency", type=int, default=0)
    args = parser.parse_args()
    config = json.loads(Path(args.config).read_text(encoding="utf-8"))
    output_dir = Path(args.output_dir) if args.output_dir else Path("output") / "benchmarks" / datetime.now().strftime("%Y%m%d-%H%M%S")
    rag_limit = args.rag_limit or int(config.get("ragSampleLimit", 50) or 50)
    agent_runs = args.agent_runs or int(config.get("agentRuns", 10) or 10)
    security_concurrency = args.security_concurrency or int(config.get("securityConcurrency", 20) or 20)
    metrics = run_all(config, output_dir, rag_limit, agent_runs, security_concurrency)
    print(json.dumps({
        "outputDir": str(output_dir),
        "ragSamples": metrics["rag"].get("sampleCount"),
        "agentRuns": metrics["agent"].get("runCount"),
        "capabilityReplayRejectionRate": metrics["security"].get("capabilityReplay", {}).get("rejectionRate"),
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
