"""LangGraph runtime for the protected teaching-handout workflow.

The graph owns AI work only. Java remains the authority for identity, evidence
visibility, assets, business persistence and PDF publication.  Checkpoints and
events are written before a node returns so a process restart can resume from a
durable boundary without replaying completed model calls.
"""

from __future__ import annotations

from contextlib import closing, contextmanager
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
import hashlib
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
from pydantic import AliasChoices, BaseModel, ConfigDict, Field, StrictBool, ValidationError, field_validator, model_validator

from app.ai_run_runtime import ProviderRoute
from app import anthropic_compat
from app.model_review_runtime import ModelReviewMetadata
from app.sse import iter_sse_data_events
from app.usage import HandoutMetricsLedger, UsageEvent, UsageLedger, cost_for, fallback_tokens


DEFAULT_GRAPH_VERSION = "handout-v2"
DEFAULT_CONTRACT_VERSION = "handout-ai-v2"
DEFAULT_CONTEXT_LIMIT = 12
# Initial context is deliberately smaller than the evidence retained across collection rounds. New authorized hits
# must remain available to the planner instead of being discarded merely because Java supplied twelve initial hits.
DEFAULT_COLLECTION_EVIDENCE_CAPACITY = 24
DEFAULT_COLLECTION_DECISION_LIMIT = 3
DEFAULT_DOCUMENT_INSPECTION_LIMIT = 3
DEFAULT_DOCUMENT_READ_BLOCKS = 80
DEFAULT_DOCUMENT_READ_CHARS = 24_000
DEFAULT_BROKER_TIMEOUT_SECONDS = 120.0
MAX_BROKER_TIMEOUT_SECONDS = 300.0
DEFAULT_RECOMMENDED_QUESTION_COUNT = 6
DEFAULT_PLAN_REVISION_LIMIT = 2
DEFAULT_BLUEPRINT_REVISION_LIMIT = 2
DEFAULT_NODE_TIMEOUT_SECONDS = 300.0
DEFAULT_MODEL_TIMEOUT_SECONDS = 75.0
DEFAULT_MODEL_REPAIR_TIMEOUT_SECONDS = 45.0
DEFAULT_MODEL_REPAIR_ATTEMPTS = 1
# A contract turn opens exactly one provider request. Deterministic validation may schedule one repair turn,
# but transport failures are reported rather than silently multiplying paid generation calls.
DEFAULT_MODEL_RETRY_ATTEMPTS = 1
DEFAULT_MODEL_REPAIR_RESERVE_SECONDS = 45.0
DEFAULT_CURATION_MODEL_RESERVE_SECONDS = 90.0


class HandoutOutputContractError(ValueError):
    """Signals that deterministic validation still fails after the single permitted repair."""


class ModelResponseParseError(ValueError):
    """Returns a received-but-invalid provider response to the deterministic repair path without transport retries."""


