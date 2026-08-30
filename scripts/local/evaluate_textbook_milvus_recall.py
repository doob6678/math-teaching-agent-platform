"""Compare the c2 local section baseline with real production Milvus recall using identical worker query vectors."""
from __future__ import annotations

import argparse
import json
import os
import statistics
import time
from pathlib import Path
from typing import Any
from urllib import request

import numpy as np
import yaml

from scripts.local.migrate_textbook_indexes_to_milvus import production_text_index_paths


ROOT = Path(__file__).resolve().parents[2]


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--processed-books-root", required=True, type=Path)
    parser.add_argument(
        "--graph-spine",
        type=Path,
        default=ROOT / "backend-java" / "src" / "main" / "resources" / "knowledge" / "graph-spine-v0.1.md",
        help="UTF-8 knowledge-point data source used to build the real evaluation query set",
    )
    # 2026-08-30 文档清理后，历史验证证据统一归档在 archive/ 下，新产出默认也写入这里。
    parser.add_argument(
        "--output",
        type=Path,
        default=ROOT / "archive" / "2026-08-30-doc-cleanup" / "acceptance-evidence" / "textbook-milvus-recall-verification.json",
    )
    args = parser.parse_args()
    cfg = yaml.safe_load((ROOT / "backend-java/src/main/resources/application.yml").read_text(encoding="utf-8"))["math-agent"]["vector-index"]
    worker_key = worker_key_from_local_secret()
    # Local WSL Milvus is unauthenticated; a token remains optional for remote deployments.
    token = os.environ.get("MATH_AGENT_MILVUS_TOKEN", "").strip()
    root = args.processed_books_root.resolve()
    queries = tuple(load_graph_knowledge_points(args.graph_spine))
    if not queries:
        raise RuntimeError(f"No knowledge points were found in graph spine: {args.graph_spine}")
    text = evaluate_route(root, cfg, worker_key, token, "text", queries)
    image = evaluate_route(root, cfg, worker_key, token, "image", queries)
    result = {
        "status": "verified",
        "querySource": str(args.graph_spine.resolve()),
        "knowledgePointCount": len(queries),
        "queries": list(queries),
        "text": text,
        "image": image,
    }
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0


def load_graph_knowledge_points(path: Path) -> list[str]:
    """Builds the benchmark from the maintained knowledge-point data, not code-level query fixtures."""
    if not path.is_file():
        raise FileNotFoundError(f"knowledge-point source does not exist: {path}")
    points: list[str] = []
    seen: set[str] = set()
    prefix = "- 知识点："
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line.strip().startswith(prefix):
            continue
        values = line.strip()[len(prefix):]
        for value in values.replace("，", "、").split("、"):
            point = " ".join(value.split()).strip(" 。；;")
            if point and point not in seen:
                seen.add(point)
                points.append(point)
    return points


def evaluate_route(root: Path, cfg: dict[str, Any], worker_key: str, token: str, route: str, queries: tuple[str, ...]) -> dict[str, Any]:
    if route == "text":
        # Benchmark the exact c2 section-child index used by production migration. Comparing Milvus with a page-only
        # summary index would report a false pass because query vectors could be correct while candidate identities
        # belonged to a different corpus contract.
        metadata_path, vector_path, index_kind = production_text_index_paths(root)
        metadata = read_jsonl(metadata_path)
        vectors = np.load(vector_path).astype(np.float32)
        collection = cfg["textbook-text-collection-name"]
        dimension = int(cfg["textbook-text-dimension"])
        endpoint = "/v1/embeddings"
        payload = lambda query: {"model": cfg["embedding-model"], "input": [query]}
        prepare = lambda vector: normalized(vector)
    else:
        metadata = read_jsonl(root / "_page_image_index/metadata.jsonl")
        vectors = np.load(root / "_page_image_index/page_embeddings.npy").astype(np.float32)
        collection = cfg["textbook-image-collection-name"]
        dimension = int(cfg["textbook-image-dimension"])
        shared = int(cfg["textbook-image-query-dimension"])
        vectors = np.array([pad(normalized(row[:shared]), dimension) for row in vectors], dtype=np.float32)
        endpoint = "/v1/clip/text-embeddings"
        payload = lambda query: {"input": [query]}
        prepare = lambda vector: pad(normalized(vector), dimension)
    local_times: list[float] = []
    milvus_times: list[float] = []
    comparisons = []
    for query in queries:
        vector = worker_embedding(endpoint, payload(query), worker_key)
        query_vector = prepare(np.asarray(vector, dtype=np.float32))
        start = time.perf_counter()
        baseline_ids = rank_local(metadata, vectors, query_vector, 10, route)
        local_times.append((time.perf_counter() - start) * 1000)
        start = time.perf_counter()
        milvus_ids = milvus_search(cfg["milvus-uri"], token, collection, query_vector.tolist(), 10)
        milvus_times.append((time.perf_counter() - start) * 1000)
        overlap = len(set(baseline_ids) & set(milvus_ids))
        rank = (milvus_ids.index(baseline_ids[0]) + 1) if baseline_ids and baseline_ids[0] in milvus_ids else 0
        comparisons.append({"query": query, "baselineTop10": baseline_ids, "milvusTop10": milvus_ids,
                            "recallAt10": overlap / max(1, len(baseline_ids)), "reciprocalRank": 0 if not rank else 1 / rank,
                            "top1Match": bool(baseline_ids and milvus_ids and baseline_ids[0] == milvus_ids[0]),
                            "top3ContainsBaselineTop1": bool(baseline_ids and baseline_ids[0] in milvus_ids[:3]),
                            "top5ContainsBaselineTop1": bool(baseline_ids and baseline_ids[0] in milvus_ids[:5])})
    return {"collection": collection, "dimension": dimension, "indexKind": index_kind if route == "text" else "page_images",
            "recallAt10": mean(comparisons, "recallAt10"),
            "mrr": mean(comparisons, "reciprocalRank"), "top1": mean_bool(comparisons, "top1Match"),
            "top3": mean_bool(comparisons, "top3ContainsBaselineTop1"),
            "top5": mean_bool(comparisons, "top5ContainsBaselineTop1"),
            "sectionTitleHitRate": mean_bool(comparisons, "top1Match"),
            "localNpyLatencyMs": percentile_summary(local_times), "milvusSearchLatencyMs": percentile_summary(milvus_times), "cases": comparisons}


