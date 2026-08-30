#!/usr/bin/env python3
"""Rebuild only gaokao_math from published canonical Markdown with explicit production replacement."""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import time
import uuid
from collections import Counter
from pathlib import Path
from typing import Any

import run_2024_luna_milvus_ingestion as ingestion

PROJECT_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_CORPUS_ROOT = PROJECT_ROOT / "output" / "math-paper-corpus"
DEFAULT_REPORT = PROJECT_ROOT / "output" / "acceptance" / "gaokao-production-full-rebuild-20260826.json"
PRODUCTION_COLLECTION = "gaokao_math"
UPSERT_BATCH_SIZE = 100
PARABOLA_QUERY = "抛物线 焦点 准线"


def timed(stage_timings: dict[str, float], name: str, operation: Any) -> Any:
    """Record wall time for a completed production stage without hiding failures."""
    started = time.perf_counter()
    result = operation()
    stage_timings[name] = round(time.perf_counter() - started, 3)
    return result


def sha256_file(path: Path) -> str:
    """Hash an already-published canonical file in bounded reads."""
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def canonical_records(corpus_root: Path) -> tuple[list[dict[str, Any]], int, int]:
    """Load verified canonical documents/questions and reject duplicate source/question keys."""
    records: list[dict[str, Any]] = []
    source_question_keys: set[tuple[str, str]] = set()
    manifest_paths = sorted(corpus_root.glob("*/source-manifest.json"))
    if not manifest_paths:
        raise RuntimeError("canonical corpus has no source manifests")
    for manifest_path in manifest_paths:
        paper_root = manifest_path.parent
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        source = str(manifest.get("documentFullName", "")).strip()
        source_sha256 = str(manifest.get("sourceSha256", "")).strip()
        if not source or not re.fullmatch(r"[0-9a-f]{64}", source_sha256):
            raise RuntimeError(f"invalid canonical manifest: {manifest_path}")
        document_relative = str(manifest.get("documentMarkdown", ""))
        document_path = paper_root / document_relative
        if not document_path.is_file() or hashlib.sha256(document_path.read_bytes()).hexdigest() != manifest.get("documentMarkdownSha256"):
            raise RuntimeError(f"document Markdown hash check failed: {source}")
        document_ref = str(uuid.uuid5(uuid.NAMESPACE_URL, f"{source}\n{source_sha256}"))
        records.append({
            "id": str(uuid.uuid5(uuid.NAMESPACE_URL, f"document\n{source}\n{source_sha256}")),
            "text": document_path.read_text(encoding="utf-8"),
            "metadata": {
                "recordType": "FULL_DOCUMENT",
                "documentFullName": source,
                "documentRef": document_ref,
                "sourceSha256": source_sha256,
                "extraction": "TERRA_VISUAL_PAGE",
            },
        })
        for question in manifest.get("questions", []):
            number = str(question.get("questionNumber", "")).strip()
            expected_id = ingestion.canonical_question_id(source_sha256, number)
            if question.get("questionId") != expected_id:
                raise RuntimeError(f"manifest stable question ID mismatch: {source} #{number}")
            source_question_key = (source, number)
            if source_question_key in source_question_keys:
                raise RuntimeError(f"duplicate canonical source/question: {source} #{number}")
            source_question_keys.add(source_question_key)
            markdown_relative = str(question.get("questionMarkdown", ""))
            if not markdown_relative.startswith("questions/") or ".." in markdown_relative.split("/"):
                raise RuntimeError(f"unsafe question Markdown path: {source} #{number}")
            markdown_path = paper_root / markdown_relative
            if not markdown_path.is_file() or sha256_file(markdown_path) != question.get("questionMarkdownSha256"):
                raise RuntimeError(f"question Markdown hash check failed: {source} #{number}")
            assets = [{
                key: asset[key]
                for key in ("assetId", "assetSha256", "sourceSha256", "pageNumber", "bboxPixels", "bindingMethod")
                if key in asset
            } for asset in question.get("assets", [])]
            records.append({
                "id": expected_id,
                "text": markdown_path.read_text(encoding="utf-8"),
                "metadata": {
                    "recordType": "QUESTION",
                    "sourceFile": source,
                    "documentFullName": source,
                    "documentRef": document_ref,
                    "sourceSha256": source_sha256,
                    "questionNumber": number,
                    "sourcePages": question.get("sourcePages", []),
                    "crossPageContinuity": question.get("crossPageContinuity", {}),
                    "assetIds": question.get("assetIds", []),
                    "assets": assets,
                    "extraction": "TERRA_VISUAL_PAGE",
                },
            })
    return records, len(manifest_paths), len(source_question_keys)


