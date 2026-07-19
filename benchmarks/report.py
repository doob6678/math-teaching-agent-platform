from __future__ import annotations

import json
from datetime import datetime
from pathlib import Path
from typing import Any


def write_benchmark_report(metrics: dict[str, Any], output_dir: Path | str | None = None) -> Path:
    """Write machine-readable metrics and resume-ready Markdown."""
    target = Path(output_dir) if output_dir else Path("output") / "benchmarks" / _timestamp()
    target.mkdir(parents=True, exist_ok=True)
    (target / "metrics.json").write_text(
        json.dumps(metrics, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    (target / "resume_numbers.md").write_text(_resume_markdown(metrics), encoding="utf-8")
    return target


def _resume_markdown(metrics: dict[str, Any]) -> str:
    rag = metrics.get("rag") or {}
    rag_primary = rag.get("teacherResource") or rag.get("textbook") or rag
    agent = metrics.get("agent") or {}
    security = metrics.get("security") or {}
    latency = rag.get("latency") or {}
    milvus = rag.get("milvus") or {}
    lines = [
        "# MathAgent 可写入简历的真实量化指标",
        "",
        "> 这些数字只能在对应 `metrics.json` 存在且运行环境真实可用时使用；不要脱离上下文夸大。",
        "",
        "## RAG 检索",
        "",
        (
            f"- 样本数 {int(rag.get('sampleCount', 0) or 0)}，"
            f"Recall@1={_percent(rag_primary.get('recall@1', 0))}，"
            f"Recall@3={_percent(rag_primary.get('recall@3', 0))}，"
            f"Recall@5={_percent(rag_primary.get('recall@5', 0))}，"
            f"Recall@10={_percent(rag_primary.get('recall@10', 0))}，"
            f"证据命中率={_percent(rag_primary.get('evidenceHitRate', 0))}，"
            f"平均检索延迟 {int(latency.get('avgMs', 0) or 0)}ms，"
            f"P95 {int(latency.get('p95Ms', 0) or 0)}ms。"
        ),
        (
            f"- Milvus 状态 `{milvus.get('status', '')}`，"
            f"向量行数 {int(milvus.get('rowCount', 0) or 0)}，"
            f"教师资料 block 数 {int(rag.get('teacherParsedBlockCount', rag.get('teacherBlockCount', 0)) or 0)}。"
        ),
        "",
        "## Agent 稳定性",
        "",
        (
            f"- 真实模型调用 {int(agent.get('runCount', 0) or 0)} 次，"
            f"成功率 {_percent(agent.get('successRate', 0))}，"
            f"provider fallback {int(agent.get('providerFallbackCount', 0) or 0)} 次，"
            f"JSON repair 恢复 {int(agent.get('jsonRepairRecoveredCount', 0) or 0)} 次。"
        ),
        (
            f"- 平均 token {int(agent.get('avgTotalTokens', 0) or 0)}，"
            f"平均耗时 {int((agent.get('latency') or {}).get('avgMs', 0) or 0)}ms。"
        ),
        "",
        "## 安全工程",
        "",
        (
            f"- Capability 重放拦截率 "
            f"{_percent((security.get('capabilityReplay') or {}).get('rejectionRate', 0))}，"
            f"重复提交拦截率 {_percent((security.get('duplicateSubmission') or {}).get('blockRate', 0))}。"
        ),
        (
            f"- Redis 限流触发 {(security.get('rateLimit') or {}).get('rateLimitedCount', 0)} 次，"
            f"限流探针 QPS {(security.get('rateLimit') or {}).get('qps', 0)}，"
            f"Agent 并发锁拒绝 {(security.get('agentConcurrency') or {}).get('rejectedCount', 0)} 次。"
        ),
        "",
    ]
    return "\n".join(lines)


def _percent(value: Any) -> str:
    return f"{float(value or 0) * 100:.1f}%"


def _timestamp() -> str:
    return datetime.now().strftime("%Y%m%d-%H%M%S")
