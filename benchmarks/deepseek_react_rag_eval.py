from __future__ import annotations

import argparse
import json
import os
import random
import re
import sys
import time
from collections import Counter
from datetime import datetime
from pathlib import Path
from typing import Any

import requests

if __package__ in {None, ""}:
    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from benchmarks.http_client import MathAgentClient, stable_request_hash
from benchmarks.metrics import compute_latency_summary


MCP_SECRET_FILE = Path(".local-secrets/mcp-secret.txt")
DEFAULT_OUTPUT_ROOT = Path("output") / "benchmarks"
BENCHMARK_LLM_PROVIDER = (
    os.environ.get("BENCHMARK_LLM_PROVIDER", "").strip().lower()
    or ("openai" if os.environ.get("OPENAI_API_KEY") else "deepseek")
)
BENCHMARK_LLM_API_URL = (
    (os.environ.get("OPENAI_BASE_URL", "https://api.openai.com/v1") if BENCHMARK_LLM_PROVIDER == "openai"
     else os.environ.get("DEEPSEEK_BASE_URL", "https://api.deepseek.com")).rstrip("/")
    + "/chat/completions"
)
BENCHMARK_LLM_MODEL = (
    os.environ.get("OPENAI_CHAT_MODEL", "gpt-5.4")
    if BENCHMARK_LLM_PROVIDER == "openai"
    else os.environ.get("DEEPSEEK_CHAT_MODEL", "deepseek-v4-flash")
)


TOPIC_BANK = [
    {
        "id": "derivative",
        "title": "导数参数讨论",
        "scope": "MATH_VIP",
        "knowledge_label": "导数与单调性",
        "notes": [
            "研究闭区间单调性时，要先定定义域，再看导数零点、端点与不可导点。",
            "若题目含参数，讨论重点不是机械解方程，而是看导函数符号变化会不会穿过区间边缘。",
            "学生最容易犯的错是把 f'(x)=0 当成结论本身，没有回到原函数的增减变化去解释。",
        ],
        "question": "一个三次函数带参数，老师想讲为什么不能只看导数零点，还得检查端点和定义域。",
        "goal": "说明导数分类讨论、闭区间端点和符号表的关系。",
    },
    {
        "id": "vector",
        "title": "空间向量建系",
        "scope": "TEACHER_PRIVATE",
        "knowledge_label": "空间向量与线面角",
        "notes": [
            "矩形底面加垂直高的立体题，常把底面放在 xOy 平面，把高落到 z 轴。",
            "线面角问题常先找方向向量，再借法向量把角度转换成数量关系。",
            "课堂上要提醒学生：建系是为了把关系算清，不是为了堆坐标。",
        ],
        "question": "四棱锥里学生知道列坐标，但不知道为什么这样选原点和坐标轴。",
        "goal": "讲清空间向量建系理由、法向量作用和线面角入口。",
    },
    {
        "id": "conic",
        "title": "圆锥曲线切线最值",
        "scope": "MATH_VIP",
        "knowledge_label": "切线与面积最值",
        "notes": [
            "椭圆切线与坐标轴围面积时，变量选择比联立展开更关键。",
            "若题目同时出现切线、焦点、面积最小值，先找几何上好解释的参数往往更稳。",
            "学生容易一开始就硬算斜率，忽略变量本身应当有清晰的几何含义。",
        ],
        "question": "椭圆上一点的切线围成三角形，老师想先讲变量该怎么设，再谈最值。",
        "goal": "说明切线参数、面积表达式和方法判断。",
    },
    {
        "id": "sequence",
        "title": "数列递推拆解",
        "scope": "TEACHER_PRIVATE",
        "knowledge_label": "递推与前n项和",
        "notes": [
            "S_n 和 a_n 混在一起时，常先用 a_n=S_n-S_{n-1} 做转换。",
            "递推从第二项开始时，n=1 常常需要单独验证，不能偷并到统一公式里。",
            "讲评时要把‘先转化，再累加，再回代’这种节奏说清楚。",
        ],
        "question": "学生一看见前 n 项和和通项混在一起就乱了，老师想做一页方法讲义。",
        "goal": "说明从和式到通项、从递推到累加的过渡。",
    },
    {
        "id": "probability",
        "title": "概率模型识别",
        "scope": "MATH_VIP",
        "knowledge_label": "二项与超几何",
        "notes": [
            "看到正确率、抽几个人、至少几人答对时，先分辨是不是独立重复试验。",
            "不放回抽取更像超几何分布，放回或近似独立时才考虑二项分布。",
            "统计量解释不能停在公式上，要说清均值看水平、方差看波动。",
        ],
        "question": "老师想让学生区分二项分布和超几何分布，不希望他们只会套词。",
        "goal": "说明概率模型识别与统计量口语化解释。",
    },
]


def run_eval(config: dict[str, Any], output_dir: Path) -> dict[str, Any]:
    output_dir.mkdir(parents=True, exist_ok=True)
    admin = MathAgentClient(config.get("backendBaseUrl", "http://127.0.0.1:8080"), timeout=180)
    admin.login(config.get("adminUsername", "admin"), config.get("adminPassword", "admin-123456"))
    run_id = f"deepseek-react-{datetime.now().strftime('%Y%m%d-%H%M%S')}-{random.randrange(1_000_000):06d}"
    stage_rows_path = output_dir / "stage_rows.jsonl"
    resource_rows: list[dict[str, Any]] = []
    try:
        runtime_dataset = _materialize_runtime_dataset(output_dir, run_id)
        _append_jsonl(stage_rows_path, {"stage": "dataset_materialized", "runId": run_id, "elapsedMs": 0})
        for topic in runtime_dataset["topics"]:
            started = time.perf_counter()
            row = _register_topic_resource(admin, topic)
            row["elapsedMs"] = int(round((time.perf_counter() - started) * 1000))
            row["runId"] = run_id
            resource_rows.append(row)
            _append_jsonl(stage_rows_path, {"stage": "resource_ingested", **row})
        _write_jsonl(output_dir / "resource_rows.jsonl", resource_rows)

        expected_doc_by_topic = {row["topicId"]: row["documentId"] for row in resource_rows if row.get("documentId")}
        explanation_sample_size = _sample_size(config, "deepseekExplanationSampleSize", 3)
        mcp_sample_size = _sample_size(config, "deepseekMcpSampleSize", 8)
        direct_search_sample_size = _sample_size(config, "deepseekDirectSearchSampleSize", 100)
        source_grounded_sample_size = _sample_size(config, "deepseekSourceGroundedSampleSize", 12)
        handout_sample_size = _sample_size(config, "deepseekHandoutSampleSize", 1)
        source_blocks = _collect_source_blocks(admin, resource_rows, config)
        source_grounded_cases = _build_source_grounded_cases(source_blocks, source_grounded_sample_size)
        explanation_queries = runtime_dataset["queries"][:max(0, explanation_sample_size)]
        mcp_queries = runtime_dataset["queries"][:max(0, mcp_sample_size)]
        direct_search_queries = runtime_dataset["queries"][:max(0, direct_search_sample_size)]
        handout_queries = runtime_dataset["queries"][:max(0, handout_sample_size)]
        direct_rows = _run_direct_teacher_searches(admin, direct_search_queries, expected_doc_by_topic, config)
        _write_jsonl(output_dir / "direct_search_rows.jsonl", direct_rows)
        source_grounded_rows = _run_source_grounded_searches(admin, source_grounded_cases, config)
        _write_jsonl(output_dir / "source_grounded_rows.jsonl", source_grounded_rows)
        query_rows = _run_student_explanations(admin, explanation_queries)
        _write_jsonl(output_dir / "query_rows.jsonl", query_rows)
        mcp_rows = _run_deepseek_mcp_react(config.get("backendBaseUrl", "http://127.0.0.1:8080"), mcp_queries, expected_doc_by_topic)
        _write_jsonl(output_dir / "mcp_rows.jsonl", mcp_rows)
        handout_rows = _run_handout_tasks(admin, handout_queries)
        _write_jsonl(output_dir / "handout_rows.jsonl", handout_rows)
        json_rows = _run_json_probes(runtime_dataset["jsonCases"])
        _write_jsonl(output_dir / "json_rows.jsonl", json_rows)
        security_rows = _run_security_checks(admin)
        graph = admin.get("/api/knowledge/graph/spine")
        vector = admin.get("/api/vector-index/status")
        runtime = admin.get("/api/system/runtime")
        metrics = {
            "generatedAt": datetime.now().isoformat(timespec="seconds"),
            "dataset": {"runId": run_id, "topicCount": len(runtime_dataset["topics"]), "queryCount": len(runtime_dataset["queries"]),
                        "explanationSampleSize": len(explanation_queries), "mcpSampleSize": len(mcp_queries),
                        "directSearchSampleSize": len(direct_search_queries), "sourceGroundedSampleSize": len(source_grounded_cases),
                        "handoutSampleSize": len(handout_queries), "jsonCaseCount": len(runtime_dataset["jsonCases"])},
            "resources": _summarize_resources(resource_rows), "cleanup": {"count": 0, "archivedRate": 0.0},
            "directTeacherSearch": _summarize_recall_rows(direct_rows), "sourceGrounded": _summarize_source_grounded_rows(source_grounded_rows),
            "studentExplanation": _summarize_student_explanations(query_rows), "deepseekReact": _summarize_mcp_rows(mcp_rows),
            "handoutTasks": _summarize_handout_rows(handout_rows), "localJson": _summarize_json_rows(json_rows),
            "security": security_rows, "knowledgeGraph": _summarize_graph(graph.body),
            "vectorIndex": _safe_vector_snapshot(vector.body), "runtimeAi": _safe_runtime_ai(runtime.body),
        }
        (output_dir / "deepseek_react_metrics.json").write_text(json.dumps(metrics, ensure_ascii=False, indent=2), encoding="utf-8")
        return metrics
    except Exception as exception:
        (output_dir / "run_failure.json").write_text(json.dumps({
            "runId": run_id,
            "errorType": type(exception).__name__,
            "message": str(exception),
            "resourceRows": resource_rows,
        }, ensure_ascii=False, indent=2), encoding="utf-8")
        raise
    finally:
        cleanup_rows = _cleanup_documents(admin, resource_rows)
        _write_jsonl(output_dir / "cleanup_rows.jsonl", cleanup_rows)
        for row in cleanup_rows:
            _append_jsonl(stage_rows_path, {"stage": "resource_archived", "runId": run_id, **row})


