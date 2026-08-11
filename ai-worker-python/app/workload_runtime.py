"""Python-owned typed runtimes for migrated non-handout AI workloads."""

from __future__ import annotations

import base64
import hashlib
import json
import logging
import os
import re
import time
from dataclasses import dataclass
from typing import Any, Callable, Literal

import requests
from fastapi import HTTPException
from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from app.ai_run_runtime import ProviderRoute
from app.sse import iter_sse_data_events
from app.usage import UsageEvent, UsageLedger, cost_for, fallback_tokens


MAX_SOURCE_COUNT = 24
MAX_IMAGE_BYTES = 8 * 1024 * 1024
MAX_SSE_FRAME_PREFIX_LENGTH = 96
# All three explanation entry points share this visible-content contract.  Keeping it in the model instruction
# rather than adding another post-processing normalizer lets the model retain the intended mathematical structure.
MATH_MARKUP_OUTPUT_CONTRACT = (
    "数学排版是硬性输出合同：conversationTitle、每张卡片的 title、summary 与 items 中，只要出现变量、"
    "函数、集合、区间、方程、不等式、分式、根式、角度或运算式，就必须将完整表达式放入 $...$；"
    "例如标题写“函数 $f(x)$ 的定义域”，不得写“函数 f(x) 的定义域”。"
    "分式一律写 $\\frac{分子}{分母}$，根式一律写 $\\sqrt{被开方整体}$；不得用 /、√、^、上标字符"
    "或裸露数学符号代替 LaTeX 结构。不要在数学公式定界符外拆开一个表达式。"
)
logger = logging.getLogger(__name__)


def redacted_sse_frame_prefix(value: str) -> str:
    normalized = " ".join(value.split())
    normalized = re.sub(r"(?i)(bearer\s+)[^\s,;]+", r"\1<redacted>", normalized)
    normalized = re.sub(
        r"(?i)((?:api[_-]?key|apikey|token|authorization|password|secret|signature)\s*[=:]\s*)[^\s,;&]+",
        r"\1<redacted>", normalized,
    )
    return normalized[:MAX_SSE_FRAME_PREFIX_LENGTH]


class AuthorizedKnowledgePoint(BaseModel):
    """Java 已授权的可见知识点，不携带用户或租户身份。"""

    model_config = ConfigDict(extra="forbid")

    knowledgePointId: str = Field(min_length=1, max_length=160)
    knowledgePointName: str = Field(min_length=1, max_length=240)


class IntentRunRequest(BaseModel):
    """学习意图分类的受限跨语言合同。"""

    model_config = ConfigDict(extra="forbid")

    runId: str = Field(min_length=1, max_length=128)
    message: str = Field(min_length=1, max_length=4_000)
    knowledgePoints: list[AuthorizedKnowledgePoint] = Field(default_factory=list, max_length=MAX_SOURCE_COUNT)
    providerRoute: ProviderRoute

    @model_validator(mode="after")
    def validate_route_grant(self) -> "IntentRunRequest":
        self.providerRoute.verify_for(self.runId, "learning_intent")
        return self


class ExplanationEvidence(BaseModel):
    """经过 Java 资源授权后的解释证据。"""

    model_config = ConfigDict(extra="forbid")

    sourceUri: str = Field(min_length=1, max_length=320)
    title: str = Field(default="", max_length=400)
    snippet: str = Field(default="", max_length=1_600)


