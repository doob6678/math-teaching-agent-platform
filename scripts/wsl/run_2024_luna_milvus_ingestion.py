"""Run the audited 2024 PDF ingestion path against real Luna, embeddings and Milvus.

This is deliberately a single command: every selected PDF page is rendered to an
original PNG, compressed to a bounded JPEG for Luna, transcribed by Luna, and
stored with its complete non-secret request/response.  Every returned question
is then embedded by the real local worker and inserted into Milvus.  A final
vector search uses one of the inserted question texts, so success cannot be
reported from a write-only index.
"""
from __future__ import annotations

import argparse
import base64
from concurrent.futures import ThreadPoolExecutor, as_completed
import hashlib
import json
import mimetypes
import os
import random
import subprocess
import time
import threading
import uuid
import zipfile
from datetime import datetime, timezone
from pathlib import Path
from typing import Any
from urllib.parse import urljoin

import requests
from PIL import Image
from pypdf import PdfReader


PROJECT_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_CONFIG = PROJECT_ROOT / "config" / "gaokao-ingestion-2024.json"
DEFAULT_EVIDENCE_ROOT = PROJECT_ROOT / "output" / "gaokao-evidence" / "2024"
LUNA_MODEL = "gpt-5.6-luna"
DEFAULT_TIMEOUT_SECONDS = 120
DEFAULT_EMBEDDING_MODEL = "text-embedding-v4"
DEFAULT_EMBEDDING_URL = "http://127.0.0.1:8092/v1/embeddings"
DEFAULT_MILVUS_URI = "http://127.0.0.1:19531"
DEFAULT_COLLECTION = "gaokao_math"
VECTOR_DIMENSION = 512
VECTOR_FIELD = "vector"
PRIMARY_KEY_FIELD = "id"
TEXT_FIELD = "text"
METADATA_FIELD = "metadata"
PAGE_RENDER_DPI = 180
# A recall validation must see through stale duplicate rows from interrupted legacy
# runs while still keeping the real nearest-neighbour request intentionally small.
RETRIEVAL_LIMIT = 10
EMBEDDING_BATCH_SIZE = 10
DEFAULT_TIMEOUT_GRACE_SECONDS = 5
LUNA_MAX_ATTEMPTS = 3
LUNA_RETRY_INITIAL_DELAY_SECONDS = 2
LUNA_RETRY_MAX_DELAY_SECONDS = 16
LUNA_RETRY_JITTER_FRACTION = 0.25
DEFAULT_GLOBAL_AI_CONCURRENCY = 20
GLOBAL_AI_CONCURRENCY_ENVIRONMENT_VARIABLE = "MATH_AGENT_AGENT_WORKER_MAX_CONCURRENCY"
RENDERER_CLASS = "RenderPdfEvidencePage"
LUNA_NETWORK_CONTAINER = "math-agent-rag-ai-worker-1"
_renderer_ready = False


class NonRetryableLunaError(RuntimeError):
    """Marks a provider response that must be surfaced immediately, never retried."""


def utc_now() -> str:
    """Produces a timezone-explicit timestamp for evidence created on either Windows or WSL."""
    return datetime.now(timezone.utc).isoformat()


def sha256_file(path: Path) -> str:
    """Streams large PDFs/images instead of loading a whole exam into memory just to identify it."""
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def load_dotenv(path: Path) -> dict[str, str]:
    """Loads local secrets without printing or copying their values into evidence."""
    values: dict[str, str] = {}
    if not path.is_file():
        return values
    for raw in path.read_text(encoding="utf-8").splitlines():
        if raw.strip() and not raw.lstrip().startswith("#") and "=" in raw:
            key, value = raw.split("=", 1)
            values[key.strip()] = value.strip().strip('"').strip("'")
    return values


def setting(name: str, dotenv: dict[str, str], default: str = "") -> str:
    """Environment wins, while .env gives the WSL command the same configured credentials as Compose."""
    return os.environ.get(name) or dotenv.get(name) or default


