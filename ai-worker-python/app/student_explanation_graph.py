"""Versioned LangGraph context preparation for student-explanation runs."""
from __future__ import annotations

import hashlib
import json
import logging
from collections.abc import Callable
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

# 2026-09-02 老板拍板：压缩触发从 4000 提到 130k 级。原因是 provider 的 prompt/prefix cache
# 按消息前缀命中，每压缩一次都会改写"已确认的较早会话摘要 + 最近会话"前缀、令缓存作废；
# 本链路证据受讲义字符预算锁死、ReAct 动作数与观测轮数封顶，正常会话几乎到不了 130k token，
# 所以压缩是极端长会话的兜底而非常态。窗口预算 131072 对齐 128K 上下文模型档位。
# 这些值仍是可配置的：Java 的 @Value/application.yml 下发真实值，这里只定义默认与契约上限，
# 两侧上限必须保持一致（Java 钳位在 StudentExplanationService 与 PythonMigratedWorkloadClient）。
DEFAULT_MAX_INPUT_TOKENS = 131_072
DEFAULT_RESERVED_OUTPUT_TOKENS = 1_500
DEFAULT_SUMMARY_TRIGGER_TOKENS = 130_000
MAX_INPUT_TOKENS_CAP = 131_072
SUMMARY_TRIGGER_TOKENS_CAP = 130_000

# Java 为本次运行签发的 provider route 仍归 Java 授权；注入的单轮 chat 可调用
# (run_id, route, messages) -> str，内部必须完成 UsageLedger 记账（见 MigratedWorkloadRuntime.chat_messages），
# 绝不允许出现 untracked provider call。None 时图直接走确定性抽取式回退。
ConversationSummarizer = Callable[[str, ProviderRoute, list[dict[str, Any]]], str]

logger = logging.getLogger(__name__)

# 五维结构化摘要的输出合同。摘要是会话记忆压缩（内部上下文），不是学生可见教学正文，
# 因此维度与措辞由 Python 图定义，Java 只持久化返回的区间内容。
SUMMARY_STRUCTURED_SYSTEM_PROMPT = (
    "你是对话记忆压缩器。把输入对话记录压缩为五个维度的结构化摘要，只返回 JSON："
    "{\"goal\":\"\",\"completed\":\"\",\"readSources\":\"\",\"decisions\":\"\",\"conclusions\":\"\"}。"
    "goal=教学目标/用户目标；completed=已完成事项；readSources=已阅读或已引用的资料，"
    "必须原样保留其中的引用标识（如 sourceUri、文档名、题号）；decisions=做出的选择（方法、路线、取舍）；"
    "conclusions=得出的结论与尚未解决的问题。previousSummary 非空时先把它折叠进对应维度再输出，"
    "不要整段复述。各维度用简洁中文（每维不超过约 300 字），没有内容留空字符串，不得编造。"
    "不要输出 Markdown 或推理过程。"
)


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

    maxInputTokens: int = Field(default=DEFAULT_MAX_INPUT_TOKENS, ge=512, le=MAX_INPUT_TOKENS_CAP)
    reservedOutputTokens: int = Field(default=DEFAULT_RESERVED_OUTPUT_TOKENS, ge=128, le=32_000)
    summaryTriggerTokens: int = Field(default=DEFAULT_SUMMARY_TRIGGER_TOKENS, ge=256, le=SUMMARY_TRIGGER_TOKENS_CAP)
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
    # Java 始终下发 operator 配置；缺省实例即上面注释所述的 130k 兜底默认，便于本地/测试直连。
    limits: StudentExplanationGraphLimits = Field(default_factory=StudentExplanationGraphLimits)
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


class _StructuredSummarySections(BaseModel):
    """模型返回的五维摘要信封；字段缺失或多余都视为生成失败并触发抽取式回退。"""

    model_config = ConfigDict(extra="forbid")

    goal: str = Field(default="", max_length=1_500)
    completed: str = Field(default="", max_length=1_500)
    readSources: str = Field(default="", max_length=1_500)
    decisions: str = Field(default="", max_length=1_500)
    conclusions: str = Field(default="", max_length=1_500)

    def as_text(self) -> str:
        labels = (
            ("goal", "目标"),
            ("completed", "已完成"),
            ("readSources", "已阅读资料"),
            ("decisions", "选择"),
            ("conclusions", "结论"),
        )
        return "\n".join(
            f"{label}：{getattr(self, name).strip()}" for name, label in labels if getattr(self, name).strip()
        )