def hit_value(hit: dict[str, Any], field: str) -> Any:
    """Read a field from either deployed Milvus REST hit envelope."""
    entity = hit.get("entity", {}) if isinstance(hit.get("entity"), dict) else {}
    return hit.get(field, entity.get(field))


def hit_metadata(hit: dict[str, Any]) -> dict[str, Any]:
    """Parse the JSON metadata field returned by the deployed Milvus REST gateway."""
    raw = hit_value(hit, "metadata") or {}
    return json.loads(raw) if isinstance(raw, str) else raw


def validate_parabola_window(uri: str, token: str, embedding_url: str, worker_key: str) -> dict[str, Any]:
    """Fail closed if raw parabola top-10 repeats a canonical source/question identity."""
    query_vector = ingestion.embed_all([PARABOLA_QUERY], embedding_url, worker_key, 120)[0]
    response = ingestion.milvus_post(uri, token, "/v2/vectordb/entities/search", {
        "collectionName": PRODUCTION_COLLECTION,
        "data": [query_vector],
        "annsField": ingestion.VECTOR_FIELD,
        "limit": 10,
        "outputFields": ["id", "text", "metadata"],
    }, 60)
    hits = ingestion.search_hits(response)
    question_keys: list[tuple[str, str]] = []
    result_hits: list[dict[str, Any]] = []
    for rank, hit in enumerate(hits, start=1):
        metadata = hit_metadata(hit)
        result_hits.append({
            "rank": rank,
            "id": hit_value(hit, "id"),
            "recordType": metadata.get("recordType"),
            "sourceFile": metadata.get("sourceFile"),
            "questionNumber": metadata.get("questionNumber"),
            "textPreview": str(hit_value(hit, "text") or "")[:180],
        })
        if metadata.get("recordType") == "QUESTION":
            source_file = str(metadata.get("sourceFile", ""))
            number = str(metadata.get("questionNumber", ""))
            if not source_file or not number:
                raise RuntimeError(f"parabola query returned incomplete question identity: {metadata}")
            question_keys.append((source_file, number))
    duplicates = {
        f"{source_file}#{number}": count
        for (source_file, number), count in Counter(question_keys).items()
        if count > 1
    }
    if duplicates:
        raise RuntimeError(f"parabola raw top-10 has duplicate source/question rows: {duplicates}")
    return {"query": PARABOLA_QUERY, "limit": 10, "hits": result_hits, "questionHitCount": len(question_keys), "duplicateSourceQuestionKeys": duplicates}