def ensure_pdf_renderer() -> None:
    """Compiles the project's PDFBox renderer in the already-running backend container once per run.

    WSL intentionally has no system Poppler dependency.  The helper's PDFBox dependencies are
    extracted from the running production backend jar into the project's ignored local-run area.
    That keeps the renderer version aligned with production without changing WSL's package state.
    """
    global _renderer_ready
    if _renderer_ready:
        return
    helper = PROJECT_ROOT / "scripts" / "wsl" / f"{RENDERER_CLASS}.java"
    renderer_root = PROJECT_ROOT / ".local-run" / "gaokao-pdf-renderer"
    libraries = renderer_root / "lib"
    class_file = renderer_root / f"{RENDERER_CLASS}.class"
    if not class_file.is_file():
        backend_jar = renderer_root / "math-agent-rag.jar"
        renderer_root.mkdir(parents=True, exist_ok=True)
        subprocess.run(["docker", "cp", "math-agent-rag-backend-1:/app/math-agent-rag.jar", str(backend_jar)], check=True)
        with zipfile.ZipFile(backend_jar) as archive:
            for member in archive.namelist():
                if member.startswith("BOOT-INF/lib/") and member.endswith(".jar"):
                    destination = libraries / Path(member).name
                    destination.parent.mkdir(parents=True, exist_ok=True)
                    destination.write_bytes(archive.read(member))
        subprocess.run(["javac", "-cp", str(libraries / "*"), "-d", str(renderer_root), str(helper)], check=True, capture_output=True, text=True, encoding="utf-8")
    _renderer_ready = True


def render_page(pdf: Path, page: int, original: Path, source_root: Path, container_input_root: str, evidence_root: Path) -> None:
    """Renders the actual source PDF through production-aligned PDFBox into the durable evidence directory."""
    ensure_pdf_renderer()
    renderer_root = PROJECT_ROOT / ".local-run" / "gaokao-pdf-renderer"
    command = ["java", "-cp", f"{renderer_root}:{renderer_root / 'lib'}/*", RENDERER_CLASS, str(pdf), str(page), str(original)]
    subprocess.run(command, check=True, capture_output=True, text=True, encoding="utf-8")
    if not original.is_file():
        raise RuntimeError(f"backend PDF renderer did not create {original}")


def compress_for_luna(original: Path, target: Path, maximum_edge: int, jpeg_quality: float) -> None:
    """Keeps the original PNG untouched and writes only a bounded-cost review derivative."""
    if maximum_edge < 1 or not 0 < jpeg_quality <= 1:
        raise ValueError("invalid image compression configuration")
    with Image.open(original) as image:
        image = image.convert("RGB")
        image.thumbnail((maximum_edge, maximum_edge), Image.Resampling.LANCZOS)
        target.parent.mkdir(parents=True, exist_ok=True)
        image.save(target, format="JPEG", quality=round(jpeg_quality * 100), optimize=True)


def page_count(pdf: Path) -> int:
    """Reads physical page count only; the recognition truth remains the subsequently rendered page image."""
    return len(PdfReader(str(pdf)).pages)


def luna_request(image: Path, paper: str, page: int) -> dict[str, Any]:
    """Builds the full persisted Luna request; its image data is required for replayable visual evidence."""
    mime = mimetypes.guess_type(image.name)[0] or "image/jpeg"
    data_url = f"data:{mime};base64,{base64.b64encode(image.read_bytes()).decode('ascii')}"
    prompt = {
        "task": "Transcribe every visible high-school mathematics question on this one rendered exam page.",
        "paper": paper,
        "page": page,
        "requiredOutput": {
            "pageText": "string",
            "questions": [{"number": "string", "text": "string", "latex": ["string"], "continuesToNextPage": "boolean", "confidence": "number"}],
            "layout": "single-column|multi-column|uncertain",
            "boundaryRisks": ["string"]
        },
        "constraints": [
            "Read only the supplied page image.",
            "Preserve mathematical formulas as LaTex strings in latex. Every visible mathematical fraction MUST use \\frac{numerator}{denominator}; never write a fraction as a/b, 1/2, or x/y in a latex field.",
            "Do not invent an answer, solution, unshown text, or an official correctness judgement.",
            "Use an empty questions list if no question is visible."
        ],
    }
    return {
        "model": LUNA_MODEL,
        "temperature": 0,
        "response_format": {"type": "json_object"},
        "messages": [
            {"role": "system", "content": "Return one valid JSON object only. State uncertainty in boundaryRisks."},
            {"role": "user", "content": [{"type": "text", "text": json.dumps(prompt, ensure_ascii=False)}, {"type": "image_url", "image_url": {"url": data_url, "detail": "high"}}]},
        ],
    }