def _summary_text(messages: list[ConversationContextMessage]) -> str:
    """确定性抽取式摘要。

    现在只承担两个角色：(1) 未注入 summarizer 可调用（无模型环境、纯单测）时的摘要来源；
    (2) 五维结构化摘要生成失败时的降级出口。保留旧行为是为了让压缩兜底永不因模型不可用而中断。
    """
    parts = []
    for message in messages:
        text = _message_text(message).replace("\n", " ").strip()
        if text:
            parts.append(text[:480])
    return "\n".join(parts)


def _summary_update(
        messages: list[ConversationContextMessage],
        previous: ConversationSummary | None,
        generated: str | None = None) -> dict[str, Any] | None:
    """把未摘要区间合并进既有摘要并产出持久化更新。

    generated 是五维结构化摘要；None 时降级为确定性抽取式（_summary_text），两者共享同一套
    contentHash 去重、version 递增与区间持久化协议，Java 侧无需感知摘要由谁生成。
    """
    if not messages:
        return None
    if generated:
        # 结构化摘要的提示词要求模型把"此前摘要"折叠进新一轮五维文本，因此整体替换；
        # 若继续走下面的追加合并，五维文本会逐轮重复堆叠并被 MAX_SUMMARY_CHARS 截断出新旧混杂的尾部。
        content = generated
        first = previous.summaryFromMessageId if previous else messages[0].messageId
    else:
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
    """LangGraph-owned summary/window preparation for v2 student explanations.

    阈值以下（默认 130k，见 DEFAULT_SUMMARY_TRIGGER_TOKENS 的注释）完全不调模型，保证消息前缀稳定、
    provider prefix cache 常态命中；越过阈值才压缩，压缩优先五维结构化摘要（模型生成、usage 入账），
    模型不可用或输出不合格时回退确定性抽取式摘要。
    """

    def __init__(self, summarizer: ConversationSummarizer | None = None) -> None:
        # summarizer 由 server 装配为 MigratedWorkloadRuntime.chat_messages，使摘要调用走同一
        # provider 路由与 UsageLedger 记账；不传时压缩仍可用，只是退化为旧的确定性摘要。
        self._summarizer = summarizer
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

    def _summarize_if_needed(self, state: _GraphState) -> _GraphState:
        request = state["request"]
        messages = state.get("selected_messages", [])
        counts, _ = count_texts([_message_text(message) for message in messages], state["model"])
        if sum(counts) <= request.limits.summaryTriggerTokens:
            # 常态路径：不改写任何消息、不调模型，前缀缓存保持有效。
            return {"memory_update": None}
        # Retain the newest turn verbatim. Only the unsummarized interval becomes the next summary update.
        older = messages[:-1]
        previous = state.get("summary")
        if previous:
            summary_to = previous.summaryToMessageId
            message_ids = [message.messageId for message in older]
            if summary_to in message_ids:
                older = older[message_ids.index(summary_to) + 1:]
        return {"memory_update": _summary_update(older, previous, self._structured_summary(request, older, previous))}

    def _structured_summary(
            self,
            request: StudentExplanationGraphRequest,
            messages: list[ConversationContextMessage],
            previous: ConversationSummary | None) -> str | None:
        """用 Java 为本次运行签发的 provider route 生成五维结构化摘要。

        返回 None 表示"模型路径不可用"（未注入、调用失败、输出不合合同），调用方随即降级到确定性
        抽取式摘要：压缩只是兜底，不能因模型抖动或配额问题让整次讲题失败。异常在此吞掉但必须落
        warning 日志，否则线上只剩一句降级结果、无法定位 provider 侧原因。
        """
        if self._summarizer is None:
            return None
        turns = [text for text in (_message_text(message) for message in messages) if text]
        if not turns:
            return None
        chat_messages = [
            {"role": "system", "content": SUMMARY_STRUCTURED_SYSTEM_PROMPT},
            {"role": "user", "content": json.dumps({
                "previousSummary": previous.content if previous else "",
                "turns": turns,
            }, ensure_ascii=False, separators=(",", ":"))},
        ]
        try:
            raw = self._summarizer(request.runId, request.providerRoute, chat_messages)
            start, end = raw.find("{"), raw.rfind("}")
            if start < 0 or end <= start:
                return None
            sections = _StructuredSummarySections.model_validate(json.loads(raw[start:end + 1]))
        except Exception as exc:
            logger.warning("student_explanation_structured_summary_degraded runId=%s error=%s",
                           request.runId, type(exc).__name__)
            return None
        return sections.as_text() or None

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
