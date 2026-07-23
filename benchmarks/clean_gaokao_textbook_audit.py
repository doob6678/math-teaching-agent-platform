"""Evaluate the rebuilt clean gaokao library and the dedicated textbook retriever with real document/block truth."""
from __future__ import annotations

import argparse
import json
import sys
from datetime import datetime
from pathlib import Path
from typing import Any

if __package__ in {None, ""}:
    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from benchmarks.http_client import MathAgentClient


# Queries are reviewer-authored paraphrases. Expected IDs are real parsed blocks from the clean documents or textbook corpus.
CASES: tuple[dict[str, str], ...] = (
    {"library": "gaokao", "query": "抛物线焦点到给定直线距离的条件通常怎样用于确定方程？", "documentId": "2075803302047907842", "blockId": "2075803303549468674", "intent": "抛物线焦点与距离条件"},
    {"library": "gaokao", "query": "已知焦点距离约束后，怎样求抛物线的标准方程？", "documentId": "2075803302047907842", "blockId": "2075803303616577538", "intent": "求抛物线方程"},
    {"library": "gaokao", "query": "椭圆上顶点、右焦点和动直线相交这一类题先写哪些几何对象？", "documentId": "2075803355814690818", "blockId": "2075803359153356801", "intent": "椭圆焦点与动直线"},
    {"library": "gaokao", "query": "解析几何练习中已给椭圆条件，第一问怎样建立椭圆方程？", "documentId": "2075803355814690818", "blockId": "2075803359220465665", "intent": "椭圆方程"},
    {"library": "gaokao", "query": "斜率相同且两条直线有公共点，如何证明三个点共线？", "documentId": "2075803413868052481", "blockId": "2075803415700963330", "intent": "斜率法共线"},
    {"library": "gaokao", "query": "证明三点共线有哪些通用的解析几何方法？", "documentId": "2075803413868052481", "blockId": "2075803415436722177", "intent": "共线方法概览"},
    {"library": "gaokao", "query": "表达式形如 a 加 b 或 ab 时，怎样利用不等式处理最值？", "documentId": "2075804214581657602", "blockId": "2075804217198903297", "intent": "不等式最值"},
    {"library": "gaokao", "query": "三角不等式在高中数学最值问题中能怎样使用？", "documentId": "2075804214581657602", "blockId": "2075804217261817858", "intent": "三角不等式"},
    {"library": "gaokao", "query": "三点共线时，怎样通过向量内积简化模长计算？", "documentId": "2075804222605361154", "blockId": "2075804224689930242", "intent": "共线向量模长"},
    {"library": "gaokao", "query": "为什么共线向量夹角为零时可以直接处理内积？", "documentId": "2075804222605361154", "blockId": "2075804224757039106", "intent": "共线与内积"},
    {"library": "textbook", "query": "教材中等比数列首项和公比已知时，如何求指定项？", "documentId": "math_b_xuanze_bixiu_3", "blockId": "math_b_xuanze_bixiu_3_p039_ai_001", "intent": "等比数列求项"},
    {"library": "textbook", "query": "等额本息还款的每一期还款金额构成什么类型的数列？", "documentId": "math_b_xuanze_bixiu_3", "blockId": "math_b_xuanze_bixiu_3_p057_ai_001", "intent": "等额本息数列"},
    {"library": "textbook", "query": "分期还款时，第 n 年以前已经归还的本金总额怎样表示？", "documentId": "math_b_xuanze_bixiu_3", "blockId": "math_b_xuanze_bixiu_3_p053_ai_001", "intent": "还款本金总额"},
    {"library": "textbook", "query": "空间两条直线的方向向量如何用于判断位置关系？", "documentId": "math_b_xuanze_bixiu_1", "blockId": "math_b_xuanze_bixiu_1_p043_ai_001", "intent": "空间向量方向"},
    {"library": "textbook", "query": "空间向量教材中如何用方向向量描述直线 l1 和 l2？", "documentId": "math_b_xuanze_bixiu_1", "blockId": "math_b_xuanze_bixiu_1_p043_ai_001", "intent": "空间直线方向向量"},
)


