"""阶段2B：Milvus 直连检索延迟（隔离向量层，排除 BGE 嵌入与 GPU rerank）。

用 pymilvus 直连 WSL 暴露的 host:19531（root 认证），自省真实 collection 的向量字段与维度，
用随机查询向量连续检索 N 次，记录纯 Milvus 搜索延迟分布（p50/p95/max）与稳定性。
凭据从 .env 读取，不硬编码。
"""
from __future__ import annotations

import json
import random
import statistics
import sys
import time
from pathlib import Path

REPO = Path(r"C:\Users\doob\Desktop\code\dev\math_agent_rag")

try:
    from pymilvus import MilvusClient
except Exception as exc:  # noqa: BLE001
    print("pymilvus 不可用:", exc)
    raise SystemExit(2)


def load_env():
    env = {}
    for line in (REPO / ".env").read_text(encoding="utf-8").splitlines():
        if "=" in line and not line.startswith("#"):
            k, v = line.split("=", 1)
            env[k.strip()] = v.strip()
    return env


def main():
    env = load_env()
    token = f"root:{env.get('MATH_AGENT_MILVUS_ROOT_PASSWORD', '')}"
    uri = "http://127.0.0.1:19531"
    client = MilvusClient(uri=uri, token=token)
    cols = client.list_collections()
    print("collections:", cols)

    targets = [c for c in cols if c in {
        "textbook_text_collection", "math_agent_teacher_text_blocks_bge"}] or cols[:2]
    results = {}
    N = 50
    for name in targets:
        try:
            info = client.describe_collection(name)
            vector_field = None
            dim = None
            for f in info.get("fields", []):
                if f.get("dtype") in (101, "FLOAT_VECTOR") or "vector" in str(f.get("name")).lower():
                    vector_field = f.get("name")
                    params = f.get("params") or {}
                    if "dim" in params:
                        dim = int(params["dim"])
                    break
            if vector_field is None:
                print(f"{name}: 未识别向量字段, fields={[f.get('name') for f in info.get('fields', [])]}")
                continue
            count = client.get_collection_stats(name).get("row_count")
            if dim is None:
                dim = 512
            lat = []
            err = 0
            random.seed(42)
            for _ in range(N):
                qv = [random.random() for _ in range(dim)]
                t0 = time.perf_counter()
                try:
                    client.search(collection_name=name, data=[qv], anns_field=vector_field,
                                  limit=20, output_fields=[])
                    lat.append(round((time.perf_counter() - t0) * 1000))
                except Exception as e:  # noqa: BLE001
                    err += 1
            s = sorted(lat)
            results[name] = {"vector_field": vector_field, "dim": dim, "row_count": count,
                             "samples": len(lat), "errors": err,
                             "p50": s[len(s) // 2] if s else None,
                             "p95": s[max(0, int(len(s) * 0.95) - 1)] if s else None,
                             "minMs": s[0] if s else None, "maxMs": s[-1] if s else None,
                             "avgMs": round(statistics.mean(lat)) if lat else None,
                             "stdevMs": round(statistics.pstdev(lat)) if len(lat) > 1 else 0}
            print(f"{name} [{vector_field} dim={dim} rows={count}] p50={results[name]['p50']}ms "
                  f"p95={results[name]['p95']}ms max={results[name]['maxMs']}ms err={err}")
        except Exception as e:  # noqa: BLE001
            print(f"{name}: 探测失败 {e}")

    out = REPO / "output" / "link-eval-20260909"
    out.mkdir(parents=True, exist_ok=True)
    (out / "phase2b_milvus_direct.json").write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding="utf-8")
    print("\n===== PHASE2B MILVUS DIRECT =====")
    print(json.dumps(results, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