def main() -> None:
    parser = argparse.ArgumentParser(description="Validate or explicitly replace gaokao_math from canonical Markdown")
    parser.add_argument("--corpus-root", type=Path, default=DEFAULT_CORPUS_ROOT)
    parser.add_argument("--report", type=Path, default=DEFAULT_REPORT)
    parser.add_argument("--replace-production", action="store_true", help="drop and rebuild gaokao_math after all validation and embedding succeed")
    arguments = parser.parse_args()
    timings: dict[str, float] = {}
    records, manifest_count, question_count = timed(timings, "canonicalValidationSeconds", lambda: canonical_records(arguments.corpus_root))
    if manifest_count != 12 or question_count != 250 or len(records) != 262:
        raise RuntimeError(f"canonical corpus shape changed: manifests={manifest_count} questions={question_count} records={len(records)}")
    dotenv = ingestion.load_dotenv(PROJECT_ROOT / ".env")
    worker_key = ingestion.setting("MATH_AGENT_WORKER_API_KEY", dotenv)
    embedding_url = ingestion.setting("MATH_AGENT_EMBEDDING_BASE_URL", dotenv, ingestion.DEFAULT_EMBEDDING_URL)
    uri = ingestion.setting("MATH_AGENT_VECTOR_INDEX_MILVUS_URI", dotenv, ingestion.DEFAULT_MILVUS_URI)
    token = ingestion.setting("MATH_AGENT_MILVUS_TOKEN", dotenv) or ("root:" + ingestion.setting("MATH_AGENT_MILVUS_ROOT_PASSWORD", dotenv) if ingestion.setting("MATH_AGENT_MILVUS_ROOT_PASSWORD", dotenv) else "")
    vectors = timed(timings, "embeddingSeconds", lambda: ingestion.embed_all([record["text"] for record in records], embedding_url, worker_key, 120))
    if len(vectors) != len(records):
        raise RuntimeError("real embedding response count mismatch")
    entities = [{
        ingestion.PRIMARY_KEY_FIELD: record["id"],
        ingestion.VECTOR_FIELD: vector,
        ingestion.TEXT_FIELD: record["text"],
        ingestion.METADATA_FIELD: ingestion.vector_metadata(record),
    } for record, vector in zip(records, vectors, strict=True)]
    report: dict[str, Any] = {
        "status": "validated_only",
        "collection": PRODUCTION_COLLECTION,
        "corpusRoot": str(arguments.corpus_root),
        "manifestCount": manifest_count,
        "questionCount": question_count,
        "entityCount": len(entities),
        "embedding": {"model": ingestion.DEFAULT_EMBEDDING_MODEL, "dimension": ingestion.VECTOR_DIMENSION, "batchSize": ingestion.EMBEDDING_BATCH_SIZE},
        "timingsSeconds": timings,
    }
    if arguments.replace_production:
        def drop_collection() -> bool:
            exists = ingestion.milvus_post(uri, token, "/v2/vectordb/collections/has", {"collectionName": PRODUCTION_COLLECTION}, 60).get("data", {}).get("has", False)
            if exists:
                ingestion.milvus_post(uri, token, "/v2/vectordb/collections/drop", {"collectionName": PRODUCTION_COLLECTION}, 60)
            return bool(exists)
        dropped = timed(timings, "dropCollectionSeconds", drop_collection)
        timed(timings, "createAndLoadSeconds", lambda: ingestion.ensure_collection(uri, token, PRODUCTION_COLLECTION, 60))
        batches = timed(timings, "upsertSeconds", lambda: ingestion.milvus_upsert_batches(uri, token, PRODUCTION_COLLECTION, entities, UPSERT_BATCH_SIZE, 120))
        timed(timings, "flushSeconds", lambda: ingestion.milvus_post(uri, token, "/v2/vectordb/collections/flush", {"collectionName": PRODUCTION_COLLECTION}, 60))
        source_names = sorted({
            str(record["metadata"].get("sourceFile") or record["metadata"].get("documentFullName") or "")
            for record in records
        })
        if not source_names or any(not source_name for source_name in source_names):
            raise RuntimeError("canonical replacement has an incomplete source identity")

        def count_rebuilt_entities() -> tuple[int, dict[str, int]]:
            source_counts = {
                source_name: ingestion.milvus_query_count(
                    uri, token, PRODUCTION_COLLECTION, ingestion.milvus_source_filter(source_name), 60)
                for source_name in source_names
            }
            return sum(source_counts.values()), source_counts

        total, source_counts = timed(timings, "countSeconds", count_rebuilt_entities)
        if total != len(entities):
            raise RuntimeError(f"production entity count mismatch after rebuild: expected={len(entities)} actual={total}")
        parabola = timed(timings, "parabolaTop10Seconds", lambda: validate_parabola_window(uri, token, embedding_url, worker_key))
        report.update({
            "status": "production_rebuild_passed",
            "milvus": {"collectionDropped": dropped, "upsertBatchSize": UPSERT_BATCH_SIZE, "upsertBatchCount": batches, "flushCount": 1, "entityCountAfterRebuild": total, "sourceCounts": source_counts},
            "parabolaTop10": parabola,
        })
    arguments.report.parent.mkdir(parents=True, exist_ok=True)
    arguments.report.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
