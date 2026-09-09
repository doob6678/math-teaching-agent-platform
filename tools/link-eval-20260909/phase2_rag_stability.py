"""阶段2A：教师资源检索 RAG 稳定性实测（真实后端 HTTP，带 ground-truth doc@k）。

复用仓库既有 MathAgentClient（sa-token cookie 会话）与 benchmarks/datasets 真实人工标注数据集。
设计：
- 取 positive 且带 expected_document_id/expected_block_id 的真实题干查询 ≥20 条（这里 24 条）。
- 连续 3 轮重复同一查询集，逐条记录 HTTP 状态、端到端延迟、doc@3/doc@5/block@3/block@5 命中。
- 聚合：每轮 doc@k、延迟 p50/p95/max、错误率；跨轮命中率方差与逐查询命中的确定性一致率。
输出 jsonl + 汇总 json 到 output/link-eval-20260909/。只读，不改任何服务。
"""
from __future__ import annotations

import json
import statistics
import sys
from datetime import datetime
from pathlib import Path

REPO = Path(r"C:\Users\doob\Desktop\code\dev\math_agent_rag")
sys.path.insert(0, str(REPO))

from benchmarks.http_client import MathAgentClient  # noqa: E402
from benchmarks.metrics import compute_latency_summary  # noqa: E402

CONFIG = json.loads((REPO / "benchmarks" / "config.json").read_text(encoding="utf-8"))
DATASET = REPO / "benchmarks" / "datasets" / "teacher_math_manual_annotated_20260830.json"
OUT_DIR = REPO / "output" / "link-eval-20260909"
ROUNDS = 3
LIMIT = 5
N_CASES = 24
MAX_RETRIES = 1  # 压低客户端自动重试，让真实错误/429 暴露到样本中如实计入错误率
ROUND_GAP_S = 5  # 轮间隔，模拟真实调用节奏并给后端/worker 冷却


def pick_cases():
    data = json.loads(DATASET.read_text(encoding="utf-8"))
    cases = []
    seen = set()
    for c in data.get("cases", []):
        if c.get("case_type") != "positive":
            continue
        doc = str(c.get("expected_document_id") or "")
        block = str(c.get("expected_block_id") or "")
        lib = str(c.get("expected_library") or c.get("requested_library") or "")
        q = (c.get("query") or "").strip()
        # 取真实题干查询；按 expected_document_id 去重保证覆盖不同来源文档（同库内多文档），而非按库去重。
        if not doc or not q or doc in seen:
            continue
        seen.add(doc)
        cases.append({"case_id": c.get("case_id"), "query": q, "expected_document_id": doc,
                      "expected_block_id": block, "library": lib})
        if len(cases) >= N_CASES:
            break
    return cases


def doc_rank(hits, expected_doc):
    for pos, h in enumerate(hits, 1):
        fid = str(h.get("fileDocumentId") or h.get("documentId") or "")
        rid = str(h.get("rootDocumentId") or h.get("documentId") or "")
        did = str(h.get("documentId") or "")
        if expected_doc in (fid, rid, did):
            return pos
    return None


def block_rank(hits, expected_block, expected_doc):
    eb = str(expected_block)
    for pos, h in enumerate(hits, 1):
        if str(h.get("blockId") or "") == eb:
            return pos
        win = h.get("evidenceBlockIds") or []
        if isinstance(win, list) and eb in [str(x) for x in win]:
            fid = str(h.get("fileDocumentId") or h.get("documentId") or "")
            if fid == expected_doc:
                return pos
    return None


