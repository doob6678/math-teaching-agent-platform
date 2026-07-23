"""Run real HTTP load tests against the local Python inference worker.

The runner deliberately stays independent from the worker implementation: every measured sample is an actual
authenticated HTTP request, while report helpers remain deterministic and easy to test without a fake service.
"""

from __future__ import annotations

import argparse
import base64
import csv
import importlib.metadata
import json
import math
import os
import platform
import statistics
import subprocess
import sys
import time
from concurrent.futures import ThreadPoolExecutor
from dataclasses import asdict, dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterable
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen


DEFAULT_LEVELS = (1, 2, 4, 8, 16, 32, 64)
SENSITIVE_MARKERS = ("KEY", "SECRET", "TOKEN", "PASSWORD", "AUTH")
MODEL_NAMES = ("bge-small-zh-v1.5", "bge-m3", "bge-reranker-v2-m3", "bge-reranker-base", "chinese-clip")


@dataclass(frozen=True)
class LoadTestConfig:
    """All workload controls are explicit so a report can reproduce the exact run."""

    worker_url: str = "http://127.0.0.1:8091"
    output_dir: Path = Path("output/benchmarks")
    request_timeout_seconds: float = 180.0
    warm_requests: int = 10
    requests_per_concurrency: int = 8
    concurrency_levels: tuple[int, ...] = DEFAULT_LEVELS
    error_rate_stop: float = 0.05
    latency_multiplier_stop: float = 4.0
    rerank_candidate_counts: tuple[int, ...] = (10, 50, 100)
    embedding_batch_sizes: tuple[int, ...] = (1, 4, 16, 32)
    test_bge_m3_url: str = "http://127.0.0.1:8092"


@dataclass(frozen=True)
class RequestRecord:
    """One end-to-end request sample, including enough attribution for error analysis."""

    model: str
    scenario: str
    concurrency: int
    elapsed_ms: float
    status: int
    provider: str
    returned_model: str
    dimension: int | None
    error: str
    timed_out: bool = False


@dataclass
class ResourceSample:
    """A point-in-time resource reading associated with a measured scenario."""

    timestamp: str
    scenario: str
    gpu_name: str = ""
    gpu_utilization_percent: float | None = None
    gpu_memory_used_mb: float | None = None
    gpu_memory_total_mb: float | None = None
    process_cpu_percent: float | None = None
    process_memory_mb: float | None = None


def percentile(values: Iterable[float], fraction: float) -> float:
    ordered = sorted(float(value) for value in values)
    if not ordered:
        return 0.0
    rank = max(1, math.ceil(fraction * len(ordered)))
    return round(ordered[min(rank - 1, len(ordered) - 1)], 2)


def summarize_records(records: list[RequestRecord], elapsed_seconds: float = 0.0) -> dict[str, Any]:
    """Summarize all outcomes; failures stay in the denominator so QPS is never overstated."""

    latencies = [record.elapsed_ms for record in records]
    successes = [record for record in records if record.status == 200]
    error_types: dict[str, int] = {}
    for record in records:
        if record.error:
            error_types[record.error] = error_types.get(record.error, 0) + 1
    dimensions = sorted({record.dimension for record in successes if record.dimension is not None})
    request_count = len(records)
    return {
        "requestCount": request_count,
        "successCount": len(successes),
        "errorCount": request_count - len(successes),
        "successRate": round(len(successes) / request_count, 4) if request_count else 0.0,
        "errorRate": round((request_count - len(successes)) / request_count, 4) if request_count else 0.0,
        "timeoutCount": sum(record.timed_out for record in records),
        "minMs": round(min(latencies), 2) if latencies else 0.0,
        "avgMs": round(statistics.mean(latencies), 2) if latencies else 0.0,
        "p50Ms": percentile(latencies, 0.50),
        "p95Ms": percentile(latencies, 0.95),
        "p99Ms": percentile(latencies, 0.99),
        "maxMs": round(max(latencies), 2) if latencies else 0.0,
        "qps": round(request_count / elapsed_seconds, 3) if elapsed_seconds > 0 else 0.0,
        "dimensions": dimensions,
        "providers": sorted({record.provider for record in successes if record.provider}),
        "returnedModels": sorted({record.returned_model for record in successes if record.returned_model}),
        "errorTypes": error_types,
    }


