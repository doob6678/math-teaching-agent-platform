"""Python-owned typed runtimes for migrated non-handout AI workloads."""

from __future__ import annotations

import base64
import hashlib
import json
import logging
import os
import re
import time
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass
from typing import Any, Callable, Literal

import requests
from fastapi import HTTPException
from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from app.ai_run_runtime import ProviderRoute
from app import provider_profiles
from app.model_review_runtime import BoundedModelReviewController, ModelReviewExhausted
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


# 工具目录（老板 2026-09-01："让它主动调用，不要写死在流程中"）：决策轮不再用"问教材就必须检索"
# 这类写死规则，而是把每个工具的能力与适用事实类型如实告诉模型，由模型对照题目自主决定 action/final。
# 描述只陈述工具能取到什么，不指定任何题型的路由；权限 allow-list 仍由 Java availableTools 决定。
REACT_TOOL_CATALOG: dict[str, str] = {
    "search_textbook": "检索已入库高中教材的正文块、章节目录与页码。教材版本、页码、章节结构、"
                       "教材原文的引入/例题/习题等都不在题目里，需要这类外部事实时才能取到。",
    "match_knowledge_graph": "把题目知识点匹配到学科知识图谱主干，用于说明前置、后续与相关知识点关系。",
    "search_teacher_resources": "检索本次运行已授权的教师资料（讲义、题库、课件）正文，"
                                "题目出处、配套练习与教师讲解素材需要这类外部事实时才能取到。",
}