def rank(hits: list[dict[str, Any]], field: str, expected: str) -> int | None:
    for position, hit in enumerate(hits, 1):
        if str(hit.get(field) or "") == expected:
            return position
    return None


def within(value: int | None, cutoff: int) -> bool:
    return value is not None and value <= cutoff


def main() -> None:
    parser = argparse.ArgumentParser(description="Run clean gaokao and textbook document/block retrieval audit.")
    parser.add_argument("--backend", default="http://127.0.0.1:8080")
    parser.add_argument("--username", default="admin")
    parser.add_argument("--password", default="admin-123456")
    parser.add_argument("--output-dir", default="")
    args = parser.parse_args()
    output_dir = Path(args.output_dir) if args.output_dir else Path("output/benchmarks") / f"clean-gaokao-textbook-audit-{datetime.now():%Y%m%d-%H%M%S}"
    output_dir.mkdir(parents=True, exist_ok=True)
    client = MathAgentClient(args.backend, timeout=120)
    client.login(args.username, args.password)
    rows = []
    for number, case in enumerate(CASES, 1):
        response = client.get("/api/teacher/resources/search", params={"query": case["query"], "limit": 5, "library": case["library"]})
        body = response.body if isinstance(response.body, dict) else {}
        hits = body.get("hits") if isinstance(body.get("hits"), list) else []
        document_rank = rank(hits, "documentId", case["documentId"])
        block_rank = rank(hits, "blockId", case["blockId"])
        top = hits[0] if hits else {}
        row = {
            **case,
            "number": number,
            "status": response.status,
            "elapsedMs": response.elapsed_ms,
            "queryId": body.get("queryId"),
            "documentRank": document_rank,
            "blockRank": block_rank,
            "documentAt1": within(document_rank, 1), "documentAt3": within(document_rank, 3), "documentAt5": within(document_rank, 5),
            "blockAt1": within(block_rank, 1), "blockAt3": within(block_rank, 3), "blockAt5": within(block_rank, 5),
            "topTitle": top.get("documentTitle"), "topBlockId": top.get("blockId"), "topEvidence": (top.get("evidenceText") or top.get("snippet") or "")[:500],
        }
        rows.append(row)
        with (output_dir / "rows.jsonl").open("a", encoding="utf-8") as handle:
            handle.write(json.dumps(row, ensure_ascii=False) + "\n")
    metrics = summarize(rows)
    (output_dir / "metrics.json").write_text(json.dumps(metrics, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({"outputDir": str(output_dir), **metrics}, ensure_ascii=False))


def summarize(rows: list[dict[str, Any]]) -> dict[str, Any]:
    groups: dict[str, list[dict[str, Any]]] = {}
    for row in rows:
        groups.setdefault(row["library"], []).append(row)
    return {"caseCount": len(rows), "overall": metric(rows), "byLibrary": {key: metric(value) for key, value in groups.items()}}


def metric(rows: list[dict[str, Any]]) -> dict[str, Any]:
    count = len(rows)
    rate = lambda field: sum(bool(row[field]) for row in rows) / count if count else 0.0
    ordered = sorted(row["elapsedMs"] for row in rows)
    return {
        "count": count, "documentAt1": rate("documentAt1"), "documentAt3": rate("documentAt3"), "documentAt5": rate("documentAt5"),
        "blockAt1": rate("blockAt1"), "blockAt3": rate("blockAt3"), "blockAt5": rate("blockAt5"),
        "averageElapsedMs": round(sum(row["elapsedMs"] for row in rows) / count, 2) if count else 0.0,
        "p95ElapsedMs": ordered[max(0, int((count * 0.95 + 0.999999)) - 1)] if count else 0,
    }


if __name__ == "__main__":
    main()
