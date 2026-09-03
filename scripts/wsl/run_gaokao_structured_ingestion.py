"""v2 高考结构化转写管线：提示词输出协议取代下游正文正则（2026-09-01 改造）。

与 run_2024_luna_milvus_ingestion.py 的核心差异（为什么重写而不是打补丁）：
旧管线在 LLM 输出之后用大量正则/启发式“反推”结构——从 pageText 定位题干签名、
按行首题号锚点改号、切【答案】/【解析】、按“图”字+bbox 比例猜题图插入点。这些
正文匹配彼此耦合，产生过“19.”与“19题”重复编号、一题双图错位等回归（见
docs/gaokao-ingestion-bottlenecks.md 第一节）。v2 把结构决定权前移到视觉模型的
输出协议：每题直接给出纯数字 number、不含题号的 stem（题图引用处按阅读顺序嵌入
[[FIGUREn]] 占位）、answer、analysis、figureCount；管线只做确定性装配（模板拼接
+ 协议标记替换），不再解析自然语言正文。

红线：本脚本是 test 小库验证版。
- 默认与唯一允许的写入 collection 是 gaokao_math_structured_test；显式拒绝
  gaokao_math，生产 collection 由主代理验收后另行接入。
- 转写证据写到 output/test-gaokao-structured-20260901/ 下，不触碰生产
  output/math-paper-corpus。
其余基建（渲染、bridge 重试、evidence 哈希、--finalize-run-id 恢复、向量发布、
Milvus upsert、真实召回自检）与旧脚本保持一致。
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
import sys
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
DEFAULT_CONFIG = PROJECT_ROOT / "config" / "gaokao-ingestion-structured-test.json"
DEFAULT_EVIDENCE_ROOT = PROJECT_ROOT / "output" / "test-gaokao-structured-20260901" / "math-paper-corpus"
ALLOWED_EVIDENCE_OUTPUT_ROOT = PROJECT_ROOT / "output"
# v2 的转写 run 目录也带 test 标识，避免与生产 math-paper-transcription-runs 混淆。
# 默认沿用单卷 test 目录；配置可用 transcriptionRunRoot 指向独立批次（如 all12），
# main() 中解析并校验必须仍在 output 之下，红线不因可配置而松动。
TRANSCRIPTION_RUN_ROOT = PROJECT_ROOT / "output" / "test-gaokao-structured-20260901" / "math-paper-transcription-runs"
# 结构化管线事件（丢弃/归因冲突）逐条追加到这个文件：主流程 fail-closed 会在坏帧时
# 中止，缓冲式“结尾统一写”恰好丢掉最需要留痕的那轮；SCAN 独立复核依赖这些事件。
PIPELINE_EVENTS_FILENAME = "pipeline-events.jsonl"
# 生产 collection 红线：v2 一律拒绝读写 gaokao_math，防止 test 数据污染生产召回。
PROTECTED_COLLECTION = "gaokao_math"
DEFAULT_TERRA_VISION_MODEL = "gpt-5.6-terra"
DEFAULT_LUNA_VISION_MODEL = "gpt-5.6-luna"
DEFAULT_TIMEOUT_SECONDS = 120
DEFAULT_EMBEDDING_MODEL = "local_bge_embedding"
DEFAULT_EMBEDDING_URL = "http://127.0.0.1:8092/v1/embeddings"
DEFAULT_MILVUS_URI = "http://127.0.0.1:19531"
DEFAULT_COLLECTION = "gaokao_math_structured_test"
VECTOR_DIMENSION = 512
VECTOR_FIELD = "vector"
PRIMARY_KEY_FIELD = "id"
TEXT_FIELD = "text"
METADATA_FIELD = "metadata"
PAGE_RENDER_DPI = 180
RETRIEVAL_LIMIT = 10
EMBEDDING_BATCH_SIZE = 10
MILVUS_UPSERT_BATCH_SIZE = 100
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
# 质量门禁：斜杠分数是 LaTeX 书写质量问题，不是正文结构匹配，v2 保留。
FRACTION_SLASH_PATTERN = re.compile(r"(?<!\\\\)\b(?:[A-Za-z0-9)}]+)\s*/\s*(?:[A-Za-z0-9({]+)\b")

# FIGURE 占位协议 token。管线只对自己签发的这个 token 做字符串级操作
# （计数、删除、重编号、替换），绝不把正则用于自然语言正文——这是本次改造的
# 边界：确定性装配 = 只处理协议标记，不猜语义。
FIGURE_MARKER_PREFIX = "[[FIGURE"
FIGURE_MARKER_SUFFIX = "]]"


class NonRetryableLunaError(RuntimeError):
    """Marks a provider response that must be surfaced immediately, never retried."""


def utc_now() -> str:
    """Produces a timezone-explicit timestamp for evidence created on either Windows or WSL."""
    return datetime.now(timezone.utc).isoformat()


_pipeline_event_log_path: Path | None = None


def set_pipeline_event_log(path: Path) -> None:
    """main() 在确定 run 目录后激活结构化事件留痕；单测与离线调用保持纯 stderr 行为。"""
    global _pipeline_event_log_path
    _pipeline_event_log_path = path


def log_pipeline_event(kind: str, message: str, **fields: Any) -> None:
    """统一输出并落盘管线事件（stderr 人类可读，JSONL 供 SCAN 机器复核）。

    追加写、失败不中断主流程：事件面是旁路审计，绝不因写日志反过来制造转写失败。
    """
    print(f"[{kind}] {message}", file=sys.stderr)
    if _pipeline_event_log_path is None:
        return
    event = {"tsUtc": utc_now(), "kind": kind, "message": message, **fields}
    try:
        with _pipeline_event_log_path.open("a", encoding="utf-8") as stream:
            stream.write(json.dumps(event, ensure_ascii=False) + "\n")
    except OSError as error:
        print(f"[v2-event-log-degraded] {error}", file=sys.stderr)


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
    """Build the v2 protocol request: the prompt itself is now the only structure contract.

    旧契约让模型输出 text（可能自带题号）+ figureAnchor（一句原文引用），再由下游正则
    反推题号、解析区与插图位置。v2 把所有结构信息收进协议字段：number 纯数字、stem 不含
    题号、题图引用处以 [[FIGUREn]] 占位、answer/analysis 为印刷原文。管线因此不需要再
    “读懂”正文。
    """
    mime = mimetypes.guess_type(image.name)[0] or "image/jpeg"
    data_url = f"data:{mime};base64,{base64.b64encode(image.read_bytes()).decode('ascii')}"
    prompt = {
        "task": "Transcribe every visible high-school mathematics question on this one rendered exam page.",
        "paper": paper,
        "page": page,
            "requiredOutput": {
                "pageText": "string",
                "questions": [{
                    "number": "string", "parentNumber": "string", "stem": "string", "answer": "string", "analysis": "string",
                    "figureCount": "integer", "latex": ["string"], "continuesToNextPage": "boolean", "confidence": "number",
                }],
            "layout": "single-column|multi-column|uncertain",
            "boundaryRisks": ["string"]
        },
        "constraints": [
            "Read only the supplied page image.",
            "pageText is the authoritative complete transcription of this page. It is published verbatim and is never used to re-derive question structure. Preserve mathematical formulas as LaTex strings in latex. Every visible mathematical fraction MUST use \\frac{numerator}{denominator}; never write a fraction as a/b, 1/2, or x/y in a latex field.",
            # 记号形态约束沿用旧契约：这是书写规范问题，与结构无关。
            "Math notation form: write exponents and subscripts ONLY in TeX ASCII form x^2, a_1, 10^{-3} (braces for multi-character groups). Never use Unicode superscript/subscript glyphs such as \u00b2 \u00b3 \u207b \u2081 \u2082. Use the ASCII hyphen-minus for minus signs; never use en-dash or em-dash as a minus.",
            # —— v2 结构协议核心，逐条对应管线装配规则 ——
            "Emit question records in the reading order they appear on the page; the pipeline relies on this order and never re-derives it from text.",
            "number contains ONLY the printed digits, no punctuation or prefix, e.g. \"19\" (never \"19.\" or \"第19题\"). Use an empty string when this page shows no printed number for the fragment.",
            "parentNumber: every record whose number is empty is a fragment and MUST set parentNumber to the printed digits of the question it continues (e.g. \"17\", never empty for fragments; do not read a number off formulas or intervals). Numbered records leave parentNumber empty. The pipeline validates this attribution and never re-derives it from prose.",
            "stem is the question stem text only: it must not start with or contain the printed question number; the pipeline adds the number heading itself.",
            "Wherever the page shows a figure belonging to a question and referenced by that question's printed content, insert [[FIGUREn]] at that exact reading position inside whichever field (stem, answer or analysis) carries the reference; number markers from 1 within each record (a question with two figures on one page uses [[FIGURE1]] and [[FIGURE2]] in page reading order). No marker when no figure is shown on this page.",
            "If any printed part of a question (stem, answer or analysis) continues onto the next page, the next page reports the fragment as a separate record with number \"\" and parentNumber set to the question's printed number; the fragment's text may appear in stem, answer and/or analysis. The fragment's [[FIGUREn]] markers restart at [[FIGURE1]]; the pipeline renumbers them by the accumulated marker count of the earlier pages.",
            "figureCount is an integer equal to the total number of [[FIGUREn]] markers across stem, answer and analysis of this record; use 0 when there is no marker.",
            "answer is the printed answer text of this question on this page, empty string if the page shows none. analysis is the printed solution/derivation text, empty string if none. An empty answer/analysis is a normal branch (blank paper or a page without the solution section), never an error.",
            "answer and analysis must be copied verbatim from the printed solution sections, WITHOUT question numbers, WITHOUT section labels such as 答案/解析/【详解】, and WITHOUT repeating the stem.",
            "Do not invent an answer, solution, unshown text, or an official correctness judgement.",
            # 2026-09-01 实测：模型对“整页只有解析续文、看不到题号”的第 22 页以
            # “no question is visible”为由返回空 questions，导致 q22 解析缺一段。
            # 空列表只适用于真正的空页（如卷末空白），片段页必须输出片段记录。
            "Use an empty questions list only when the page truly shows no question content at all (e.g. a blank end-of-paper page). A page that shows only a continuation fragment of some question MUST still report that fragment as a record with number \"\" and parentNumber set; leaving parentNumber empty is allowed only if the parent number is genuinely unclear, and that risk must be stated in boundaryRisks.",
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
    """v2 校验：number 必须是 strip 后的纯数字。

    不再做“第 19 题 / 19．”这类归一化（旧 CANONICAL_QUESTION_NUMBER_PATTERN）：
    归一化本身就是对模型输出的正则猜测。协议已规定 number 只能是打印数字，
    违规输出（如 "19."）视为不可信结构信号——按续页片段处理或丢弃，与旧管线
    “非规范题号不建立独立发布身份”的行为一致。
    """
    normalized = str(value or "").strip()
    return normalized if normalized.isdigit() else ""


def find_figure_markers(text: str) -> list[tuple[int, int, int]]:
    """返回正文中协议 token 的 (start, end, n)。

    只识别精确的 [[FIGURE<纯数字>]] 形态；这是管线自己签发/约定的 token，
    字符串扫描即是权威解析，不存在对自然语言的语义猜测。
    """
    positions: list[tuple[int, int, int]] = []
    cursor = 0
    while True:
        start = text.find(FIGURE_MARKER_PREFIX, cursor)
        if start < 0:
            return positions
        end = text.find(FIGURE_MARKER_SUFFIX, start + len(FIGURE_MARKER_PREFIX))
        if end < 0:
            return positions
        digits = text[start + len(FIGURE_MARKER_PREFIX):end]
        if digits.isdigit() and digits == str(int(digits)) and int(digits) >= 1:
            positions.append((start, end + len(FIGURE_MARKER_SUFFIX), int(digits)))
            cursor = end + len(FIGURE_MARKER_SUFFIX)
        else:
            # 非协议形态（如 [[FIGUREx]]）不吞掉正文：跳过该前缀继续扫描。
            cursor = start + len(FIGURE_MARKER_PREFIX)


def count_figure_markers(text: str) -> int:
    """协议标记计数（装配校验用）。"""
    return len(find_figure_markers(text))


def strip_figure_markers(text: str) -> str:
    """从向量文本中删除 FIGURE 标记（任务规则：发布 md 替换为图片，向量删除标记）。"""
    positions = find_figure_markers(text)
    if not positions:
        return text
    parts: list[str] = []
    cursor = 0
    for start, end, _n in positions:
        parts.append(text[cursor:start])
        cursor = end
    parts.append(text[cursor:])
    return "".join(parts)


def dedupe_merged_template_prefixes(previous_text: str, fragment_text: str) -> str:
    """合并跨页片段时剥掉重复的 【答案】/【解析】 模板前缀（2026-09-01 q10/q19 实测）。

    装配模板只把前缀放在“节点头”位置（串首或 \\n\\n 之后），因此去重同样只认节点头
    边界的 token——这是对自家模板标记的确定性删除，片段正文一律保留；印刷小节标题
    （【分析】【详解】等）不在模板 token 之列，不受影响。
    """
    result = fragment_text
    for prefix in ("【答案】", "【解析】"):
        if prefix not in previous_text:
            continue
        parts: list[str] = []
        cursor = 0
        while True:
            position = result.find(prefix, cursor)
            if position < 0:
                parts.append(result[cursor:])
                break
            at_section_head = position == 0 or result[position - 2:position] == "\n\n"
            if at_section_head:
                parts.append(result[cursor:position])
            else:
                parts.append(result[cursor:position + len(prefix)])
            cursor = position + len(prefix)
        result = "".join(parts)
    return result


def renumber_figure_markers(text: str, offset: int) -> str:
    """把 text 内第 k 个标记改写为第 k+offset 个（跨页续片的全局重编号）。

    确定性 token 替换：编号来自协议字段累计，不来自正文匹配。倒序重写偏移
    由逐段重建保证，无需担心新旧 token 互相覆盖。
    """
    if offset <= 0:
        return text
    positions = find_figure_markers(text)
    if not positions:
        return text
    parts: list[str] = []
    cursor = 0
    for _start, end, n in positions:
        start = _start
        parts.append(text[cursor:start])
        parts.append(f"{FIGURE_MARKER_PREFIX}{n + offset}{FIGURE_MARKER_SUFFIX}")
        cursor = end
    parts.append(text[cursor:])
    return "".join(parts)


def recognized_questions(response: dict[str, Any], source_name: str, page: int, provider: str,
                         question_assets: dict[str, list[dict[str, Any]]]) -> list[dict[str, Any]]:
    """按 v2 协议解析结构化输出并做确定性装配；不读取 pageText 反推任何结构。"""
    try:
        content = response["choices"][0]["message"]["content"]
        parsed = json.loads(content) if isinstance(content, str) else content
    except (KeyError, IndexError, TypeError, json.JSONDecodeError) as error:
        raise RuntimeError(f"{provider} did not return a parseable JSON transcription: {error}") from error
    questions = parsed.get("questions")
    if not isinstance(questions, list):
        raise RuntimeError(f"{provider} transcription has no questions array")
    output: list[dict[str, Any]] = []
    # pageSequence 记录模型数组内的阅读顺序（提示词已规定 questions 按版面先后输出）。
    # 合并与审计都依赖这个序号，而不是题号大小或正文匹配——2026-09-01 实测：按题号
    # 优先排序会让解析续段错挂到同页后出现的下一题上。
    for sequence, item in enumerate(questions):
        if not isinstance(item, dict):
            continue
        latex = item.get("latex", [])
        if not isinstance(latex, list):
            latex = []
        latex = [str(value).strip() for value in latex if str(value).strip()]
        for formula in latex:
            if FRACTION_SLASH_PATTERN.search(formula):
                raise RuntimeError(f"{provider} latex fraction must use \\frac{{numerator}}{{denominator}}, not slash notation")
        stem = str(item.get("stem", "") or "").strip()
        answer = str(item.get("answer", "") or "").strip()
        analysis = str(item.get("analysis", "") or "").strip()
        # 三个方向全空的记录没有可发布内容（模型对纯页脚页可能输出空壳），静默跳过。
        # 注意不能要求 stem 非空：解析卷续页片段的正文常只落在 analysis 里，旧管线
        # 靠 pageText 正则反推补解析，v2 删除该路径后，若丢弃这类片段会直接丢失解析续文。
        if not (stem or answer or analysis):
            continue
        raw_number = str(item.get("number", "") or "").strip()
        question_number = canonical_question_number(raw_number)
        # parentNumber：片段对归属题的显式声明（协议字段，模型输出，非管线推断）。
        # 合并阶段以“版面线程优先、parentNumber 兜底”消费它——实测模型给出的
        # parent 声明存在错号（见 merge_cross_page_questions 归因注释）。
        parent_number = canonical_question_number(item.get("parentNumber", ""))
        continues = bool(item.get("continuesToNextPage", False))
        # 丢弃规则按协议语义收窄：“number 为空串”本身就是协议规定的续页片段证据
        # （本页无打印题号），必须保留——解析卷的最后一段续文没有“再往后翻页”，若
        # 要求 continuesToNextPage 会把 q19 这类跨页解析的尾段丢掉。只有“非空但违规
        # 的题号”（"19."/"第19题"）且未标续页时才丢弃并记日志，与旧管线的处理一致。
        # 未与前一页合并的空号片段会在 main 的 isdigit 过滤处再兜底丢弃并告警。
        if raw_number and not question_number and not continues:
            log_pipeline_event("v2-drop", f"{source_name} page {page}: non-numeric question number {raw_number!r}",
                               reason="non-numeric-number", sourceFile=source_name, page=page, number=raw_number)
            continue
        # figureCount 是协议自检字段：以三个方向正文中的标记实数为准（装配只认确定性
        # token），不一致时记录告警供人工回看，不做任何“猜图”兜底。
        # 2026-09-01 实测：解析卷题图引用常出现在 analysis（“连接AE，如图”），标记
        # 允许落在任一字段，因此计数覆盖整个 body。
        marker_count = count_figure_markers(stem) + count_figure_markers(answer) + count_figure_markers(analysis)
        declared_count = item.get("figureCount")
        if isinstance(declared_count, bool) or not str(declared_count or "").strip().isdigit():
            declared_count = None
        else:
            declared_count = int(declared_count)
        # 装配（发布与向量共用同一 text）严格按任务规则 2 的模板：
        # stem + 【答案】 + 【解析】，latex 不再拼进正文——模型转写的正文本身已内联
        # LaTeX，旧管线“text 尾部追加 latex 行”的形态在多段跨页解析合并后会把同一批
        # 公式重复堆叠成裸行（2026-09-01 q19 实测）。latex 字段保留在元数据里，并继续
        # 作为斜杠分数质量门禁的校验对象。模板拼接完全由协议字段驱动，零正文正则；
        # 片段可能只有 answer/analysis（续页解析片段），join 不产生悬空分隔符。
        sections = [part for part in (
            stem,
            f"【答案】{answer}" if answer else "",
            f"【解析】{analysis}" if analysis else "",
        ) if part]
        body = "\n\n".join(sections)
        vector_text = strip_figure_markers(body)
        # 稳定身份只来自不可变视觉证据；恢复运行可对同题 upsert 而非新建重复行。
        stable_identity = f"{source_name}\n{page}\n{question_number}\n{vector_text}"
        transcription_fields = {"stem": stem, "answer": answer, "analysis": analysis}
        metadata: dict[str, Any] = {
            "sourceFile": source_name, "page": page, "pageStart": page, "pageEnd": page,
            "questionNumber": question_number, "latex": latex, "confidence": item.get("confidence"),
            "continuesToNextPage": continues, "extraction": f"{provider.upper()}_VISUAL_PAGE",
            # 题图资产仍按打印题号整卷绑定（OCR 裁剪清单键为题号）；FIGURE 标记与
            # 资产在发布阶段按 (pageNumber, bbox.top) 有序对应，替代旧“图字+bbox 比例”猜测。
            "questionAssets": question_assets.get(question_number, []),
            "textSegments": [{"page": page, "text": vector_text}],
            # 规则 6：solutionAttached 的含义改为“本页协议字段是否给出答案/解析”，
            # 不再由页文本反推解析区。
            "solutionAttached": bool(answer or analysis),
            "figureCount": marker_count,
            "parentQuestionNumber": parent_number,
            "pageSequence": sequence,
        }
        if declared_count is not None and declared_count != marker_count:
            metadata["figureCountMismatch"] = {"declared": declared_count, "markers": marker_count}
        if any(transcription_fields.values()):
            # 下划线前缀 = 发布临时字段：vector_metadata 排除 + 守卫双保险，绝不进 Milvus。
            metadata["_transcriptionFields"] = transcription_fields
        output.append({
            "id": str(uuid.uuid5(uuid.NAMESPACE_URL, stable_identity)), "text": body,
            "metadata": metadata,
        })
    return output


def recognized_page_text(response: dict[str, Any], provider: str) -> str:
    """读取模型声明的页级完整转写；v2 中它只用于 document.md 全文页，不参与结构化。"""
    try:
        content = response["choices"][0]["message"]["content"]
        parsed = json.loads(content) if isinstance(content, str) else content
    except (KeyError, IndexError, TypeError, json.JSONDecodeError) as error:
        raise RuntimeError(f"{provider} did not return a parseable JSON transcription: {error}") from error
    page_text = str(parsed.get("pageText", "")).strip() if isinstance(parsed, dict) else ""
    if not page_text:
        raise RuntimeError(f"{provider} transcription has no authoritative pageText")
    return page_text


def canonical_question_id(source_sha256: str, question_number: Any) -> str:
    """Build the final question identity from immutable source bytes and the printed number only."""
    if not re.fullmatch(r"[0-9a-f]{64}", str(source_sha256 or "")):
        raise ValueError("canonical question identity requires a lowercase SHA-256 source identity")
    normalized_number = canonical_question_number(question_number)
    if not re.fullmatch(r"[1-9]\d{0,2}", normalized_number):
        raise ValueError("canonical question identity requires a numeric question number")
    return str(uuid.uuid5(uuid.NAMESPACE_URL, f"question\n{source_sha256}\n{int(normalized_number)}"))


def canonical_question_records(
        questions: list[dict[str, Any]], with_stats: bool = False) -> list[dict[str, Any]] | tuple[list[dict[str, Any]], int]:
    """同号冲突保留最早来源页的首条记录。

    v2 明确删除了旧 repair_question_number_collisions 的“唯一可推断改号”：那是用
    缺号集合反推打印题号，属于对编号的正则式猜测。协议规定 number 只能是打印
    数字，改号即伪造来源证据；真误标由 run 报告的 duplicateSkippedCount 暴露，人工回看。
    """
    selected: list[dict[str, Any]] = []
    seen: set[tuple[str, str]] = set()
    duplicate_count = 0
    for question in sorted(questions, key=lambda item: (
            str(item["metadata"].get("sourceSha256", "")),
            int(item["metadata"].get("pageStart", item["metadata"].get("page", 0))),
            str(item["metadata"].get("questionNumber", "")),
            str(item.get("id", "")))):
        metadata = question["metadata"]
        source_identity = str(metadata.get("sourceSha256", "")).strip()
        number = canonical_question_number(metadata.get("questionNumber"))
        if not source_identity or not number:
            continue
        key = (source_identity, number)
        if key in seen:
            duplicate_count += 1
            continue
        metadata["questionNumber"] = number
        seen.add(key)
        selected.append(question)
    return (selected, duplicate_count) if with_stats else selected


def merge_cross_page_questions(questions: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """Joins only an explicitly flagged page tail to an unnumbered next-page fragment from the same PDF.

    A new printed number is authoritative evidence of a distinct question, so it is never merged even when Luna
    incorrectly marked the previous page as continuing. The merged identity is recalculated from its full evidence.
    v2 差异：
    - 页内排序改用模型数组的 pageSequence（阅读顺序契约），不再用“编号优先”排序——
      旧排序会把页首解析尾段错挂到同页后出现的下一题；
    - 片段归属优先级为“版面线程 > parentNumber 声明”，冲突留痕（见归因块注释）；
    - 续页片段的 [[FIGUREn]] 标记按前一页累计标记数重编号后拼接（任务规则 3），
      保证整题的 FIGURE 序号按阅读顺序全局连续；重编号是对协议 token 的确定性
      字符串替换，不读取正文语义。
    """
    def order_key(item: dict[str, Any]) -> tuple[Any, ...]:
        metadata = item["metadata"]
        sequence = metadata.get("pageSequence")
        if isinstance(sequence, int):
            # 页内顺序 = 模型数组顺序（提示词规定按版面阅读顺序输出）。
            within_page: tuple[int, int] = (0, sequence)
        else:
            # 兼容手工构造的测试记录（无序号字段）：退回旧的“编号优先”页内排序。
            number = str(metadata.get("questionNumber", ""))
            within_page = ((0, int(number)) if number.isdigit() else (1, 0))
        return (
            str(metadata.get("sourceFile", "")),
            int(metadata.get("pageStart", metadata.get("page", 0))),
            within_page,
            str(item["id"]),
        )

    def can_merge_into(previous: dict[str, Any], current: dict[str, Any]) -> bool:
        previous_metadata = previous["metadata"]
        metadata = current["metadata"]
        return (
            previous_metadata.get("sourceFile") == metadata.get("sourceFile")
            and bool(previous_metadata.get("continuesToNextPage"))
            and int(metadata.get("pageStart", metadata.get("page", 0))) == int(previous_metadata.get("pageEnd", previous_metadata.get("page", 0))) + 1
            and (
                not str(metadata.get("questionNumber", "")).strip()
                or str(metadata.get("questionNumber", "")).strip() == str(previous_metadata.get("questionNumber", "")).strip()
            )
        )

    def merge_into(previous: dict[str, Any], current: dict[str, Any]) -> None:
        """把 current 记录并入 previous（同一题的跨页片段），确定性重编号 FIGURE 标记。"""
        metadata = current["metadata"]
        previous_metadata = previous["metadata"]
        combined_latex = list(previous_metadata.get("latex", [])) + list(metadata.get("latex", []))
        previous_figure_offset = count_figure_markers(previous["text"])
        # 模板前缀去重：每个页内片段按协议各自装配，跨页合并后同一题会出现多个
        # 【解析】/【答案】前缀堆叠（2022Ⅰ q10/q19 实测，正是验收点名的“前缀堆叠”）。
        fragment_text = dedupe_merged_template_prefixes(previous["text"], current["text"])
        renumbered_current_text = renumber_figure_markers(fragment_text, previous_figure_offset)
        combined_text = "\n".join(part for part in [previous["text"], renumbered_current_text] if part)
        previous["text"] = combined_text
        previous_metadata["latex"] = combined_latex
        previous_metadata.setdefault("pageStart", previous_metadata.get("page"))
        previous_metadata["pageEnd"] = metadata.get("pageEnd", metadata.get("page"))
        previous_metadata["continuesToNextPage"] = bool(metadata.get("continuesToNextPage"))
        previous_metadata["figureCount"] = count_figure_markers(combined_text)
        # questionAssets 按题号整卷挂载，同号续页合并会把同一份列表拼两遍；
        # 按 assetId 去重，保证逐题材料与 manifest 不出现重复题图。
        merged_assets = list(previous_metadata.get("questionAssets", []))
        seen_asset_ids = {str(asset.get("assetId")) for asset in merged_assets}
        for asset in metadata.get("questionAssets", []):
            if str(asset.get("assetId")) not in seen_asset_ids:
                merged_assets.append(asset)
                seen_asset_ids.add(str(asset.get("assetId")))
        previous_metadata["questionAssets"] = merged_assets
        previous_metadata["textSegments"] = list(previous_metadata.get("textSegments", [])) + list(metadata.get("textSegments", []))
        identity = f"{previous_metadata['sourceFile']}\n{previous_metadata['pageStart']}\n{previous_metadata['pageEnd']}\n{previous_metadata.get('questionNumber', '')}\n{strip_figure_markers(combined_text)}"
        previous["id"] = str(uuid.uuid5(uuid.NAMESPACE_URL, identity))

    ordered = sorted(questions, key=order_key)
    merged: list[dict[str, Any]] = []
    # thread：每个来源卷“最近一条编号记录”的引用。高考卷版面线性排印，无编号片段
    # 按阅读顺序必然延续当前线程；这是对 parentNumber 缺失/失配的确定性兜底，
    # 仍只用协议字段（编号、页号）比较，不匹配正文。
    thread: dict[str, dict[str, Any]] = {}

    def thread_target(metadata: dict[str, Any]) -> dict[str, Any] | None:
        candidate = thread.get(str(metadata.get("sourceFile", "")))
        if candidate is None:
            return None
        page_start = int(metadata.get("pageStart", metadata.get("page", 0)))
        candidate_end = int(candidate["metadata"].get("pageEnd", candidate["metadata"].get("page", 0)))
        # 同页片段（页内多段解析文本）或下一页片段都算邻接；更远即疑似归属错误，宁丢勿错挂。
        return candidate if page_start - candidate_end in {0, 1} else None

    for current in ordered:
        metadata = current["metadata"]
        # 首选旧规则：紧邻的上一条记录显式标了 continuesToNextPage。
        if merged and can_merge_into(merged[-1], current):
            merge_into(merged[-1], current)
            continue
        if not str(metadata.get("questionNumber", "")).strip():
            # 片段归属优先级（2026-09-01 真实卷面证据驱动的设计）：
            # 1) 版面线程：高考卷面线性排印，“片段延续最近一条编号记录”是版面物理事实，
            #    由阅读顺序契约承载；实测 7/7 次线程归属全部正确。
            # 2) parentNumber 仅在无线程可用时兜底（线程断裂/页距异常）：实测模型会给出
            #    错误声明——第 10 页把 q14 的两圆相切解析标成 parent “17”，第 19 页把
            #    q21 双曲线解析标成 parent “19”，第 5 页把区间 “[18,27]” 读成 parent “18”，
            #    其 boundaryRisks 也自述编号是“按题号顺序判定”的推断。声明与线程冲突时
            #    取线程并打日志，让模型的错误声明显式暴露供人工复核。
            parent_number = str(metadata.get("parentQuestionNumber", "") or "")
            thread_candidate = thread_target(metadata)
            parent_candidate = next((
                record for record in reversed(merged)
                if record["metadata"].get("sourceFile") == metadata.get("sourceFile")
                and str(record["metadata"].get("questionNumber", "")) == parent_number
                and int(record["metadata"].get("pageEnd", record["metadata"].get("page", 0))) + 1
                == int(metadata.get("pageStart", metadata.get("page", 0)))
            ), None) if parent_number else None
            target = thread_candidate
            if thread_candidate is not None and parent_number and parent_candidate is not thread_candidate:
                # 声明错误或失配的片段仍走线程，但必须留痕：这是模型协议违规的信号。
                log_pipeline_event(
                    "v2-attr",
                    f"fragment {metadata.get('sourceFile')} page {metadata.get('pageStart')}: declared parent "
                    f"{parent_number} {'conflicts with' if parent_candidate is not None else 'did not match'} reading "
                    f"thread #{thread_candidate['metadata'].get('questionNumber')}; keeping thread",
                    sourceFile=metadata.get("sourceFile"), page=metadata.get("pageStart"),
                    declaredParent=parent_number, keptThread=thread_candidate["metadata"].get("questionNumber"))
            if thread_candidate is None and parent_candidate is not None:
                target = parent_candidate
                log_pipeline_event(
                    "v2-attr",
                    f"fragment {metadata.get('sourceFile')} page {metadata.get('pageStart')}: "
                    f"no adjacent thread, attached by declared parent #{parent_number}",
                    sourceFile=metadata.get("sourceFile"), page=metadata.get("pageStart"),
                    declaredParent=parent_number, attachedBy="parent")
            if target is not None:
                merge_into(target, current)
                continue
        merged.append({"id": current["id"], "text": current["text"], "metadata": dict(metadata)})
        if str(metadata.get("questionNumber", "")).strip().isdigit():
            # 编号记录刷新版面线程。merged 尾部是 dict(metadata) 拷贝，后续合并会改变它，
            # 线程指针必须指向发布列表中的记录本体而不是输入引用。
            thread[str(metadata.get("sourceFile", ""))] = merged[-1]
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
            "pageHeightPixels": item.get("pageHeightPixels"),
            "bboxPixels": item["bboxPixels"],
            "bindingMethod": item["bindingMethod"],
            "_sourceAssetPath": asset_path,
        })
    return assets


def order_assets_for_reading(assets: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """题图资产按 (来源页, bbox.top) 的页面阅读顺序排序，与 FIGURE 标记序号一一对应。

    这是 v2 的绑定依据：模型按阅读顺序编号标记，裁剪清单按同一坐标序排列，两侧对齐
    是纯排序比较，不再用“图”字段落搜索 + 几何比例就近猜插入点。
    """
    return [asset for _index, asset in sorted(
        enumerate(assets),
        key=lambda pair: (int(pair[1].get("pageNumber", 0) or 0),
                          int((pair[1].get("bboxPixels") or {}).get("top", 0) or 0), pair[0]),
    )]


def embed_figure_markers(text: str, figure_markdowns: list[str]) -> str:
    """把 stem 中第 k 个 [[FIGUREk]] 替换为第 k 张题图的 Markdown（取代 place_question_figures）。

    校验规则（任务规则 4）：
    - 标记数 == 资产数：逐位替换；
    - 标记多于资产：删除多余标记（无对应资产即不显示图片，符合讲义架构）；
    - 资产多于标记：多余资产按序追加文末（绝不丢图）；
    - 零资产：不显示图片。
    整个过程只操作协议 token 与传入的 Markdown 字符串，不匹配自然语言正文。
    """
    positions = find_figure_markers(text)
    parts: list[str] = []
    cursor = 0
    for marker_index, (start, end, _n) in enumerate(positions):
        parts.append(text[cursor:start])
        if marker_index < len(figure_markdowns):
            parts.append(figure_markdowns[marker_index])
        # 多余标记在此被“替换为空串”，即删除。
        cursor = end
    parts.append(text[cursor:])
    result = "".join(parts)
    if len(figure_markdowns) > len(positions):
        extra = figure_markdowns[len(positions):]
        result = result + "".join(f"\n\n{markdown}" for markdown in extra)
    return result


def canonical_paper_directory_name(source_file: Path) -> str:
    """以原始完整文件名（含扩展名，与生产目录一致）命名发布目录，并拒绝跨平台不可读的路径片段。"""
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
    """发布单份试卷的可读全文、逐题材料及来源图片，作为唯一 RAG 证据目录。

    与旧版差异：逐题正文不再需要 place_question_figures 猜插入点——question["text"]
    已含 FIGURE 标记，发布时逐位替换为图片 Markdown（embed_figure_markers）。
    """
    paper_root = corpus_root / canonical_paper_directory_name(source_file)
    if paper_root.exists():
        # Recovery must refresh generated question Markdown and hashes after publication retries.
        manifest_path = paper_root / "source-manifest.json"
        document_path = paper_root / "document.md"
        if not manifest_path.is_file() or not document_path.is_file():
            raise FileExistsError(f"incomplete canonical paper output cannot be reused: {paper_root}")
        existing_manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        if (existing_manifest.get("documentFullName") != source_file.name
                or existing_manifest.get("sourceSha256") != source_sha256):
            raise FileExistsError(f"canonical paper output belongs to another source: {paper_root}")
        for generated_path in (paper_root / "questions").glob("q-*.md"):
            generated_path.unlink()
        for generated_path in (paper_root / "figures").glob("q-*.*"):
            generated_path.unlink()
    else:
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
        # document.md 全文页仍用 pageText 原样发布；v2 中它不承担任何结构化职责。
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
        ordered_assets = order_assets_for_reading(list(metadata.get("questionAssets", [])))
        copied_assets: list[dict[str, Any]] = []
        manifest_assets: list[dict[str, Any]] = []
        figure_markdowns: list[str] = []
        for asset_order, asset in enumerate(ordered_assets, start=1):
            source_asset = asset.get("_sourceAssetPath")
            if not isinstance(source_asset, Path):
                raise RuntimeError("question asset has no private publication path")
            relative_asset = Path("figures") / f"q-{file_stem}-{asset_order:02d}{source_asset.suffix.lower()}"
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
            figure_markdowns.append(f"![第 {number} 题图]({relative_asset.as_posix()})")
        # 标记 -> 图片按协议序号逐位替换；多余资产保底追加文末，多余标记删除，绝不丢图。
        question_lines[6] = embed_figure_markers(str(question["text"]), figure_markdowns)
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
        raise ValueError("vector metadata must be an object")
    # Formula strings are already part of the embedded text and canonical Markdown.
    # Excluding this redundant field prevents the path guard treating TeX backslashes as paths.
    # textSegments 是发布阶段的排版辅助（页->正文块），正文已含全部文字，不重复进入向量库。
    # 下划线前缀键（_transcriptionFields 的 stem/answer/analysis）是发布临时量，学生版隔离
    # 红线要求答案绝不进入检索面，这里先于守卫整体剔除。
    metadata = {key: value for key, value in metadata.items()
                if key not in {"latex", "textSegments"} and not key.startswith("_")}
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


def vector_text_of(record: dict[str, Any]) -> str:
    """写入向量库/参与嵌入的文本 = 装配正文删除 FIGURE 标记（任务规则 2 的选择）。"""
    return strip_figure_markers(str(record.get("text", "")))


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


def milvus_upsert_batches(
        uri: str, token: str, collection: str, entities: list[dict[str, Any]], batch_size: int, timeout: int) -> int:
    """Upsert bounded slices so retries and request memory stay scoped to one batch."""
    if batch_size < 1:
        raise ValueError("Milvus upsert batch size must be positive")
    batch_count = 0
    for start in range(0, len(entities), batch_size):
        batch = entities[start:start + batch_size]
        if not batch:
            continue
        milvus_post(uri, token, "/v2/vectordb/entities/upsert", {
            "collectionName": collection,
            "data": batch,
        }, timeout)
        batch_count += 1
    return batch_count


def milvus_filter_expression(field: str, value: str) -> str:
    """Build a JSON-quoted Milvus metadata equality filter without interpolating raw input."""
    if field not in {"sourceFile", "documentFullName"} or not value:
        raise ValueError("source cleanup accepts only non-empty source provenance fields")
    return f'metadata["{field}"] == {json.dumps(value, ensure_ascii=False)}'


def milvus_source_filter(source_name: str) -> str:
    """Match both legacy and canonical provenance fields in one server-side expression."""
    if not source_name:
        raise ValueError("source cleanup requires a non-empty source name")
    return "(" + " or ".join(
        milvus_filter_expression(field, source_name) for field in ("sourceFile", "documentFullName")
    ) + ")"


def milvus_query_count(uri: str, token: str, collection: str, expression: str, timeout: int) -> int:
    """Ask Milvus for a server-side filtered count without returning entity rows."""
    payload = milvus_post(uri, token, "/v2/vectordb/entities/query", {
        "collectionName": collection,
        "filter": expression,
        "outputFields": ["count(*)"],
    }, timeout)
    rows = payload.get("data", [])
    if not isinstance(rows, list) or not rows:
        return 0
    return int(rows[0].get("count(*)", 0) or 0)


def cleanup_source_records(
        uri: str, token: str, collection: str, source_names: list[str], timeout: int) -> dict[str, int]:
    """Replace only explicitly selected source provenance; never enumerate the collection client-side.

    v2 把删除面收窄到本 test collection：误指向生产 collection 会被直接拒绝，
    保护 gaokao_math 不被 test 管线删行。
    """
    if collection != DEFAULT_COLLECTION:
        raise ValueError("v2 source cleanup is restricted to the structured test collection")
    matched = 0
    deleted = 0
    for source_name in sorted(set(source_names)):
        expression = milvus_source_filter(source_name)
        count = milvus_query_count(uri, token, collection, expression, timeout)
        matched += count
        if count:
            milvus_post(uri, token, "/v2/vectordb/entities/delete", {
                "collectionName": collection,
                "filter": expression,
            }, timeout)
            deleted += count
    return {"matchedCount": matched, "deletedCount": deleted}


def search_hits(response: dict[str, Any]) -> list[dict[str, Any]]:
    """Normalize Milvus v2's one-query nested rows before checking the deterministic inserted ID."""
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
    """Creates the structured-test collection schema and FLAT/COSINE index before load.

    Milvus v2 deliberately rejects loading a vector collection without an index.
    The schema matches the canonical gaokao contract so downstream tools can reuse it.
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
    parser = argparse.ArgumentParser(description="v2 structured-output Gaokao ingestion (test collection only)")
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
    parser.add_argument("--finalize-run-id", help="resume only the embedding, Milvus and recall stages from durable page evidence")
    parser.add_argument("--milvus-upsert-batch-size", type=int, default=MILVUS_UPSERT_BATCH_SIZE,
                        help="maximum number of entities per Milvus upsert request")
    parser.add_argument("--skip-milvus", action="store_true",
                        help="debug switch: stop after canonical publication (no embedding/Milvus writes).")
    arguments = parser.parse_args()
    if arguments.milvus_upsert_batch_size < 1:
        parser.error("--milvus-upsert-batch-size must be positive")
    # 红线在入口重复校验：即便调用方绕过默认值，v2 也绝不触碰生产 collection。
    if arguments.collection == PROTECTED_COLLECTION:
        parser.error(f"v2 refuses to write the production collection {PROTECTED_COLLECTION}; use the structured test collection")
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
        api_key = setting("OPENAI_API_KEY", dotenv)
        base_url = setting("OPENAI_BASE_URL", dotenv)
        if not api_key or not base_url:
            raise RuntimeError("OPENAI_API_KEY and OPENAI_BASE_URL must be configured before real visual ingestion")
        configured_bridge = (
            arguments.vision_bridge_container
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
    # A one-paper test names its exact asset directory directly or through the batch parent.
    asset_root_by_file = {
        pdf.name: configured_asset_root if (configured_asset_root / "asset-report.json").is_file()
        else configured_asset_root / pdf.stem
        for pdf in files
    }
    question_assets_by_file = {
        pdf.name: load_question_assets(asset_root_by_file[pdf.name], pdf)
        for pdf in files
    }
    source_hash_by_name = {pdf.name: sha256_file(pdf) for pdf in files}
    paper_type = str(config["paperType"]).lower()
    run_id = arguments.finalize_run_id or f"v2-{arguments.vision_provider}-{paper_type}-{datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')}-{uuid.uuid4().hex[:8]}"
    # transcriptionRunRoot 允许每个批次带自己的 test 标识目录；与 evidence-root 同一
    # 输出红线守卫，防止可配置项被用来把 run 证据写到项目外。
    configured_run_root = (PROJECT_ROOT / str(config.get("transcriptionRunRoot") or TRANSCRIPTION_RUN_ROOT)).resolve()
    if configured_run_root != ALLOWED_EVIDENCE_OUTPUT_ROOT and ALLOWED_EVIDENCE_OUTPUT_ROOT not in configured_run_root.parents:
        raise ValueError("transcriptionRunRoot must remain under this project's output directory")
    run_root = configured_run_root / run_id
    settings = config["visionOptimization"]
    all_questions: list[dict[str, Any]] = []
    model_calls: list[dict[str, Any]] = []
    if arguments.finalize_run_id:
        manifest_path = run_root / "run-manifest.json"
        if not manifest_path.is_file():
            raise RuntimeError(f"run {run_id} has no source-bound manifest; refuse unsafe evidence recovery")
        # finalize 轮重新解析证据时同样产生丢弃/归因事件；多轮追加到同一 JSONL，
        # SCAN 侧按 (kind, sourceFile, page, message) 去重即得确定事件集。
        set_pipeline_event_log(run_root / PIPELINE_EVENTS_FILENAME)
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
            page_questions = recognized_questions(evidence["response"], evidence["sourceFile"], int(evidence["page"]), arguments.vision_provider, question_assets_by_file[evidence["sourceFile"]])
            for question in page_questions:
                question["metadata"]["sourceSha256"] = source_hash_by_name[evidence["sourceFile"]]
            all_questions.extend(page_questions)
            model_calls.append({"taskSequence": evidence.get("taskSequence"), "workerThread": evidence.get("workerThread"), "sourceFile": evidence["sourceFile"], "page": evidence["page"], "pageText": recognized_page_text(evidence["response"], arguments.vision_provider), "usage": evidence.get("usage", {}), "elapsedMs": evidence.get("elapsedMs"), "attemptCount": len(evidence.get("attempts", [])), "recoveredFromEvidence": True})
    else:
        run_root.mkdir(parents=True, exist_ok=False)
        set_pipeline_event_log(run_root / PIPELINE_EVENTS_FILENAME)
        # This immutable manifest binds later --finalize-run-id execution to the exact input PDF bytes and policy.
        (run_root / "run-manifest.json").write_text(json.dumps({"runId": run_id, "paperType": config["paperType"], "subject": config["subject"], "pipeline": "gaokao-structured-v2", "configSha256": hashlib.sha256(arguments.config.read_bytes()).hexdigest(), "sources": {path.name: sha256_file(path) for path in files}}, ensure_ascii=False, indent=2), encoding="utf-8")
        ensure_pdf_renderer()
        jobs = [(sequence, pdf, page, run_root / sha256_file(pdf)) for sequence, (pdf, page) in enumerate(((pdf, page) for pdf in files for page in range(1, page_count(pdf) + 1)), start=1)]
        failures: list[str] = []
        with ThreadPoolExecutor(max_workers=requested_page_workers, thread_name_prefix=f"gaokao-{arguments.vision_provider}-page") as executor:
            futures = {executor.submit(process_page, job, run_id, settings, arguments, source_root, config.get("containerInputRoot", ""), requested_page_workers, question_assets_by_file[job[1].name]): job for job in jobs}
            for future in as_completed(futures):
                _sequence, pdf, page, _paper_root = futures[future]
                try:
                    questions, model_call = future.result()
                    for question in questions:
                        question["metadata"]["sourceSha256"] = source_hash_by_name[pdf.name]
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
    # 未合并掉的片段是“模型标了片段但前一页没标 continues”的协议不一致，告警而非静默吞掉。
    unmerged_fragments = [
        item for item in all_questions
        if not str(item["metadata"].get("questionNumber", "")).isdigit()
    ]
    for fragment in unmerged_fragments:
        metadata = fragment["metadata"]
        log_pipeline_event("v2-drop",
                           f"unmerged continuation fragment {metadata.get('sourceFile')} page {metadata.get('pageStart')}: "
                           f"{strip_figure_markers(fragment['text'])[:60]!r}",
                           reason="unmerged-fragment", sourceFile=metadata.get("sourceFile"),
                           page=metadata.get("pageStart"), parent=metadata.get("parentQuestionNumber", ""),
                           textPrefix=strip_figure_markers(fragment["text"])[:60])
    all_questions = [item for item in all_questions if str(item["metadata"].get("questionNumber", "")).isdigit()]
    # v2 明确不再调用 reconcile/repair：改号=用缺号集合反推打印题号，属于被废弃的
    # 正文猜测。同号冲突只保留首条，duplicateSkippedCount 暴露到报告供人工复核。
    all_questions, duplicate_skipped_count = canonical_question_records(all_questions, with_stats=True)
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
        paper_questions = [
            item for item in all_questions
            if item["metadata"].get("sourceFile") == source_file.name
        ]
        document_ref = str(uuid.uuid5(uuid.NAMESPACE_URL, f"{source_file.name}\n{source_hash}"))
        for question in paper_questions:
            metadata = question["metadata"]
            metadata["documentFullName"] = source_file.name
            metadata["documentRef"] = document_ref
            metadata["sourceSha256"] = source_hash
            question["id"] = canonical_question_id(source_hash, metadata.get("questionNumber"))
        published = publish_canonical_paper(
            evidence_root,
            source_file,
            source_hash,
            page_texts,
            all_questions,
            asset_root_by_file[source_file.name],
        )
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
    if arguments.skip_milvus:
        # 调试收敛：发布目录已生成即可停止，不触碰嵌入与向量库（软验收路径）。
        report = {"timestampUtc": utc_now(), "runId": run_id, "pipeline": "gaokao-structured-v2", "provider": arguments.vision_provider,
                  "model": arguments.vision_model, "selectedFileCount": len(files), "visionCallCount": len(model_calls),
                  "questionCount": len(all_questions), "fullDocumentCount": len(canonical_documents),
                  "duplicateSkippedCount": duplicate_skipped_count, "skippedMilvus": True,
                  "canonicalEvidenceRoot": str(evidence_root)}
        report_path = configured_run_root / f"{run_id}-report.json"
        report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
        print(json.dumps({"report": str(report_path), "runId": run_id, "questionCount": len(all_questions), "skippedMilvus": True}, ensure_ascii=False))
        return
    worker_key = setting("MATH_AGENT_WORKER_API_KEY", dotenv)
    vectors = embed_all([vector_text_of(item) for item in index_records], setting("MATH_AGENT_EMBEDDING_BASE_URL", dotenv, DEFAULT_EMBEDDING_URL), worker_key, arguments.timeout_seconds)
    milvus_uri = setting("MATH_AGENT_VECTOR_INDEX_MILVUS_URI", dotenv, DEFAULT_MILVUS_URI)
    milvus_token = setting("MATH_AGENT_MILVUS_TOKEN", dotenv) or ("root:" + setting("MATH_AGENT_MILVUS_ROOT_PASSWORD", dotenv) if setting("MATH_AGENT_MILVUS_ROOT_PASSWORD", dotenv) else "")
    ensure_collection(milvus_uri, milvus_token, arguments.collection, arguments.timeout_seconds)
    cleanup_stats = cleanup_source_records(
        milvus_uri, milvus_token, arguments.collection, [pdf.name for pdf in files], arguments.timeout_seconds)
    entities = [{PRIMARY_KEY_FIELD: item["id"], VECTOR_FIELD: vector, TEXT_FIELD: vector_text_of(item), METADATA_FIELD: vector_metadata(item)} for item, vector in zip(index_records, vectors, strict=True)]
    upsert_batch_count = milvus_upsert_batches(
        milvus_uri, milvus_token, arguments.collection, entities,
        arguments.milvus_upsert_batch_size, arguments.timeout_seconds)
    milvus_post(milvus_uri, milvus_token, "/v2/vectordb/collections/flush", {"collectionName": arguments.collection}, arguments.timeout_seconds)
    # 2026-09-01 test collection 实测：flush 后查询节点对新段的可见性有窗口期，
    # 立即 search 会返回空 data（不是数据丢失，几分钟后同向量可命中首条）。
    # 因此 flush 后先 release+load 强制段状态一致，再给召回最多 3 次有界复查；
    # 复查失败仍 fail-closed，绝不放行“只写不读”的索引。
    milvus_post(milvus_uri, milvus_token, "/v2/vectordb/collections/release", {"collectionName": arguments.collection}, arguments.timeout_seconds)
    milvus_post(milvus_uri, milvus_token, "/v2/vectordb/collections/load", {"collectionName": arguments.collection}, arguments.timeout_seconds)
    query_vector = embed_all([vector_text_of(all_questions[0])], setting("MATH_AGENT_EMBEDDING_BASE_URL", dotenv, DEFAULT_EMBEDDING_URL), worker_key, arguments.timeout_seconds)[0]
    recall_limit = RETRIEVAL_LIMIT
    hits: list[dict[str, Any]] = []
    for recall_attempt in range(1, MILVUS_MAX_ATTEMPTS + 1):
        recalled = milvus_post(milvus_uri, milvus_token, "/v2/vectordb/entities/search", {"collectionName": arguments.collection, "data": [query_vector], "annsField": VECTOR_FIELD, "limit": recall_limit, "outputFields": [PRIMARY_KEY_FIELD, TEXT_FIELD, METADATA_FIELD]}, arguments.timeout_seconds)
        hits = search_hits(recalled)
        if any(hit.get("id") == all_questions[0]["id"] or hit.get("entity", {}).get(PRIMARY_KEY_FIELD) == all_questions[0]["id"] for hit in hits):
            break
        if recall_attempt < MILVUS_MAX_ATTEMPTS:
            time.sleep(luna_retry_delay_seconds(recall_attempt))
    if not any(hit.get("id") == all_questions[0]["id"] or hit.get("entity", {}).get(PRIMARY_KEY_FIELD) == all_questions[0]["id"] for hit in hits):
        recalled_ids = [str(hit.get("id") or hit.get("entity", {}).get(PRIMARY_KEY_FIELD) or "") for hit in hits]
        raise RuntimeError(f"real Milvus recall did not return the inserted query question; queryId={all_questions[0]['id']}; hitIds={recalled_ids}")
    totals = {name: sum(int(call["usage"].get(name, 0) or 0) for call in model_calls) for name in ("prompt_tokens", "completion_tokens", "total_tokens")}
    report = {"timestampUtc": utc_now(), "runId": run_id, "pipeline": "gaokao-structured-v2", "provider": arguments.vision_provider, "model": arguments.vision_model, "selectedFileCount": len(files), "visionCallCount": len(model_calls), "questionCount": len(all_questions), "fullDocumentCount": len(canonical_documents), "duplicateSkippedCount": duplicate_skipped_count, "cleanup": cleanup_stats, "usage": totals, "concurrency": {"environmentVariable": GLOBAL_AI_CONCURRENCY_ENVIRONMENT_VARIABLE, "globalLimit": global_ai_concurrency, "effectivePageWorkers": requested_page_workers, "retryScope": "per_request_only", "maxAttemptsPerRequest": LUNA_MAX_ATTEMPTS}, "collection": arguments.collection, "milvusWrite": {"upsertEntityCount": len(entities), "upsertBatchSize": arguments.milvus_upsert_batch_size, "upsertBatchCount": upsert_batch_count, "flushCount": 1, "clientFullCollectionLoad": False}, "realRecall": {"queryQuestionId": all_questions[0]["id"], "hitCount": len(hits), "hits": hits}, "modelCalls": model_calls, "canonicalEvidenceRoot": str(evidence_root)}
    report_path = configured_run_root / f"{run_id}-report.json"
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({"report": str(report_path), "runId": run_id, "questionCount": len(all_questions), "usage": totals, "recallHitCount": len(hits)}, ensure_ascii=False))


if __name__ == "__main__":
    main()