class StudentExplanationRunRequest(BaseModel):
    """学生解释模型合同；检索和引用授权仍由 Java 完成。"""

    model_config = ConfigDict(extra="forbid")

    runId: str = Field(min_length=1, max_length=128)
    mode: Literal["react", "compose"] = "compose"
    problem: str = Field(min_length=1, max_length=8_000)
    evidence: list[ExplanationEvidence] = Field(default_factory=list, max_length=MAX_SOURCE_COUNT)
    availableTools: list[Literal["search_textbook", "match_knowledge_graph", "search_teacher_resources"]] = Field(
        default_factory=list,
        max_length=3,
    )
    observations: list[str] = Field(default_factory=list, max_length=12)
    imageDataUrl: str = Field(default="", max_length=12_000_000)
    providerRoute: ProviderRoute

    @field_validator("observations")
    @classmethod
    def normalize_observations(cls, value: list[str]) -> list[str]:
        return [item.strip()[:800] for item in value if item and item.strip()]

    @model_validator(mode="after")
    def validate_route_grant(self) -> "StudentExplanationRunRequest":
        self.providerRoute.verify_for(self.runId, "student_explanation")
        return self


class ImageTranscriptionRunRequest(BaseModel):
    """授权图片转写合同，不接受文件路径或远程 URL。"""

    model_config = ConfigDict(extra="forbid")

    runId: str = Field(min_length=1, max_length=128)
    mimeType: str = Field(pattern=r"^image/[a-zA-Z0-9.+-]+$")
    imageDataUrl: str = Field(min_length=32, max_length=12_000_000)
    providerRoute: ProviderRoute

    @field_validator("imageDataUrl")
    @classmethod
    def validate_image_data(cls, value: str) -> str:
        prefix, separator, encoded = value.partition(",")
        if not separator or not prefix.startswith("data:image/") or ";base64" not in prefix:
            raise ValueError("imageDataUrl must be a base64 image data URL")
        try:
            decoded = base64.b64decode(encoded, validate=True)
        except ValueError as exc:
            raise ValueError("imageDataUrl contains invalid base64") from exc
        if len(decoded) > MAX_IMAGE_BYTES:
            raise ValueError("imageDataUrl exceeds the image byte limit")
        return value

    @model_validator(mode="after")
    def validate_route_grant(self) -> "ImageTranscriptionRunRequest":
        self.providerRoute.verify_for(self.runId, "image_transcription")
        return self


class ProviderHealthRunRequest(BaseModel):
    """只返回脱敏 provider 可达性，不暴露端点、密钥或原始错误体。"""

    model_config = ConfigDict(extra="forbid")

    runId: str = Field(min_length=1, max_length=128)
    providerRoute: ProviderRoute

    @model_validator(mode="after")
    def validate_route_grant(self) -> "ProviderHealthRunRequest":
        self.providerRoute.verify_for(self.runId, "provider_health")
        return self


@dataclass(frozen=True)
class ProviderResult:
    provider: str
    model: str
    content: str
    prompt_tokens: int
    completion_tokens: int
    total_tokens: int
    estimated_cost: float

    def usage(self) -> dict[str, int | float]:
        return {
            "promptTokens": self.prompt_tokens,
            "completionTokens": self.completion_tokens,
            "totalTokens": self.total_tokens,
            "estimatedCost": self.estimated_cost,
        }