def summarize_resources(samples: Iterable[dict[str, Any]]) -> dict[str, Any]:
    """Summarize GPU samples without treating one end-of-batch snapshot as sustained utilization."""

    rows = list(samples)
    utilization = [float(row["gpu_utilization_percent"]) for row in rows if row.get("gpu_utilization_percent") is not None]
    memory = [float(row["gpu_memory_used_mb"]) for row in rows if row.get("gpu_memory_used_mb") is not None]
    return {
        "sampleCount": len(rows),
        "gpuUtilizationAvgPercent": round(statistics.mean(utilization), 2) if utilization else 0.0,
        "gpuUtilizationMaxPercent": round(max(utilization), 2) if utilization else 0.0,
        "gpuMemoryMaxMb": round(max(memory), 2) if memory else 0.0,
    }


def sanitize_environment(environment: dict[str, str]) -> dict[str, str]:
    """Keep paths and runtime choices while preventing credentials from entering command/report artifacts."""

    sanitized = {}
    for name, value in environment.items():
        sanitized[name] = "<set>" if any(marker in name.upper() for marker in SENSITIVE_MARKERS) else value
    return sanitized


def _has_model_weights(path: Path) -> bool:
    # HuggingFace BGE uses config.json; the project's ModelScope CLIP package uses configuration.json.
    has_configuration = (path / "config.json").is_file() or (path / "configuration.json").is_file()
    return path.is_dir() and has_configuration and any(
        (path / filename).is_file() for filename in ("model.safetensors", "pytorch_model.bin")
    )


def _first_existing(candidates: Iterable[Path]) -> Path | None:
    for candidate in candidates:
        if _has_model_weights(candidate):
            return candidate
    return None


def detect_models(environment: dict[str, str] | None = None, model_root: Path | None = None) -> dict[str, dict[str, Any]]:
    """Resolve configured paths first, then known local caches, without downloading or inventing weights."""

    source = environment or dict(os.environ)
    root = model_root or Path("D:/ModelScope/models/BAAI")
    clip_root = Path(source.get("MATH_AGENT_LOCAL_CLIP_MODEL_PATH", "D:/ModelScope/models/damo/multi-modal_clip-vit-large-patch14_zh"))
    candidates = {
        "bge-small-zh-v1.5": [Path(source.get("MATH_AGENT_LOCAL_TEXT_EMBEDDING_MODEL_PATH", "")), root / "bge-small-zh-v1.5"],
        "bge-m3": [root / "bge-m3", Path("D:/project2026/hf_cache/hub/models--BAAI--bge-m3/snapshots")],
        "bge-reranker-v2-m3": [Path(source.get("MATH_AGENT_LOCAL_RERANK_MODEL_PATH", "")), root / "bge-reranker-v2-m3"],
        "bge-reranker-base": [root / "bge-reranker-base", Path("D:/project2026/hf_cache/hub/models--BAAI--bge-reranker-base/snapshots")],
        "chinese-clip": [clip_root],
    }
    result: dict[str, dict[str, Any]] = {}
    for name, paths in candidates.items():
        resolved = _first_existing(path for path in paths if str(path))
        result[name] = {"status": "available" if resolved else "unavailable", "path": str(resolved) if resolved else ""}
    return result


def _response_dimension(body: dict[str, Any]) -> int | None:
    data = body.get("data") or []
    vector = data[0].get("embedding") if data and isinstance(data[0], dict) else None
    return len(vector) if isinstance(vector, list) else None


def _call(worker_url: str, key: str, endpoint: str, payload: dict[str, Any], model: str, scenario: str, concurrency: int, timeout: float) -> RequestRecord:
    started = time.perf_counter()
    status = 0
    provider = returned_model = error = ""
    dimension = None
    timed_out = False
    try:
        request = Request(
            worker_url.rstrip("/") + endpoint,
            data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
            headers={"Authorization": "Bearer " + key, "Content-Type": "application/json"},
            method="POST",
        )
        with urlopen(request, timeout=timeout) as response:
            status = response.status
            body = json.loads(response.read().decode("utf-8"))
        provider = str(body.get("provider", ""))
        returned_model = str(body.get("model", ""))
        dimension = _response_dimension(body)
    except HTTPError as exc:
        status = exc.code
        error = "http_" + str(exc.code)
    except TimeoutError:
        error, timed_out = "timeout", True
    except (URLError, OSError, json.JSONDecodeError) as exc:
        error = type(exc).__name__
    except Exception as exc:  # The report must retain unexpected real-client failures for diagnosis.
        error = type(exc).__name__ + ":" + str(exc)[:120]
    return RequestRecord(model, scenario, concurrency, (time.perf_counter() - started) * 1000, status, provider, returned_model, dimension, error, timed_out)


