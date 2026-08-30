"""Build a positive-only teacher-resource oracle from the current live API.

The important rule in this builder is that a block id is meaningful only inside the
current document and its current parser split.  The fixture therefore stores a
split fingerprint and every later report groups block metrics by that fingerprint;
old ids from a different database snapshot are never silently reused.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import sys
import time
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any

if __package__ in {None, ""}:
    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from benchmarks.http_client import MathAgentClient


DEFAULT_BACKEND_URL = "http://127.0.0.1:8080"
DEFAULT_ADMIN_USERNAME = "admin"
DEFAULT_ADMIN_PASSWORD = "admin-123456"
DEFAULT_CASE_COUNT = 100
DEFAULT_CASES_PER_LIBRARY = 20
DEFAULT_REQUEST_INTERVAL_SECONDS = 0.35
MAX_QUERY_CHARS = 120
MIN_QUERY_CHARS = 12
MIN_BLOCK_TEXT_CHARS = 18
TEXT_WINDOW_CHARS = 88
MAX_VARIANTS_PER_BLOCK = 4
ZH_TERM_PATTERN = re.compile(r"[\u4e00-\u9fff]{2,}")
LATIN_TERM_PATTERN = re.compile(r"[A-Za-z][A-Za-z0-9_\\^{}=+*/().-]{2,}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-dir", required=True)
    parser.add_argument("--config", default=os.environ.get("MATH_AGENT_BENCHMARK_CONFIG", ""))
    parser.add_argument("--case-count", type=int, default=DEFAULT_CASE_COUNT)
    parser.add_argument("--cases-per-library", type=int, default=DEFAULT_CASES_PER_LIBRARY)
    parser.add_argument("--timeout", type=float, default=120.0)
    parser.add_argument("--request-interval-seconds", type=float, default=DEFAULT_REQUEST_INTERVAL_SECONDS)
    args = parser.parse_args()

    config = _load_config(Path(args.config) if args.config else None)
    backend_url = os.environ.get("MATH_AGENT_BENCHMARK_BACKEND_URL", config.get("backendBaseUrl", DEFAULT_BACKEND_URL))
    username = os.environ.get("MATH_AGENT_BENCHMARK_ADMIN_USERNAME", config.get("adminUsername", DEFAULT_ADMIN_USERNAME))
    password = os.environ.get("MATH_AGENT_BENCHMARK_ADMIN_PASSWORD", config.get("adminPassword", DEFAULT_ADMIN_PASSWORD))
    output_dir = Path(args.output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)

    client = MathAgentClient(backend_url, timeout=args.timeout)
    client.login(username, password)
    request_interval = max(0.0, float(args.request_interval_seconds))
    last_request_at = 0.0

    def paced_get(path: str, params: dict[str, Any] | None = None):
        nonlocal last_request_at
        now = time.monotonic()
        wait_seconds = request_interval - (now - last_request_at)
        if wait_seconds > 0:
            time.sleep(wait_seconds)
        attempt = client.get(path, params=params)
        last_request_at = time.monotonic()
        return attempt

    resources_attempt = paced_get("/api/teacher/resources")
    if resources_attempt.status != 200 or not isinstance(resources_attempt.body, list):
        raise RuntimeError(f"Cannot read visible teacher resources: HTTP {resources_attempt.status} {resources_attempt.body}")

    roots: list[dict[str, Any]] = []
    resources: list[dict[str, Any]] = []
    blocks_by_document: dict[str, list[dict[str, Any]]] = {}
    for raw_resource in resources_attempt.body:
        if not isinstance(raw_resource, dict):
            continue
        root_id = _text(raw_resource.get("documentId"))
        if not root_id or _text(raw_resource.get("sourceType")).lower() != "feishu":
            continue
        roots.append(raw_resource)
        files_attempt = paced_get(f"/api/teacher/resources/{root_id}/files", params={"limit": 512})
        if files_attempt.status != 200 or not isinstance(files_attempt.body, list):
            raise RuntimeError(f"Cannot read FILE documents for ROOT {root_id}: HTTP {files_attempt.status} {files_attempt.body}")
        for raw_file in files_attempt.body:
            if not isinstance(raw_file, dict):
                continue
            file_id = _text(raw_file.get("documentId"))
            if not file_id:
                continue
            normalized_resource = dict(raw_file)
            normalized_resource["fileDocumentId"] = file_id
            normalized_resource["rootDocumentId"] = _text(raw_file.get("rootDocumentId") or root_id)
            normalized_resource["documentKind"] = "FILE"
            normalized_resource["effectiveLibrary"] = _effective_library(raw_file)
            blocks_attempt = paced_get(
                f"/api/teacher/resources/{file_id}/blocks", params={"limit": 512})
            if blocks_attempt.status != 200 or not isinstance(blocks_attempt.body, list):
                raise RuntimeError(f"Cannot read FILE blocks for {file_id}: HTTP {blocks_attempt.status} {blocks_attempt.body}")
            normalized_resource["blockCount"] = len(blocks_attempt.body)
            normalized_resource["splitFingerprint"] = _text(raw_file.get("splitFingerprint")) or _split_fingerprint(blocks_attempt.body)
            resources.append(normalized_resource)
            blocks_by_document[file_id] = [_normalize_block(block) for block in blocks_attempt.body if isinstance(block, dict)]

    searchable_resources = [resource for resource in resources if _is_production_searchable_resource(resource)]
    if not searchable_resources:
        raise RuntimeError(
            "Current API exposes no searchable physical FILE documents; refusing to build a ROOT-only oracle. "
            "Run the isolated FILE reindex first."
        )
    cases = _build_positive_cases(
        searchable_resources,
        blocks_by_document,
        max(1, min(args.case_count, 1000)),
        max(1, args.cases_per_library),
    )
    if not cases:
        raise RuntimeError("No current production teacher-resource blocks can produce a positive oracle")

    snapshot = {
        "generatedAt": _now(),
        "source": {
            "backendUrl": backend_url,
            "resourcesEndpoint": "/api/teacher/resources",
            "blocksEndpoint": "/api/teacher/resources/{documentId}/blocks",
            "authenticatedAs": username,
            "positiveOnly": True,
        },
        "resources": resources,
        "blocks": blocks_by_document,
    }
    manifest = _build_manifest(resources, searchable_resources, cases)
    (output_dir / "source_snapshot.json").write_text(json.dumps(snapshot, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (output_dir / "dataset_manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (output_dir / "cases.json").write_text(json.dumps({"cases": cases}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(manifest, ensure_ascii=False, indent=2))


def _load_config(path: Path | None) -> dict[str, Any]:
    if path is None or not path.exists():
        return {}
    payload = json.loads(path.read_text(encoding="utf-8-sig"))
    return payload if isinstance(payload, dict) else {}


def _build_positive_cases(
    resources: list[dict[str, Any]],
    blocks_by_document: dict[str, list[dict[str, Any]]],
    case_count: int,
    cases_per_library: int,
) -> list[dict[str, Any]]:
    by_library: dict[str, list[tuple[dict[str, Any], dict[str, Any]]]] = defaultdict(list)
    for resource in resources:
        document_id = _text(resource.get("documentId"))
        library = _text(resource.get("effectiveLibrary"))
        for block in blocks_by_document.get(document_id, []):
            if len(_block_text(block)) >= MIN_BLOCK_TEXT_CHARS:
                by_library[library].append((resource, block))

    cases: list[dict[str, Any]] = []
    for library in sorted(by_library):
        candidates = sorted(
            by_library[library],
            key=lambda pair: (
                _text(pair[0].get("documentId")),
                int(pair[1].get("blockOrder") or 0),
                _text(pair[1].get("blockId")),
            ),
        )
        variants = []
        for resource, block in candidates:
            for variant_type, query in _query_variants(resource, block):
                variants.append((resource, block, variant_type, query))
                if len(variants) >= cases_per_library * 2:
                    break
            if len(variants) >= cases_per_library * 2:
                break
        if not variants:
            continue
        for index in range(min(cases_per_library, len(variants))):
            resource, block, variant_type, query = variants[index]
            cases.append(_case(len(cases) + 1, resource, block, library, variant_type, query))

    # If a small library has fewer distinct blocks, fill the requested total by using different query views of the
    # same real blocks. This keeps every library visible without inventing documents or labels.
    all_variants = []
    for library in sorted(by_library):
        for resource, block in by_library[library]:
            for variant_type, query in _query_variants(resource, block):
                all_variants.append((resource, block, library, variant_type, query))
    variant_index = 0
    while len(cases) < min(case_count, 1000) and all_variants:
        resource, block, library, variant_type, query = all_variants[variant_index % len(all_variants)]
        candidate = _case(len(cases) + 1, resource, block, library, variant_type, query)
        if not any(existing["query"] == candidate["query"] and existing["expected_block_id"] == candidate["expected_block_id"] for existing in cases):
            cases.append(candidate)
        variant_index += 1
        if variant_index >= len(all_variants) * 2 and len(cases) < min(case_count, 1000):
            break
    return cases[:min(case_count, 1000)]


def _query_variants(resource: dict[str, Any], block: dict[str, Any]) -> list[tuple[str, str]]:
    text = _block_text(block)
    title = _text(resource.get("title"))
    section = _text(block.get("section"))
    variants: list[tuple[str, str]] = []
    if section and len(section) >= MIN_QUERY_CHARS:
        variants.append(("section", _clip(section)))
    for term in _terms(text):
        if len(term) >= MIN_QUERY_CHARS:
            variants.append(("term", _clip(term)))
            break
    if text:
        variants.append(("text_span", _clip(text[:TEXT_WINDOW_CHARS])))
    formula_match = re.search(r"(?:\\frac|\\sin|\\cos|\\tan|[A-Za-z]\\s*[=<>]|[一二三四五六七八九十]+次)", text)
    if formula_match:
        start = max(0, formula_match.start() - 18)
        variants.append(("formula_or_symbol", _clip(text[start:start + TEXT_WINDOW_CHARS])))
    if title and section:
        variants.append(("title_section", _clip(f"{title} {section}")))
    deduped: list[tuple[str, str]] = []
    seen: set[str] = set()
    for variant_type, query in variants:
        normalized = _normalize_query(query)
        if len(normalized) < MIN_QUERY_CHARS or normalized in seen:
            continue
        seen.add(normalized)
        deduped.append((variant_type, normalized))
        if len(deduped) >= MAX_VARIANTS_PER_BLOCK:
            break
    return deduped


def _case(case_number: int, resource: dict[str, Any], block: dict[str, Any], library: str, variant_type: str, query: str) -> dict[str, Any]:
    document_id = _text(resource.get("documentId"))
    fingerprint = _text(resource.get("splitFingerprint"))
    return {
        "case_id": f"teacher-current-{case_number:03d}",
        "case_type": "positive",
        "query": query,
        "query_variant": variant_type,
        "expected_document_id": document_id,
        "expected_root_document_id": document_id,
        "expected_file_document_id": _text(resource.get("fileDocumentId")),
        "expected_provider_item_id": _text(resource.get("providerItemId")),
        "expected_source_path": _text(block.get("sourcePath")),
        "expected_block_order": int(block.get("blockOrder") or 0),
        "expected_block_id": _text(block.get("blockId")),
        "expected_library": library,
        "requested_library": library,
        "expected_role": _text(block.get("blockRole")),
        "expected_scope": _text(resource.get("permissionScope")),
        "split_fingerprint": fingerprint,
        "split_group": f"{library}:{document_id}:{fingerprint[:16]}",
        "document_title": _text(resource.get("title")),
        "block_order": int(block.get("blockOrder") or 0),
    }


def _normalize_block(block: dict[str, Any]) -> dict[str, Any]:
    normalized = {key: block.get(key) for key in (
        "blockId", "externalBlockId", "blockType", "blockOrder", "chapter", "section", "pageNo",
        "printedPageNo", "sourcePath", "blockRole", "rawText", "normalizedText", "imageRefs",
        "formulaRefs", "checksum", "status", "confidence", "graphNodeIdsJson", "graphTagNamesJson",
    )}
    normalized["textLength"] = len(_block_text(block))
    return normalized


def _split_fingerprint(blocks: list[dict[str, Any]]) -> str:
    parts = []
    for block in blocks:
        text = _normalize_query(_block_text(block))
        parts.append("|".join((
            _text(block.get("blockType")),
            _text(block.get("blockRole")),
            str(block.get("pageNo") if block.get("pageNo") is not None else ""),
            str(block.get("blockOrder") if block.get("blockOrder") is not None else ""),
            str(len(text)),
            hashlib.sha256(text.encode("utf-8")).hexdigest(),
        )))
    return hashlib.sha256("\n".join(parts).encode("utf-8")).hexdigest()


def _build_manifest(resources: list[dict[str, Any]], searchable_resources: list[dict[str, Any]], cases: list[dict[str, Any]]) -> dict[str, Any]:
    searchable_ids = {_text(resource.get("documentId")) for resource in searchable_resources}
    case_groups = Counter(case["split_group"] for case in cases)
    return {
        "generatedAt": _now(),
        "positiveOnly": True,
        "comparisonRule": "Compare document recall only across distinct corpora; compare block recall only inside the same current split_group.",
        "resourceCount": len(resources),
        "productionSearchableResourceCount": len(searchable_resources),
        "productionSearchableDocumentIds": sorted(searchable_ids),
        "libraries": dict(sorted(Counter(_text(resource.get("effectiveLibrary")) for resource in searchable_resources).items())),
        "splitGroups": dict(sorted(case_groups.items())),
        "caseCount": len(cases),
        "caseCountByLibrary": dict(sorted(Counter(case["expected_library"] for case in cases).items())),
        "caseCountByVariant": dict(sorted(Counter(case["query_variant"] for case in cases).items())),
        "excludedFromRecall": ["negative cases", "historical document ids", "blocks from another parser/chunk snapshot", "cross-library block comparisons"],
    }


def _is_production_searchable_resource(resource: dict[str, Any]) -> bool:
    return all(_text(resource.get(field)).lower() in {"synced", "parsed", "ready"}
               for field in ("syncStatus", "parseStatus", "embeddingStatus", "indexStatus")) and _is_physical_file_resource(resource)


def _is_physical_file_resource(resource: dict[str, Any]) -> bool:
    """A current oracle is valid only for a persisted FILE document, never a shared ROOT row."""
    return bool(_text(resource.get("fileDocumentId")) or _text(resource.get("documentKind")).upper() == "FILE")


def _effective_library(resource: dict[str, Any]) -> str:
    source_type = _text(resource.get("sourceType")).lower()
    if source_type == "textbook":
        return "public_textbook"
    if source_type in {"feishu", "gaokao", "mock_exam", "teacher_resource", "qq_bundle", "public_textbook", "system_reference"}:
        return source_type
    if source_type == "local_path":
        return "teacher_resource"
    return source_type or "teacher_resource"


def _terms(text: str) -> list[str]:
    terms = ZH_TERM_PATTERN.findall(text) + LATIN_TERM_PATTERN.findall(text)
    return sorted(set(terms), key=lambda value: (-len(value), value))


def _block_text(block: dict[str, Any]) -> str:
    return _normalize_query(_text(block.get("normalizedText") or block.get("rawText")))


def _normalize_query(value: str) -> str:
    return re.sub(r"\s+", " ", value.replace("[Markdown image block; no extractable text]", "")).strip()


def _clip(value: str) -> str:
    return _normalize_query(value)[:MAX_QUERY_CHARS].strip()


def _text(value: Any) -> str:
    return "" if value is None else str(value).strip()


def _now() -> str:
    return time.strftime("%Y-%m-%dT%H:%M:%S%z")


if __name__ == "__main__":
    main()