def _materialize_runtime_dataset(output_dir: Path, run_id: str) -> dict[str, Any]:
    dataset_root = output_dir / "runtime-authored"
    dataset_root.mkdir(parents=True, exist_ok=True)
    randomizer = random.Random(int(time.time()))
    topics = []
    queries = []
    json_cases = []
    for index, base in enumerate(TOPIC_BANK, 1):
        topic_dir = dataset_root / f"{index:02d}-{base['id']}"
        topic_dir.mkdir(parents=True, exist_ok=True)
        suffix = randomizer.choice(["先别急着算", "这节容易讲糊", "适合做短讲义", "学生常在这里卡住"])
        notes = "\n".join(f"- {line}" for line in base["notes"])
        worked_steps = "\n".join(f"{step}. {line}" for step, line in enumerate(_worked_solution_steps(base, randomizer), 1))
        handout_outline = "\n".join(f"- {line}" for line in _handout_outline(base, randomizer))
        content = (
            f"# {base['title']}\n\n"
            f"知识标签：{base['knowledge_label']}\n"
            f"适用范围：{base['scope']}\n"
            f"课堂备注：{suffix}\n\n"
            f"## 讲法碎片\n{notes}\n\n"
            f"## 题目口吻\n{base['question']}\n\n"
            f"## 目标\n{base['goal']}\n\n"
            f"## 题解骨架\n{worked_steps}\n\n"
            f"## 讲义版式\n{handout_outline}\n"
        )
        (topic_dir / "notes.md").write_text(content, encoding="utf-8")
        (topic_dir / "qa.md").write_text(
            f"# 题目与拆解\n\n"
            f"题目：{base['question']}\n\n"
            f"讲解目标：{base['goal']}\n\n"
            f"参考解法：\n{worked_steps}\n\n"
            f"提醒：{randomizer.choice(base['notes'])}\n",
            encoding="utf-8",
        )
        (topic_dir / "handout.md").write_text(
            f"# 讲义草稿\n\n"
            f"主题：{base['title']}\n\n"
            f"学生版留白：先写判断入口，再补关键式子，最后解释为什么不能只套公式。\n\n"
            f"教师版答案：{base['goal']}\n\n"
            f"板书顺序：\n{handout_outline}\n\n"
            f"易错提醒：{randomizer.choice(base['notes'])}\n",
            encoding="utf-8",
        )
        topics.append({
            "id": base["id"],
            "title": f"runtime-{run_id}-{base['id']}",
            "scope": base["scope"],
            "path": str(topic_dir.resolve()),
            "knowledgeLabel": base["knowledge_label"],
            "question": base["question"],
            "goal": base["goal"],
        })
        queries.extend(_topic_queries(base, randomizer))
        json_cases.extend(_topic_json_cases(base, randomizer))
    randomizer.shuffle(queries)
    return {"topics": topics, "queries": queries, "jsonCases": json_cases}


def _topic_queries(base: dict[str, Any], randomizer: random.Random) -> list[dict[str, Any]]:
    topic_tags = _runtime_topic_tags(base)
    openings = [
        "老师想讲",
        "别直接给答案，先讲方法判断",
        "如果做成一页短讲义，重点应该放在哪",
        "学生刚问完这题，想要一个不太公式化的解释",
        "备课时想找一点能放进讲义里的材料",
        "这道题不想从计算开始，想先讲为什么这样做",
        "课堂讲评要短一点，但不能只给答案",
        "想做教师版和学生留白版，先找证据",
    ]
    middles = [
        base["question"],
        base["goal"],
        randomizer.choice(base["notes"]),
        f"{base['question']} 但学生说自己看不出入口。",
        f"{base['goal']}，最好能顺带提醒常见误区。",
    ]
    endings = [
        "",
        "请优先找讲法依据。",
        "如果有私有资料和共享资料都可以看。",
        "不要直接写完整答案，先帮我找材料。",
        "希望能区分教师讲义和学生空白讲义。",
        randomizer.choice(base["notes"]),
    ]
    queries = []
    for index in range(24):
        query = "。".join(part for part in [
            randomizer.choice(openings),
            randomizer.choice(middles),
            randomizer.choice(endings),
        ] if part).strip("。")
        if index % 5 == 0:
            query += "，顺便看一下有没有可引用的题解步骤。"
        if index % 7 == 0:
            query = query.replace("。", "，")
        queries.append({
            "topicId": base["id"],
            "query": query,
            "scope": base["scope"],
            "tags": topic_tags,
            "expectedScope": base["scope"],
        })
    randomizer.shuffle(queries)
    return queries


def _runtime_topic_tags(base: dict[str, Any]) -> list[str]:
    structural_tags = _unique_runtime_tags([
        str(base.get("id") or ""),
        str(base.get("knowledge_label") or ""),
        str(base.get("title") or ""),
    ])
    semantic_tags = _rank_runtime_tags([
        str(base.get("question") or ""),
        str(base.get("goal") or ""),
        *[str(note) for note in (base.get("notes") or []) if isinstance(note, str)],
    ], limit=8)
    return _unique_runtime_tags([*structural_tags, *semantic_tags])[:8]


def _worked_solution_steps(base: dict[str, Any], randomizer: random.Random) -> list[str]:
    return [
        f"先把题意翻译成'{base['knowledge_label']}'下的判断问题，不急着套公式。",
        randomizer.choice(base["notes"]),
        f"用一个小例子说明入口，再回到原题目标：{base['goal']}",
        "最后检查边界、条件或模型假设，避免把中间式当成结论。",
    ]


def _handout_outline(base: dict[str, Any], randomizer: random.Random) -> list[str]:
    return [
        f"标题区：{base['title']}，下方留一句本节要解决的困惑。",
        "左栏放题目和学生常见想法，右栏放教师追问。",
        f"中段写方法入口：{base['goal']}",
        f"底部写易错提醒：{randomizer.choice(base['notes'])}",
    ]