def luna_retry_delay_seconds(completed_attempt: int) -> float:
    """Returns capped exponential backoff plus jitter so concurrent failed pages do not retry in lockstep."""
    exponential_delay = min(LUNA_RETRY_MAX_DELAY_SECONDS, LUNA_RETRY_INITIAL_DELAY_SECONDS * (2 ** (completed_attempt - 1)))
    jitter_low = 1 - LUNA_RETRY_JITTER_FRACTION
    jitter_high = 1 + LUNA_RETRY_JITTER_FRACTION
    return round(exponential_delay * random.uniform(jitter_low, jitter_high), 3)


def call_luna(request: dict[str, Any], base_url: str, api_key: str, timeout: int, grace_seconds: int,
              configured_page_workers: int) -> tuple[int, dict[str, Any], int, list[dict[str, Any]]]:
    """Makes one visual request from the healthy Docker network with a hard parent-process deadline.

    WSL's direct socket can remain blocked beyond the HTTP library deadline. The worker
    already has the configured provider secret and Docker DNS route; this bridge gets an
    unconditional subprocess deadline. Calls remain serial, not a page worker pool.
    """
    if timeout < 1 or grace_seconds < 0:
        raise ValueError("provider timeout must be positive and grace seconds cannot be negative")
    bridge = """import json, os, sys, time, requests
request = json.load(sys.stdin)
started = time.perf_counter()
response = requests.post(os.environ['OPENAI_BASE_URL'].rstrip('/') + '/chat/completions', headers={'Authorization': 'Bearer ' + os.environ['OPENAI_API_KEY'], 'Content-Type': 'application/json'}, json=request, timeout=int(os.environ['LUNA_HTTP_TIMEOUT_SECONDS']))
try:
    body = response.json()
except ValueError:
    body = {'nonJsonBody': response.text}
print(json.dumps({'status': response.status_code, 'body': body, 'elapsedMs': round((time.perf_counter() - started) * 1000)}, ensure_ascii=False))
"""
    attempts: list[dict[str, Any]] = []
    for attempt in range(1, LUNA_MAX_ATTEMPTS + 1):
        started_at = utc_now()
        try:
            result = subprocess.run(["docker", "exec", "-i", "-e", f"LUNA_HTTP_TIMEOUT_SECONDS={timeout}", LUNA_NETWORK_CONTAINER, "python", "-c", bridge], input=json.dumps(request, ensure_ascii=False), capture_output=True, text=True, encoding="utf-8", timeout=timeout + grace_seconds, check=False)
            if result.returncode != 0:
                raise RuntimeError(f"Luna Docker bridge failed: {result.stderr.strip()}")
            bridge_response = json.loads(result.stdout)
            status = int(bridge_response["status"])
            body = bridge_response["body"]
            attempts.append({"attempt": attempt, "startedAt": started_at, "configuredPageWorkers": configured_page_workers, "httpStatus": status, "response": body, "elapsedMs": int(bridge_response["elapsedMs"])})
            if 200 <= status < 300:
                return status, body, int(bridge_response["elapsedMs"]), attempts
            retryable = status in {429, 500, 502, 503, 504, 520}
            if not retryable:
                raise NonRetryableLunaError(f"Luna HTTP {status}: {body}")
        except NonRetryableLunaError:
            raise
        except (subprocess.TimeoutExpired, TimeoutError, RuntimeError, json.JSONDecodeError) as error:
            attempts.append({"attempt": attempt, "startedAt": started_at, "configuredPageWorkers": configured_page_workers, "errorType": type(error).__name__, "error": str(error)})
            if attempt == LUNA_MAX_ATTEMPTS:
                raise RuntimeError(f"Luna failed after {LUNA_MAX_ATTEMPTS} attempts: {error}") from error
        if attempt < LUNA_MAX_ATTEMPTS:
            retry_delay_seconds = luna_retry_delay_seconds(attempt)
            attempts[-1]["retryDelaySeconds"] = retry_delay_seconds
            time.sleep(retry_delay_seconds)
    raise AssertionError("unreachable Luna retry state")


