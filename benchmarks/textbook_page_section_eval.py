"""Compare page and all-book section textbook retrieval."""

from __future__ import annotations

import argparse
import json
import re
import sys
from collections import defaultdict
from datetime import datetime
from pathlib import Path
from typing import Any

import numpy as np

if __package__ in {None, ""}:
    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from benchmarks import textbook_ablation_eval as ablation

DEFAULT_CASES = Path("output/benchmarks/textbook-ablation-body-business-v4/cases.json")
DEFAULT_PAGE_RESULTS = Path("output/benchmarks/textbook-ablation-body-business-v4/results.json")
DEFAULT_PAGE_AUDITS = Path("output/benchmarks/textbook-ablation-body-business-v4/luna_audits.json")
DEFAULT_OUTPUT = Path("output/benchmarks/textbook-page-section-ablation-final")
# The production comparison uses the same c2 root for page evidence and section-child evidence. Keep the explicit name
# here so the benchmark cannot silently select another textbook snapshot.
LEGACY_SECTION_ROOT_NAME = "processed_books_section_shadow_all_mini_c2"
# The Java-shaped hybrid path is the fixed production control.  Every alternate
# route is compared with it using recall values only; no weighted score can hide
# a regression in a secondary cutoff such as document@3.
PRODUCTION_CONTROL_CONFIG = "hybrid_rerank"


def read_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def read_jsonl(path: Path) -> list[dict[str, Any]]:
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def write_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def compact_title(value: Any) -> str:
    """Normalize OCR spacing and page suffixes without changing title semantics."""
    return re.sub(r"\d+\s*$", "", re.sub(r"\s+", "", str(value or "")).lower())


def page_numbers(row: dict[str, Any]) -> set[int]:
    values = row.get("source_page_nos") or row.get("page_nos") or [row.get("page_no")]
    pages: set[int] = set()
    for value in values:
        try:
            pages.add(int(value))
        except (TypeError, ValueError):
            continue
    return pages


def anchor_for_case(case: dict[str, Any]) -> str:
    """Return a fixed business anchor when this is one of the six regression queries."""
    query = str(case.get("query") or "")
    for spec in ablation.BUSINESS_CASE_SPECS:
        if query == str(spec["query"]):
            return str(spec["required_text"])
    return ""


def section_match_score(case: dict[str, Any], candidate: dict[str, Any]) -> tuple[int, list[str]]:
    source = case["source"]
    source_title = compact_title(source.get("section_title"))
    candidate_title = compact_title(candidate.get("section_title"))
    score = 100 if page_numbers(source) & page_numbers(candidate) else 0
    reasons = ["same_page"] if score else []
    if source_title and candidate_title == source_title:
        score += 80
        reasons.append("exact_title")
    elif source_title and (source_title in candidate_title or candidate_title in source_title):
        score += 35
        reasons.append("title_contains")
    anchor = anchor_for_case(case)
    if anchor and anchor in str(candidate.get("text") or ""):
        score += 120
        reasons.append("business_anchor")
    source_text = str(source.get("text") or "")
    candidate_text = str(candidate.get("text") or "")
    if source_text and candidate_text and candidate_text[:80] in source_text:
        score += 25
        reasons.append("text_prefix")
    if candidate.get("chunk_type") == "section_prose":
        score += 5
    return score, reasons