# One bounded original document may be supplied after RAG authorization; leave room for its full inspected content.
DEFAULT_MAX_EVIDENCE_CHARS = 64_000
DEFAULT_MAX_INSPECTED_SOURCE_CHARS = 64_000
DEFAULT_MAX_OUTPUT_CHARS = 24000
DEFAULT_MIN_DOCUMENT_CHARS = 32
DEFAULT_MIN_QUESTION_TOKEN_MATCHES = 1
# Reserve three times the former run envelope so a large structured completion can finish without starving later
# graph nodes. The reservation remains a hard cap across concurrent writer nodes.
DEFAULT_HANDOUT_MAX_TOTAL_TOKENS = 1_200_000
DEFAULT_HANDOUT_MAX_PROVIDER_CALLS = 16
# DeepSeek-compatible routes can account for internal reasoning inside completion_tokens even when JSON mode is
# requested. Thirty-two thousand tokens leaves room for that accounting plus a complete visible writer contract.
DEFAULT_HANDOUT_MAX_OUTPUT_TOKENS = 32_000
# Resource collection returns bounded broker decisions rather than lesson prose, but provider reasoning can still
# consume a short completion. The operational 4,800 default prevents a 1,200-token JSON truncation without retries.
DEFAULT_COLLECTION_DECISION_MAX_OUTPUT_TOKENS = 4_800
# A provider-reported length stop may increase a future request once; retain a hard ceiling below the run reservation.
MAX_ADAPTIVE_COMPLETION_TOKENS = 128_000
DEFAULT_EVENT_PAGE_LIMIT = 100
MAX_EVENT_PAGE_LIMIT = 500
MAX_EVENT_HISTORY = 10000
# Provider deltas stay in memory between bounded private checkpoint flushes. The thresholds preserve a recoverable
# prefix while avoiding one MySQL transaction and full JSON rewrite for every streamed token.
DEFAULT_STREAM_CHECKPOINT_FLUSH_MS = 250
DEFAULT_STREAM_CHECKPOINT_FLUSH_BYTES = 8 * 1024
DEFAULT_STREAM_CHECKPOINT_FLUSH_CHUNKS = 32
DEFAULT_STREAM_CHECKPOINT_HARD_BYTES = 32 * 1024
# Writer nodes produce all reader-visible handout text, including headings.  This belongs in the generation contract
# so a valid formula is emitted once instead of relying on an ever-growing list of renderer-specific repairs.
HANDOUT_MATH_MARKUP_CONTRACT = {
    "allVisibleFields": ["title", "markdown", "lectureCards[].title", "lectureCards[].content"],
    "rule": "任何变量、函数、集合、区间、方程、不等式、分式、根式、角度或运算式必须完整放入 $...$ 或 $$...$$。标题同样适用，例如“函数 $f(x)$ 的定义域”，不得出现裸露 f(x)。",
    "fraction": "分式必须写作 \\frac{分子}{分母}，不得使用 / 或 ／替代。",
    "radical": "根式必须写作 \\sqrt{被开方整体}，不得使用 √ 或省略花括号。",
    "integrity": "一个数学表达式不得跨出其 LaTeX 定界符；不得用裸露 ^、Unicode 上标或数学符号替代 LaTeX 结构。",
}
# The lock covers the full graph rather than an individual checkpoint write.  A second replica waits for the
# first replica's durable result, then returns it without opening another provider socket for the same run.
DEFAULT_RUN_LOCK_WAIT_SECONDS = 900
QUESTION_MARKER_PATTERN = re.compile(r"(?ms)(?:^|\n)【题目\s*(\d+)】\s*\n?(.*?)(?=\n【题目\s*\d+】|\Z)")
QUESTION_TOKEN_PATTERN = re.compile(r"[A-Za-z]+(?:_[A-Za-z0-9]+)?(?:\([^)]*\))?|[-+]?\d+(?:\.\d+)?|[\u4e00-\u9fff]{2,}")
GENERIC_QUESTION_TOKENS = frozenset({"已知", "函数", "求", "在", "其中", "关于", "实数", "得到", "问题", "题目"})
LECTURE_FORBIDDEN_MARKERS = ("<wait>", "TEACHER_IMAGE", "/api/teacher/resources/assets/", "资料依据", "完整解答")
COMMON_FORBIDDEN_MARKERS = ("/api/teacher/resources/assets/", "TEACHER_IMAGE", "内部日志", "资源卡", "证据卡")
ANSWER_LEAK_MARKERS = ("答案：", "答案:", "参考答案", "最终答案", "完整解答", "评分点：", "评分标准：", "教师提示：")
TEACHER_REQUIRED_SECTION_MARKERS = ("题目", "解题过程", "最终答案", "评分点", "易错点")
UNSAFE_DOCUMENT_TRANSPORT_PATTERN = re.compile(
    r"(?i)(?:https?://|file://|data:[^\s]+;base64,|\\includegraphics\\b|<\s*(?:img|script|iframe)\\b)"
)
ESCAPED_OR_LIST_HEADING_PATTERN = re.compile(r"(?m)^\s*(?:\\#+\s+|[-*+]\s+#+\s+)")
DISPLAY_MATH_LINE_PATTERN = re.compile(r"^\s*\$\$(?!\$)(?P<formula>.+?)(?<!\$)\$\$\s*$")
UNESCAPED_DOLLAR_PATTERN = re.compile(r"(?<!\\)\$")
STAGE_TITLES = {
    "teacher_writer": "教师版讲义",
    "student_writer": "学生版讲义",
    "lecture_writer": "16:10 课堂投影",
}
# The usage table's attempt number is the idempotency key; reserve a non-provider range for deterministic node rows.
RUNTIME_USAGE_ATTEMPTS = {
    "resource_curation": 1001,
    "plan_writer": 1002,
    "teacher_resource_curation": 1007,
    "teacher_blueprint_writer": 1003,
    "student_writer": 1004,
    "lecture_writer": 1005,
    "structured_validation": 1006,
}
RUNTIME_USAGE_FALLBACK_ATTEMPT = 1099
# Provider attempts share one unique `(run_id, provider, model, attempt)` key. Each node has one initial
# generation and at most one deterministic repair generation, so the slot preserves idempotency without
# allocating token budget for the removed multi-round self-review protocol.
PROVIDER_ATTEMPT_SLOT_SIZE = 100
PROVIDER_ATTEMPT_BASES = {
    "plan_writer": 0,
    "teacher_blueprint_writer": PROVIDER_ATTEMPT_SLOT_SIZE,
    "student_writer": PROVIDER_ATTEMPT_SLOT_SIZE * 2,
    "lecture_writer": PROVIDER_ATTEMPT_SLOT_SIZE * 3,
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
    """Adds every logical model turn while preserving unknown provider pricing."""
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


def _nested_document_text(value: Any) -> str:
    """Recovers visible document text from provider-specific JSON wrappers without accepting metadata as lesson text.

    Providers occasionally wrap the contract's ``markdown`` field in a named audience/document object.  The writer
    nodes must preserve that real model output instead of discarding it and asking the model to write the same lesson
    again.  This intentionally follows only content-bearing names, never arbitrary object values such as ids, usage,
    tool records, or internal reasoning fields.
    """
    if isinstance(value, str):
        return value.strip()
    if isinstance(value, list):
        return "\n".join(item for entry in value if (item := _nested_document_text(entry))).strip()
    if not isinstance(value, dict):
        return ""
    content_fields = (
        "markdown", "content", "body", "text", "result", "document", "data", "output", "response", "handout",
        "teacherExplanation", "studentWorksheet", "studentHandout", "studentContent", "worksheet",
        "sections", "blocks", "paragraphs", "items",
    )
    for field in content_fields:
        if field not in value:
            continue
        content = _nested_document_text(value[field])
        if content:
            return content
    return ""


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
        else:
            content = _nested_document_text(value)
        if content.strip():
            return content.strip()
    # Some OpenAI-compatible relays place the entire WriterDocument under `data` or `document` instead of the
    # advertised audience field.  Reuse the allow-listed recursive extractor so the visible lesson survives while
    # stage, usage, and other metadata still cannot become printable content.
    wrapped_content = _nested_document_text(payload)
    if wrapped_content:
        return wrapped_content
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
    initial_evidence: list[dict[str, Any]] = Field(default_factory=list, alias="initialEvidence", max_length=24)
    graph_version: str = Field(default=DEFAULT_GRAPH_VERSION, alias="graphVersion", min_length=1, max_length=40)
    idempotency_key: str = Field(default="", alias="idempotencyKey", max_length=160)
    trace_id: str | None = Field(default=None, alias="traceId", max_length=120)
    traceparent: str | None = Field(default=None, max_length=160)
    # Java supplies this signed, bounded route in production; direct unit fixtures can omit it.
    provider_route: ProviderRoute | None = Field(default=None, alias="providerRoute")
    deadline_epoch_ms: int | None = Field(default=None, alias="deadlineEpochMs", ge=0)
    # Java selects a durable boundary. COMPLETE preserves the existing synchronous teaching-task call during rollout.
    operation: str = Field(default="COMPLETE", max_length=40)
    revision_feedback: list[str] = Field(default_factory=list, alias="revisionFeedback", max_length=12)
    resume: bool = False

    def compact(self) -> "HandoutRunRequest":
        """Keeps the payload bounded while preserving the question verbatim up to the API contract limit."""
        return self.model_copy(update={
            "writing_goal": _bounded(self.writing_goal, 1200),
            # Question markers are line-oriented; collapsing their newlines would silently merge a batch into one stem.
            "question_text": self.question_text.strip()[:16000],
            "evidence_refs": list(dict.fromkeys(_bounded(item, 240) for item in self.evidence_refs if item.strip()))[:24],
            "initial_evidence": [dict(item) for item in self.initial_evidence[:24] if isinstance(item, dict)],
            "graph_version": _bounded(self.graph_version, 40) or DEFAULT_GRAPH_VERSION,
            # Legacy callers used runId as the retry identity. Preserve that deterministic behavior during rollout.
            "idempotency_key": _bounded(self.idempotency_key, 160) or self.run_id,
            "contract_version": _bounded(self.contract_version, 40) or DEFAULT_CONTRACT_VERSION,
            "operation": _bounded(self.operation, 40).upper() or "COMPLETE",
            "revision_feedback": _string_list(self.revision_feedback, 12),
        })


class EvidenceItem(BaseModel):
    """Compact permission-filtered evidence; source images remain ordinary Markdown references."""

    model_config = ConfigDict(populate_by_name=True, extra="ignore")

    ref: str = Field(default="", max_length=240)
    transparent_ref: str = Field(default="", alias="transparentRef", max_length=700)
    title: str = Field(default="", max_length=600)
    document_name: str = Field(default="", alias="documentName", max_length=600)
    document_ref: str = Field(default="", alias="documentRef", max_length=240)
    page_no: int = Field(default=0, alias="pageNo", ge=0)
    excerpt: str = Field(default="", max_length=12000)
    source_relative_path: str = Field(default="", alias="sourceRelativePath", max_length=1200)
    markdown: str = Field(default="", max_length=12000)
    image_refs: list[dict[str, str]] = Field(default_factory=list, alias="imageRefs", max_length=50)

    @field_validator("image_refs", mode="before")
    @classmethod
    def validate_image_refs(cls, value: Any) -> list[dict[str, str]]:
        if not isinstance(value, list):
            return []
        result: list[dict[str, str]] = []
        for item in value[:50]:
            if not isinstance(item, dict):
                continue
            line = str(item.get("markdownLine") or "")
            logical = str(item.get("logicalPath") or "")
            if line and logical and "http://" not in line and "https://" not in line and ".." not in logical:
                result.append({"markdownLine": line[:12000], "logicalPath": logical[:1200]})
        return result


class PlannedQuestion(BaseModel):
    """A concise, reviewable teaching decision for one question; it never contains hidden reasoning or source text."""

    number: int = Field(ge=1, le=100)
    question: str = Field(min_length=1, max_length=4000)
    evidence_refs: list[str] = Field(default_factory=list, alias="evidenceRefs", max_length=12)
    knowledge_point: str = Field(default="", alias="knowledgePoint", max_length=600)
    teaching_sequence: list[str] = Field(default_factory=list, alias="teachingSequence", min_length=1, max_length=8)
    figure_required: bool = Field(default=False, alias="figureRequired")


class WritingPlan(BaseModel):
    """Visible staged plan: instructional decisions only, never private model deliberation or raw evidence."""

    learning_objective: str = Field(alias="learningObjective", min_length=1, max_length=1200)
    questions: list[PlannedQuestion] = Field(min_length=1, max_length=24)
    completion_criteria: list[str] = Field(alias="completionCriteria", min_length=1, max_length=12)
    ready_for_next_stage: bool = Field(alias="readyForNextStage")
    revision_round: int = Field(default=0, alias="revisionRound", ge=0, le=DEFAULT_PLAN_REVISION_LIMIT)
    warnings: list[str] = Field(default_factory=list, max_length=24)
    # Deprecated compatibility field: v1/v2 checkpoints may deserialize it, but collection is now complete before
    # planning and no graph route or runtime behavior reads these values.
    teacher_resource_queries: list[str] = Field(
        default_factory=list, alias="teacherResourceQueries", max_length=4)


class TeacherBlueprint(BaseModel):
    """Reviewable teacher source from which student and lecture variants may be safely derived."""

    model_config = ConfigDict(populate_by_name=True, extra="forbid")

    title: str = Field(min_length=1, max_length=600)
    markdown: str = Field(min_length=DEFAULT_MIN_DOCUMENT_CHARS, max_length=DEFAULT_MAX_OUTPUT_CHARS)
    citations: list[str] = Field(default_factory=list, max_length=24)
    # A preserved source-image Markdown row in markdown is the only image placement signal.
    lecture_cards: list[dict[str, Any]] | dict[str, list[dict[str, Any]]] = Field(
        default_factory=list,
        alias="lectureCards",
    )

    @field_validator("lecture_cards", mode="before")
    @classmethod
    def validate_opaque_lecture_cards(cls, value: Any) -> Any:
        """Accepts only the two writer contract containers without inspecting teaching-card content."""
        cards = value if isinstance(value, list) else value.get("cards") if isinstance(value, dict) else None
        if not isinstance(cards, list) or len(cards) > 48:
            raise ValueError("lectureCards must be a bounded card list or cards container")
        if isinstance(value, dict) and set(value) != {"cards"}:
            raise ValueError("lectureCards container may contain only cards")
        return value
    completion_checklist: list[str] = Field(alias="completionChecklist", min_length=1, max_length=12)
    remaining_edits: list[str] = Field(default_factory=list, alias="remainingEdits", max_length=12)
    # DeepSeek Flash can emit the semantically equivalent derivationReady key. Missing readiness remains invalid and
    # is repaired by the same DeepSeek route; it must never be inferred from otherwise valid lesson text.
    # StrictBool prevents strings such as "true" from becoming an approval declaration through Pydantic coercion.
    ready_for_derivation: StrictBool | None = Field(
        default=None,
        validation_alias=AliasChoices("readyForDerivation", "derivationReady"),
        serialization_alias="readyForDerivation",
    )
    revision_round: int = Field(default=0, alias="revisionRound", ge=0, le=DEFAULT_BLUEPRINT_REVISION_LIMIT)

    @model_validator(mode="before")
    @classmethod
    def normalize_duplicate_derivation_readiness(cls, value: Any) -> Any:
        """Accept provider responses that state the same readiness decision under both supported names."""
        if not isinstance(value, dict) or "readyForDerivation" not in value or "derivationReady" not in value:
            return value
        if value["readyForDerivation"] != value["derivationReady"]:
            raise ValueError("conflicting teacher blueprint readiness declarations")
        normalized = dict(value)
        normalized.pop("derivationReady")
        return normalized


class EvidenceSnapshot(BaseModel):
    """Java-authorized RAG matches plus bounded original-source content shared by all writer nodes."""

    query: str = ""
    items: list[EvidenceItem] = Field(default_factory=list, max_length=DEFAULT_COLLECTION_EVIDENCE_CAPACITY)
    inspected_items: list[EvidenceItem] = Field(default_factory=list, alias="inspectedItems", max_length=DEFAULT_DOCUMENT_INSPECTION_LIMIT * DEFAULT_DOCUMENT_READ_BLOCKS)
    source: str = "java-broker"

    def prompt_text(self) -> str:
        rows: list[str] = []
        used = 0
        # 当前命中与原文阅读块分别序列化，保证模型能够辨认二者的授权用途。
        for kind, source, limit in (
            ("retrieved_hit", self.items[:DEFAULT_CONTEXT_LIMIT], DEFAULT_MAX_EVIDENCE_CHARS),
            ("inspected_source", self.inspected_items, DEFAULT_MAX_INSPECTED_SOURCE_CHARS),
        ):
            for item in source:
                row = json.dumps({"kind": kind, **item.model_dump(by_alias=True, exclude_none=True)}, ensure_ascii=False, separators=(",", ":"))
                separator = 1 if rows else 0
                if used + separator + len(row) > limit:
                    continue
                rows.append(row)
                used += separator + len(row)
        return "\n".join(rows)


class ResourceCollectionAction(BaseModel):
    """A private decision can only read already-authorized documents; retrieval is owned by the prior curation step."""
    model_config = ConfigDict(populate_by_name=True, extra="forbid")

    kind: str = Field(min_length=1, max_length=40)
    document_ref: str = Field(default="", alias="documentRef", max_length=240)
    query: str = Field(default="", max_length=160)
    page_no: int = Field(default=0, alias="pageNo", ge=0)
    page_radius: int = Field(default=0, alias="pageRadius", ge=0, le=4)

    @model_validator(mode="after")
    def validate_broker_scope(self) -> "ResourceCollectionAction":
        """Allows only sequential reads against opaque run-authorized document references."""
        if self.kind not in {"document_read", "document_page_read", "canonical_question_read", "teacher_resource_search"}:
            raise ValueError("unsupported collection action")
        if self.kind in {"document_read", "document_page_read", "canonical_question_read"} and not self.document_ref:
            raise ValueError("document action requires documentRef")
        if self.kind == "teacher_resource_search" and not self.query.strip():
            raise ValueError("search action requires query")
        if self.kind in {"document_read", "canonical_question_read"} and (self.query or self.page_no or self.page_radius):
            raise ValueError(f"{self.kind} cannot include query or page selection")
        if self.kind == "document_page_read":
            if self.query or self.page_no <= 0:
                raise ValueError("document_page_read requires pageNo and cannot include query")
        elif self.page_no or self.page_radius:
            raise ValueError("only document_page_read can include page selection")
        return self


class ResourceCollectionDecision(BaseModel):
    """Private ReAct decision that controls only the next bounded authorized collection action."""

    model_config = ConfigDict(populate_by_name=True, extra="forbid")

    sufficient: StrictBool
    actions: list[ResourceCollectionAction] = Field(default_factory=list, max_length=4)
    source_to_gap_assessment: str = Field(alias="sourceToGapAssessment", min_length=1, max_length=2400)


class WriterDocument(BaseModel):
    """Validated audience-specific document returned by a Writer node."""

    model_config = ConfigDict(populate_by_name=True, extra="ignore")

    stage_code: str = Field(alias="stageCode", min_length=1, max_length=40)
    title: str = Field(min_length=1, max_length=600)
    markdown: str = Field(min_length=1, max_length=DEFAULT_MAX_OUTPUT_CHARS)
    citations: list[str] = Field(default_factory=list, max_length=24)
    # A preserved source-image Markdown row in markdown is the only image placement signal.
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
    # Node timestamps are recorded separately from duration so DB aggregations can correlate graph work with
    # Java queue/lease and PDF timings without relying on container clocks for elapsed-time calculations.
    started_at: str | None = Field(default=None, alias="startedAt")
    finished_at: str | None = Field(default=None, alias="finishedAt")
    provider: str = ""
    model: str = ""
    elapsed_ms: int = Field(alias="elapsedMs")
    provider_calls: int = Field(default=0, alias="providerCalls")
    java_requests: int = Field(default=0, alias="javaRequests")
    payload_bytes: int = Field(default=0, alias="payloadBytes")
    prompt_tokens: int = Field(default=0, alias="promptTokens")
    cached_prompt_tokens: int = Field(default=0, alias="cachedPromptTokens")
    completion_tokens: int = Field(default=0, alias="completionTokens")
    total_tokens: int = Field(default=0, alias="totalTokens")
    # Negative one is the cross-language unknown-price sentinel. A zero is a real configured free price.
    estimated_cost: float = Field(default=-1.0, alias="estimatedCost")
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
    # Do not claim a cost until at least one billable provider attempt supplies a known price.
    estimated_cost: float = Field(default=-1.0, alias="estimatedCost")
    cost_known: bool = Field(default=False, alias="costKnown")
    system_load: list[dict[str, Any]] = Field(default_factory=list, alias="systemLoad")


class HandoutDraftPackage(BaseModel):
    """One result sent back to Java; Java still decides whether it can be published."""

    model_config = ConfigDict(populate_by_name=True)

    run_id: str = Field(alias="runId")
    task_id: str = Field(alias="taskId")
    contract_version: str = Field(default=DEFAULT_CONTRACT_VERSION, alias="contractVersion")
    graph_version: str = Field(alias="graphVersion")
    status: str
    phase: str = "COMPLETED"
    evidence: EvidenceSnapshot
    writing_plan: WritingPlan | None = Field(default=None, alias="writingPlan")
    teacher_blueprint: TeacherBlueprint | None = Field(default=None, alias="teacherBlueprint")
    documents: dict[str, WriterDocument] = Field(default_factory=dict)
    validation: ValidationReport
    metrics: HandoutMetrics


class HandoutRunState(TypedDict, total=False):
    """LangGraph state intentionally contains only bounded snapshots, never local paths."""

    request: HandoutRunRequest
    evidence: EvidenceSnapshot
    writing_plan: WritingPlan
    teacher_blueprint: TeacherBlueprint
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
        # Runtime-only nodes have no provider price. Keep their status telemetry out of the billable-cost decision.
        self._has_unknown_provider_cost = False

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
            # Only an actual provider attempt is billable. Java-context, validation and checkpoint nodes contribute
            # latency but must not turn a fully priced model run into an "unknown cost" report.
            if metric.provider_calls > 0:
                if metric.estimated_cost < 0:
                    self._has_unknown_provider_cost = True
                    self.metrics.cost_known = False
                    self.metrics.estimated_cost = -1.0
                elif not self._has_unknown_provider_cost:
                    if not self.metrics.cost_known:
                        self.metrics.estimated_cost = 0.0
                    self.metrics.cost_known = True
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
        # closing() 必须显式包裹：sqlite3 连接对象进 with 只提交事务、不关闭文件句柄，
        # Windows 上未关闭句柄会让临时目录清理报 WinError 32（测试顺序相关闪失的根因）。
        with closing(self._connect()) as conn:
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
    def run_lock(self, run_id: str, deadline_epoch_ms: int | None = None):
        """Claims one durable run lock without waiting past the caller's lease deadline."""
        deadline = None if deadline_epoch_ms is None else max(0.0, (deadline_epoch_ms - int(time.time() * 1000)) / 1000.0)
        if self.backend != "mysql":
            with self._sqlite_run_locks_guard:
                local_lock = self._sqlite_run_locks.setdefault(run_id, threading.Lock())
            acquired = local_lock.acquire(timeout=deadline) if deadline is not None else local_lock.acquire()
            if not acquired:
                raise HTTPException(status_code=504, detail={
                    "code": "MODEL_TIMEOUT",
                    "message": "Handout graph deadline exceeded while waiting for run ownership",
                })
            try:
                yield
            finally:
                local_lock.release()
            return
        wait_seconds = max(0, int(os.getenv(
            "MATH_AGENT_HANDOUT_RUN_LOCK_WAIT_SECONDS", str(DEFAULT_RUN_LOCK_WAIT_SECONDS))))
        if deadline is not None:
            wait_seconds = min(wait_seconds, max(0, int(deadline)))
        lock_name = self._mysql_lock_name(run_id)
        with self._mysql_connection() as conn:
            with conn.cursor() as cursor:
                cursor.execute("SELECT GET_LOCK(%s,%s)", (lock_name, wait_seconds))
                acquired = cursor.fetchone()
            if not acquired or int(acquired[0] or 0) != 1:
                if deadline_epoch_ms is not None and int(time.time() * 1000) >= deadline_epoch_ms:
                    raise HTTPException(status_code=504, detail={
                        "code": "MODEL_TIMEOUT",
                        "message": "Handout graph deadline exceeded while waiting for run ownership",
                    })
                raise HTTPException(status_code=409, detail="HANDOUT_RUN_LOCK_TIMEOUT")
            try:
                yield
            finally:
                with conn.cursor() as cursor:
                    cursor.execute("SELECT RELEASE_LOCK(%s)", (lock_name,))

    @staticmethod
    def _mysql_lock_name(run_id: str) -> str:
        """生成不含原始运行标识且满足 MySQL 64 字符限制的确定性锁名。"""
        # 使用固定命名空间隔离业务锁；截取摘要后仍保留 236 位碰撞空间，覆盖合法运行标识范围。
        return f"ma:h:{hashlib.sha256(run_id.encode('utf-8')).hexdigest()[:59]}"

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

    def save_private_state(self, run_id: str, state: dict[str, Any]) -> None:
        """Persists private AI turn material without creating an event-stream record."""
        now = _utc_now()
        if self.backend == "mysql":
            # MySQL row locking is the cross-process merge guard. Avoid the process-global Python mutex here so a
            # streaming flush for one run cannot block another run's terminal durability boundary.
            with self._mysql_connection() as conn:
                with conn.cursor() as cursor:
                    cursor.execute(
                        "INSERT INTO handout_checkpoint(run_id,status,state_json,updated_at) VALUES(%s,%s,%s,%s) "
                        "ON DUPLICATE KEY UPDATE run_id=VALUES(run_id)",
                        (run_id, "RUNNING", "{}", now),
                    )
                    cursor.execute("SELECT status,state_json FROM handout_checkpoint WHERE run_id=%s FOR UPDATE", (run_id,))
                    row = cursor.fetchone()
                    previous_state = json.loads(row[1]) if row and row[1] else {}
                    merged_state = self._merge_state(previous_state, _jsonable(state))
                    cursor.execute(
                        "UPDATE handout_checkpoint SET state_json=%s,updated_at=%s WHERE run_id=%s",
                        (json.dumps(merged_state, ensure_ascii=False, separators=(",", ":")), now, run_id),
                    )
                conn.commit()
            return
        with self._lock, closing(self._connect()) as conn:
            previous = conn.execute("SELECT state_json FROM handout_checkpoint WHERE run_id=?", (run_id,)).fetchone()
            previous_state = json.loads(previous["state_json"]) if previous is not None else {}
            merged_state = self._merge_state(previous_state, _jsonable(state))
            conn.execute(
                "INSERT INTO handout_checkpoint(run_id,status,state_json,updated_at) VALUES(?,?,?,?) "
                "ON CONFLICT(run_id) DO UPDATE SET state_json=excluded.state_json,updated_at=excluded.updated_at",
                (run_id, "RUNNING", json.dumps(merged_state, ensure_ascii=False, separators=(",", ":")), now),
            )
            conn.commit()

    def load_private_state(self, run_id: str) -> dict[str, Any]:
        """Returns private diagnostics for operator-only verification without adding an event-stream entry."""
        loaded = self.load(run_id)
        return loaded[1] if loaded is not None else {}

    @staticmethod
    def _merge_state(previous: dict[str, Any], incoming: dict[str, Any]) -> dict[str, Any]:
        """Merges sibling nodes and opaque worker-only diagnostics without public event leakage."""
        # Graph state uses snake_case, while the durable checkpoint has explicit camelCase artifact keys. Retaining
        # both lets a terminal package write resurrect stale graph values and obscures the one authoritative resume key.
        runtime_only_keys = {"writing_plan", "teacher_blueprint"}
        merged = {key: value for key, value in previous.items() if key not in runtime_only_keys}
        for key, value in incoming.items():
            if key not in {"writers", "evidence", "model_reviews", "privateDiagnostics", "modelTurnDiagnostics", *runtime_only_keys}:
                merged[key] = value
        if incoming.get("evidence") is not None:
            merged["evidence"] = incoming["evidence"]
        review_by_node = dict(previous.get("modelReviews") or previous.get("model_reviews") or {})
        review_by_node.update(incoming.get("modelReviews") or incoming.get("model_reviews") or {})
        if review_by_node:
            merged["modelReviews"] = review_by_node
        private_diagnostics = dict(previous.get("privateDiagnostics") or {})
        private_diagnostics.update(incoming.get("privateDiagnostics") or {})
        if private_diagnostics:
            merged["privateDiagnostics"] = private_diagnostics
        turn_diagnostics = dict(previous.get("modelTurnDiagnostics") or {})
        for diagnostic_id, update in (incoming.get("modelTurnDiagnostics") or {}).items():
            current = dict(turn_diagnostics.get(diagnostic_id) or {})
            current.update(update if isinstance(update, dict) else {})
            turn_diagnostics[diagnostic_id] = current
        if turn_diagnostics:
            merged["modelTurnDiagnostics"] = turn_diagnostics
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


class _StreamingCheckpointBuffer:
    """Batches private streamed output while retaining an exact, bounded recovery prefix."""

    def __init__(self, flush_callback):
        self._flush_callback = flush_callback
        self.parts: list[str] = []
        self.durable_chars = 0
        self.pending_chars = 0
        self.pending_bytes = 0
        self.received_chunks = 0
        self.flush_count = 0
        self.max_pending_chars = 0
        self.first_chunk_at: str | None = None
        self.last_chunk_at: str | None = None
        self.last_flushed_at: str | None = None
        self.started = time.monotonic()
        self.last_flush_monotonic = self.started
        self.flush_reasons: dict[str, int] = {}

    @property
    def content(self) -> str:
        return "".join(self.parts)

    def add(self, value: str) -> None:
        if not value:
            return
        now = _utc_now()
        if self.first_chunk_at is None:
            self.first_chunk_at = now
        self.last_chunk_at = now
        self.parts.append(value)
        self.pending_chars += len(value)
        self.pending_bytes += len(value.encode("utf-8"))
        self.received_chunks += 1
        self.max_pending_chars = max(self.max_pending_chars, self.pending_chars)

    def should_flush(self) -> bool:
        return bool(self.pending_chars) and (
            self.pending_bytes >= int(os.getenv("MATH_AGENT_HANDOUT_STREAM_FLUSH_BYTES", str(DEFAULT_STREAM_CHECKPOINT_FLUSH_BYTES)))
            or self.received_chunks >= int(os.getenv("MATH_AGENT_HANDOUT_STREAM_FLUSH_CHUNKS", str(DEFAULT_STREAM_CHECKPOINT_FLUSH_CHUNKS)))
            or self.pending_bytes >= int(os.getenv("MATH_AGENT_HANDOUT_STREAM_HARD_BYTES", str(DEFAULT_STREAM_CHECKPOINT_HARD_BYTES)))
            or (time.monotonic() - self.last_flush_monotonic) * 1000 >= float(os.getenv(
                "MATH_AGENT_HANDOUT_STREAM_FLUSH_MS", str(DEFAULT_STREAM_CHECKPOINT_FLUSH_MS)))
        )

    def flush(self, reason: str) -> None:
        if not self.pending_chars:
            return
        now = _utc_now()
        self.flush_count += 1
        self.flush_reasons[reason] = self.flush_reasons.get(reason, 0) + 1
        self._flush_callback({
            "partialContent": self.content,
            "partialChars": len(self.content),
            "durableChars": len(self.content),
            "receivedChunkCount": self.received_chunks,
            "persistedFlushCount": self.flush_count,
            "firstChunkAt": self.first_chunk_at,
            "lastChunkAt": self.last_chunk_at,
            "lastFlushedAt": now,
            "maxUnflushedChars": self.max_pending_chars,
            "flushReason": reason,
            "flushReasonCounts": dict(self.flush_reasons),
            "outcome": "STREAMING",
        })
        self.durable_chars = len(self.content)
        self.pending_chars = 0
        self.pending_bytes = 0
        self.last_flushed_at = now
        self.last_flush_monotonic = time.monotonic()
        self.received_chunks = 0


class HandoutRuntime:
    """Executes the complete graph with one Java context request and three parallel model writers."""

    def __init__(self) -> None:
        self._session = requests.Session()
        self._session.headers.update({"Content-Type": "application/json"})
        self._checkpoint = _CheckpointStore()
        self._telemetry_by_run: dict[str, _RunTelemetry] = {}
        self._telemetry_lock = threading.Lock()
        # Only an explicit provider length stop can create this short-lived, node-scoped next-call ceiling.
        # It is runtime state rather than a guessed token calculation and never changes retry cardinality.
        self._next_completion_ceilings: dict[tuple[str, str, str], int] = {}
        self._completion_ceiling_lock = threading.Lock()
        self._graph = self._build_graph()

    def _completion_ceiling(self, node: str, provider: str, model: str, configured: int) -> int:
        """Return the configured ceiling or one provider-authorized length-recovery ceiling."""
        key = (node, provider, model)
        if not hasattr(self, "_completion_ceiling_lock"):
            self._completion_ceiling_lock = threading.Lock()
            self._next_completion_ceilings = {}
        with self._completion_ceiling_lock:
            return self._next_completion_ceilings.pop(key, max(1, configured))

    def _record_length_ceiling(self, node: str, provider: str, model: str, requested: int) -> int:
        """Double only a real length-truncated request for the next same-route invocation."""
        ceiling = min(MAX_ADAPTIVE_COMPLETION_TOKENS, max(1, requested) * 2)
        if not hasattr(self, "_completion_ceiling_lock"):
            self._completion_ceiling_lock = threading.Lock()
            self._next_completion_ceilings = {}
        with self._completion_ceiling_lock:
            self._next_completion_ceilings[(node, provider, model)] = ceiling
        return ceiling

    @staticmethod
    def _is_checkpoint_store_unavailable(exc: BaseException) -> bool:
        """Recognizes only MySQL-driver failures raised by the durable checkpoint store."""
        try:
            import pymysql
        except ImportError:
            return False
        seen: set[int] = set()
        current: BaseException | None = exc
        while current is not None and id(current) not in seen:
            if isinstance(current, pymysql.MySQLError):
                return True
            seen.add(id(current))
            current = current.__cause__ or current.__context__
        return False

    @staticmethod
    def _checkpoint_unavailable_response() -> HTTPException:
        """Keeps a durable run retryable when its authoritative checkpoint store is offline."""
        return HTTPException(status_code=503, detail={
            "code": "HANDOUT_CHECKPOINT_UNAVAILABLE",
            "message": "Handout checkpoint storage is temporarily unavailable; the run can be resumed.",
        })

    def execute(self, request: HandoutRunRequest) -> HandoutDraftPackage:
        request = request.compact()
        # Java 的每次租约接管都保持同一 durable taskId/runId；先取得跨进程运行锁再读检查点，
        # 才能覆盖 Java 预检与 HTTP 派发之间的失租窗口，后到请求只能复用已完成包而不能再次调用 provider。
        try:
            with self._checkpoint.run_lock(request.run_id, request.deadline_epoch_ms):
                return self._execute_locked(request)
        except HTTPException:
            raise
        except Exception as exc:
            # A failed ownership/read/startup checkpoint operation has no safe secondary write path. Preserve the
            # last durable boundary and let Java redeliver this same run after the storage dependency recovers.
            if self._is_checkpoint_store_unavailable(exc):
                raise self._checkpoint_unavailable_response() from exc
            raise

    def _execute_locked(self, request: HandoutRunRequest) -> HandoutDraftPackage:
        """Runs one graph after the durable run-level ownership gate has been acquired."""
        self._check_deadline(request)
        telemetry = _RunTelemetry(request.run_id)
        with self._telemetry_lock:
            self._telemetry_by_run[request.run_id] = telemetry
        telemetry.sample_system()
        started_state: HandoutRunState = {"request": request}
        if request.initial_evidence:
            started_state["evidence"] = EvidenceSnapshot(items=[EvidenceItem.model_validate(item) for item in request.initial_evidence])
        existing = self._checkpoint.load(request.run_id) if request.resume else None
        if existing:
            saved_request = existing[1].get("request") if isinstance(existing[1], dict) else None
            if isinstance(saved_request, dict):
                saved_graph_version = str(saved_request.get("graphVersion", DEFAULT_GRAPH_VERSION))
                # Older checkpoints serialized the optional key as an empty string. Treat that legacy form as the
                # deterministic run-id key so a valid retry is resumed instead of triggering another provider call.
                saved_idempotency_key = str(saved_request.get("idempotencyKey") or request.run_id)
                if saved_graph_version != request.graph_version:
                    raise HTTPException(status_code=409, detail="GRAPH_VERSION_INCOMPATIBLE")
                if saved_idempotency_key != request.idempotency_key:
                    raise HTTPException(status_code=409, detail="IDEMPOTENCY_KEY_INCOMPATIBLE")
                saved_refs = saved_request.get("evidenceRefs") or []
                if list(saved_refs) != list(request.evidence_refs):
                    existing = None
        if existing and not request.initial_evidence and existing[0] == "COMPLETED" and existing[1].get("package"):
            return HandoutDraftPackage.model_validate(existing[1]["package"])
        # A v1 checkpoint can already contain all validated audience documents. Preserve that completed work during
        # the v2 rollout instead of reopening provider calls merely to recreate intermediate review artifacts.
        if existing and existing[1].get("writers") and not existing[1].get("writingPlan"):
            saved_writers = [WriterDocument.model_validate(item) for item in existing[1]["writers"]]
            saved_codes = {item.stage_code for item in saved_writers}
            if {"teacher_writer", "student_writer", "lecture_writer"}.issubset(saved_codes):
                started_state["writers"] = saved_writers
                for node in ("resource_curation", "plan_writer", "teacher_blueprint_writer", "teacher_writer", "student_writer", "lecture_writer"):
                    self._record_node(request, node, telemetry.started, "RESUMED")
                state = self._structured_validation(started_state)
                package = state["package"].model_copy(update={"metrics": telemetry.finish()})
                self._checkpoint.save(request.run_id, package.status, {**state, "package": package},
                                      {"event": "completed", "status": package.status, "legacyCheckpoint": True})
                return package
            # A partially executed v1 run cannot safely infer a visible plan from an old teacher document. Explicitly
            # reject it so Java can start the v2 staged artifact rather than silently spending model calls.
            raise HTTPException(status_code=409, detail="LEGACY_CHECKPOINT_REQUIRES_RESTART")
        if existing and existing[1].get("evidence") and not request.initial_evidence:
            started_state["evidence"] = EvidenceSnapshot.model_validate(existing[1]["evidence"])
        # Evidence-refresh recovery deliberately regenerates every downstream artifact. Reusing a plan, blueprint, or
        # writer document from the completed checkpoint would bind the refreshed source set to old visible content.
        reuse_existing_artifacts = bool(existing) and not request.initial_evidence
        if reuse_existing_artifacts and existing[1].get("writingPlan"):
            saved_plan = WritingPlan.model_validate(existing[1]["writingPlan"])
            if request.operation == "PLAN_REVISE":
                if saved_plan.revision_round >= DEFAULT_PLAN_REVISION_LIMIT:
                    raise HTTPException(status_code=409, detail="PLAN_REVISION_EXHAUSTED")
            else:
                started_state["writing_plan"] = saved_plan
        if reuse_existing_artifacts and existing[1].get("teacherBlueprint"):
            saved_blueprint = TeacherBlueprint.model_validate(existing[1]["teacherBlueprint"])
            if request.operation == "BLUEPRINT_REVISE":
                if saved_blueprint.revision_round >= DEFAULT_BLUEPRINT_REVISION_LIMIT:
                    raise HTTPException(status_code=409, detail="TEACHER_BLUEPRINT_REVISION_EXHAUSTED")
            else:
                started_state["teacher_blueprint"] = saved_blueprint
        if reuse_existing_artifacts and existing[1].get("writers"):
            # A node checkpoint is authoritative after queue redelivery. Resumed nodes return validated artifacts
            # without opening another provider socket, so retries cannot silently double token cost.
            started_state["writers"] = [WriterDocument.model_validate(item) for item in existing[1]["writers"]]
        self._checkpoint.save(request.run_id, "RUNNING", started_state, {"event": "started", "graphVersion": request.graph_version, "operation": request.operation})
        try:
            state = self._graph.invoke(started_state)
            package = state.get("package") or self._stage_package(request, state)
            telemetry.sample_system()
            package = package.model_copy(update={"metrics": telemetry.finish()})
            HandoutMetricsLedger().append(request, package.metrics, package.status)
            final_state = dict(state)
            final_state["package"] = package
            self._checkpoint.save(request.run_id, package.status, final_state, {"event": "completed", "status": package.status})
            return package
        except HandoutOutputContractError as exc:
            if self._is_checkpoint_store_unavailable(exc):
                raise self._checkpoint_unavailable_response() from exc
            telemetry.sample_system()
            HandoutMetricsLedger().append(request, telemetry.finish(), "FAILED")
            latest = self._checkpoint.load(request.run_id)
            self._checkpoint.save(request.run_id, "FAILED", latest[1] if latest else started_state,
                                  {"event": "failed", "error": "output_contract_failure"})
            raise HTTPException(status_code=422, detail={
                "code": "HANDOUT_OUTPUT_CONTRACT_FAILURE",
                "message": "Handout model output failed deterministic validation after one repair",
            }) from exc
        except HTTPException:
            telemetry.sample_system()
            HandoutMetricsLedger().append(request, telemetry.finish(), "FAILED")
            latest = self._checkpoint.load(request.run_id)
            self._checkpoint.save(request.run_id, "FAILED", latest[1] if latest else started_state, {"event": "failed", "error": "http_error"})
            raise
        except Exception as exc:
            # Checkpoint writes are the recovery boundary. Once MySQL is unavailable, avoid telemetry and failure
            # writes that would mask the outage or replace the last valid boundary.
            if self._is_checkpoint_store_unavailable(exc):
                raise self._checkpoint_unavailable_response() from exc
            telemetry.sample_system()
            HandoutMetricsLedger().append(request, telemetry.finish(), "FAILED")
            latest = self._checkpoint.load(request.run_id)
            # The Java task timeline needs a concise actionable failure, not a generic 503 that hides the node or
            # contract boundary. Keep Python internals out of the response while retaining the exception class.
            safe_detail = str(exc).replace("\n", " ").strip()[:360]
            failure = type(exc).__name__ if not safe_detail else f"{type(exc).__name__}: {safe_detail}"
            self._checkpoint.save(request.run_id, "FAILED", latest[1] if latest else started_state,
                                  {"event": "failed", "error": failure})
            raise HTTPException(status_code=503, detail=f"Handout graph failed: {failure}") from exc
        finally:
            with self._telemetry_lock:
                self._telemetry_by_run.pop(request.run_id, None)

    @staticmethod
    def _stage_package(request: HandoutRunRequest, state: HandoutRunState) -> HandoutDraftPackage:
        """Returns a reviewable partial artifact; Java remains the authority that selects the next operation."""
        plan = state.get("writing_plan")
        blueprint = state.get("teacher_blueprint")
        if request.operation in {"PLAN", "PLAN_REVISE"} and plan is not None:
            phase = "PLAN_APPROVED"
        elif request.operation in {"BLUEPRINT", "BLUEPRINT_REVISE"} and blueprint is not None:
            phase = "TEACHER_BLUEPRINT_APPROVED"
        else:
            raise ValueError("staged operation completed without its required artifact")
        return HandoutDraftPackage(runId=request.run_id, taskId=request.task_id, contractVersion=request.contract_version,
                                  graphVersion=request.graph_version, status="WAITING_REVIEW", phase=phase,
                                  evidence=state.get("evidence", EvidenceSnapshot()), writingPlan=plan,
                                  teacherBlueprint=blueprint, documents={}, validation=ValidationReport(valid=True),
                                  metrics=HandoutMetrics(startedAt=_utc_now()))

    def events(self, run_id: str) -> list[dict[str, Any]]:
        """Returns only operational events; prompt and source content never enter this stream."""
        return self._checkpoint.events(run_id)

    def event_page(self, run_id: str, after_id: int = 0, limit: int = DEFAULT_EVENT_PAGE_LIMIT) -> list[tuple[int, dict[str, Any]]]:
        """Returns one bounded event-store page for SSE and reconnecting control-plane consumers."""
        return self._checkpoint.events_after(run_id, after_id, limit)

    def _build_graph(self):
        graph = StateGraph(HandoutRunState)
        graph.add_node("resource_curation", self._resource_curation)
        graph.add_node("teacher_resource_curation", self._teacher_resource_curation)
        graph.add_node("plan_writer", self._plan_writer)
        graph.add_node("teacher_blueprint_writer", self._teacher_blueprint_writer)
        graph.add_node("teacher_writer", self._teacher_writer)
        graph.add_node("student_writer", self._student_writer)
        graph.add_node("lecture_writer", self._lecture_writer)
        graph.add_node("structured_validation", self._structured_validation)
        graph.add_edge(START, "resource_curation")
        graph.add_edge("resource_curation", "plan_writer")
        graph.add_conditional_edges("plan_writer", self._after_plan)
        graph.add_conditional_edges("teacher_resource_curation", self._after_teacher_resource_curation)
        graph.add_conditional_edges("teacher_blueprint_writer", self._after_blueprint)
        graph.add_edge("teacher_writer", "structured_validation")
        graph.add_edge("student_writer", "structured_validation")
        graph.add_edge("lecture_writer", "structured_validation")
        graph.add_edge("structured_validation", END)
        return graph.compile()

    @staticmethod
    def _after_plan(state: HandoutRunState) -> str | list[str]:
        """Collection is complete before planning; downstream nodes consume its consolidated evidence only."""
        return END if state["request"].operation in {"PLAN", "PLAN_REVISE"} else "teacher_blueprint_writer"

    @staticmethod
    def _after_teacher_resource_curation(state: HandoutRunState) -> str | list[str]:
        """Lets PLAN inspect AI-selected private evidence, then stops before any visible-content node."""
        if state["request"].operation in {"PLAN", "PLAN_REVISE"}:
            return END
        return "teacher_blueprint_writer"

    @staticmethod
    def _after_blueprint(state: HandoutRunState) -> str | list[str]:
        """Allows review of the teacher blueprint before any student or projection text is generated."""
        if state["request"].operation in {"BLUEPRINT", "BLUEPRINT_REVISE"}:
            return END
        return ["teacher_writer", "student_writer", "lecture_writer"]

    def _resource_curation(self, state: HandoutRunState) -> dict[str, Any]:
        """Collects authorized evidence before planning through a bounded private ReAct loop.

        Java supplies only initial run-scoped context and executes exact Python-selected queries. Decision prompts,
        broker payloads/responses and source-to-gap assessments are checkpointed privately; public events expose only
        iteration, counts and a categorical stop reason.
        """
        request = state["request"]
        self._check_deadline(request)
        started = time.monotonic()
        supplied_evidence = state.get("evidence")
        # A recovery request can carry freshly re-authorized initial evidence. That evidence is not a completed
        # collection stage: it must still pass the document-specific deep-read gate before planning. Only a state
        # that has already reached a downstream artifact may reuse its previously collected evidence.
        if supplied_evidence is not None and (
                state.get("writing_plan") is not None
                or state.get("teacher_blueprint") is not None
                or bool(state.get("writers"))):
            self._record_node(request, "resource_curation", started, "RESUMED")
            return {"evidence": supplied_evidence}
        payload = {
            "runId": request.run_id,
            "evidenceRefs": request.evidence_refs,
            "limit": int(os.getenv("MATH_AGENT_HANDOUT_CONTEXT_LIMIT", str(DEFAULT_CONTEXT_LIMIT))),
        }
        try:
            if supplied_evidence is not None:
                evidence = supplied_evidence
                self._save_collection_diagnostic(request, {
                    "directContextEvidence": evidence.model_dump(by_alias=True, exclude_none=True),
                    "initialContextPayload": {"initialEvidence": True, "runId": request.run_id},
                })
            else:
                response = self._java_context(payload) if request.deadline_epoch_ms is None else self._java_context(
                    payload, deadline_epoch_ms=request.deadline_epoch_ms)
                evidence = EvidenceSnapshot.model_validate(response)
                # Direct context is intentionally not deep-read. Only a later AI-selected opaque document action may
                # materialize original-Markdown blocks into inspected_items.
                self._save_collection_diagnostic(request, {
                    "directContextEvidence": evidence.model_dump(by_alias=True, exclude_none=True),
                    "initialContextPayload": payload, "initialContextResponse": response,
                })
            for iteration in range(1, DEFAULT_COLLECTION_DECISION_LIMIT + 1):
                decision, usage, provider, model, review = self._reviewed_model_candidate(
                    request, "resource_curation", self._resource_collection_prompt(request, evidence, iteration),
                    lambda candidate: self._validate_collection_decision(candidate, evidence),
                )
                self._save_collection_diagnostic(request, {"iterations": {str(iteration): {
                    "decisionPrompt": self._resource_collection_prompt(request, evidence, iteration),
                    "effectiveDecision": decision.model_dump(by_alias=True), "provider": provider, "model": model,
                    "review": review.checkpoint_value(), "evidenceBefore": evidence.model_dump(by_alias=True, exclude_none=True),
                }}})
                if decision.sufficient:
                    if not any(item.ref for item in evidence.items):
                        self._terminate_insufficient_evidence(request, evidence, iteration, "NO_USABLE_EVIDENCE")
                    return self._handoff_collected_evidence(
                        request, state, evidence, iteration, "SUFFICIENT", started, usage, provider, model, review)
                # The final decision is a forced handoff boundary. Its prompt forbids another query, and any malformed
                # query is ignored rather than extending collection beyond the fixed decision cap.
                if iteration == DEFAULT_COLLECTION_DECISION_LIMIT and not decision.actions:
                    if not any(item.ref for item in evidence.items):
                        self._terminate_insufficient_evidence(request, evidence, iteration, "NO_USABLE_EVIDENCE")
                    return self._handoff_collected_evidence(
                        request, state, evidence, iteration, "DECISION_CAP_REACHED_HANDOFF", started, usage, provider, model, review)
                actions = decision.actions
                if not actions:
                    if not any(item.ref for item in evidence.items):
                        self._terminate_insufficient_evidence(request, evidence, iteration, "NO_USABLE_EVIDENCE")
                    return self._handoff_collected_evidence(
                        request, state, evidence, iteration, "NO_USABLE_ACTION_HANDOFF", started, usage, provider, model, review)
                before_refs = {item.ref for item in evidence.items if item.ref}
                before_inspected_refs = {item.ref for item in evidence.inspected_items if item.ref}
                evidence = self._execute_collection_actions(request, evidence, actions, iteration)
                new_count = len({item.ref for item in evidence.items if item.ref} - before_refs)
                new_inspected_count = len({item.ref for item in evidence.inspected_items if item.ref} - before_inspected_refs)
                self._checkpoint.save(request.run_id, "RUNNING", {**state, "evidence": evidence}, {
                    "event": "resource_collection", "iteration": iteration, "status": "COLLECTED",
                    "actionCount": len(actions), "newEvidenceCount": new_count, "newInspectedCount": new_inspected_count,
                    "evidenceCount": len(evidence.items),
                })
                if iteration == DEFAULT_COLLECTION_DECISION_LIMIT:
                    return self._handoff_collected_evidence(
                        request, state, evidence, iteration, "DECISION_CAP_IMAGE_READ_HANDOFF", started, usage, provider, model, review)
                if not new_count and not new_inspected_count and any(item.ref for item in evidence.items):
                    # A selected read/search that returns no usable block is still a private observation the next
                    # decision needs in order to choose a different authorized document or teacher-resource search.
                    selected_document_action = any(action.kind in {"document_read", "document_page_read", "canonical_question_read"} for action in actions)
                    if selected_document_action:
                        self._save_collection_diagnostic(request, {"iterations": {str(iteration): {
                            "collectionObservation": "UNREADABLE_OR_EMPTY_DOCUMENT",
                        }}})
                        continue
                    return self._handoff_collected_evidence(
                        request, state, evidence, iteration, "NO_NEW_USABLE_EVIDENCE_HANDOFF", started, usage, provider, model, review)
            raise AssertionError("collection decision loop exceeded its fixed cap")
        except HTTPException:
            self._record_node(request, "resource_curation", started, "FAILED", java_requests=1,
                              payload_bytes=len(json.dumps(payload, ensure_ascii=False).encode("utf-8")))
            raise
        except Exception as exc:
            self._record_node(request, "resource_curation", started, "FAILED", java_requests=1,
                              payload_bytes=len(json.dumps(payload, ensure_ascii=False).encode("utf-8")), error=type(exc).__name__)
            raise

    def _handoff_collected_evidence(
            self, request: HandoutRunRequest, state: HandoutRunState, evidence: EvidenceSnapshot, iteration: int,
            reason: str, started: float, usage: dict[str, int | float], provider: str, model: str,
            review: ModelReviewMetadata) -> dict[str, Any]:
        """Stops collection without discarding non-empty authorized evidence; strict plan validation remains next."""
        self._save_collection_diagnostic(request, {
            "stopReason": reason, "consolidatedEvidence": evidence.model_dump(by_alias=True, exclude_none=True),
        })
        self._record_node(request, "resource_curation", started, "SUCCESS", provider_calls=review.turns,
                          java_requests=1, payload_bytes=0, usage=usage, provider=provider, model=model)
        self._checkpoint.save(request.run_id, "RUNNING", {**state, "evidence": evidence}, {
            "event": "resource_collection", "iteration": iteration, "status": "HANDOFF", "stopCategory": reason,
            "evidenceCount": len(evidence.items), "inspectedCount": len(evidence.inspected_items),
        })
        return {"evidence": evidence}

    def _save_collection_diagnostic(self, request: HandoutRunRequest, update: dict[str, Any]) -> None:
        """Stores protected collection replay material outside public event records."""
        previous = self._checkpoint.load_private_state(request.run_id).get("privateDiagnostics", {}).get("resourceCollection", {})
        merged = dict(previous) if isinstance(previous, dict) else {}
        for key, value in update.items():
            if isinstance(value, dict) and isinstance(merged.get(key), dict):
                next_value = dict(merged[key])
                next_value.update(value)
                merged[key] = next_value
            else:
                merged[key] = value
        self._checkpoint.save_private_state(request.run_id, {"privateDiagnostics": {"resourceCollection": merged}})

    @staticmethod
    def _validate_collection_decision(candidate: Any, evidence: EvidenceSnapshot) -> ResourceCollectionDecision:
        """Requires a selected original-source read before planning can hand off image-bearing evidence."""
        decision = ResourceCollectionDecision.model_validate(candidate)
        if decision.sufficient and decision.actions:
            raise ValueError("resource collection: sufficient decision cannot include actions")
        if decision.sufficient and any(item.document_ref for item in evidence.items) and not evidence.inspected_items:
            raise ValueError("resource collection: inspectable evidence requires an authorized deep read before handoff")
        image_document_refs = {
            item.document_ref or item.ref
            for item in evidence.items
            if item.image_refs
        }
        inspected_image_document_refs = {
            item.document_ref or item.ref
            for item in evidence.inspected_items
            if item.image_refs
        }
        missing_image_document_refs = image_document_refs - inspected_image_document_refs
        selected_document_refs = {
            action.document_ref
            for action in decision.actions
            if action.kind in {"document_read", "document_page_read", "canonical_question_read"}
        }
        # Image-bearing sources are deep-read automatically at the bounded handoff. The decision model may focus its
        # limited actions on remaining evidence without restating a Java-authorized source-image document.
        return decision

    def _resource_collection_prompt(self, request: HandoutRunRequest, evidence: EvidenceSnapshot, iteration: int) -> str:
        """Supplies the private decision model only current run-authorized evidence and its own prior collection state."""
        final_iteration = iteration >= DEFAULT_COLLECTION_DECISION_LIMIT
        action_rules = (
            "直接命中包含可读 documentRef 时，必须先选择至少一个 document_read、document_page_read 或 canonical_question_read，完成原文深读后才能返回 sufficient=true、actions=[]；仅当没有可读 documentRef 或原文已精读充分时才可停止。"
            "transparentRef 以 gaokao:// 开头的 documentRef 必须选择 canonical_question_read 精读，不得对它使用 document_read。"
            if not final_iteration else
            "这是最终第 %d/%d 轮：应交接当前已授权证据。仅当存在尚未精读且带 imageRefs 的 documentRef 时，必须选择对应 document_read、document_page_read 或 canonical_question_read；否则返回 sufficient=false、actions=[]，不得请求任何新动作或检索。"
            % (iteration, DEFAULT_COLLECTION_DECISION_LIMIT)
        )
        return json.dumps({
            "stageCode": "resource_curation",
            "instruction": ("只输出 ResourceCollectionDecision JSON。评估直接命中与此前 agentSelectedDeepReads 是否足以支撑后续计划的真实题干、方法和必要图形。"
                            "sourceToGapAssessment 必须说明来源覆盖与具体缺口。%s actions 最多 4 项；document_read、document_page_read 和 canonical_question_read 只能使用 authorizedEvidence 中已有的 opaque documentRef；"
                            "canonical_question_read 仅用于 canonical 高考证据，固定读取当前 run 已授权的单题，不能提供题号、文件名、路径、查询或页码；"
                            "document_page_read 的 pageNo 必须与该 documentRef 对应 retrieved_hit 的 pageNo 完全相同，pageRadius 只能为 0 至 4；"
                            "teacher_resource_search 仅在当前授权文档仍不能填补缺口时使用。不得输出教学正文、逐步推理、路径、URL、Base64、文件系统信息或未经授权的引用。" % action_rules),
            "iteration": iteration, "decisionLimit": DEFAULT_COLLECTION_DECISION_LIMIT, "finalIteration": final_iteration,
            "writingGoal": request.writing_goal, "questionText": request.question_text,
            "authorizedEvidence": evidence.prompt_text(),
            "outputContract": {"sufficient": False, "actions": [{"kind": "document_read|document_page_read|canonical_question_read|teacher_resource_search", "documentRef": "opaque ref for document actions", "pageNo": "required only for document_page_read and must equal retrieved page", "pageRadius": "0 through 4 only for document_page_read", "query": "required only for teacher_resource_search"}], "sourceToGapAssessment": "来源到缺口的评估"},
        }, ensure_ascii=False)

    def _execute_collection_actions(self, request: HandoutRunRequest, evidence: EvidenceSnapshot,
                                    actions: list[ResourceCollectionAction], iteration: int) -> EvidenceSnapshot:
        """Executes only decision-selected bounded actions against direct-hit or search-authorized opaque refs."""
        diagnostics: dict[str, Any] = {"selectedActions": [action.model_dump(by_alias=True) for action in actions],
                                       "agentSelectedDeepReads": [], "teacherSearches": []}
        document_reads = [action.document_ref for action in actions if action.kind == "document_read"]
        canonical_question_reads = {
            action.document_ref for action in actions if action.kind == "canonical_question_read"
        }
        # Canonical 高考证据没有初始 imageRefs，模型可能只对飞书带图来源执行深读。与下方 imageRefs 自动补排
        # 同一模式：尚未精读的 gaokao:// 引用在此补排为单题 canonical 精读，保证授权题干与其 figures 行到达 Writer。
        canonical_hit_refs = {
            item.document_ref for item in evidence.items
            if item.document_ref and str(item.transparent_ref or "").startswith("gaokao://")
        }
        canonical_question_reads.update(ref for ref in canonical_hit_refs if ref not in canonical_question_reads)
        page_reads: dict[str, tuple[int, int]] = {}
        teacher_queries: list[str] = []
        allowed_refs = {item.document_ref for item in evidence.items if item.document_ref}
        for action in actions:
            if action.kind == "document_page_read":
                matching_item = next((item for item in evidence.items if item.document_ref == action.document_ref), None)
                if matching_item is None or matching_item.page_no != action.page_no:
                    diagnostics.setdefault("rejectedActions", []).append({"action": action.model_dump(by_alias=True), "reason": "UNAUTHORIZED_RETRIEVED_PAGE"})
                else:
                    page_reads[action.document_ref] = (action.page_no, action.page_radius)
            elif action.kind == "teacher_resource_search":
                teacher_queries.append(action.query)
        if teacher_queries:
            evidence = self._collect_teacher_resource_queries(request, evidence, teacher_queries, iteration)
            diagnostics["teacherSearches"] = teacher_queries
        trace: list[dict[str, Any]] = []
        selected_refs = list(dict.fromkeys([*document_reads, *canonical_question_reads, *page_reads]))
        # An image-bearing source already authorized by Java must be available to the writer before handoff. This only
        # schedules its bounded original-source read; the Writer remains the sole authority for retaining its row.
        image_refs = {item.document_ref for item in evidence.items if item.document_ref and item.image_refs}
        selected_refs.extend(ref for ref in image_refs if ref not in selected_refs)
        # The fixed deep-read budget serves canonical single-question reads first (one bounded block each, carrying
        # manifest-bound figures), then image-bearing sources, then the model's remaining selections.
        target_refs = [ref for ref in selected_refs if ref in canonical_question_reads]
        target_refs.extend(ref for ref in selected_refs if ref in image_refs and ref not in canonical_question_reads)
        target_refs.extend(ref for ref in selected_refs if ref not in image_refs and ref not in canonical_question_reads)
        if target_refs:
            evidence = self._enrich_authorized_document_context(request, evidence,
                diagnostic_trace=trace, target_document_refs=target_refs,
                canonical_question_read_refs=canonical_question_reads, page_reads=page_reads)
        diagnostics["agentSelectedDeepReads"] = trace
        self._save_collection_diagnostic(request, {"iterations": {str(iteration): diagnostics}})
        return evidence

    def _collect_teacher_resource_queries(self, request: HandoutRunRequest, evidence: EvidenceSnapshot,
                                          queries: list[str], iteration: int) -> EvidenceSnapshot:
        """Executes exactly the model-selected searches; later reads use only returned authorized document refs."""
        discovered: list[EvidenceItem] = []
        diagnostics: dict[str, Any] = {"selectedQueries": queries, "searches": [], "dedupeDrops": [], "capacityDrops": []}
        existing_refs = {item.ref for item in evidence.items if item.ref}
        for query in queries:
            self._ensure_curation_budget(request)
            payload = {"runId": request.run_id, "query": _bounded(query, 160), "limit": 6}
            response = self._java_broker_request("handout-teacher-resource-search", payload,
                                                 deadline_epoch_ms=request.deadline_epoch_ms)
            diagnostics["searches"].append({"payload": payload, "response": response})
            rows = response.get("items", [])
            if not isinstance(rows, list):
                raise ValueError("Java teacher-resource search returned invalid items")
            for row in rows:
                if not isinstance(row, dict):
                    diagnostics["dedupeDrops"].append({"reason": "INVALID_ITEM", "query": query})
                    continue
                item = EvidenceItem.model_validate(row)
                if not item.ref or item.ref in existing_refs or any(found.ref == item.ref for found in discovered):
                    diagnostics["dedupeDrops"].append({"reason": "DUPLICATE_OR_EMPTY_REF", "query": query, "item": row})
                    continue
                if len(evidence.items) + len(discovered) >= DEFAULT_COLLECTION_EVIDENCE_CAPACITY:
                    diagnostics["capacityDrops"].append({"reason": "EVIDENCE_CAPACITY", "query": query, "item": row})
                    continue
                discovered.append(item)
        merged = evidence.model_copy(update={"items": [*evidence.items, *discovered]})
        # Search hits are direct context for the next decision. The collector must explicitly choose a document action
        # before any original-Markdown block is materialized as an agent-selected deep read.
        diagnostics["retainedNewEvidenceCount"] = len(discovered)
        self._save_collection_diagnostic(request, {"iterations": {str(iteration): diagnostics}})
        return merged

    def _terminate_insufficient_evidence(self, request: HandoutRunRequest, evidence: EvidenceSnapshot,
                                         iteration: int, reason: str) -> None:
        """Creates a terminal, source-free public event before preventing plan and writer execution."""
        self._save_collection_diagnostic(request, {"stopReason": reason, "consolidatedEvidence": evidence.model_dump(by_alias=True, exclude_none=True)})
        self._checkpoint.save(request.run_id, "FAILED", {"request": request, "evidence": evidence}, {
            "event": "resource_collection", "iteration": iteration, "status": "INSUFFICIENT",
            "stopCategory": reason, "evidenceCount": len(evidence.items), "inspectedCount": len(evidence.inspected_items),
        })
        raise HTTPException(status_code=422, detail={"code": "HANDOUT_INSUFFICIENT_AUTHORIZED_EVIDENCE", "stopCategory": reason})

    def _teacher_resource_curation(self, state: HandoutRunState) -> dict[str, Any]:
        """Deprecated graph-node compatibility no-op; pre-plan collection owns all teacher-resource retrieval."""
        request = state["request"]
        started = time.monotonic()
        evidence = state.get("evidence", EvidenceSnapshot())
        self._record_node(request, "teacher_resource_curation", started, "SKIPPED")
        return {"evidence": evidence}

    def _record_model_turn(
        self,
        request: HandoutRunRequest,
        node: str,
        review_turn: int,
        attempt_number: int,
        update: dict[str, Any],
    ) -> str:
        """Appends the complete plaintext model exchange to the run checkpoint only.

        This intentionally creates no public event. It is the operator replay record for every
        provider call: outbound payload, raw reply, parsing, review, validation and retry outcome.
        """
        record_id = f"{node}:{review_turn}:{attempt_number}"
        if hasattr(self, "_checkpoint"):
            self._checkpoint.save_private_state(request.run_id, {"modelTurnDiagnostics": {record_id: update}})
        return record_id

    def _reviewed_model_candidate(
        self,
        request: HandoutRunRequest,
        node: str,
        initial_prompt: str,
        validate_candidate,
    ) -> tuple[Any, dict[str, int | float], str, str, ModelReviewMetadata]:
        """Generates once and makes one full repair only when deterministic validation fails.

        Model-written review envelopes and JSON patches were expensive and prolonged every normal request.
        Validation remains authoritative: the repair prompt receives only the original contract, the invalid
        candidate, and its deterministic failure code/message. All raw exchanges stay in the private checkpoint.
        """
        input_fingerprint = hashlib.sha256(
            f"{request.run_id}:{request.task_id}:{node}:{request.writing_goal}:{request.question_text}".encode("utf-8")
        ).hexdigest()
        usages: list[dict[str, int | float]] = []
        last_provider = ""
        last_model = ""
        candidate: Any = None
        feedback_codes: tuple[str, ...] = ()
        validation_message = ""
        repair_attempts = max(0, int(os.getenv(
            "MATH_AGENT_HANDOUT_MODEL_REPAIR_ATTEMPTS", str(DEFAULT_MODEL_REPAIR_ATTEMPTS))))

        for turn in range(1, repair_attempts + 2):
            prompt = initial_prompt if turn == 1 else self._repair_prompt(
                node, initial_prompt, candidate, feedback_codes, validation_message,
                request.operation, self._expected_revision_round(request, node))
            try:
                raw, usage, provider, model = self._invoke_json_model(request, node, prompt, review_turn=turn)
                # Older deterministic test adapters returned the removed review envelope. Runtime prompts now request
                # the business JSON directly, but unwrapping this legacy shape preserves checkpoint compatibility.
                candidate = raw.get("candidate") if isinstance(raw, dict) and raw.get("mode") == "full" and "candidate" in raw else raw
                usages.append(usage)
                last_provider, last_model = provider, model
                validated = validate_candidate(candidate)
            except (ValidationError, ValueError, ModelResponseParseError) as exc:
                validation_message = str(exc)
                feedback_codes = self._validation_feedback_codes(exc)
                self._record_model_turn(request, node, turn, 0, {
                    "validation": "FAILED",
                    "candidate": candidate,
                    "validationErrorType": type(exc).__name__,
                    "validationMessage": validation_message,
                    "feedbackCodes": list(feedback_codes),
                    "repairScheduled": turn <= repair_attempts,
                })
                if turn <= repair_attempts:
                    continue
                raise HandoutOutputContractError("HANDOUT_OUTPUT_CONTRACT_FAILURE") from exc

            metadata = ModelReviewMetadata(
                node=node,
                turns=turn,
                approved=True,
                feedback_codes=feedback_codes,
                candidate_hash=hashlib.sha256(json.dumps(candidate, ensure_ascii=False, sort_keys=True, default=str).encode("utf-8")).hexdigest(),
                input_fingerprint=input_fingerprint,
                status="APPROVED",
            )
            self._checkpoint.save(request.run_id, "RUNNING", {"modelReviews": {node: metadata.checkpoint_value()}},
                                  metadata.event_value())
            usage_total = {"promptTokens": 0, "completionTokens": 0, "totalTokens": 0, "estimatedCost": 0.0}
            for item in usages:
                usage_total = _sum_usage(usage_total, item)
            return validated, usage_total, last_provider, last_model, metadata

        raise HandoutOutputContractError("HANDOUT_OUTPUT_CONTRACT_FAILURE")

    @staticmethod
    def _validation_feedback_codes(error: Exception) -> tuple[str, ...]:
        """Maps deterministic contract failures to a small non-prose repair contract."""
        message = str(error).lower()
        if "unauthorized evidence" in message:
            return ("EVIDENCE_REFERENCE_INVALID",)
        if "asset" in message or "image" in message or "placement" in message:
            return ("ASSET_PLACEMENT_REQUIRED",)
        if "math" in message or "latex" in message:
            return ("MATH_MARKUP_INVALID",)
        if "question" in message or "missing" in message:
            return ("CANDIDATE_INCOMPLETE",)
        return ("CANDIDATE_INVALID",)

    def _expected_revision_round(self, request: HandoutRunRequest, node: str) -> int | None:
        """Returns the semantic revision required by the operation; a repair turn never increments it."""
        if node == "plan_writer":
            field = "writingPlan"
            revise = request.operation == "PLAN_REVISE"
        elif node == "teacher_blueprint_writer":
            field = "teacherBlueprint"
            revise = request.operation == "BLUEPRINT_REVISE"
        else:
            return None
        if not revise:
            return 0
        saved = self._checkpoint.load(request.run_id)
        saved_round = int((saved[1].get(field) or {}).get("revisionRound", 0)) if saved else -1
        return saved_round + 1

    @staticmethod
    def _repair_prompt(node: str, initial_prompt: str, candidate: Any,
                       feedback_codes: tuple[str, ...], validation_message: str,
                       operation: str, expected_revision_round: int | None) -> str:
        """Requests one complete replacement candidate from a concrete failed output and safe validation details."""
        revision_hint = ({
            "revisionRound": expected_revision_round,
            "instruction": "修复调用不等于业务修订；revisionRound 必须保持该操作要求的语义值。",
        } if expected_revision_round is not None else None)
        return json.dumps({
            "stageCode": node,
            "operation": operation,
            "instruction": "上一次 JSON 未通过确定性合同校验。只输出一个完整、可直接校验的 JSON 对象或数组，不要 Markdown、解释、推理、URL、路径或 Base64。必须修复所有 failureCodes 和 validationMessage 指出的错误。图片选择不通过 JSON 字段回传：不得输出 assetPlacements、logicalPath、assetId、assetIds、锚点、布局或题号绑定。写作正文若采用授权图片，只能在相关位置原样保留已有的 source-image: Markdown 行，不得新增、重复或改写该行。不能输出占位图。",
            "originalInstruction": initial_prompt,
            "invalidCandidate": candidate,
            "failureCodes": list(feedback_codes),
            "validationMessage": validation_message[:1200],
            "contractHints": revision_hint,
        }, ensure_ascii=False)

    def _plan_writer(self, state: HandoutRunState) -> dict[str, Any]:
        """Produces a self-approved, deterministically validated teaching plan."""
        request = state["request"]
        started = time.monotonic()
        if state.get("writing_plan") is not None:
            self._record_node(request, "plan_writer", started, "RESUMED")
            return {"writing_plan": state["writing_plan"]}
        evidence = state.get("evidence", EvidenceSnapshot())
        try:
            plan, usage, provider, model, review = self._reviewed_model_candidate(
                request,
                "plan_writer",
                self._plan_prompt(request, evidence),
                lambda candidate: self._validate_plan_candidate(candidate, request, evidence),
            )
        except HandoutOutputContractError as exc:
            self._record_node(request, "plan_writer", started, "FAILED", provider_calls=2, error="HANDOUT_OUTPUT_CONTRACT_FAILURE")
            raise
        self._record_node(request, "plan_writer", started, "SUCCESS", provider_calls=review.turns,
                          usage=usage, provider=provider, model=model)
        self._checkpoint.save(
            request.run_id,
            "RUNNING",
            {"request": request, "writingPlan": plan,
             "modelReviews": {"plan_writer": review.checkpoint_value()}},
            {"event": "plan_ready", "phase": "PLAN_DRAFTED", "revisionRound": plan.revision_round},
        )
        return {"writing_plan": plan}

    def _validate_plan_candidate(self, candidate: Any, request: HandoutRunRequest,
                                 evidence: EvidenceSnapshot) -> WritingPlan:
        """Applies all plan-specific deterministic checks only after model self-approval."""
        plan = WritingPlan.model_validate(candidate)
        saved = self._checkpoint.load(request.run_id) if request.operation == "PLAN_REVISE" else None
        saved_round = int((saved[1].get("writingPlan") or {}).get("revisionRound", 0)) if saved else -1
        expected_round = saved_round + 1 if request.operation == "PLAN_REVISE" else 0
        if plan.revision_round != expected_round:
            raise ValueError("writing plan: revision round does not match requested operation")
        self._validate_writing_plan(plan, request, evidence)
        return plan

    def _teacher_blueprint_writer(self, state: HandoutRunState) -> dict[str, Any]:
        """Writes a self-approved teacher source before deterministic derivation checks."""
        request = state["request"]
        started = time.monotonic()
        if state.get("teacher_blueprint") is not None:
            self._record_node(request, "teacher_blueprint_writer", started, "RESUMED")
            return {"teacher_blueprint": state["teacher_blueprint"]}
        plan = state.get("writing_plan")
        if plan is None or not plan.ready_for_next_stage:
            raise ValueError("teacher_blueprint_writer: approved writing plan is required")
        try:
            blueprint, usage, provider, model, review = self._reviewed_model_candidate(
                request,
                "teacher_blueprint_writer",
                self._teacher_blueprint_prompt(request, state.get("evidence", EvidenceSnapshot()), plan),
                lambda candidate: self._validate_blueprint_candidate(
                    candidate, request, plan, state.get("evidence", EvidenceSnapshot())),
            )
        except HandoutOutputContractError:
            self._record_node(request, "teacher_blueprint_writer", started, "FAILED", provider_calls=2,
                              error="HANDOUT_OUTPUT_CONTRACT_FAILURE")
            raise
        self._record_node(request, "teacher_blueprint_writer", started, "SUCCESS", provider_calls=review.turns,
                          usage=usage, provider=provider, model=model)
        self._checkpoint.save(
            request.run_id,
            "RUNNING",
            {"request": request, "teacherBlueprint": blueprint,
             "modelReviews": {"teacher_blueprint_writer": review.checkpoint_value()}},
            {"event": "teacher_blueprint_ready", "phase": "TEACHER_BLUEPRINT_DRAFTED",
             "revisionRound": blueprint.revision_round},
        )
        return {"teacher_blueprint": blueprint}

    def _validate_blueprint_candidate(self, candidate: Any, request: HandoutRunRequest,
                                      plan: WritingPlan, evidence: EvidenceSnapshot | None = None) -> TeacherBlueprint:
        """Validates only self-approved blueprints and keeps revision ownership at this node."""
        blueprint = TeacherBlueprint.model_validate(candidate)
        saved = self._checkpoint.load(request.run_id) if request.operation == "BLUEPRINT_REVISE" else None
        saved_round = int((saved[1].get("teacherBlueprint") or {}).get("revisionRound", 0)) if saved else -1
        expected_round = saved_round + 1 if request.operation == "BLUEPRINT_REVISE" else 0
        if blueprint.revision_round != expected_round:
            raise ValueError("teacher blueprint: revision round does not match requested operation")
        return self._validate_teacher_blueprint(blueprint, request, plan, evidence)

    def _writer(self, state: HandoutRunState, stage_code: str, audience: str, instruction: str) -> dict[str, Any]:
        """Runs the student or lecture writer through the shared self-review controller."""
        request = state["request"]
        started = time.monotonic()
        resumed = next((item for item in state.get("writers", []) if item.stage_code == stage_code), None)
        if resumed is not None:
            self._record_node(request, stage_code, started, "RESUMED")
            return {"writers": []}
        evidence = state.get("evidence", EvidenceSnapshot())
        plan = state.get("writing_plan")
        blueprint = state.get("teacher_blueprint")
        if plan is None or blueprint is None or not blueprint.ready_for_derivation:
            raise ValueError(f"{stage_code}: approved plan and teacher blueprint are required")
        try:
            document, usage, provider, model, review = self._reviewed_model_candidate(
                request,
                stage_code,
                self._writer_prompt(request, evidence, stage_code, audience, instruction, plan, blueprint),
                lambda candidate: self._normalize_writer_payload(candidate, stage_code, request.question_text, plan),
            )
        except HandoutOutputContractError:
            self._record_node(request, stage_code, started, "FAILED", provider_calls=2,
                              error="HANDOUT_OUTPUT_CONTRACT_FAILURE")
            raise
        self._record_node(request, stage_code, started, "SUCCESS", provider_calls=review.turns, usage=usage,
                          provider=provider, model=model)
        self._checkpoint.save(
            request.run_id,
            "RUNNING",
            {"request": request, "writers": [document],
             "modelReviews": {stage_code: review.checkpoint_value()}},
            {"event": "node_completed", "node": stage_code, "reviewTurns": review.turns},
        )
        return {"writers": [document]}

    def _teacher_writer(self, state: HandoutRunState) -> dict[str, Any]:
        """Publishes the approved teacher blueprint without opening a divergent fourth model call."""
        request = state["request"]
        started = time.monotonic()
        resumed = next((item for item in state.get("writers", []) if item.stage_code == "teacher_writer"), None)
        if resumed is not None:
            self._record_node(request, "teacher_writer", started, "RESUMED")
            return {"writers": []}
        blueprint = state.get("teacher_blueprint")
        if blueprint is None or not blueprint.ready_for_derivation:
            raise ValueError("teacher_writer: approved teacher blueprint is required")
        document = WriterDocument(stageCode="teacher_writer", title=blueprint.title, markdown=blueprint.markdown,
                                  citations=blueprint.citations,
                                  warnings=blueprint.remaining_edits)
        self._record_node(request, "teacher_writer", started, "SUCCESS")
        self._checkpoint.save(
            request.run_id,
            "RUNNING",
            {"request": request, "writers": [document]},
            {"event": "node_completed", "node": "teacher_writer", "derivedFrom": "teacher_blueprint"},
        )
        return {"writers": [document]}

    def _student_writer(self, state: HandoutRunState) -> dict[str, Any]:
        return self._writer(
            state,
            "student_writer",
            "student",
            "逐题写学生练习版：题目、分层提示、作答区。绝不输出最终答案、结论、完整推导、评分点、"
            "教师提示或正确选项；不要把教师版长解改写后放入学生版。标题从行首写 ## 标题，显示公式单行成对"
            "写作 $$公式$$，不得转义标题、混用 \\[、\\] 或裸 $。",
        )

    def _lecture_writer(self, state: HandoutRunState) -> dict[str, Any]:
        return self._writer(
            state,
            "lecture_writer",
            "lecture",
            "逐题写 16:10 课堂投影：每题一个独立教学单元，包含题目、最少必要的分步引导和课堂追问。"
            "图必须与对应题同页；不要复制教师版长解，不要输出最终答案或完整解答。标题从行首写 ## 标题，显示"
            "公式单行成对写作 $$公式$$，不得转义标题、混用 \\[、\\] 或裸 $。",
        )

    @staticmethod
    def _normalize_writer_payload(raw: Any, stage_code: str, question_text: str,
                                  plan: WritingPlan | None = None) -> WriterDocument:
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
                                  citations=_string_list(payload.get("citations")),
                                  warnings=_string_list(payload.get("warnings")))
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
        # A topic-only request is intentionally expanded into source-grounded questions by plan_writer. Requiring
        # the original topic tokens in every audience projection rejects valid paraphrases and previously caused the
        # student/lecture writers to fail after the plan and teacher blueprint had already passed. Keep the stronger
        # ordered token gate only for an explicitly numbered question batch supplied by the caller.
        explicit_batch = bool(QUESTION_MARKER_PATTERN.search(question_text or ""))
        if explicit_batch:
            cursor = 0
            for index, question in enumerate(questions, start=1):
                tokens = _question_tokens(question)
                if not tokens:
                    raise ValueError(f"{stage_code}: question {index} has no distinctive semantic tokens")
                positions = [(markdown.find(token, cursor), token) for token in tokens]
                found = [(position, token) for position, token in positions if position >= 0]
                if len(found) < DEFAULT_MIN_QUESTION_TOKEN_MATCHES:
                    raise ValueError(f"{stage_code}: question {index} is missing or semantically unmatched")
                position, token = min(found)
                cursor = position + len(token)
        if UNSAFE_DOCUMENT_TRANSPORT_PATTERN.search(markdown):
            raise ValueError(f"{stage_code}: unsafe image, URL, or HTML transport")
        HandoutRuntime._validate_writer_markup(markdown, stage_code)
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
        if stage_code == "teacher_writer":
            missing_sections = [marker for marker in TEACHER_REQUIRED_SECTION_MARKERS if marker not in markdown]
            if missing_sections:
                raise ValueError(f"teacher_writer: missing required sections: {','.join(missing_sections)}")
        if stage_code == "student_writer":
            leaked = [marker for marker in ANSWER_LEAK_MARKERS if marker in markdown]
            if leaked:
                raise ValueError(f"student_writer: answer leakage: {','.join(leaked)}")

    @staticmethod
    def _validate_writer_markup(markdown: str, stage_code: str) -> None:
        """Keeps model Markdown within the small delimiter subset that the XeLaTeX exporter can publish safely."""
        if ESCAPED_OR_LIST_HEADING_PATTERN.search(markdown):
            raise ValueError(f"{stage_code}: headings must start with an unescaped Markdown #")
        display_open = False
        for line_number, line in enumerate(markdown.splitlines(), start=1):
            has_display_delimiter = "$$" in line
            if "\\[" in line or "\\]" in line:
                raise ValueError(f"{stage_code}: line {line_number}: display math must use closed $$...$$ delimiters")
            if display_open:
                if line.strip() == "$$":
                    display_open = False
                    continue
                if "$$" in line or UNESCAPED_DOLLAR_PATTERN.search(line):
                    raise ValueError(f"{stage_code}: line {line_number}: display math cannot contain nested or inline dollar delimiters")
                continue
            if not has_display_delimiter:
                continue
            if line.strip() == "$$":
                display_open = True
                continue
            match = DISPLAY_MATH_LINE_PATTERN.fullmatch(line)
            if match is None or "$$" in match.group("formula"):
                raise ValueError(f"{stage_code}: line {line_number}: display math must be one closed $$...$$ expression")
            if len(UNESCAPED_DOLLAR_PATTERN.findall(line)) != 4:
                raise ValueError(f"{stage_code}: line {line_number}: display math cannot contain inline dollar delimiters")
        if display_open:
            raise ValueError(f"{stage_code}: display math block is not closed")

    def _structured_validation(self, state: HandoutRunState) -> dict[str, Any]:
        request = state["request"]
        started = time.monotonic()
        writers = state.get("writers", [])
        documents = {document.stage_code: document for document in writers}
        required = ("teacher_writer", "student_writer", "lecture_writer")
        # All three visible writer artifacts already originate from the AI runtime. At this handoff boundary require
        # only their presence so optional headings, question wording, and formatting never discard usable output.
        errors = [f"missing:{stage}" for stage in required if not (documents.get(stage).title.strip() and documents.get(stage).markdown.strip())]
        observed_codes = [document.stage_code for document in writers]
        duplicate_codes = sorted({code for code in observed_codes if observed_codes.count(code) > 1})
        errors.extend(f"duplicate:{code}" for code in duplicate_codes)
        # Publication continues with writer-produced content; validation reports operational artifact presence only.
        self._record_node(request, "structured_validation", started, "SUCCESS" if not errors else "FAILED", error=";".join(errors)[:500] if errors else None)
        report = ValidationReport(valid=not errors, repaired=False, errors=errors)
        metrics = HandoutMetrics(started_at=_utc_now())
        package = HandoutDraftPackage(run_id=request.run_id, task_id=request.task_id, contract_version=request.contract_version,
                                      graph_version=request.graph_version, status="COMPLETED" if report.valid else "FAILED",
                                      phase="COMPLETED" if report.valid else "FINAL_REVIEW", evidence=state.get("evidence", EvidenceSnapshot()),
                                      writingPlan=state.get("writing_plan"), teacherBlueprint=state.get("teacher_blueprint"),
                                      documents=documents, validation=report, metrics=metrics)
        self._checkpoint.save(request.run_id, package.status, {**state, "package": package},
                              {"event": "validated", "phase": package.phase, "valid": report.valid, "errors": errors})
        return {"package": package}

    def _java_context(self, payload: dict[str, Any], deadline_epoch_ms: int | None = None) -> dict[str, Any]:
        if deadline_epoch_ms is None:
            decoded = self._java_broker_request("handout-context", payload)
        else:
            decoded = self._java_broker_request("handout-context", payload, deadline_epoch_ms=deadline_epoch_ms)
        return {"query": "", "items": decoded.get("items", decoded.get("hits", [])), "source": "java-broker"}

    def _enrich_authorized_document_context(
            self, request: HandoutRunRequest, evidence: EvidenceSnapshot,
            diagnostic_trace: list[dict[str, Any]] | None = None,
            target_document_refs: list[str] | None = None,
            canonical_question_read_refs: set[str] | None = None,
            page_reads: dict[str, tuple[int, int]] | None = None) -> EvidenceSnapshot:
        """Appends bounded original parsed-Markdown windows only after an AI-selected opaque document action."""
        inspected_document_refs = {item.document_ref for item in evidence.inspected_items if item.document_ref}
        allowed_document_refs = {item.document_ref for item in evidence.items if item.document_ref}
        requested_refs = target_document_refs if target_document_refs is not None else [
            item.document_ref for item in evidence.items if item.document_ref
        ]
        document_refs = list(dict.fromkeys(
            document_ref for document_ref in requested_refs
            if document_ref in allowed_document_refs and document_ref not in inspected_document_refs
        ))
        # Search-stage enrichment continues the context-stage budget; a later model-selected source can never push
        # the checkpoint beyond its fixed inspected-source allocation.
        remaining = DEFAULT_MAX_INSPECTED_SOURCE_CHARS - sum(len(item.excerpt) for item in evidence.inspected_items)
        for document_ref in document_refs[:DEFAULT_DOCUMENT_INSPECTION_LIMIT]:
            self._ensure_curation_budget(request)
            if remaining <= 0:
                break
            page_selection = (page_reads or {}).get(document_ref)
            if document_ref in (canonical_question_read_refs or set()):
                broker_operation = "handout-canonical-question-read"
                broker_payload = {
                    "runId": request.run_id,
                    "documentRef": document_ref,
                    "maxBlocks": 1,
                    "maxChars": min(DEFAULT_DOCUMENT_READ_CHARS, remaining),
                }
            elif page_selection is None:
                broker_operation = "handout-document-read"
                broker_payload = {
                    "runId": request.run_id,
                    "documentRef": document_ref,
                    "maxBlocks": DEFAULT_DOCUMENT_READ_BLOCKS,
                    "maxChars": min(DEFAULT_DOCUMENT_READ_CHARS, remaining),
                }
            else:
                page_no, page_radius = page_selection
                broker_operation = "handout-document-page-read"
                broker_payload = {
                    "runId": request.run_id,
                    "documentRef": document_ref,
                    "pageNo": page_no,
                    "pageRadius": page_radius,
                    "maxBlocks": DEFAULT_DOCUMENT_READ_BLOCKS,
                    "maxChars": min(DEFAULT_DOCUMENT_READ_CHARS, remaining),
                }
            try:
                if request.deadline_epoch_ms is None:
                    response = self._java_broker_request(broker_operation, broker_payload)
                else:
                    response = self._java_broker_request(
                        broker_operation, broker_payload, deadline_epoch_ms=request.deadline_epoch_ms)
            except HTTPException as error:
                # A run-authorized teacher source can be archived or lose its bounded FILE reader after retrieval.
                # Keep that source opaque and let the next capped curation decision select another authorized document.
                if error.status_code == 404 and broker_operation == "handout-document-read":
                    if diagnostic_trace is not None:
                        diagnostic_trace.append({
                            "operation": broker_operation,
                            "documentRef": document_ref,
                            "payload": broker_payload,
                            "sourceAvailability": "UNAVAILABLE",
                        })
                    continue
                raise
            blocks = response.get("blocks", [])
            read_diagnostic = {"operation": broker_operation, "documentRef": document_ref,
                               "payload": broker_payload, "response": response, "budgetBefore": remaining,
                               "acceptedBlocks": [], "skippedBlocks": []}
            if not isinstance(blocks, list):
                if diagnostic_trace is not None:
                    diagnostic_trace.append(read_diagnostic)
                raise ValueError("Java handout document read returned invalid blocks")
            document_name = next((item.document_name or item.title for item in evidence.items if item.document_ref == document_ref), "")
            # 所有文档共用一个原文预算；超过剩余额度的块整体跳过，绝不截断块正文或丢失其不透明引用。
            for block in blocks[:DEFAULT_DOCUMENT_READ_BLOCKS]:
                if not isinstance(block, dict):
                    read_diagnostic["skippedBlocks"].append({"reason": "INVALID_BLOCK"})
                    continue
                text = str(block.get("text") or "").strip()
                reference = str(block.get("ref") or "").strip()
                if not text or not reference:
                    read_diagnostic["skippedBlocks"].append({"reason": "EMPTY_REF_OR_TEXT", "block": block})
                    continue
                if len(text) > remaining:
                    read_diagnostic["skippedBlocks"].append({"reason": "CHARACTER_BUDGET", "ref": reference, "chars": len(text)})
                    continue
                evidence.inspected_items.append(EvidenceItem(
                    ref=_bounded(reference, 240),
                    title=document_name,
                    documentName=document_name,
                    documentRef=document_ref,
                    pageNo=int(block.get("pageNo") or 0),
                    excerpt=text,
                    sourceRelativePath=str(block.get("articlePath") or "")[:1200],
                    imageRefs=[
                        {"markdownLine": str(item.get("markdownLine") or "")[:12000],
                         "logicalPath": str(item.get("logicalPath") or "")[:1200]}
                        for item in block.get("imageRefs", [])
                        if isinstance(item, dict) and item.get("markdownLine") and item.get("logicalPath")
                    ][:50],
                ))
                read_diagnostic["acceptedBlocks"].append({
                    "ref": reference,
                    "text": text,
                    "imageRefs": [
                        {"markdownLine": str(item.get("markdownLine") or "")[:12000],
                         "logicalPath": str(item.get("logicalPath") or "")[:1200]}
                        for item in block.get("imageRefs", [])
                        if isinstance(item, dict) and item.get("markdownLine") and item.get("logicalPath")
                    ][:50],
                    "chars": len(text),
                })
                remaining -= len(text)
            read_diagnostic["budgetAfter"] = remaining
            if diagnostic_trace is not None:
                diagnostic_trace.append(read_diagnostic)
        return evidence

    def _java_broker_request(
            self, operation: str, payload: dict[str, Any], deadline_epoch_ms: int | None = None) -> dict[str, Any]:
        """Uses fixed internal routes; neither a model nor input can select a URL, filesystem path, or shell command."""
        routes = {
            "handout-context": "handout-context",
            "handout-document-page-read": "handout-document-page-read",
            "handout-document-read": "handout-document-read",
            "handout-canonical-question-read": "handout-canonical-question-read",
            "handout-teacher-resource-search": "handout-teacher-resource-search",
        }
        route = routes.get(operation)
        if route is None:
            raise ValueError("Unsupported Java handout broker operation")
        base_url = os.getenv("MATH_AGENT_TOOL_BROKER_BASE_URL", "http://backend:8080").rstrip("/")
        worker_key = os.getenv("MATH_AGENT_AGENT_WORKER_SHARED_KEY", "")
        if not worker_key:
            raise HTTPException(status_code=503, detail="MATH_AGENT_AGENT_WORKER_SHARED_KEY is required")
        configured_timeout = float(os.getenv(
            "MATH_AGENT_HANDOUT_TOOL_BROKER_TIMEOUT_SECONDS",
            os.getenv("MATH_AGENT_TOOL_BROKER_TIMEOUT_SECONDS", str(DEFAULT_BROKER_TIMEOUT_SECONDS)),
        ))
        timeout = min(MAX_BROKER_TIMEOUT_SECONDS, max(1.0, configured_timeout))
        deadline_epoch_ms = deadline_epoch_ms or payload.get("deadlineEpochMs")
        if deadline_epoch_ms is not None:
            remaining = (int(deadline_epoch_ms) - int(time.time() * 1000)) / 1000.0
            if remaining <= 0:
                raise RuntimeError("handout deadline exceeded before Java broker request")
            # Java 读取的是已授权文档块；请求限时共享本次运行预算，避免为 broker 另开无限等待窗口。
            timeout = min(timeout, remaining)
        response = self._session.post(f"{base_url}/internal/agent-tools/v1/{route}", headers={"X-Agent-Worker-Key": worker_key}, json=payload, timeout=max(0.1, timeout))
        try:
            response.raise_for_status()
        except requests.HTTPError as error:
            status = getattr(error.response, "status_code", None) or getattr(response, "status_code", 0)
            # Source authorization/input failures are deterministic for this persisted run. Preserve their status so
            # Java records a terminal task instead of retrying an unchanged document reference until timeout.
            if 400 <= status < 500 and status not in {408, 429}:
                raise HTTPException(status_code=status, detail={
                    "code": "HANDOUT_BROKER_CLIENT_FAILURE",
                    "operation": operation,
                    "status": status,
                }) from error
            raise
        decoded = response.json()
        if not isinstance(decoded, dict):
            raise ValueError("Java handout broker returned invalid response")
        return decoded

    def _invoke_json_model(self, request: HandoutRunRequest, node: str, prompt: str,
                           review_turn: int = 1) -> tuple[Any, dict[str, int | float], str, str]:
        # Java owns the allow-list and signs the route. Production handouts never accept an
        # unverified environment order, while unit fixtures retain the explicit test-only default.
        if request.provider_route is not None:
            request.provider_route.verify_for(request.run_id, "handout")
            routes = [(selection.name, selection.model) for selection in [
                request.provider_route.primary, *request.provider_route.fallbacks,
            ]]
        else:
            routes = [(item.strip().lower(), "") for item in os.getenv(
                "MATH_AGENT_HANDOUT_PROVIDER_ORDER", "deepseek",
            ).split(",") if item.strip()]
        failures: list[str] = []
        # Transport retries only absorb short provider failures. Deterministic JSON/contract repairs happen once in
        # _reviewed_model_candidate and never repeat the same malformed response through the network retry loop.
        provider_attempts = max(1, int(os.getenv("MATH_AGENT_HANDOUT_MODEL_ATTEMPTS", str(DEFAULT_MODEL_RETRY_ATTEMPTS))))
        if provider_attempts * 2 >= PROVIDER_ATTEMPT_SLOT_SIZE:
            raise RuntimeError("MATH_AGENT_HANDOUT_MODEL_ATTEMPTS exceeds the durable generation/repair attempt slot")
        if review_turn < 1 or review_turn > 2:
            raise RuntimeError("handout generation attempt is outside the one-repair policy budget")
        # Each generation/repair turn owns its transport-retry range, preventing UsageLedger collisions while
        # preserving a strict maximum of one contract-directed repair generation.
        turn_slot_size = PROVIDER_ATTEMPT_SLOT_SIZE // 2
        attempt_number = PROVIDER_ATTEMPT_BASES.get(node, PROVIDER_ATTEMPT_SLOT_SIZE * 4) + (review_turn - 1) * turn_slot_size
        for provider, routed_model in routes:
            key, base_url, configured_model = self._provider_config(provider)
            model = routed_model or configured_model
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
                # DeepSeek-compatible reasoning routes account for hidden reasoning within max_tokens. Reserve
                # sufficient room for the complete visible JSON candidate after that internal reasoning finishes.
                # Keep each model completion within the shared run budget. A 100k default reserved the entire
                # 400k run budget after only a few repair turns and prevented the mandatory final review request.
                configured_max_output_tokens = max(1, int(os.getenv(
                    "MATH_AGENT_HANDOUT_COLLECTION_DECISION_MAX_OUTPUT_TOKENS" if node == "resource_curation"
                    else "MATH_AGENT_HANDOUT_MAX_OUTPUT_TOKENS",
                    str(DEFAULT_COLLECTION_DECISION_MAX_OUTPUT_TOKENS if node == "resource_curation"
                        else DEFAULT_HANDOUT_MAX_OUTPUT_TOKENS))))
                max_output_tokens = self._completion_ceiling(node, provider, model, configured_max_output_tokens)
                with self._telemetry_lock:
                    telemetry = self._telemetry_by_run.get(request.run_id)
                if telemetry is None:
                    raise RuntimeError("handout telemetry is unavailable for provider budget reservation")
                prompt_estimate = fallback_tokens(messages, "")[0]
                telemetry.reserve_provider_call(prompt_estimate, max_output_tokens)
                payload = {
                    "model": model,
                    "messages": messages,
                    "temperature": float(os.getenv("MATH_AGENT_HANDOUT_TEMPERATURE", "0.2")),
                    "max_tokens": max_output_tokens,
                }
                if provider == "deepseek" and model == "deepseek-v4-flash":
                    # Structured handout contracts need the visible JSON within the bounded completion budget. This
                    # route otherwise spends that budget in reasoning_content, leaving an empty or truncated content
                    # stream despite JSON-object mode. Operators may explicitly opt back in for provider diagnostics.
                    payload["response_format"] = {"type": "json_object"}
                    disable_thinking = os.getenv("MATH_AGENT_HANDOUT_DEEPSEEK_DISABLE_THINKING", "true")
                    if disable_thinking.strip().lower() in {"1", "true", "yes"}:
                        payload["enable_thinking"] = False
                # Use provider SSE so every received writer character becomes a private durable artifact immediately.
                # A timeout can then preserve the exact partial candidate instead of discarding the whole response body.
                payload["stream"] = True
                payload["stream_options"] = {"include_usage": True}
                try:
                    configured_timeout = float(os.getenv(
                        "MATH_AGENT_HANDOUT_MODEL_REPAIR_TIMEOUT_SECONDS" if review_turn > 1 else "MATH_AGENT_HANDOUT_MODEL_TIMEOUT_SECONDS",
                        str(DEFAULT_MODEL_REPAIR_TIMEOUT_SECONDS if review_turn > 1 else DEFAULT_MODEL_TIMEOUT_SECONDS),
                    ))
                    timeout = configured_timeout
                    if request.deadline_epoch_ms is not None:
                        remaining = (request.deadline_epoch_ms - int(time.time() * 1000)) / 1000.0
                        reserve = 0.0 if review_turn > 1 else float(os.getenv(
                            "MATH_AGENT_HANDOUT_MODEL_REPAIR_RESERVE_SECONDS", str(DEFAULT_MODEL_REPAIR_RESERVE_SECONDS)))
                        usable = remaining - reserve
                        if usable <= 0:
                            raise HTTPException(status_code=504, detail={
                                "code": "MODEL_TIMEOUT",
                                "message": "Handout model generation has no remaining budget after repair reserve",
                            })
                        timeout = min(configured_timeout, usable)
                    self._record_model_turn(request, node, review_turn, attempt_number, {
                        "requestedAt": _utc_now(),
                        "node": node,
                        "reviewTurn": review_turn,
                        "attemptNumber": attempt_number,
                        "provider": provider,
                        "model": model,
                        "requestPayload": payload,
                    })
                    stream_buffer = _StreamingCheckpointBuffer(
                        lambda update: self._record_model_turn(request, node, review_turn, attempt_number, update)
                    )
                    partial_content: list[str] = []
                    raw_events: list[str] = []
                    raw_usage: dict[str, Any] = {}
                    provider_started = time.monotonic()
                    anthropic_format = anthropic_compat.is_anthropic_provider(provider)
                    response = self._session.post(
                        f"{base_url}/v1/messages" if anthropic_format else f"{base_url}/chat/completions",
                        headers=anthropic_compat.anthropic_headers(key) if anthropic_format else {"Authorization": f"Bearer {key}"},
                        json=anthropic_compat.build_messages_payload(payload) if anthropic_format else payload,
                        stream=True,
                        timeout=max(0.1, timeout),
                    )
                    response.raise_for_status()
                    finish_reason: str | None = None
                    try:
                        content_type = str(getattr(response, "headers", {}).get("Content-Type", "")).lower()
                        if "text/event-stream" not in content_type:
                            # Some compatible relays ignore stream=true and still return one JSON response. Retain that
                            # valid full candidate rather than treating it as an empty SSE stream.
                            data = response.json()
                            if anthropic_format:
                                data = anthropic_compat.to_openai_completion(data)
                            first_choice = (data.get("choices") or [{}])[0]
                            content = str(first_choice.get("message", {}).get("content") or "")
                            finish_reason = str(first_choice.get("finish_reason") or "").strip() or None
                            raw_usage = data.get("usage") or {}
                            raw_body = response.text
                            if content:
                                if stream_buffer is None:
                                    stream_buffer = _StreamingCheckpointBuffer(
                                        lambda update: self._record_model_turn(request, node, review_turn, attempt_number, update)
                                    )
                                partial_content.append(content)
                                stream_buffer.add(content)
                                stream_buffer.flush("non_stream_completion")
                        else:
                            # Anthropic 流由适配层翻译成 OpenAI 形状的 data 帧字符串，下方解析逻辑保持单一。
                            event_stream = anthropic_compat.openai_sse_data_lines(response) if anthropic_format else iter_sse_data_events(response)
                            for event_data in event_stream:
                                # requests applies its read timeout between chunks; enforce the Java-issued absolute
                                # deadline here so a slow but chatty SSE response cannot outlive the task lease.
                                # Leave response serialization and checkpoint cleanup time before Java's HTTP deadline.
                                if request.deadline_epoch_ms is not None and (
                                        request.deadline_epoch_ms - int(time.time() * 1000)) <= 15_000:
                                    raise HTTPException(status_code=504, detail={
                                        "code": "MODEL_TIMEOUT",
                                        "message": "Handout model generation reached its lease safety margin",
                                    })
                                self._check_deadline(request)
                                if event_data == "[DONE]":
                                    continue
                                raw_events.append(event_data)
                                event = json.loads(event_data)
                                if not isinstance(event, dict):
                                    continue
                                usage = event.get("usage")
                                if isinstance(usage, dict):
                                    raw_usage = usage
                                for choice in event.get("choices") or []:
                                    reported_finish_reason = str(choice.get("finish_reason") or "").strip()
                                    if reported_finish_reason:
                                        finish_reason = reported_finish_reason
                                    delta = choice.get("delta") or {}
                                    content_delta = delta.get("content")
                                    if not content_delta:
                                        continue
                                    partial_content.append(str(content_delta))
                                    stream_buffer.add(str(content_delta))
                                    if stream_buffer.should_flush():
                                        stream_buffer.flush("threshold")
                            content = "".join(partial_content)
                            raw_body = "\n".join(raw_events)
                    finally:
                        if stream_buffer is not None:
                            stream_buffer.flush("terminal")
                        close = getattr(response, "close", None)
                        if callable(close):
                            close()
                    elapsed_ms = max(0, int((time.monotonic() - provider_started) * 1000))
                    adaptive_ceiling = None
                    if finish_reason == "length":
                        adaptive_ceiling = self._record_length_ceiling(node, provider, model, max_output_tokens)
                    self._record_model_turn(request, node, review_turn, attempt_number, {
                        "httpStatus": 200,
                        "rawResponse": raw_body,
                        "receivedAt": _utc_now(),
                        "elapsedMs": elapsed_ms,
                        "finishReason": finish_reason or "not_reported",
                        "requestedCompletionTokens": max_output_tokens,
                        "nextCompletionCeiling": adaptive_ceiling,
                    })
                    data = {"choices": [{"message": {"content": content}}], "usage": raw_usage}
                    self._record_model_turn(request, node, review_turn, attempt_number, {
                        "parsedJson": data,
                    })
                    # Token and prefix-cache accounting is provider-owned. Missing fields remain unavailable rather
                    # than being estimated from local strings, which would corrupt billing and cache evidence.
                    prompt_tokens = int(raw_usage.get("prompt_tokens", 0) or 0)
                    cached_details = raw_usage.get("prompt_tokens_details") or raw_usage.get("input_tokens_details") or {}
                    cached_prompt_tokens = int(cached_details.get("cached_tokens", 0) or 0) if isinstance(cached_details, dict) else 0
                    completion_tokens = int(raw_usage.get("completion_tokens", 0) or 0)
                    total_tokens = int(raw_usage.get("total_tokens", 0) or 0)
                    reported_fields = {
                        "promptTokens": "prompt_tokens" in raw_usage,
                        "cachedPromptTokens": isinstance(cached_details, dict) and "cached_tokens" in cached_details,
                        "completionTokens": "completion_tokens" in raw_usage,
                        "totalTokens": "total_tokens" in raw_usage,
                    }
                    usage_availability = "provider" if all(
                        reported_fields[key] for key in ("promptTokens", "completionTokens", "totalTokens")
                    ) else "not_reported"
                    price = cost_for(provider, model, prompt_tokens, completion_tokens) if usage_availability == "provider" else -1.0
                    UsageLedger().append(UsageEvent(
                        request.run_id, provider, model, attempt_number, "SUCCESS", prompt_tokens,
                        completion_tokens, total_tokens, price, usage_availability, cached_prompt_tokens=cached_prompt_tokens,
                    ))
                    self._record_model_turn(request, node, review_turn, attempt_number, {
                        "usage": {"promptTokens": prompt_tokens, "cachedPromptTokens": cached_prompt_tokens,
                                  "completionTokens": completion_tokens, "totalTokens": total_tokens,
                                  "estimatedCost": price, "availability": usage_availability,
                                  "reportedFields": reported_fields},
                    })
                    try:
                        parsed = self._parse_json(content)
                        self._record_model_turn(request, node, review_turn, attempt_number, {
                            "extractedContent": content,
                            "extractedJson": parsed,
                            "outcome": "SUCCESS",
                        })
                        return parsed, {"promptTokens": prompt_tokens, "completionTokens": completion_tokens, "totalTokens": total_tokens, "estimatedCost": price}, provider, model
                    except (ValueError, json.JSONDecodeError) as parse_exc:
                        self._record_model_turn(request, node, review_turn, attempt_number, {
                            "extractedContent": content,
                            "parseError": type(parse_exc).__name__,
                            "parseMessage": str(parse_exc),
                            "outcome": "PARSE_FAILED",
                        })
                        raise ModelResponseParseError(str(parse_exc)) from parse_exc
                except ModelResponseParseError:
                    # A complete response that cannot satisfy JSON parsing is a content defect, not a transient
                    # transport failure. The caller performs one contract-directed full repair generation.
                    raise
                except requests.HTTPError as exc:
                    if partial_content:
                        partial = "".join(partial_content)
                        self._record_model_turn(request, node, review_turn, attempt_number, {
                            "partialContent": partial,
                            "partialChars": len(partial),
                            "terminatedAt": _utc_now(),
                            "outcome": "PARTIAL_TERMINATED",
                        })
                    status = exc.response.status_code if exc.response is not None else 0
                    provider_code = ""
                    raw_error_body = ""
                    if exc.response is not None:
                        try:
                            raw_error_body = exc.response.text
                            provider_code = str((exc.response.json() or {}).get("error", {}).get("code", ""))
                        except ValueError:
                            provider_code = ""
                    error_code = f"HTTP_{status}" + (f"_{provider_code}" if provider_code else "")
                    if 500 <= status <= 599:
                        error_code = "UNAVAILABLE_5XX"
                    self._record_model_turn(request, node, review_turn, attempt_number, {
                        "transportError": error_code,
                        "exceptionType": type(exc).__name__,
                        "httpStatus": status,
                        "rawErrorBody": raw_error_body,
                        "outcome": "HTTP_ERROR",
                    })
                    failures.append(f"{provider}:{error_code}")
                    UsageLedger().append(UsageEvent(request.run_id, provider, model, attempt_number, "FAILED", 0, 0, 0, -1.0, "unavailable", error_code))
                    if provider_try + 1 >= provider_attempts:
                        break
                    base_backoff = max(0.1, float(os.getenv("MATH_AGENT_HANDOUT_RETRY_BACKOFF_SECONDS", "1.0")))
                    max_backoff = max(base_backoff, float(os.getenv("MATH_AGENT_HANDOUT_RETRY_MAX_BACKOFF_SECONDS", "8.0")))
                    time.sleep(min(max_backoff, base_backoff * (provider_try + 1)))
                except (requests.RequestException, ValueError, KeyError, IndexError, json.JSONDecodeError) as exc:
                    if partial_content:
                        partial = "".join(partial_content)
                        self._record_model_turn(request, node, review_turn, attempt_number, {
                            "partialContent": partial,
                            "partialChars": len(partial),
                            "terminatedAt": _utc_now(),
                            "outcome": "PARTIAL_TERMINATED",
                        })
                    self._record_model_turn(request, node, review_turn, attempt_number, {
                        "transportError": type(exc).__name__,
                            "exceptionMessage": str(exc),
                        "outcome": "TRANSPORT_ERROR",
                    })
                    failures.append(f"{provider}:{type(exc).__name__}")
                    UsageLedger().append(UsageEvent(request.run_id, provider, model, attempt_number, "FAILED", 0, 0, 0, -1.0, "unavailable", type(exc).__name__))
                    if provider_try + 1 < provider_attempts:
                        base_backoff = max(0.1, float(os.getenv("MATH_AGENT_HANDOUT_RETRY_BACKOFF_SECONDS", "1.0")))
                        max_backoff = max(base_backoff, float(os.getenv("MATH_AGENT_HANDOUT_RETRY_MAX_BACKOFF_SECONDS", "8.0")))
                        time.sleep(min(max_backoff, base_backoff * (provider_try + 1)))
                    else:
                        break
        raise HTTPException(status_code=503, detail="Handout model call failed: " + ",".join(failures))

    @staticmethod
    def _ensure_curation_budget(request: HandoutRunRequest) -> None:
        """Stops source expansion before it consumes the generation and one-repair time reservation."""
        if request.deadline_epoch_ms is None:
            return
        remaining = (request.deadline_epoch_ms - int(time.time() * 1000)) / 1000.0
        reserve = float(os.getenv(
            "MATH_AGENT_HANDOUT_CURATION_MODEL_RESERVE_SECONDS", str(DEFAULT_CURATION_MODEL_RESERVE_SECONDS)))
        if remaining <= reserve:
            raise HTTPException(status_code=504, detail={
                "code": "HANDOUT_CURATION_TIMEOUT",
                "message": "Handout source curation exhausted the reserved model generation window",
            })

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
    def _repair_invalid_json_string_escapes(content: str) -> str:
        """Preserves model-authored LaTeX when a JSON response leaves its backslashes unescaped.

        JSON permits only a small escape set, while mathematical Markdown commonly contains commands such as
        ``\\left`` and ``\\operatorname``. The provider can otherwise return a complete object that JSON rejects
        before the output contract has a chance to validate its fields. Only invalid escapes inside string values
        are doubled; valid JSON escapes and all structural characters remain untouched.
        """
        result: list[str] = []
        in_string = False
        escaped = False
        index = 0
        valid_simple_escapes = {'"', "\\", "/", "b", "f", "n", "r", "t"}
        while index < len(content):
            character = content[index]
            if not in_string:
                result.append(character)
                if character == '"':
                    in_string = True
                index += 1
                continue
            if escaped:
                result.append(character)
                escaped = False
                index += 1
                continue
            if character == "\\":
                following = content[index + 1] if index + 1 < len(content) else ""
                next_character = content[index + 2] if index + 2 < len(content) else ""
                latex_command = following.isalpha() and next_character.isascii() and next_character.isalpha()
                unicode_escape = following == "u" and index + 5 < len(content) and all(
                    digit in "0123456789abcdefABCDEF" for digit in content[index + 2:index + 6])
                if (following in valid_simple_escapes and not latex_command) or unicode_escape:
                    result.append(character)
                    escaped = True
                else:
                    result.append("\\\\")
                index += 1
                continue
            result.append(character)
            if character == '"':
                in_string = False
            index += 1
        return "".join(result)

    @classmethod
    def _parse_json(cls, content: str) -> Any:
        """Parses one provider JSON root without mistaking a nested array for a complete response."""
        cleaned = (content or "").lstrip("\ufeff").strip()
        if cleaned.startswith("```"):
            cleaned = cleaned.split("\n", 1)[-1].rsplit("```", 1)[0].strip()
        object_start = cleaned.find("{")
        array_start = cleaned.find("[")
        if object_start < 0 and array_start < 0:
            raise ValueError("model response contains no valid JSON object or array")
        # A provider may put a citations array in prose before the document object. Prefer the first object when one
        # exists; once selected, never search its nested arrays after an incomplete object fails to decode.
        position = object_start if object_start >= 0 else array_start
        expected_type = dict if object_start >= 0 else list
        candidate = cleaned[position:]
        try:
            value, _ = json.JSONDecoder().raw_decode(candidate)
        except json.JSONDecodeError as initial_error:
            try:
                value, _ = json.JSONDecoder().raw_decode(cls._repair_invalid_json_string_escapes(candidate))
            except json.JSONDecodeError as repaired_error:
                raise ValueError("model response contains no complete top-level JSON value") from repaired_error
        if not isinstance(value, expected_type):
            raise ValueError("model response root type does not match its opening delimiter")
        return value

    @staticmethod
    def _provider_config(provider: str) -> tuple[str | None, str, str]:
        keys = {"openai": "OPENAI_API_KEY", "dashscope": "DASHSCOPE_API_KEY", "deepseek": "DEEPSEEK_API_KEY", "ark": "ARK_API_KEY", "glm": "GLM_API_KEY"}
        bases = {"openai": os.getenv("OPENAI_BASE_URL", "https://api1.aisz.mom/v1"), "dashscope": "https://dashscope.aliyuncs.com/compatible-mode/v1", "deepseek": "https://api.deepseek.com/v1", "ark": "https://ark.cn-beijing.volces.com/api/v3", "glm": anthropic_compat.default_base_url()}
        key = os.getenv(keys.get(provider, ""))
        base = os.getenv(f"{provider.upper()}_BASE_URL", bases.get(provider, "")).rstrip("/")
        model = os.getenv(f"{provider.upper()}_CHAT_MODEL", os.getenv(f"MATH_AGENT_AI_RUNTIME_{provider.upper()}_MODEL", os.getenv("MATH_AGENT_AI_RUNTIME_MODEL", os.getenv("OPENAI_CHAT_MODEL", "gpt-5.6-luna"))))
        return key, base, model

    @staticmethod
    def _plan_prompt(request: HandoutRunRequest, evidence: EvidenceSnapshot) -> str:
        """Requests auditable instructional decisions rather than private model reasoning."""
        return json.dumps({
            "stageCode": "plan_writer",
            "instruction": "只输出一个完整 WritingPlan JSON 对象，不要 Markdown 代码围栏。计划是可见教学决策，不是思考过程：禁止 <think>、逐步推理、工具日志、原始证据转录、路径、URL、Base64 或图片标记。不得输出单个题目对象或数组。顶层必须逐项包含 learningObjective（非空字符串）、questions（非空数组）、completionCriteria（非空字符串数组）、readyForNextStage（JSON 布尔值 true）、revisionRound（首次为整数 0）和 warnings（数组）；questions 的每一项必须逐项包含 number（从 1 连续编号）、question（题干）、evidenceRefs（数组）、knowledgePoint（字符串）、teachingSequence（至少一个字符串）和 figureRequired（JSON 布尔值）。图片选择不属于计划 JSON：不得输出 assetPlacements、logicalPath、assetId、assetIds、题号绑定、URL、Base64 或 LaTeX 图片命令。写作阶段若采用授权资料，必须只在相关正文位置原样保留上下文已有的 source-image: Markdown 行，不得改写别名或目标，不得新增或重复图片行。按题目顺序覆盖提交题目；每题只引用 evidence 中实际存在的 ref。资料充足时建议组织约 %d 个以上互不重复的真实题目或变式；资料不足时只保留证据支持的题目，绝不凑题或编造题。授权资料收集已在此计划前完成，不得提出或输出检索查询。readyForNextStage 只能在所有完成标准已满足时为 true。" % DEFAULT_RECOMMENDED_QUESTION_COUNT,
            "writingGoal": request.writing_goal,
            "questionText": request.question_text,
            "revisionFeedback": request.revision_feedback,
            "requestedRevisionRound": "从已有计划修订时必须比原 round 加 1；首次为 0。",
            "evidence": evidence.prompt_text(),
            "outputContract": {
                "learningObjective": "string",
                "questions": [{"number": 1, "question": "题干", "evidenceRefs": ["evidence ref"], "knowledgePoint": "string", "teachingSequence": ["步骤"], "figureRequired": False}],
                "completionCriteria": ["可检验完成条件"], "readyForNextStage": True, "revisionRound": 0,
                "warnings": [],
            },
        }, ensure_ascii=False)

    @staticmethod
    def _validate_writing_plan(plan: WritingPlan, request: HandoutRunRequest, evidence: EvidenceSnapshot) -> None:
        """Ensures plan structure and evidence authorization without constraining AI lesson design."""
        # A plan may cite either the initial authorized retrieval hit or a block materialized by an
        # AI-selected bounded deep read. Both ref classes are run-scoped broker evidence; only the latter
        # carries more precise original wording for the same authorized document.
        authorized_items = [*evidence.items, *evidence.inspected_items]
        allowed_refs = {item.ref for item in authorized_items if item.ref}
        allowed_images = {
            (image.get("logicalPath", ""), image.get("markdownLine", ""))
            for item in authorized_items for image in item.image_refs
        }
        for expected_number, planned in enumerate(plan.questions, start=1):
            if planned.number != expected_number:
                raise ValueError("writing plan: question numbers must be consecutive")
            if not planned.evidence_refs:
                raise ValueError(f"writing plan: question {expected_number} requires authorized evidence")
            if not set(planned.evidence_refs).issubset(allowed_refs):
                raise ValueError(f"writing plan: question {expected_number} cites unauthorized evidence")
        if not plan.ready_for_next_stage:
            raise ValueError("writing plan: model did not declare completion")

    @staticmethod
    def _teacher_blueprint_prompt(request: HandoutRunRequest, evidence: EvidenceSnapshot, plan: WritingPlan) -> str:
        """Requests the single teacher source that downstream audiences derive from."""
        return json.dumps({
            "stageCode": "teacher_blueprint_writer",
            "instruction": "只输出 DeepSeek/OpenAI structured JSON 的 TeacherBlueprint 对象。依据已批准 writingPlan 写完整、可直接派生的教师版蓝图；不要输出 <think>、私有推理、路径、URL、Base64 或 LaTex 标记。下方 authorizedSourceImageRows 是已授权的原始来源图行：若某图与当前题目、解题步骤或关键分类直接相关，应在首次讲解该内容的位置逐字保留该完整 Markdown 行，使教师 PDF 可展示原图；与当前讲解无关的召回图可以不使用。不得改写、借用、重复、移动或新增图片行。每题必须按计划顺序包含题目、解题过程、最终答案、评分点、易错点。completionChecklist 必须列出已完成项目；本次 COMPLETE 操作不得保留 remainingEdits，必须输出空数组。readyForDerivation 必须是布尔值 true，且仅在 markdown 已覆盖全部题目和全部五个必填章节时为 true；不得为了保留编辑项输出 false。revisionRound 必须为数字 0；不要输出 1 或其他修订轮次。兼容旧 structured 输出时仅接受等价字段 derivationReady，且同样必须是布尔值 true。不要故意省略声明。",
            "writingGoal": request.writing_goal,
            "questionText": request.question_text,
            "approvedWritingPlan": plan.model_dump(by_alias=True, exclude_none=True),
            "evidence": evidence.prompt_text(),
            "authorizedSourceImageRows": list(dict.fromkeys(
                str(image.get("markdownLine") or "").strip()
                for item in evidence.inspected_items
                for image in item.image_refs
                if str(image.get("markdownLine") or "").strip().startswith("![source-image:")
            )),
            "mathematicsFormatting": HANDOUT_MATH_MARKUP_CONTRACT,
            "outputContract": {
                "type": "object",
                "required": ["title", "markdown", "completionChecklist", "remainingEdits", "readyForDerivation", "revisionRound"],
                "properties": {
                    "title": "string",
                    "markdown": "教师版蓝图",
                    "citations": ["evidence ref"],
                    "lectureCards": [{"title": "投影标题", "content": "投影内容"}],
                    "completionChecklist": ["完成项"],
                    "remainingEdits": [],
                    "readyForDerivation": "boolean",
                    "revisionRound": 0,
                },
            },
        }, ensure_ascii=False)

    @staticmethod
    def _validate_teacher_blueprint(blueprint: TeacherBlueprint, request: HandoutRunRequest,
                                    plan: WritingPlan, evidence: EvidenceSnapshot | None = None) -> TeacherBlueprint:
        """Accepts a readiness alias only after the complete teacher source passes deterministic semantic checks."""
        document = WriterDocument(stageCode="teacher_writer", title=blueprint.title, markdown=blueprint.markdown,
                                  citations=blueprint.citations,
                                  warnings=blueprint.remaining_edits)
        planned_question_text = "\n".join(
            f"【题目 {question.number}】\n{question.question}" for question in plan.questions
        )
        HandoutRuntime._validate_document_semantics(document, "teacher_writer", planned_question_text)
        required_image_rows = {
            str(image.get("markdownLine") or "").strip()
            for item in (evidence.inspected_items if evidence is not None else [])
            for image in item.image_refs
            if str(image.get("markdownLine") or "").strip().startswith("![source-image:")
        }
        if required_image_rows and not any(row in document.markdown for row in required_image_rows):
            raise ValueError("teacher blueprint: an authorized source image row must be retained verbatim")
        if not blueprint.completion_checklist:
            raise ValueError("teacher blueprint: completion checklist is empty")
        if blueprint.ready_for_derivation is False:
            raise ValueError("teacher blueprint: model explicitly declined derivation readiness")
        if blueprint.ready_for_derivation is None:
            raise ValueError("teacher blueprint: readyForDerivation is required")
        return blueprint

    @staticmethod
    def _writer_prompt(request: HandoutRunRequest, evidence: EvidenceSnapshot, stage: str, audience: str, instruction: str,
                       plan: WritingPlan, blueprint: TeacherBlueprint) -> str:
        projection_rules = {
            "preserveAllSubmittedQuestionsInOrder": True,
            "preserveAllKnowledgePoints": True,
            "approvedUpstreamOnly": "只依据 approvedWritingPlan 和 approvedTeacherBlueprint 派生当前版本；不得增加未计划题目、交换题序、借用别题图片或暴露私有推理。",
            "resourceImages": {
                "allowedReferencesOnly": True,
                "neverOutput": ["URL", "file path", "data URL", "Base64", "\\includegraphics", "HTML image tag"],
                "figureDependentQuestionRule": "题干出现如图、下图或图中时，只能保留当前 evidence 中已有且同源的 source-image: Markdown 行；没有匹配图片时必须改写为不依赖图片的文字题干，不得编造、借用、新增、重复或改写图片行。",
            },
            "markdownStructure": {
                "headings": "标题必须从行首写 # 或 ##，禁止 \\# 标题、列表前缀标题或代码围栏。",
                "displayMath": "显示公式必须单独占一行并写为 $$公式$$；每行只能有一组闭合 $$，不得混用 \\[、\\]、$公式$ 或裸 $。",
            },
            "teacher_writer": {
                "requiredOrderPerQuestion": ["题目", "解题过程", "最终答案", "评分点", "易错点"],
                "solutionRule": "解题过程至少以步骤 1、步骤 2 编号；每步说明目的及计算或推理依据。最终答案独立成行，不能只写在推导段落中。",
                "answerRule": "选择题写出选项及结论；填空题写出唯一填入内容；证明题写出结论与关键依据；计算题写出化简后的最终结果。",
            },
            "student_writer": {
                "allowedContent": ["题目", "合法图片引用", "分层提示", "作答区"],
                "forbiddenContent": ["最终答案", "结论", "完整推导", "评分点", "教师提示", "正确选项"],
            },
            "lecture_writer": {
                "oneQuestionPerTeachingUnit": True,
                "figureWithQuestion": True,
                "minimalText": True,
                "noFinalAnswerOrFullSolution": True,
                "noHorizontalRules": True,
                "noFillInLines": True,
                "blankSpaceOnly": True,
                "noResourceOrEvidenceCards": True,
                "noPageScreenshotAssets": True,
            },
        }
        return json.dumps({"stageCode": stage, "audience": audience, "instruction": instruction,
                           "writingGoal": request.writing_goal, "questionText": request.question_text,
                           "approvedWritingPlan": plan.model_dump(by_alias=True, exclude_none=True),
                           "approvedTeacherBlueprint": blueprint.model_dump(by_alias=True, exclude_none=True),
                           "evidence": evidence.prompt_text(), "projectionRules": projection_rules,
                           "mathematicsFormatting": HANDOUT_MATH_MARKUP_CONTRACT,
                           "outputContract": {"stageCode": stage, "title": "string", "markdown": "完整中文讲义内容",
                                               "citations": ["evidence ref"], "warnings": []},
                           "examples": {
                               "teacherSectionHeading": "## 最终答案\n\n$\\boxed{x=1}$",
                               "studentHint": "提示：先写出定义域，再判断端点是否可取。",
                               "forbiddenTransport": "不要输出 https://、file://、data:image、\\includegraphics 或 <img>。",
                           }}, ensure_ascii=False)

    def _record_node(self, request: HandoutRunRequest, node: str, started: float, status: str, provider_calls: int = 0, java_requests: int = 0, payload_bytes: int = 0, usage: dict[str, int | float] | None = None, error: str | None = None, provider: str = "", model: str = "") -> None:
        """Node records are emitted through the event sink while preserving bounded operational metadata."""
        elapsed_ms = int((time.monotonic() - started) * 1000)
        finished_at = datetime.now(timezone.utc)
        # The monotonic duration is authoritative. Deriving the start wall-clock timestamp from it avoids a clock
        # adjustment producing negative node timing while still allowing cross-service incident correlation.
        started_at = finished_at - timedelta(milliseconds=elapsed_ms)
        metric = NodeMetric(node=node, status=status, provider=provider, model=model,
                            started_at=started_at.isoformat(), finished_at=finished_at.isoformat(),
                            elapsed_ms=elapsed_ms, provider_calls=provider_calls,
                            java_requests=java_requests, payload_bytes=payload_bytes,
                            prompt_tokens=int((usage or {}).get("promptTokens", 0)),
                            cached_prompt_tokens=int((usage or {}).get("cachedPromptTokens", 0)),
                            completion_tokens=int((usage or {}).get("completionTokens", 0)),
                            total_tokens=int((usage or {}).get("totalTokens", 0)),
                            estimated_cost=float((usage or {}).get("estimatedCost", -1.0)), error=error)
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