def recognized_questions(response: dict[str, Any], source_name: str, page: int) -> list[dict[str, Any]]:
    """Accepts only Luna's structured JSON response and creates immutable source-backed vector payloads."""
    try:
        content = response["choices"][0]["message"]["content"]
        parsed = json.loads(content) if isinstance(content, str) else content
    except (KeyError, IndexError, TypeError, json.JSONDecodeError) as error:
        raise RuntimeError(f"Luna did not return a parseable JSON transcription: {error}") from error
    questions = parsed.get("questions")
    if not isinstance(questions, list):
        raise RuntimeError("Luna transcription has no questions array")
    output: list[dict[str, Any]] = []
    for item in questions:
        if not isinstance(item, dict) or not str(item.get("text", "")).strip():
            continue
        latex = item.get("latex", [])
        if not isinstance(latex, list):
            latex = []
        text = str(item["text"]).strip()
        vector_text = text + ("\n" + "\n".join(map(str, latex)) if latex else "")
        # The key derives only from immutable visual evidence.  A recovery run can
        # therefore upsert the same question instead of creating a fresh duplicate.
        stable_identity = f"{source_name}\n{page}\n{item.get('number', '')}\n{vector_text}"
        output.append({
            "id": str(uuid.uuid5(uuid.NAMESPACE_URL, stable_identity)), "text": vector_text,
            "metadata": {"sourceFile": source_name, "page": page, "questionNumber": str(item.get("number", "")), "latex": latex, "confidence": item.get("confidence"), "continuesToNextPage": bool(item.get("continuesToNextPage", False)), "extraction": "LUNA_VISUAL_PAGE"},
        })
    return output


def embed(texts: list[str], url: str, api_key: str, timeout: int) -> list[list[float]]:
    """Uses the running local worker's real embedding endpoint; dimensions are validated before Milvus writes."""
    response = requests.post(url, headers={"Authorization": f"Bearer {api_key}", "Content-Type": "application/json"} if api_key else {"Content-Type": "application/json"}, json={"model": DEFAULT_EMBEDDING_MODEL, "input": texts}, timeout=timeout)
    if not response.ok:
        raise RuntimeError(f"embedding HTTP {response.status_code}: {response.text[:1000]}")
    vectors = [entry["embedding"] for entry in response.json().get("data", [])]
    if len(vectors) != len(texts) or any(len(vector) != VECTOR_DIMENSION for vector in vectors):
        raise RuntimeError("embedding response count or configured 512-dimensional vector contract failed")
    return vectors


def embed_all(texts: list[str], url: str, api_key: str, timeout: int) -> list[list[float]]:
    """Batches real embedding requests to keep the worker payload bounded while preserving insertion order."""
    vectors: list[list[float]] = []
    for start in range(0, len(texts), EMBEDDING_BATCH_SIZE):
        vectors.extend(embed(texts[start:start + EMBEDDING_BATCH_SIZE], url, api_key, timeout))
    return vectors


def milvus_post(uri: str, token: str, path: str, body: dict[str, Any], timeout: int) -> dict[str, Any]:
    """Calls Milvus REST and fails on its explicit status code rather than treating an HTTP 200 error object as success."""
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    response = requests.post(urljoin(uri.rstrip("/") + "/", path.lstrip("/")), headers=headers, json=body, timeout=timeout)
    try:
        payload = response.json()
    except ValueError as error:
        raise RuntimeError(f"Milvus {path} returned non-JSON HTTP {response.status_code}: {response.text[:1000]}") from error
    if not response.ok or payload.get("code", 0) != 0:
        raise RuntimeError(f"Milvus {path} failed: {payload}")
    return payload