def resolve_section_case(case: dict[str, Any], rows_by_doc: dict[str, list[dict[str, Any]]]) -> tuple[dict[str, Any] | None, dict[str, Any]]:
    """Resolve a page source to an extracted section block and record mapping evidence."""
    doc_id = str(case["source"].get("doc_id") or "")
    candidates = [row for row in rows_by_doc.get(doc_id, []) if page_numbers(case["source"]) & page_numbers(row)]
    fallback_reason = ""
    if not candidates:
        candidates = rows_by_doc.get(doc_id, [])
        fallback_reason = "no_same_page_candidate"
    ranked = sorted(
        ((section_match_score(case, row), row) for row in candidates),
        key=lambda pair: (pair[0][0], len(str(pair[1].get("text") or ""))),
        reverse=True,
    )
    if not ranked or ranked[0][0][0] <= 0:
        return None, {"caseId": case["caseId"], "status": "unmapped", "reason": fallback_reason or "no_title_match"}
    score, reasons = ranked[0][0]
    selected = dict(ranked[0][1])
    return selected, {
        "caseId": case["caseId"],
        "status": "mapped",
        "score": score,
        "reasons": reasons,
        "docId": selected.get("doc_id"),
        "pageNo": selected.get("page_no"),
        "sourcePageNos": selected.get("source_page_nos") or selected.get("page_nos") or [selected.get("page_no")],
        "sectionId": selected.get("section_id"),
        "sectionTitle": selected.get("section_title"),
        "chunkType": selected.get("chunk_type"),
        "candidateCount": len(candidates),
    }


def normalize_audits(raw: Any, configs: tuple[str, ...]) -> dict[str, dict[str, Any]]:
    """Normalize Luna's list/dict envelope and mark missing configuration blocks."""
    items = list(raw.values()) if isinstance(raw, dict) else raw if isinstance(raw, list) else []
    audits: dict[str, dict[str, Any]] = {}
    for item in items:
        if not isinstance(item, dict) or not item.get("caseId"):
            continue
        normalized = dict(item)
        normalized["validScore"] = ablation.score_audit(item.get("validScore", item.get("validCase", 0)))
        normalized["validCase"] = normalized["validScore"] >= 1
        config_items = normalized.get("configs") if isinstance(normalized.get("configs"), dict) else {}
        for alias, canonical in ablation.AUDIT_CONFIG_ALIASES.items():
            if alias in config_items and canonical not in config_items:
                config_items[canonical] = config_items.pop(alias)
        normalized["configs"] = config_items
        missing = [config for config in configs if not isinstance(config_items.get(config), dict)]
        if missing:
            normalized["auditIncomplete"] = True
            normalized["missingConfigs"] = missing
        audits[str(item["caseId"])] = normalized
    return audits


def validate_reused_page_results(results: list[dict[str, Any]], case_ids: set[str]) -> bool:
    expected = len(case_ids) * len(ablation.CONFIGS)
    page = [row for row in results if row.get("corpus") == "page"]
    return len(page) == expected and {str(row.get("caseId")) for row in page} == case_ids and {str(row.get("result", {}).get("config")) for row in page} == set(ablation.CONFIGS)


def normalize_document_ranks(
    result_rows: list[dict[str, Any]],
    sources_by_corpus: dict[str, dict[str, dict[str, Any]]],
) -> None:
    """Apply unique-document ranking to fresh and persisted result snapshots.

    Historical result files preserve full hit lists, so their document ranks can
    be corrected without fabricating a retrieval run.  This makes the report
    comparable to future runs, which calculate the same rank in ``rank_result``.
    """
    for row in result_rows:
        corpus = str(row.get("corpus") or "")
        case_id = str(row.get("caseId") or "")
        source = sources_by_corpus.get(corpus, {}).get(case_id)
        result = row.get("result")
        if source is None or not isinstance(result, dict):
            continue
        before_document_rank = ablation.distinct_document_rank(
            list(result.get("preRerankHits") or []),
            source.get("doc_id"),
        )
        after_document_rank = ablation.distinct_document_rank(
            list(result.get("hits") or []),
            source.get("doc_id"),
        )
        result["beforeRerankDocumentRank"] = before_document_rank
        result["documentRank"] = after_document_rank
        result["documentRankDefinition"] = "distinct_doc_id"
        if result.get("rerankApplied"):
            result["rerankChanged"] = (
                before_document_rank != after_document_rank
                or result.get("beforeRerankPageRank") != result.get("pageRank")
                or result.get("beforeRerankBlockRank") != result.get("blockRank")
            )