def main():
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    ts = datetime.now().strftime("%Y%m%d-%H%M%S")
    cases = pick_cases()
    assert len(cases) >= 20, f"仅取到 {len(cases)} 条真实查询，不足 20"
    client = MathAgentClient(CONFIG["backendBaseUrl"], timeout=120, max_retries=MAX_RETRIES)
    client.login(CONFIG["teacherUsername"], CONFIG["teacherPassword"])

    rows = []
    rows_path = OUT_DIR / f"phase2a_rag_rows_{ts}.jsonl"
    for rnd in range(1, ROUNDS + 1):
        for case in cases:
            attempt = client.get("/api/teacher/resources/search",
                                 params={"query": case["query"], "limit": LIMIT, "library": case["library"]})
            body = attempt.body if isinstance(attempt.body, dict) else {}
            hits = [h for h in (body.get("hits") or []) if isinstance(h, dict)]
            dr = doc_rank(hits, case["expected_document_id"])
            br = block_rank(hits, case["expected_block_id"], case["expected_document_id"])
            row = {"round": rnd, "case_id": case["case_id"], "library": case["library"],
                   "status": attempt.status, "ok": attempt.status == 200,
                   "elapsed_ms": attempt.elapsed_ms, "retry_count": attempt.retry_count,
                   "rate_429": attempt.rate_limit_429_count, "hit_count": len(hits),
                   "doc_rank": dr, "block_rank": br,
                   "doc@3": dr is not None and dr <= 3, "doc@5": dr is not None and dr <= 5,
                   "block@3": br is not None and br <= 3, "block@5": br is not None and br <= 5}
            rows.append(row)
            with rows_path.open("a", encoding="utf-8") as fh:
                fh.write(json.dumps(row, ensure_ascii=False) + "\n")
            tag = "OK " if row["ok"] and row["doc@3"] else ("ERR" if not row["ok"] else "MISS")
            print(f"R{rnd} {case['case_id']} [{tag}] {attempt.status} {attempt.elapsed_ms}ms doc@{dr}", flush=True)
        if rnd < ROUNDS:
            import time
            time.sleep(ROUND_GAP_S)

    # 汇总
    def rate(rs, k):
        ok = [r for r in rs if r["ok"]]
        return sum(1 for r in ok if r[k]) / len(ok) if ok else 0.0

    per_round = {}
    for rnd in range(1, ROUNDS + 1):
        rs = [r for r in rows if r["round"] == rnd]
        per_round[f"round{rnd}"] = {
            "count": len(rs),
            "errorCount": sum(1 for r in rs if not r["ok"]),
            "errorRate": round(sum(1 for r in rs if not r["ok"]) / len(rs), 4),
            "doc@3": round(rate(rs, "doc@3"), 4),
            "doc@5": round(rate(rs, "doc@5"), 4),
            "block@3": round(rate(rs, "block@3"), 4),
            "block@5": round(rate(rs, "block@5"), 4),
            "latencyMs": compute_latency_summary(r["elapsed_ms"] for r in rs),
            "p50Ms": compute_latency_summary(r["elapsed_ms"] for r in rs).get("avgMs"),
            "totalRetries": sum(r["retry_count"] for r in rs),
            "total429": sum(r["rate_429"] for r in rs),
        }

    # 跨轮 doc@3 命中率方差（按轮聚合值）
    doc3_vals = [per_round[f"round{r}"]["doc@3"] for r in range(1, ROUNDS + 1)]
    doc5_vals = [per_round[f"round{r}"]["doc@5"] for r in range(1, ROUNDS + 1)]
    # 逐查询跨轮一致性：同一 case 三轮 doc@3 结果是否完全相同
    agree3 = agree5 = total_q = 0
    for case in cases:
        r3 = [r["doc@3"] for r in rows if r["case_id"] == case["case_id"]]
        r5 = [r["doc@5"] for r in rows if r["case_id"] == case["case_id"]]
        if len(r3) == ROUNDS:
            total_q += 1
            if len(set(r3)) == 1:
                agree3 += 1
            if len(set(r5)) == 1:
                agree5 += 1

    summary = {
        "generatedAt": datetime.now().isoformat(timespec="seconds"),
        "target": "GET /api/teacher/resources/search (full RAG chain: BGE embed + Milvus + GPU rerank)",
        "dataset": str(DATASET.name),
        "cases": len(cases), "rounds": ROUNDS, "limit": LIMIT,
        "perRound": per_round,
        "crossRound": {
            "doc@3_values": doc3_vals,
            "doc@3_mean": round(statistics.mean(doc3_vals), 4),
            "doc@3_stdev": round(statistics.pstdev(doc3_vals), 4) if len(doc3_vals) > 1 else 0.0,
            "doc@5_values": doc5_vals,
            "doc@5_stdev": round(statistics.pstdev(doc5_vals), 4) if len(doc5_vals) > 1 else 0.0,
            "perQueryDoc3AgreementAcrossRounds": f"{agree3}/{total_q}",
            "perQueryDoc5AgreementAcrossRounds": f"{agree5}/{total_q}",
        },
        "overallLatencyMs": compute_latency_summary(r["elapsed_ms"] for r in rows),
        "overallErrorCount": sum(1 for r in rows if not r["ok"]),
        "gate": {"doc@3>=0.80": rate(rows, "doc@3") >= 0.80, "block@3>=0.60": rate(rows, "block@3") >= 0.60},
    }
    out_json = OUT_DIR / f"phase2a_rag_summary_{ts}.json"
    out_json.write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    print("\n===== PHASE2A SUMMARY =====")
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    print("SUMMARY_JSON=" + str(out_json))


if __name__ == "__main__":
    main()