def _topic_json_cases(base: dict[str, Any], randomizer: random.Random) -> list[dict[str, Any]]:
    short = base["knowledge_label"]
    return [
        {"topicId": base["id"], "content": f'{{"topic":"{short}","idea":"{base["goal"]}"}}'},
        {"topicId": base["id"], "content": f'前面加一句说明 {{"topic":"{short}","idea":"{randomizer.choice(base["notes"])}"}}'},
        {"topicId": base["id"], "content": f'{{"topic":"{short}","idea":"{base["question"]}"'},
        {"topicId": base["id"], "content": f'```json\n{{"topic":"{short}","idea":"{base["goal"]}","tags":["{base["id"]}"]}}\n```'},
        {"topicId": base["id"], "content": f'不是 JSON：topic={short}; idea={base["goal"]}'},
    ]


def _register_topic_resource(client: MathAgentClient, topic: dict[str, Any]) -> dict[str, Any]:
    body = {
        "sourceType": "local_path",
        "title": topic["title"],
        "localPath": topic["path"],
        "permissionScope": topic["scope"],
        "feishuExportFormat": "md",
    }
    register = _capability_post(client, "teacher-resource:register", "/api/teacher/resources", body, 1)
    register_body = register.body if isinstance(register.body, dict) else {}
    document_id = str(register_body.get("documentId") or "")
    create_job = _capability_post(
        client,
        "teacher-resource:sync",
        f"/api/teacher/resources/{document_id}/sync-jobs",
        {},
        1,
    ) if document_id else None
    job_id = str((create_job.body or {}).get("jobId") or "") if create_job and isinstance(create_job.body, dict) else ""
    execute_job = _capability_post(
        client,
        "teacher-resource:sync-execute",
        f"/api/teacher/resources/{document_id}/sync-jobs/{job_id}/execute",
        {},
        1,
    ) if document_id and job_id else None
    final_job = _wait_for_sync_job(client, document_id, job_id) if document_id and job_id else {}
    return {
        "topicId": topic["id"],
        "scope": topic["scope"],
        "documentId": document_id,
        "jobId": job_id,
        "registerStatus": register.status,
        "syncStatus": create_job.status if create_job else 0,
        "executeStatus": execute_job.status if execute_job else 0,
        "finalStatus": final_job.get("status", ""),
        "finalPhase": final_job.get("phase", ""),
        "message": final_job.get("message", ""),
    }


def _cleanup_documents(client: MathAgentClient, resource_rows: list[dict[str, Any]]) -> list[dict[str, Any]]:
    rows = []
    for resource in resource_rows:
        document_id = str(resource.get("documentId") or "")
        if not document_id:
            continue
        path = f"/api/teacher/resources/{document_id}"
        token = _capability(client, "teacher-resource:archive", path, [], 1)
        archived = client.delete(path, {"X-Capability-Token": token, "X-Request-Hash": stable_request_hash([])})
        rows.append({
            "documentId": document_id,
            "title": "",
            "localPath": "",
            "status": archived.status,
        })
        time.sleep(0.25)
    return rows


def _run_direct_teacher_searches(
        client: MathAgentClient,
        queries: list[dict[str, Any]],
        expected_doc_by_topic: dict[str, str],
        config: dict[str, Any]) -> list[dict[str, Any]]:
    rows = []
    delay_ms = int(config.get("deepseekDirectSearchDelayMs", 2300) or 2300)
    for index, item in enumerate(queries, 1):
        params: dict[str, Any] = {"query": item["query"], "limit": 10}
        filter_mode = index % 4
        if filter_mode in {1, 3}:
            params["permissionScope"] = item.get("expectedScope") or item.get("scope") or ""
        if filter_mode in {2, 3}:
            params["tag"] = item.get("tags") or []
        attempt = client.get("/api/teacher/resources/search", params=params)
        body = attempt.body if isinstance(attempt.body, dict) else {}
        hits = body.get("hits") if isinstance(body, dict) else []
        hit_document_ids = [
            str(hit.get("documentId") or "")
            for hit in (hits or [])
            if isinstance(hit, dict)
        ]
        expected_document_id = expected_doc_by_topic.get(item["topicId"], "")
        expected_rank = _first_rank(hit_document_ids, expected_document_id)
        rows.append({
            "topicId": item["topicId"],
            "query": item["query"],
            "filterMode": filter_mode,
            "params": params,
            "status": attempt.status,
            "elapsedMs": attempt.elapsed_ms,
            "retrievalMode": body.get("retrievalMode", ""),
            "hitCount": body.get("hitCount", 0),
            "expectedDocumentId": expected_document_id,
            "hitDocumentIds": hit_document_ids,
            "expectedRank": expected_rank,
            "recallAt1": bool(expected_rank and expected_rank <= 1),
            "recallAt3": bool(expected_rank and expected_rank <= 3),
            "recallAt5": bool(expected_rank and expected_rank <= 5),
            "recallAt10": bool(expected_rank and expected_rank <= 10),
        })
        if delay_ms > 0:
            time.sleep(delay_ms / 1000)
    return rows


def _collect_source_blocks(
        client: MathAgentClient,
        resource_rows: list[dict[str, Any]],
        config: dict[str, Any]) -> list[dict[str, Any]]:
    per_document_limit = int(config.get("deepseekSourceBlocksPerDocument", 4) or 4)
    cases = []
    for row in resource_rows:
        document_id = str(row.get("documentId") or "")
        topic_id = str(row.get("topicId") or "")
        scope = str(row.get("scope") or "")
        if not document_id:
            continue
        attempt = client.get(f"/api/teacher/resources/{document_id}/blocks")
        blocks = attempt.body if isinstance(attempt.body, list) else []
        selected = 0
        for block in blocks:
            if not isinstance(block, dict):
                continue
            text = str(block.get("normalizedText") or block.get("rawText") or "").strip()
            if len(text) < 60 or len(text) > 900:
                continue
            cases.append({
                "topicId": topic_id,
                "scope": scope,
                "documentId": document_id,
                "blockId": str(block.get("blockId") or ""),
                "chapter": str(block.get("chapter") or ""),
                "section": str(block.get("section") or ""),
                "text": text,
            })
            selected += 1
            if selected >= per_document_limit:
                break
    return cases


def _build_source_grounded_cases(source_blocks: list[dict[str, Any]], sample_size: int) -> list[dict[str, Any]]:
    rows = []
    for block in source_blocks[:max(0, sample_size)]:
        query = _generate_source_grounded_query(block)
        rows.append({
            **block,
            "query": query,
            "derivedTags": _derived_runtime_tags(block),
        })
    return rows


def _run_source_grounded_searches(
        client: MathAgentClient,
        cases: list[dict[str, Any]],
        config: dict[str, Any]) -> list[dict[str, Any]]:
    rows = []
    delay_ms = int(config.get("deepseekSourceGroundedDelayMs", 1000) or 1000)
    for case in cases:
        for filter_mode, params in _source_grounded_filter_variants(case):
            started = time.perf_counter()
            attempt = client.get("/api/teacher/resources/search", params=params)
            body = attempt.body if isinstance(attempt.body, dict) else {}
            hits = body.get("hits") if isinstance(body, dict) else []
            hit_document_ids = []
            hit_block_ids = []
            hit_snippets = []
            for hit in hits or []:
                if not isinstance(hit, dict):
                    continue
                hit_document_ids.append(str(hit.get("documentId") or ""))
                hit_block_ids.append(str(hit.get("blockId") or ""))
                hit_snippets.append(str(hit.get("snippet") or ""))
            block_rank = _first_rank(hit_block_ids, case["blockId"])
            document_rank = _first_rank(hit_document_ids, case["documentId"])
            judge = _judge_source_grounded_hit(case, case["query"], hit_snippets[:3])
            rows.append({
                "topicId": case["topicId"],
                "scope": case["scope"],
                "query": case["query"],
                "filterMode": filter_mode,
                "params": params,
                "expectedDocumentId": case["documentId"],
                "expectedBlockId": case["blockId"],
                "status": attempt.status,
                "elapsedMs": int(round((time.perf_counter() - started) * 1000)),
                "retrievalMode": body.get("retrievalMode", ""),
                "hitCount": body.get("hitCount", 0),
                "hitDocumentIds": hit_document_ids,
                "hitBlockIds": hit_block_ids,
                "blockRank": block_rank,
                "documentRank": document_rank,
                "blockRecallAt1": bool(block_rank and block_rank <= 1),
                "blockRecallAt3": bool(block_rank and block_rank <= 3),
                "blockRecallAt5": bool(block_rank and block_rank <= 5),
                "documentRecallAt1": bool(document_rank and document_rank <= 1),
                "documentRecallAt3": bool(document_rank and document_rank <= 3),
                "documentRecallAt5": bool(document_rank and document_rank <= 5),
                "judgeScore": judge.get("score", 0),
                "judgePass": bool(judge.get("pass", False)),
                "judgeReason": judge.get("reason", ""),
            })
            if delay_ms > 0:
                time.sleep(delay_ms / 1000)
    return rows