def run_corpus(cases: list[dict[str, Any]], corpus: str, index: Any, metadata: list[dict[str, Any]], vectors: np.ndarray, worker: ablation.ProductionWorker, section_mode: bool) -> list[dict[str, Any]]:
    results: list[dict[str, Any]] = []
    for case in cases:
        for config in ablation.CONFIGS:
            result = ablation.rank_result(config, str(case["query"]), case["source"], index, metadata, vectors, worker, section_mode)
            results.append({"caseId": case["caseId"], "corpus": corpus, "result": result})
    return results


def summary_deltas(page: dict[str, Any], section: dict[str, Any]) -> dict[str, Any]:
    fields = ("documentRecall@1", "documentRecall@3", "documentRecall@5", "pageRecall@1", "pageRecall@3", "pageRecall@5", "blockRecall@1", "blockRecall@3", "blockRecall@5", "score100")
    deltas: dict[str, Any] = {}
    for field in fields:
        section_value = section.get(field)
        if section_value is None:
            deltas[field] = None
        else:
            deltas[field] = round(float(section_value) - float(page.get(field, 0.0)), 6)
    return deltas


def production_decisions(section_summaries: dict[str, dict[str, Any]]) -> dict[str, dict[str, Any]]:
    """Accept only section routes that are Pareto-non-regressive against production.

    Document@1 answers whether the right textbook is first, document@3 protects
    fallback coverage, and page/block cutoffs protect the result users actually
    read.  Comparing each field directly avoids a fabricated blended metric or
    a threshold tuned to individual queries.
    """
    control = section_summaries.get(PRODUCTION_CONTROL_CONFIG)
    if control is None:
        raise RuntimeError(f"missing production control summary: {PRODUCTION_CONTROL_CONFIG}")
    fields = (
        "documentRecall@1",
        "documentRecall@3",
        "pageRecall@1",
        "pageRecall@3",
        "blockRecall@1",
        "blockRecall@3",
    )
    decisions: dict[str, dict[str, Any]] = {}
    for config, summary in section_summaries.items():
        regressions = [field for field in fields if float(summary.get(field, 0.0)) < float(control.get(field, 0.0))]
        decisions[config] = {
            "control": PRODUCTION_CONTROL_CONFIG,
            "status": "production_control" if config == PRODUCTION_CONTROL_CONFIG else (
                "accepted_pareto_non_regressive" if not regressions else "rejected_regression"
            ),
            "regressedFields": regressions,
        }
    return decisions