def _resource_sample(scenario: str) -> ResourceSample:
    sample = ResourceSample(datetime.now(timezone.utc).isoformat(), scenario)
    try:
        output = subprocess.check_output(
            ["nvidia-smi", "--query-gpu=name,utilization.gpu,memory.used,memory.total", "--format=csv,noheader,nounits"],
            text=True,
            timeout=5,
        ).strip().splitlines()[0]
        name, utilization, used, total = [part.strip() for part in output.split(",")]
        sample.gpu_name = name
        sample.gpu_utilization_percent = float(utilization)
        sample.gpu_memory_used_mb = float(used)
        sample.gpu_memory_total_mb = float(total)
    except (OSError, subprocess.SubprocessError, ValueError, IndexError):
        pass
    return sample


def _get_json(worker_url: str, key: str, endpoint: str, timeout: float) -> dict[str, Any]:
    """Capture health and capability evidence through the public API, never by inspecting worker memory."""

    started = time.perf_counter()
    headers = {"Authorization": "Bearer " + key} if endpoint != "/health" else {}
    try:
        request = Request(worker_url.rstrip("/") + endpoint, headers=headers, method="GET")
        with urlopen(request, timeout=timeout) as response:
            return {"status": response.status, "elapsedMs": round((time.perf_counter() - started) * 1000, 2), "body": json.loads(response.read().decode("utf-8"))}
    except HTTPError as exc:
        return {"status": exc.code, "elapsedMs": round((time.perf_counter() - started) * 1000, 2), "error": "http_" + str(exc.code)}
    except (URLError, OSError, json.JSONDecodeError) as exc:
        return {"status": 0, "elapsedMs": round((time.perf_counter() - started) * 1000, 2), "error": type(exc).__name__}


def _runtime_environment() -> dict[str, str]:
    """Record exact runtime versions so latency comparisons do not silently mix CUDA/Python environments."""

    environment = {"os": platform.platform(), "python": sys.version.split()[0]}
    for package in ("fastapi", "uvicorn", "torch", "transformers", "sentence-transformers"):
        try:
            environment[package] = importlib.metadata.version(package)
        except importlib.metadata.PackageNotFoundError:
            environment[package] = "not-installed"
    try:
        import torch

        environment["torchCudaAvailable"] = str(torch.cuda.is_available())
        environment["torchCudaRuntime"] = str(torch.version.cuda)
        environment["gpu"] = torch.cuda.get_device_name(0) if torch.cuda.is_available() else "none"
    except Exception as exc:
        environment["torchProbeError"] = type(exc).__name__
    return environment


def _run_batch(worker_url: str, key: str, request_spec: dict[str, Any], model: str, scenario: str, concurrency: int, count: int, timeout: float) -> tuple[list[RequestRecord], float, ResourceSample]:
    started = time.perf_counter()
    with ThreadPoolExecutor(max_workers=concurrency) as executor:
        records = list(executor.map(lambda _: _call(worker_url, key, request_spec["endpoint"], request_spec["payload"], model, scenario, concurrency, timeout), range(count)))
    elapsed = time.perf_counter() - started
    return records, elapsed, _resource_sample(scenario)


def _text_payload(batch_size: int, length: str, model_name: str) -> dict[str, Any]:
    seed = {"short": "空间向量", "medium": "高中数学空间向量与立体几何中的数量积和垂直关系。", "long": "高中数学空间向量与立体几何中的数量积、夹角、垂直、平行和距离计算。" * 12}[length]
    return {"endpoint": "/v1/embeddings", "payload": {"input": [seed] * batch_size, "model": model_name}}


def _image_payload() -> dict[str, Any]:
    image_path = Path(__file__).parents[1] / "backend-java/src/main/resources/handout-assets-zhao-header.png"
    if not image_path.is_file():
        raise FileNotFoundError(str(image_path))
    encoded = base64.b64encode(image_path.read_bytes()).decode("ascii")
    return {"endpoint": "/v1/clip/image-embeddings", "payload": {"images": "data:image/png;base64," + encoded}}


