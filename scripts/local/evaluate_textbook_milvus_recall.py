"""Compare the retired local NPY baseline with real Milvus textbook recall using identical worker query vectors."""
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


QUERIES = ("平面向量的基本定理", "正弦定理的应用条件", "椭圆的标准方程", "导数的几何意义", "等差数列前n项和")
ROOT = Path(__file__).resolve().parents[2]


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--processed-books-root", required=True, type=Path)
    parser.add_argument("--output", type=Path, default=ROOT / "docs" / "textbook-milvus-recall-verification.json")
    args = parser.parse_args()
    cfg = yaml.safe_load((ROOT / "backend-java/src/main/resources/application.yml").read_text(encoding="utf-8"))["math-agent"]["vector-index"]
    worker_key = worker_key_from_local_secret()
    token = os.environ.get("MATH_AGENT_MILVUS_TOKEN", "")
    if not token:
        raise RuntimeError("MATH_AGENT_MILVUS_TOKEN is required for real Milvus verification")
    root = args.processed_books_root.resolve()
    text = evaluate_route(root, cfg, worker_key, token, "text", QUERIES)
    image = evaluate_route(root, cfg, worker_key, token, "image", QUERIES)
    result = {"status": "verified", "queries": list(QUERIES), "text": text, "image": image}
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0


def evaluate_route(root: Path, cfg: dict[str, Any], worker_key: str, token: str, route: str, queries: tuple[str, ...]) -> dict[str, Any]:
    if route == "text":
        metadata = read_jsonl(root / "_page_text_index/metadata.jsonl")
        vectors = np.load(root / "_page_text_index/page_embeddings.npy").astype(np.float32)
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
                            "top3ContainsBaselineTop1": bool(baseline_ids and baseline_ids[0] in milvus_ids[:3])})
    return {"collection": collection, "dimension": dimension, "recallAt10": mean(comparisons, "recallAt10"),
            "mrr": mean(comparisons, "reciprocalRank"), "top1": mean_bool(comparisons, "top1Match"),
            "top3": mean_bool(comparisons, "top3ContainsBaselineTop1"), "sectionTitleHitRate": mean_bool(comparisons, "top1Match"),
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
    data = post(uri.rstrip("/") + "/v2/vectordb/entities/search", {"collectionName": collection, "data": [vector], "limit": limit,
        "outputFields": ["id"], "searchParams": {"metricType": "COSINE", "params": {}}}, {"Authorization": "Bearer " + token})["data"]
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
