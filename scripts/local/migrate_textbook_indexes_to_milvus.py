"""Perform the real, idempotent textbook BGE/CLIP migration into Milvus.

The legacy NPY matrices are read only by this offline migration and recall-comparison command.  Online Java retrieval
uses the two Milvus collections exclusively.  All non-secret endpoints, collection names, dimensions and batch limits
come from backend-java/src/main/resources/application.yml; the optional Milvus token is the sole environment input.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import time
from collections import defaultdict
from pathlib import Path
from typing import Any
from urllib import request

import numpy as np
import yaml


DEFAULT_CONFIG = Path(__file__).resolve().parents[2] / "backend-java" / "src" / "main" / "resources" / "application.yml"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Migrate real textbook BGE and CLIP vectors into Milvus")
    parser.add_argument("--processed-books-root", required=True, type=Path)
    parser.add_argument("--config", type=Path, default=DEFAULT_CONFIG)
    return parser.parse_args()


def main() -> int:
    started = time.perf_counter()
    args = parse_args()
    root = args.processed_books_root.resolve()
    config = vector_config(args.config)
    token = os.environ.get("MATH_AGENT_MILVUS_TOKEN", "").strip()
    corpus_version = str(config["textbook-corpus-version"])
    text_rows, raw_text_row_count = text_entities(root, corpus_version)
    image_rows = image_entities(root, corpus_version, int(config["textbook-image-dimension"]), int(config["textbook-image-query-dimension"]))
    assert_dimension(text_rows, config["textbook-text-dimension"], "BGE text")
    assert_dimension(image_rows, config["textbook-image-dimension"], "CLIP image")
    client = MilvusClient(config["milvus-uri"], token, int(config["textbook-timeout-ms"]))
    summaries = [
        rebuild_collection(client, config, "textbook-text-collection-name", "textbook-text-dimension", text_rows),
        rebuild_collection(client, config, "textbook-image-collection-name", "textbook-image-dimension", image_rows),
    ]
    output = {
        "status": "indexed",
        "processedBooksRoot": str(root),
        "corpusVersion": corpus_version,
        "documentCount": len({row["metadata"]["docId"] for row in text_rows}),
        "textSourceRows": raw_text_row_count,
        "textVectorsSucceeded": len(text_rows),
        "textVectorsFailed": 0,
        "imageVectorsSucceeded": len(image_rows),
        "imageVectorsFailed": 0,
        "collections": summaries,
        "elapsedSeconds": round(time.perf_counter() - started, 3),
    }
    print(json.dumps(output, ensure_ascii=False, indent=2))
    return 0


def vector_config(config_path: Path) -> dict[str, Any]:
    data = yaml.safe_load(config_path.read_text(encoding="utf-8"))
    # application.yml is the deployment contract and therefore contains Spring-style environment placeholders.
    # Resolve them here as the Java process would; sending the literal ${NAME:default} string produces an invalid
    # URL and prevents a fresh Docker Milvus volume from ever receiving the bundled textbook corpus.
    values = {
        key: resolve_spring_placeholder(value, os.environ)
        for key, value in data["math-agent"]["vector-index"].items()
    }
    required = (
        "milvus-uri", "textbook-text-collection-name", "textbook-image-collection-name",
        "textbook-text-dimension", "textbook-image-dimension", "textbook-image-query-dimension", "textbook-metric-type", "textbook-index-type",
        "textbook-top-k", "textbook-upsert-batch-size", "textbook-timeout-ms", "textbook-corpus-version",
    )
    missing = [key for key in required if key not in values or values[key] in (None, "")]
    if missing:
        raise ValueError(f"application.yml is missing required textbook Milvus configuration: {', '.join(missing)}")
    # YAML keeps the provider-neutral Milvus index options as a compact JSON object. Convert it before sending the
    # REST request because Milvus requires an object rather than the YAML scalar itself.
    if isinstance(values.get("textbook-index-params"), str):
        values["textbook-index-params"] = json.loads(values["textbook-index-params"])
    return values


SPRING_PLACEHOLDER = re.compile(r"^\$\{([A-Za-z_][A-Za-z0-9_]*)(?::(.*))?\}$")


def resolve_spring_placeholder(value: Any, environment: dict[str, str]) -> Any:
    """Resolve one complete Spring placeholder while preserving non-string YAML values and nested defaults."""
    if not isinstance(value, str):
        return value
    resolved = value.strip()
    # Nested defaults such as ${A:${B:http://localhost}} are common in application.yml. Bound the loop so malformed
    # operator input fails below as a normal URL/configuration error instead of spinning forever.
    for _ in range(8):
        match = SPRING_PLACEHOLDER.fullmatch(resolved)
        if match is None:
            return resolved
        name, default = match.groups()
        candidate = environment.get(name)
        resolved = candidate.strip() if candidate is not None and candidate.strip() else (default or "").strip()
    raise ValueError(f"Spring placeholder nesting is too deep: {value}")


def text_entities(root: Path, corpus_version: str) -> tuple[list[dict[str, Any]], int]:
    metadata_rows = read_jsonl(root / "_page_text_index" / "metadata.jsonl")
    vectors = np.load(root / "_page_text_index" / "page_embeddings.npy")
    chunks = chunks_by_id(root)
    if len(metadata_rows) != len(vectors):
        raise ValueError("text NPY row count does not match text metadata row count")
    # Milvus primary key is the required chunkId. Parser retries can emit the same logical chunk more than once;
    # retain the richest vector row deterministically instead of silently allowing last-write-wins batch order.
    entities_by_id: dict[str, dict[str, Any]] = {}
    for row, vector in zip(metadata_rows, vectors, strict=True):
        chunk_id = text(row.get("chunk_id"))
        source = chunks.get(chunk_id, {})
        if not chunk_id:
            raise ValueError("text page index contains an empty chunk_id")
        entity = {
            "id": chunk_id,
            "vector": vector.astype(float).tolist(),
            "text": text(row.get("text")),
            "metadata": text_metadata(row, source, corpus_version),
        }
        prior = entities_by_id.get(chunk_id)
        if prior is None or len(entity["text"]) > len(prior["text"]):
            entities_by_id[chunk_id] = entity
    return list(entities_by_id.values()), len(metadata_rows)


def image_entities(root: Path, corpus_version: str, stored_dimension: int, query_dimension: int) -> list[dict[str, Any]]:
    metadata_rows = read_jsonl(root / "_page_image_index" / "metadata.jsonl")
    vectors = np.load(root / "_page_image_index" / "page_embeddings.npy")
    chunks_by_page = first_chunk_by_page(root)
    if len(metadata_rows) != len(vectors):
        raise ValueError("image NPY row count does not match image metadata row count")
    entities = []
    for row, vector in zip(metadata_rows, vectors, strict=True):
        doc_id = text(row.get("doc_id"))
        page_no = int(row.get("page_no") or 0)
        image = text(row.get("source_page_image"))
        if not doc_id or page_no <= 0:
            raise ValueError("image page index contains an empty document or page identity")
        source = chunks_by_page.get((doc_id, page_no), {})
        entities.append({
            "id": f"{doc_id}:p{page_no:04d}:{image or 'page'}",
            "vector": legacy_clip_vector_for_milvus(vector, stored_dimension, query_dimension),
            "text": text(row.get("text")),
            "metadata": {
                "doc_id": doc_id, "docId": doc_id, "book_name": text(row.get("book_name")),
                "bookName": text(row.get("book_name")), "chapter_path": text(row.get("chapter_path")),
                "chapterPath": text(row.get("chapter_path")), "section_id": text(source.get("section_id")),
                "sectionId": text(source.get("section_id")), "page_no": page_no, "pageNo": page_no,
                "source_page_image": image, "sourcePageImage": image, "corpus_version": corpus_version,
                "corpusVersion": corpus_version,
            },
        })
    return entities


def text_metadata(row: dict[str, Any], source: dict[str, Any], corpus_version: str) -> dict[str, Any]:
    chapter_path = row.get("chapter_path") or " / ".join(source.get("chapter_path") or [])
    return {
        "chunk_id": text(row.get("chunk_id")), "chunkId": text(row.get("chunk_id")),
        "doc_id": text(row.get("doc_id")), "docId": text(row.get("doc_id")),
        "book_name": text(row.get("book_name")), "bookName": text(row.get("book_name")),
        "chapter_path": text(chapter_path), "chapterPath": text(chapter_path),
        "section_id": text(row.get("section_id") or source.get("section_id")),
        "sectionId": text(row.get("section_id") or source.get("section_id")),
        "parent_section_id": text(source.get("parent_section_id")), "parentSectionId": text(source.get("parent_section_id")),
        "page_no": int(row.get("page_no") or 0), "pageNo": int(row.get("page_no") or 0),
        "printed_page_no": text(row.get("printed_page_no")), "printedPageNo": text(row.get("printed_page_no")),
        "section_title": text(row.get("section_title")), "sectionTitle": text(row.get("section_title")),
        "chunk_type": text(source.get("chunk_type")), "chunkType": text(source.get("chunk_type")),
        "formula_text": text(source.get("formula_text")), "formulaText": text(source.get("formula_text")),
        "source_path": text(source.get("source_pdf") or source.get("source_page_image")),
        "sourcePath": text(source.get("source_pdf") or source.get("source_page_image")),
        "source_chunk_id": text(row.get("source_chunk_id")), "sourceChunkId": text(row.get("source_chunk_id")),
        "source_page_image": text(row.get("source_page_image")), "sourcePageImage": text(row.get("source_page_image")),
        "corpus_version": corpus_version, "corpusVersion": corpus_version,
    }


def rebuild_collection(client: "MilvusClient", config: dict[str, Any], name_key: str, dim_key: str, entities: list[dict[str, Any]]) -> dict[str, Any]:
    name = str(config[name_key])
    dimension = int(config[dim_key])
    client.ensure_collection(name, dimension)
    # Milvus requires a collection release before replacing its configured vector index; the collection and all
    # entities remain intact throughout this operation.
    client.release(name)
    client.ensure_index(name, str(config["textbook-metric-type"]), str(config["textbook-index-type"]), config.get("textbook-index-params") or {})
    # Milvus accepts entity deletion only after the newly created or existing collection has been loaded.
    client.load(name)
    # The collection contains exactly one textbook corpus. Deleting every current source doc before upsert prevents
    # stale changed/removed chunks from surviving a reparse as ghost vectors while preserving the collection itself.
    for doc_id in sorted({row["metadata"]["docId"] for row in entities}):
        client.delete(name, f'metadata["docId"] == {json.dumps(doc_id, ensure_ascii=False)}')
    # Commit all deletes before inserting the replacement corpus. Without this barrier a restarted upsert can make
    # the entity count temporarily include old and new rows, masking ghost vectors during migration validation.
    client.flush(name)
    client.load(name)
    client.upsert(name, entities, int(config["textbook-upsert-batch-size"]))
    client.flush(name)
    client.load(name)
    count = client.entity_count(name)
    if count != len(entities):
        # Verify primary-key visibility rather than trusting a stale segment count immediately after a large upsert.
        # A retry only contains keys missing from Milvus, so it cannot hide a duplicate/ghost-vector problem.
        visible = client.entity_ids(name, len(entities) + 1)
        missing = [row for row in entities if row["id"] not in visible]
        if missing:
            client.upsert(name, missing, int(config["textbook-upsert-batch-size"]))
            client.flush(name)
            client.load(name)
        count = client.entity_count(name)
    if count != len(entities):
        raise RuntimeError(f"Milvus entity count mismatch for {name}: expected {len(entities)}, got {count}")
    return {"name": name, "entityCount": count, "dimension": dimension, "metricType": config["textbook-metric-type"], "indexType": config["textbook-index-type"]}


class MilvusClient:
    def __init__(self, base_url: str, token: str, timeout_ms: int):
        self.base_url = base_url.rstrip("/")
        self.token = token
        self.timeout = max(1, timeout_ms) / 1000

    def post(self, path: str, body: dict[str, Any]) -> dict[str, Any]:
        headers = {"Content-Type": "application/json", "Request-Timeout": str(max(1, int(self.timeout)))}
        if self.token:
            headers["Authorization"] = f"Bearer {self.token}"
        request_body = json.dumps(body, ensure_ascii=False).encode("utf-8")
        req = request.Request(self.base_url + path, data=request_body, headers=headers, method="POST")
        rate_limit_delays = (12, 24, 36, 48)
        for attempt, delay in enumerate((0, *rate_limit_delays), start=1):
            if delay:
                time.sleep(delay)
            try:
                with request.urlopen(req, timeout=self.timeout) as response:
                    payload = json.loads(response.read().decode("utf-8"))
            except Exception as exc:
                if attempt == len(rate_limit_delays) + 1:
                    raise RuntimeError(f"Milvus {path} request failed") from exc
                continue
            if int(payload.get("code", -1)) == 0:
                return payload
            if int(payload.get("code", -1)) != 1807 or attempt == len(rate_limit_delays) + 1:
                raise RuntimeError(f"Milvus {path} failed: {payload}")
        raise RuntimeError(f"Milvus {path} request exhausted rate-limit retries")

    def ensure_collection(self, name: str, dimension: int) -> None:
        schema = {"autoID": False, "enableDynamicField": False, "fields": [
            {"fieldName": "id", "dataType": "VarChar", "isPrimary": True, "autoID": False, "elementTypeParams": {"max_length": "1024"}},
            {"fieldName": "vector", "dataType": "FloatVector", "elementTypeParams": {"dim": str(dimension)}},
            {"fieldName": "text", "dataType": "VarChar", "elementTypeParams": {"max_length": "65535"}},
            {"fieldName": "metadata", "dataType": "JSON", "isNullable": True},
        ]}
        try:
            self.post("/v2/vectordb/collections/create", {"collectionName": name, "schema": schema})
        except RuntimeError as exc:
            if "exist" not in str(exc).lower():
                raise

    def ensure_index(self, name: str, metric: str, index_type: str, params: Any) -> None:
        # Index parameters are an explicit application.yml contract. Replace an older index in place so a migration
        # cannot silently keep AUTOINDEX after configuration is changed to exact FLAT recall verification.
        try:
            self.post("/v2/vectordb/indexes/drop", {"collectionName": name, "indexName": "vector_index"})
        except RuntimeError as exc:
            if "not found" not in str(exc).lower() and "does not exist" not in str(exc).lower():
                raise
        try:
            self.post("/v2/vectordb/indexes/create", {"collectionName": name, "indexParams": [{"fieldName": "vector", "indexName": "vector_index", "metricType": metric, "indexType": index_type, "params": params}]})
        except RuntimeError as exc:
            if "exist" not in str(exc).lower():
                raise

    def delete(self, name: str, expression: str) -> None:
        self.post("/v2/vectordb/entities/delete", {"collectionName": name, "filter": expression})

    def upsert(self, name: str, entities: list[dict[str, Any]], batch_size: int) -> None:
        for start in range(0, len(entities), max(1, batch_size)):
            self.post("/v2/vectordb/entities/upsert", {"collectionName": name, "data": entities[start:start + max(1, batch_size)]})

    def flush(self, name: str) -> None:
        self.post("/v2/vectordb/collections/flush", {"collectionName": name})

    def load(self, name: str) -> None:
        self.post("/v2/vectordb/collections/load", {"collectionName": name})

    def release(self, name: str) -> None:
        self.post("/v2/vectordb/collections/release", {"collectionName": name})

    def entity_count(self, name: str) -> int:
        data = self.post("/v2/vectordb/entities/query", {"collectionName": name, "filter": 'id >= ""', "outputFields": ["count(*)"]}).get("data", [])
        if not data:
            raise RuntimeError(f"Milvus returned no entity count for {name}")
        return int(data[0].get("count(*)", -1))

    def entity_ids(self, name: str, limit: int) -> set[str]:
        data = self.post("/v2/vectordb/entities/query", {
            "collectionName": name, "filter": 'id >= ""', "outputFields": ["id"], "limit": limit,
        }).get("data", [])
        return {str(row.get("id", "")) for row in data if row.get("id")}


def chunks_by_id(root: Path) -> dict[str, dict[str, Any]]:
    return {text(row.get("chunk_id")): row for path in sorted(root.glob("*/jsonl_ai/chunks.jsonl")) for row in read_jsonl(path) if text(row.get("chunk_id"))}


def first_chunk_by_page(root: Path) -> dict[tuple[str, int], dict[str, Any]]:
    result: dict[tuple[str, int], dict[str, Any]] = {}
    for row in chunks_by_id(root).values():
        key = (text(row.get("doc_id")), int(row.get("page_no") or 0))
        if key[0] and key[1] and key not in result:
            result[key] = row
    return result


def read_jsonl(path: Path) -> list[dict[str, Any]]:
    if not path.is_file():
        raise FileNotFoundError(path)
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def assert_dimension(rows: list[dict[str, Any]], expected: int, label: str) -> None:
    if not rows or len(rows[0]["vector"]) != expected:
        actual = 0 if not rows else len(rows[0]["vector"])
        raise ValueError(f"{label} vector dimension mismatch: expected {expected}, got {actual}")


def text(value: Any) -> str:
    return "" if value is None else str(value).strip()


def legacy_clip_vector_for_milvus(vector: np.ndarray, stored_dimension: int, query_dimension: int) -> list[float]:
    """Matches the former NPY path: common-prefix truncation, L2 normalization, then schema-safe zero padding."""
    if query_dimension <= 0 or stored_dimension < query_dimension or len(vector) < query_dimension:
        raise ValueError("configured CLIP dimensions cannot reproduce the legacy page-index prefix")
    prefix = np.asarray(vector[:query_dimension], dtype=np.float32)
    norm = float(np.linalg.norm(prefix))
    if norm <= 0:
        raise ValueError("page-image index contains a zero-norm CLIP vector")
    normalized = (prefix / norm).astype(float).tolist()
    return normalized + [0.0] * (stored_dimension - query_dimension)


if __name__ == "__main__":
    raise SystemExit(main())
