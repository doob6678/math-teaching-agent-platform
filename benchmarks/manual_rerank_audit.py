"""Run a UTF-8, manually authored RAG and reranker audit against the live local stack.

This is intentionally separate from source-fragment recall evaluation.  The questions are paraphrased by a reviewer,
include old-document facts, and record evidence rather than inferring relevance from HTTP success.
"""
from __future__ import annotations

import argparse
import json
import sys
from datetime import datetime
from pathlib import Path
from typing import Any

import requests

if __package__ in {None, ""}:
    # Direct Windows execution starts this file from benchmarks/, so expose the project package root explicitly.
    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from benchmarks.http_client import MathAgentClient


MANUAL_CASES: tuple[tuple[str, str, str], ...] = (
    ("teacher_resource", "判断一个函数在哪些区间递增，最先要列出什么条件？", "单调性：定义域与导数符号表"),
    ("teacher_resource", "导数为零的点很多时，怎样避免误判函数的增减趋势？", "单调性：零点分区间与符号表"),
    ("teacher_resource", "带参数的单调性题，端点和定义域要怎样检查？", "单调性：边界检查"),
    ("qq_bundle", "空间几何的底面和高如何放进直角坐标系？", "空间向量：建系"),
    ("qq_bundle", "直线和平面的夹角求解为什么常用法向量？", "空间向量：法向量"),
    ("qq_bundle", "用向量证明线面垂直时，应建立哪些向量关系？", "空间向量：垂直"),
    ("feishu", "从盒中连续摸球且不放回，适合采用什么概率模型？", "概率：超几何"),
    ("feishu", "重复进行互不影响的试验如何写成功次数概率？", "概率：二项"),
    ("feishu", "哪些抽样情形不能直接按二项分布计算？", "概率：非独立"),
    ("gaokao", "椭圆相关面积取最值时，通常怎样选择切点参数？", "圆锥曲线：切线参数"),
    ("gaokao", "已知圆锥曲线的切线斜率，怎样把它化为一个优化问题？", "圆锥曲线：斜率与最值"),
    ("gaokao", "求椭圆切线最值为什么要先选几何意义明确的变量？", "圆锥曲线：几何变量"),
    ("mock_exam", "前n项和已知时，如何由Sn写出数列第n项？", "数列：Sn 转 an"),
    ("mock_exam", "把Sn改写成an时，为何n等于1必须另外验证？", "数列：首项"),
    ("mock_exam", "递推数列中，Sn与Sn减一相减能得到什么？", "数列：差分"),
    ("gaokao", "2022年全国乙卷理科数学试卷包含多少道选择题？", "旧卷事实：选择题数量"),
    ("gaokao", "这份全国乙卷理科数学试卷的填空题总分是多少？", "旧卷事实：填空题分值"),
    ("gaokao", "2022全国乙卷数学答题时选择题答案应写在哪里？", "旧卷事实：答题卡"),
)

# These pairs deliberately come from different libraries. The reranker sees no benchmark identifiers or expected ids.
PAIR_CONTROLS: tuple[tuple[str, str, str], ...] = (
    ("函数增减区间如何判断", "函数单调性先检查定义域和端点，再依据导数符号表判断增减。", "不放回抽取应采用超几何分布。"),
    ("空间坐标系怎样计算线面角", "空间向量建系时把底面放在xOy平面，高放在z轴，法向量用于线面角。", "Sn与an的转换要检查数列首项。"),
    ("不放回抽取概率该选什么分布", "概率题应先区分放回独立试验与不放回抽取，再选择二项或超几何模型。", "椭圆切线最值可选几何意义明确的参数。"),
    ("已知数列前n项和怎样求通项", "Sn与an转换使用an等于Sn减Sn减一，并单独检查首项。", "法向量用于处理线面角。"),
    ("高考选择题答案写在什么地方", "回答选择题时，选出答案后在答题卡上对应题目的答案标号涂黑。", "函数的定义域为实数集。"),
)


def main() -> None:
    parser = argparse.ArgumentParser(description="Run manually authored, UTF-8 RAG audit queries.")
    parser.add_argument("--backend", default="http://127.0.0.1:8080")
    parser.add_argument("--worker", default="http://127.0.0.1:8091")
    parser.add_argument("--username", default="admin")
    parser.add_argument("--password", default="admin-123456")
    parser.add_argument("--worker-key-file", default=".local-secrets/worker-api-key.txt")
    parser.add_argument("--output-dir", default="")
    args = parser.parse_args()

    output_dir = Path(args.output_dir) if args.output_dir else Path("output/benchmarks") / f"manual-rerank-audit-{datetime.now():%Y%m%d-%H%M%S}"
    output_dir.mkdir(parents=True, exist_ok=True)
    client = MathAgentClient(args.backend, timeout=120)
    client.login(args.username, args.password)
    rows = [search_case(client, number, library, query, intent) for number, (library, query, intent) in enumerate(MANUAL_CASES, 1)]
    worker_key = Path(args.worker_key_file).read_text(encoding="utf-8").strip()
    controls = [rerank_control(args.worker, worker_key, number, *control) for number, control in enumerate(PAIR_CONTROLS, 1)]
    write_json(output_dir / "queries.json", rows)
    write_json(output_dir / "reranker_pair_controls.json", controls)
    write_json(output_dir / "summary.json", {
        "caseCount": len(rows),
        "averageElapsedMs": round(sum(row["elapsedMs"] for row in rows) / len(rows), 2),
        "maximumElapsedMs": max(row["elapsedMs"] for row in rows),
        "rerankerControlWins": sum(bool(row["relevantWins"]) for row in controls),
        "rerankerControlCount": len(controls),
    })
    print(json.dumps({"outputDir": str(output_dir), "caseCount": len(rows), "rerankerControls": len(controls)}, ensure_ascii=False))


def search_case(client: MathAgentClient, number: int, library: str, query: str, intent: str) -> dict[str, Any]:
    response = client.get("/api/teacher/resources/search", params={"query": query, "limit": 5, "library": library})
    body = response.body if isinstance(response.body, dict) else {}
    hits = body.get("hits") if isinstance(body.get("hits"), list) else []
    top = hits[0] if hits else {}
    return {
        "number": number,
        "library": library,
        "query": query,
        "targetIntent": intent,
        "status": response.status,
        "elapsedMs": response.elapsed_ms,
        "queryId": body.get("queryId"),
        "hitCount": body.get("hitCount", 0),
        "topDocumentId": top.get("documentId"),
        "topTitle": top.get("documentTitle"),
        "topBlockId": top.get("blockId"),
        "topScore": top.get("score"),
        "topEvidence": (top.get("evidenceText") or top.get("snippet") or "")[:500],
        "topFive": [{"documentId": hit.get("documentId"), "title": hit.get("documentTitle"), "blockId": hit.get("blockId"), "score": hit.get("score")} for hit in hits],
    }


def rerank_control(worker_url: str, worker_key: str, number: int, query: str, relevant: str, irrelevant: str) -> dict[str, Any]:
    response = requests.post(
        worker_url.rstrip("/") + "/v1/rerank",
        headers={"Authorization": "Bearer " + worker_key},
        json={"query": query, "documents": [relevant, irrelevant]},
        timeout=90,
    )
    response.raise_for_status()
    body = response.json()
    scores = [float(item["score"]) for item in body["data"]]
    return {
        "number": number,
        "query": query,
        "relevantScore": scores[0],
        "irrelevantScore": scores[1],
        "relevantWins": scores[0] > scores[1],
        "model": body.get("model"),
    }


def write_json(path: Path, payload: Any) -> None:
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")


if __name__ == "__main__":
    main()
