"""Versioned LangGraph context preparation for student-explanation runs."""
from __future__ import annotations

import hashlib
import json
from typing import Any, Literal, TypedDict

from langgraph.graph import END, START, StateGraph
from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from app.ai_run_runtime import ProviderRoute
from app.tokenizer import count_texts
from app.workload_runtime import StudentExplanationRunRequest


STUDENT_EXPLANATION_V2_CONTRACT = "student-explanation-ai-v2"
MAX_CONTEXT_MESSAGES = 200
MAX_SUMMARY_CHARS = 8_000
MAX_MESSAGE_CHARS = 8_000


class ConversationContextMessage(BaseModel):
    """Java-authorized, model-safe projection of one persisted conversation turn."""

    model_config = ConfigDict(extra="forbid")

    messageId: str = Field(min_length=1, max_length=160)
    questionText: str = Field(default="", max_length=MAX_MESSAGE_CHARS)
    answerText: str = Field(default="", max_length=MAX_MESSAGE_CHARS)
    createdAt: str = Field(default="", max_length=64)


class ConversationSummary(BaseModel):
    """Append-only summary interval confirmed by Java after durable history persistence."""

    model_config = ConfigDict(extra="forbid")

    summaryFromMessageId: str = Field(min_length=1, max_length=160)
    summaryToMessageId: str = Field(min_length=1, max_length=160)
    summaryVersion: int = Field(ge=1, le=2_147_483_647)
    contentHash: str = Field(min_length=32, max_length=128)
    content: str = Field(min_length=1, max_length=16_000)


class ConversationContextSnapshot(BaseModel):
    """One Java-authorized context snapshot; it carries no tenant or subject identity."""

    model_config = ConfigDict(extra="forbid")

    schemaVersion: Literal["student-conversation-context-v1"]
    revision: str = Field(min_length=1, max_length=160)
    messages: list[ConversationContextMessage] = Field(default_factory=list, max_length=MAX_CONTEXT_MESSAGES)
    summary: ConversationSummary | None = None

    @field_validator("messages")
    @classmethod
    def ordered_unique_messages(cls, value: list[ConversationContextMessage]) -> list[ConversationContextMessage]:
        seen: set[str] = set()
        result: list[ConversationContextMessage] = []
        for message in value:
            if message.messageId not in seen:
                seen.add(message.messageId)
                result.append(message)
        return result


class StudentExplanationGraphLimits(BaseModel):
    """Token policy supplied by Java's operator-owned route configuration."""

    model_config = ConfigDict(extra="forbid")

    maxInputTokens: int = Field(ge=512, le=120_000)
    reservedOutputTokens: int = Field(ge=128, le=32_000)
    summaryTriggerTokens: int = Field(ge=256, le=100_000)
    maxProviderCalls: int = Field(ge=1, le=4, default=1)

    @model_validator(mode="after")
    def usable_budget(self) -> "StudentExplanationGraphLimits":
        if self.reservedOutputTokens >= self.maxInputTokens:
            raise ValueError("reservedOutputTokens must be smaller than maxInputTokens")
        return self


class StudentExplanationGraphRequest(BaseModel):
    """Strict v2 worker contract for context packing before the student explanation model call."""

    model_config = ConfigDict(extra="forbid")

    contractVersion: Literal[STUDENT_EXPLANATION_V2_CONTRACT]
    runId: str = Field(min_length=1, max_length=128)
    deadlineEpochMs: int = Field(gt=0)
    problem: str = Field(min_length=1, max_length=8_000)
    imageDataUrl: str = Field(default="", max_length=12_000_000)
    context: ConversationContextSnapshot
    limits: StudentExplanationGraphLimits
    providerRoute: ProviderRoute

    @model_validator(mode="after")
    def validate_route_grant(self) -> "StudentExplanationGraphRequest":
        self.providerRoute.verify_for(self.runId, "student_explanation")
        return self


class _GraphState(TypedDict, total=False):
    request: StudentExplanationGraphRequest
    model: str
    selected_messages: list[ConversationContextMessage]
    summary: ConversationSummary | None
    packed_context: str
    input_tokens: int
    memory_update: dict[str, Any] | None


def _message_text(message: ConversationContextMessage) -> str:
    question = message.questionText.strip()
    answer = message.answerText.strip()
    return "\n".join(part for part in (f"用户：{question}" if question else "", f"助手：{answer}" if answer else "") if part)


def _summary_text(messages: list[ConversationContextMessage]) -> str:
    """Deterministic compact summary avoids an untracked extra provider call during the first migration."""
    parts = []
    for message in messages:
        text = _message_text(message).replace("\n", " ").strip()
        if text:
            parts.append(text[:480])
    return "\n".join(parts)