def _run_student_explanations(client: MathAgentClient, queries: list[dict[str, Any]]) -> list[dict[str, Any]]:
    rows = []
    for item in queries:
        started = time.perf_counter()
        attempt = client.post("/api/students/explanations", {
            "questionText": item["query"],
            "searchTextbook": True,
            "searchKnowledgeGraph": True,
            "searchTeacherResources": True,
            "maxTextbookHits": 4,
            "maxTeacherResourceHits": 4,
        })
        elapsed_ms = int(round((time.perf_counter() - started) * 1000))
        body = attempt.body if isinstance(attempt.body, dict) else {}
        sources = body.get("sources") or []
        ai_draft = body.get("aiDraft") or {}
        cards = body.get("cards") or []
        explanation_judge = _judge_student_explanation_quality(item["query"], cards, sources)
        rows.append({
            "topicId": item["topicId"],
            "query": item["query"],
            "status": attempt.status,
            "elapsedMs": elapsed_ms,
            "sourceTypes": sorted({str(source.get("sourceType") or "") for source in sources if isinstance(source, dict)}),
            "sourceCount": len(sources) if isinstance(sources, list) else 0,
            "knowledgeGraphSourceCount": sum(1 for source in sources if isinstance(source, dict) and source.get("sourceType") == "knowledge_graph"),
            "teacherSourceCount": sum(1 for source in sources if isinstance(source, dict) and source.get("sourceType") == "teacher_resource"),
            "textbookSourceCount": sum(1 for source in sources if isinstance(source, dict) and source.get("sourceType") == "textbook"),
            "aiProvider": ai_draft.get("providerName", ""),
            "aiModel": ai_draft.get("modelCode", ""),
            "aiStructured": bool(ai_draft.get("structured")),
            "cardCount": len(cards) if isinstance(cards, list) else 0,
            "cardItemCount": sum(len(card.get("items") or []) for card in cards if isinstance(card, dict)),
            "qualityScore": explanation_judge["score"],
            "qualityPass": explanation_judge["pass"],
            "qualityReason": explanation_judge["reason"],
            "workflowStages": [stage.get("stageKey") for stage in (body.get("workflowStages") or []) if isinstance(stage, dict)],
        })
        time.sleep(1.2)
    return rows


def _run_handout_tasks(client: MathAgentClient, queries: list[dict[str, Any]]) -> list[dict[str, Any]]:
    rows = []
    templates = client.get("/api/teaching/handout-templates")
    template_items = templates.body if isinstance(templates.body, list) else []
    preferred_template = _preferred_template_code(template_items)
    for item in queries:
        body = {
            "clientRequestId": f"deepseek-handout-{item['topicId']}-{int(time.time() * 1000000)}",
            "questionText": item["query"],
            "learningGoal": f"制作一份讲义，覆盖题目入口、题解步骤、学生留白和教师提示：{item['query'][:120]}",
            "evidenceLimit": 4,
            "handoutTemplateCode": preferred_template,
        }
        started = time.perf_counter()
        token = _capability(client, "teaching:submit", "/api/teaching/tasks", body, 1.5)
        attempt = client.post(
            "/api/teaching/tasks",
            body,
            {"X-Capability-Token": token, "X-Request-Hash": stable_request_hash(body)},
        )
        submitted = attempt.body if isinstance(attempt.body, dict) else {}
        response = _wait_for_teaching_task(client, str(submitted.get("taskId") or ""), submitted)
        elapsed_ms = int(round((time.perf_counter() - started) * 1000))
        handout_text = "\n".join(
            str(response.get(key) or "")
            for key in ["handoutLatex", "teacherHandoutLatex", "studentHandoutLatex"]
        )
        ai_draft = response.get("aiDraft") if isinstance(response.get("aiDraft"), dict) else {}
        handout_judge = _judge_handout_quality(item["query"], response)
        rows.append({
            "topicId": item["topicId"],
            "query": item["query"],
            "status": attempt.status,
            "finalStatus": response.get("status", ""),
            "elapsedMs": elapsed_ms,
            "taskId": response.get("taskId", ""),
            "selectedTemplateCode": ((response.get("selectedTemplate") or {}).get("code") if isinstance(response.get("selectedTemplate"), dict) else ""),
            "templateRequested": preferred_template,
            "handoutChars": len(handout_text),
            "hasTeacherVersion": bool(response.get("teacherHandoutLatex") or response.get("handoutLatex")),
            "hasStudentVersion": bool(response.get("studentHandoutLatex")),
            "containsEvidenceRefs": "evidence" in handout_text.lower() or "证据" in handout_text,
            "evidenceCount": len(response.get("evidence") or []) if isinstance(response.get("evidence"), list) else 0,
            "aiProvider": ai_draft.get("providerName", ""),
            "aiModel": ai_draft.get("modelCode", ""),
            "aiStructured": bool(ai_draft.get("structured")),
            "qualityScore": handout_judge["score"],
            "qualityPass": handout_judge["pass"],
            "qualityReason": handout_judge["reason"],
            "stageTimings": [stage.get("stage") for stage in (response.get("stageTimings") or []) if isinstance(stage, dict)],
        })
        time.sleep(1.2)
    return rows


def _wait_for_teaching_task(client: MathAgentClient, task_id: str, fallback: dict[str, Any]) -> dict[str, Any]:
    if not task_id:
        return fallback
    deadline = time.time() + 180
    latest = fallback
    while time.time() < deadline:
        attempt = client.get(f"/api/teaching/tasks/{task_id}")
        if attempt.status == 429:
            # Teaching-task polling can hit backend rate limits under repeated benchmark runs.
            # Back off instead of burning the entire timeout window with ineffective 2-second retries.
            time.sleep(8)
            continue
        if isinstance(attempt.body, dict):
            latest = attempt.body
            status = str(latest.get("status") or "").lower()
            if status in {"completed", "failed"}:
                return latest
            if latest.get("teacherHandoutLatex") or latest.get("studentHandoutLatex") or latest.get("handoutLatex"):
                return latest
        time.sleep(2)
    return latest


