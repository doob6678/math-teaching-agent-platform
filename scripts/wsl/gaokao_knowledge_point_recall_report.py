#!/usr/bin/env python3
"""Read-only direct knowledge-point recall report for the canonical Gaokao corpus.

Each requested knowledge point is sent to the real embedding service unchanged.  The
knowledge graph is deliberately not used to expand, rewrite, route, or rerank a query.
The report labels its scores as answer-free stem-evidence coverage because the corpus
has no authoritative per-question knowledge-point annotations.
"""
from __future__ import annotations

import argparse
from collections import Counter
import json
from pathlib import Path
import re
import sys
import time
from typing import Any
from urllib.parse import urljoin
import uuid

import requests

import knowledge_point_recall_acceptance as recall

PROJECT_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_CORPUS_ROOT = PROJECT_ROOT / "output" / "math-paper-corpus"
DEFAULT_REPORT_DIR = PROJECT_ROOT / "测试与功能梳理" / "向量库RAG召回报告"
DEFAULT_JSON_OUTPUT = DEFAULT_REPORT_DIR / "高考数学知识点召回原始结果-20260826.json"
DEFAULT_MARKDOWN_OUTPUT = DEFAULT_REPORT_DIR / "高考数学知识点RAG召回报告-20260826.md"
PRODUCTION_REBUILD_REPORT = PROJECT_ROOT / "output" / "acceptance" / "gaokao-production-full-rebuild-20260826.json"
DEFAULT_COLLECTION = "gaokao_math"
TOP_K = 10
REVIEW_BATCH_SIZE = 10
REVIEW_LABELS = frozenset({"relevant", "not_relevant", "uncertain"})

# These queries are the user's requested test cases.  They must be embedded verbatim.
# Evidence terms only form an auditable, answer-free coverage sample; they do not change
# the query and are not authoritative knowledge-point labels.
KNOWLEDGE_POINT_CASES: tuple[dict[str, Any], ...] = (
    {"id": "probability_statistics", "name": "概率统计", "query": "概率统计", "evidenceTerms": ("概率", "随机抽样", "频率", "正态分布", "频数", "相关性")},
    {"id": "trigonometric_functions", "name": "三角函数", "query": "三角函数", "evidenceTerms": ("三角函数", "正弦", "余弦", "正切", "tan", "sin", "cos")},
    {"id": "solving_triangles", "name": "解三角形", "query": "解三角形", "evidenceTerms": ("解三角形", "正弦定理", "余弦定理")},
    {"id": "spatial_vector", "name": "空间向量", "query": "空间向量", "evidenceTerms": ("空间向量", "法向量", "二面角", "直三棱柱", "三棱锥", "四棱锥", "四棱柱")},
    {"id": "derivative", "name": "导数", "query": "导数", "evidenceTerms": ("导数", "导函数", "极值", "单调")},
    {"id": "implicit_zero", "name": "隐零点", "query": "隐零点", "evidenceTerms": ("隐零点", "零点")},
    {"id": "tangent_function", "name": "切线函数", "query": "切线函数", "evidenceTerms": ("正切函数", "正切", "tan")},
    {"id": "ellipse", "name": "椭圆", "query": "椭圆", "evidenceTerms": ("椭圆",)},
    {"id": "hyperbola", "name": "双曲线", "query": "双曲线", "evidenceTerms": ("双曲线",)},
)


class KnowledgePointReportError(RuntimeError):
    """Raised when a direct knowledge-point evaluation violates its read-only contract."""


def _deepseek_endpoint(base_url: str) -> str:
    """Normalize the configured DeepSeek-compatible base URL to its chat endpoint."""
    normalized = base_url.rstrip("/")
    return normalized if normalized.endswith("/chat/completions") else f"{normalized}/chat/completions"