def _rerank_payload(candidate_count: int) -> dict[str, Any]:
    documents = ["数量积可以判断向量垂直关系。" if index == 0 else f"高中数学候选知识片段 {index}。" for index in range(candidate_count)]
    return {"endpoint": "/v1/rerank", "payload": {"query": "空间向量的数量积", "documents": documents}}


def _append_scenario(scenarios: list[dict[str, Any]], model: str, name: str, concurrency: int, records: list[RequestRecord], elapsed_seconds: float) -> dict[str, Any]:
    """Attach each batch's wall-clock duration before records are merged with other scenarios."""

    summary = summarize_records(records, elapsed_seconds)
    scenarios.append({"model": model, "name": name, "concurrency": concurrency, "summary": summary})
    return summary


def _run_model_scenarios(config: LoadTestConfig, key: str, model: str, worker_url: str, specs: list[tuple[str, dict[str, Any]]], records: list[RequestRecord], resources: list[ResourceSample], stops: list[dict[str, Any]], scenarios: list[dict[str, Any]]) -> None:
    for scenario, request_spec in specs:
        cold_records, elapsed, resource = _run_batch(worker_url, key, request_spec, model, scenario + ":cold", 1, 1, config.request_timeout_seconds)
        records.extend(cold_records)
        resources.append(resource)
        _append_scenario(scenarios, model, scenario + ":cold", 1, cold_records, elapsed)
        warm_records, elapsed = _run_batch(worker_url, key, request_spec, model, scenario + ":warm", 1, config.warm_requests, config.request_timeout_seconds)[:2]
        records.extend(warm_records)
        baseline = _append_scenario(scenarios, model, scenario + ":warm", 1, warm_records, elapsed)
        for level in config.concurrency_levels:
            batch_records, batch_elapsed, batch_resource = _run_batch(worker_url, key, request_spec, model, scenario + ":concurrency", level, level * config.requests_per_concurrency, config.request_timeout_seconds)
            records.extend(batch_records)
            resources.append(batch_resource)
            summary = _append_scenario(scenarios, model, scenario + ":concurrency", level, batch_records, batch_elapsed)
            if summary["errorRate"] > config.error_rate_stop or (baseline["p95Ms"] and summary["p95Ms"] > baseline["p95Ms"] * config.latency_multiplier_stop):
                stops.append({"model": model, "scenario": scenario, "concurrency": level, "reason": "error_rate_or_latency_threshold", "summary": summary})
                break


def build_report_markdown(run: dict[str, Any], command: str) -> str:
    environment = run.get("environment", {})
    lines = ["# Python Worker 模型压力测试报告", "", f"生成时间：`{run.get('generatedAt', '')}`", "", "## 1. 测试环境", ""]
    lines.extend(f"- `{key}`：`{value}`" for key, value in environment.items())
    lines.extend(["", "## 2. 使用代码与框架", "", "- 压测代码：`benchmarks/python_worker_load_test.py`", "- 服务：FastAPI + Uvicorn；推理：PyTorch + Transformers/Sentence-Transformers。", "- 客户端：Python 标准库 `urllib`，并发：`ThreadPoolExecutor`。", "- 测试请求均为真实鉴权 HTTP 请求；不使用 mock、fake 或模拟分数。", "", "## 3. 测试方式", "", "- 先执行 capability/健康检查，再分别记录冷启动和热请求。", "- embedding 覆盖短、中、长文本及多个 batch；reranker 覆盖 10/50/100 候选；CLIP 覆盖文本和真实 PNG 图像。", "- 并发梯度按配置从低到高升压；错误率或 P95 超过阈值时停止该场景并记录原因。", "- GPU 资源通过 `nvidia-smi` 采样；请求级数据、响应模型、维度和错误全部保存。", "", "## 4. 模型状态", "", "| 模型 | 状态 | 实际路径 |", "|---|---|---|"])
    for model, detail in run.get("models", {}).items():
        lines.append(f"| `{model}` | `{detail.get('status', '')}` | `{detail.get('path', '')}` |")
    lines.extend(["", "## 5. 统计结果", "", "| 场景 | 请求数 | 成功率 | P50 ms | P95 ms | P99 ms | QPS | 错误数 |", "|---|---:|---:|---:|---:|---:|---:|---:|"])
    for scenario in run.get("scenarios", []):
        summary = scenario["summary"]
        lines.append(f"| `{scenario['model']} / {scenario['name']}` | {summary['requestCount']} | {summary['successRate']:.2%} | {summary['p50Ms']:.2f} | {summary['p95Ms']:.2f} | {summary['p99Ms']:.2f} | {summary['qps']:.3f} | {summary['errorCount']} |")
    health_checks = run.get("healthChecks", {})
    cache = run.get("cacheValidation", {})
    lines.extend(["", "## 6. 缓存与推理验证", "", "- Worker 端通过 `lru_cache(maxsize=1)` 复用 `EmbeddingService`；冷启动与热请求差异已分别记录。", "- 模型推理代码使用 `eval()` 和 `torch.no_grad()`，本报告只验证真实接口行为，不修改模型实现。", "- 健康与 capability：`" + json.dumps(health_checks, ensure_ascii=False) + "`", "- 缓存验证（manifest fingerprint 索引重复查询）：`" + json.dumps(cache, ensure_ascii=False) + "`", "", "## 7. 瓶颈与 SLA 建议", "", "" if not run.get("stops") else "升压停止点：" + json.dumps(run["stops"], ensure_ascii=False), "", "具体数值以 `results.json` 和 `summary.csv` 为准；SLA 应选择错误率为 0 且 P95 尚未明显拐点的最高并发级别。", "", "## 8. 实际命令", "", "```text", command, "```", ""])
    return "\n".join(lines)


