"""Real multi-library RAG evaluation for teacher resources.

This script uses only public backend APIs: it registers real local files, waits for parser/vector completion, and
queries one explicitly selected logical library per case.  Rows are written immediately so a Windows restart never
turns a partially completed run into an untraceable success claim.
"""
from __future__ import annotations

import argparse
import json
import random
import sys
import time
from collections import defaultdict
from datetime import datetime
from pathlib import Path
from typing import Any

if __package__ in {None, ""}:
    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from benchmarks.deepseek_react_rag_eval import _wait_for_sync_job
from benchmarks.http_client import MathAgentClient
from benchmarks.metrics import compute_latency_summary


OUTPUT_ROOT = Path("output") / "benchmarks"
NEW_LIBRARY_TOPICS = (
    ("teacher_resource", "教师资料函数单调性", "函数单调性应先检查定义域和端点，再根据导数符号表判断增减。"),
    ("qq_bundle", "QQ专题空间向量", "空间向量建系时把底面放在xOy平面，高放在z轴，法向量用于线面角。"),
    ("feishu", "飞书课堂概率模型", "概率题先区分放回独立试验与不放回抽取，再选择二项或超几何模型。"),
    ("gaokao", "高考圆锥曲线切线", "椭圆切线最值题先选择有几何意义的参数，再建立面积表达式。"),
    ("mock_exam", "模拟卷数列递推", "数列中Sn与an转换使用an等于Sn减Sn减一，并单独检查首项。"),
)
NEW_CASES_PER_LIBRARY = 16
OLD_CASES_PER_DOCUMENT = 10
TOTAL_CASES = 100


def append_jsonl(path: Path, row: dict[str, Any]) -> None:
    with path.open("a", encoding="utf-8") as handle:
        handle.write(json.dumps(row, ensure_ascii=False) + "\n")