def _run_deepseek_mcp_react(
    base_url: str,
    queries: list[dict[str, Any]],
    expected_doc_by_topic: dict[str, str],
) -> list[dict[str, Any]]:
    if not os.environ.get("DEEPSEEK_API_KEY"):
        raise RuntimeError("DEEPSEEK_API_KEY is required for deepseek_react_rag_eval")
    mcp_secret = MCP_SECRET_FILE.read_text(encoding="utf-8").strip()
    session = requests.Session()
    rows = []
    for item in queries:
        plan = _mcp_call(session, base_url, mcp_secret, "plan_agent_run", {
            "agentCode": "TeacherAssistantAgent",
            "taskType": "teacher_query_routing",
            "userVipLevel": "admin",
            "estimatedInputTokens": 800,
            "estimatedOutputTokens": 240,
            "hasImage": False,
            "hasFormula": True,
            "difficulty": "medium",
            "latencyRequirement": "normal",
            "costBudget": 1.2,
            "previousFailureCount": 0,
            "requiredJsonSchema": True,
            "requestedToolScopes": ["tool:search:textbook", "tool:search:private"],
            "requestedDataScopes": ["PUBLIC_TEXTBOOK", item.get("scope", "TEACHER_PRIVATE")],
            "preferredProviderName": BENCHMARK_LLM_PROVIDER,
            "preferredModelCode": BENCHMARK_LLM_MODEL,
        })
        react_plan = (((plan.get("result") or {}).get("reactToolPlan")) or {})
        deepseek_decision = _deepseek_choose_actions(item["query"], react_plan)
        actions = [name for name in deepseek_decision.get("toolNames", []) if name in {"search_textbook_evidence", "search_teacher_resource_evidence"}]
        tool_rows = []
        for tool_name in actions[:2]:
            arguments = {"query": item["query"], "limit": 4}
            if tool_name == "search_teacher_resource_evidence":
                arguments.update(_teacher_filter_arguments(deepseek_decision, item["query"]))
            tool_rows.append(_mcp_call(session, base_url, mcp_secret, tool_name, arguments))
            time.sleep(0.6)
        expected_document_id = expected_doc_by_topic.get(item["topicId"], "")
        teacher_result = next(
            (tool_row.get("result") or {} for tool_row in tool_rows if tool_row.get("toolName") == "search_teacher_resource_evidence"),
            {},
        )
        teacher_hit_document_ids = [
            str(hit.get("documentId") or "")
            for hit in (teacher_result.get("hits") or [])
            if isinstance(hit, dict)
        ]
        expected_rank = _first_rank(teacher_hit_document_ids, expected_document_id)
        rows.append({
            "topicId": item["topicId"],
            "query": item["query"],
            "expectedDocumentId": expected_document_id,
            "plannedProvider": ((plan.get("result") or {}).get("providerName")) or "",
            "plannedModel": ((plan.get("result") or {}).get("modelCode")) or "",
            "allowedToolScopes": ((plan.get("result") or {}).get("allowedToolScopes")) or [],
            "allowedDataScopes": ((plan.get("result") or {}).get("allowedDataScopes")) or [],
            "deepseekDecision": deepseek_decision,
            "executedTools": actions[:2],
            "teacherRetrievalMode": teacher_result.get("retrievalMode", ""),
            "teacherHitDocumentIds": teacher_hit_document_ids,
            "teacherExpectedRank": expected_rank,
            "teacherRecallAt1": bool(expected_rank and expected_rank <= 1),
            "teacherRecallAt3": bool(expected_rank and expected_rank <= 3),
            "teacherRecallAt5": bool(expected_rank and expected_rank <= 5),
            "toolHitCounts": {
                tool_row.get("toolName", ""): (((tool_row.get("result") or {}).get("hitCount")) or ((tool_row.get("result") or {}).get("total")) or 0)
                for tool_row in tool_rows
            },
        })
    return rows


def _deepseek_choose_actions(query: str, react_plan: dict[str, Any]) -> dict[str, Any]:
    body = {
        "model": BENCHMARK_LLM_MODEL,
        "temperature": 0.2,
        "messages": [
            {
                "role": "system",
                "content": (
                    "You are only a retrieval router for a high-school math assistant. "
                    "Do not solve the problem. Choose public textbook search, teacher resource search, or both. "
                    "For teacher resource search you may optionally return generic permissionScopes and tags, "
                    "but never invent documentIds. Return strict JSON with keys toolNames, teacherResourceFilter, and reason. "
                    "你是一个只负责检索路由的数学助教。"
                    "你不能直接解题，只能决定先查 public textbook、private teacher resource，或两者都查。"
                    "返回严格 JSON：{\"toolNames\":[...],\"reason\":\"...\"}。"
                ),
            },
            {
                "role": "user",
                "content": json.dumps({
                    "query": query,
                    "reactToolPlan": react_plan,
                    "allowedTools": ["search_textbook_evidence", "search_teacher_resource_evidence"],
                    "availablePermissionScopes": ["TEACHER_PRIVATE", "MATH_VIP"],
                }, ensure_ascii=False),
            },
        ],
        "response_format": {"type": "json_object"},
    }
    payload = _post_benchmark_llm_json(body)
    content = (((payload.get("choices") or [{}])[0].get("message") or {}).get("content")) or "{}"
    return json.loads(content)


def _generate_source_grounded_query(block: dict[str, Any]) -> str:
    snippet = str(block.get("text") or "")[:260]
    chapter = str(block.get("chapter") or "")
    section = str(block.get("section") or "")
    body = {
        "model": BENCHMARK_LLM_MODEL,
        "temperature": 0.3,
        "messages": [
            {
                "role": "system",
                "content": (
                    "你要根据一段高中数学教学资料，编一个自然、模糊、但仍然能让老师去检索相关内容的问题。"
                    "不要照抄原文，不要直接引用长句，不要输出答案。"
                    "返回严格 JSON：{\"query\":\"...\"}。"
                ),
            },
            {
                "role": "user",
                "content": json.dumps({
                    "chapter": chapter,
                    "section": section,
                    "sourceSnippet": snippet,
                }, ensure_ascii=False),
            },
        ],
        "response_format": {"type": "json_object"},
    }
    payload = _post_benchmark_llm_json(body)
    content = (((payload.get("choices") or [{}])[0].get("message") or {}).get("content")) or "{}"
    parsed = json.loads(content)
    query = str(parsed.get("query") or "").strip()
    if query:
        return query
    return f"老师想找和{chapter or section or '这段内容'}有关的讲解材料，最好能先讲入口再讲步骤。"


def _derived_runtime_tags(block: dict[str, Any]) -> list[str]:
    structural_tags = _unique_runtime_tags([
        str(block.get("topicId") or ""),
        str(block.get("chapter") or ""),
        str(block.get("section") or ""),
    ])
    semantic_tags = _rank_runtime_tags([
        str(block.get("text") or "")[:320],
    ], limit=8)
    return _unique_runtime_tags([*structural_tags, *semantic_tags])[:8]


def _rank_runtime_tags(texts: list[str], limit: int) -> list[str]:
    seen: set[str] = set()
    ordered: list[str] = []
    scores: Counter[str] = Counter()
    for text in texts:
        normalized = _compact_runtime_text(text)
        if not normalized:
            continue
        for candidate in _runtime_tag_candidates(normalized):
            scores[candidate] += 1
            if candidate not in seen:
                seen.add(candidate)
                ordered.append(candidate)
    ranked = sorted(
        ordered,
        key=lambda candidate: (-scores[candidate], -len(candidate), ordered.index(candidate)),
    )
    return ranked[:max(0, limit)]


def _runtime_tag_candidates(text: str) -> list[str]:
    candidates: list[str] = []
    for segment in re.split(r"[，。；：、！？,.!?\s()（）【】\[\]\"'“”‘’/\\|-]+", text):
        compact = _compact_runtime_text(segment)
        if _runtime_tag_candidate_ok(compact):
            candidates.append(compact)
    if _contains_cjk(text):
        for width in (6, 5, 4):
            for index in range(0, max(0, len(text) - width + 1)):
                candidate = text[index:index + width]
                if _runtime_tag_candidate_ok(candidate):
                    candidates.append(candidate)
    return list(dict.fromkeys(candidates))


def _runtime_tag_candidate_ok(candidate: str) -> bool:
    if len(candidate) < 2 or len(candidate) > 14:
        return False
    if candidate.isdigit():
        return False
    if len(set(candidate)) == 1:
        return False
    if candidate.lower() in {"json", "topic", "idea", "runtime"}:
        return False
    return any(character.isalpha() for character in candidate) or _contains_cjk(candidate)


def _compact_runtime_text(text: str) -> str:
    return re.sub(r"\s+", "", str(text or "").strip())


def _contains_cjk(text: str) -> bool:
    return any("\u4e00" <= character <= "\u9fff" for character in text)


def _unique_runtime_tags(tags: list[str]) -> list[str]:
    unique: list[str] = []
    seen: set[str] = set()
    for tag in tags:
        compact = _compact_runtime_text(tag)
        if not compact or compact in seen:
            continue
        seen.add(compact)
        unique.append(compact)
    return unique


