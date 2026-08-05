"""LangGraph runtime for the protected teaching-handout workflow.

The graph owns AI work only. Java remains the authority for identity, evidence
visibility, assets, business persistence and PDF publication.  Checkpoints and
events are written before a node returns so a process restart can resume from a
durable boundary without replaying completed model calls.
"""

from __future__ import annotations

from contextlib import closing, contextmanager
from dataclasses import dataclass, field
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import operator
import re
import sqlite3
import subprocess
import threading
import time
from typing import Annotated, Any, TypedDict

import requests
from fastapi import HTTPException
from langgraph.graph import END, START, StateGraph
from pydantic import BaseModel, ConfigDict, Field, ValidationError

from app.usage import UsageEvent, UsageLedger, cost_for, fallback_tokens


DEFAULT_GRAPH_VERSION = "handout-v1"
DEFAULT_CONTRACT_VERSION = "handout-ai-v1"
DEFAULT_CONTEXT_LIMIT = 12
DEFAULT_NODE_TIMEOUT_SECONDS = 420.0
DEFAULT_REPAIR_ATTEMPTS = 1
DEFAULT_MAX_EVIDENCE_CHARS = 16000
DEFAULT_MAX_OUTPUT_CHARS = 24000
DEFAULT_MIN_DOCUMENT_CHARS = 32
DEFAULT_MIN_QUESTION_TOKEN_MATCHES = 1
DEFAULT_HANDOUT_MAX_TOTAL_TOKENS = 56000
DEFAULT_HANDOUT_MAX_PROVIDER_CALLS = 8
DEFAULT_EVENT_PAGE_LIMIT = 100
MAX_EVENT_PAGE_LIMIT = 500
MAX_EVENT_HISTORY = 10000
# The lock covers the full graph rather than an individual checkpoint write.  A second replica waits for the
# first replica's durable result, then returns it without opening another provider socket for the same run.
DEFAULT_RUN_LOCK_WAIT_SECONDS = 900
QUESTION_MARKER_PATTERN = re.compile(r"(?ms)(?:^|\n)【题目\s*(\d+)】\s*\n?(.*?)(?=\n【题目\s*\d+】|\Z)")
QUESTION_TOKEN_PATTERN = re.compile(r"[A-Za-z]+(?:_[A-Za-z0-9]+)?(?:\([^)]*\))?|[-+]?\d+(?:\.\d+)?|[\u4e00-\u9fff]{2,}")
GENERIC_QUESTION_TOKENS = frozenset({"已知", "函数", "求", "在", "其中", "关于", "实数", "得到", "问题", "题目"})
LECTURE_FORBIDDEN_MARKERS = ("<wait>", "TEACHER_IMAGE", "/api/teacher/resources/assets/", "资料依据", "完整解答")
COMMON_FORBIDDEN_MARKERS = ("/api/teacher/resources/assets/", "TEACHER_IMAGE", "内部日志", "资源卡", "证据卡")
ANSWER_LEAK_MARKERS = ("答案：", "答案:", "参考答案", "最终答案", "完整解答", "评分点：", "评分标准：", "教师提示：")
STAGE_TITLES = {
    "teacher_writer": "教师版讲义",
    "student_writer": "学生版讲义",
    "lecture_writer": "16:10 课堂投影",
}
# The usage table's attempt number is the idempotency key; reserve a non-provider range for deterministic node rows.
RUNTIME_USAGE_ATTEMPTS = {
    "resource_curation": 1001,
    "teacher_writer": 1002,
    "student_writer": 1003,
    "lecture_writer": 1004,
    "structured_validation": 1005,
}
RUNTIME_USAGE_FALLBACK_ATTEMPT = 1099
# Provider attempts share one unique `(run_id, provider, model, attempt)` key.  Writer nodes run in parallel, so
# each node owns a stable block instead of restarting at attempt one and silently collapsing three billable calls.
PROVIDER_ATTEMPT_SLOT_SIZE = 100
PROVIDER_ATTEMPT_BASES = {
    "teacher_writer": 0,
    "student_writer": PROVIDER_ATTEMPT_SLOT_SIZE,
    "lecture_writer": PROVIDER_ATTEMPT_SLOT_SIZE * 2,
    "teacher_writer_repair": PROVIDER_ATTEMPT_SLOT_SIZE * 3,
    "student_writer_repair": PROVIDER_ATTEMPT_SLOT_SIZE * 4,
    "lecture_writer_repair": PROVIDER_ATTEMPT_SLOT_SIZE * 5,
}


def _utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def _bounded(value: str | None, limit: int) -> str:
    """Normalizes operator/model text before it enters a checkpoint or provider prompt."""
    normalized = "" if value is None else " ".join(value.split())
    return normalized if len(normalized) <= limit else normalized[: max(0, limit - 3)].rstrip() + "..."


def _text(value: Any) -> str:
    """Converts only scalar provider values to text; mappings are handled by the structured adapter below."""
    return value.strip() if isinstance(value, str) else ""


def _sum_usage(left: dict[str, int | float], right: dict[str, int | float]) -> dict[str, int | float]:
    """Adds initial and one bounded repair usage so the workflow budget sees both provider calls."""
    left_cost = float(left.get("estimatedCost", -1.0))
    right_cost = float(right.get("estimatedCost", -1.0))
    return {
        "promptTokens": int(left.get("promptTokens", 0)) + int(right.get("promptTokens", 0)),
        "completionTokens": int(left.get("completionTokens", 0)) + int(right.get("completionTokens", 0)),
        "totalTokens": int(left.get("totalTokens", 0)) + int(right.get("totalTokens", 0)),
        # An unknown price remains unknown after repair; -1 must never become a negative sum.
        "estimatedCost": left_cost + right_cost if left_cost >= 0 and right_cost >= 0 else -1.0,
    }


def _string_list(value: Any, limit: int = 24) -> list[str]:
    """Normalizes scalar/list citation fields without allowing nulls or nested provider objects into the contract."""
    values = value if isinstance(value, list) else [value]
    return [_text(item) for item in values if _text(item)][:limit]


def _submitted_questions(question_text: str) -> list[str]:
    """Returns the backend-canonical question batch without splitting on ordinary whitespace or blank lines."""
    matches = list(QUESTION_MARKER_PATTERN.finditer(question_text or ""))
    if not matches:
        return [question_text.strip()] if question_text and question_text.strip() else []
    ordered: list[str] = []
    expected_number = 1
    for match in matches:
        number = int(match.group(1))
        if number != expected_number:
            raise ValueError(f"question order is not consecutive: expected {expected_number}, got {number}")
        body = match.group(2).strip()
        if not body:
            raise ValueError(f"question {number} is empty")
        ordered.append(body)
        expected_number += 1
    return ordered


def _question_tokens(question: str) -> list[str]:
    """Selects distinctive formula/geometry terms for a lightweight order-preserving semantic gate."""
    tokens: list[str] = []
    for token in QUESTION_TOKEN_PATTERN.findall(question or ""):
        normalized = token.strip()
        if not normalized or normalized in GENERIC_QUESTION_TOKENS:
            continue
        if normalized not in tokens:
            tokens.append(normalized)
    # A long Chinese phrase is useful when a problem has no Latin symbol, while keeping the gate bounded.
    return tokens[:4]


def _markdown_from_cards(value: Any) -> str:
    """Projects lecture cards to content and renderer-owned whitespace without exposing card metadata."""
    cards = value.get("cards") if isinstance(value, dict) and "cards" in value else value
    if isinstance(cards, dict):
        cards = [cards]
    if not isinstance(cards, list):
        return _text(cards)
    parts: list[str] = []
    for card in cards:
        if isinstance(card, str) and card.strip():
            parts.append(card.strip())
            continue
        if not isinstance(card, dict):
            continue
        card_type = _text(card.get("type")).lower()
        if card_type in {"resource", "evidence", "source", "transition", "checkpoint"}:
            continue
        title = _text(card.get("title"))
        content = card.get("content")
        if isinstance(content, list):
            content_text = "\n".join(f"- {_text(item)}" for item in content if _text(item))
        else:
            content_text = _text(content)
        if not title or not content_text:
            continue
        parts.append(f"## {title}\n\n{content_text}\n\nMATHAGENTHTMLSPACER260")
    return "\n\n".join(parts).strip()