def react_tool_catalog_entries(names: list[str]) -> list[dict[str, str]]:
    """把 Java 签发的工具名映射为带能力描述的清单；未收录的名字不下发描述，避免泄露内部工具。"""
    return [{"name": name, "description": REACT_TOOL_CATALOG.get(name, "")} for name in dict.fromkeys(names)]


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
        """Uses the shared bounded review profile before exposing an intent classification."""
        points = [item.model_dump() for item in request.knowledgePoints]
        controller = BoundedModelReviewController("planner", profile="agent_run")

        def invoke(review_prompt: str, _: int) -> tuple[Any, ProviderResult]:
            content, result = self._call_json(
                request.runId,
                request.providerRoute,
                self._intent_review_messages(request, points, review_prompt),
            )
            try:
                return json.loads(content), result
            except json.JSONDecodeError:
                return None, result

        try:
            parsed, usages, review = controller.execute(
                invoke,
                self._review_prompt,
                lambda candidate: self._validated_intent_candidate(candidate, request.knowledgePoints),
            )
        except ModelReviewExhausted as exc:
            raise HTTPException(status_code=422, detail="MODEL_REVIEW_EXHAUSTED") from exc
        result = usages[-1]
        return {
            "status": "COMPLETED",
            **parsed,
            "usage": result.usage(),
            "providerName": result.provider,
            "modelCode": result.model,
        }

    @staticmethod
    def _intent_review_messages(
            request: IntentRunRequest, points: list[dict[str, Any]], review_prompt: str) -> list[dict[str, Any]]:
        return [
            {"role": "system", "content": (
                "你是学习系统意图分类器。只返回严格 JSON 信封："
                "{\"candidate\":{\"intentCode\":\"LEARNING_PATH|WRONG_QUESTION_REVIEW|MASTERY_STATUS|"
                "TARGETED_EXPLANATION|TARGETED_PRACTICE|QUESTION_RECOMMENDATION|ANSWER_SUBMISSION|UNKNOWN\","
                "\"confidence\":0.0,\"knowledgePointId\":null},"
                "\"review\":{\"approved\":true|false,\"feedbackCodes\":[]}}。"
                "只能选择输入中可见的 knowledgePointId；无法判断返回 UNKNOWN。feedbackCodes 只能是固定策略代码。"
            )},
            {"role": "user", "content": json.dumps({
                "message": request.message, "knowledgePoints": points, "reviewInstruction": review_prompt,
            }, ensure_ascii=False, separators=(",", ":"))},
        ]

    @staticmethod
    def _review_prompt(turn: int, prior: str | None, active_hash: str, codes: tuple[str, ...]) -> str:
        # 与 model_review_runtime 的 4 参契约对齐（turn, prior, active_hash, codes）；
        # 旧 3 参签名会让通用评审循环在第二轮 TypeError。
        if turn == 1:
            return "生成候选分类并完成严格自审。"
        return json.dumps({
            "previousCandidate": prior or "", "baseCandidateHash": active_hash, "feedbackCodes": list(codes),
            "instruction": "仅修正候选以满足字段、授权知识点和固定安全合同。",
        }, ensure_ascii=False, separators=(",", ":"))

    @staticmethod
    def _validated_intent_candidate(
            candidate: Any, knowledge_points: list[AuthorizedKnowledgePoint]) -> dict[str, Any]:
        if not isinstance(candidate, dict):
            raise ValueError("intent candidate must be an object")
        allowed_ids = {item.knowledgePointId for item in knowledge_points}
        intent = str(candidate.get("intentCode") or "UNKNOWN").strip().upper()
        allowed_intents = {
            "LEARNING_PATH", "WRONG_QUESTION_REVIEW", "MASTERY_STATUS", "TARGETED_EXPLANATION",
            "TARGETED_PRACTICE", "QUESTION_RECOMMENDATION", "ANSWER_SUBMISSION", "UNKNOWN",
        }
        if intent not in allowed_intents:
            intent = "UNKNOWN"
        point_id = str(candidate.get("knowledgePointId") or "").strip()
        if point_id not in allowed_ids:
            point_id = ""
        confidence = candidate.get("confidence", 0.0)
        confidence = float(confidence) if isinstance(confidence, (int, float)) else 0.0
        return {
            "intentCode": intent,
            "confidence": min(1.0, max(0.0, confidence)),
            "knowledgePointId": point_id or None,
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
                    "sourceUris 只能来自 evidence；不要输出 Markdown 或推理过程。"
                    # 老板 09-01：讲解范围锁死高中课标，防止小模型顺手用大学方法（极限/洛必达等）跳级
                    "讲解必须限定在高中数学课程范围内，禁止使用大学数学工具（如极限、洛必达法则、多元微积分、线性代数）解题；"
                    "即使题目超纲也要先给高中生能懂的方法，再指出超纲点。"
                    # 思考质量约束：小模型在拼装 JSON 时容易把思考退化成字段名片段，这里要求思考面向讲解本身。
                    "思考时请用连贯完整的中文叙述讲解思路与推导依据；不要在思考中逐字拼装 JSON、复述字段名或输出"
                    "英文碎片，想清内容后直接给出最终 JSON。" + MATH_MARKUP_OUTPUT_CONTRACT
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
                    "题干已经给出全部条件且可通过代数、几何或定义直接完成的题目，必须选择 final，"
                    "不得为了复述通用概念而检索；只有缺少题目所必需的外部事实时才能选择 action。"
                    "对照 availableTools 中每个工具能取到的事实类型自主判断：题目所需的事实在题目之外、"
                    "且某个工具恰好能取到时才调用；检索词写具体知识点名，2-4 个。"
                    "思考时请用连贯完整的中文说明判断依据；不要在思考中逐字拼装 JSON 或输出英文碎片。"
                    + MATH_MARKUP_OUTPUT_CONTRACT
                )},
                {"role": "user", "content": json.dumps({
                    "problem": request.problem,
                    "availableTools": react_tool_catalog_entries(request.availableTools),
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
        reasoning_parts: list[str] = []
        usage: dict[str, Any] = {}
        provider = ""
        model = ""
        provider_attempt = 1
        try:
            try:
                for item in self._stream_call_json(
                        request.runId, request.providerRoute, messages, require_json_object=request.mode == "compose",
                        # 两种模式都把 provider 原始 JSON 增量实时上抛：Java 投影层只提取 title/summary/items
                        # 文本字段，学生不会看到 JSON 语法。此前 compose 吞掉增量导致首字要等整包完成（9 秒级）。
                        emit_visible_content=True):
                    provider = item.get("provider", provider)
                    model = item.get("model", model)
                    provider_attempt = int(item.get("attempt", provider_attempt))
                    if item.get("reasoning"):
                        reasoning_parts.append(str(item["reasoning"]))
                        yield {"event": "delta", "data": {
                            "runId": request.runId,
                            "reasoning": str(item["reasoning"]),
                            "providerName": provider,
                            "modelCode": model,
                        }}
                    if item.get("content"):
                        content_parts.append(str(item["content"]))
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
            # 完整思考轨迹随 completed 事件上抛，Java 截断后写入 ai_draft_json，历史回看不再依赖事件表重放。
            response["reasoningTrace"] = "".join(reasoning_parts)
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
            resolved = provider_profiles.profile(selection.name)
            api_key, base_url = provider_profiles.credentials(selection.name)
            if not api_key:
                failures.append(selection.name + ":configuration")
                continue
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
                    with provider_profiles.open_stream(
                        resolved, self._session, api_key, base_url, payload,
                        float(os.getenv("MATH_AGENT_MIGRATED_RUNTIME_TIMEOUT_SECONDS", "45")),
                    ) as response:
                        response.raise_for_status()
                        completed = False
                        # Anthropic 流被 provider 层翻译成 OpenAI 形状的 data 帧字符串，下游解析保持不变。
                        data_events = provider_profiles.sse_data_lines(resolved, response)
                        for value in data_events:
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
                                delta = choice.get("delta") or {}
                                reasoning = delta.get("reasoning_content")
                                if reasoning:
                                    # 思考增量与正文分离上抛：前端要在"思考与搜索"面板展示完整推理过程，
                                    # 但绝不能混进 content，否则会污染卡片 JSON 流和可见正文。
                                    yield {"provider": selection.name, "model": selection.model, "attempt": attempt, "reasoning": str(reasoning)}
                                text = delta.get("content")
                                if text:
                                    content_parts.append(str(text))
                                    if require_json_object and not "".join(content_parts).lstrip().startswith("{"):
                                        raise ValueError("provider response does not start with a JSON object")
                                    if emit_visible_content:
                                        visible_output = True
                                    yield {"provider": selection.name, "model": selection.model, "attempt": attempt, "content": str(text)}
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
                    error_detail = str(exc)
                except (requests.RequestException, ValueError, KeyError, json.JSONDecodeError) as exc:
                    error = type(exc).__name__
                    retryable = True
                    error_detail = str(exc)
                # 老板 2026-09-01"太慢了"：GLM flash 的 compose JSON 经常不合格，旧逻辑一旦吐过正文
                # 增量就 503 终止整轮、不回退。结构化输出（require_json_object）下学生看到的只是
                # 直播面板的过程文本，终稿由 completed 事件的 cards 整包渲染，半截 JSON 增量不会
                # 进入终稿，因此允许换下一个 provider 重试；自由文本模式仍保持 fail-closed。
                if visible_output and not require_json_object:
                    raise HTTPException(
                        status_code=503,
                        detail="provider stream interrupted after visible output: " + selection.name,
                    )
                failures.append(selection.name + ":" + error)
                # 老板 2026-09-01 验收：GLM flash compose 失败时整条链只有 usage ledger 的 ValueError 字样，
                # 无法区分"通道断/JSON 不合格/超时"，这里把 provider 路由与失败上下文如实落一条结构化日志。
                logger.warning(json.dumps({
                    "event": "provider_attempt_failed",
                    "runId": run_id,
                    "provider": selection.name,
                    "model": selection.model,
                    "attempt": attempt,
                    "error": error,
                    "errorDetail": error_detail[:200],
                    "retryable": retryable,
                    "visibleOutput": visible_output,
                    "requireJson": require_json_object,
                    "contentChars": sum(len(part) for part in content_parts),
                    "route": [item.name for item in [route.primary, *route.fallbacks]],
                }, ensure_ascii=False, sort_keys=True))
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
                "不要输出推理过程或 Markdown。题干已提供全部条件且可用代数、几何或定义直接完成时，"
                "必须返回 final；不得仅为讲解通用概念而调用检索。"
                + MATH_MARKUP_OUTPUT_CONTRACT
            )},
            {"role": "user", "content": json.dumps({
                "problem": request.problem,
                "availableTools": react_tool_catalog_entries(available_tools),
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
        """Reviews only the pre-publication card JSON; streaming remains outside this correction path."""
        sources = [item.model_dump() for item in request.evidence]
        controller = BoundedModelReviewController("explanation_writer", profile="student_explanation")

        def invoke(review_prompt: str, _: int) -> tuple[Any, ProviderResult]:
            content, result = self._call_json(
                request.runId,
                request.providerRoute,
                self._compose_review_messages(request, sources, review_prompt),
            )
            try:
                return json.loads(content), result
            except json.JSONDecodeError:
                return None, result

        try:
            cards, usages, review = controller.execute(
                invoke,
                self._review_prompt,
                lambda candidate: self._normalize_explanation_cards(self._candidate_object(candidate), request.evidence),
            )
        except ModelReviewExhausted as exc:
            raise HTTPException(status_code=422, detail="MODEL_REVIEW_EXHAUSTED") from exc
        result = usages[-1]
        return {
            "status": "COMPLETED",
            **cards,
            "usage": result.usage(),
            "providerName": result.provider,
            "modelCode": result.model,
        }

    @staticmethod
    def _candidate_object(candidate: Any) -> dict[str, Any]:
        if not isinstance(candidate, dict):
            raise ValueError("student explanation candidate must be an object")
        return candidate

    @staticmethod
    def _compose_review_messages(
            request: StudentExplanationRunRequest, sources: list[dict[str, Any]], review_prompt: str) -> list[dict[str, Any]]:
        messages: list[dict[str, Any]] = [
            {"role": "system", "content": (
                "你是高中数学教师。讲解必须限定在高中数学课程范围内，禁止使用大学数学工具（如极限、洛必达法则、多元微积分、线性代数）解题。"
                "只返回严格 JSON 信封："
                "{\"candidate\":{\"conversationTitle\":\"不超过15个中文字符\",\"cards\":[{\"cardKey\":\"stable_snake_case\","
                "\"title\":\"\",\"summary\":\"简明中文讲解\",\"items\":[],\"sourceUris\":[],"
                "\"renderMode\":\"text|formula|source_list\"}]},"
                "\"review\":{\"approved\":true|false,\"feedbackCodes\":[]}}。"
                "只能引用输入 evidence 的 sourceUri，不得暴露推理过程。feedbackCodes 只能使用固定策略代码。"
                + MATH_MARKUP_OUTPUT_CONTRACT
            )},
            {"role": "user", "content": json.dumps({
                "problem": request.problem, "evidence": sources, "reviewInstruction": review_prompt,
            }, ensure_ascii=False, separators=(",", ":"))},
        ]
        if request.imageDataUrl:
            messages[-1] = {"role": "user", "content": [
                {"type": "text", "text": messages[-1]["content"]},
                {"type": "image_url", "image_url": {"url": request.imageDataUrl}},
            ]}
        return messages

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
        selections = [request.providerRoute.primary, *request.providerRoute.fallbacks]

        def probe(selection) -> dict[str, Any]:
            started = time.monotonic()
            try:
                # 探测消息必须包含 “json” 一词：_call_one 统一要求 response_format=json_object，
                # OpenAI 兼容网关（含 DeepSeek）会校验消息里出现 json，否则 400，导致健康检查永远失败。
                result = self._call_one(request.runId, selection.name, selection.model, 1,
                                        [{"role": "user", "content": 'Health check: reply with the json object {"ok": true} only.'}])
                return {"providerName": selection.name, "modelCode": selection.model, "configured": True,
                        "available": True, "statusCode": 200, "elapsedMs": round((time.monotonic() - started) * 1000),
                        "message": "Provider answered the health check.", "usage": result.usage()}
            except HTTPException as exc:
                return {"providerName": selection.name, "modelCode": selection.model, "configured": True,
                        "available": False, "statusCode": exc.status_code, "elapsedMs": round((time.monotonic() - started) * 1000),
                        "message": "Provider health check failed."}

        # 串行探测的耗时 = 各 provider 之和（主模型 + fallback 逐个真实调用，实测 7.5s+），
        # 控制台“检查”按钮因此极慢。探测语义不变、仍是每家真实调用一次，仅并发执行：
        # 总耗时收敛为最慢的一家。UsageLedger.append 与 requests/urllib3 连接池均线程安全。
        with ThreadPoolExecutor(max_workers=max(1, len(selections))) as pool:
            results = list(pool.map(probe, selections))
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
        api_key, base_url = provider_profiles.credentials(provider)
        if not api_key:
            raise HTTPException(status_code=503, detail="provider API key is unavailable")
        request_payload = {
            "model": model,
            "messages": messages,
            "temperature": 0,
            # Compose is consumed by a strict card schema below.  Ask every compatible provider for a
            # JSON object here as well as in the streaming route, otherwise a valid prose answer would
            # be rejected only after the paid model call has completed.  GLM has no response_format
            # concept; the provider layer's Anthropic conversion simply ignores this field.
            "response_format": {"type": "json_object"},
        }
        try:
            data = provider_profiles.post_completion(
                provider_profiles.profile(provider), self._session, api_key, base_url, request_payload,
                float(os.getenv("MATH_AGENT_MIGRATED_RUNTIME_TIMEOUT_SECONDS", "45")),
            )
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