def write_report_artifacts(output_dir: Path, config: LoadTestConfig, run: dict[str, Any], command: str) -> None:
    """Write all artifact formats together so Markdown and machine data cannot drift apart."""

    output_dir.mkdir(parents=True, exist_ok=True)
    (output_dir / "results.json").write_text(json.dumps(run, ensure_ascii=False, indent=2), encoding="utf-8")
    (output_dir / "report.md").write_text(build_report_markdown(run, command), encoding="utf-8")
    with (output_dir / "summary.csv").open("w", encoding="utf-8", newline="") as stream:
        fields = ["model", "scenario", "concurrency", "requestCount", "successRate", "errorRate", "p50Ms", "p95Ms", "p99Ms", "qps", "errorCount"]
        writer = csv.DictWriter(stream, fieldnames=fields)
        writer.writeheader()
        for scenario in run.get("scenarios", []):
            row = {"model": scenario["model"], "scenario": scenario["name"], "concurrency": scenario.get("concurrency", "")}
            row.update({key: scenario["summary"].get(key, "") for key in fields[3:]})
            writer.writerow(row)
    with (output_dir / "resource-samples.csv").open("w", encoding="utf-8", newline="") as stream:
        resources = run.get("resources", [])
        fields = list(asdict(ResourceSample("", "")).keys())
        writer = csv.DictWriter(stream, fieldnames=fields)
        writer.writeheader()
        writer.writerows(resources)
    (output_dir / "command.txt").write_text(command + "\n\n" + json.dumps(sanitize_environment(dict(os.environ)), ensure_ascii=False, indent=2), encoding="utf-8")


def _parse_levels(value: str) -> tuple[int, ...]:
    levels = tuple(sorted({int(item) for item in value.split(",") if int(item) > 0}))
    if not levels:
        raise ValueError("concurrency levels must contain a positive integer")
    return levels