def _structured_content(payload: dict[str, Any], stage_code: str) -> str:
    """Extracts the audience field deterministically before any model repair is considered."""
    if stage_code == "teacher_writer":
        candidates = ("teacherExplanation", "markdown", "content", "body", "result")
    elif stage_code == "student_writer":
        candidates = ("studentWorksheet", "markdown", "content", "body", "result")
    else:
        candidates = ("lectureCards", "markdown", "content", "body", "result")
    for field in candidates:
        if field not in payload:
            continue
        value = payload[field]
        if stage_code == "lecture_writer" and isinstance(value, (dict, list)):
            content = _markdown_from_cards(value)
        elif isinstance(value, list):
            content = "\n".join(_text(item) for item in value if _text(item))
        else:
            content = _text(value)
        if content.strip():
            return content.strip()
    # Rich provider objects sometimes omit the audience wrapper but still expose card-like fields.
    if stage_code == "lecture_writer" and ("cards" in payload or isinstance(payload.get("items"), list)):
        return _markdown_from_cards(payload.get("cards", payload.get("items")))
    return ""


def _title_from_markdown(markdown: str) -> str:
    """Uses a generated H1 when present; title fallback is applied only after content is known to be non-empty."""
    for line in markdown.splitlines():
        if line.startswith("# ") and line[2:].strip():
            return line[2:].strip()[:600]
    return ""


def _deterministic_markdown_cleanup(markdown: str, stage_code: str) -> str:
    """Removes renderer/runtime wrappers in code before considering a model repair.

    The provider is allowed to describe classroom interactions, but `<wait>`, Markdown rules, and fill-in
    underscores are protocol/layout artifacts rather than lecture content.  Removing only the affected blocks keeps
    the submitted questions and knowledge explanation intact, while unsafe asset paths remain validation failures.
    """
    cleaned = re.sub(r"(?is)<wait\b[^>]*>.*?</wait\s*>", "\n", markdown or "")
    cleaned = re.sub(r"(?i)</?wait\b[^>]*>", "", cleaned)
    if stage_code == "lecture_writer":
        kept: list[str] = []
        for line in cleaned.splitlines():
            stripped = line.strip()
            if re.fullmatch(r"(?:[-*_]\s*){3,}", stripped) or re.fullmatch(r"_{2,}", stripped):
                continue
            kept.append(line)
        cleaned = "\n".join(kept)
    return re.sub(r"\n{3,}", "\n\n", cleaned).strip()


class HandoutRunRequest(BaseModel):
    """Minimal cross-language request; no tenant identity, path, SQL or secret is accepted."""

    # Reject identity/path overrides at the HTTP boundary instead of silently discarding a hostile field.
    model_config = ConfigDict(populate_by_name=True, extra="forbid")

    run_id: str = Field(alias="runId", min_length=8, max_length=80)
    task_id: str = Field(alias="taskId", min_length=1, max_length=100)
    contract_version: str = Field(default=DEFAULT_CONTRACT_VERSION, alias="contractVersion", min_length=1, max_length=40)
    writing_goal: str = Field(alias="writingGoal", min_length=1, max_length=1200)
    question_text: str = Field(alias="questionText", min_length=1, max_length=16000)
    evidence_refs: list[str] = Field(default_factory=list, alias="evidenceRefs", max_length=24)
    graph_version: str = Field(default=DEFAULT_GRAPH_VERSION, alias="graphVersion", min_length=1, max_length=40)
    idempotency_key: str = Field(default="", alias="idempotencyKey", max_length=160)
    trace_id: str | None = Field(default=None, alias="traceId", max_length=120)
    traceparent: str | None = Field(default=None, max_length=160)
    deadline_epoch_ms: int | None = Field(default=None, alias="deadlineEpochMs", ge=0)
    resume: bool = False

    def compact(self) -> "HandoutRunRequest":
        """Keeps the payload bounded while preserving the question verbatim up to the API contract limit."""
        return self.model_copy(update={
            "writing_goal": _bounded(self.writing_goal, 1200),
            "question_text": _bounded(self.question_text, 16000),
            "evidence_refs": list(dict.fromkeys(_bounded(item, 240) for item in self.evidence_refs if item.strip()))[:24],
            "graph_version": _bounded(self.graph_version, 40) or DEFAULT_GRAPH_VERSION,
            # Legacy callers used runId as the retry identity. Preserve that deterministic behavior during rollout.
            "idempotency_key": _bounded(self.idempotency_key, 160) or self.run_id,
            "contract_version": _bounded(self.contract_version, 40) or DEFAULT_CONTRACT_VERSION,
        })


class EvidenceItem(BaseModel):
    """Compact permission-filtered evidence returned by Java."""

    model_config = ConfigDict(populate_by_name=True, extra="ignore")

    ref: str = Field(default="", max_length=240)
    title: str = Field(default="", max_length=600)
    excerpt: str = Field(default="", max_length=3000)
    asset_id: str | None = Field(default=None, alias="assetId", max_length=160)


class EvidenceSnapshot(BaseModel):
    """Single Java context response shared by all three Writer nodes."""

    query: str = ""
    items: list[EvidenceItem] = Field(default_factory=list, max_length=DEFAULT_CONTEXT_LIMIT)
    source: str = "java-broker"

    def prompt_text(self) -> str:
        rows = []
        for item in self.items[:DEFAULT_CONTEXT_LIMIT]:
            rows.append(json.dumps(item.model_dump(by_alias=True, exclude_none=True), ensure_ascii=False, separators=(",", ":")))
        return "\n".join(rows)[:DEFAULT_MAX_EVIDENCE_CHARS]


class WriterDocument(BaseModel):
    """Validated audience-specific document returned by a Writer node."""

    model_config = ConfigDict(populate_by_name=True, extra="ignore")

    stage_code: str = Field(alias="stageCode", min_length=1, max_length=40)
    title: str = Field(min_length=1, max_length=600)
    markdown: str = Field(min_length=1, max_length=DEFAULT_MAX_OUTPUT_CHARS)
    citations: list[str] = Field(default_factory=list, max_length=24)
    warnings: list[str] = Field(default_factory=list, max_length=24)


class ValidationReport(BaseModel):
    """Publication-independent structural validation result."""

    valid: bool
    repaired: bool = False
    errors: list[str] = Field(default_factory=list)