def _source_grounded_filter_variants(case: dict[str, Any]) -> list[tuple[str, dict[str, Any]]]:
    base = {"query": case["query"], "limit": 10}
    scope = str(case.get("scope") or "")
    tags = case.get("derivedTags") or []
    variants = [("none", dict(base))]
    if scope:
        variants.append(("scope", {**base, "permissionScope": scope}))
    if tags:
        variants.append(("tag", {**base, "tag": tags}))
    if scope and tags:
        variants.append(("scope+tag", {**base, "permissionScope": scope, "tag": tags}))
    return variants


def _judge_source_grounded_hit(case: dict[str, Any], query: str, snippets: list[str]) -> dict[str, Any]:
    if not snippets:
        return {"score": 0, "pass": False, "reason": "no_hits"}
    body = {
        "model": BENCHMARK_LLM_MODEL,
        "temperature": 0.0,
        "messages": [
            {
                "role": "system",
                "content": (
                    "你是检索评测裁判。根据原始资料片段、生成的问题和检索结果片段，"
                    "判断检索结果是否真正命中原始资料想表达的内容。"
                    "返回严格 JSON：{\"score\":0-5,\"pass\":true/false,\"reason\":\"...\"}。"
                    "score>=4 才算 pass。"
                ),
            },
            {
                "role": "user",
                "content": json.dumps({
                    "query": query,
                    "sourceSnippet": str(case.get("text") or "")[:260],
                    "retrievedSnippets": snippets,
                }, ensure_ascii=False),
            },
        ],
        "response_format": {"type": "json_object"},
    }
    payload = _post_benchmark_llm_json(body)
    content = (((payload.get("choices") or [{}])[0].get("message") or {}).get("content")) or "{}"
    parsed = json.loads(content)
    score = int(parsed.get("score") or 0)
    return {
        "score": max(0, min(score, 5)),
        "pass": bool(parsed.get("pass")) and score >= 4,
        "reason": str(parsed.get("reason") or "").strip(),
    }


def _judge_student_explanation_quality(
    query: str,
    cards: list[Any],
    sources: list[Any],
) -> dict[str, Any]:
    if not cards:
        return {"score": 0, "pass": False, "reason": "no_cards"}
    compact_cards = []
    for card in cards[:4]:
        if not isinstance(card, dict):
            continue
        compact_cards.append({
            "title": str(card.get("title") or "")[:80],
            "summary": str(card.get("summary") or "")[:220],
            "items": [str(item)[:100] for item in (card.get("items") or [])[:4] if isinstance(item, str)],
            "renderMode": str(card.get("renderMode") or ""),
        })
    compact_sources = []
    for source in sources[:5]:
        if not isinstance(source, dict):
            continue
        compact_sources.append({
            "sourceType": str(source.get("sourceType") or ""),
            "title": str(source.get("title") or "")[:80],
            "snippet": str(source.get("snippet") or "")[:180],
        })
    body = {
        "model": BENCHMARK_LLM_MODEL,
        "temperature": 0.0,
        "messages": [
            {
                "role": "system",
                "content": (
                    "You are grading a high-school math explanation response. "
                    "Judge whether the explanation actually addresses the user's question, has teaching structure, "
                    "and appears grounded in the provided sources instead of generic filler. "
                    "Return strict JSON with keys score, pass, and reason. score is 0-5 and pass requires score>=4. "
                    "你在评估高中数学讲解卡片质量。看它有没有回答问题、有没有教学结构、有没有基于给定证据。"
                ),
            },
            {
                "role": "user",
                "content": json.dumps({
                    "query": query,
                    "cards": compact_cards,
                    "sources": compact_sources,
                }, ensure_ascii=False),
            },
        ],
        "response_format": {"type": "json_object"},
    }
    payload = _post_benchmark_llm_json(body)
    content = (((payload.get("choices") or [{}])[0].get("message") or {}).get("content")) or "{}"
    parsed = json.loads(content)
    score = int(parsed.get("score") or 0)
    return {
        "score": max(0, min(score, 5)),
        "pass": bool(parsed.get("pass")) and score >= 4,
        "reason": str(parsed.get("reason") or "").strip(),
    }


def _judge_handout_quality(query: str, response: dict[str, Any]) -> dict[str, Any]:
    teacher_latex = str(response.get("teacherHandoutLatex") or response.get("handoutLatex") or "")
    student_latex = str(response.get("studentHandoutLatex") or "")
    ai_draft = response.get("aiDraft") if isinstance(response.get("aiDraft"), dict) else {}
    if not teacher_latex and not student_latex:
        return {"score": 0, "pass": False, "reason": "no_handout"}
    body = {
        "model": BENCHMARK_LLM_MODEL,
        "temperature": 0.0,
        "messages": [
            {
                "role": "system",
                "content": (
                    "You are grading a teacher handout draft for a high-school math lesson. "
                    "Judge whether it matches the user's requested topic, includes a usable teaching structure, "
                    "and distinguishes teacher/student use when both versions exist. "
                    "Return strict JSON with keys score, pass, and reason. score is 0-5 and pass requires score>=4. "
                    "你在评估高中数学讲义草稿质量。看它是否贴题、结构是否可用、教师版和学生版是否有区分。"
                ),
            },
            {
                "role": "user",
                "content": json.dumps({
                    "query": query,
                    "teacherHandoutExcerpt": teacher_latex[:1800],
                    "studentHandoutExcerpt": student_latex[:1400],
                    "evidenceCount": len(response.get("evidence") or []) if isinstance(response.get("evidence"), list) else 0,
                    "aiTeacherExplanation": str(ai_draft.get("teacherExplanation") or "")[:300],
                    "aiStudentHint": str(ai_draft.get("studentHint") or "")[:220],
                }, ensure_ascii=False),
            },
        ],
        "response_format": {"type": "json_object"},
    }
    payload = _post_benchmark_llm_json(body)
    content = (((payload.get("choices") or [{}])[0].get("message") or {}).get("content")) or "{}"
    parsed = json.loads(content)
    score = int(parsed.get("score") or 0)
    return {
        "score": max(0, min(score, 5)),
        "pass": bool(parsed.get("pass")) and score >= 4,
        "reason": str(parsed.get("reason") or "").strip(),
    }


def _post_benchmark_llm_json(body: dict[str, Any]) -> dict[str, Any]:
    last_error: Exception | None = None
    api_key = _benchmark_llm_api_key()
    headers = {
        "Authorization": f"Bearer {api_key}",
        "Content-Type": "application/json",
    }
    for attempt in range(1, 4):
        try:
            response = requests.post(BENCHMARK_LLM_API_URL, headers=headers, json=body, timeout=120)
            response.raise_for_status()
            return response.json()
        except (requests.Timeout, requests.ConnectionError) as exception:
            last_error = exception
            if attempt == 3:
                break
            time.sleep(2 * attempt)
    raise RuntimeError(f"Benchmark LLM request failed after 3 real attempts via {BENCHMARK_LLM_PROVIDER}") from last_error


def _benchmark_llm_api_key() -> str:
    if BENCHMARK_LLM_PROVIDER == "openai":
        api_key = os.environ.get("OPENAI_API_KEY", "")
    else:
        api_key = os.environ.get("DEEPSEEK_API_KEY", "")
    if not api_key:
        raise RuntimeError(f"{BENCHMARK_LLM_PROVIDER.upper()} API key is required for benchmark LLM requests")
    return api_key


def _teacher_filter_arguments(decision: dict[str, Any], query: str = "") -> dict[str, Any]:
    teacher_filter = decision.get("teacherResourceFilter")
    if not isinstance(teacher_filter, dict):
        return {}
    arguments: dict[str, Any] = {}
    scopes = _string_values(teacher_filter.get("permissionScopes"))
    tags = _normalized_filter_tags(_string_values(teacher_filter.get("tags")), query)
    if scopes:
        arguments["permissionScopes"] = scopes
    if tags:
        arguments["tags"] = tags
    return arguments


def _string_values(value: Any) -> list[str]:
    if isinstance(value, str):
        candidates = value.split(",")
    elif isinstance(value, list):
        candidates = [str(item) for item in value if isinstance(item, str)]
    else:
        return []
    return [candidate.strip() for candidate in candidates if candidate.strip()]