def _summary_update(messages: list[ConversationContextMessage], previous: ConversationSummary | None) -> dict[str, Any] | None:
    if not messages:
        return None
    content = _summary_text(messages)
    if previous and previous.content:
        content = previous.content.strip() + "\n" + content
        first = previous.summaryFromMessageId
    else:
        first = messages[0].messageId
    content = content[-MAX_SUMMARY_CHARS:]
    if not content:
        return None
    last = messages[-1].messageId
    digest = hashlib.sha256(content.encode("utf-8")).hexdigest()
    if previous and previous.summaryToMessageId == last and previous.contentHash == digest:
        return None
    return {
        "summaryFromMessageId": first,
        "summaryToMessageId": last,
        "summaryVersion": 1 if previous is None else previous.summaryVersion + 1,
        "contentHash": digest,
        "content": content,
    }


class StudentExplanationContextGraph:
    """LangGraph-owned deterministic summary/window preparation for v2 student explanations."""

    def __init__(self) -> None:
        graph = StateGraph(_GraphState)
        graph.add_node("load_context", self._load_context)
        graph.add_node("summarize_if_needed", self._summarize_if_needed)
        graph.add_node("pack_window", self._pack_window)
        graph.add_edge(START, "load_context")
        graph.add_edge("load_context", "summarize_if_needed")
        graph.add_edge("summarize_if_needed", "pack_window")
        graph.add_edge("pack_window", END)
        self._graph = graph.compile()

    def prepare(self, request: StudentExplanationGraphRequest) -> dict[str, Any]:
        state = self._graph.invoke({"request": request, "model": request.providerRoute.primary.model})
        return {
            "packedContext": state.get("packed_context", ""),
            "inputTokens": int(state.get("input_tokens", 0)),
            "memoryUpdate": state.get("memory_update"),
            "selectedMessageIds": [message.messageId for message in state.get("selected_messages", [])],
        }

    @staticmethod
    def _load_context(state: _GraphState) -> _GraphState:
        request = state["request"]
        messages = sorted(request.context.messages, key=lambda message: (message.createdAt, message.messageId))
        return {"selected_messages": messages, "summary": request.context.summary}

    @staticmethod
    def _summarize_if_needed(state: _GraphState) -> _GraphState:
        request = state["request"]
        messages = state.get("selected_messages", [])
        counts, _ = count_texts([_message_text(message) for message in messages], state["model"])
        if sum(counts) <= request.limits.summaryTriggerTokens:
            return {"memory_update": None}
        # Retain the newest turn verbatim. Only the unsummarized interval becomes the next summary update.
        older = messages[:-1]
        previous = state.get("summary")
        if previous:
            summary_to = previous.summaryToMessageId
            message_ids = [message.messageId for message in older]
            if summary_to in message_ids:
                older = older[message_ids.index(summary_to) + 1:]
        return {"memory_update": _summary_update(older, previous)}

    @staticmethod
    def _pack_window(state: _GraphState) -> _GraphState:
        request = state["request"]
        model = state["model"]
        messages = state.get("selected_messages", [])
        memory_update = state.get("memory_update")
        summary = memory_update or (state.get("summary").model_dump() if state.get("summary") else None)
        summary_text = str(summary.get("content") or "") if summary else ""
        if summary:
            summary_to = str(summary.get("summaryToMessageId") or "")
            message_ids = [message.messageId for message in messages]
            if summary_to in message_ids:
                messages = messages[message_ids.index(summary_to) + 1:]
        base = "当前题目：\n" + request.problem.strip()
        budget = request.limits.maxInputTokens - request.limits.reservedOutputTokens
        base_tokens = count_texts([base, summary_text], model)[0]
        selected: list[ConversationContextMessage] = []
        used = sum(base_tokens)
        for message in reversed(messages):
            message_tokens = count_texts([_message_text(message)], model)[0][0]
            if used + message_tokens > budget:
                continue
            selected.append(message)
            used += message_tokens
        selected.reverse()
        lines = []
        if summary_text:
            lines.append("已确认的较早会话摘要：\n" + summary_text)
        if selected:
            lines.append("最近会话：\n" + "\n\n".join(_message_text(message) for message in selected))
        lines.append(base)
        packed = "\n\n".join(lines)
        input_tokens = count_texts([packed], model)[0][0]
        if input_tokens > budget:
            raise ValueError("student explanation context exceeds configured token budget")
        return {"selected_messages": selected, "packed_context": packed, "input_tokens": input_tokens}


def as_v1_compose_request(request: StudentExplanationGraphRequest, prepared: dict[str, Any]) -> StudentExplanationRunRequest:
    """Compatibility adapter while Java migrates from v1 compose to the single graph stream."""
    return StudentExplanationRunRequest(
        runId=request.runId,
        mode="compose",
        problem=str(prepared["packedContext"]),
        imageDataUrl=request.imageDataUrl,
        providerRoute=request.providerRoute,
    )