def _review_prompt(case: dict[str, Any], hits: list[dict[str, Any]]) -> list[dict[str, str]]:
    """Build a narrow JSON-only relevance review from answer-free canonical question stems."""
    rows = [
        {
            "rank": hit["rank"],
            "questionId": hit["questionId"],
            "stem": hit["fullStem"],
        }
        for hit in hits
    ]
    instruction = (
        "你是高中数学题库检索审查员。仅根据题干判断每题是否属于指定知识点。"
        "相关填 relevant；明显不是填 not_relevant；题干不足以判定填 uncertain。"
        "不得使用答案、解析、外部资料或推断不存在的内容。理由不超过30个汉字。"
        "只返回 JSON 对象：{\"judgments\":[{\"rank\":number,\"questionId\":string,\"label\":\"relevant|not_relevant|uncertain\",\"reason\":string}]}。"
    )
    return [
        {"role": "system", "content": instruction},
        {"role": "user", "content": json.dumps({"knowledgePoint": case.get("name", case.get("knowledgePoint", "")), "query": case["query"], "questions": rows}, ensure_ascii=False)},
    ]


def _extract_json_object(content: str) -> dict[str, Any]:
    """Accept one JSON object after removing a provider reasoning or Markdown wrapper."""
    candidate = re.sub(r"<think>.*?</think>\s*", "", content.strip(), flags=re.DOTALL | re.IGNORECASE).strip()
    fenced = re.fullmatch(r"```(?:json)?\s*(\{.*\})\s*```", candidate, flags=re.DOTALL | re.IGNORECASE)
    if fenced:
        candidate = fenced.group(1)
    try:
        value = json.loads(candidate)
    except json.JSONDecodeError as error:
        raise KnowledgePointReportError(
            f"DeepSeek review returned invalid JSON after wrapper removal at character {error.pos}"
        ) from error
    if not isinstance(value, dict):
        raise KnowledgePointReportError("DeepSeek review must return a JSON object")
    return value


def _validate_review(case: dict[str, Any], hits: list[dict[str, Any]], payload: dict[str, Any]) -> list[dict[str, Any]]:
    """Require a one-to-one bounded judgment for each displayed result row."""
    raw = payload.get("judgments")
    if not isinstance(raw, list) or len(raw) != len(hits):
        raise KnowledgePointReportError("DeepSeek review must judge every returned Top-10 row exactly once")
    expected = {(int(hit["rank"]), str(hit["questionId"])) for hit in hits}
    judgments: list[dict[str, Any]] = []
    seen: set[tuple[int, str]] = set()
    for item in raw:
        if not isinstance(item, dict):
            raise KnowledgePointReportError("DeepSeek review contains a non-object judgment")
        try:
            key = (int(item.get("rank")), str(item.get("questionId", "")).strip())
        except (TypeError, ValueError) as error:
            raise KnowledgePointReportError("DeepSeek review judgment has an invalid identity") from error
        label = str(item.get("label", "")).strip()
        reason = str(item.get("reason", "")).strip()
        if key not in expected or key in seen or label not in REVIEW_LABELS or not reason or len(reason) > 60:
            raise KnowledgePointReportError("DeepSeek review violates the identity, label, or reason contract")
        seen.add(key)
        judgments.append({"rank": key[0], "questionId": key[1], "label": label, "reason": reason})
    if seen != expected:
        raise KnowledgePointReportError("DeepSeek review did not cover the exact returned Top-10 set")
    return sorted(judgments, key=lambda item: item["rank"])


def review_case_with_deepseek(case: dict[str, Any], hits: list[dict[str, Any]], base_url: str, api_key: str, model: str, timeout: int) -> dict[str, Any]:
    """Call DeepSeek up to three times and accept only a complete validated Top-10 review."""
    if not api_key:
        raise KnowledgePointReportError("DEEPSEEK_API_KEY is required for LLM relevance review")
    started = time.perf_counter()
    last_error: Exception | None = None
    for attempt in range(1, 4):
        try:
            response = requests.post(
                _deepseek_endpoint(base_url),
                headers={"Authorization": f"Bearer {api_key}", "Content-Type": "application/json"},
                json={
                    "model": model,
                    "messages": _review_prompt(case, hits),
                    "temperature": 0,
                    "max_tokens": 4096,
                    "response_format": {"type": "json_object"},
                    "enable_thinking": False,
                },
                timeout=timeout,
            )
            response.raise_for_status()
            body = response.json()
            content = str((body.get("choices") or [{}])[0].get("message", {}).get("content") or "")
            judgments = _validate_review(case, hits, _extract_json_object(content))
            usage = body.get("usage") if isinstance(body.get("usage"), dict) else {}
            return {
                "provider": "deepseek",
                "model": model,
                "reviewAttempts": attempt,
                "reviewLatencyMs": round((time.perf_counter() - started) * 1000, 3),
                "usage": {key: int(usage.get(key, 0) or 0) for key in ("prompt_tokens", "completion_tokens", "total_tokens")},
                "judgments": judgments,
            }
        except (KnowledgePointReportError, requests.RequestException, ValueError, IndexError, KeyError) as error:
            last_error = error
            if attempt < 3:
                time.sleep(attempt)
    raise KnowledgePointReportError("DeepSeek review failed its complete JSON contract after 3 attempts") from last_error


