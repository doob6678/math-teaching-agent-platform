from __future__ import annotations

import argparse
import json
import sys
import time
from collections import Counter
from pathlib import Path
from typing import Any

if __package__ in {None, ""}:
    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from benchmarks.deepseek_react_rag_eval import (
    _cleanup_runtime_resources,
    _first_rank,
    _judge_source_grounded_hit,
    _run_json_probes,
    _run_security_checks,
    _safe_runtime_ai,
    _safe_vector_snapshot,
    _register_topic_resource,
)
from benchmarks.http_client import MathAgentClient
from benchmarks.metrics import compute_latency_summary


DEFAULT_OUTPUT_ROOT = Path("output") / "benchmarks"
JUDGE_FILTER_MODES = {"scope+tag"}


DOCUMENT_SPECS = [
    {
        "id": "public-textbook-derivative",
        "scope": "PUBLIC_TEXTBOOK",
        "title": "runtime-public-textbook-derivative",
        "files": {
            "教材-导数参数讨论.md": """# 导数参数讨论

## 闭区间单调性
讲教材时要先把定义域和闭区间端点摆出来，再看导数零点与不可导点，不能把 f'(x)=0 当成结论本身。

## 参数分类入口
参数出现时先判断讨论区间会不会穿过端点，再决定符号表怎么列，这样学生不会把分段讨论写散。
""",
        },
        "directQueries": [
            "公共教材里如果讲导数参数讨论，为什么不能只盯着导数零点，还得顺手检查定义域和端点？",
            "想找教材里关于闭区间单调性的讲法，不是答案解析，而是先讲为什么要看端点。",
        ],
        "groundedCases": [
            {
                "query": "教材里闭区间单调性这一段，哪一块明确提醒不能把 f'(x)=0 当成最后结论？",
                "sourcePathContains": "教材-导数参数讨论",
                "sectionContains": "闭区间单调性",
                "expectedRole": "reference",
                "tags": ["导数参数讨论", "闭区间", "端点"],
            },
        ],
    },
    {
        "id": "qq-bundle-vector",
        "scope": "MATH_VIP",
        "title": "runtime-qq-bundle-vector",
        "files": {
            "专题讲解.md": """# 空间向量夹角专题

## 建系入口
四棱锥或长方体题先定原点和坐标轴，再看方向向量与法向量各自服务于哪个角。
""",
            "真题.md": """# 真题

## 题面
已知四棱锥 P-ABCD 中底面是矩形，PA 垂直底面，求直线 PB 与平面 PCD 所成角的正弦值。
""",
            "答案解析.md": """# 答案解析

## 点积转角
解析时先找方向向量，再补一个平面的法向量，用点积和模长把线面角转成向量夹角，避免一上来硬套投影长度。
""",
            "点评.txt": """专题点评：学生最容易把方向向量和法向量混着用，讲评时要强调谁用来表示直线，谁用来代表平面。""",
        },
        "directQueries": [
            "想找 QQ 专题包里那道空间向量线面角题的解析，不是只要题面，重点是为什么先找法向量。",
            "QQ 包里如果学生总把方向向量和法向量混着用，哪份讲评材料最适合拿来提醒？",
        ],
        "groundedCases": [
            {
                "query": "QQ 专题包里哪一块明确说线面角要先补法向量，再用点积和模长去转角？",
                "sourcePathContains": "答案解析",
                "sectionContains": "点积转角",
                "expectedRole": "analysis",
                "tags": ["空间向量", "法向量", "线面角"],
            },
            {
                "query": "如果只想找点评里提醒方向向量和法向量别混用的那句话，应该命中哪块？",
                "sourcePathContains": "点评",
                "sectionContains": "",
                "expectedRole": "analysis",
                "tags": ["方向向量", "法向量", "点评"],
            },
        ],
    },
    {
        "id": "feishu-method-probability",
        "scope": "TEACHER_PRIVATE",
        "title": "runtime-feishu-method-probability",
        "files": {
            "讲法模板.md": """# 二项分布与超几何分布讲法模板

## 先分模型
先追问抽取过程是否独立且可重复，再决定是二项分布还是超几何分布，不要先背公式名字。
""",
            "板书逻辑.md": """# 板书逻辑

## 三列板书
左列写情境，中列写是否放回与是否独立，右列才落到模型名称和期望方差。
""",
            "课堂提示.md": """# 课堂提示

## 易错提醒
学生一看到“至少几人答对”就想套二项分布，必须先看抽样是否不放回。
""",
        },
        "directQueries": [
            "飞书方法文档里有没有讲二项分布和超几何分布怎么先分模型的讲法模板？",
            "想找课堂提示，不是题目解析，重点提醒学生别一见到至少几人答对就直接套二项分布。",
        ],
        "groundedCases": [
            {
                "query": "方法文档里哪一块最适合拿来提醒“至少几人答对”也要先看是不是不放回？",
                "sourcePathContains": "课堂提示",
                "sectionContains": "易错提醒",
                "expectedRole": "tip",
                "tags": ["二项分布", "超几何", "不放回"],
            },
            {
                "query": "如果要做板书，哪一块明确把情境、是否放回、模型名称拆成三列？",
                "sourcePathContains": "板书逻辑",
                "sectionContains": "三列板书",
                "expectedRole": "boardwork",
                "tags": ["板书", "是否放回", "模型名称"],
            },
        ],
    },
    {
        "id": "gaokao-conic",
        "scope": "MATH_VIP",
        "title": "runtime-gaokao-conic",
        "files": {
            "2024高考真题.md": """# 2024 高考真题

## 椭圆切线题
已知椭圆上一点的切线与坐标轴围成三角形，求面积最小值。
""",
            "解析.md": """# 真题解析

## 变量怎么设
先把切点参数化，让变量有几何意义，再去写面积式，别一上来盯着斜率硬算。
""",
        },
        "directQueries": [
            "高考真题里椭圆切线面积最值这类题，想找解析里关于变量怎么设的入口。",
            "如果要讲圆锥曲线真题，不想从斜率硬算开始，应该找哪份解析材料？",
        ],
        "groundedCases": [
            {
                "query": "真题解析里哪一块明确说先把切点参数化，让变量先有几何意义？",
                "sourcePathContains": "解析",
                "sectionContains": "变量怎么设",
                "expectedRole": "analysis",
                "tags": ["椭圆", "切线", "参数化"],
            },
        ],
    },
    {
        "id": "mock-sequence",
        "scope": "TEACHER_PRIVATE",
        "title": "runtime-mock-sequence",
        "files": {
            "模拟题.md": """# 数列模拟题

## 题面
设数列 {a_n} 的前 n 项和为 S_n，已知 S_n=2a_n+n，求数列通项并讨论单调性。
""",
            "答案.md": """# 模拟题答案

## 先转化再回代
先用 a_n=S_n-S_{n-1} 把和式转成通项关系，再回代检查 n=1 是否单独成立。
""",
            "讲评.md": """# 讲评

## 易错点
学生常把 S_n 和 a_n 混写在同一行里，忘了 n=1 往往需要单独验算。
""",
        },
        "directQueries": [
            "模拟题里数列前 n 项和和通项混在一起时，讲评材料是怎么提醒先转化再回代的？",
            "如果学生总把 S_n 和 a_n 写混，想找讲评里专门提醒 n=1 单独验算的那一块。",
        ],
        "groundedCases": [
            {
                "query": "模拟题答案里哪一块明确说先用 a_n=S_n-S_{n-1} 做转化，再回代检查 n=1？",
                "sourcePathContains": "答案",
                "sectionContains": "先转化再回代",
                "expectedRole": "analysis",
                "tags": ["数列", "前n项和", "n=1"],
            },
            {
                "query": "讲评里哪一块专门提醒 S_n 和 a_n 容易混写，并且 n=1 要单独验算？",
                "sourcePathContains": "讲评",
                "sectionContains": "易错点",
                "expectedRole": "analysis",
                "tags": ["S_n", "a_n", "验算"],
            },
        ],
    },
]