def markdown(report: dict[str, Any], cases: list[dict[str, Any]], output: Path) -> None:
    lines = [
        "# 页级与小标题级全教材检索对比",
        "",
        f"同一批 {len(cases)} 条用例；页库 {report['pageCorpus']['rows']} 行/{report['pageCorpus']['books']} 本，小标题库 {report['sectionCorpus']['rows']} 块/{report['sectionCorpus']['books']} 本。",
        f"审查状态：页级复用已有 Luna；小标题级为 {report['auditStatus']['section']}。小标题级表中的 Luna 分数不作为结论，真实 rerank 前后排名见 results.json。",
        "",
        "| 配置 | 页文档@1/@3 | 小标题文档@1/@3 | 页块@1/@3 | 小标题块@1/@3 | 文档@3差值 | 块@3差值 | 小标题均耗时ms | rerank前后块分 | 生产判定 |",
        "|---|---:|---:|---:|---:|---:|---:|---:|---|---|",
    ]
    for config in ablation.CONFIGS:
        page = report["pageSummaries"][config]
        section = report["sectionSummaries"][config]
        delta = report["deltas"][config]
        luna_cell = (
            f"{section['lunaScoreAvgBefore']['blockScore']:.2f} -> {section['lunaScoreAvg']['blockScore']:.2f}"
            if report["auditStatus"]["section"] == "complete"
            else "未审查"
        )
        decision = report["deploymentDecisions"][config]
        decision_cell = decision["status"] if not decision["regressedFields"] else (
            f"{decision['status']}: {', '.join(decision['regressedFields'])}"
        )
        lines.append(
            f"| {config} | {page['documentRecall@1']:.3f}/{page['documentRecall@3']:.3f} | {section['documentRecall@1']:.3f}/{section['documentRecall@3']:.3f} | {page['blockRecall@1']:.3f}/{page['blockRecall@3']:.3f} | {section['blockRecall@1']:.3f}/{section['blockRecall@3']:.3f} | {delta['documentRecall@3']:+.3f} | {delta['blockRecall@3']:+.3f} | {section['elapsedMs']['avg']:.1f} | {luna_cell} | {decision_cell} |"
        )
    lines.extend(
        [
            "",
            "## 映射",
            "",
            f"成功映射 {report['sectionMapping']['mapped']}/{report['sectionMapping']['total']} 条；未映射不会被补成正确，详见 section_case_mapping.json。",
            "",
            "| caseId | docId | 页码 | 小标题 | 查询 |",
            "|---|---|---:|---|---|",
        ]
    )
    for case in cases:
        source = case["source"]
        mapping = report["sectionMapping"]["items"].get(case["caseId"], {})
        title = str(mapping.get("sectionTitle") or "未映射").replace("|", "\\|")
        query = str(case.get("query") or "").replace("|", "\\|")
        lines.append(f"| {case['caseId']} | {source.get('doc_id')} | {source.get('page_no')} | {title} | {query} |")
    lines.extend(
        [
            "",
            "## 口径",
            "",
            "- BM25 与 BGE 是独立的一阶段召回分支；hybrid 只做候选并集，不把两种原始分数相加。",
            "- doc@1/doc@3/doc@5 按去重后的 docId 判断正确教材是否进入前 1/3/5；page 指标判断页码；block 指标判断小标题块。",
        "- rerank 前后分别保留，rerank 只作用于已召回候选，未改变阶段边界。",
        "- `production_control` 为线上 Java 同预算的 `hybrid_rerank`；只有全部文档、页、块 @1/@3 均不低于它的方案才标记为可接受。任一项回归（包括 document@3）均为 `rejected_regression`，不能作为优化结论。",
        "- 页级基线复用已完成的同一 46 条真实结果；小标题级重新在 c2 全教材块库上执行。",
        ]
    )
    (output / "summary.md").write_text("\n".join(lines) + "\n", encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser(description="Compare page and all-book section textbook retrieval")
    parser.add_argument("--library-parent", type=Path, default=ablation.DEFAULT_LIBRARY_PARENT)
    parser.add_argument("--section-root-name", default=LEGACY_SECTION_ROOT_NAME)
    parser.add_argument("--cases", type=Path, default=DEFAULT_CASES)
    parser.add_argument("--page-results", type=Path, default=DEFAULT_PAGE_RESULTS)
    parser.add_argument("--page-audits", type=Path, default=DEFAULT_PAGE_AUDITS)
    parser.add_argument("--output-dir", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--reuse-results", type=Path, default=None, help="reuse a completed results.json and skip worker retrieval")
    parser.add_argument("--skip-luna", action="store_true", help="record Luna section audit as unavailable without fabricating scores")
    parser.add_argument("--rerun-page", action="store_true", help="re-run page retrieval instead of reusing the frozen baseline")
    args = parser.parse_args()

    parent = args.library_parent.expanduser().resolve()
    output = args.output_dir.expanduser().resolve()
    output.mkdir(parents=True, exist_ok=True)
    cases = read_json(args.cases.expanduser().resolve())
    if len(cases) != 46:
        raise RuntimeError(f"expected the fixed 46-case set, got {len(cases)} from {args.cases}")
    page_root = parent / ablation.DEFAULT_PAGE_ROOT_NAME
    section_root = parent / args.section_root_name
    page_rows = ablation.load_page_rows(parent)
    section_rows: list[dict[str, Any]] = []
    catalog = read_json(section_root / "catalog.json")
    for book in catalog.get("books", []):
        section_rows.extend(read_jsonl(section_root / str(book["doc_id"]) / "jsonl_ai" / "chunks.jsonl"))
    if len({str(row.get("doc_id") or "") for row in section_rows}) != 8:
        raise RuntimeError("section corpus is incomplete: expected 8 textbook documents")

    rows_by_doc: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for row in section_rows:
        rows_by_doc[str(row.get("doc_id") or "")].append(row)
    section_cases: list[dict[str, Any]] = []
    mappings: dict[str, dict[str, Any]] = {}
    for original in cases:
        section_source, mapping = resolve_section_case(original, rows_by_doc)
        mappings[original["caseId"]] = mapping
        if section_source is None:
            raise RuntimeError(f"section mapping failed for {original['caseId']}: {mapping}")
        section_case = dict(original)
        section_case["source"] = section_source
        section_case["pageSource"] = original["source"]
        section_cases.append(section_case)
    write_json(output / "cases.json", cases)
    write_json(output / "section_cases.json", section_cases)
    write_json(output / "section_case_mapping.json", {"total": len(mappings), "mapped": sum(item.get("status") == "mapped" for item in mappings.values()), "items": mappings})

    sys.path.insert(0, str(parent))
    import OCR测试方案.bm25_index as bm25

    page_index = bm25.build_bm25_index(page_rows)
    section_index = bm25.build_bm25_index(section_rows)
    page_metadata, page_vectors = ablation.load_index(page_root)
    section_metadata, section_vectors = ablation.load_index(section_root)
    worker = ablation.ProductionWorker(ablation.WORKER_BASE_URL, ablation.WORKER_KEY_FILE)
    capabilities = worker.capabilities()

    all_results: list[dict[str, Any]] = []
    section_reused = False
    if args.reuse_results and args.reuse_results.expanduser().exists():
        reused_results = read_json(args.reuse_results.expanduser().resolve())
        expected_results = len(cases) * len(ablation.CONFIGS) * 2
        unique_results: dict[tuple[str, str, str], dict[str, Any]] = {}
        for row in reused_results:
            key = (str(row.get("corpus") or ""), str(row.get("caseId") or ""), str(row.get("result", {}).get("config") or ""))
            unique_results.setdefault(key, row)
        if len(unique_results) != expected_results:
            raise RuntimeError(f"reused results do not contain {expected_results} unique page/section rows")
        all_results.extend(unique_results.values())
        page_reused = True
        section_reused = True
    elif not args.rerun_page and args.page_results.expanduser().exists():
        reused_page = read_json(args.page_results.expanduser().resolve())
        case_ids = {str(case["caseId"]) for case in cases}
        if not validate_reused_page_results(reused_page, case_ids):
            raise RuntimeError("frozen page results do not match the fixed 46 cases and 11 configs; pass --rerun-page")
        all_results.extend(row for row in reused_page if row.get("corpus") == "page")
        page_reused = True
    else:
        all_results.extend(run_corpus(cases, "page", page_index, page_metadata, page_vectors, worker, False))
        page_reused = False
    if not section_reused:
        all_results.extend(run_corpus(section_cases, "section", section_index, section_metadata, section_vectors, worker, True))
    normalize_document_ranks(
        all_results,
        {
            "page": {str(case["caseId"]): case["source"] for case in cases},
            "section": {str(case["caseId"]): case["source"] for case in section_cases},
        },
    )
    write_json(output / "results.json", all_results)

    endpoint, api_key, llm_model = ablation.llm_config()
    if args.page_audits.expanduser().exists() and page_reused:
        page_audits = normalize_audits(read_json(args.page_audits.expanduser().resolve()), ablation.CONFIGS)
    else:
        page_audits = normalize_audits(
            ablation.audit_results(cases, [row for row in all_results if row["corpus"] == "page"], endpoint, api_key, llm_model),
            ablation.CONFIGS,
        )
    if args.skip_luna:
        # Keep the real page validity denominator but explicitly mark section
        # audit evidence unavailable; no synthetic config scores are created.
        section_audits = {
            case_id: {
                "caseId": case_id,
                "validScore": page_audits.get(case_id, {}).get("validScore", 0),
                "validCase": bool(page_audits.get(case_id, {}).get("validCase")),
                "auditUnavailable": "luna_section_audit_skipped",
                "configs": {},
            }
            for case_id in {str(case["caseId"]) for case in section_cases}
        }
        section_audit_status = "unavailable"
    else:
        section_audits = normalize_audits(
            ablation.audit_results(section_cases, [row for row in all_results if row["corpus"] == "section"], endpoint, api_key, llm_model),
            ablation.CONFIGS,
        )
        section_audit_status = "complete"
    write_json(output / "luna_audits.json", {"page": page_audits, "section": section_audits})

    page_valid = {case_id for case_id, item in page_audits.items() if item.get("validCase")}
    section_valid = {case_id for case_id, item in section_audits.items() if item.get("validCase")}
    page_summaries = {
        config: ablation.summarize(config, [row for row in all_results if row["corpus"] == "page" and row["result"]["config"] == config], page_audits, page_valid)
        for config in ablation.CONFIGS
    }
    section_summaries = {
        config: ablation.summarize(config, [row for row in all_results if row["corpus"] == "section" and row["result"]["config"] == config], section_audits, section_valid)
        for config in ablation.CONFIGS
    }
    if args.skip_luna:
        for summary in section_summaries.values():
            summary["score100"] = None
            summary["scoreStatus"] = "retrieval_only_luna_unavailable"
            summary.setdefault("scoreComponents", {})["lunaAudit100"] = None
    deployment_decisions = production_decisions(section_summaries)
    report = {
        "kind": "page_vs_all_book_section_ablation",
        "generatedAt": datetime.now().isoformat(timespec="seconds"),
        "caseCount": len(cases),
        "bookCount": len({str(case["source"].get("doc_id") or "") for case in cases}),
        "bookIds": sorted({str(case["source"].get("doc_id") or "") for case in cases}),
        "pageCorpus": {"root": str(page_root), "rows": len(page_rows), "books": len({str(row.get("doc_id") or "") for row in page_rows}), "resultsReused": page_reused},
        "sectionCorpus": {"root": str(section_root), "rows": len(section_rows), "books": len({str(row.get("doc_id") or "") for row in section_rows}), "indexRows": int(section_vectors.shape[0])},
        "sectionMapping": {"total": len(mappings), "mapped": sum(item.get("status") == "mapped" for item in mappings.values()), "items": mappings},
        "validCases": {"page": len(page_valid), "section": len(section_valid), "intersection": len(page_valid & section_valid)},
        "auditStatus": {"page": "reused_existing_luna_audit", "section": section_audit_status},
        "worker": {"baseUrl": ablation.WORKER_BASE_URL, "capabilities": capabilities},
        "pipelineSpecs": [{"name": spec.name, "useBm25": spec.use_bm25, "useBge": spec.use_bge, "useRerank": spec.use_rerank, "parallelRecall": spec.parallel_recall, "graphExpand": spec.graph_expand, "description": spec.description} for spec in ablation.PIPELINE_SPECS],
        "pageSummaries": page_summaries,
        "sectionSummaries": section_summaries,
        "productionControlConfig": PRODUCTION_CONTROL_CONFIG,
        "deploymentDecisions": deployment_decisions,
        "summaries": page_summaries,
        "deltas": {config: summary_deltas(page_summaries[config], section_summaries[config]) for config in ablation.CONFIGS},
        "files": {"cases": str(output / "cases.json"), "sectionCases": str(output / "section_cases.json"), "mapping": str(output / "section_case_mapping.json"), "results": str(output / "results.json"), "audits": str(output / "luna_audits.json")},
        "auditCompleteness": {"pageCases": len(page_audits), "sectionCases": len(section_audits), "requiredConfigs": list(ablation.CONFIGS)},
    }
    write_json(output / "report.json", report)
    markdown(report, cases, output)
    print(json.dumps({"outputDir": str(output), "cases": len(cases), "pageValid": len(page_valid), "sectionValid": len(section_valid), "summaries": section_summaries}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