def apply_llm_review(case: dict[str, Any], review: dict[str, Any]) -> None:
    """Attach validated independent review rows and calculate review coverage metrics."""
    by_question = {(item["rank"], item["questionId"]): item for item in review["judgments"]}
    counts: Counter[str] = Counter()
    for hit in case["hits"]:
        judgment = by_question[(hit["rank"], hit["questionId"])]
        hit["llmReview"] = {"label": judgment["label"], "reason": judgment["reason"]}
        counts[judgment["label"]] += 1
    reviewed = counts["relevant"] + counts["not_relevant"] + counts["uncertain"]
    decisive = counts["relevant"] + counts["not_relevant"]
    case["llmReview"] = {
        "provider": review["provider"],
        "model": review["model"],
        "reviewAttempts": review["reviewAttempts"],
        "reviewLatencyMs": review["reviewLatencyMs"],
        "usage": review["usage"],
        "reviewedTop10Count": reviewed,
        "relevantCount": counts["relevant"],
        "notRelevantCount": counts["not_relevant"],
        "uncertainCount": counts["uncertain"],
        "decisivePrecisionAt10": round(counts["relevant"] / decisive, 6) if decisive else None,
        "judgmentCoverageAt10": round(reviewed / len(case["hits"]), 6) if case["hits"] else 0.0,
    }


def _matches_evidence(stem: str, terms: tuple[str, ...]) -> bool:
    """Match transparent evidence terms only against an answer-free canonical stem."""
    normalized = stem.lower()
    return any(term.lower() in normalized for term in terms)


def _public_hit(detail: dict[str, Any], catalog: dict[tuple[str, str], dict[str, Any]], terms: tuple[str, ...]) -> dict[str, Any]:
    """Add canonical answer-free stem and direct evidence status without local paths."""
    result = dict(detail)
    item = catalog.get((str(detail.get("sourceFile", "")), str(detail.get("questionNumber", ""))))
    stem = str(item["stem"]) if item else ""
    result["fullStem"] = stem or None
    result["stemEvidence"] = "matched" if stem and _matches_evidence(stem, terms) else "not_matched"
    return result


def _score_stem_evidence(catalog: dict[tuple[str, str], dict[str, Any]], hits: list[dict[str, Any]], terms: tuple[str, ...], top_k: int) -> dict[str, Any]:
    """Score coverage against all canonical stems matching explicit evidence terms.

    This is deliberately not presented as authoritative topic precision/recall.
    """
    candidates = [
        item for item in catalog.values()
        if _matches_evidence(str(item["stem"]), terms)
    ]
    candidate_ids = {str(item["questionId"]) for item in candidates}
    window = recall.deduplicate_hits(hits)[:top_k]
    ranks: list[int] = []
    for rank, row in enumerate(window, start=1):
        identity = recall.hit_identity(row["hit"])
        if identity["questionId"] in candidate_ids:
            ranks.append(rank)
    hit_count = len(ranks)
    candidate_count = len(candidate_ids)
    returned_count = len(window)
    return {
        "label": "answer_free_stem_evidence_coverage_not_authoritative_labels",
        "evidenceTerms": list(terms),
        "candidateCount": candidate_count,
        "matchedTop10Count": hit_count,
        "coverageAt10": round(hit_count / candidate_count, 6) if candidate_count else None,
        "top10EvidenceShare": round(hit_count / returned_count, 6) if returned_count else None,
        "firstEvidenceRank": ranks[0] if ranks else None,
        "evidenceRanks": ranks,
    }