class NodeMetric(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    node: str
    status: str
    provider: str = ""
    model: str = ""
    elapsed_ms: int = Field(alias="elapsedMs")
    provider_calls: int = Field(default=0, alias="providerCalls")
    java_requests: int = Field(default=0, alias="javaRequests")
    payload_bytes: int = Field(default=0, alias="payloadBytes")
    prompt_tokens: int = Field(default=0, alias="promptTokens")
    completion_tokens: int = Field(default=0, alias="completionTokens")
    total_tokens: int = Field(default=0, alias="totalTokens")
    estimated_cost: float = Field(default=0.0, alias="estimatedCost")
    error: str | None = None


class HandoutMetrics(BaseModel):
    """Run-level telemetry required for production cost and latency comparison."""

    model_config = ConfigDict(populate_by_name=True)

    started_at: str = Field(alias="startedAt")
    finished_at: str | None = Field(default=None, alias="finishedAt")
    elapsed_ms: int = Field(default=0, alias="elapsedMs")
    node_metrics: list[NodeMetric] = Field(default_factory=list, alias="nodeMetrics")
    provider_successes: int = Field(default=0, alias="providerSuccesses")
    provider_failures: int = Field(default=0, alias="providerFailures")
    java_requests: int = Field(default=0, alias="javaRequests")
    java_payload_bytes: int = Field(default=0, alias="javaPayloadBytes")
    prompt_tokens: int = Field(default=0, alias="promptTokens")
    completion_tokens: int = Field(default=0, alias="completionTokens")
    total_tokens: int = Field(default=0, alias="totalTokens")
    estimated_cost: float = Field(default=0.0, alias="estimatedCost")
    cost_known: bool = Field(default=True, alias="costKnown")
    system_load: list[dict[str, Any]] = Field(default_factory=list, alias="systemLoad")


class HandoutDraftPackage(BaseModel):
    """One result sent back to Java; Java still decides whether it can be published."""

    model_config = ConfigDict(populate_by_name=True)

    run_id: str = Field(alias="runId")
    task_id: str = Field(alias="taskId")
    contract_version: str = Field(default=DEFAULT_CONTRACT_VERSION, alias="contractVersion")
    graph_version: str = Field(alias="graphVersion")
    status: str
    evidence: EvidenceSnapshot
    documents: dict[str, WriterDocument] = Field(default_factory=dict)
    validation: ValidationReport
    metrics: HandoutMetrics


class HandoutRunState(TypedDict, total=False):
    """LangGraph state intentionally contains only bounded snapshots, never local paths."""

    request: HandoutRunRequest
    evidence: EvidenceSnapshot
    writers: Annotated[list[WriterDocument], operator.add]
    package: HandoutDraftPackage


class _RunTelemetry:
    """Thread-safe accumulator because three Writer nodes execute concurrently."""

    def __init__(self, run_id: str) -> None:
        self.run_id = run_id
        self.started = time.monotonic()
        self.metrics = HandoutMetrics(started_at=_utc_now())
        self._lock = threading.Lock()
        self._reserved_provider_calls = 0
        self._reserved_tokens = 0
        self._last_process_cpu_seconds: float | None = None
        self._last_cpu_sample_at: float | None = None

    @staticmethod
    def _budget_int(name: str, default: int) -> int:
        """Reads a deployment budget once per reservation and never permits a non-positive limit."""
        try:
            return max(1, int(os.getenv(name, str(default))))
        except ValueError:
            return default

    def reserve_provider_call(self, prompt_tokens: int, max_output_tokens: int) -> None:
        """Reserves the worst-case call envelope before opening a provider socket.

        The Java AgentRun budget is bypassed by the Python graph, so this local reservation is the final protection
        against a repair/retry storm.  Reservations include the configured output ceiling; concurrent Writer nodes
        therefore cannot each spend the same remaining budget based on stale counters.
        """
        with self._lock:
            max_calls = self._budget_int("MATH_AGENT_HANDOUT_MAX_PROVIDER_CALLS", DEFAULT_HANDOUT_MAX_PROVIDER_CALLS)
            max_tokens = self._budget_int("MATH_AGENT_HANDOUT_MAX_TOTAL_TOKENS", DEFAULT_HANDOUT_MAX_TOTAL_TOKENS)
            planned = max(0, int(prompt_tokens)) + max(0, int(max_output_tokens))
            if self._reserved_provider_calls >= max_calls:
                raise RuntimeError("handout provider-call budget exceeded before provider request")
            if self._reserved_tokens + planned > max_tokens:
                raise RuntimeError("handout token budget exceeded before provider request")
            self._reserved_provider_calls += 1
            self._reserved_tokens += planned

    def sample_system(self) -> dict[str, Any]:
        sample: dict[str, Any] = {"timestamp": _utc_now()}
        if os.name == "nt":
            # Windows Python does not expose getloadavg or a portable ru_maxrss. Use the real process clock for CPU
            # utilization and Win32 working-set counters for RSS rather than reporting unavailable telemetry.
            now = time.monotonic()
            process_cpu = time.process_time()
            if self._last_process_cpu_seconds is None or self._last_cpu_sample_at is None:
                sample["cpu_percent"] = None
            else:
                wall_seconds = max(now - self._last_cpu_sample_at, 1e-6)
                cpu_count = max(os.cpu_count() or 1, 1)
                sample["cpu_percent"] = round(
                    max(0.0, min(100.0, (process_cpu - self._last_process_cpu_seconds) / wall_seconds * 100.0 / cpu_count)),
                    3,
                )
            self._last_process_cpu_seconds = process_cpu
            self._last_cpu_sample_at = now
            sample["rss_bytes"] = self._windows_rss_bytes()
        else:
            try:
                sample["cpu_percent"] = round(os.getloadavg()[0], 3)
            except (AttributeError, OSError):
                sample["cpu_percent"] = None
            try:
                pages = os.sysconf("SC_PAGE_SIZE")
                rss = os.getrusage(os.RUSAGE_SELF).ru_maxrss
                sample["rss_bytes"] = int(rss * pages)
            except (AttributeError, ValueError, OSError):
                sample["rss_bytes"] = None
        try:
            completed = subprocess.run(
                ["nvidia-smi", "--query-gpu=utilization.gpu,memory.used,memory.total", "--format=csv,noheader,nounits"],
                capture_output=True, text=True, timeout=2, check=False,
            )
            sample["gpu"] = [line.strip() for line in completed.stdout.splitlines() if line.strip()]
        except (FileNotFoundError, OSError, subprocess.TimeoutExpired):
            sample["gpu"] = []
        with self._lock:
            self.metrics.system_load.append(sample)
        return sample

    @staticmethod
    def _windows_rss_bytes() -> int | None:
        """Reads the current process working set without adding a platform-specific monitoring dependency."""
        try:
            import ctypes

            class _ProcessMemoryCounters(ctypes.Structure):
                _fields_ = [
                    ("cb", ctypes.c_ulong),
                    ("PageFaultCount", ctypes.c_ulong),
                    ("PeakWorkingSetSize", ctypes.c_size_t),
                    ("WorkingSetSize", ctypes.c_size_t),
                    ("QuotaPeakPagedPoolUsage", ctypes.c_size_t),
                    ("QuotaPagedPoolUsage", ctypes.c_size_t),
                    ("QuotaPeakNonPagedPoolUsage", ctypes.c_size_t),
                    ("QuotaNonPagedPoolUsage", ctypes.c_size_t),
                    ("PagefileUsage", ctypes.c_size_t),
                    ("PeakPagefileUsage", ctypes.c_size_t),
                ]

            counters = _ProcessMemoryCounters()
            counters.cb = ctypes.sizeof(counters)
            process = ctypes.windll.kernel32.GetCurrentProcess()
            get_memory_info = ctypes.windll.psapi.GetProcessMemoryInfo
            get_memory_info.restype = ctypes.c_bool
            get_memory_info.argtypes = [ctypes.c_void_p, ctypes.POINTER(_ProcessMemoryCounters), ctypes.c_ulong]
            if not get_memory_info(process, ctypes.byref(counters), counters.cb):
                return None
            return int(counters.WorkingSetSize) or None
        except (AttributeError, OSError, TypeError):
            return None

    def record(self, metric: NodeMetric) -> None:
        with self._lock:
            self.metrics.node_metrics.append(metric)
            self.metrics.provider_successes += metric.provider_calls if metric.status == "SUCCESS" else 0
            self.metrics.provider_failures += metric.provider_calls if metric.status == "FAILED" else 0
            self.metrics.java_requests += metric.java_requests
            self.metrics.java_payload_bytes += metric.payload_bytes
            self.metrics.prompt_tokens += metric.prompt_tokens
            self.metrics.completion_tokens += metric.completion_tokens
            self.metrics.total_tokens += metric.total_tokens
            # -1 is the explicit unknown-cost sentinel. Never add it as if it were a real currency amount.
            if metric.estimated_cost < 0:
                self.metrics.cost_known = False
            elif self.metrics.cost_known:
                self.metrics.estimated_cost += metric.estimated_cost

    def finish(self) -> HandoutMetrics:
        with self._lock:
            self.metrics.finished_at = _utc_now()
            self.metrics.elapsed_ms = int((time.monotonic() - self.started) * 1000)
            return self.metrics.model_copy(deep=True)


class _CheckpointStore:
    """Durable checkpoint/event store with a shared MySQL mode and a SQLite development fallback.

    MySQL is selected explicitly in production because multiple worker replicas must observe the same node boundary.
    SQLite remains useful for isolated unit tests and a single local worker; the public methods deliberately stay the
    same so changing persistence does not change graph semantics.
    """

    def __init__(self) -> None:
        # SQLite is the safe dependency-free default for unit tests and one-process development; Compose production
        # explicitly selects MySQL so a missing database cannot be hidden behind a local container file.
        backend = os.getenv("MATH_AGENT_HANDOUT_CHECKPOINT_BACKEND", "sqlite").strip().lower()
        self.backend = backend if backend in {"mysql", "sqlite"} else "sqlite"
        # Checkpoint writes can be issued by the three parallel writer nodes, so this lock remains narrow.
        self._lock = threading.Lock()
        # SQLite is a one-process fallback. Its per-run gates keep duplicate local submissions apart without blocking
        # sibling writer checkpoints; production replicas use the MySQL named lock below instead.
        self._sqlite_run_locks: dict[str, threading.Lock] = {}
        self._sqlite_run_locks_guard = threading.Lock()
        if self.backend == "mysql":
            self._ensure_mysql_schema()
            return
        configured = os.getenv("MATH_AGENT_HANDOUT_CHECKPOINT_DB", "/app/data/handout-checkpoints.sqlite3")
        self.path = Path(configured)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        with self._connect() as conn:
            conn.executescript(
                """
                CREATE TABLE IF NOT EXISTS handout_checkpoint (
                    run_id TEXT PRIMARY KEY,
                    status TEXT NOT NULL,
                    state_json TEXT NOT NULL,
                    updated_at TEXT NOT NULL
                );
                CREATE TABLE IF NOT EXISTS handout_event (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    run_id TEXT NOT NULL,
                    event_json TEXT NOT NULL,
                    created_at TEXT NOT NULL
                );
                CREATE INDEX IF NOT EXISTS idx_handout_event_run ON handout_event(run_id, id);
                """
            )

    def _connect(self) -> sqlite3.Connection:
        conn = sqlite3.connect(self.path, timeout=30, check_same_thread=False)
        conn.row_factory = sqlite3.Row
        return conn

    @staticmethod
    def _mysql_connection():
        """Creates a short-lived UTF-8 MySQL connection; transaction scope is kept inside each store operation."""
        import pymysql
        return pymysql.connect(
            host=os.getenv("MATH_AGENT_DB_HOST", "mysql"),
            port=int(os.getenv("MATH_AGENT_DB_PORT", "3306")),
            # Production must set this to the dedicated ai_runtime account; root is never a fallback identity.
            user=os.getenv("MATH_AGENT_DB_USERNAME", "ai_runtime"),
            password=os.getenv("MATH_AGENT_DB_PASSWORD", ""),
            database=os.getenv("MATH_AGENT_DB_NAME", "math_agent_rag"),
            autocommit=False,
            charset="utf8mb4",
        )

    def _ensure_mysql_schema(self) -> None:
        """Verifies the Flyway-owned tables are accessible before a restricted worker accepts model work."""
        import pymysql
        try:
            with self._mysql_connection() as conn:
                with conn.cursor() as cursor:
                    # These reads prove migrations are complete and the least-privileged account has only the
                    # runtime tables it needs. DDL is intentionally owned by Java/Flyway, never the AI process.
                    cursor.execute("SELECT 1 FROM handout_checkpoint LIMIT 1")
                    cursor.execute("SELECT 1 FROM handout_event LIMIT 1")
                    cursor.execute("SELECT 1 FROM ai_usage_event LIMIT 1")
        except pymysql.MySQLError as exc:
            raise RuntimeError("shared handout runtime MySQL schema or restricted account is unavailable") from exc

    def _mysql_save(self, run_id: str, status: str, encoded: str, event_encoded: str, now: str) -> None:
        """Serializes concurrent writers with a row lock before merging sibling state snapshots."""
        with self._mysql_connection() as conn:
            with conn.cursor() as cursor:
                cursor.execute(
                    "INSERT INTO handout_checkpoint(run_id,status,state_json,updated_at) VALUES(%s,%s,%s,%s) "
                    "ON DUPLICATE KEY UPDATE run_id=VALUES(run_id)",
                    (run_id, status, "{}", now),
                )
                cursor.execute("SELECT state_json FROM handout_checkpoint WHERE run_id=%s FOR UPDATE", (run_id,))
                row = cursor.fetchone()
                previous_state = json.loads(row[0]) if row and row[0] else {}
                incoming_state = json.loads(encoded)
                merged_state = self._merge_state(previous_state, incoming_state)
                cursor.execute(
                    "UPDATE handout_checkpoint SET status=%s,state_json=%s,updated_at=%s WHERE run_id=%s",
                    (status, json.dumps(merged_state, ensure_ascii=False, separators=(",", ":")), now, run_id),
                )
                cursor.execute(
                    "INSERT INTO handout_event(run_id,event_json,created_at) VALUES(%s,%s,%s)",
                    (run_id, event_encoded, now),
                )
            conn.commit()

    @contextmanager
    def run_lock(self, run_id: str):
        """Claims one durable graph execution for a run across all worker replicas.

        A checkpoint row lock protects each state merge but is released between graph nodes.  MySQL's named lock
        remains held through the provider calls, so a concurrent redelivery re-reads the completed checkpoint after
        the owner releases it instead of duplicating a billable writer call.  The lock name contains only an opaque
        run ID and is bounded by the same operator-configurable graph timeout.
        """
        if self.backend != "mysql":
            with self._sqlite_run_locks_guard:
                local_lock = self._sqlite_run_locks.setdefault(run_id, threading.Lock())
            with local_lock:
                yield
            return
        wait_seconds = max(0, int(os.getenv(
            "MATH_AGENT_HANDOUT_RUN_LOCK_WAIT_SECONDS", str(DEFAULT_RUN_LOCK_WAIT_SECONDS))))
        lock_name = f"math-agent:handout:{run_id}"
        with self._mysql_connection() as conn:
            with conn.cursor() as cursor:
                cursor.execute("SELECT GET_LOCK(%s,%s)", (lock_name, wait_seconds))
                acquired = cursor.fetchone()
            if not acquired or int(acquired[0] or 0) != 1:
                raise HTTPException(status_code=409, detail="HANDOUT_RUN_LOCK_TIMEOUT")
            try:
                yield
            finally:
                with conn.cursor() as cursor:
                    cursor.execute("SELECT RELEASE_LOCK(%s)", (lock_name,))

    def save(self, run_id: str, status: str, state: dict[str, Any], event: dict[str, Any]) -> None:
        event_encoded = json.dumps(_jsonable(event), ensure_ascii=False, separators=(",", ":"))
        now = _utc_now()
        encoded = json.dumps(_jsonable(state), ensure_ascii=False, separators=(",", ":"))
        if self.backend == "mysql":
            with self._lock:
                self._mysql_save(run_id, status, encoded, event_encoded, now)
            return
        with self._lock, closing(self._connect()) as conn:
            previous = conn.execute("SELECT state_json FROM handout_checkpoint WHERE run_id=?", (run_id,)).fetchone()
            previous_state = json.loads(previous["state_json"]) if previous is not None else {}
            merged_state = self._merge_state(previous_state, _jsonable(state))
            encoded = json.dumps(merged_state, ensure_ascii=False, separators=(",", ":"))
            conn.execute(
                "INSERT INTO handout_checkpoint(run_id,status,state_json,updated_at) VALUES(?,?,?,?) "
                "ON CONFLICT(run_id) DO UPDATE SET status=excluded.status,state_json=excluded.state_json,updated_at=excluded.updated_at",
                (run_id, status, encoded, now),
            )
            conn.execute("INSERT INTO handout_event(run_id,event_json,created_at) VALUES(?,?,?)", (run_id, event_encoded, now))
            conn.commit()

    @staticmethod
    def _merge_state(previous: dict[str, Any], incoming: dict[str, Any]) -> dict[str, Any]:
        """Merges sibling node snapshots so concurrent Writer checkpoints cannot erase one another."""
        merged = dict(previous)
        for key, value in incoming.items():
            if key not in {"writers", "evidence"}:
                merged[key] = value
        if incoming.get("evidence") is not None:
            merged["evidence"] = incoming["evidence"]
        writer_by_stage: dict[str, Any] = {}
        for item in [*(previous.get("writers") or []), *(incoming.get("writers") or [])]:
            if not isinstance(item, dict):
                continue
            stage = str(item.get("stageCode") or item.get("stage_code") or "")
            if stage:
                writer_by_stage[stage] = item
        if writer_by_stage:
            merged["writers"] = list(writer_by_stage.values())
        return merged

    def load(self, run_id: str) -> tuple[str, dict[str, Any]] | None:
        if self.backend == "mysql":
            with self._mysql_connection() as conn:
                with conn.cursor() as cursor:
                    cursor.execute("SELECT status,state_json FROM handout_checkpoint WHERE run_id=%s", (run_id,))
                    row = cursor.fetchone()
            return (str(row[0]), json.loads(row[1])) if row else None
        with self._lock, closing(self._connect()) as conn:
            row = conn.execute("SELECT status,state_json FROM handout_checkpoint WHERE run_id=?", (run_id,)).fetchone()
        if row is None:
            return None
        return str(row["status"]), json.loads(row["state_json"])

    def events(self, run_id: str) -> list[dict[str, Any]]:
        return [event for _, event in self.events_after(run_id, 0, MAX_EVENT_HISTORY)]

    def events_after(self, run_id: str, after_id: int = 0, limit: int = 100) -> list[tuple[int, dict[str, Any]]]:
        """Reads a bounded event page after a cursor, enabling reconnectable real-time SSE."""
        bounded_limit = max(1, min(int(limit), MAX_EVENT_PAGE_LIMIT))
        if self.backend == "mysql":
            with self._mysql_connection() as conn:
                with conn.cursor() as cursor:
                    cursor.execute(
                        "SELECT event_id,event_json FROM handout_event WHERE run_id=%s AND event_id>%s "
                        "ORDER BY event_id LIMIT %s", (run_id, max(0, int(after_id)), bounded_limit))
                    rows = cursor.fetchall()
            return [(int(row[0]), json.loads(row[1])) for row in rows]
        with self._lock, closing(self._connect()) as conn:
            rows = conn.execute(
                "SELECT id,event_json FROM handout_event WHERE run_id=? AND id>? ORDER BY id LIMIT ?",
                (run_id, max(0, int(after_id)), bounded_limit),
            ).fetchall()
        return [(int(row["id"]), json.loads(row["event_json"])) for row in rows]


def _jsonable(value: Any) -> Any:
    if isinstance(value, BaseModel):
        return value.model_dump(by_alias=True, exclude_none=True)
    if isinstance(value, dict):
        return {key: _jsonable(item) for key, item in value.items()}
    if isinstance(value, (list, tuple)):
        return [_jsonable(item) for item in value]
    return value


class HandoutRuntime:
    """Executes the complete graph with one Java context request and three parallel model writers."""

    def __init__(self) -> None:
        self._session = requests.Session()
        self._session.headers.update({"Content-Type": "application/json"})
        self._checkpoint = _CheckpointStore()
        self._telemetry_by_run: dict[str, _RunTelemetry] = {}
        self._telemetry_lock = threading.Lock()
        self._graph = self._build_graph()

    def execute(self, request: HandoutRunRequest) -> HandoutDraftPackage:
        request = request.compact()
        # Obtain the cross-process ownership gate before reading any checkpoint.  Otherwise two replicas can both
        # observe an empty row and independently start the three writer nodes before either checkpoint is written.
        with self._checkpoint.run_lock(request.run_id):
            return self._execute_locked(request)

    def _execute_locked(self, request: HandoutRunRequest) -> HandoutDraftPackage:
        """Runs one graph after the durable run-level ownership gate has been acquired."""
        self._check_deadline(request)
        telemetry = _RunTelemetry(request.run_id)
        with self._telemetry_lock:
            self._telemetry_by_run[request.run_id] = telemetry
        telemetry.sample_system()
        started_state: HandoutRunState = {"request": request}
        existing = self._checkpoint.load(request.run_id) if request.resume else None
        if existing:
            saved_request = existing[1].get("request") if isinstance(existing[1], dict) else None
            if isinstance(saved_request, dict):
                saved_graph_version = str(saved_request.get("graphVersion", DEFAULT_GRAPH_VERSION))
                saved_idempotency_key = str(saved_request.get("idempotencyKey", request.run_id))
                if saved_graph_version != request.graph_version:
                    raise HTTPException(status_code=409, detail="GRAPH_VERSION_INCOMPATIBLE")
                if saved_idempotency_key != request.idempotency_key:
                    raise HTTPException(status_code=409, detail="IDEMPOTENCY_KEY_INCOMPATIBLE")
        if existing and existing[0] == "COMPLETED" and existing[1].get("package"):
            return HandoutDraftPackage.model_validate(existing[1]["package"])
        if existing and existing[1].get("evidence"):
            started_state["evidence"] = EvidenceSnapshot.model_validate(existing[1]["evidence"])
        if existing and existing[1].get("writers"):
            # A node checkpoint is authoritative after queue redelivery. Resumed nodes return validated artifacts
            # without opening another provider socket, so retries cannot silently double token cost.
            started_state["writers"] = [WriterDocument.model_validate(item) for item in existing[1]["writers"]]
        self._checkpoint.save(request.run_id, "RUNNING", started_state, {"event": "started", "graphVersion": request.graph_version})
        try:
            state = self._graph.invoke(started_state)
            package = state["package"]
            telemetry.sample_system()
            package = package.model_copy(update={"metrics": telemetry.finish()})
            final_state = dict(state)
            final_state["package"] = package
            self._checkpoint.save(request.run_id, package.status, final_state, {"event": "completed", "status": package.status})
            return package
        except HTTPException:
            telemetry.sample_system()
            latest = self._checkpoint.load(request.run_id)
            self._checkpoint.save(request.run_id, "FAILED", latest[1] if latest else started_state, {"event": "failed", "error": "http_error"})
            raise
        except Exception as exc:
            telemetry.sample_system()
            latest = self._checkpoint.load(request.run_id)
            self._checkpoint.save(request.run_id, "FAILED", latest[1] if latest else started_state, {"event": "failed", "error": type(exc).__name__})
            raise HTTPException(status_code=503, detail="Handout graph failed") from exc
        finally:
            with self._telemetry_lock:
                self._telemetry_by_run.pop(request.run_id, None)

    def events(self, run_id: str) -> list[dict[str, Any]]:
        """Returns only operational events; prompt and source content never enter this stream."""
        return self._checkpoint.events(run_id)

    def event_page(self, run_id: str, after_id: int = 0, limit: int = DEFAULT_EVENT_PAGE_LIMIT) -> list[tuple[int, dict[str, Any]]]:
        """Returns one bounded event-store page for SSE and reconnecting control-plane consumers."""
        return self._checkpoint.events_after(run_id, after_id, limit)

    def _build_graph(self):
        graph = StateGraph(HandoutRunState)
        graph.add_node("resource_curation", self._resource_curation)
        graph.add_node("teacher_writer", self._teacher_writer)
        graph.add_node("student_writer", self._student_writer)
        graph.add_node("lecture_writer", self._lecture_writer)
        graph.add_node("structured_validation", self._structured_validation)
        graph.add_edge(START, "resource_curation")
        graph.add_edge("resource_curation", "teacher_writer")
        graph.add_edge("resource_curation", "student_writer")
        graph.add_edge("resource_curation", "lecture_writer")
        graph.add_edge("teacher_writer", "structured_validation")
        graph.add_edge("student_writer", "structured_validation")
        graph.add_edge("lecture_writer", "structured_validation")
        graph.add_edge("structured_validation", END)
        return graph.compile()

    def _resource_curation(self, state: HandoutRunState) -> dict[str, Any]:
        request = state["request"]
        self._check_deadline(request)
        started = time.monotonic()
        if state.get("evidence") is not None:
            self._record_node(request, "resource_curation", started, "RESUMED")
            return {"evidence": state["evidence"]}
        payload = {"runId": request.run_id, "query": _bounded(request.question_text + " " + request.writing_goal, 6000), "evidenceRefs": request.evidence_refs, "limit": int(os.getenv("MATH_AGENT_HANDOUT_CONTEXT_LIMIT", str(DEFAULT_CONTEXT_LIMIT)))}
        try:
            response = self._java_context(payload)
            evidence = EvidenceSnapshot.model_validate(response)
            self._record_node(request, "resource_curation", started, "SUCCESS", java_requests=1, payload_bytes=len(json.dumps(payload, ensure_ascii=False).encode("utf-8")))
            self._checkpoint.save(request.run_id, "RUNNING", {**state, "evidence": evidence}, {"event": "node_completed", "node": "resource_curation"})
            return {"evidence": evidence}
        except Exception as exc:
            self._record_node(request, "resource_curation", started, "FAILED", java_requests=1, payload_bytes=len(json.dumps(payload, ensure_ascii=False).encode("utf-8")), error=type(exc).__name__)
            raise

    def _writer(self, state: HandoutRunState, stage_code: str, audience: str, instruction: str) -> dict[str, Any]:
        request = state["request"]
        started = time.monotonic()
        resumed = next((item for item in state.get("writers", []) if item.stage_code == stage_code), None)
        if resumed is not None:
            self._record_node(request, stage_code, started, "RESUMED")
            return {"writers": []}
        evidence = state.get("evidence", EvidenceSnapshot())
        prompt = self._writer_prompt(request, evidence, stage_code, audience, instruction)
        provider_calls = 0
        try:
            document, usage, provider, model = self._invoke_json_model(request, stage_code, prompt)
            provider_calls = 1
            try:
                # This is the cheap and deterministic path: normalize wrapper shapes and validate semantics before
                # spending another provider call. A valid but differently shaped lectureCards array is not an error.
                document = self._normalize_writer_payload(document, stage_code, request.question_text)
                repair_reason = ""
            except (ValidationError, ValueError) as exc:
                repair_reason = str(exc)
                if DEFAULT_REPAIR_ATTEMPTS <= 0:
                    raise
                # The model is a last resort only for a semantically invalid response. The repair prompt contains the
                # failed fields and the submitted questions, not the full evidence bundle, so repair cost stays bounded.
                repaired_raw, repair_usage, repair_provider, repair_model = self._invoke_json_model(
                    request,
                    f"{stage_code}_repair",
                    self._repair_prompt(request, stage_code, [repair_reason]),
                )
                provider_calls += 1
                document = self._normalize_writer_payload(repaired_raw, stage_code, request.question_text)
                usage = _sum_usage(usage, repair_usage)
                provider = repair_provider
                model = repair_model
            self._record_node(request, stage_code, started, "SUCCESS", provider_calls=provider_calls, usage=usage,
                              provider=provider, model=model)
            self._checkpoint.save(request.run_id, "RUNNING", {**state, "writers": [document]}, {"event": "node_completed", "node": stage_code, "provider": provider, "model": model, "deterministicRepair": bool(repair_reason)})
            return {"writers": [document]}
        except Exception as exc:
            self._record_node(request, stage_code, started, "FAILED", provider_calls=max(1, provider_calls), error=type(exc).__name__)
            # Preserve the failing node in the durable event stream without persisting provider response bodies or
            # prompt text. This makes a later resume auditable when LangGraph wraps the original exception.
            detail = type(exc).__name__
            if isinstance(exc, ValueError) and not isinstance(exc, ValidationError):
                detail = f"{detail}:{str(exc)[:180]}"
            self._checkpoint.save(request.run_id, "RUNNING", state, {"event": "node_failed", "node": stage_code, "error": detail})
            raise

    def _teacher_writer(self, state: HandoutRunState) -> dict[str, Any]:
        return self._writer(state, "teacher_writer", "teacher", "写教师版讲义，保留逐题推导、答案、评分点和易错提醒。")

    def _student_writer(self, state: HandoutRunState) -> dict[str, Any]:
        return self._writer(state, "student_writer", "student", "写学生练习版，只给题目、提示和留白，不输出答案、评分点或教师内部分析。")

    def _lecture_writer(self, state: HandoutRunState) -> dict[str, Any]:
        return self._writer(state, "lecture_writer", "lecture", "写课堂投影版，按连续教学顺序组织知识点、例题和课堂追问。")

    @staticmethod
    def _normalize_writer_payload(raw: Any, stage_code: str, question_text: str) -> WriterDocument:
        """Normalizes provider variants and enforces non-empty, ordered question semantics in code."""
        payload: Any = raw
        if isinstance(payload, list):
            if stage_code == "lecture_writer":
                payload = {"lectureCards": payload}
            else:
                # Some providers return a list of paragraph/card objects for teacher and student drafts. Project
                # only their text fields into the stable contract; metadata must not become visible document text.
                projected: list[str] = []
                for item in payload:
                    if isinstance(item, str) and item.strip():
                        projected.append(item.strip())
                    elif isinstance(item, dict):
                        value = item.get("markdown", item.get("content", item.get("text", item.get("body", ""))))
                        if isinstance(value, list):
                            value = "\n".join(_text(part) for part in value if _text(part))
                        if _text(value):
                            projected.append(_text(value))
                payload = {"content": projected}
        if not isinstance(payload, dict):
            raise ValueError(f"{stage_code}: provider JSON root must be an object")
        supplied_stage = _text(payload.get("stageCode") or payload.get("stage_code"))
        if supplied_stage and supplied_stage != stage_code:
            raise ValueError(f"{stage_code}: stageCode mismatch: {supplied_stage}")
        markdown = _deterministic_markdown_cleanup(_structured_content(payload, stage_code), stage_code)
        title = _text(payload.get("title")) or _title_from_markdown(markdown) or STAGE_TITLES[stage_code]
        if len(markdown.strip()) < DEFAULT_MIN_DOCUMENT_CHARS:
            raise ValueError(f"{stage_code}: markdown is empty or too short")
        document = WriterDocument(stageCode=stage_code, title=title, markdown=markdown,
                                  citations=_string_list(payload.get("citations")), warnings=_string_list(payload.get("warnings")))
        HandoutRuntime._validate_document_semantics(document, stage_code, question_text)
        return document

    @staticmethod
    def _validate_document_semantics(document: WriterDocument, stage_code: str, question_text: str) -> None:
        """Rejects plausible-looking but empty, incomplete, reordered, or audience-inappropriate drafts."""
        markdown = document.markdown.strip()
        if not document.title.strip() or not markdown:
            raise ValueError(f"{stage_code}: title and markdown must be non-empty")
        questions = _submitted_questions(question_text)
        if not questions:
            raise ValueError("question batch is empty")
        cursor = 0
        for index, question in enumerate(questions, start=1):
            tokens = _question_tokens(question)
            if not tokens:
                raise ValueError(f"{stage_code}: question {index} has no distinctive semantic tokens")
            positions = [(markdown.find(token, cursor), token) for token in tokens]
            found = [(position, token) for position, token in positions if position >= 0]
            # Models may paraphrase a formula-rich stem, so requiring every extracted token would reject valid drafts.
            # Requiring one distinctive token after the previous question still proves ordered coverage while the
            # audience-specific and publication gates handle the stronger content/safety checks.
            if len(found) < DEFAULT_MIN_QUESTION_TOKEN_MATCHES:
                raise ValueError(f"{stage_code}: question {index} is missing or semantically unmatched")
            # Advance past the earliest matched token so a repeated generic symbol cannot satisfy every later stem.
            position, token = min(found)
            cursor = position + len(token)
        if stage_code == "lecture_writer":
            forbidden = [marker for marker in (*COMMON_FORBIDDEN_MARKERS, *LECTURE_FORBIDDEN_MARKERS) if marker in markdown]
            if re.search(r"(?m)^\s*([-*_]){3,}\s*$", markdown) or re.search(r"_{2,}", markdown):
                forbidden.append("horizontal_or_fill_line")
            if forbidden:
                raise ValueError(f"lecture_writer: forbidden projection content: {','.join(forbidden)}")
        else:
            forbidden = [marker for marker in COMMON_FORBIDDEN_MARKERS if marker in markdown]
            if forbidden:
                raise ValueError(f"{stage_code}: forbidden internal or asset content: {','.join(forbidden)}")
        if stage_code == "student_writer":
            leaked = [marker for marker in ANSWER_LEAK_MARKERS if marker in markdown]
            if leaked:
                raise ValueError(f"student_writer: answer leakage: {','.join(leaked)}")

    def _structured_validation(self, state: HandoutRunState) -> dict[str, Any]:
        request = state["request"]
        started = time.monotonic()
        writers = state.get("writers", [])
        documents = {document.stage_code: document for document in writers}
        required = ("teacher_writer", "student_writer", "lecture_writer")
        errors = [f"missing:{stage}" for stage in required if stage not in documents]
        observed_codes = [document.stage_code for document in writers]
        duplicate_codes = sorted({code for code in observed_codes if observed_codes.count(code) > 1})
        errors.extend(f"duplicate:{code}" for code in duplicate_codes)
        for stage in required:
            document = documents.get(stage)
            if document is None:
                continue
            try:
                self._validate_document_semantics(document, stage, request.question_text)
            except ValueError as exc:
                errors.append(str(exc))
        # A second model call here used to turn an incomplete package into a superficially non-empty package. Writer
        # nodes already get one bounded model fallback after deterministic normalization; this final gate only rejects.
        self._record_node(request, "structured_validation", started, "SUCCESS" if not errors else "FAILED", error=";".join(errors)[:500] if errors else None)
        report = ValidationReport(valid=not errors, repaired=False, errors=errors)
        metrics = HandoutMetrics(started_at=_utc_now())
        package = HandoutDraftPackage(run_id=request.run_id, task_id=request.task_id, contract_version=request.contract_version, graph_version=request.graph_version, status="COMPLETED" if report.valid else "FAILED", evidence=state.get("evidence", EvidenceSnapshot()), documents=documents, validation=report, metrics=metrics)
        self._checkpoint.save(request.run_id, package.status, {**state, "package": package}, {"event": "validated", "valid": report.valid, "errors": errors})
        return {"package": package}

    def _java_context(self, payload: dict[str, Any]) -> dict[str, Any]:
        base_url = os.getenv("MATH_AGENT_TOOL_BROKER_BASE_URL", "http://backend:8080").rstrip("/")
        worker_key = os.getenv("MATH_AGENT_AGENT_WORKER_SHARED_KEY", "")
        if not worker_key:
            raise HTTPException(status_code=503, detail="MATH_AGENT_AGENT_WORKER_SHARED_KEY is required")
        timeout = float(os.getenv("MATH_AGENT_TOOL_BROKER_TIMEOUT_SECONDS", "30"))
        response = self._session.post(f"{base_url}/internal/agent-tools/v1/handout-context", headers={"X-Agent-Worker-Key": worker_key}, json=payload, timeout=timeout)
        response.raise_for_status()
        decoded = response.json()
        return {"query": decoded.get("query", payload.get("query", "")), "items": decoded.get("items", decoded.get("hits", [])), "source": "java-broker"}

    def _invoke_json_model(self, request: HandoutRunRequest, node: str, prompt: str) -> tuple[Any, dict[str, int | float], str, str]:
        providers = [item.strip().lower() for item in os.getenv("MATH_AGENT_AI_RUNTIME_PROVIDER_ORDER", os.getenv("MATH_AGENT_AI_RUNTIME_PROVIDER", "openai")).split(",") if item.strip()]
        failures: list[str] = []
        provider_attempts = max(1, int(os.getenv("MATH_AGENT_HANDOUT_MODEL_ATTEMPTS", "2")))
        if provider_attempts >= PROVIDER_ATTEMPT_SLOT_SIZE:
            raise RuntimeError("MATH_AGENT_HANDOUT_MODEL_ATTEMPTS exceeds the durable attempt slot size")
        # The fixed node slot makes duplicate redelivery idempotent while preserving a separate immutable row for
        # every writer and retry.  Unknown internal nodes are deliberately placed after the named writer slots.
        attempt_number = PROVIDER_ATTEMPT_BASES.get(node, PROVIDER_ATTEMPT_SLOT_SIZE * 6)
        for provider in providers:
            key, base_url, model = self._provider_config(provider)
            if not key or not base_url:
                failures.append(f"{provider}:configuration")
                continue
            for provider_try in range(provider_attempts):
                self._check_deadline(request)
                attempt_number += 1
                messages = [
                    {"role": "system", "content": "你是受控的高中数学讲义编排节点。只输出合法 JSON，不要 Markdown 代码围栏，不要输出路径、权限、数据库或运行时说明。"},
                    {"role": "user", "content": prompt},
                ]
                max_output_tokens = max(1, int(os.getenv("MATH_AGENT_HANDOUT_MAX_OUTPUT_TOKENS", "5000")))
                with self._telemetry_lock:
                    telemetry = self._telemetry_by_run.get(request.run_id)
                if telemetry is None:
                    raise RuntimeError("handout telemetry is unavailable for provider budget reservation")
                prompt_estimate = fallback_tokens(messages, "")[0]
                telemetry.reserve_provider_call(prompt_estimate, max_output_tokens)
                payload = {"model": model, "messages": messages, "temperature": float(os.getenv("MATH_AGENT_HANDOUT_TEMPERATURE", "0.2")), "max_tokens": max_output_tokens}
                try:
                    configured_timeout = float(os.getenv("MATH_AGENT_HANDOUT_MODEL_TIMEOUT_SECONDS", str(DEFAULT_NODE_TIMEOUT_SECONDS)))
                    timeout = configured_timeout
                    if request.deadline_epoch_ms is not None:
                        remaining = (request.deadline_epoch_ms - int(time.time() * 1000)) / 1000.0
                        if remaining <= 0:
                            raise RuntimeError("handout deadline exceeded before provider request")
                        timeout = min(configured_timeout, remaining)
                    response = self._session.post(f"{base_url}/chat/completions", headers={"Authorization": f"Bearer {key}"}, json=payload, timeout=max(0.1, timeout))
                    response.raise_for_status()
                    data = response.json()
                    content = str((data.get("choices") or [])[0].get("message", {}).get("content") or "")
                    raw_usage = data.get("usage") or {}
                    prompt_tokens = int(raw_usage.get("prompt_tokens", 0) or 0)
                    # OpenAI-compatible providers put cache hits in either prompt_tokens_details or input_tokens_details.
                    cached_details = raw_usage.get("prompt_tokens_details") or raw_usage.get("input_tokens_details") or {}
                    cached_prompt_tokens = int(cached_details.get("cached_tokens", 0) or 0) if isinstance(cached_details, dict) else 0
                    completion_tokens = int(raw_usage.get("completion_tokens", 0) or 0)
                    total_tokens = int(raw_usage.get("total_tokens", 0) or 0)
                    source = "provider"
                    if total_tokens <= 0:
                        prompt_tokens, completion_tokens, total_tokens = fallback_tokens(messages, content)
                        source = "fallback"
                    price = cost_for(provider, model, prompt_tokens, completion_tokens)
                    UsageLedger().append(UsageEvent(
                        request.run_id, provider, model, attempt_number, "SUCCESS", prompt_tokens,
                        completion_tokens, total_tokens, price, source, cached_prompt_tokens=cached_prompt_tokens,
                    ))
                    return self._parse_json(content), {"promptTokens": prompt_tokens, "completionTokens": completion_tokens, "totalTokens": total_tokens, "estimatedCost": price}, provider, model
                except requests.HTTPError as exc:
                    # Keep only status and a bounded provider code in diagnostics; response bodies may contain prompt text.
                    status = exc.response.status_code if exc.response is not None else 0
                    provider_code = ""
                    if exc.response is not None:
                        try:
                            provider_code = str((exc.response.json() or {}).get("error", {}).get("code", ""))[:80]
                        except ValueError:
                            provider_code = ""
                    error_code = f"HTTP_{status}" + (f"_{provider_code}" if provider_code else "")
                    failures.append(f"{provider}:{error_code}")
                    UsageLedger().append(UsageEvent(request.run_id, provider, model, attempt_number, "FAILED", 0, 0, 0, 0.0, "unavailable", error_code))
                    if status < 500 or provider_try + 1 >= provider_attempts:
                        break
                except (requests.RequestException, ValueError, KeyError, IndexError, json.JSONDecodeError) as exc:
                    failures.append(f"{provider}:{type(exc).__name__}")
                    UsageLedger().append(UsageEvent(request.run_id, provider, model, attempt_number, "FAILED", 0, 0, 0, 0.0, "unavailable", type(exc).__name__))
                    if provider_try + 1 < provider_attempts:
                        time.sleep(float(os.getenv("MATH_AGENT_HANDOUT_RETRY_BACKOFF_SECONDS", "1.0")) * (provider_try + 1))
                    else:
                        break
        raise HTTPException(status_code=503, detail="Handout model call failed: " + ",".join(failures))

    @staticmethod
    def _check_deadline(request: HandoutRunRequest) -> None:
        """Stops work before the Java lease deadline so RabbitMQ can safely reclaim and resume the run."""
        if request.deadline_epoch_ms is not None and int(time.time() * 1000) >= request.deadline_epoch_ms:
            # Java maps this stable code to a terminal worker event. The textual message remains operator-readable,
            # while the code prevents timeout accounting from being confused with provider or checkpoint failures.
            raise HTTPException(
                status_code=504,
                detail={"code": "MODEL_TIMEOUT", "message": "Handout graph deadline exceeded"},
            )

    @staticmethod
    def _parse_json(content: str) -> Any:
        """Extracts one JSON value from provider wrappers without asking the model to reformat valid JSON."""
        cleaned = (content or "").lstrip("\ufeff").strip()
        if cleaned.startswith("```"):
            cleaned = cleaned.split("\n", 1)[-1].rsplit("```", 1)[0].strip()
        decoder = json.JSONDecoder()
        # Providers sometimes place a citations array before the document object. Prefer an object root so that an
        # incidental array is never mistaken for the whole WriterDocument; only fall back to an array when no object
        # can be decoded (needed for providers that legitimately return lecture cards as a bare list).
        for opening in ("{", "["):
            candidate_positions = [index for index, character in enumerate(cleaned) if character == opening]
            for position in candidate_positions:
                try:
                    value, _ = decoder.raw_decode(cleaned[position:])
                    if isinstance(value, dict if opening == "{" else list):
                        return value
                except json.JSONDecodeError:
                    continue
        raise ValueError("model response contains no valid JSON object or array")

    @staticmethod
    def _provider_config(provider: str) -> tuple[str | None, str, str]:
        keys = {"openai": "OPENAI_API_KEY", "dashscope": "DASHSCOPE_API_KEY", "deepseek": "DEEPSEEK_API_KEY", "ark": "ARK_API_KEY"}
        bases = {"openai": os.getenv("OPENAI_BASE_URL", "https://api1.aisz.mom/v1"), "dashscope": "https://dashscope.aliyuncs.com/compatible-mode/v1", "deepseek": "https://api.deepseek.com/v1", "ark": "https://ark.cn-beijing.volces.com/api/v3"}
        key = os.getenv(keys.get(provider, ""))
        base = os.getenv(f"{provider.upper()}_BASE_URL", bases.get(provider, "")).rstrip("/")
        model = os.getenv(f"MATH_AGENT_AI_RUNTIME_{provider.upper()}_MODEL", os.getenv("MATH_AGENT_AI_RUNTIME_MODEL", os.getenv("OPENAI_CHAT_MODEL", "gpt-5.6-luna")))
        return key, base, model

    @staticmethod
    def _writer_prompt(request: HandoutRunRequest, evidence: EvidenceSnapshot, stage: str, audience: str, instruction: str) -> str:
        projection_rules = {
            "preserveAllSubmittedQuestionsInOrder": True,
            "preserveAllKnowledgePoints": True,
            "lecture_writer": {
                "noHorizontalRules": True,
                "noFillInLines": True,
                "blankSpaceOnly": True,
                "noResourceOrEvidenceCards": True,
                "noPageScreenshotAssets": True,
            },
        }
        return json.dumps({"stageCode": stage, "audience": audience, "instruction": instruction,
                           "writingGoal": request.writing_goal, "questionText": request.question_text,
                           "evidence": evidence.prompt_text(), "projectionRules": projection_rules,
                           "outputContract": {"stageCode": stage, "title": "string", "markdown": "完整中文讲义内容",
                                               "citations": ["evidence ref"], "warnings": []}}, ensure_ascii=False)

    @staticmethod
    def _repair_prompt(request: HandoutRunRequest, stage_code: str, errors: list[str]) -> str:
        return json.dumps({"instruction": "修复当前讲义节点的结构或语义问题，只输出一个 WriterDocument JSON，不要数组。", "stageCode": stage_code, "errors": errors, "questionText": request.question_text, "outputContract": {"stageCode": stage_code, "title": "string", "markdown": "non-empty", "citations": [], "warnings": []}}, ensure_ascii=False)

    def _record_node(self, request: HandoutRunRequest, node: str, started: float, status: str, provider_calls: int = 0, java_requests: int = 0, payload_bytes: int = 0, usage: dict[str, int | float] | None = None, error: str | None = None, provider: str = "", model: str = "") -> None:
        """Node records are emitted through the event sink while preserving bounded operational metadata."""
        metric = NodeMetric(node=node, status=status, provider=provider, model=model,
                            elapsed_ms=int((time.monotonic() - started) * 1000), provider_calls=provider_calls,
                            java_requests=java_requests, payload_bytes=payload_bytes,
                            prompt_tokens=int((usage or {}).get("promptTokens", 0)),
                            completion_tokens=int((usage or {}).get("completionTokens", 0)),
                            total_tokens=int((usage or {}).get("totalTokens", 0)),
                            estimated_cost=float((usage or {}).get("estimatedCost", 0.0)), error=error)
        with self._telemetry_lock:
            telemetry = self._telemetry_by_run.get(request.run_id)
        if telemetry is not None:
            telemetry.record(metric)
        # Keep a compact immutable usage record as well.  The final package carries the aggregate metrics, while this
        # row lets the acceptance report distinguish a failed provider attempt from a failed graph node.
        # Runtime node rows share the provider-attempt table with model calls. A stable per-node attempt namespace
        # keeps all five node records distinct while making a RabbitMQ replay idempotent for the same run/node pair.
        attempt = RUNTIME_USAGE_ATTEMPTS.get(node, RUNTIME_USAGE_FALLBACK_ATTEMPT)
        UsageLedger().append(UsageEvent(request.run_id, "runtime", node, attempt, status, metric.prompt_tokens, metric.completion_tokens, metric.total_tokens, metric.estimated_cost, "runtime", error))
