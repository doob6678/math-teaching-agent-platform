"""Run the audited mathematics-PDF ingestion path against real vision, embeddings and Milvus.

This is deliberately a single command: every selected PDF page is rendered to an
original PNG, compressed to a bounded JPEG for the configured vision provider, and
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
import re
import subprocess
import shutil
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


PROJECT_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_CONFIG = PROJECT_ROOT / "config" / "gaokao-ingestion-2024.json"
DEFAULT_EVIDENCE_ROOT = PROJECT_ROOT / "output" / "math-paper-corpus"
ALLOWED_EVIDENCE_OUTPUT_ROOT = PROJECT_ROOT / "output"
TRANSCRIPTION_RUN_ROOT = PROJECT_ROOT / "output" / "math-paper-transcription-runs"
DEFAULT_TERRA_VISION_MODEL = "gpt-5.6-terra"
DEFAULT_LUNA_VISION_MODEL = "gpt-5.6-luna"
DEFAULT_TIMEOUT_SECONDS = 120
DEFAULT_EMBEDDING_MODEL = "local_bge_embedding"
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
MILVUS_MAX_ATTEMPTS = 3
DEFAULT_GLOBAL_AI_CONCURRENCY = 20
GLOBAL_AI_CONCURRENCY_ENVIRONMENT_VARIABLE = "MATH_AGENT_AGENT_WORKER_MAX_CONCURRENCY"
RENDERER_CLASS = "RenderPdfEvidencePage"
_renderer_ready = False
FRACTION_SLASH_PATTERN = re.compile(r"(?<!\\\\)\b(?:[A-Za-z0-9)}]+)\s*/\s*(?:[A-Za-z0-9({]+)\b")
# Terra can preserve an exam's displayed number as ``第 1 题`` or ``1．``. Only those
# exact visual equivalents may become a canonical selector; subquestions and descriptive
# annotations remain non-canonical and cannot create a separate published question.
CANONICAL_QUESTION_NUMBER_PATTERN = re.compile(r"^\s*(?:第\s*)?([1-9]\d{0,2})\s*(?:[.、．]|题)?\s*$")


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


def resolve_selected_files(config: dict[str, Any], source_root: Path) -> list[Path]:
    """Resolves the sole explicit PDF whitelist without permitting traversal or source-root escape."""
    if "selectedFiles" not in config or "selectedFileNames" in config:
        raise ValueError("configuration must contain only non-empty selectedFiles")
    selected = config["selectedFiles"]
    if not isinstance(selected, list) or not selected:
        raise ValueError("selectedFiles must be a non-empty list")
    resolved_root = source_root.resolve()
    files: list[Path] = []
    normalized_selectors: set[str] = set()
    base_names: set[str] = set()
    for selector in selected:
        if not isinstance(selector, str) or not selector.strip() or "\\" in selector:
            raise ValueError("selectedFiles entries must be non-empty POSIX relative paths")
        relative = Path(selector)
        if relative.is_absolute() or any(part in {"", ".", ".."} for part in relative.parts):
            raise ValueError("selectedFiles entries must not be absolute or traverse directories")
        if relative.suffix.lower() != ".pdf":
            raise ValueError("selectedFiles entries must name PDF files")
        normalized = relative.as_posix()
        if normalized in normalized_selectors:
            raise ValueError("selectedFiles contains duplicate normalized paths")
        normalized_selectors.add(normalized)
        candidate = (resolved_root / relative).resolve()
        if not candidate.is_relative_to(resolved_root):
            raise ValueError("selectedFiles entry escapes sourceRootWsl")
        if not candidate.is_file():
            raise FileNotFoundError(f"configured source PDF is missing: {normalized}")
        if candidate.name in base_names:
            raise ValueError("selectedFiles cannot contain duplicate PDF base names")
        base_names.add(candidate.name)
        files.append(candidate)
    return files


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
    from pypdf import PdfReader

    return len(PdfReader(str(pdf)).pages)


def vision_request(image: Path, paper: str, page: int, model: str) -> dict[str, Any]:
    """Build a provider-neutral visual request while retaining its source image for replayable evidence."""
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
            "pageText is the authoritative complete transcription of this page. Preserve mathematical formulas as LaTex strings in latex. Every visible mathematical fraction MUST use \\frac{numerator}{denominator}; never write a fraction as a/b, 1/2, or x/y in a latex field.",
            "Do not invent an answer, solution, unshown text, or an official correctness judgement.",
            "Use an empty questions list if no question is visible."
        ],
    }
    return {
        "model": model,
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


def call_luna(request: dict[str, Any], timeout: int, grace_seconds: int, configured_page_workers: int,
               bridge_container: str) -> tuple[int, dict[str, Any], int, list[dict[str, Any]]]:
    """Make one visual request from the healthy Docker network with a hard parent-process deadline.

    WSL's direct socket can remain blocked beyond the HTTP library deadline. The worker
    already has the configured provider secret and Docker DNS route; this bridge gets an
    unconditional subprocess deadline. Calls remain serial, not a page worker pool.
    """
    if timeout < 1 or grace_seconds < 0 or not bridge_container.strip():
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
            # The bridge target is deployment configuration, never a Compose-generated container name. This keeps
            # the runner usable with another project name, replicated worker, or an explicitly selected worker.
            result = subprocess.run(["docker", "exec", "-i", "-e", f"LUNA_HTTP_TIMEOUT_SECONDS={timeout}", bridge_container, "python", "-c", bridge], input=json.dumps(request, ensure_ascii=False), capture_output=True, text=True, encoding="utf-8", timeout=timeout + grace_seconds, check=False)
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


def canonical_question_number(value: Any) -> str:
    """Converts only unambiguous visually printed question-number formats to the canonical numeric selector."""
    matched = CANONICAL_QUESTION_NUMBER_PATTERN.fullmatch(str(value or ""))
    return matched.group(1) if matched else ""


def recognized_questions(response: dict[str, Any], source_name: str, page: int, provider: str,
                         question_assets: dict[str, list[dict[str, Any]]]) -> list[dict[str, Any]]:
    """Accept only structured visual output and create immutable source-backed vector payloads."""
    try:
        content = response["choices"][0]["message"]["content"]
        parsed = json.loads(content) if isinstance(content, str) else content
    except (KeyError, IndexError, TypeError, json.JSONDecodeError) as error:
        raise RuntimeError(f"{provider} did not return a parseable JSON transcription: {error}") from error
    questions = parsed.get("questions")
    if not isinstance(questions, list):
        raise RuntimeError(f"{provider} transcription has no questions array")
    output: list[dict[str, Any]] = []
    for item in questions:
        if not isinstance(item, dict) or not str(item.get("text", "")).strip():
            continue
        latex = item.get("latex", [])
        if not isinstance(latex, list):
            latex = []
        latex = [str(value).strip() for value in latex if str(value).strip()]
        for formula in latex:
            if FRACTION_SLASH_PATTERN.search(formula):
                raise RuntimeError(f"{provider} latex fraction must use \\frac{{numerator}}{{denominator}}, not slash notation")
        text = str(item["text"]).strip()
        vector_text = text + ("\n" + "\n".join(map(str, latex)) if latex else "")
        question_number = canonical_question_number(item.get("number"))
        # Non-canonical labels (subquestions/continuations) have no independent source
        # identity. Retain them only as a possible explicitly flagged continuation.
        if not question_number and not bool(item.get("continuesToNextPage", False)):
            continue
        # The key derives only from immutable visual evidence. A recovery run can
        # therefore upsert the same question instead of creating a fresh duplicate.
        stable_identity = f"{source_name}\n{page}\n{question_number}\n{vector_text}"
        output.append({
            "id": str(uuid.uuid5(uuid.NAMESPACE_URL, stable_identity)), "text": vector_text,
            "metadata": {"sourceFile": source_name, "page": page, "pageStart": page, "pageEnd": page, "questionNumber": question_number, "latex": latex, "confidence": item.get("confidence"), "continuesToNextPage": bool(item.get("continuesToNextPage", False)), "extraction": f"{provider.upper()}_VISUAL_PAGE", "questionAssets": question_assets.get(question_number, [])},
        })
    return output


def recognized_page_text(response: dict[str, Any], provider: str) -> str:
    """读取模型声明的页级完整转写，拒绝将局部题干误发布为整页正文。"""
    try:
        content = response["choices"][0]["message"]["content"]
        parsed = json.loads(content) if isinstance(content, str) else content
    except (KeyError, IndexError, TypeError, json.JSONDecodeError) as error:
        raise RuntimeError(f"{provider} did not return a parseable JSON transcription: {error}") from error
    page_text = str(parsed.get("pageText", "")).strip() if isinstance(parsed, dict) else ""
    if not page_text:
        raise RuntimeError(f"{provider} transcription has no authoritative pageText")
    return page_text


def canonical_question_records(questions: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """Keeps one earliest visually sourced record per paper/question number for collision-free Markdown publication."""
    selected: list[dict[str, Any]] = []
    seen: set[tuple[str, str]] = set()
    for question in sorted(questions, key=lambda item: (
            str(item["metadata"].get("sourceFile", "")),
            int(item["metadata"].get("pageStart", item["metadata"].get("page", 0))),
            str(item["metadata"].get("questionNumber", "")))):
        key = (str(question["metadata"].get("sourceFile", "")), str(question["metadata"].get("questionNumber", "")))
        if not key[0] or not key[1] or key in seen:
            continue
        seen.add(key)
        selected.append(question)
    return selected


def merge_cross_page_questions(questions: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """Joins only an explicitly flagged page tail to an unnumbered next-page fragment from the same PDF.

    A new printed number is authoritative evidence of a distinct question, so it is never merged even when Luna
    incorrectly marked the previous page as continuing. The merged identity is recalculated from its full evidence.
    """
    ordered = sorted(questions, key=lambda item: (str(item["metadata"].get("sourceFile", "")), int(item["metadata"].get("pageStart", item["metadata"].get("page", 0))), str(item["id"])))
    merged: list[dict[str, Any]] = []
    for current in ordered:
        metadata = current["metadata"]
        if merged:
            previous = merged[-1]
            previous_metadata = previous["metadata"]
            can_merge = (
                previous_metadata.get("sourceFile") == metadata.get("sourceFile")
                and bool(previous_metadata.get("continuesToNextPage"))
                and not str(metadata.get("questionNumber", "")).strip()
                and int(metadata.get("pageStart", metadata.get("page", 0))) == int(previous_metadata.get("pageEnd", previous_metadata.get("page", 0))) + 1
            )
            if can_merge:
                combined_latex = list(previous_metadata.get("latex", [])) + list(metadata.get("latex", []))
                combined_text = "\n".join(part for part in [previous["text"], current["text"], *combined_latex[len(previous_metadata.get("latex", [])):]] if part)
                previous["text"] = combined_text
                previous_metadata["latex"] = combined_latex
                previous_metadata.setdefault("pageStart", previous_metadata.get("page"))
                previous_metadata["pageEnd"] = metadata.get("pageEnd", metadata.get("page"))
                previous_metadata["continuesToNextPage"] = bool(metadata.get("continuesToNextPage"))
                previous_metadata["questionAssets"] = list(previous_metadata.get("questionAssets", [])) + list(metadata.get("questionAssets", []))
                identity = f"{previous_metadata['sourceFile']}\n{previous_metadata['pageStart']}\n{previous_metadata['pageEnd']}\n{previous_metadata.get('questionNumber', '')}\n{combined_text}"
                previous["id"] = str(uuid.uuid5(uuid.NAMESPACE_URL, identity))
                continue
        merged.append({"id": current["id"], "text": current["text"], "metadata": dict(metadata)})
    return merged


def load_question_assets(asset_root: Path, source_file: Path) -> dict[str, list[dict[str, Any]]]:
    """Load source-hash-verified crop references so visual transcription and downstream rendering share one asset contract."""
    report_path = asset_root / "asset-report.json"
    manifest_path = asset_root / "question-assets.jsonl"
    if not report_path.is_file() or not manifest_path.is_file():
        raise FileNotFoundError(f"question assets are required before visual ingestion: {asset_root}")
    report = json.loads(report_path.read_text(encoding="utf-8"))
    if report.get("sourceSha256") != sha256_file(source_file):
        raise RuntimeError(f"question asset source hash does not match selected PDF: {source_file.name}")
    assets: dict[str, list[dict[str, Any]]] = {}
    for line in manifest_path.read_text(encoding="utf-8").splitlines():
        item = json.loads(line)
        relative_path = str(item.get("relativeAssetPath", ""))
        asset_path = asset_root / relative_path
        if not relative_path or not asset_path.is_file():
            raise RuntimeError(f"question asset manifest has no readable figure: {asset_path}")
        if item.get("sourceSha256") != report["sourceSha256"]:
            raise RuntimeError(f"question asset source hash is missing or invalid: {asset_path}")
        actual_asset_sha256 = sha256_file(asset_path)
        if item.get("assetSha256") != actual_asset_sha256:
            raise RuntimeError(f"question asset hash is missing or invalid: {asset_path}")
        # 文件系统位置只在本进程发布规范材料时使用。以下划线开头的临时字段绝不能进入
        # Milvus metadata、RAG 载荷或模型上下文；可持久谱系只使用不可逆的资产标识和哈希。
        source_sha256 = str(report["sourceSha256"])
        asset_id = str(uuid.uuid5(uuid.NAMESPACE_URL, f"asset\n{source_sha256}\n{actual_asset_sha256}"))
        assets.setdefault(str(item["questionNumber"]), []).append({
            "assetId": asset_id,
            "assetSha256": actual_asset_sha256,
            "sourceSha256": source_sha256,
            "pageNumber": item["pageNumber"],
            "bboxPixels": item["bboxPixels"],
            "bindingMethod": item["bindingMethod"],
            "_sourceAssetPath": asset_path,
        })
    return assets


def canonical_paper_directory_name(source_file: Path) -> str:
    """以原始完整文件名（去扩展名）命名发布目录，并拒绝跨平台不可读的路径片段。"""
    name = source_file.name.strip()
    if not name or name in {".", ".."} or any(character in name for character in "\\/:*?\"<>|"):
        raise ValueError(f"source filename cannot name a canonical paper directory: {source_file.name}")
    return name


def copy_source_asset(source: Path, destination: Path) -> None:
    """复制经过哈希验证的来源图像，令发布材料在其试卷目录内可独立审阅。"""
    if not source.is_file():
        raise FileNotFoundError(f"source asset is unavailable: {source}")
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source, destination)
    if sha256_file(source) != sha256_file(destination):
        raise RuntimeError(f"copied asset hash mismatch: {destination}")


def publish_canonical_paper(
        corpus_root: Path,
        source_file: Path,
        source_sha256: str,
        page_texts: dict[int, str],
        questions: list[dict[str, Any]],
        asset_root: Path) -> dict[str, Any]:
    """发布单份试卷的可读全文、逐题材料及来源图片，作为唯一 RAG 证据目录。"""
    paper_root = corpus_root / canonical_paper_directory_name(source_file)
    if paper_root.exists():
        # A durable evidence finalization can reach the vector stage after every canonical
        # file was atomically published. Never overwrite that evidence: reuse it only when
        # the manifest proves it belongs to this exact source PDF.
        manifest_path = paper_root / "source-manifest.json"
        document_path = paper_root / "document.md"
        if not manifest_path.is_file() or not document_path.is_file():
            raise FileExistsError(f"incomplete canonical paper output cannot be reused: {paper_root}")
        existing_manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        if (existing_manifest.get("documentFullName") != source_file.name
                or existing_manifest.get("sourceSha256") != source_sha256):
            raise FileExistsError(f"canonical paper output belongs to another source: {paper_root}")
        return {"paperRoot": paper_root, "documentPath": document_path, "content": document_path.read_text(encoding="utf-8")}
    paper_root.mkdir(parents=True)
    paper_questions = [item for item in questions if item["metadata"].get("sourceFile") == source_file.name]
    document_lines = [f"# {source_file.name}", "", f"- 来源 SHA-256：`{source_sha256}`", "- 正文权威来源：Terra 页级视觉转写。", ""]
    page_index: list[dict[str, Any]] = []
    question_index: list[dict[str, Any]] = []
    for page_number, page_text in sorted(page_texts.items()):
        source_image = asset_root / "page-images" / f"page-{page_number:03d}.png"
        relative_image = Path("page-images") / f"page-{page_number:03d}.png"
        copied_image = paper_root / relative_image
        copy_source_asset(source_image, copied_image)
        page_asset_id = str(uuid.uuid5(uuid.NAMESPACE_URL, f"page\n{source_sha256}\n{page_number}"))
        page_index.append({
            "pageNo": page_number,
            "canonicalAssetPath": relative_image.as_posix(),
            "assetId": page_asset_id,
            "assetSha256": sha256_file(copied_image),
        })
        document_lines.extend([f"## 第 {page_number} 页", "", f"![第 {page_number} 页]({relative_image.as_posix()})", "", page_text, ""])
    document_lines.extend(["# 题目索引", ""])
    for question in sorted(paper_questions, key=lambda item: (int(item["metadata"].get("pageStart", 0)), str(item["metadata"].get("questionNumber", "")))):
        metadata = question["metadata"]
        number = str(metadata.get("questionNumber", "未编号")).strip() or "未编号"
        if not number.isdigit():
            raise RuntimeError(f"canonical publication requires a numeric question number: {source_file.name}")
        file_stem = number.zfill(3)
        question_file = Path("questions") / f"q-{file_stem}.md"
        page_start = int(metadata.get("pageStart", metadata.get("page", 0)))
        page_end = int(metadata.get("pageEnd", page_start))
        source_pages = list(range(page_start, page_end + 1))
        if page_start < 1 or page_end < page_start or any(page not in page_texts for page in source_pages):
            raise RuntimeError(f"canonical question page range is not present in transcription: {source_file.name} #{number}")
        question_lines = [
            f"# {source_file.name} 第 {number} 题", "", f"- 来源页：{page_start} 至 {page_end}",
            f"- 来源题目：{number}",
            f"- 跨页连续：{'是' if len(source_pages) > 1 else '否'}", "", question["text"], "",
        ]
        copied_assets: list[dict[str, Any]] = []
        manifest_assets: list[dict[str, Any]] = []
        for asset_index, asset in enumerate(metadata.get("questionAssets", []), start=1):
            source_asset = asset.get("_sourceAssetPath")
            if not isinstance(source_asset, Path):
                raise RuntimeError("question asset has no private publication path")
            relative_asset = Path("figures") / f"q-{file_stem}-{asset_index:02d}{source_asset.suffix.lower()}"
            copied_asset_path = paper_root / relative_asset
            copy_source_asset(source_asset, copied_asset_path)
            copied_asset = {key: value for key, value in asset.items() if not key.startswith("_")}
            copied_asset["assetSha256"] = sha256_file(copied_asset_path)
            copied_assets.append(copied_asset)
            manifest_asset = {
                **copied_asset,
                "canonicalAssetPath": relative_asset.as_posix(),
            }
            manifest_assets.append(manifest_asset)
            question_lines.extend([f"![第 {number} 题图]({relative_asset.as_posix()})", ""])
        question_path = paper_root / question_file
        question_path.parent.mkdir(parents=True, exist_ok=True)
        question_path.write_text("\n".join(question_lines), encoding="utf-8")
        page_asset_ids = [
            str(uuid.uuid5(uuid.NAMESPACE_URL, f"page\n{source_sha256}\n{page}")) for page in source_pages
        ]
        metadata["pageAssetIds"] = page_asset_ids
        metadata["questionAssets"] = copied_assets
        metadata["sourcePages"] = source_pages
        metadata["crossPageContinuity"] = {"present": len(source_pages) > 1, "pageBoundaries": [
            {"fromPage": page, "toPage": page + 1} for page in source_pages[:-1]
        ]}
        question_index.append({
            "questionNumber": number,
            "questionId": question["id"],
            "questionMarkdown": question_file.as_posix(),
            "questionMarkdownSha256": sha256_file(question_path),
            "sourcePages": source_pages,
            "crossPageContinuity": metadata["crossPageContinuity"],
            "assetIds": [asset["assetId"] for asset in copied_assets] + page_asset_ids,
            "assets": manifest_assets,
        })
        document_lines.extend([f"- [第 {number} 题]({question_file.as_posix()})", ""])
    document_path = paper_root / "document.md"
    document_path.write_text("\n".join(document_lines), encoding="utf-8")
    manifest = {
        "documentFullName": source_file.name,
        "sourceSha256": source_sha256,
        "authoritativeTranscription": "TERRA_VISUAL_PAGE",
        "documentMarkdown": "document.md",
        "documentMarkdownSha256": sha256_file(document_path),
        "questionCount": len(question_index),
        "pageCount": len(page_index),
        "pages": page_index,
        "questions": question_index,
    }
    (paper_root / "source-manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    return {"paperRoot": paper_root, "documentPath": document_path, "content": document_path.read_text(encoding="utf-8")}


def vector_metadata(record: dict[str, Any]) -> dict[str, Any]:
    """拒绝将路径或发布临时字段写入向量库，令 RAG 只能消费不透明来源谱系。"""
    metadata = record.get("metadata", {})
    if not isinstance(metadata, dict):
        raise ValueError("vector record metadata must be an object")
    # Formula strings are already part of the embedded text and canonical Markdown.
    # Excluding this redundant field prevents the path guard treating TeX backslashes as paths.
    metadata = {key: value for key, value in metadata.items() if key != "latex"}
    forbidden_tokens = ("path", "root", "directory", "file://", "\\\\", "/app/", "/mnt/", "c:/", "d:/")

    def validate(value: Any, key: str = "") -> Any:
        normalized_key = key.lower()
        if any(token in normalized_key for token in forbidden_tokens[:3]) or normalized_key.startswith("_"):
            raise ValueError(f"vector metadata contains a filesystem key: {key}")
        if isinstance(value, dict):
            return {
                child_key: validate(child_value, str(child_key))
                for child_key, child_value in value.items()
                if not str(child_key).startswith("_")
            }
        if isinstance(value, list):
            return [validate(child) for child in value]
        if isinstance(value, str) and any(token in value.lower() for token in forbidden_tokens[3:]):
            raise ValueError("vector metadata contains a filesystem value")
        return value

    return validate(metadata)


def resolve_vision_bridge_container(configured_override: str = "") -> str:
    """解析当前健康的 Compose ai-worker，允许非密钥覆盖但拒绝停止或未就绪容器。"""
    candidates: list[str] = []
    if configured_override.strip():
        candidates.append(configured_override.strip())
    else:
        result = subprocess.run(
            ["docker", "compose", "-f", str(PROJECT_ROOT / "docker-compose.yml"), "ps", "-q", "ai-worker"],
            capture_output=True, text=True, encoding="utf-8", check=False)
        if result.returncode != 0:
            raise RuntimeError("cannot inspect Compose ai-worker; start the project Compose stack before visual ingestion")
        candidates.extend(line.strip() for line in result.stdout.splitlines() if line.strip())
    for candidate in candidates:
        inspection = subprocess.run(
            ["docker", "inspect", "--format", "{{.State.Status}} {{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}", candidate],
            capture_output=True, text=True, encoding="utf-8", check=False)
        state = inspection.stdout.strip().lower()
        if inspection.returncode == 0 and state == "running healthy":
            return candidate
    if configured_override.strip():
        raise RuntimeError("configured vision bridge container is not running and healthy")
    raise RuntimeError("no healthy Compose ai-worker container exists; start the Compose ai-worker and wait for its health check")


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
    """Call Milvus REST with bounded retry for transient transport/server failures.

    Upsert, index creation and collection load are idempotent for this runner's
    deterministic records.  A connection reset must therefore be retried rather
    than turning an otherwise valid evidence recovery into a false failure.
    """
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    endpoint = urljoin(uri.rstrip("/") + "/", path.lstrip("/"))
    last_error: Exception | None = None
    for attempt in range(1, MILVUS_MAX_ATTEMPTS + 1):
        try:
            response = requests.post(endpoint, headers=headers, json=body, timeout=timeout)
            if response.status_code in {429, 500, 502, 503, 504}:
                raise RuntimeError(f"Milvus transient HTTP {response.status_code}: {response.text[:1000]}")
            try:
                payload = response.json()
            except ValueError as error:
                raise RuntimeError(f"Milvus {path} returned non-JSON HTTP {response.status_code}: {response.text[:1000]}") from error
            if not response.ok or payload.get("code", 0) != 0:
                raise RuntimeError(f"Milvus {path} failed: {payload}")
            return payload
        except (requests.RequestException, RuntimeError) as error:
            last_error = error
            if attempt == MILVUS_MAX_ATTEMPTS:
                break
            time.sleep(luna_retry_delay_seconds(attempt))
    raise RuntimeError(f"Milvus {path} failed after {MILVUS_MAX_ATTEMPTS} attempts: {last_error}") from last_error


def search_hits(response: dict[str, Any]) -> list[dict[str, Any]]:
    """Normalize Milvus v2's one-query nested rows before checking the deterministic inserted ID.

    Some deployed v2 REST gateways return ``data`` as a single list of hits,
    while others retain one list per query vector.  Both describe the same real
    search response, so flatten only list envelopes and preserve each hit object.
    """
    raw_hits = response.get("data", [])
    if not isinstance(raw_hits, list):
        raise RuntimeError("Milvus search response has no list data field")
    normalized: list[dict[str, Any]] = []
    pending: list[Any] = list(raw_hits)
    while pending:
        current = pending.pop(0)
        if isinstance(current, list):
            pending[0:0] = current
        elif isinstance(current, dict):
            normalized.append(current)
        else:
            raise RuntimeError("Milvus search response contains a non-object hit")
    return normalized


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
                source_root: Path, container_input_root: str, configured_page_workers: int,
                question_assets: dict[str, list[dict[str, Any]]]) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    """Processes one page independently so bounded workers never share page assets, evidence paths, or token rows."""
    task_sequence, pdf, page, paper_root = job
    task_started_at = utc_now()
    original = paper_root / f"page-{page}.png"
    compressed = paper_root / f"page-{page}-initial-review.jpg"
    render_page(pdf, page, original, source_root, container_input_root, arguments.evidence_root)
    compress_for_luna(original, compressed, int(settings["pageInitialReviewMaxLongEdgePixels"]), float(settings["pageInitialReviewJpegQuality"]))
    request = vision_request(compressed, pdf.name, page, arguments.vision_model)
    try:
        status, response, elapsed_ms, attempts = call_luna(request, arguments.timeout_seconds, arguments.timeout_grace_seconds, configured_page_workers, arguments.vision_bridge_container)
    except Exception as error:
        failure = {"timestampUtc": utc_now(), "taskSequence": task_sequence, "workerThread": threading.current_thread().name, "taskStartedAt": task_started_at, "runId": run_id, "provider": arguments.vision_provider, "model": arguments.vision_model, "sourceFile": pdf.name, "page": page, "request": request, "errorType": type(error).__name__, "error": str(error), "configuredHttpTimeoutSeconds": arguments.timeout_seconds, "configuredProcessGraceSeconds": arguments.timeout_grace_seconds, "credentialHandling": "Authorization was used only for transport and is omitted from evidence."}
        (paper_root / f"page-{page}-{arguments.vision_provider}-request-failure.json").write_text(json.dumps(failure, ensure_ascii=False, indent=2), encoding="utf-8")
        raise
    usage = response.get("usage", {}) if isinstance(response, dict) else {}
    call_evidence = {"timestampUtc": utc_now(), "taskSequence": task_sequence, "workerThread": threading.current_thread().name, "taskStartedAt": task_started_at, "taskCompletedAt": utc_now(), "runId": run_id, "provider": arguments.vision_provider, "model": arguments.vision_model, "sourceFile": pdf.name, "page": page, "image": {"original": str(original), "originalSha256": sha256_file(original), "compressed": str(compressed), "compressedSha256": sha256_file(compressed)}, "request": request, "responseHttpStatus": status, "response": response, "usage": usage, "elapsedMs": elapsed_ms, "attempts": attempts, "credentialHandling": "Authorization was used only for transport and is omitted from evidence."}
    (paper_root / f"page-{page}-{arguments.vision_provider}-request-response.json").write_text(json.dumps(call_evidence, ensure_ascii=False, indent=2), encoding="utf-8")
    return recognized_questions(response, pdf.name, page, arguments.vision_provider, question_assets), {"taskSequence": task_sequence, "workerThread": threading.current_thread().name, "sourceFile": pdf.name, "page": page, "pageText": recognized_page_text(response, arguments.vision_provider), "usage": usage, "elapsedMs": elapsed_ms, "attemptCount": len(attempts)}


def main() -> None:
    parser = argparse.ArgumentParser(description="Process configured mathematics PDFs through vision, embeddings and Milvus")
    parser.add_argument("--config", type=Path, default=DEFAULT_CONFIG)
    parser.add_argument("--evidence-root", type=Path,
                        help="Project output subdirectory for immutable page evidence; defaults to config evidenceRoot.")
    parser.add_argument("--collection", default=DEFAULT_COLLECTION)
    parser.add_argument("--timeout-seconds", type=int, default=DEFAULT_TIMEOUT_SECONDS)
    parser.add_argument("--timeout-grace-seconds", type=int, default=DEFAULT_TIMEOUT_GRACE_SECONDS)
    parser.add_argument("--page-workers", type=int,
                        help="optional lower per-run cap; it is local process capacity, not a distributed quota")
    parser.add_argument("--vision-provider", choices=("terra", "luna"), default="terra",
                        help="OpenAI-compatible visual model provider; Terra is the authoritative production default.")
    parser.add_argument("--vision-model", help="Optional provider-model override; defaults match the selected provider.")
    parser.add_argument("--vision-bridge-container",
                        help="Docker container with the configured provider network and credentials.")
    parser.add_argument("--luna-bridge-container",
                        help="Deprecated alias for --vision-bridge-container.")
    parser.add_argument("--finalize-run-id", help="resume only the embedding, Milvus and recall stages from durable page evidence")
    arguments = parser.parse_args()
    config = json.loads(arguments.config.read_text(encoding="utf-8"))
    if config.get("subject") != "MATHEMATICS":
        raise ValueError("only MATHEMATICS ingestion configurations are accepted")
    config_evidence_root = (PROJECT_ROOT / config.get("evidenceRoot", str(DEFAULT_EVIDENCE_ROOT))).resolve()
    evidence_root = (arguments.evidence_root or config_evidence_root).resolve()
    if evidence_root != ALLOWED_EVIDENCE_OUTPUT_ROOT and ALLOWED_EVIDENCE_OUTPUT_ROOT not in evidence_root.parents:
        raise ValueError("--evidence-root must remain under this project's output directory")
    dotenv = load_dotenv(PROJECT_ROOT / ".env")
    provider_models = {"terra": DEFAULT_TERRA_VISION_MODEL, "luna": DEFAULT_LUNA_VISION_MODEL}
    arguments.vision_model = arguments.vision_model or provider_models[arguments.vision_provider]
    if not arguments.finalize_run_id:
        # Only a new visual run needs provider credentials and a Docker bridge.
        # Evidence finalization instead verifies stored response/image hashes, then
        # invokes the real local embedding worker and Milvus for safe recovery.
        api_key = setting("OPENAI_API_KEY", dotenv)
        base_url = setting("OPENAI_BASE_URL", dotenv)
        if not api_key or not base_url:
            raise RuntimeError("OPENAI_API_KEY and OPENAI_BASE_URL must be configured before real visual ingestion")
        configured_bridge = (
            arguments.vision_bridge_container
            or arguments.luna_bridge_container
            or setting("MATH_AGENT_VISION_BRIDGE_CONTAINER", dotenv)
            or setting("MATH_AGENT_LUNA_BRIDGE_CONTAINER", dotenv)
        )
        arguments.vision_bridge_container = resolve_vision_bridge_container(configured_bridge)
    global_ai_concurrency = int(setting(GLOBAL_AI_CONCURRENCY_ENVIRONMENT_VARIABLE, dotenv, str(DEFAULT_GLOBAL_AI_CONCURRENCY)))
    requested_page_workers = arguments.page_workers or global_ai_concurrency
    if global_ai_concurrency < 1 or requested_page_workers < 1:
        raise ValueError("global AI concurrency and --page-workers must be at least one")
    if requested_page_workers > global_ai_concurrency:
        raise ValueError(f"--page-workers={requested_page_workers} exceeds global {GLOBAL_AI_CONCURRENCY_ENVIRONMENT_VARIABLE}={global_ai_concurrency}")
    source_root = Path(config["sourceRootWsl"])
    files = resolve_selected_files(config, source_root)
    configured_asset_root = (PROJECT_ROOT / config["questionAssetRoot"]).resolve()
    # A one-paper simulation may name its exact asset directory, whereas a
    # Gaokao batch names the parent directory containing one subdirectory per
    # PDF.  Detect the immutable report rather than relying on filename shape.
    asset_root_by_file = {
        pdf.name: configured_asset_root if (configured_asset_root / "asset-report.json").is_file()
        else configured_asset_root / pdf.stem
        for pdf in files
    }
    question_assets_by_file = {
        pdf.name: load_question_assets(asset_root_by_file[pdf.name], pdf)
        for pdf in files
    }
    paper_type = str(config["paperType"]).lower()
    run_id = arguments.finalize_run_id or f"{arguments.vision_provider}-{paper_type}-{datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')}-{uuid.uuid4().hex[:8]}"
    run_root = TRANSCRIPTION_RUN_ROOT / run_id
    settings = config["visionOptimization"]
    all_questions: list[dict[str, Any]] = []
    model_calls: list[dict[str, Any]] = []
    if arguments.finalize_run_id:
        manifest_path = run_root / "run-manifest.json"
        if not manifest_path.is_file():
            raise RuntimeError(f"run {run_id} has no source-bound manifest; refuse unsafe evidence recovery")
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        expected_config_hash = hashlib.sha256(arguments.config.read_bytes()).hexdigest()
        expected_sources = {path.name: sha256_file(path) for path in files}
        if manifest.get("configSha256") != expected_config_hash or manifest.get("sources") != expected_sources:
            raise RuntimeError("recovery evidence does not match the configured source PDFs and ingestion configuration")
        evidence_files = sorted(run_root.rglob(f"*-{arguments.vision_provider}-request-response.json"))
        if not evidence_files:
            raise RuntimeError(f"no durable {arguments.vision_provider} response evidence exists for run {run_id}")
        for evidence_file in evidence_files:
            evidence = json.loads(evidence_file.read_text(encoding="utf-8"))
            original = Path(evidence["image"]["original"])
            compressed = Path(evidence["image"]["compressed"])
            if (not original.is_file() or not compressed.is_file()
                    or sha256_file(original) != evidence["image"].get("originalSha256")
                    or sha256_file(compressed) != evidence["image"].get("compressedSha256")):
                raise RuntimeError(f"recovery evidence image hash validation failed: {evidence_file}")
            if evidence.get("provider") != arguments.vision_provider or evidence.get("model") != arguments.vision_model:
                raise RuntimeError("recovery evidence provider/model does not match this finalization request")
            all_questions.extend(recognized_questions(evidence["response"], evidence["sourceFile"], int(evidence["page"]), arguments.vision_provider, question_assets_by_file[evidence["sourceFile"]]))
            model_calls.append({"taskSequence": evidence.get("taskSequence"), "workerThread": evidence.get("workerThread"), "sourceFile": evidence["sourceFile"], "page": evidence["page"], "pageText": recognized_page_text(evidence["response"], arguments.vision_provider), "usage": evidence.get("usage", {}), "elapsedMs": evidence.get("elapsedMs"), "attemptCount": len(evidence.get("attempts", [])), "recoveredFromEvidence": True})
    else:
        run_root.mkdir(parents=True, exist_ok=False)
        # This immutable manifest binds later --finalize-run-id execution to the exact input PDF bytes and policy.
        (run_root / "run-manifest.json").write_text(json.dumps({"runId": run_id, "paperType": config["paperType"], "subject": config["subject"], "configSha256": hashlib.sha256(arguments.config.read_bytes()).hexdigest(), "sources": {path.name: sha256_file(path) for path in files}}, ensure_ascii=False, indent=2), encoding="utf-8")
        # Compile/extract once before worker dispatch. The renderer cache is read-only afterwards, avoiding a race
        # while the workers deliberately own different page paths and provider subprocesses.
        ensure_pdf_renderer()
        jobs = [(sequence, pdf, page, run_root / sha256_file(pdf)) for sequence, (pdf, page) in enumerate(((pdf, page) for pdf in files for page in range(1, page_count(pdf) + 1)), start=1)]
        failures: list[str] = []
        with ThreadPoolExecutor(max_workers=requested_page_workers, thread_name_prefix=f"gaokao-{arguments.vision_provider}-page") as executor:
            futures = {executor.submit(process_page, job, run_id, settings, arguments, source_root, config.get("containerInputRoot", ""), requested_page_workers, question_assets_by_file[job[1].name]): job for job in jobs}
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
    all_questions = merge_cross_page_questions(all_questions)
    # An unnumbered page fragment is valid only while being merged into an explicit
    # preceding continuation. It is never a standalone canonical question/vector row.
    all_questions = [item for item in all_questions if str(item["metadata"].get("questionNumber", "")).isdigit()]
    all_questions = canonical_question_records(all_questions)
    if not all_questions:
        raise RuntimeError(f"{arguments.vision_provider} completed but returned no non-empty questions; refusing to create an empty success report")
    if arguments.vision_provider != "terra":
        raise RuntimeError("canonical publication requires Terra page-level visual transcription")
    canonical_documents: list[dict[str, Any]] = []
    for source_file in files:
        source_hash = sha256_file(source_file)
        paper_calls = [call for call in model_calls if call["sourceFile"] == source_file.name]
        page_texts = {int(call["page"]): str(call["pageText"]) for call in paper_calls}
        expected_pages = set(range(1, page_count(source_file) + 1))
        if set(page_texts) != expected_pages:
            raise RuntimeError(f"Terra transcription is incomplete for canonical publication: {source_file.name}")
        published = publish_canonical_paper(
            evidence_root,
            source_file,
            source_hash,
            page_texts,
            all_questions,
            asset_root_by_file[source_file.name],
        )
        document_ref = str(uuid.uuid5(uuid.NAMESPACE_URL, f"{source_file.name}\n{source_hash}"))
        for question in all_questions:
            metadata = question["metadata"]
            if metadata.get("sourceFile") == source_file.name:
                metadata["documentFullName"] = source_file.name
                metadata["documentRef"] = document_ref
                metadata["sourceSha256"] = source_hash
        canonical_documents.append({
            "id": str(uuid.uuid5(uuid.NAMESPACE_URL, f"document\n{source_file.name}\n{source_hash}")),
            "text": published["content"],
            "metadata": {
                "recordType": "FULL_DOCUMENT",
                "documentFullName": source_file.name,
                "documentRef": document_ref,
                "sourceSha256": source_hash,
                "extraction": "TERRA_VISUAL_PAGE",
            },
        })
    index_records = canonical_documents + all_questions
    worker_key = setting("MATH_AGENT_WORKER_API_KEY", dotenv)
    vectors = embed_all([item["text"] for item in index_records], setting("MATH_AGENT_EMBEDDING_BASE_URL", dotenv, DEFAULT_EMBEDDING_URL), worker_key, arguments.timeout_seconds)
    milvus_uri = setting("MATH_AGENT_VECTOR_INDEX_MILVUS_URI", dotenv, DEFAULT_MILVUS_URI)
    milvus_token = setting("MATH_AGENT_MILVUS_TOKEN", dotenv) or ("root:" + setting("MATH_AGENT_MILVUS_ROOT_PASSWORD", dotenv) if setting("MATH_AGENT_MILVUS_ROOT_PASSWORD", dotenv) else "")
    ensure_collection(milvus_uri, milvus_token, arguments.collection, arguments.timeout_seconds)
    entities = [{PRIMARY_KEY_FIELD: item["id"], VECTOR_FIELD: vector, TEXT_FIELD: item["text"], METADATA_FIELD: vector_metadata(item)} for item, vector in zip(index_records, vectors, strict=True)]
    # Upsert makes evidence recovery idempotent: a repeat never creates a second
    # vector for the deterministic question key after an interrupted finalization.
    milvus_post(milvus_uri, milvus_token, "/v2/vectordb/entities/upsert", {"collectionName": arguments.collection, "data": entities}, arguments.timeout_seconds)
    # Milvus v2's REST FlushReq accepts one collectionName (unlike older SDK APIs
    # which exposed a plural collectionNames list), so keep this payload versioned
    # to the same v2 REST contract used by every other operation in this script.
    milvus_post(milvus_uri, milvus_token, "/v2/vectordb/collections/flush", {"collectionName": arguments.collection}, arguments.timeout_seconds)
    query_vector = embed_all([all_questions[0]["text"]], setting("MATH_AGENT_EMBEDDING_BASE_URL", dotenv, DEFAULT_EMBEDDING_URL), worker_key, arguments.timeout_seconds)[0]
    recalled = milvus_post(milvus_uri, milvus_token, "/v2/vectordb/entities/search", {"collectionName": arguments.collection, "data": [query_vector], "annsField": VECTOR_FIELD, "limit": RETRIEVAL_LIMIT, "outputFields": [PRIMARY_KEY_FIELD, TEXT_FIELD, METADATA_FIELD]}, arguments.timeout_seconds)
    hits = search_hits(recalled)
    if not hits or not any(hit.get("id") == all_questions[0]["id"] or hit.get("entity", {}).get(PRIMARY_KEY_FIELD) == all_questions[0]["id"] for hit in hits):
        recalled_ids = [str(hit.get("id") or hit.get("entity", {}).get(PRIMARY_KEY_FIELD) or "") for hit in hits]
        raise RuntimeError(f"real Milvus recall did not return the inserted query question; queryId={all_questions[0]['id']}; hitIds={recalled_ids}")
    totals = {name: sum(int(call["usage"].get(name, 0) or 0) for call in model_calls) for name in ("prompt_tokens", "completion_tokens", "total_tokens")}
    report = {"timestampUtc": utc_now(), "runId": run_id, "provider": arguments.vision_provider, "model": arguments.vision_model, "selectedFileCount": len(files), "visionCallCount": len(model_calls), "questionCount": len(all_questions), "fullDocumentCount": len(canonical_documents), "usage": totals, "concurrency": {"environmentVariable": GLOBAL_AI_CONCURRENCY_ENVIRONMENT_VARIABLE, "globalLimit": global_ai_concurrency, "effectivePageWorkers": requested_page_workers, "retryScope": "per_request_only", "maxAttemptsPerRequest": LUNA_MAX_ATTEMPTS}, "collection": arguments.collection, "realRecall": {"queryQuestionId": all_questions[0]["id"], "hitCount": len(hits), "hits": hits}, "modelCalls": model_calls, "canonicalEvidenceRoot": str(evidence_root)}
    report_path = TRANSCRIPTION_RUN_ROOT / f"{run_id}-report.json"
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({"report": str(report_path), "runId": run_id, "questionCount": len(all_questions), "usage": totals, "recallHitCount": len(hits)}, ensure_ascii=False))


if __name__ == "__main__":
    main()