def _query_case(
    *,
    vector: list[float],
    case: dict[str, Any],
    collection: str,
    uri: str,
    token: str,
    timeout: int,
    manifests: dict[str, tuple[Path, dict[str, Any]]],
    catalog: dict[tuple[str, str], dict[str, Any]],
) -> dict[str, Any]:
    """Run one direct read-only knowledge-point search and record canonical evidence."""
    started = time.perf_counter()
    hits = recall.search_milvus(vector, collection, uri, token, TOP_K, timeout)
    latency_ms = round((time.perf_counter() - started) * 1000, 3)
    raw_duplicates = recall.raw_duplicate_observation(hits, TOP_K)
    unique_rows = recall.deduplicate_hits(hits)
    errors: Counter[str] = Counter()
    details: list[dict[str, Any]] = []
    terms = tuple(case["evidenceTerms"])
    for rank, row in enumerate(unique_rows[:TOP_K], start=1):
        detail, row_errors = recall.audit_hit_contract(row["hit"], rank, row["rawRank"], manifests, catalog, [])
        errors.update(row_errors)
        details.append(_public_hit(detail, catalog, terms))
    return {
        "caseId": case["id"],
        "knowledgePoint": case["name"],
        "query": case["query"],
        "querySentUnchanged": True,
        "searchLatencyMs": latency_ms,
        "returnedHitCount": len(hits),
        "returnedUniqueHitCount": len(unique_rows[:TOP_K]),
        **raw_duplicates,
        "queryStatus": "duplicate_fail" if raw_duplicates["rawTopKDuplicateKeyCount"] else "passed_duplicate_gate",
        "stemEvidenceScore": _score_stem_evidence(catalog, hits, terms, TOP_K),
        "contractErrors": dict(sorted(errors.items())),
        "hits": details,
    }


def _markdown_escape(value: Any) -> str:
    """Keep values inside Markdown tables without altering the recorded JSON evidence."""
    return str(value or "").replace("|", "\\|").replace("\n", " ").strip()


def _write_markdown(report: dict[str, Any], output: Path) -> None:
    """Render every requested direct-query Top-10 with complete answer-free stems."""
    lines = [
        "# 高考数学知识点 RAG 召回报告",
        "",
        "## 口径",
        "",
        "- 本报告仅执行真实 GPU embedding 与 `gaokao_math` 的只读 Top-10 检索；没有图谱扩词、重写查询、rerank 或任何 Milvus 写操作。",
        "- 九个知识点查询按指定文本原样发送。图谱是否存在同名节点不影响检索，也不参与查询构造。",
        "- `题干证据覆盖` 只基于 canonical Markdown 的答案隔离题干与明示证据词，不能替代逐题人工知识点标注。",
        "- 每组 Top-10 已由 DeepSeek 独立按答案隔离题干审查；`相关/不相关/不确定` 与理由不改变 BGE 检索排序。",
        "- `not_matched` 表示该题干未命中当前公开证据词，不等同于 DeepSeek 的相关性结论。",
        f"- corpus：{report['corpus']['manifestCount']} 份试卷、{report['corpus']['questionCount']} 道题；collection：`{report['collection']}`；Top-K：{report['topK']}。",
        "",
        "## 总览",
        "",
        "| 知识点 | 原始重复键 | 检索(ms) | LLM相关/不相关/不确定 | 审查精度 |",
        "|---|---:|---:|---:|---:|",
    ]
    for case in report["knowledgePointCases"]:
        review = case["llmReview"]
        lines.append(f"| {_markdown_escape(case['knowledgePoint'])} | {case['rawTopKDuplicateKeyCount']} | {case['searchLatencyMs']} | {review['relevantCount']}/{review['notRelevantCount']}/{review['uncertainCount']} | {review['decisivePrecisionAt10']} |")
    for case in report["knowledgePointCases"]:
        score = case["stemEvidenceScore"]
        review = case["llmReview"]
        lines.extend([
            "",
            f"## {case['knowledgePoint']}",
            "",
            f"- 查询：`{case['query']}`（原样发送：{case['querySentUnchanged']}）",
            f"- 真实检索耗时：{case['searchLatencyMs']} ms；原始 Top-10 `(sourceFile, questionNumber)` 重复键：{case['rawTopKDuplicateKeyCount']}；状态：`{case['queryStatus']}`。",
            f"- DeepSeek 审查：模型 `{review['model']}`，耗时 {review['reviewLatencyMs']} ms；相关 {review['relevantCount']}，不相关 {review['notRelevantCount']}，不确定 {review['uncertainCount']}，审查精度 {review['decisivePrecisionAt10']}。",
            f"- 题干证据辅助统计：候选题数 {score['candidateCount']}；Top-10 字面证据命中 {score['matchedTop10Count']}；这不是相关性真值。",
            "",
            "### Top-10",
            "",
        ])
        for hit in case["hits"]:
            review_row = hit["llmReview"]
            lines.extend([
                f"#### {hit['rank']}. {hit.get('sourceFile') or '未知来源'} 第 {hit.get('questionNumber') or '?'} 题",
                "",
                f"- DeepSeek 审查：`{review_row['label']}`，理由：{review_row['reason']}；距离：{hit.get('distance')}；资产契约：`{hit.get('assetContract', {}).get('status', 'unknown')}`。",
                "",
                hit.get("fullStem") or hit.get("textSummary") or "[无可读 canonical 题干]",
                "",
            ])
    lines.extend([
        "## 验收限制",
        "",
        "- 原始重复门禁只判断同一 `sourceFile + questionNumber` 是否重复；空白卷与解析卷是不同规范来源记录，报告保留它们的真实排名，不将其伪装为入库重复。",
        "- 当前资产契约错误单独记录在 JSON `contractErrors`，不影响本报告对文本检索、原始重复窗口与耗时的如实记录。",
    ])
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text("\n".join(lines) + "\n", encoding="utf-8")


