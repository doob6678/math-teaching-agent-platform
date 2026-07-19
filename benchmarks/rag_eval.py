from __future__ import annotations

import argparse
import json
import sys
import time
from pathlib import Path
from typing import Any

if __package__ in {None, ""}:
    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from benchmarks.build_math_eval_set import build_eval_set, write_eval_set
from benchmarks.http_client import MathAgentClient
from benchmarks.metrics import compute_latency_summary, compute_recall_summary


def run_rag_eval(client: MathAgentClient, config: dict[str, Any], limit: int, output_dir: Path) -> dict[str, Any]:
    """Run real RAG searches through backend HTTP APIs and return metrics."""
    strict_limit = min(limit, int(config.get("strictRagLimit", min(50, limit)) or 0))
    strict_cases, teacher_snapshot = _build_teacher_strict_cases(client, config, strict_limit)
    fill_cases = build_eval_set(config, max(0, limit - len(strict_cases)))
    cases = (strict_cases + fill_cases)[:limit]
    request_delay_ms = int(config.get("ragRequestDelayMs", 0) or 0)
    write_eval_set(cases, output_dir / "rag_eval_set.jsonl")
    textbook_rows = []
    teacher_rows = []
    latencies = []
    for case in cases:
        query = case["query"]
        textbook = client.get("/api/retrieval/textbooks/search", params={"query": query, "limit": 10})
        teacher = client.get("/api/teacher/resources/search", params={"query": query, "limit": 10})
        latencies.extend([textbook.elapsed_ms, teacher.elapsed_ms])
        textbook_rows.append(_search_row(case, textbook, "textbook"))
        teacher_rows.append(_search_row(case, teacher, "teacherResource"))
        if request_delay_ms > 0:
            time.sleep(request_delay_ms / 1000)
    vector_status = client.get("/api/vector-index/status")
    resources = client.get("/api/teacher/resources")
    metrics = {
        "sampleCount": len(cases),
        "textbook": _recall_from_rows(textbook_rows),
        "teacherResource": _recall_from_rows(teacher_rows),
        "httpStatus": _http_status_summary(textbook_rows + teacher_rows),
        "latency": compute_latency_summary(latencies),
        "retrievalModes": _retrieval_modes(teacher_rows),
        "milvus": _milvus_snapshot(vector_status),
        "teacherResourceCount": len(resources.body) if isinstance(resources.body, list) else 0,
        "teacherParsedBlockCount": teacher_snapshot["parsedBlockCount"],
        "teacherStrictCaseCount": len(strict_cases),
        "localMathCaseCount": sum(1 for case in cases if str(case.get("sourceType", "")).startswith("local")),
        "teacherBlockCount": _teacher_block_count(teacher_rows),
        "rawOutputFiles": {
            "evalSet": str(output_dir / "rag_eval_set.jsonl"),
            "textbookRows": str(output_dir / "rag_textbook_rows.jsonl"),
            "teacherRows": str(output_dir / "rag_teacher_rows.jsonl"),
            "teacherStrictCases": str(output_dir / "rag_teacher_strict_cases.jsonl"),
        },
    }
    _write_jsonl(output_dir / "rag_textbook_rows.jsonl", textbook_rows)
    _write_jsonl(output_dir / "rag_teacher_rows.jsonl", teacher_rows)
    _write_jsonl(output_dir / "rag_teacher_strict_cases.jsonl", strict_cases)
    return metrics


def _http_status_summary(rows: list[dict[str, Any]]) -> dict[str, Any]:
    counts: dict[str, int] = {}
    for row in rows:
        key = str(row.get("status", "unknown"))
        counts[key] = counts.get(key, 0) + 1
    return {
        "counts": counts,
        "successfulRequestCount": sum(1 for row in rows if int(row.get("status", 0) or 0) == 200),
        "rateLimitedCount": sum(1 for row in rows if int(row.get("status", 0) or 0) == 429),
    }


def _search_row(case: dict[str, Any], attempt, channel: str) -> dict[str, Any]:
    body = attempt.body if isinstance(attempt.body, dict) else {}
    return {
        "caseId": case["id"],
        "query": case["query"],
        "sourceType": case.get("sourceType", ""),
        "expectedDocumentId": case.get("expectedDocumentId", ""),
        "expectedBlockId": case.get("expectedBlockId", ""),
        "channel": channel,
        "status": attempt.status,
        "elapsedMs": attempt.elapsed_ms,
        "queryId": body.get("queryId", ""),
        "retrievalMode": body.get("retrievalMode") or body.get("retrievalStrategy") or "",
        "hitCount": body.get("hitCount") or body.get("total") or 0,
        "hits": list(body.get("hits") or []),
    }


def _recall_from_rows(rows: list[dict[str, Any]]) -> dict[str, Any]:
    # Source-file questions rarely have exact backend ids. We calculate strict recall only when ids exist,
    # and separately report evidenceHitRate from non-empty real backend hits.
    strict_rows = [
        row for row in rows
        if row.get("expectedDocumentId") or row.get("expectedBlockId") or row.get("expectedChunkId")
    ]
    strict = compute_recall_summary(strict_rows, cutoffs=(1, 3, 5, 10))
    strict["totalSampleCount"] = len(rows)
    hit_rows = sum(1 for row in rows if int(row.get("hitCount", 0) or 0) > 0)
    strict["evidenceHitRate"] = hit_rows / len(rows) if rows else 0.0
    strict["evidenceHitCount"] = hit_rows
    return strict