class MigratedWorkloadRuntime:
    """统一拥有 provider 调用、有限路由、结构化结果和用量记录。"""

    def __init__(self) -> None:
        self._session = requests.Session()
        self._ledger = UsageLedger()

    def recognize_intent(self, request: IntentRunRequest) -> dict[str, Any]:
        points = [item.model_dump() for item in request.knowledgePoints]
        content, result = self._call_json(
            request.runId,
            request.providerRoute,
            [
                {"role": "system", "content": (
                    "你是学习系统意图分类器。只返回 JSON："
                    "{\"intentCode\":\"LEARNING_PATH|WRONG_QUESTION_REVIEW|MASTERY_STATUS|TARGETED_EXPLANATION|"
                    "TARGETED_PRACTICE|QUESTION_RECOMMENDATION|ANSWER_SUBMISSION|UNKNOWN\","
                    "\"confidence\":0.0,\"knowledgePointId\":null}。"
                    "只能选择输入中可见的 knowledgePointId；无法判断返回 UNKNOWN。"
                )},
                {"role": "user", "content": json.dumps({"message": request.message, "knowledgePoints": points}, ensure_ascii=False)},
            ],
        )
        parsed = self._json_object(content)
        allowed_ids = {item.knowledgePointId for item in request.knowledgePoints}
        intent = str(parsed.get("intentCode") or "UNKNOWN").strip().upper()
        allowed_intents = {
            "LEARNING_PATH", "WRONG_QUESTION_REVIEW", "MASTERY_STATUS", "TARGETED_EXPLANATION",
            "TARGETED_PRACTICE", "QUESTION_RECOMMENDATION", "ANSWER_SUBMISSION", "UNKNOWN",
        }
        if intent not in allowed_intents:
            intent = "UNKNOWN"
        point_id = str(parsed.get("knowledgePointId") or "").strip()
        if point_id not in allowed_ids:
            point_id = ""
        confidence = parsed.get("confidence", 0.0)
        confidence = float(confidence) if isinstance(confidence, (int, float)) else 0.0
        return {
            "status": "COMPLETED",
            "intentCode": intent,
            "confidence": min(1.0, max(0.0, confidence)),
            "knowledgePointId": point_id or None,
            "usage": result.usage(),
            "providerName": result.provider,
            "modelCode": result.model,
        }

    def explain_student_problem(self, request: StudentExplanationRunRequest) -> dict[str, Any]:
        if request.mode == "react":
            return self._react_student_explanation(request)
        return self._compose_student_explanation(request)

    def stream_student_explanation(self, request: StudentExplanationRunRequest):
        """流式返回 ReAct provider delta；完整 JSON 仍在末尾经过同一张卡片校验。"""
        if request.mode == "compose":
            messages: list[dict[str, Any]] = [
                {"role": "system", "content": (
                    "你是高中数学教师。只返回 JSON："
                    "{\"conversationTitle\":\"不超过15个中文字符\",\"cards\":[{\"cardKey\":\"stable_snake_case\","
                    "\"title\":\"\",\"summary\":\"简明中文讲解\",\"items\":[],\"sourceUris\":[],"
                    "\"renderMode\":\"text|formula|source_list\"}]}。"
                    "sourceUris 只能来自 evidence；不要输出 Markdown 或推理过程。" + MATH_MARKUP_OUTPUT_CONTRACT
                )},
                {"role": "user", "content": json.dumps({
                    "problem": request.problem,
                    "evidence": [item.model_dump() for item in request.evidence],
                }, ensure_ascii=False)},
            ]
        else:
            messages = [
                {"role": "system", "content": (
                    "你是高中数学讲解的受限 ReAct 规划器。只返回 JSON："
                    "{\"decision\":\"action|final\",\"tools\":[],\"queries\":[],"
                    "\"conversationTitle\":\"\",\"cards\":[]}。"
                    "final 必须同时返回 cards，引用只能来自 evidence。不要输出推理过程或 Markdown。"
                    + MATH_MARKUP_OUTPUT_CONTRACT
                )},
                {"role": "user", "content": json.dumps({
                    "problem": request.problem,
                    "availableTools": list(dict.fromkeys(request.availableTools)),
                    "observations": request.observations,
                    "evidence": [item.model_dump() for item in request.evidence],
                }, ensure_ascii=False)},
            ]
        if request.imageDataUrl:
            messages[-1] = {"role": "user", "content": [
                {"type": "text", "text": messages[-1]["content"]},
                {"type": "image_url", "image_url": {"url": request.imageDataUrl}},
            ]}
        yield {"event": "started", "data": {"runId": request.runId}}
        content_parts: list[str] = []
        usage: dict[str, Any] = {}
        provider = ""
        model = ""
        provider_attempt = 1
        try:
            try:
                for item in self._stream_call_json(
                        request.runId, request.providerRoute, messages, require_json_object=request.mode == "compose",
                        emit_visible_content=request.mode != "compose"):
                    provider = item.get("provider", provider)
                    model = item.get("model", model)
                    provider_attempt = int(item.get("attempt", provider_attempt))
                    if item.get("content"):
                        content_parts.append(str(item["content"]))
                        if request.mode != "compose":
                            yield {"event": "delta", "data": {
                                "runId": request.runId,
                                "content": str(item["content"]),
                                "providerName": provider,
                                "modelCode": model,
                            }}
                    usage = item.get("usage") or usage
            except HTTPException as exc:
                raw = "".join(content_parts)
                if not str(exc.detail).startswith("provider stream interrupted after visible output:"):
                    raise
                # Relays occasionally omit the terminal [DONE] after a complete JSON payload. Reuse it only when
                # the existing JSON and card/citation validators can still establish a complete safe result.
                self._json_object(raw)
            raw = "".join(content_parts)
            parsed = self._json_object(raw)
            result = self._result_from_stream(request.runId, provider, model, usage, messages, raw, provider_attempt)
            decision = "final" if request.mode == "compose" else str(parsed.get("decision") or "final").strip().lower()
            if decision == "final":
                response = {"status": "COMPLETED", "decision": "final", "tools": [], "queries": [],
                            **self._normalize_explanation_cards(parsed, request.evidence),
                            "usage": result.usage(), "providerName": provider, "modelCode": model}
            else:
                allowed = list(dict.fromkeys(request.availableTools))
                tools = [str(value) for value in parsed.get("tools", []) if str(value) in allowed][:3]
                queries = list(dict.fromkeys([str(value).strip()[:80] for value in parsed.get("queries", []) if str(value).strip()]))[:6]
                response = {"status": "COMPLETED", "decision": "action" if tools else "final",
                            "tools": tools, "queries": queries, "usage": result.usage(),
                            "providerName": provider, "modelCode": model}
            yield {"event": "completed", "data": {"runId": request.runId, **response}}
        except HTTPException as exc:
            yield {"event": "error", "data": {"runId": request.runId, "status": exc.status_code, "message": str(exc.detail)}}

    def _stream_call_json(
            self, run_id: str, route: ProviderRoute, messages: list[dict[str, Any]], require_json_object: bool = False,
            emit_visible_content: bool = True):
        failures = []
        provider_attempts = max(1, int(os.getenv("MATH_AGENT_STUDENT_EXPLANATION_MODEL_ATTEMPTS", "2")))
        retry_backoff_seconds = max(0.0, float(os.getenv("MATH_AGENT_STUDENT_EXPLANATION_RETRY_BACKOFF_SECONDS", "1.0")))
        for provider_index, selection in enumerate([route.primary, *route.fallbacks]):
            key_name = {"openai": "OPENAI_API_KEY", "dashscope": "DASHSCOPE_API_KEY", "deepseek": "DEEPSEEK_API_KEY", "ark": "ARK_API_KEY"}[selection.name]
            api_key = os.getenv(key_name)
            if not api_key:
                failures.append(selection.name + ":configuration")
                continue
            bases = {"openai": os.getenv("OPENAI_BASE_URL", "https://api.openai.com/v1"), "dashscope": "https://dashscope.aliyuncs.com/compatible-mode/v1", "deepseek": "https://api.deepseek.com/v1", "ark": "https://ark.cn-beijing.volces.com/api/v3"}
            payload = {
                "model": selection.model,
                "messages": messages,
                "temperature": 0,
                "stream": True,
                "stream_options": {"include_usage": True},
            }
            if require_json_object:
                payload["response_format"] = {"type": "json_object"}
            for provider_try in range(provider_attempts):
                attempt = provider_index * provider_attempts + provider_try + 1
                visible_output = False
                content_parts: list[str] = []
                try:
                    with self._session.post(
                        bases[selection.name].rstrip() + "/chat/completions",
                        headers={"Authorization": f"Bearer {api_key}", "Content-Type": "application/json"},
                        json=payload,
                        stream=True, timeout=float(os.getenv("MATH_AGENT_MIGRATED_RUNTIME_TIMEOUT_SECONDS", "45")),
                    ) as response:
                        response.raise_for_status()
                        completed = False
                        for value in iter_sse_data_events(response):
                            if value == "[DONE]":
                                completed = True
                                break
                            try:
                                decoded = json.loads(value)
                            except json.JSONDecodeError as exc:
                                if value.lower() in {"ping", "keep-alive"}:
                                    continue
                                logger.warning(json.dumps({
                                    "event": "provider_sse_non_json_frame",
                                    "runId": run_id,
                                    "phase": "student_explanation_stream",
                                    "provider": selection.name,
                                    "model": selection.model,
                                    "attempt": attempt,
                                    "httpStatus": getattr(response, "status_code", 0),
                                    "contentType": getattr(response, "headers", {}).get("Content-Type", ""),
                                    "requestId": getattr(response, "headers", {}).get("X-Request-ID", getattr(response, "headers", {}).get("Request-ID", "")),
                                    "frameLength": len(value),
                                    "jsonError": exc.msg,
                                    "jsonErrorPosition": exc.pos,
                                    "frameSha256": hashlib.sha256(value.encode("utf-8")).hexdigest(),
                                    "framePrefix": redacted_sse_frame_prefix(value),
                                }, ensure_ascii=False, sort_keys=True))
                                raise
                            usage = decoded.get("usage") or {}
                            if usage:
                                yield {"provider": selection.name, "model": selection.model, "attempt": attempt, "usage": usage}
                            for choice in decoded.get("choices") or []:
                                delta = (choice.get("delta") or {}).get("content")
                                if delta:
                                    content_parts.append(str(delta))
                                    if require_json_object and not "".join(content_parts).lstrip().startswith("{"):
                                        raise ValueError("provider response does not start with a JSON object")
                                    if emit_visible_content:
                                        visible_output = True
                                    yield {"provider": selection.name, "model": selection.model, "attempt": attempt, "content": str(delta)}
                        if require_json_object:
                            try:
                                self._json_object("".join(content_parts))
                            except HTTPException as exc:
                                raise ValueError("provider response is not a JSON object") from exc
                            # Compatible relays occasionally omit [DONE] after a complete structured payload.
                            return
                        if not completed:
                            raise requests.RequestException("provider stream ended before [DONE]")
                        return
                except requests.HTTPError as exc:
                    status = exc.response.status_code if exc.response is not None else 0
                    error = f"HTTP_{status}"
                    retryable = status == 429 or status >= 500
                except (requests.RequestException, ValueError, KeyError, json.JSONDecodeError) as exc:
                    error = type(exc).__name__
                    retryable = True
                if visible_output:
                    raise HTTPException(
                        status_code=503,
                        detail="provider stream interrupted after visible output: " + selection.name,
                    )
                failures.append(selection.name + ":" + error)
                self._ledger.append(UsageEvent(
                    run_id, selection.name, selection.model, attempt, "FAILED", 0, 0, 0, -1.0,
                    "unavailable", error,
                ))
                if not retryable or provider_try + 1 >= provider_attempts:
                    break
                time.sleep(retry_backoff_seconds * (provider_try + 1))
        raise HTTPException(status_code=503, detail="all configured providers failed: " + ",".join(failures))

    def _result_from_stream(
            self, run_id: str, provider: str, model: str, raw_usage: dict[str, Any], messages: list[dict[str, Any]],
            content: str, attempt: int = 1) -> ProviderResult:
        prompt = int(raw_usage.get("prompt_tokens", 0) or 0)
        completion = int(raw_usage.get("completion_tokens", 0) or 0)
        total = int(raw_usage.get("total_tokens", 0) or 0)
        if total <= 0:
            prompt, completion, total = fallback_tokens(messages, content)
        cost = cost_for(provider, model, prompt, completion)
        self._ledger.append(UsageEvent(run_id, provider, model, attempt, "SUCCESS", prompt, completion, total, cost, "provider" if raw_usage else "fallback"))
        return ProviderResult(provider, model, content, prompt, completion, total, cost)

    def _react_student_explanation(self, request: StudentExplanationRunRequest) -> dict[str, Any]:
        available_tools = list(dict.fromkeys(request.availableTools))
        messages: list[dict[str, Any]] = [
            {"role": "system", "content": (
                "你是高中数学讲解的受限 ReAct 规划器。只返回 JSON："
                "{\"decision\":\"action|final\",\"tools\":[\"search_textbook|match_knowledge_graph|search_teacher_resources\"],"
                "\"queries\":[\"短检索词\"]}。"
                "只有在确实需要已授权资料时才选 action；tools 只能来自 availableTools，queries 最多 6 个。"
                "若题目自洽则返回 final，且 tools 与 queries 为空，并同时返回 "
                "conversationTitle 和 cards。cards 使用与 compose 相同的字段，sourceUris 只能来自 evidence。"
                "不要输出推理过程或 Markdown。"
                + MATH_MARKUP_OUTPUT_CONTRACT
            )},
            {"role": "user", "content": json.dumps({
                "problem": request.problem,
                "availableTools": available_tools,
                "observations": request.observations,
                "evidence": [item.model_dump() for item in request.evidence],
            }, ensure_ascii=False)},
        ]
        if request.imageDataUrl:
            messages[-1] = {"role": "user", "content": [
                {"type": "text", "text": messages[-1]["content"]},
                {"type": "image_url", "image_url": {"url": request.imageDataUrl}},
            ]}
        content, result = self._call_json(request.runId, request.providerRoute, messages)
        parsed = self._json_object(content)
        decision = str(parsed.get("decision") or "final").strip().lower()
        tools = parsed.get("tools") if isinstance(parsed.get("tools"), list) else []
        safe_tools = [str(tool) for tool in tools if str(tool) in available_tools][:3]
        queries = parsed.get("queries") if isinstance(parsed.get("queries"), list) else []
        safe_queries = []
        for raw in queries:
            query = str(raw).strip()[:80]
            if query and query not in safe_queries:
                safe_queries.append(query)
            if len(safe_queries) == 6:
                break
        if decision == "final":
            try:
                final_payload = self._normalize_explanation_cards(parsed, request.evidence)
            except HTTPException:
                # A planner-only final lets Java continue through its validated compose fallback.
                final_payload = {"conversationTitle": "", "cards": []}
            return {
                "status": "COMPLETED",
                "decision": "final",
                "tools": [],
                "queries": [],
                **final_payload,
                "usage": result.usage(),
                "providerName": result.provider,
                "modelCode": result.model,
            }
        if not safe_tools:
            return {
                "status": "COMPLETED",
                "decision": "final",
                "tools": [],
                "queries": [],
                "usage": result.usage(),
                "providerName": result.provider,
                "modelCode": result.model,
            }
        return {
            "status": "COMPLETED",
            "decision": "action",
            "tools": safe_tools,
            "queries": safe_queries,
            "usage": result.usage(),
            "providerName": result.provider,
            "modelCode": result.model,
        }

    def _compose_student_explanation(self, request: StudentExplanationRunRequest) -> dict[str, Any]:
        sources = [item.model_dump() for item in request.evidence]
        messages: list[dict[str, Any]] = [
            {"role": "system", "content": (
                "你是高中数学教师。只返回 JSON："
                "{\"conversationTitle\":\"不超过15个中文字符\",\"cards\":[{\"cardKey\":\"stable_snake_case\","
                "\"title\":\"\",\"summary\":\"简明中文讲解\",\"items\":[],\"sourceUris\":[],"
                "\"renderMode\":\"text|formula|source_list\"}]}。"
                "只能引用输入 evidence 的 sourceUri，不得暴露推理过程。" + MATH_MARKUP_OUTPUT_CONTRACT
            )},
            {"role": "user", "content": json.dumps({"problem": request.problem, "evidence": sources}, ensure_ascii=False)},
        ]
        if request.imageDataUrl:
            messages[-1] = {"role": "user", "content": [
                {"type": "text", "text": messages[-1]["content"]},
                {"type": "image_url", "image_url": {"url": request.imageDataUrl}},
            ]}
        content, result = self._call_json(request.runId, request.providerRoute, messages)
        parsed = self._json_object(content)
        return {
            "status": "COMPLETED",
            **self._normalize_explanation_cards(parsed, request.evidence),
            "usage": result.usage(),
            "providerName": result.provider,
            "modelCode": result.model,
        }

    @staticmethod
    def _normalize_explanation_cards(
            parsed: dict[str, Any], evidence: list[ExplanationEvidence]) -> dict[str, Any]:
        cards = parsed.get("cards") if isinstance(parsed.get("cards"), list) else []
        allowed_sources = {item.sourceUri for item in evidence}
        normalized_cards = []
        for raw in cards[:12]:
            if not isinstance(raw, dict):
                continue
            source_uris = raw.get("sourceUris") if isinstance(raw.get("sourceUris"), list) else []
            safe_uris = [str(uri) for uri in source_uris if str(uri) in allowed_sources][:MAX_SOURCE_COUNT]
            summary = str(raw.get("summary") or "").strip()[:8_000]
            if not summary:
                continue
            normalized_cards.append({
                "cardKey": str(raw.get("cardKey") or "explanation").strip()[:80],
                "title": str(raw.get("title") or "").strip()[:160],
                "summary": summary,
                "items": [str(item).strip()[:800] for item in raw.get("items", []) if isinstance(item, str) and item.strip()][:16],
                "sourceUris": safe_uris,
                "renderMode": str(raw.get("renderMode") or "text") if str(raw.get("renderMode") or "text") in {"text", "formula", "source_list"} else "text",
            })
        if not normalized_cards:
            raise HTTPException(status_code=422, detail="student explanation response did not contain valid cards")
        return {
            "conversationTitle": str(parsed.get("conversationTitle") or "数学讲解").strip()[:80],
            "cards": normalized_cards,
        }

    def transcribe_image(self, request: ImageTranscriptionRunRequest) -> dict[str, Any]:
        content, result = self._call_json(
            request.runId,
            request.providerRoute,
            [
                {"role": "system", "content": (
                    "读取高中数学题图片，只提取可见题干、公式、选项和图形标记，不解题、不猜测。"
                    "只返回 JSON：{\"problemText\":\"\",\"confidence\":0.0}。"
                    "数学分数必须使用 \\frac{分子}{分母}。"
                )},
                {"role": "user", "content": [
                    {"type": "image_url", "image_url": {"url": request.imageDataUrl}},
                    {"type": "text", "text": "提取可见数学题文本。"},
                ]},
            ],
        )
        parsed = self._json_object(content)
        problem = str(parsed.get("problemText") or "").strip()[:16_000]
        confidence = parsed.get("confidence", 0.0)
        confidence = float(confidence) if isinstance(confidence, (int, float)) else 0.0
        return {
            "status": "COMPLETED" if problem else "FAILED",
            "problemText": problem,
            "confidence": min(1.0, max(0.0, confidence)),
            "usage": result.usage(),
            "providerName": result.provider,
            "modelCode": result.model,
        }

    def provider_health(self, request: ProviderHealthRunRequest) -> dict[str, Any]:
        results = []
        for selection in [request.providerRoute.primary, *request.providerRoute.fallbacks]:
            started = time.monotonic()
            try:
                result = self._call_one(request.runId, selection.name, selection.model, 1, [{"role": "user", "content": "health-check"}])
                results.append({"providerName": selection.name, "modelCode": selection.model, "configured": True,
                                "available": True, "statusCode": 200, "elapsedMs": round((time.monotonic() - started) * 1000),
                                "message": "Provider answered the health check.", "usage": result.usage()})
            except HTTPException as exc:
                results.append({"providerName": selection.name, "modelCode": selection.model, "configured": True,
                                "available": False, "statusCode": exc.status_code, "elapsedMs": round((time.monotonic() - started) * 1000),
                                "message": "Provider health check failed."})
        return {"status": "COMPLETED", "results": results}

    def _call_json(self, run_id: str, route: ProviderRoute, messages: list[dict[str, Any]]) -> tuple[str, ProviderResult]:
        failures: list[str] = []
        for attempt, selection in enumerate([route.primary, *route.fallbacks], 1):
            try:
                result = self._call_one(run_id, selection.name, selection.model, attempt, messages)
                return result.content, result
            except HTTPException as exc:
                failures.append(f"{selection.name}:{exc.status_code}")
        raise HTTPException(status_code=503, detail="all configured providers failed: " + ",".join(failures))

    def _call_one(self, run_id: str, provider: str, model: str, attempt: int, messages: list[dict[str, Any]]) -> ProviderResult:
        key_name = {"openai": "OPENAI_API_KEY", "dashscope": "DASHSCOPE_API_KEY", "deepseek": "DEEPSEEK_API_KEY", "ark": "ARK_API_KEY"}[provider]
        api_key = os.getenv(key_name)
        if not api_key:
            raise HTTPException(status_code=503, detail="provider API key is unavailable")
        defaults = {
            "openai": os.getenv("OPENAI_BASE_URL", "https://api.openai.com/v1"),
            "dashscope": "https://dashscope.aliyuncs.com/compatible-mode/v1",
            "deepseek": "https://api.deepseek.com/v1",
            "ark": "https://ark.cn-beijing.volces.com/api/v3",
        }
        try:
            response = self._session.post(
                defaults[provider].rstrip("/") + "/chat/completions",
                headers={"Authorization": f"Bearer {api_key}", "Content-Type": "application/json"},
                json={"model": model, "messages": messages, "temperature": 0},
                timeout=float(os.getenv("MATH_AGENT_MIGRATED_RUNTIME_TIMEOUT_SECONDS", "45")),
            )
            response.raise_for_status()
            data = response.json()
            content = str(data["choices"][0]["message"].get("content") or "")
            usage = data.get("usage") or {}
            prompt = int(usage.get("prompt_tokens", 0) or 0)
            completion = int(usage.get("completion_tokens", 0) or 0)
            total = int(usage.get("total_tokens", 0) or 0)
            source = "provider"
            if total <= 0:
                prompt, completion, total = fallback_tokens(messages, content)
                source = "fallback"
            cost = cost_for(provider, model, prompt, completion)
            self._ledger.append(UsageEvent(run_id, provider, model, attempt, "SUCCESS", prompt, completion, total, cost, source))
            return ProviderResult(provider, model, content, prompt, completion, total, cost)
        except (KeyError, ValueError, requests.RequestException) as exc:
            self._ledger.append(UsageEvent(run_id, provider, model, attempt, "FAILED", 0, 0, 0, -1.0, "unavailable", type(exc).__name__))
            raise HTTPException(status_code=503, detail="provider call failed") from exc

    @staticmethod
    def _json_object(content: str) -> dict[str, Any]:
        start, end = content.find("{"), content.rfind("}")
        if start < 0 or end <= start:
            raise HTTPException(status_code=422, detail="model response is not a JSON object")
        try:
            value = json.loads(content[start:end + 1])
        except json.JSONDecodeError as exc:
            raise HTTPException(status_code=422, detail="model response JSON is invalid") from exc
        if not isinstance(value, dict):
            raise HTTPException(status_code=422, detail="model response is not a JSON object")
        return value