def run_report(args: argparse.Namespace) -> dict[str, Any]:
    """Generate nine direct-query evidence cases without mutating production data."""
    if args.collection != DEFAULT_COLLECTION:
        raise KnowledgePointReportError("knowledge-point report is restricted to gaokao_math")
    if args.top_k != TOP_K:
        raise KnowledgePointReportError("knowledge-point report fixes top-k to 10")
    started = time.perf_counter()
    manifests = recall.load_manifest_index(args.corpus_root)
    catalog = recall.build_question_catalog(manifests)
    if len(manifests) != 12 or len(catalog) != 250:
        raise KnowledgePointReportError(f"canonical corpus shape changed: manifests={len(manifests)} questions={len(catalog)}")
    dotenv = recall.load_dotenv_values(PROJECT_ROOT / ".env")
    embedding_url = args.embedding_url or recall.configured_value("MATH_AGENT_EMBEDDING_BASE_URL", dotenv, recall.DEFAULT_EMBEDDING_URL)
    milvus_uri = args.milvus_uri or recall.configured_value("MATH_AGENT_VECTOR_INDEX_MILVUS_URI", dotenv, recall.DEFAULT_MILVUS_URI)
    worker_key = recall.configured_value("MATH_AGENT_WORKER_API_KEY", dotenv) or recall.configured_value("MATH_AGENT_EMBEDDING_API_KEY", dotenv)
    token = recall.configured_value("MATH_AGENT_MILVUS_TOKEN", dotenv)
    if not token:
        password = recall.configured_value("MATH_AGENT_MILVUS_ROOT_PASSWORD", dotenv)
        token = f"root:{password}" if password else ""
    queries = [str(case["query"]) for case in KNOWLEDGE_POINT_CASES]
    embedding_started = time.perf_counter()
    vectors = recall.embed_queries(queries, embedding_url, worker_key, args.timeout_seconds)
    embedding_ms = round((time.perf_counter() - embedding_started) * 1000, 3)
    cases = [
        _query_case(vector=vector, case=case, collection=args.collection, uri=milvus_uri, token=token, timeout=args.timeout_seconds, manifests=manifests, catalog=catalog)
        for case, vector in zip(KNOWLEDGE_POINT_CASES, vectors, strict=True)
    ]
    deepseek_base_url = recall.configured_value("DEEPSEEK_BASE_URL", dotenv, "https://api.deepseek.com/v1")
    deepseek_api_key = recall.configured_value("DEEPSEEK_API_KEY", dotenv)
    deepseek_model = recall.configured_value("DEEPSEEK_CHAT_MODEL", dotenv, "deepseek-v4-flash")
    for case in cases:
        review = review_case_with_deepseek(
            case,
            case["hits"],
            deepseek_base_url,
            deepseek_api_key,
            deepseek_model,
            args.review_timeout_seconds,
        )
        apply_llm_review(case, review)
    contract_errors: Counter[str] = Counter()
    for case in cases:
        contract_errors.update(case["contractErrors"])
    duplicate_failures = sum(case["queryStatus"] == "duplicate_fail" for case in cases)
    rebuild = json.loads(PRODUCTION_REBUILD_REPORT.read_text(encoding="utf-8")) if PRODUCTION_REBUILD_REPORT.is_file() else {}
    report = {
        "status": "duplicate_gate_failed" if duplicate_failures else ("asset_contract_failed" if contract_errors else "passed"),
        "runId": f"gaokao-knowledge-point-recall-{uuid.uuid4()}",
        "generatedAtUtc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "durationSeconds": round(time.perf_counter() - started, 3),
        "readOnly": True,
        "collection": args.collection,
        "topK": TOP_K,
        "corpus": {"manifestCount": len(manifests), "questionCount": len(catalog), "selection": "current canonical source-manifest.json and answer-free questions/*.md stems"},
        "embedding": {"model": recall.DEFAULT_EMBEDDING_MODEL, "dimension": recall.VECTOR_DIMENSION, "embeddingRequestLatencyMs": embedding_ms},
        "llmReview": {"provider": "deepseek", "model": deepseek_model, "reviewedCaseCount": len(cases), "scope": "independent relevance review of the returned answer-free Top-10 stems only"},
        "queryConstruction": {"mode": "direct_knowledge_point_queries_only", "graphExpansionApplied": False, "queries": queries},
        "productionRebuild": {key: rebuild.get(key) for key in ("status", "collection", "entityCount", "timingsSeconds", "milvus")},
        "knowledgePointCases": cases,
        "contractErrors": dict(sorted(contract_errors.items())),
        "duplicateFailureCount": duplicate_failures,
        "limitations": [
            "No authoritative per-question knowledge-point labels are published in the canonical corpus.",
            "Stem-evidence coverage is calculated only from answer-free canonical question stems and is not a human relevance verdict.",
            "The report does not use graph terms to expand, rewrite, route, or rerank any query.",
        ],
    }
    secrets = {worker_key, token, deepseek_api_key, recall.configured_value("OPENAI_API_KEY", dotenv)}
    if recall._report_contains_secret(report, secrets):
        raise KnowledgePointReportError("refusing to write a report containing a configured secret")
    args.json_output.parent.mkdir(parents=True, exist_ok=True)
    args.json_output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    _write_markdown(report, args.markdown_output)
    return report