def _normalized_filter_tags(tags: list[str], query: str) -> list[str]:
    if not tags:
        return []
    query_is_cjk = _contains_cjk(query)
    normalized = []
    for tag in tags:
        if query_is_cjk and _is_ascii_only(tag):
            continue
        normalized.append(tag)
    return normalized


def _contains_cjk(text: str) -> bool:
    return bool(re.search(r"[\u3400-\u9fff]", text or ""))


def _is_ascii_only(text: str) -> bool:
    stripped = (text or "").strip()
    return bool(stripped) and all(ord(character) < 128 for character in stripped)


def _first_rank(document_ids: list[str], expected_document_id: str) -> int | None:
    if not expected_document_id:
        return None
    for index, document_id in enumerate(document_ids, 1):
        if document_id == expected_document_id:
            return index
    return None


def _run_json_probes(cases: list[dict[str, Any]]) -> list[dict[str, Any]]:
    rows = []
    for case in cases:
        parsed = _extract_first_json_object(case["content"])
        rows.append({
            "topicId": case["topicId"],
            "parsed": parsed is not None,
            "schemaLike": isinstance(parsed, dict) and bool(parsed.get("topic")) and bool(parsed.get("idea")),
        })
    return rows


def _preferred_template_code(template_items: list[Any]) -> str | None:
    codes = [
        str(item.get("code") or "")
        for item in template_items
        if isinstance(item, dict) and str(item.get("code") or "").strip()
    ]
    for preferred in ["teacher_solution_v1", "space_vector_reference_v1", "default_standard"]:
        if preferred in codes:
            return preferred
    return codes[0] if codes else None


def _run_security_checks(client: MathAgentClient) -> dict[str, Any]:
    body = {
        "clientRequestId": f"deepseek-react-{int(time.time() * 1000)}",
        "questionText": "只做安全探针，不做题目生成",
        "learningGoal": "验证 capability replay 和 hash mismatch",
        "evidenceLimit": 1,
        "handoutTemplateCode": None,
    }
    token = _capability(client, "teaching:submit", "/api/teaching/tasks", body, 1)
    headers = {"X-Capability-Token": token, "X-Request-Hash": stable_request_hash(body)}
    first = client.post("/api/teaching/tasks", body, headers)
    second = client.post("/api/teaching/tasks", body, headers)
    return {
        "replayFirstStatus": first.status,
        "replaySecondStatus": second.status,
        "replayBlocked": second.status in {403, 409, 429},
    }


def _summarize_resources(rows: list[dict[str, Any]]) -> dict[str, Any]:
    return {
        "count": len(rows),
        "completedRate": _rate(rows, lambda row: row["finalStatus"] == "completed"),
        "scopeBreakdown": Counter(row["scope"] for row in rows),
    }


def _summarize_cleanup_rows(rows: list[dict[str, Any]]) -> dict[str, Any]:
    return {
        "count": len(rows),
        "archivedRate": _rate(rows, lambda row: row.get("status") == 200),
    }


def _summarize_recall_rows(rows: list[dict[str, Any]]) -> dict[str, Any]:
    successful_rows = [row for row in rows if row.get("status") == 200]
    return {
        "count": len(rows),
        "successRate": _rate(rows, lambda row: row.get("status") == 200),
        "hitRate": _rate(successful_rows, lambda row: int(row.get("hitCount") or 0) > 0),
        "recallAt1": _rate(successful_rows, lambda row: bool(row.get("recallAt1"))),
        "recallAt3": _rate(successful_rows, lambda row: bool(row.get("recallAt3"))),
        "recallAt5": _rate(successful_rows, lambda row: bool(row.get("recallAt5"))),
        "recallAt10": _rate(successful_rows, lambda row: bool(row.get("recallAt10"))),
        "filteredRetrievalRate": _rate(
            successful_rows,
            lambda row: str(row.get("retrievalMode") or "").endswith("_filtered"),
        ),
        "latency": compute_latency_summary(row.get("elapsedMs", 0) for row in successful_rows),
        "statusBreakdown": dict(Counter(str(row.get("status", "")) for row in rows)),
        "filterModeBreakdown": dict(Counter(str(row.get("filterMode", "")) for row in rows)),
    }


def _summarize_source_grounded_rows(rows: list[dict[str, Any]]) -> dict[str, Any]:
    successful_rows = [row for row in rows if row.get("status") == 200]
    return {
        "count": len(rows),
        "successRate": _rate(rows, lambda row: row.get("status") == 200),
        "blockRecallAt1": _rate(successful_rows, lambda row: bool(row.get("blockRecallAt1"))),
        "blockRecallAt3": _rate(successful_rows, lambda row: bool(row.get("blockRecallAt3"))),
        "blockRecallAt5": _rate(successful_rows, lambda row: bool(row.get("blockRecallAt5"))),
        "documentRecallAt1": _rate(successful_rows, lambda row: bool(row.get("documentRecallAt1"))),
        "documentRecallAt3": _rate(successful_rows, lambda row: bool(row.get("documentRecallAt3"))),
        "documentRecallAt5": _rate(successful_rows, lambda row: bool(row.get("documentRecallAt5"))),
        "judgePassRate": _rate(successful_rows, lambda row: bool(row.get("judgePass"))),
        "avgJudgeScore": round(sum(float(row.get("judgeScore") or 0) for row in successful_rows) / len(successful_rows), 3)
        if successful_rows else 0.0,
        "latency": compute_latency_summary(row.get("elapsedMs", 0) for row in successful_rows),
        "statusBreakdown": dict(Counter(str(row.get("status", "")) for row in rows)),
        "filterModeBreakdown": dict(Counter(str(row.get("filterMode", "")) for row in rows)),
        "byFilterMode": _group_source_grounded_by_filter_mode(successful_rows),
    }


def _group_source_grounded_by_filter_mode(rows: list[dict[str, Any]]) -> dict[str, Any]:
    grouped: dict[str, list[dict[str, Any]]] = {}
    for row in rows:
        grouped.setdefault(str(row.get("filterMode", "")), []).append(row)
    summary: dict[str, Any] = {}
    for filter_mode, group in grouped.items():
        summary[filter_mode] = {
            "count": len(group),
            "blockRecallAt1": _rate(group, lambda row: bool(row.get("blockRecallAt1"))),
            "blockRecallAt3": _rate(group, lambda row: bool(row.get("blockRecallAt3"))),
            "blockRecallAt5": _rate(group, lambda row: bool(row.get("blockRecallAt5"))),
            "documentRecallAt1": _rate(group, lambda row: bool(row.get("documentRecallAt1"))),
            "documentRecallAt3": _rate(group, lambda row: bool(row.get("documentRecallAt3"))),
            "documentRecallAt5": _rate(group, lambda row: bool(row.get("documentRecallAt5"))),
            "judgePassRate": _rate(group, lambda row: bool(row.get("judgePass"))),
            "avgJudgeScore": round(sum(float(row.get("judgeScore") or 0) for row in group) / len(group), 3),
        }
    return summary


def _summarize_student_explanations(rows: list[dict[str, Any]]) -> dict[str, Any]:
    provider_counts = Counter(row["aiProvider"] or "unknown" for row in rows)
    return {
        "count": len(rows),
        "successRate": _rate(rows, lambda row: row["status"] == 200),
        "textbookRouteRate": _rate(rows, lambda row: row["textbookSourceCount"] > 0),
        "teacherRouteRate": _rate(rows, lambda row: row["teacherSourceCount"] > 0),
        "knowledgeGraphRouteRate": _rate(rows, lambda row: row["knowledgeGraphSourceCount"] > 0),
        "aiStructuredRate": _rate(rows, lambda row: row["aiStructured"]),
        "qualityPassRate": _rate(rows, lambda row: bool(row.get("qualityPass"))),
        "avgQualityScore": round(sum(float(row.get("qualityScore") or 0) for row in rows) / len(rows), 3) if rows else 0.0,
        "avgCardCount": round(sum(int(row.get("cardCount") or 0) for row in rows) / len(rows), 2) if rows else 0.0,
        "latency": compute_latency_summary(row["elapsedMs"] for row in rows),
        "providerCounts": dict(provider_counts),
    }


