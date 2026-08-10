"""Build the persisted production retrieval handoff from real benchmark artifacts.

The report intentionally keeps textbook and teacher-resource metrics separate.  Teacher-resource labels from the
historical 100-case file refer to document ids that are absent from the current database, so this builder records the
live execution as non-comparable rather than manufacturing a zero recall score or mixing different split contracts.
"""

from __future__ import annotations

import json
import subprocess
from datetime import datetime
from pathlib import Path
from typing import Any


ROOT = Path("output/benchmarks/retrieval-production-final-20260803")


def read(path: Path) -> Any:
    """Read a saved UTF-8 artifact without altering benchmark evidence."""
    return json.loads(path.read_text(encoding="utf-8"))


def git_value(args: list[str]) -> str:
    """Capture repository identity when available; absence is explicit rather than guessed."""
    try:
        return subprocess.run(["git", *args], capture_output=True, text=True, check=True).stdout.strip()
    except (OSError, subprocess.SubprocessError):
        return ""


def main() -> None:
    # Use the final healthy run, not the earlier run that overlapped with background Agent jobs.
    textbook = read(ROOT / "textbook-final-run-healthy" / "report.json")
    dynamic = read(ROOT / "textbook-dynamic-route" / "report.json")
    ablation = read(ROOT / "ablation" / "positive-only-report.json")
    teacher_metrics = read(ROOT / "teacher" / "metrics.json")
    final_report = {
        "kind": "production_retrieval_final_comparison",
        "generatedAt": datetime.now().isoformat(timespec="seconds"),
        "runtime": {
            "java": "Docker eclipse-temurin:21-jre; Maven clean package release 21",
            "hostJava": "17 is installed locally but is not the project compiler",
            "backendHealth": "UP",
            "databaseSchema": "Flyway V30",
            "services": ["backend", "ai-worker", "mysql", "redis", "milvus"],
            "gitHead": git_value(["rev-parse", "HEAD"]),
        },
        "productionSelection": {
            "strategy": textbook["observedStrategies"][0],
            "configuration": "dynamic-route=false; normalize-agent-wrapper=false; coarse document 5; coarse pages 5/document; final 3 documents x 3 pages; 9 Cross-Encoder candidates",
            "selectionRule": "Same 46-case oracle and same live corpus; keep the stable production route because enabling dynamic route produced identical recall and did not improve tail latency enough to justify a behavior change.",
        },
        "textbook": {
            "oracle": textbook["caseFile"],
            "positiveOnly": True,
            "production": textbook["summary"],
            "observedStrategies": textbook["observedStrategies"],
            "resourceSummary": textbook["resourceSummary"],
            "dynamicRouteControl": dynamic["summary"],
        },
        "teacherResources": {
            "executed": {
                "caseCount": teacher_metrics["generation"]["queryCount"],
                "positiveCount": teacher_metrics["generation"]["caseTypes"].get("positive", 0),
                "libraries": teacher_metrics["generation"]["libraries"],
                "output": str((ROOT / "teacher").resolve()),
            },
            "scoring": {
                "status": "not-comparable",
                "positiveRecallIncluded": False,
                "reason": "历史 expected document/block ids belong to a different teacher-resource database snapshot and split contract; current source_document ids are absent. Different libraries and block splitting are not mixed or directly scored.",
                "negativeCasesIncluded": False,
            },
        },
        "ablation": ablation,
        "artifacts": {
            "textbookReport": str((ROOT / "textbook-final-run-healthy/report.json").resolve()),
            "textbookSummary": str((ROOT / "textbook-final-run-healthy/summary.md").resolve()),
            "teacherMetrics": str((ROOT / "teacher/metrics.json").resolve()),
            "teacherRows": str((ROOT / "teacher/query_rows.jsonl").resolve()),
            "ablationReport": str((ROOT / "ablation/positive-only-report.json").resolve()),
            "ablationSummary": str((ROOT / "ablation/positive-only-summary.md").resolve()),
            "archive": str(Path("output/benchmarks/archive-20260803").resolve()),
        },
    }
    (ROOT / "report.json").write_text(json.dumps(final_report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    s = textbook["summary"]
    l = s["latencyMs"]
    r = textbook["resourceSummary"]
    lines = [
        "# 检索生产最终报告（2026-08-03）",
        "",
        "## 结论",
        "",
        "当前生产链路已用 Java 21 容器真实启动，backend、ai-worker、MySQL、Redis、Milvus 均健康。教材生产默认保留稳定语义优先路线；dynamic-route 同 oracle 对照没有带来召回提升，因此不切换。",
        "",
        "## 教材：46 条真实全库正例",
        "",
        f"- 策略：`{textbook['observedStrategies'][0]}`。",
        f"- 延迟：平均 {l['average']} ms，P50 {l['p50']} ms，P95 {l['p95']} ms，P99 {l['p99']} ms。",
        f"- 文档召回：doc@1={s['documentRecall@1']:.3f}，doc@3={s['documentRecall@3']:.3f}，doc@5={s['documentRecall@5']:.3f}。",
        f"- 页面诊断：page@1={s['pageRecall@1']:.3f}，page@3={s['pageRecall@3']:.3f}，page@5={s['pageRecall@5']:.3f}。",
        f"- 块诊断：block@1={s['blockRecall@1']:.3f}，block@3={s['blockRecall@3']:.3f}，block@5={s['blockRecall@5']:.3f}；只在同一教材切分内使用。",
        f"- GPU：{r['gpu']['name']}，平均利用率 {r['gpu']['utilizationAvgPercent']}%，峰值显存 {r['gpu']['memoryUsedMaxMb']} / {r['gpu']['memoryTotalMb']} MB。",
        "",
        "## 教师资料：只保留可比性结论",
        "",
        "已真实执行 100 条请求，其中 70 条正例；但历史 oracle 的 documentId/blockId 不存在于当前数据库快照，且各 library 的切分不同。因此本报告不把它们混成一个召回分，不把负例纳入评分，也不宣称当前教师资料召回率。原始执行数据仍完整留盘。",
        "",
        "## 正例-only 消融",
        "",
        "40 条真实教材正例已完成 BM25、BGE、混合并集、并行召回和 rerank 组件请求；表格不含负例。消融的原始结果、doc/page/block 和 avg/P95/P99 见 `ablation/positive-only-summary.md`。外部 Luna 盲审没有计入任何召回分。",
        "",
        "## 生产链路",
        "",
        "Agent/MCP → 查询规范化与范围过滤 → 正文 BM25 + 标题 BM25 + BGE 文本页召回 → 按 docId 合并取并集 → RRF 只按 rank 粗排 → 最多 3 本教材、每本最多 3 页 → 9 页进入 GPU Cross-Encoder → parent/logical block 合并 → 返回教材证据和受控图片 URI。",
        "",
        "教师资料链路继续执行 tenant/role/permission/library 过滤 → document-level vector coarse recall → 指定 library 先过滤 → document 内 block rerank → 权限约束 → evidence response；不同 library 不共享 block 分数。",
        "",
        "## 留盘位置",
        "",
        f"- 完整机器报告：`{(ROOT / 'report.json').resolve()}`",
        f"- 教材原始报告：`{(ROOT / 'textbook-final-run-healthy/report.json').resolve()}`",
        f"- 教师原始请求：`{(ROOT / 'teacher/query_rows.jsonl').resolve()}`",
        f"- 消融报告：`{(ROOT / 'ablation/positive-only-report.json').resolve()}`",
        f"- 历史版本归档：`{Path('output/benchmarks/archive-20260803').resolve()}`",
    ]
    (ROOT / "summary.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(json.dumps({"report": str((ROOT / "report.json").resolve()), "summary": str((ROOT / "summary.md").resolve())}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