def ensure_collection(uri: str, token: str, collection: str, timeout: int) -> None:
    """Ensures gaokao_math has its schema and vector index before Milvus loads it.

    Milvus v2 deliberately rejects loading a vector collection without an index.  The
    index creation is therefore idempotent and runs for both a newly-created
    collection and an older partially-created collection left by an interrupted run.
    FLAT/COSINE is used here because the final verification is an exact recall check.
    """
    exists = milvus_post(uri, token, "/v2/vectordb/collections/has", {"collectionName": collection}, timeout).get("data", {}).get("has", False)
    if not exists:
        schema = {"collectionName": collection, "schema": {"autoId": False, "enableDynamicField": False, "fields": [
            {"fieldName": PRIMARY_KEY_FIELD, "dataType": "VarChar", "isPrimary": True, "elementTypeParams": {"max_length": "64"}},
            {"fieldName": VECTOR_FIELD, "dataType": "FloatVector", "elementTypeParams": {"dim": str(VECTOR_DIMENSION)}},
            {"fieldName": TEXT_FIELD, "dataType": "VarChar", "elementTypeParams": {"max_length": "65535"}},
            {"fieldName": METADATA_FIELD, "dataType": "JSON"},
        ]}}
        milvus_post(uri, token, "/v2/vectordb/collections/create", schema, timeout)
    index = {
        "collectionName": collection,
        "indexParams": [{
            "fieldName": VECTOR_FIELD,
            "indexName": "vector_index",
            "metricType": "COSINE",
            "indexType": "FLAT",
            "params": {},
        }],
    }
    try:
        milvus_post(uri, token, "/v2/vectordb/indexes/create", index, timeout)
    except RuntimeError as error:
        # An earlier successful run owns the same named index; its schema and metric
        # are the stable gaokao_math contract, so it is safe to retain it.
        if "exist" not in str(error).lower():
            raise
    milvus_post(uri, token, "/v2/vectordb/collections/load", {"collectionName": collection}, timeout)


def process_page(job: tuple[int, Path, int, Path], run_id: str, settings: dict[str, Any], arguments: argparse.Namespace,
                 source_root: Path, container_input_root: str, configured_page_workers: int) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    """Processes one page independently so bounded workers never share page assets, evidence paths, or token rows."""
    task_sequence, pdf, page, paper_root = job
    task_started_at = utc_now()
    original = paper_root / f"page-{page}.png"
    compressed = paper_root / f"page-{page}-initial-review.jpg"
    render_page(pdf, page, original, source_root, container_input_root, arguments.evidence_root)
    compress_for_luna(original, compressed, int(settings["pageInitialReviewMaxLongEdgePixels"]), float(settings["pageInitialReviewJpegQuality"]))
    request = luna_request(compressed, pdf.name, page)
    try:
        status, response, elapsed_ms, attempts = call_luna(request, "", "", arguments.timeout_seconds, arguments.timeout_grace_seconds, configured_page_workers)
    except Exception as error:
        failure = {"timestampUtc": utc_now(), "taskSequence": task_sequence, "workerThread": threading.current_thread().name, "taskStartedAt": task_started_at, "runId": run_id, "model": LUNA_MODEL, "sourceFile": pdf.name, "page": page, "request": request, "errorType": type(error).__name__, "error": str(error), "configuredHttpTimeoutSeconds": arguments.timeout_seconds, "configuredProcessGraceSeconds": arguments.timeout_grace_seconds, "credentialHandling": "Authorization was used only for transport and is omitted from evidence."}
        (paper_root / f"page-{page}-luna-request-failure.json").write_text(json.dumps(failure, ensure_ascii=False, indent=2), encoding="utf-8")
        raise
    usage = response.get("usage", {}) if isinstance(response, dict) else {}
    call_evidence = {"timestampUtc": utc_now(), "taskSequence": task_sequence, "workerThread": threading.current_thread().name, "taskStartedAt": task_started_at, "taskCompletedAt": utc_now(), "runId": run_id, "model": LUNA_MODEL, "sourceFile": pdf.name, "page": page, "image": {"original": str(original), "originalSha256": sha256_file(original), "compressed": str(compressed), "compressedSha256": sha256_file(compressed)}, "request": request, "responseHttpStatus": status, "response": response, "usage": usage, "elapsedMs": elapsed_ms, "attempts": attempts, "credentialHandling": "Authorization was used only for transport and is omitted from evidence."}
    (paper_root / f"page-{page}-luna-request-response.json").write_text(json.dumps(call_evidence, ensure_ascii=False, indent=2), encoding="utf-8")
    return recognized_questions(response, pdf.name, page), {"taskSequence": task_sequence, "workerThread": threading.current_thread().name, "sourceFile": pdf.name, "page": page, "usage": usage, "elapsedMs": elapsed_ms, "attemptCount": len(attempts)}