def main() -> None:
    parser = argparse.ArgumentParser(description="Run 100 real explicit-library RAG recall cases.")
    parser.add_argument("--backend", default="http://127.0.0.1:8080")
    parser.add_argument("--username", default="admin")
    parser.add_argument("--password", default="admin-123456")
    parser.add_argument("--output-dir", default="")
    args = parser.parse_args()
    output_dir = Path(args.output_dir) if args.output_dir else OUTPUT_ROOT / f"library-rag-{datetime.now():%Y%m%d-%H%M%S}"
    output_dir.mkdir(parents=True, exist_ok=True)
    client = MathAgentClient(args.backend, timeout=180)
    client.login(args.username, args.password)
    run_id = f"library-rag-{int(time.time())}-{random.randrange(1_000_000):06d}"
    stage_path = output_dir / "stages.jsonl"
    append_jsonl(stage_path, {"stage": "started", "runId": run_id})

    new_documents = register_new_library_documents(client, output_dir, run_id, stage_path)
    new_cases = cases_from_documents(client, new_documents, NEW_CASES_PER_LIBRARY, "new")
    old_documents = discover_old_library_documents(client, {row["documentId"] for row in new_documents})
    old_cases = cases_from_documents(client, old_documents, OLD_CASES_PER_DOCUMENT, "old")
    cases = balance_cases(new_cases, old_cases)
    if len(cases) != TOTAL_CASES:
        raise RuntimeError(f"Expected {TOTAL_CASES} cases but built {len(cases)} from real parsed blocks")
    (output_dir / "cases.json").write_text(json.dumps(cases, ensure_ascii=False, indent=2), encoding="utf-8")

    rows = []
    rows_path = output_dir / "query_rows.jsonl"
    for index, case in enumerate(cases, 1):
        row = execute_case(client, case, index)
        rows.append(row)
        append_jsonl(rows_path, row)
    metrics = summarize(rows, run_id, new_documents, old_documents)
    (output_dir / "metrics.json").write_text(json.dumps(metrics, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({"outputDir": str(output_dir), **metrics}, ensure_ascii=False, indent=2))


def register_new_library_documents(client: MathAgentClient, output_dir: Path, run_id: str, stage_path: Path) -> list[dict[str, Any]]:
    root = output_dir / "uploaded-libraries"
    documents = []
    for library, title, statement in NEW_LIBRARY_TOPICS:
        folder = root / library
        folder.mkdir(parents=True, exist_ok=True)
        (folder / "lesson.md").write_text(
            f"# {title}\n\n知识库：{library}\n\n{statement}\n\n课堂提醒：先说明判断依据，再进行计算。\n",
            encoding="utf-8",
        )
        # Titles are user-visible metadata and logical-library routing inputs; keep their spelling independent from
        # the internal underscore identifier so all generated runtime packs follow the established resolver format.
        library_title = {
            "teacher_resource": "runtime-teacher-resource-pack",
            "qq_bundle": "runtime-qq-bundle",
            "feishu": "runtime-feishu-method",
        }.get(library, f"runtime-{library}")
        body = {
            "sourceType": "local_path",
            "title": f"{library_title}-{run_id}",
            "localPath": str(folder.resolve()),
            "permissionScope": "TEACHER_PRIVATE" if library in {"qq_bundle", "mock_exam"} else "MATH_VIP",
            "feishuExportFormat": "md",
        }
        started = time.perf_counter()
        register = client.post("/api/teacher/resources", body)
        document_id = str((register.body or {}).get("documentId") or "") if isinstance(register.body, dict) else ""
        create = client.post(f"/api/teacher/resources/{document_id}/sync-jobs", {})
        job_id = str((create.body or {}).get("jobId") or "") if isinstance(create.body, dict) else ""
        client.post(f"/api/teacher/resources/{document_id}/sync-jobs/{job_id}/execute", {})
        final = _wait_for_sync_job(client, document_id, job_id, timeout_seconds=180)
        row = {"library": library, "documentId": document_id, "jobId": job_id, "status": final.get("status"),
               "phase": final.get("phase"), "elapsedMs": int(round((time.perf_counter() - started) * 1000))}
        append_jsonl(stage_path, {"stage": "uploaded", **row})
        if register.status != 200 or final.get("status") != "completed":
            raise RuntimeError(f"Upload/index failed for {library}: {row}")
        documents.append(row)
    return documents


def discover_old_library_documents(client: MathAgentClient, excluded: set[str]) -> list[dict[str, Any]]:
    resources = client.get("/api/teacher/resources").body
    if not isinstance(resources, list):
        return []
    selected = []
    for resource in resources:
        if resource.get("documentId") in excluded or resource.get("parseStatus") != "parsed" or resource.get("syncStatus") != "synced":
            continue
        library = str(resource.get("sourceType") or "").lower()
        if library in {"gaokao", "mock_exam", "feishu", "qq_bundle", "teacher_resource"}:
            selected.append({"library": library, "documentId": str(resource["documentId"]), "origin": "old"})
    return selected[:2]


def cases_from_documents(client: MathAgentClient, documents: list[dict[str, Any]], per_document: int, origin: str) -> list[dict[str, Any]]:
    cases = []
    for document in documents:
        blocks = client.get(f"/api/teacher/resources/{document['documentId']}/blocks").body
        if not isinstance(blocks, list) or not blocks:
            continue
        for index in range(per_document):
            block = blocks[index % len(blocks)]
            text = str(block.get("normalizedText") or block.get("rawText") or "").replace("\n", " ").strip()
            if not text:
                continue
            # A fragment from the persisted parsed block is a real source-grounded query, not a fabricated answer.
            fragment = text[: min(len(text), 72)]
            cases.append({"origin": origin, "library": document["library"], "documentId": document["documentId"],
                          "blockId": str(block.get("blockId") or ""), "query": fragment, "variant": index})
    return cases


def balance_cases(new_cases: list[dict[str, Any]], old_cases: list[dict[str, Any]]) -> list[dict[str, Any]]:
    desired_new = len(NEW_LIBRARY_TOPICS) * NEW_CASES_PER_LIBRARY
    selected = new_cases[:desired_new]
    selected.extend(old_cases[: TOTAL_CASES - len(selected)])
    if len(selected) < TOTAL_CASES:
        selected.extend(new_cases[len(selected): TOTAL_CASES])
    return selected[:TOTAL_CASES]


def execute_case(client: MathAgentClient, case: dict[str, Any], index: int) -> dict[str, Any]:
    result = client.get("/api/teacher/resources/search", params={"query": case["query"], "limit": 10, "library": case["library"]})
    body = result.body if isinstance(result.body, dict) else {}
    hits = body.get("hits") if isinstance(body.get("hits"), list) else []
    document_rank = rank(hits, "documentId", case["documentId"])
    block_rank = rank(hits, "blockId", case["blockId"])
    score, reason = quality_score(document_rank, block_rank)
    return {**case, "caseIndex": index, "status": result.status, "elapsedMs": result.elapsed_ms,
            "retrievalMode": body.get("retrievalMode", ""), "hitCount": body.get("hitCount", 0),
            "documentRank": document_rank, "blockRank": block_rank, "documentRecallAt1": within(document_rank, 1),
            "documentRecallAt3": within(document_rank, 3), "documentRecallAt5": within(document_rank, 5),
            "blockRecallAt1": within(block_rank, 1), "blockRecallAt3": within(block_rank, 3),
            "blockRecallAt5": within(block_rank, 5), "qualityScore": score, "qualityReason": reason,
            "hitDocumentIds": [hit.get("documentId") for hit in hits], "hitBlockIds": [hit.get("blockId") for hit in hits]}


def rank(hits: list[dict[str, Any]], field: str, expected: str) -> int | None:
    for position, hit in enumerate(hits, 1):
        if str(hit.get(field) or "") == expected:
            return position
    return None


def within(rank_value: int | None, cutoff: int) -> bool:
    return rank_value is not None and rank_value <= cutoff


def quality_score(document_rank: int | None, block_rank: int | None) -> tuple[int, str]:
    if within(block_rank, 1): return 100, "expected block ranked first"
    if within(document_rank, 1): return 85, "expected document ranked first"
    if within(block_rank, 3): return 75, "expected block ranked in top 3"
    if within(document_rank, 3): return 60, "expected document ranked in top 3"
    if within(block_rank, 5): return 45, "expected block ranked in top 5"
    if within(document_rank, 5): return 30, "expected document ranked in top 5"
    return 0, "expected source absent from top 10"


def summarize(rows: list[dict[str, Any]], run_id: str, new_documents: list[dict[str, Any]], old_documents: list[dict[str, Any]]) -> dict[str, Any]:
    groups: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for row in rows:
        groups[row["library"]].append(row)
    return {"runId": run_id, "caseCount": len(rows), "newDocuments": new_documents, "oldDocuments": old_documents,
            "overall": summary(rows), "byLibrary": {library: summary(group) for library, group in groups.items()},
            "byOrigin": {origin: summary([row for row in rows if row["origin"] == origin]) for origin in {row["origin"] for row in rows}}}


def summary(rows: list[dict[str, Any]]) -> dict[str, Any]:
    count = len(rows)
    rate = lambda key: sum(bool(row.get(key)) for row in rows) / count if count else 0.0
    return {"count": count, "successRate": sum(row["status"] == 200 for row in rows) / count if count else 0.0,
            "documentRecallAt1": rate("documentRecallAt1"), "documentRecallAt3": rate("documentRecallAt3"),
            "documentRecallAt5": rate("documentRecallAt5"), "blockRecallAt1": rate("blockRecallAt1"),
            "blockRecallAt3": rate("blockRecallAt3"), "blockRecallAt5": rate("blockRecallAt5"),
            "averageQualityScore": round(sum(row["qualityScore"] for row in rows) / count, 2) if count else 0.0,
            "latency": compute_latency_summary(row["elapsedMs"] for row in rows)}


if __name__ == "__main__":
    main()