JSON_CASES = [
    {"topicId": "json-1", "content": '{"topic":"导数参数讨论","idea":"先看定义域再看零点"}'},
    {"topicId": "json-2", "content": '前面带一句说明 {"topic":"空间向量","idea":"先找法向量再转角"}'},
    {"topicId": "json-3", "content": '{"topic":"数列","idea":"先转化再回代"'},
    {"topicId": "json-4", "content": '```json\n{"topic":"概率模型","idea":"先区分是否放回"}\n```'},
    {"topicId": "json-5", "content": 'topic=圆锥曲线; idea=切点参数化后再写面积式'},
]


def main() -> None:
    parser = argparse.ArgumentParser(description="Compare legacy and two-stage teacher-resource retrieval on runtime-authored data.")
    parser.add_argument("--config", default=".tmp/grounded-compare-6.json")
    parser.add_argument("--output-dir", default=str(DEFAULT_OUTPUT_ROOT / "deepseek-react-rag-two-stage-doc-block-compare-1"))
    parser.add_argument("--direct-limit", type=int, default=10)
    parser.add_argument("--grounded-limit", type=int, default=8)
    parser.add_argument("--strategies", default="legacy_block_hybrid,two_stage_doc_block")
    parser.add_argument("--request-delay-ms", type=int, default=300)
    parser.add_argument("--strategy-cooldown-ms", type=int, default=65000)
    args = parser.parse_args()

    config = json.loads(Path(args.config).read_text(encoding="utf-8"))
    output_dir = Path(args.output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)

    admin = MathAgentClient(config.get("backendBaseUrl", "http://127.0.0.1:8080"), timeout=180)
    admin.login(config.get("adminUsername", "admin"), config.get("adminPassword", "admin-123456"))

    cleanup_rows = _cleanup_runtime_resources(admin)
    dataset = _materialize_runtime_dataset(output_dir)
    resource_rows = [_register_topic_resource(admin, topic) for topic in dataset["topics"]]
    resource_by_topic = {row["topicId"]: row for row in resource_rows}
    direct_cases = dataset["directCases"][: max(0, args.direct_limit)]
    grounded_cases = _resolve_grounded_cases(admin, dataset["groundedSpecs"], resource_by_topic)[: max(0, args.grounded_limit)]
    strategies = [part.strip() for part in args.strategies.split(",") if part.strip()]

    direct_rows: list[dict[str, Any]] = []
    grounded_rows: list[dict[str, Any]] = []
    for strategy in strategies:
        direct_rows.extend(_run_direct_cases(admin, direct_cases, resource_by_topic, strategy, args.request_delay_ms))
        grounded_rows.extend(_run_grounded_cases(admin, grounded_cases, strategy, args.request_delay_ms))
        if strategy != strategies[-1] and args.strategy_cooldown_ms > 0:
            time.sleep(args.strategy_cooldown_ms / 1000)

    vector_status = admin.get("/api/vector-index/status")
    runtime = admin.get("/api/system/runtime")
    security = _run_security_checks(admin)
    json_rows = _run_json_probes(JSON_CASES)

    metrics = {
        "generatedAt": time.strftime("%Y-%m-%dT%H:%M:%S"),
        "reusedHistoricalRuns": {
            "groundedBaseline": "output/benchmarks/deepseek-react-rag-grounded-compare-6",
            "groundedDiversified": "output/benchmarks/deepseek-react-rag-grounded-compare-6-diversified",
            "fullChainLite": "output/benchmarks/deepseek-react-rag-full-chain-lite",
            "mcpDeepSeekOnly": "output/benchmarks/deepseek-react-rag-mcp-deepseek-only-v2",
        },
        "dataset": {
            "documentCount": len(dataset["topics"]),
            "directCaseCount": len(direct_cases),
            "groundedCaseCount": len(grounded_cases),
            "resourceRows": str(output_dir / "resource_rows.jsonl"),
            "cleanupRows": str(output_dir / "cleanup_rows.jsonl"),
            "directRows": str(output_dir / "direct_search_rows.jsonl"),
            "sourceGroundedRows": str(output_dir / "source_grounded_rows.jsonl"),
            "jsonRows": str(output_dir / "json_rows.jsonl"),
        },
        "resourceSummary": {
            "count": len(resource_rows),
            "completedRate": _rate(resource_rows, lambda row: row.get("finalStatus") == "completed"),
            "scopeBreakdown": dict(Counter(str(row.get("scope") or "") for row in resource_rows)),
        },
        "directTeacherSearch": _summarize_direct_rows(direct_rows),
        "sourceGrounded": _summarize_grounded_rows(grounded_rows),
        "localJson": {
            "count": len(json_rows),
            "jsonRepairSuccessRate": _rate(json_rows, lambda row: bool(row.get("parsed"))),
            "schemaLikeRate": _rate(json_rows, lambda row: bool(row.get("schemaLike"))),
        },
        "security": security,
        "vectorIndex": _safe_vector_snapshot(vector_status.body),
        "runtimeAi": _safe_runtime_ai(runtime.body),
    }
    _write_jsonl(output_dir / "resource_rows.jsonl", resource_rows)
    _write_jsonl(output_dir / "cleanup_rows.jsonl", cleanup_rows)
    _write_jsonl(output_dir / "direct_search_rows.jsonl", direct_rows)
    _write_jsonl(output_dir / "source_grounded_rows.jsonl", grounded_rows)
    _write_jsonl(output_dir / "json_rows.jsonl", json_rows)
    (output_dir / "two_stage_compare_metrics.json").write_text(json.dumps(metrics, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(metrics, ensure_ascii=False, indent=2))


def _materialize_runtime_dataset(output_dir: Path) -> dict[str, Any]:
    dataset_root = output_dir / "runtime-authored"
    dataset_root.mkdir(parents=True, exist_ok=True)
    topics: list[dict[str, Any]] = []
    direct_cases: list[dict[str, Any]] = []
    grounded_specs: list[dict[str, Any]] = []
    for index, spec in enumerate(DOCUMENT_SPECS, 1):
        topic_dir = dataset_root / f"{index:02d}-{spec['id']}"
        topic_dir.mkdir(parents=True, exist_ok=True)
        for file_name, content in spec["files"].items():
            (topic_dir / file_name).write_text(content, encoding="utf-8")
        topics.append({
            "id": spec["id"],
            "title": spec["title"],
            "scope": spec["scope"],
            "path": str(topic_dir.resolve()),
        })
        for query in spec["directQueries"]:
            direct_cases.append({
                "topicId": spec["id"],
                "query": query,
                "expectedScope": spec["scope"],
                "tags": _derive_tags(spec["title"], query),
            })
        for case in spec["groundedCases"]:
            grounded_specs.append({
                "topicId": spec["id"],
                "scope": spec["scope"],
                "query": case["query"],
                "sourcePathContains": case["sourcePathContains"],
                "sectionContains": case["sectionContains"],
                "expectedRole": case["expectedRole"],
                "tags": list(case["tags"]),
            })
    return {
        "topics": topics,
        "directCases": direct_cases,
        "groundedSpecs": grounded_specs,
    }


def _resolve_grounded_cases(
        client: MathAgentClient,
        grounded_specs: list[dict[str, Any]],
        resource_by_topic: dict[str, dict[str, Any]]) -> list[dict[str, Any]]:
    cases: list[dict[str, Any]] = []
    for spec in grounded_specs:
        resource = resource_by_topic.get(spec["topicId"]) or {}
        document_id = str(resource.get("documentId") or "")
        if not document_id:
            continue
        attempt = client.get(f"/api/teacher/resources/{document_id}/blocks")
        blocks = attempt.body if isinstance(attempt.body, list) else []
        matched = None
        for block in blocks:
            if not isinstance(block, dict):
                continue
            source_path = str(block.get("sourcePath") or "")
            section = str(block.get("section") or "")
            if spec["sourcePathContains"] and spec["sourcePathContains"] not in source_path:
                continue
            if spec["sectionContains"] and spec["sectionContains"] not in section:
                continue
            matched = block
            break
        if not matched:
            continue
        cases.append({
            "topicId": spec["topicId"],
            "scope": spec["scope"],
            "query": spec["query"],
            "documentId": document_id,
            "blockId": str(matched.get("blockId") or ""),
            "blockRole": str(matched.get("blockRole") or ""),
            "sourcePath": str(matched.get("sourcePath") or ""),
            "graphTags": _parse_graph_tags(matched),
            "text": str(matched.get("rawText") or matched.get("normalizedText") or ""),
            "derivedTags": list(spec["tags"]),
        })
    return cases


def _run_direct_cases(
        client: MathAgentClient,
        cases: list[dict[str, Any]],
        resource_by_topic: dict[str, dict[str, Any]],
        strategy: str,
        delay_ms: int) -> list[dict[str, Any]]:
    rows = []
    for index, case in enumerate(cases):
        params: dict[str, Any] = {
            "query": case["query"],
            "limit": 10,
            "strategy": strategy,
        }
        if index % 4 in {1, 3}:
            params["permissionScope"] = case["expectedScope"]
        if index % 4 in {2, 3}:
            params["tag"] = case["tags"]
        attempt = client.get("/api/teacher/resources/search", params=params)
        body = attempt.body if isinstance(attempt.body, dict) else {}
        hits = [hit for hit in (body.get("hits") or []) if isinstance(hit, dict)]
        expected_document_id = str((resource_by_topic.get(case["topicId"]) or {}).get("documentId") or "")
        hit_document_ids = [str(hit.get("documentId") or "") for hit in hits]
        expected_rank = _first_rank(hit_document_ids, expected_document_id)
        top_hit = hits[0] if hits else {}
        rows.append({
            "strategy": strategy,
            "topicId": case["topicId"],
            "query": case["query"],
            "status": attempt.status,
            "elapsedMs": attempt.elapsed_ms,
            "filterMode": _direct_filter_mode(index),
            "retrievalMode": body.get("retrievalMode", ""),
            "expectedDocumentId": expected_document_id,
            "expectedScope": case["expectedScope"],
            "hitDocumentIds": hit_document_ids,
            "expectedRank": expected_rank,
            "recallAt1": bool(expected_rank and expected_rank <= 1),
            "recallAt3": bool(expected_rank and expected_rank <= 3),
            "recallAt5": bool(expected_rank and expected_rank <= 5),
            "topHitScope": str(top_hit.get("permissionScope") or ""),
            "scopeHit": bool(top_hit) and str(top_hit.get("permissionScope") or "") == case["expectedScope"],
        })
        if delay_ms > 0:
            time.sleep(delay_ms / 1000)
    return rows


def _run_grounded_cases(
        client: MathAgentClient,
        cases: list[dict[str, Any]],
        strategy: str,
        delay_ms: int) -> list[dict[str, Any]]:
    rows = []
    for case in cases:
        for filter_mode, params in _grounded_filter_variants(case, strategy):
            attempt = client.get("/api/teacher/resources/search", params=params)
            body = attempt.body if isinstance(attempt.body, dict) else {}
            hits = [hit for hit in (body.get("hits") or []) if isinstance(hit, dict)]
            hit_document_ids = [str(hit.get("documentId") or "") for hit in hits]
            hit_block_ids = [str(hit.get("blockId") or "") for hit in hits]
            block_rank = _first_rank(hit_block_ids, case["blockId"])
            document_rank = _first_rank(hit_document_ids, case["documentId"])
            top_hit = hits[0] if hits else {}
            judge = {"score": None, "pass": None, "reason": "skipped"}
            if filter_mode in JUDGE_FILTER_MODES:
                evidence_snippets = [
                    str(hit.get("evidenceText") or hit.get("snippet") or "")
                    for hit in hits[:3]
                ]
                judge = _judge_source_grounded_hit(case, case["query"], evidence_snippets)
            top_graph_tags = [str(tag).strip() for tag in (top_hit.get("graphTags") or []) if str(tag).strip()]
            expected_graph_tags = [tag for tag in case["graphTags"] if tag]
            rows.append({
                "strategy": strategy,
                "topicId": case["topicId"],
                "scope": case["scope"],
                "query": case["query"],
                "status": attempt.status,
                "elapsedMs": attempt.elapsed_ms,
                "filterMode": filter_mode,
                "retrievalMode": body.get("retrievalMode", ""),
                "expectedDocumentId": case["documentId"],
                "expectedBlockId": case["blockId"],
                "expectedRole": case["blockRole"],
                "expectedScope": case["scope"],
                "expectedGraphTags": expected_graph_tags,
                "hitDocumentIds": hit_document_ids,
                "hitBlockIds": hit_block_ids,
                "documentRank": document_rank,
                "blockRank": block_rank,
                "documentRecallAt1": bool(document_rank and document_rank <= 1),
                "documentRecallAt3": bool(document_rank and document_rank <= 3),
                "documentRecallAt5": bool(document_rank and document_rank <= 5),
                "blockRecallAt1": bool(block_rank and block_rank <= 1),
                "blockRecallAt3": bool(block_rank and block_rank <= 3),
                "blockRecallAt5": bool(block_rank and block_rank <= 5),
                "topHitScope": str(top_hit.get("permissionScope") or ""),
                "topHitRole": str(top_hit.get("blockRole") or ""),
                "topHitGraphTags": top_graph_tags,
                "scopeHit": bool(top_hit) and str(top_hit.get("permissionScope") or "") == case["scope"],
                "roleHit": bool(top_hit) and str(top_hit.get("blockRole") or "") == case["blockRole"],
                "graphTagHit": bool(expected_graph_tags) and any(tag in top_graph_tags for tag in expected_graph_tags),
                "judgeScore": judge.get("score"),
                "judgePass": judge.get("pass") if judge.get("pass") is not None else None,
                "judgeReason": judge.get("reason", ""),
            })
            if delay_ms > 0:
                time.sleep(delay_ms / 1000)
    return rows


def _grounded_filter_variants(case: dict[str, Any], strategy: str) -> list[tuple[str, dict[str, Any]]]:
    base = {"query": case["query"], "limit": 10, "strategy": strategy}
    tags = case["derivedTags"]
    scope = case["scope"]
    variants = [("none", dict(base))]
    if scope:
        variants.append(("scope", {**base, "permissionScope": scope}))
    if tags:
        variants.append(("tag", {**base, "tag": tags}))
    if scope and tags:
        variants.append(("scope+tag", {**base, "permissionScope": scope, "tag": tags}))
    return variants


def _summarize_direct_rows(rows: list[dict[str, Any]]) -> dict[str, Any]:
    grouped = _group_by_strategy(rows)
    summary = {
        "count": len(rows),
        "byStrategy": {},
    }
    for strategy, group in grouped.items():
        successful = [row for row in group if row.get("status") == 200]
        summary["byStrategy"][strategy] = {
            "count": len(group),
            "documentRecallAt1": _rate(successful, lambda row: bool(row.get("recallAt1"))),
            "documentRecallAt3": _rate(successful, lambda row: bool(row.get("recallAt3"))),
            "documentRecallAt5": _rate(successful, lambda row: bool(row.get("recallAt5"))),
            "scopeHitRate": _rate(successful, lambda row: bool(row.get("scopeHit"))),
            "latency": compute_latency_summary(row.get("elapsedMs", 0) for row in successful),
            "retrievalModes": dict(Counter(str(row.get("retrievalMode") or "") for row in successful)),
        }
    return summary


def _summarize_grounded_rows(rows: list[dict[str, Any]]) -> dict[str, Any]:
    grouped = _group_by_strategy(rows)
    summary = {
        "count": len(rows),
        "byStrategy": {},
    }
    for strategy, group in grouped.items():
        successful = [row for row in group if row.get("status") == 200]
        graph_rows = [row for row in successful if row.get("expectedGraphTags")]
        judged_rows = [row for row in successful if row.get("judgePass") is not None]
        summary["byStrategy"][strategy] = {
            "count": len(group),
            "documentRecallAt1": _rate(successful, lambda row: bool(row.get("documentRecallAt1"))),
            "documentRecallAt3": _rate(successful, lambda row: bool(row.get("documentRecallAt3"))),
            "documentRecallAt5": _rate(successful, lambda row: bool(row.get("documentRecallAt5"))),
            "blockRecallAt1": _rate(successful, lambda row: bool(row.get("blockRecallAt1"))),
            "blockRecallAt3": _rate(successful, lambda row: bool(row.get("blockRecallAt3"))),
            "blockRecallAt5": _rate(successful, lambda row: bool(row.get("blockRecallAt5"))),
            "judgeSampleCount": len(judged_rows),
            "judgePassRate": _rate(judged_rows, lambda row: bool(row.get("judgePass"))),
            "avgJudgeScore": round(sum(float(row.get("judgeScore") or 0) for row in judged_rows) / len(judged_rows), 3)
            if judged_rows else 0.0,
            "scopeHitRate": _rate(successful, lambda row: bool(row.get("scopeHit"))),
            "roleHitRate": _rate(successful, lambda row: bool(row.get("roleHit"))),
            "graphTagHitRate": _rate(graph_rows, lambda row: bool(row.get("graphTagHit"))) if graph_rows else None,
            "latency": compute_latency_summary(row.get("elapsedMs", 0) for row in successful),
            "retrievalModes": dict(Counter(str(row.get("retrievalMode") or "") for row in successful)),
            "filterModeBreakdown": dict(Counter(str(row.get("filterMode") or "") for row in group)),
        }
    if "legacy_block_hybrid" in summary["byStrategy"] and "two_stage_doc_block" in summary["byStrategy"]:
        legacy = summary["byStrategy"]["legacy_block_hybrid"]
        upgraded = summary["byStrategy"]["two_stage_doc_block"]
        summary["deltaVsLegacy"] = {
            "documentRecallAt1": round(float(upgraded["documentRecallAt1"]) - float(legacy["documentRecallAt1"]), 4),
            "documentRecallAt3": round(float(upgraded["documentRecallAt3"]) - float(legacy["documentRecallAt3"]), 4),
            "documentRecallAt5": round(float(upgraded["documentRecallAt5"]) - float(legacy["documentRecallAt5"]), 4),
            "blockRecallAt1": round(float(upgraded["blockRecallAt1"]) - float(legacy["blockRecallAt1"]), 4),
            "blockRecallAt3": round(float(upgraded["blockRecallAt3"]) - float(legacy["blockRecallAt3"]), 4),
            "blockRecallAt5": round(float(upgraded["blockRecallAt5"]) - float(legacy["blockRecallAt5"]), 4),
            "judgePassRate": round(float(upgraded["judgePassRate"]) - float(legacy["judgePassRate"]), 4),
            "avgJudgeScore": round(float(upgraded["avgJudgeScore"]) - float(legacy["avgJudgeScore"]), 4),
            "scopeHitRate": round(float(upgraded["scopeHitRate"]) - float(legacy["scopeHitRate"]), 4),
            "roleHitRate": round(float(upgraded["roleHitRate"]) - float(legacy["roleHitRate"]), 4),
        }
    return summary


def _derive_tags(title: str, query: str) -> list[str]:
    parts = [piece.strip() for piece in (title + " " + query).replace("，", " ").replace("？", " ").split() if piece.strip()]
    tags: list[str] = []
    for part in parts:
        compact = part.strip("。、：:,.!?！？")
        if compact and compact not in tags and len(tags) < 8:
            tags.append(compact)
    return tags


def _parse_graph_tags(block: dict[str, Any]) -> list[str]:
    value = block.get("graphTagNamesJson") or block.get("graphTags") or []
    if isinstance(value, list):
        return [str(item).strip() for item in value if str(item).strip()]
    if isinstance(value, str) and value.strip().startswith("["):
        try:
            parsed = json.loads(value)
            if isinstance(parsed, list):
                return [str(item).strip() for item in parsed if str(item).strip()]
        except json.JSONDecodeError:
            return []
    return []


def _direct_filter_mode(index: int) -> str:
    return {0: "none", 1: "scope", 2: "tag", 3: "scope+tag"}[index % 4]


def _group_by_strategy(rows: list[dict[str, Any]]) -> dict[str, list[dict[str, Any]]]:
    grouped: dict[str, list[dict[str, Any]]] = {}
    for row in rows:
        grouped.setdefault(str(row.get("strategy") or ""), []).append(row)
    return grouped


def _rate(rows: list[dict[str, Any]], predicate) -> float:
    if not rows:
        return 0.0
    return sum(1 for row in rows if predicate(row)) / len(rows)


def _write_jsonl(path: Path, rows: list[dict[str, Any]]) -> None:
    path.write_text(
        "\n".join(json.dumps(row, ensure_ascii=False) for row in rows) + ("\n" if rows else ""),
        encoding="utf-8",
    )


if __name__ == "__main__":
    main()