def run(config: LoadTestConfig) -> Path:
    key = os.getenv("MATH_AGENT_WORKER_API_KEY") or os.getenv("MATH_AGENT_EMBEDDING_API_KEY")
    if not key:
        secret_path = Path(".local-secrets/worker-api-key.txt")
        key = secret_path.read_text(encoding="utf-8").strip() if secret_path.is_file() else ""
    if not key:
        raise RuntimeError("MATH_AGENT_WORKER_API_KEY or .local-secrets/worker-api-key.txt is required")
    generated_at = datetime.now(timezone.utc)
    output_dir = config.output_dir / f"python-worker-load-{generated_at.astimezone().strftime('%Y%m%d-%H%M%S')}"
    models = detect_models()
    records: list[RequestRecord] = []
    resources: list[ResourceSample] = []
    stops: list[dict[str, Any]] = []
    scenarios: list[dict[str, Any]] = []
    health_checks = {"primary": {"health": _get_json(config.worker_url, key, "/health", config.request_timeout_seconds), "capabilities": _get_json(config.worker_url, key, "/v1/capabilities", config.request_timeout_seconds)}}
    cache_validation: dict[str, Any] = {"status": "not_executed"}
    if health_checks["primary"]["capabilities"].get("body", {}).get("levels", {}).get("textPageSearch", {}).get("status") == "ready":
        page_payload = {"endpoint": "/v1/text/page-search", "payload": {"query": "空间向量", "limit": 3}}
        first = _call(config.worker_url, key, page_payload["endpoint"], page_payload["payload"], "page-text-index", "manifest-cache:first", 1, config.request_timeout_seconds)
        repeated = _call(config.worker_url, key, page_payload["endpoint"], page_payload["payload"], "page-text-index", "manifest-cache:repeat", 1, config.request_timeout_seconds)
        records.extend((first, repeated))
        cache_validation = {"status": "executed", "first": asdict(first), "repeat": asdict(repeated), "reuseObserved": first.status == 200 and repeated.status == 200 and repeated.elapsed_ms <= first.elapsed_ms}
    specs: list[tuple[str, dict[str, Any]]] = []
    if models["bge-small-zh-v1.5"]["status"] == "available":
        specs.extend((f"embedding:{length}:batch:{batch}", _text_payload(batch, length, "BAAI/bge-small-zh-v1.5")) for length in ("short", "medium", "long") for batch in config.embedding_batch_sizes)
        _run_model_scenarios(config, key, "bge-small-zh-v1.5", config.worker_url, specs, records, resources, stops, scenarios)
    if models["bge-m3"]["status"] == "available":
        m3_specs = [("embedding:medium:batch:1", _text_payload(1, "medium", "BAAI/bge-m3"))]
        _run_model_scenarios(config, key, "bge-m3", config.test_bge_m3_url, m3_specs, records, resources, stops, scenarios)
    if models["bge-reranker-v2-m3"]["status"] == "available":
        rerank_specs = [(f"rerank:candidates:{count}", _rerank_payload(count)) for count in config.rerank_candidate_counts]
        _run_model_scenarios(config, key, "bge-reranker-v2-m3", config.worker_url, rerank_specs, records, resources, stops, scenarios)
    if models["chinese-clip"]["status"] == "available":
        clip_specs = [("clip:text", {"endpoint": "/v1/clip/text-embeddings", "payload": {"input": "空间向量与立体几何"}}), ("clip:image", _image_payload())]
        _run_model_scenarios(config, key, "chinese-clip", config.worker_url, clip_specs, records, resources, stops, scenarios)
    environment = _runtime_environment() | {name: value for name, value in os.environ.items() if name.startswith(("MATH_AGENT_", "CUDA_", "HF_", "TRANSFORMERS_"))}
    run_data = {"generatedAt": generated_at.isoformat(), "config": asdict(config) | {"output_dir": str(config.output_dir)}, "environment": sanitize_environment(environment), "models": models, "healthChecks": health_checks, "cacheValidation": cache_validation, "scenarios": scenarios, "resources": [asdict(sample) for sample in resources], "requests": [asdict(record) for record in records], "stops": stops}
    command = " ".join(sys.argv)
    write_report_artifacts(output_dir, config, run_data, command)
    return output_dir


def main() -> int:
    parser = argparse.ArgumentParser(description="Run real HTTP load tests against the local Python worker")
    parser.add_argument("--worker-url", default="http://127.0.0.1:8091")
    parser.add_argument("--bge-m3-url", default="http://127.0.0.1:8092")
    parser.add_argument("--output-root", type=Path, default=Path("output/benchmarks"))
    parser.add_argument("--warm-requests", type=int, default=10)
    parser.add_argument("--requests-per-concurrency", type=int, default=8)
    parser.add_argument("--concurrency-levels", default=",".join(map(str, DEFAULT_LEVELS)))
    parser.add_argument("--error-rate-stop", type=float, default=0.05)
    parser.add_argument("--timeout-seconds", type=float, default=180.0)
    args = parser.parse_args()
    config = LoadTestConfig(worker_url=args.worker_url, test_bge_m3_url=args.bge_m3_url, output_dir=args.output_root, warm_requests=args.warm_requests, requests_per_concurrency=args.requests_per_concurrency, concurrency_levels=_parse_levels(args.concurrency_levels), error_rate_stop=args.error_rate_stop, request_timeout_seconds=args.timeout_seconds)
    output_dir = run(config)
    print(json.dumps({"outputDir": str(output_dir)}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