def main() -> None:
    parser = argparse.ArgumentParser(description="Process the configured 2024 PDFs through Luna, embeddings and Milvus")
    parser.add_argument("--config", type=Path, default=DEFAULT_CONFIG)
    parser.add_argument("--evidence-root", type=Path, default=DEFAULT_EVIDENCE_ROOT)
    parser.add_argument("--collection", default=DEFAULT_COLLECTION)
    parser.add_argument("--timeout-seconds", type=int, default=DEFAULT_TIMEOUT_SECONDS)
    parser.add_argument("--timeout-grace-seconds", type=int, default=DEFAULT_TIMEOUT_GRACE_SECONDS)
    parser.add_argument("--page-workers", type=int,
                        help="optional lower per-run cap; it can never exceed the global AI concurrency limit")
    parser.add_argument("--finalize-run-id", help="resume only the embedding, Milvus and recall stages from durable page evidence")
    arguments = parser.parse_args()
    config = json.loads(arguments.config.read_text(encoding="utf-8"))
    dotenv = load_dotenv(PROJECT_ROOT / ".env")
    api_key = setting("OPENAI_API_KEY", dotenv)
    base_url = setting("OPENAI_BASE_URL", dotenv)
    if not api_key or not base_url:
        raise RuntimeError("OPENAI_API_KEY and OPENAI_BASE_URL must be configured before real Luna ingestion")
    global_ai_concurrency = int(setting(GLOBAL_AI_CONCURRENCY_ENVIRONMENT_VARIABLE, dotenv, str(DEFAULT_GLOBAL_AI_CONCURRENCY)))
    requested_page_workers = arguments.page_workers or global_ai_concurrency
    if global_ai_concurrency < 1 or requested_page_workers < 1:
        raise ValueError("global AI concurrency and --page-workers must be at least one")
    if requested_page_workers > global_ai_concurrency:
        raise ValueError(f"--page-workers={requested_page_workers} exceeds global {GLOBAL_AI_CONCURRENCY_ENVIRONMENT_VARIABLE}={global_ai_concurrency}")
    source_root = Path(config["sourceRootWsl"])
    files = [source_root / name for name in config["selectedFileNames"]]
    missing = [str(path) for path in files if not path.is_file()]
    if missing:
        raise RuntimeError(f"configured 2024 source PDFs are missing: {missing}")
    run_id = arguments.finalize_run_id or f"luna-2024-{datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')}-{uuid.uuid4().hex[:8]}"
    run_root = arguments.evidence_root / "runs" / run_id
    if arguments.evidence_root.resolve() != DEFAULT_EVIDENCE_ROOT.resolve():
        raise ValueError("--evidence-root must remain the mounted project output/gaokao-evidence/2024 directory")
    settings = config["lunaVisionOptimization"]
    all_questions: list[dict[str, Any]] = []
    model_calls: list[dict[str, Any]] = []
    if arguments.finalize_run_id:
        evidence_files = sorted(run_root.rglob("*-luna-request-response.json"))
        if not evidence_files:
            raise RuntimeError(f"no durable Luna response evidence exists for run {run_id}")
        for evidence_file in evidence_files:
            evidence = json.loads(evidence_file.read_text(encoding="utf-8"))
            all_questions.extend(recognized_questions(evidence["response"], evidence["sourceFile"], int(evidence["page"])))
            model_calls.append({"taskSequence": evidence.get("taskSequence"), "workerThread": evidence.get("workerThread"), "sourceFile": evidence["sourceFile"], "page": evidence["page"], "usage": evidence.get("usage", {}), "elapsedMs": evidence.get("elapsedMs"), "attemptCount": len(evidence.get("attempts", [])), "recoveredFromEvidence": True})
    else:
        # Compile/extract once before worker dispatch. The renderer cache is read-only afterwards, avoiding a race
        # while the workers deliberately own different page paths and provider subprocesses.
        ensure_pdf_renderer()
        jobs = [(sequence, pdf, page, run_root / sha256_file(pdf)) for sequence, (pdf, page) in enumerate(((pdf, page) for pdf in files for page in range(1, page_count(pdf) + 1)), start=1)]
        failures: list[str] = []
        with ThreadPoolExecutor(max_workers=requested_page_workers, thread_name_prefix="gaokao-luna-page") as executor:
            futures = {executor.submit(process_page, job, run_id, settings, arguments, source_root, config["containerInputRoot"], requested_page_workers): job for job in jobs}
            for future in as_completed(futures):
                _sequence, pdf, page, _paper_root = futures[future]
                try:
                    questions, model_call = future.result()
                    all_questions.extend(questions)
                    model_calls.append(model_call)
                except Exception as error:
                    failures.append(f"{pdf.name} page {page}: {error}")
        if failures:
            raise RuntimeError("one or more page tasks failed; see page-level failure evidence: " + " | ".join(failures))
    model_calls.sort(key=lambda call: call["taskSequence"])
    if not all_questions:
        raise RuntimeError("Luna completed but returned no non-empty questions; refusing to create an empty success report")
    worker_key = setting("MATH_AGENT_WORKER_API_KEY", dotenv)
    vectors = embed_all([item["text"] for item in all_questions], setting("MATH_AGENT_EMBEDDING_BASE_URL", dotenv, DEFAULT_EMBEDDING_URL), worker_key, arguments.timeout_seconds)
    milvus_uri = setting("MATH_AGENT_VECTOR_INDEX_MILVUS_URI", dotenv, DEFAULT_MILVUS_URI)
    milvus_token = setting("MATH_AGENT_MILVUS_TOKEN", dotenv) or ("root:" + setting("MATH_AGENT_MILVUS_ROOT_PASSWORD", dotenv) if setting("MATH_AGENT_MILVUS_ROOT_PASSWORD", dotenv) else "")
    ensure_collection(milvus_uri, milvus_token, arguments.collection, arguments.timeout_seconds)
    entities = [{PRIMARY_KEY_FIELD: item["id"], VECTOR_FIELD: vector, TEXT_FIELD: item["text"], METADATA_FIELD: item["metadata"]} for item, vector in zip(all_questions, vectors, strict=True)]
    # Upsert makes evidence recovery idempotent: a repeat never creates a second
    # vector for the deterministic question key after an interrupted finalization.
    milvus_post(milvus_uri, milvus_token, "/v2/vectordb/entities/upsert", {"collectionName": arguments.collection, "data": entities}, arguments.timeout_seconds)
    # Milvus v2's REST FlushReq accepts one collectionName (unlike older SDK APIs
    # which exposed a plural collectionNames list), so keep this payload versioned
    # to the same v2 REST contract used by every other operation in this script.
    milvus_post(milvus_uri, milvus_token, "/v2/vectordb/collections/flush", {"collectionName": arguments.collection}, arguments.timeout_seconds)
    query_vector = embed_all([all_questions[0]["text"]], setting("MATH_AGENT_EMBEDDING_BASE_URL", dotenv, DEFAULT_EMBEDDING_URL), worker_key, arguments.timeout_seconds)[0]
    recalled = milvus_post(milvus_uri, milvus_token, "/v2/vectordb/entities/search", {"collectionName": arguments.collection, "data": [query_vector], "annsField": VECTOR_FIELD, "limit": RETRIEVAL_LIMIT, "outputFields": [PRIMARY_KEY_FIELD, TEXT_FIELD, METADATA_FIELD]}, arguments.timeout_seconds)
    hits = recalled.get("data", [])
    if not hits or not any(hit.get("id") == all_questions[0]["id"] or hit.get("entity", {}).get(PRIMARY_KEY_FIELD) == all_questions[0]["id"] for hit in hits):
        raise RuntimeError("real Milvus recall did not return the inserted query question")
    totals = {name: sum(int(call["usage"].get(name, 0) or 0) for call in model_calls) for name in ("prompt_tokens", "completion_tokens", "total_tokens")}
    report = {"timestampUtc": utc_now(), "runId": run_id, "model": LUNA_MODEL, "selectedFileCount": len(files), "lunaCallCount": len(model_calls), "questionCount": len(all_questions), "usage": totals, "concurrency": {"environmentVariable": GLOBAL_AI_CONCURRENCY_ENVIRONMENT_VARIABLE, "globalLimit": global_ai_concurrency, "effectivePageWorkers": requested_page_workers, "retryScope": "per_request_only", "maxAttemptsPerRequest": LUNA_MAX_ATTEMPTS}, "collection": arguments.collection, "realRecall": {"queryQuestionId": all_questions[0]["id"], "hitCount": len(hits), "hits": hits}, "modelCalls": model_calls, "evidenceRoot": str(run_root)}
    report_path = arguments.evidence_root / f"{run_id}-report.json"
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({"report": str(report_path), "runId": run_id, "questionCount": len(all_questions), "usage": totals, "recallHitCount": len(hits)}, ensure_ascii=False))


if __name__ == "__main__":
    main()