def _summarize_handout_rows(rows: list[dict[str, Any]]) -> dict[str, Any]:
    return {
        "count": len(rows),
        "acceptedRate": _rate(rows, lambda row: row.get("status") == 200),
        "completedRate": _rate(rows, lambda row: str(row.get("finalStatus") or "").lower() == "completed"),
        "teacherVersionRate": _rate(rows, lambda row: bool(row.get("hasTeacherVersion"))),
        "studentVersionRate": _rate(rows, lambda row: bool(row.get("hasStudentVersion"))),
        "evidenceReferenceRate": _rate(rows, lambda row: bool(row.get("containsEvidenceRefs"))),
        "aiStructuredRate": _rate(rows, lambda row: bool(row.get("aiStructured"))),
        "qualityPassRate": _rate(rows, lambda row: bool(row.get("qualityPass"))),
        "avgQualityScore": round(sum(float(row.get("qualityScore") or 0) for row in rows) / len(rows), 3) if rows else 0.0,
        "avgHandoutChars": int(sum(int(row.get("handoutChars") or 0) for row in rows) / len(rows)) if rows else 0,
        "latency": compute_latency_summary(row.get("elapsedMs", 0) for row in rows),
    }


def _summarize_mcp_rows(rows: list[dict[str, Any]]) -> dict[str, Any]:
    tool_counter = Counter(tool for row in rows for tool in row["executedTools"])
    teacher_rows = [row for row in rows if "search_teacher_resource_evidence" in row["executedTools"]]
    return {
        "count": len(rows),
        "plannedDeepSeekRate": _rate(rows, lambda row: row["plannedProvider"] == "deepseek"),
        "teacherPrivateToolRate": _rate(rows, lambda row: "search_teacher_resource_evidence" in row["executedTools"]),
        "publicTextbookToolRate": _rate(rows, lambda row: "search_textbook_evidence" in row["executedTools"]),
        "teacherRecallSampleSize": len(teacher_rows),
        "teacherRecallAt1": _rate(teacher_rows, lambda row: bool(row.get("teacherRecallAt1"))),
        "teacherRecallAt3": _rate(teacher_rows, lambda row: bool(row.get("teacherRecallAt3"))),
        "teacherRecallAt5": _rate(teacher_rows, lambda row: bool(row.get("teacherRecallAt5"))),
        "teacherFilteredRetrievalRate": _rate(
            teacher_rows,
            lambda row: str(row.get("teacherRetrievalMode") or "").endswith("_filtered"),
        ),
        "toolCounts": dict(tool_counter),
    }


def _summarize_json_rows(rows: list[dict[str, Any]]) -> dict[str, Any]:
    return {
        "count": len(rows),
        "parsedRate": _rate(rows, lambda row: row["parsed"]),
        "schemaLikeRate": _rate(rows, lambda row: row["schemaLike"]),
    }


def _summarize_graph(body: Any) -> dict[str, Any]:
    if not isinstance(body, dict):
        return {"nodeCount": 0, "edgeCount": 0}
    return {
        "version": body.get("version", ""),
        "nodeCount": body.get("nodeCount", len(body.get("nodes") or [])),
        "edgeCount": body.get("edgeCount", len(body.get("edges") or [])),
    }


def _safe_vector_snapshot(body: Any) -> dict[str, Any]:
    if not isinstance(body, dict):
        return {"rowCount": 0}
    return {
        "rowCount": body.get("rowCount", 0),
        "collectionName": body.get("collectionName", ""),
        "status": body.get("status", ""),
    }


def _safe_runtime_ai(body: Any) -> dict[str, Any]:
    if not isinstance(body, dict):
        return {}
    runtime_ai = (body.get("ai") or {})
    return {
        "defaultProviderName": runtime_ai.get("defaultProviderName", ""),
        "defaultModelCode": runtime_ai.get("defaultModelCode", ""),
        "enabledProviderCount": runtime_ai.get("enabledProviderCount", 0),
        "benchmarkLlmProvider": BENCHMARK_LLM_PROVIDER,
        "benchmarkLlmModel": BENCHMARK_LLM_MODEL,
    }


def _mcp_call(session: requests.Session, base_url: str, secret: str, tool_name: str, arguments: dict[str, Any]) -> dict[str, Any]:
    response = session.post(
        f"{base_url.rstrip('/')}/api/mcp/tools/{tool_name}/call",
        headers={"Authorization": f"Bearer {secret}"},
        json={"arguments": arguments},
        timeout=90,
    )
    response.raise_for_status()
    payload = response.json()
    return {"toolName": tool_name, "result": payload.get("result", {})}


def _wait_for_sync_job(client: MathAgentClient, document_id: str, job_id: str, timeout_seconds: int = 90) -> dict[str, Any]:
    deadline = time.time() + timeout_seconds
    latest: dict[str, Any] = {}
    while time.time() < deadline:
        attempt = client.get(f"/api/teacher/resources/{document_id}/sync-jobs")
        jobs = attempt.body if isinstance(attempt.body, list) else []
        match = next((job for job in jobs if isinstance(job, dict) and str(job.get("jobId") or "") == job_id), None)
        if match:
            latest = match
            if str(match.get("status") or "").lower() in {"completed", "failed", "paused"}:
                return latest
        time.sleep(2)
    return latest


def _capability_post(client: MathAgentClient, action: str, path: str, body: dict[str, Any] | list[Any], max_cost: float):
    token = _capability(client, action, path, body, max_cost)
    return client.post(path, body, {"X-Capability-Token": token, "X-Request-Hash": stable_request_hash(body)})


def _capability(client: MathAgentClient, action: str, path: str, body: dict[str, Any] | list[Any], max_cost: float) -> str:
    response = client.post("/api/security/capabilities", {
        "action": action,
        "path": path,
        "requestHash": stable_request_hash(body),
        "idempotencyKey": f"deepseek-react-{action}-{int(time.time() * 1000000)}",
        "maxCost": max_cost,
    })
    if response.status != 200 or not isinstance(response.body, dict):
        raise RuntimeError(f"capability failed: {action} {path} HTTP {response.status} {response.body}")
    return str(response.body.get("token", ""))


def _extract_first_json_object(text: str) -> dict[str, Any] | None:
    start = text.find("{")
    end = text.rfind("}")
    if start < 0 or end <= start:
        return None
    candidate = text[start:end + 1]
    if candidate.count("{") > candidate.count("}"):
        candidate += "}" * (candidate.count("{") - candidate.count("}"))
    try:
        parsed = json.loads(candidate)
    except Exception:
        return None
    return parsed if isinstance(parsed, dict) else None


def _sample_size(config: dict[str, Any], key: str, default_value: int) -> int:
    value = config.get(key, default_value)
    if value is None or value == "":
        return default_value
    return max(0, int(value))


def _rate(rows: list[dict[str, Any]], predicate) -> float:
    return sum(1 for row in rows if predicate(row)) / len(rows) if rows else 0.0


def _write_jsonl(path: Path, rows: list[dict[str, Any]]) -> None:
    path.write_text("\n".join(json.dumps(row, ensure_ascii=False) for row in rows) + "\n", encoding="utf-8")


def _append_jsonl(path: Path, row: dict[str, Any]) -> None:
    """Persists one completed runtime stage immediately so an interrupted real run remains diagnosable."""
    with path.open("a", encoding="utf-8") as handle:
        handle.write(json.dumps(row, ensure_ascii=False) + "\n")


def main() -> None:
    parser = argparse.ArgumentParser(description="Run runtime-only DeepSeek+MCP+RAG evaluation without preserving a fixed test set.")
    parser.add_argument("--config", default="benchmarks/config.example.json")
    parser.add_argument("--output-dir", default="")
    args = parser.parse_args()
    config = json.loads(Path(args.config).read_text(encoding="utf-8"))
    output_dir = Path(args.output_dir) if args.output_dir else DEFAULT_OUTPUT_ROOT / f"deepseek-react-rag-{datetime.now().strftime('%Y%m%d-%H%M%S')}"
    metrics = run_eval(config, output_dir)
    print(json.dumps({"outputDir": str(output_dir), **metrics}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