def rank_local(metadata: list[dict[str, Any]], vectors: np.ndarray, query: np.ndarray, limit: int, route: str) -> list[str]:
    scores = vectors @ query
    order = np.argsort(-scores)
    result: list[str] = []
    seen: set[str] = set()
    for index in order.tolist():
        item = metadata[index]
        key = text(item.get("chunk_id")) if route == "text" else f"{text(item.get('doc_id'))}:p{int(item.get('page_no') or 0):04d}:{text(item.get('source_page_image')) or 'page'}"
        # Match the online text path's primary-key/section collapse rather than inflating recall with parser duplicates.
        if route == "text":
            key = text(item.get("chunk_id"))
        if key and key not in seen:
            seen.add(key); result.append(key)
        if len(result) == limit: break
    return result


def worker_embedding(path: str, payload: dict[str, Any], key: str) -> list[float]:
    return post("http://127.0.0.1:8091" + path, payload, {"Authorization": "Bearer " + key})["data"][0]["embedding"]


def milvus_search(uri: str, token: str, collection: str, vector: list[float], limit: int) -> list[str]:
    headers = {"Authorization": "Bearer " + token} if token else {}
    data = post(uri.rstrip("/") + "/v2/vectordb/entities/search", {"collectionName": collection, "data": [vector], "limit": limit,
        "outputFields": ["id"], "searchParams": {"metricType": "COSINE", "params": {}}}, headers)["data"]
    return [str(row["id"]) for row in data]


def post(url: str, payload: dict[str, Any], headers: dict[str, str]) -> dict[str, Any]:
    req = request.Request(url, data=json.dumps(payload, ensure_ascii=False).encode("utf-8"), headers={"Content-Type": "application/json", **headers}, method="POST")
    with request.urlopen(req, timeout=120) as response: result = json.loads(response.read().decode("utf-8"))
    if result.get("code", 0) != 0: raise RuntimeError(result)
    return result


def worker_key_from_local_secret() -> str:
    key = os.environ.get("MATH_AGENT_WORKER_API_KEY") or os.environ.get("MATH_AGENT_EMBEDDING_API_KEY")
    if key: return key
    secret = ROOT / ".local-secrets" / "worker-api-key.txt"
    return secret.read_text(encoding="utf-8").strip()


def normalized(vector: np.ndarray) -> np.ndarray:
    norm = float(np.linalg.norm(vector))
    if norm <= 0: raise ValueError("zero-norm vector")
    return vector / norm


def pad(vector: np.ndarray, dimension: int) -> np.ndarray:
    if len(vector) > dimension: raise ValueError("vector exceeds configured Milvus dimension")
    return np.pad(vector, (0, dimension - len(vector)))


def read_jsonl(path: Path) -> list[dict[str, Any]]:
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def text(value: Any) -> str: return "" if value is None else str(value).strip()
def mean(rows: list[dict[str, Any]], key: str) -> float: return round(sum(float(row[key]) for row in rows) / len(rows), 4)
def mean_bool(rows: list[dict[str, Any]], key: str) -> float: return round(sum(bool(row[key]) for row in rows) / len(rows), 4)
def percentile_summary(values: list[float]) -> dict[str, float]: return {"p50": round(float(np.percentile(values, 50)), 3), "p95": round(float(np.percentile(values, 95)), 3)}


if __name__ == "__main__": raise SystemExit(main())
