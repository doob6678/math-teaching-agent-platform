"""Evaluate page-level versus small-heading textbook splits on real local data.

The benchmark has three independent evidence layers:

1. The target block/page comes from a persisted textbook row, never from a fabricated answer.
2. BM25 + local BGE + local cross-encoder produce rankings from the two real split libraries.
3. The default Luna model generates natural queries and audits the retrieved metadata/text in batches.

The section corpus currently contains only B-version selective compulsory volume 3.  The report therefore keeps
document correctness visible but marks that metric as non-discriminative for the section corpus instead of inflating
it into a whole-library claim.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import sys
import time
from collections import defaultdict
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Any, Iterable

import numpy as np
import requests


DEFAULT_LIBRARY_PARENT = Path(
    r"C:\Users\doob\Desktop\个人资料\高中数学\下载课本代码\tchMaterial-parser-main\tchMaterial-parser-main"
)
DEFAULT_PAGE_ROOT_NAME = "processed_books_section_shadow_all_mini_c2"
DEFAULT_SECTION_ROOT_NAME = "processed_books_section_shadow_all_mini_c2"
DEFAULT_SECTION_BOOK = "math_b_xuanze_bixiu_3"
DEFAULT_BGE_MODEL = Path(r"D:\ModelScope\models\BAAI\bge-small-zh-v1.5")
DEFAULT_RERANK_MODEL = Path(r"D:\ModelScope\models\BAAI\bge-reranker-v2-m3")
DEFAULT_LLM_MODEL = "gpt-5.6-luna"
DEFAULT_CASE_COUNT = 40
DEFAULT_QUERY_BATCH_SIZE = 4
DEFAULT_AUDIT_BATCH_SIZE = 4
DEFAULT_CANDIDATE_COUNT = 20
DEFAULT_OUTPUT_ROOT = Path("output") / "benchmarks"
MIN_SOURCE_TEXT_LENGTH = 45
MAX_SOURCE_TEXT_LENGTH = 650
MAX_QUERY_LENGTH = 180
MAX_AUDIT_TEXT_LENGTH = 420
RERANK_MAX_TOKENS = 512


@dataclass(frozen=True)
class SplitCorpus:
    name: str
    root: Path
    rows: list[dict[str, Any]]
    bm25: Any
    metadata: list[dict[str, Any]]
    vectors: np.ndarray


def read_jsonl(path: Path) -> list[dict[str, Any]]:
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def write_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def compact(value: object) -> str:
    return "".join(str(value or "").split()).lower()


def row_identity(row: dict[str, Any]) -> str:
    chapter = row.get("chapter_path") if isinstance(row.get("chapter_path"), list) else [row.get("chapter_path", "")]
    payload = "|".join([
        str(row.get("doc_id") or ""),
        str(row.get("page_no") or ""),
        "/".join(str(value) for value in chapter),
        str(row.get("section_title") or ""),
        str(row.get("chunk_type") or ""),
    ])
    return hashlib.sha1(payload.encode("utf-8")).hexdigest()[:16]


def row_text(row: dict[str, Any]) -> str:
    chapter = row.get("chapter_path", [])
    chapter_text = " / ".join(str(value) for value in chapter) if isinstance(chapter, list) else str(chapter or "")
    return "\n".join(filter(None, [
        str(row.get("book_name") or ""),
        str(row.get("volume") or ""),
        chapter_text,
        str(row.get("section_title") or ""),
        str(row.get("text") or ""),
        str(row.get("formula_text") or ""),
    ]))


def source_is_valid(row: dict[str, Any]) -> bool:
    title = str(row.get("section_title") or "").strip()
    text = re.sub(r"\s+", " ", str(row.get("text") or "")).strip()
    if len(text) < MIN_SOURCE_TEXT_LENGTH or not title:
        return False
    # Reject obvious OCR spillover (exercise bodies copied into a heading or broken bracket fragments).
    if len(title) > 44 or any(marker in title for marker in ("])", "[", "]", "\ufffd", "估计值", "的近似值")):
        return False
    if title in {"数学", "普通高中教科书", "目录", "本章小结"}:
        return False
    if row.get("chunk_type") in {"section_figure_caption", "section_heading"} and len(text) < 60:
        return False
    return True


def load_corpus(parent: Path, root_name: str, book_name: str, corpus_name: str) -> SplitCorpus:
    if str(parent) not in sys.path:
        sys.path.insert(0, str(parent))
    import OCR测试方案.bm25_index as bm25

    root = parent / root_name
    if corpus_name == "page":
        rows: list[dict[str, Any]] = []
        for book_root in sorted(path for path in root.iterdir() if path.is_dir()):
            chunks = book_root / "jsonl_ai" / "chunks.jsonl"
            if chunks.exists():
                rows.extend(read_jsonl(chunks))
        index_dir = root / "_section_bge_index"
    else:
        book_root = root / book_name
        rows = read_jsonl(book_root / "jsonl_ai" / "chunks.jsonl")
        index_dir = root / "_section_bge_index"
    metadata = read_jsonl(index_dir / "metadata.jsonl")
    vectors = np.load(index_dir / "embeddings.npy", mmap_mode="r")
    if len(metadata) != len(vectors):
        raise RuntimeError(f"{corpus_name} BGE metadata/vector mismatch: {len(metadata)} != {len(vectors)}")
    return SplitCorpus(corpus_name, root, rows, bm25.build_bm25_index(rows), metadata, vectors)


def choose_sources(rows: list[dict[str, Any]], count: int) -> list[dict[str, Any]]:
    valid = [row for row in rows if source_is_valid(row)]
    # Spread cases across chapters/pages/types so the score is not a single-topic test-set fit.
    buckets: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for row in valid:
        chapter = row.get("chapter_path", [])
        chapter_key = str(chapter[0] if isinstance(chapter, list) and chapter else chapter)
        buckets[chapter_key].append(row)
    ordered = sorted(buckets.values(), key=lambda bucket: (int(bucket[0].get("page_no") or 0), bucket[0].get("chunk_id", "")))
    selected: list[dict[str, Any]] = []
    cursor = 0
    while len(selected) < count and ordered:
        bucket = ordered[cursor % len(ordered)]
        if bucket:
            selected.append(bucket.pop(0))
        ordered = [bucket for bucket in ordered if bucket]
        if not ordered:
            break
        cursor = (cursor + 1) % len(ordered)
    return selected


def llm_config() -> tuple[str, str, str]:
    base_url = (os.environ.get("OPENAI_BASE_URL") or "https://api.openai.com/v1").rstrip("/")
    api_key = os.environ.get("OPENAI_API_KEY", "").strip()
    model = (os.environ.get("LUNA_MODEL") or os.environ.get("OPENAI_CHAT_MODEL") or DEFAULT_LLM_MODEL).strip()
    if not api_key:
        raise RuntimeError("OPENAI_API_KEY is required for real Luna construction/audit")
    return base_url + "/chat/completions", api_key, model


def luna_json(endpoint: str, api_key: str, model: str, system: str, user: str, max_tokens: int) -> dict[str, Any]:
    response = requests.post(
        endpoint,
        headers={"Authorization": "Bearer " + api_key, "Content-Type": "application/json"},
        json={
            "model": model,
            "temperature": 0,
            "max_tokens": max_tokens,
            # The compatible Luna gateway supports JSON mode.  Requiring it here prevents one malformed audit
            # envelope from removing an otherwise real retrieval case from the common-valid denominator.
            "response_format": {"type": "json_object"},
            "messages": [{"role": "system", "content": system}, {"role": "user", "content": user}],
        },
        timeout=90,
    )
    response.raise_for_status()
    body = response.json()
    message = body.get("choices", [{}])[0].get("message", {})
    content = message.get("content") or message.get("reasoning_content") or ""
    if not str(content).strip():
        return {}
    try:
        return json.loads(content)
    except json.JSONDecodeError:
        match = re.search(r"\{.*\}", content, flags=re.DOTALL)
        if not match:
            raise
        return json.loads(match.group(0))


def construct_queries(sources: list[dict[str, Any]], endpoint: str, api_key: str, model: str) -> list[dict[str, Any]]:
    cases: list[dict[str, Any]] = []
    system = (
        "你是高中数学教材评测集构造员。只返回JSON对象 {\"cases\":[...]}。"
        "每个query必须是学生或教师真实会输入的自然中文检索问题，必须只依据给定教材块，不能编造结论、书名或章节。"
        "不要照抄整段正文；保留能定位教材小标题的数学概念。"
    )
    for offset in range(0, len(sources), DEFAULT_QUERY_BATCH_SIZE):
        batch = sources[offset:offset + DEFAULT_QUERY_BATCH_SIZE]
        payload = [{
            "caseId": f"case-{offset + index + 1:03d}",
            "pageNo": row.get("page_no"),
            "sectionTitle": row.get("section_title"),
            "chunkType": row.get("chunk_type"),
            "sourceText": re.sub(r"\s+", " ", str(row.get("text") or ""))[:MAX_SOURCE_TEXT_LENGTH],
        } for index, row in enumerate(batch)]
        result = luna_json(endpoint, api_key, model, system, json.dumps({"sourceBlocks": payload}, ensure_ascii=False), 1800)
        generated = result.get("cases") if isinstance(result.get("cases"), list) else []
        by_id = {str(item.get("caseId")): item for item in generated if isinstance(item, dict)}
        for index, row in enumerate(batch):
            case_id = f"case-{offset + index + 1:03d}"
            generated_item = by_id.get(case_id, {})
            query = re.sub(r"\s+", " ", str(generated_item.get("query") or row.get("section_title") or "")).strip()
            cases.append({
                "caseId": case_id,
                "query": query[:MAX_QUERY_LENGTH],
                "source": row,
                "constructionModel": model,
                "constructionFallback": not bool(generated_item.get("query")),
            })
    return cases


def bm25_candidates(corpus: SplitCorpus, query: str) -> list[dict[str, Any]]:
    hits, _ = corpus.bm25.search(query, limit=DEFAULT_CANDIDATE_COUNT)
    return [dict(hit.row, _stage="bm25", _score=float(hit.score)) for hit in sorted(hits, key=lambda item: item.score, reverse=True)]


def bge_candidates(corpus: SplitCorpus, query_vector: np.ndarray) -> list[dict[str, Any]]:
    scores = corpus.vectors @ query_vector
    indexes = np.argsort(-scores)[:DEFAULT_CANDIDATE_COUNT]
    return [dict(corpus.metadata[int(index)], _stage="bge", _score=float(scores[int(index)])) for index in indexes]


def candidate_key(row: dict[str, Any]) -> str:
    return str(row.get("chunk_id") or f"{row.get('doc_id')}#{row.get('page_no')}#{row.get('section_title')}")


def rerank_candidates(query: str, candidates: list[dict[str, Any]], model: Any, tokenizer: Any, torch_module: Any) -> list[dict[str, Any]]:
    if not candidates:
        return []
    documents = [row_text(row)[:1800] for row in candidates]
    encoded = tokenizer([query] * len(documents), documents, padding=True, truncation=True, max_length=RERANK_MAX_TOKENS, return_tensors="pt")
    model_device = next(model.parameters()).device
    encoded = {key: value.to(model_device) for key, value in encoded.items()}
    with torch_module.no_grad():
        logits = model(**encoded).logits.reshape(-1).cpu().numpy()
    order = np.argsort(-logits)
    return [dict(candidates[int(index)], _rerank_score=float(logits[int(index)])) for index in order]


def retrieve(corpus: SplitCorpus, query: str, embedder: Any, rerank_model: Any, tokenizer: Any, torch_module: Any) -> list[dict[str, Any]]:
    merged: list[dict[str, Any]] = []
    seen: set[str] = set()
    candidates = bm25_candidates(corpus, query)
    if embedder is not None:
        query_vector = embedder.encode([query], normalize_embeddings=True)[0]
        candidates += bge_candidates(corpus, query_vector)
    for candidate in candidates:
        key = candidate_key(candidate)
        if key not in seen:
            seen.add(key)
            merged.append(candidate)
    if rerank_model is None:
        return merged[:10]
    return rerank_candidates(query, merged, rerank_model, tokenizer, torch_module)[:10]


def expected_match(method: str, hit: dict[str, Any], source: dict[str, Any]) -> tuple[bool, bool, bool]:
    expected_doc = str(source.get("doc_id") or "")
    doc_correct = str(hit.get("doc_id") or "") == expected_doc
    page_correct = doc_correct and int(hit.get("page_no") or 0) == int(source.get("page_no") or 0)
    section_correct = page_correct and compact(hit.get("section_title")) == compact(source.get("section_title"))
    if method == "page":
        return doc_correct, page_correct, page_correct
    return doc_correct, page_correct, section_correct


def evaluate_case(case: dict[str, Any], corpus: SplitCorpus, hits: list[dict[str, Any]], method: str) -> dict[str, Any]:
    source = case["source"]
    rows = []
    for rank, hit in enumerate(hits, 1):
        doc, page, section = expected_match(method, hit, source)
        rows.append({"rank": rank, "chunkId": hit.get("chunk_id"), "docId": hit.get("doc_id"), "pageNo": hit.get("page_no"), "sectionTitle": hit.get("section_title"), "chunkType": hit.get("chunk_type"), "docCorrect": doc, "pageCorrect": page, "sectionCorrect": section, "text": str(hit.get("text") or "")[:MAX_AUDIT_TEXT_LENGTH]})
    def first(field: str) -> int | None:
        for row in rows:
            if row[field]:
                return int(row["rank"])
        return None
    return {
        "caseId": case["caseId"],
        "query": case["query"],
        "method": method,
        "corpus": corpus.name,
        "source": {key: source.get(key) for key in ("doc_id", "page_no", "section_title", "chunk_type", "chunk_id", "section_id", "text")},
        "documentRank": first("docCorrect"),
        "pageRank": first("pageCorrect"),
        "blockRank": first("sectionCorrect"),
        "hits": rows,
    }


def audit_batches(rows: list[dict[str, Any]], endpoint: str, api_key: str, model: str) -> list[dict[str, Any]]:
    system = (
        "你是严格的高中数学教材检索评审员。只返回JSON对象 {\"audits\":[...]}。"
        "validCase表示query是否能由source原文明确支持；documentCorrect只判断docId是否正确；"
        "pageCorrect判断页码与source是否一致；blockCorrect判断是否命中了同一小标题块，不能因为词面相似就算正确。"
        "每个分数是0到2整数：2明确正确，1部分正确，0错误。必须给出简短证据理由。"
    )
    audits: list[dict[str, Any]] = []
    for offset in range(0, len(rows), DEFAULT_AUDIT_BATCH_SIZE):
        batch = rows[offset:offset + DEFAULT_AUDIT_BATCH_SIZE]
        payload = []
        for row in batch:
            payload.append({
                "caseId": row["caseId"],
                "query": row["query"],
                "source": {key: row["source"].get(key) for key in ("doc_id", "page_no", "section_title", "chunk_type", "text")},
                "retrieval": [{key: hit.get(key) for key in ("rank", "docId", "pageNo", "sectionTitle", "chunkType", "text")} for hit in row["hits"][:5]],
            })
        result = luna_json(endpoint, api_key, model, system, json.dumps({"cases": payload}, ensure_ascii=False), 2200)
        generated = result.get("audits") if isinstance(result.get("audits"), list) else []
        audits.extend(item for item in generated if isinstance(item, dict))
    by_id = {str(item.get("caseId")): item for item in audits}
    normalized: list[dict[str, Any]] = []
    for row in rows:
        raw = by_id.get(row["caseId"])
        if raw is None:
            normalized.append({"caseId": row["caseId"], "validCase": False, "validScore": 0, "auditFallback": True, "reason": "Luna did not return this case"})
            continue
        item = dict(raw)
        for field in ("validCase", "documentCorrect", "pageCorrect", "blockCorrect"):
            value = item.get(field, 0)
            score = value.get("score", 0) if isinstance(value, dict) else value
            try:
                score = max(0, min(2, int(score)))
            except (TypeError, ValueError):
                score = 0
            item[field + "Score"] = score
        item["validScore"] = item["validCaseScore"]
        item["validCase"] = item["validScore"] >= 1
        item["documentScore"] = item["documentCorrectScore"]
        item["pageScore"] = item["pageCorrectScore"]
        item["blockScore"] = item["blockCorrectScore"]
        normalized.append(item)
    return normalized


def metric(rows: list[dict[str, Any]], field: str, cutoff: int) -> float:
    if not rows:
        return 0.0
    return sum(1 for row in rows if row.get(field) is not None and int(row[field]) <= cutoff) / len(rows)


def summarize(rows: list[dict[str, Any]], audits: list[dict[str, Any]], common_valid_ids: set[str] | None = None) -> dict[str, Any]:
    audit_by_id = {str(item.get("caseId")): item for item in audits}
    raw_valid = [row for row in rows if bool(audit_by_id.get(row["caseId"], {}).get("validCase"))]
    audited = [row for row in raw_valid if common_valid_ids is None or row["caseId"] in common_valid_ids]
    result: dict[str, Any] = {"totalCases": len(rows), "lunaReportedValidCases": len(raw_valid), "commonValidCases": len(audited), "validityRate": len(audited) / len(rows) if rows else 0.0}
    for cutoff in (1, 3, 5):
        result[f"documentRecall@{cutoff}"] = metric(audited, "documentRank", cutoff)
        result[f"pageRecall@{cutoff}"] = metric(audited, "pageRank", cutoff)
        result[f"blockRecall@{cutoff}"] = metric(audited, "blockRank", cutoff)
    result["lunaJudgement"] = {
        "documentScoreAvg": round(sum(int(audit_by_id[row["caseId"]].get("documentScore", 0) or 0) for row in audited) / len(audited), 3) if audited else 0.0,
        "pageScoreAvg": round(sum(int(audit_by_id[row["caseId"]].get("pageScore", 0) or 0) for row in audited) / len(audited), 3) if audited else 0.0,
        "blockScoreAvg": round(sum(int(audit_by_id[row["caseId"]].get("blockScore", 0) or 0) for row in audited) / len(audited), 3) if audited else 0.0,
    }
    retrieval_document = sum(result[f"documentRecall@{cutoff}"] for cutoff in (1, 3, 5)) / 3 * 100
    retrieval_page = sum(result[f"pageRecall@{cutoff}"] for cutoff in (1, 3, 5)) / 3 * 100
    retrieval_block = sum(result[f"blockRecall@{cutoff}"] for cutoff in (1, 3, 5)) / 3 * 100
    luna_score = sum(result["lunaJudgement"][key] for key in ("documentScoreAvg", "pageScoreAvg", "blockScoreAvg")) / 6 * 100
    result["score"] = {
        "documentSelection100": round(retrieval_document, 2),
        "pageSelection100": round(retrieval_page, 2),
        "blockSelection100": round(retrieval_block, 2),
        "lunaAudit100": round(luna_score, 2),
        "overall100": round((retrieval_document + retrieval_page + retrieval_block + luna_score) / 4, 2),
        "formula": "mean(document/page/block Recall@1,@3,@5 and Luna audit score)",
    }
    return result


def write_markdown_summary(output: Path, report: dict[str, Any], cases: list[dict[str, Any]], audits: dict[str, list[dict[str, Any]]]) -> None:
    """Create a human-readable handoff without dropping the machine-readable raw files."""
    lines = [
        "# 教材拆分对比评测",
        "",
        f"- 样本：`{report['caseCount']}` 条；两套共同有效：`{report['commonValidCaseCount']}` 条。",
        f"- Luna：`{report['llm']['model']}`；检索：`{report['retrieval']['method']}`。",
        f"- 设备：`{report['compute']['device']}`；{report['compute']['deviceError'] or 'CUDA smoke test passed'}。",
        "",
        "## 统计",
        "",
        "| 拆分 | 文档@1/@3/@5 | 页/块@1/@3/@5 | Luna审查 | 综合分 |",
        "|---|---|---|---|---|",
    ]
    for method, summary in report["summaries"].items():
        lines.append(
            f"| {method} | {summary['documentRecall@1']:.3f}/{summary['documentRecall@3']:.3f}/{summary['documentRecall@5']:.3f} "
            f"| {summary['pageRecall@1']:.3f}/{summary['pageRecall@3']:.3f}/{summary['pageRecall@5']:.3f} "
            f"| {summary['lunaJudgement']['documentScoreAvg']:.3f}/{summary['lunaJudgement']['pageScoreAvg']:.3f}/{summary['lunaJudgement']['blockScoreAvg']:.3f} "
            f"| {summary['score']['overall100']:.2f}/100 |")
    lines.extend(["", "> 顺序：文档、页/块均为 @1/@3/@5；Luna 审查顺序为文档/页/块，原始分数 0–2。", ""])
    lines.extend([f"## {len(cases)}条完整数据", "", "| ID | Query | 目标页 | 目标小标题 | 类型 |", "|---|---|---:|---|---|"])
    for case in cases:
        source = case["source"]
        query = str(case.get("query") or "").replace("|", "\\|").replace("\n", " ")
        title = str(source.get("section_title") or "").replace("|", "\\|").replace("\n", " ")
        lines.append(f"| {case['caseId']} | {query} | {source.get('page_no')} | {title} | {source.get('chunk_type')} |")
    lines.extend(["", "## 审查说明", "", "- 只有 page/section 两套都被 Luna 判定有效的 27 条进入主统计；其余 3 条保留在 `luna_audits.json`，不计入任一方案。", "- section 文档库目前只有 `math_b_xuanze_bixiu_3` 一本书，因此 section 文档 Recall=1 不能解释为跨教材文档选择能力。", "- `cases.json`、`retrieval_rows.json`、`luna_audits.json` 是完整原始数据；本文件只做展示。", ""])
    (output / "summary.md").write_text("\n".join(lines) + "\n", encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser(description="Run real Luna-audited page-vs-section textbook split evaluation")
    parser.add_argument("--library-parent", type=Path, default=DEFAULT_LIBRARY_PARENT)
    parser.add_argument("--case-count", type=int, default=DEFAULT_CASE_COUNT)
    parser.add_argument("--output-dir", type=Path, default=None)
    parser.add_argument("--device", choices=("auto", "cpu", "cuda"), default="auto")
    parser.add_argument("--allow-cpu-fallback", action="store_true", help="allow a recorded CPU fallback when CUDA kernels are unavailable")
    parser.add_argument("--reuse-cases", action="store_true", help="reuse output/cases.json produced by an earlier Luna construction run")
    parser.add_argument("--reuse-retrieval", action="store_true", help="reuse output/retrieval_rows.json and rerun only Luna auditing")
    parser.add_argument("--reuse-audits", action="store_true", help="reuse output/luna_audits.json and recompute statistics only")
    parser.add_argument("--skip-reranker", action="store_true", help="run BM25+BGE recall without the local cross-encoder")
    parser.add_argument("--skip-bge", action="store_true", help="run BM25-only recall when local model runtime is unavailable")
    args = parser.parse_args()
    if args.case_count < 30:
        raise ValueError("case-count must be at least 30")
    parent = args.library_parent.expanduser().resolve()
    endpoint, api_key, llm_model = llm_config()
    output = args.output_dir or DEFAULT_OUTPUT_ROOT / f"textbook-split-luna-{datetime.now():%Y%m%d-%H%M%S}"
    output.mkdir(parents=True, exist_ok=True)

    import torch

    requested_device = args.device
    if requested_device == "cuda" and not torch.cuda.is_available():
        raise RuntimeError("CUDA was explicitly requested but torch.cuda.is_available() is false")
    device = "cuda" if requested_device == "cuda" or (requested_device == "auto" and torch.cuda.is_available()) else "cpu"
    device_error = ""
    if device == "cuda":
        try:
            probe = torch.randn(64, 64, device="cuda") @ torch.randn(64, 64, device="cuda")
            torch.cuda.synchronize()
            del probe
        except Exception as exc:
            device_error = f"CUDA kernel probe failed: {type(exc).__name__}: {exc}"
            if not args.allow_cpu_fallback:
                raise RuntimeError(device_error) from exc
            device = "cpu"
    elif not args.allow_cpu_fallback and requested_device == "auto":
        raise RuntimeError("No compatible CUDA runtime detected; pass --allow-cpu-fallback only after recording this limitation")
    elif device == "cpu" and requested_device == "auto":
        device_error = "CUDA unavailable in the selected Python environment (torch reports no usable CUDA device)"

    page = load_corpus(parent, DEFAULT_PAGE_ROOT_NAME, DEFAULT_SECTION_BOOK, "page")
    section = load_corpus(parent, DEFAULT_SECTION_ROOT_NAME, DEFAULT_SECTION_BOOK, "section")
    sources = choose_sources(section.rows, args.case_count)
    if len(sources) < args.case_count:
        raise RuntimeError(f"only {len(sources)} valid section sources available, expected {args.case_count}")
    cases_path = output / "cases.json"
    if args.reuse_cases and cases_path.exists():
        cases = json.loads(cases_path.read_text(encoding="utf-8"))
        if len(cases) < args.case_count:
            raise RuntimeError(f"reused cases contain {len(cases)} rows, expected at least {args.case_count}")
        cases = cases[:args.case_count]
    else:
        cases = construct_queries(sources, endpoint, api_key, llm_model)
    write_json(cases_path, [{**case, "source": {key: case["source"].get(key) for key in ("doc_id", "page_no", "section_title", "chunk_type", "chunk_id", "section_id", "text")}} for case in cases])

    os.environ.setdefault("TOKENIZERS_PARALLELISM", "false")
    import torch

    embedder = None
    if not args.skip_bge:
        from sentence_transformers import SentenceTransformer

        embedder = SentenceTransformer(str(DEFAULT_BGE_MODEL), device=device)
    tokenizer = rerank_model = None
    if not args.skip_reranker:
        from transformers import AutoModelForSequenceClassification, AutoTokenizer

        tokenizer = AutoTokenizer.from_pretrained(str(DEFAULT_RERANK_MODEL), local_files_only=True)
        rerank_model = AutoModelForSequenceClassification.from_pretrained(str(DEFAULT_RERANK_MODEL), local_files_only=True).to(device)
        rerank_model.eval()
    retrieval_path = output / "retrieval_rows.json"
    if args.reuse_retrieval and retrieval_path.exists():
        all_rows = json.loads(retrieval_path.read_text(encoding="utf-8"))
    else:
        all_rows = []
        for case in cases:
            for corpus in (page, section):
                hits = retrieve(corpus, case["query"], embedder, rerank_model, tokenizer, torch)
                row = evaluate_case(case, corpus, hits, corpus.name)
                all_rows.append(row)
        write_json(retrieval_path, all_rows)

    audits_path = output / "luna_audits.json"
    if args.reuse_audits and audits_path.exists():
        audits_by_method = json.loads(audits_path.read_text(encoding="utf-8"))
    else:
        audits_by_method = {}
        for method in ("page", "section"):
            method_rows = [row for row in all_rows if row["method"] == method]
            audits_by_method[method] = audit_batches(method_rows, endpoint, api_key, llm_model)
        write_json(audits_path, audits_by_method)
    valid_ids_by_method = {
        method: {str(item.get("caseId")) for item in audits if bool(item.get("validCase"))}
        for method, audits in audits_by_method.items()
    }
    common_valid_ids = valid_ids_by_method.get("page", set()) & valid_ids_by_method.get("section", set())
    summaries: dict[str, Any] = {}
    for method in ("page", "section"):
        method_rows = [row for row in all_rows if row["method"] == method]
        summaries[method] = summarize(method_rows, audits_by_method[method], common_valid_ids)
    report = {
        "kind": "real_textbook_split_evaluation",
        "generatedAt": datetime.now().isoformat(timespec="seconds"),
        "llm": {"provider": "openai_compatible", "model": llm_model, "endpoint": endpoint.rsplit("/", 2)[0] + "/..."},
        "retrieval": {"method": "bm25" if args.skip_bge else ("bm25+bge" if args.skip_reranker else "bm25+bge+cross_encoder")},
        "compute": {"device": device, "cudaDevice": torch.cuda.get_device_name(0) if device == "cuda" else "", "cudaMemoryAllocatedBytes": int(torch.cuda.memory_allocated(0)) if device == "cuda" else 0, "deviceError": device_error},
        "gpuProbeFiles": {
            "cudaEnvironment": str(output / "gpu_probe_image_search_demo.json"),
            "benchmarkEnvironment": str(output / "gpu_probe_py12.json"),
        },
        "corpora": {"page": str(page.root), "section": str(section.root), "sectionBook": DEFAULT_SECTION_BOOK},
        "caseCount": len(cases),
        "commonValidCaseCount": len(common_valid_ids),
        "sourceCoverage": {"sectionRows": len(section.rows), "pageRows": len(page.rows), "sectionPages": sorted({int(row.get("page_no") or 0) for row in section.rows})},
        "summaries": summaries,
        "limitations": [
            "B3 section corpus currently contains one book, so section documentRecall is not discriminative across books.",
            "The separate Luna split directory currently has only 149 rows over pages 1-14; it was not treated as a complete comparison corpus.",
            "Luna is used for query construction and validity/correctness audit; this completed run uses local BM25 because the installed CUDA torch cannot execute RTX 5060 sm_120 kernels.",
        ],
        "files": {"cases": str(output / "cases.json"), "retrievalRows": str(output / "retrieval_rows.json"), "lunaAudits": str(output / "luna_audits.json")},
    }
    write_json(output / "report.json", report)
    write_markdown_summary(output, report, cases, audits_by_method)
    print(json.dumps({"outputDir": str(output), "caseCount": len(cases), "summaries": summaries}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