def _build_teacher_strict_cases(
        client: MathAgentClient,
        config: dict[str, Any],
        limit: int) -> tuple[list[dict[str, Any]], dict[str, int]]:
    """Build strict Recall cases from real parsed teacher blocks exposed by backend visibility checks."""
    cases: list[dict[str, Any]] = []
    parsed_block_count = 0
    source_client = client
    resources = source_client.get("/api/teacher/resources")
    if isinstance(resources.body, list) and not resources.body and config.get("adminUsername") and config.get("adminPassword"):
        # Some local stacks seed shared resources under admin ownership. Falling back to admin here keeps the
        # benchmark tied to real backend visibility rules while avoiding invented block ids.
        source_client = MathAgentClient(client.base_url, timeout=client.timeout)
        source_client.login(str(config["adminUsername"]), str(config["adminPassword"]))
        resources = source_client.get("/api/teacher/resources")
    if not isinstance(resources.body, list):
        return cases, {"parsedBlockCount": parsed_block_count}
    seen_queries: set[str] = set()
    for resource in resources.body:
        if not isinstance(resource, dict):
            continue
        if not _is_strict_math_resource(resource, config):
            continue
        document_id = str(resource.get("documentId") or "")
        if not document_id:
            continue
        blocks = source_client.get(f"/api/teacher/resources/{document_id}/blocks")
        if blocks.status != 200 or not isinstance(blocks.body, list):
            continue
        parsed_block_count += len(blocks.body)
        for block in blocks.body:
            if not isinstance(block, dict):
                continue
            block_id = str(block.get("blockId") or "")
            query = _query_from_block(block)
            if not block_id or not query or query in seen_queries:
                continue
            seen_queries.add(query)
            cases.append({
                "id": f"teacher-block-{len(cases) + 1}",
                "query": query,
                "sourceType": "teacherStrictBlock",
                "expectedDocumentId": document_id,
                "expectedBlockId": block_id,
                "documentTitle": resource.get("title", ""),
                "blockOrder": block.get("blockOrder", 0),
            })
            if len(cases) >= limit:
                return cases, {"parsedBlockCount": parsed_block_count}
    return cases, {"parsedBlockCount": parsed_block_count}


def _is_strict_math_resource(resource: dict[str, Any], config: dict[str, Any]) -> bool:
    hints = [str(value).lower() for value in config.get("strictTeacherResourceTitleHints", [])]
    if not hints:
        hints = ["数学", "math", "space-vector"]
    haystack = " ".join(str(resource.get(field) or "") for field in ("title", "localPath", "originalUrl")).lower()
    return any(hint and hint in haystack for hint in hints)


def _query_from_block(block: dict[str, Any]) -> str:
    text = str(block.get("normalizedText") or block.get("rawText") or "")
    text = " ".join(text.split())
    if len(text) < 18:
        return ""
    # Prefer a compact exact substring; it makes strict Recall measurable without inventing labels.
    if len(text) <= 120:
        return text
    start = 0
    for marker in ("解", "证明", "函数", "向量", "数列", "概率", "圆", "椭圆"):
        index = text.find(marker)
        if 0 <= index < len(text) - 18:
            start = max(0, index - 8)
            break
    return text[start:start + 120]


def _retrieval_modes(rows: list[dict[str, Any]]) -> dict[str, int]:
    modes: dict[str, int] = {}
    for row in rows:
        mode = str(row.get("retrievalMode") or "unknown")
        modes[mode] = modes.get(mode, 0) + 1
    return modes


def _milvus_snapshot(attempt) -> dict[str, Any]:
    if not isinstance(attempt.body, dict):
        return {"status": "unavailable", "rowCount": 0, "httpStatus": attempt.status}
    return {
        "enabled": attempt.body.get("enabled"),
        "configured": attempt.body.get("configured"),
        "collectionName": attempt.body.get("collectionName", ""),
        "dimension": attempt.body.get("dimension", 0),
        "rowCount": attempt.body.get("rowCount", 0),
        "status": attempt.body.get("status", ""),
        "indexState": attempt.body.get("indexState", ""),
        "loadState": attempt.body.get("loadState", ""),
    }


def _teacher_block_count(rows: list[dict[str, Any]]) -> int:
    block_ids = set()
    for row in rows:
        for hit in row.get("hits") or []:
            block_id = hit.get("blockId")
            if block_id:
                block_ids.add(block_id)
    return len(block_ids)


def _write_jsonl(path: Path, rows: list[dict[str, Any]]) -> None:
    path.write_text(
        "\n".join(json.dumps(row, ensure_ascii=False) for row in rows) + ("\n" if rows else ""),
        encoding="utf-8",
    )


def main() -> None:
    parser = argparse.ArgumentParser(description="Run real MathAgent RAG benchmark through backend HTTP APIs.")
    parser.add_argument("--config", default="benchmarks/config.example.json")
    parser.add_argument("--output-dir", default="output/benchmarks/manual-rag")
    parser.add_argument("--limit", type=int, default=50)
    parser.add_argument("--request-delay-ms", type=int, default=-1)
    args = parser.parse_args()
    config = json.loads(Path(args.config).read_text(encoding="utf-8"))
    if args.request_delay_ms >= 0:
        config["ragRequestDelayMs"] = args.request_delay_ms
    output_dir = Path(args.output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)
    client = MathAgentClient(config.get("backendBaseUrl", "http://127.0.0.1:8080"))
    client.login(config.get("teacherUsername", "teacher"), config.get("teacherPassword", "teacher-123456"))
    metrics = run_rag_eval(client, config, args.limit, output_dir)
    (output_dir / "rag_metrics.json").write_text(json.dumps(metrics, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(metrics, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