def main() -> int:
    parser = argparse.ArgumentParser(description="Read-only direct Gaokao knowledge-point recall report")
    parser.add_argument("--corpus-root", type=Path, default=DEFAULT_CORPUS_ROOT)
    parser.add_argument("--collection", default=DEFAULT_COLLECTION)
    parser.add_argument("--top-k", type=int, default=TOP_K)
    parser.add_argument("--timeout-seconds", type=int, default=recall.DEFAULT_TIMEOUT_SECONDS)
    parser.add_argument("--review-timeout-seconds", type=int, default=120)
    parser.add_argument("--embedding-url", default="")
    parser.add_argument("--milvus-uri", default="")
    parser.add_argument("--json-output", type=Path, default=DEFAULT_JSON_OUTPUT)
    parser.add_argument("--markdown-output", type=Path, default=DEFAULT_MARKDOWN_OUTPUT)
    args = parser.parse_args()
    try:
        report = run_report(args)
    except (KnowledgePointReportError, recall.AcceptanceError, OSError, ValueError) as error:
        print(f"knowledge-point report failed: {error}", file=sys.stderr)
        return 1
    print(json.dumps({"status": report["status"], "runId": report["runId"], "jsonOutput": str(args.json_output), "markdownOutput": str(args.markdown_output), "durationSeconds": report["durationSeconds"]}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
