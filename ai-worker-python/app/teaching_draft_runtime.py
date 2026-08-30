"""Python-owned teaching draft runtime.

The runtime deliberately receives only bounded, Java-authorized evidence summaries.  It owns the
provider call, JSON contract, retry decision and usage ledger, while Java keeps identity, source
authorization, task persistence and PDF publication.  This is a separate contract from the larger
LangGraph handout graph because the legacy teaching-task workflow still owns its deterministic renderer.
"""

from __future__ import annotations

import json
import os
import re
import time
from datetime import datetime, timezone
from dataclasses import dataclass
from typing import Any

import requests
from fastapi import HTTPException
from pydantic import BaseModel, ConfigDict, Field

from app import anthropic_compat
from app.model_review_runtime import BoundedModelReviewController, ModelReviewExhausted, ModelReviewMetadata
from app.usage import UsageEvent, UsageLedger, cost_for, fallback_tokens


DEFAULT_CONTRACT_VERSION = "teaching-ai-v1"
DEFAULT_MAX_RETRIES = 1
DEFAULT_TIMEOUT_SECONDS = 420.0
DEFAULT_MAX_EVIDENCE = 24
DEFAULT_MAX_EVIDENCE_CHARS = 1_200
DEFAULT_MAX_INPUT_CHARS = 4_000
DEFAULT_MAX_OUTPUT_CHARS = 28_000
DEFAULT_MAX_LIST_ITEMS = 16
DEFAULT_MAX_LIST_ITEM_CHARS = 1_200
STUDENT_FORBIDDEN_MARKERS = (
    "答案与评分点",
    "参考答案",
    "参考解析",
    "评分标准",
    "完整解析",
    "教师讲解",
    "教师提示",
    "推导路径",
    "结论核对",
)
JSON_FENCE_PATTERN = re.compile(r"(?is)^\s*```(?:json)?\s*(.*?)\s*```\s*$")


class TeachingDraftEvidence(BaseModel):
    """Permission-filtered evidence; paths and raw assets are intentionally absent from this contract."""

    model_config = ConfigDict(populate_by_name=True, extra="ignore")

    source_scope: str = Field(default="", alias="sourceScope", max_length=80)
    source_title: str = Field(default="", alias="sourceTitle", max_length=600)
    chunk_id: str = Field(default="", alias="chunkId", max_length=240)
    page_no: int = Field(default=0, alias="pageNo", ge=0, le=100000)
    snippet: str = Field(default="", max_length=DEFAULT_MAX_EVIDENCE_CHARS)
    image_description: str = Field(default="", alias="imageDescription", max_length=1_000)
    source_path: str = Field(default="", alias="sourcePath", max_length=400)
    asset_ids: list[str] = Field(default_factory=list, alias="assetIds", max_length=8)

    def compact(self) -> "TeachingDraftEvidence":
        """Bounds every field before it enters a prompt or provider request."""
        return self.model_copy(update={
            "source_scope": self.source_scope.strip()[:80],
            "source_title": " ".join(self.source_title.split())[:600],
            "chunk_id": self.chunk_id.strip()[:240],
            "snippet": self.snippet.strip()[:DEFAULT_MAX_EVIDENCE_CHARS],
            "image_description": " ".join(self.image_description.split())[:1_000],
            "source_path": self.source_path.strip()[:400],
            "asset_ids": [item.strip()[:160] for item in self.asset_ids if item and item.strip()][:8],
        })


class TeachingDraftRequest(BaseModel):
    """Cross-language request for one teaching AI draft; no identity, SQL or filesystem path is accepted."""

    # This endpoint is retained for non-handout teaching features only. Rejecting unknown fields prevents a handout
    # caller from silently entering the obsolete runtime when its graph/idempotency contract should use LangGraph.
    model_config = ConfigDict(populate_by_name=True, extra="forbid")

    run_id: str = Field(alias="runId", min_length=8, max_length=120)
    task_id: str = Field(alias="taskId", min_length=1, max_length=120)
    contract_version: str = Field(default=DEFAULT_CONTRACT_VERSION, alias="contractVersion", max_length=40)
    writing_goal: str = Field(alias="writingGoal", min_length=1, max_length=DEFAULT_MAX_INPUT_CHARS)
    question_text: str = Field(alias="questionText", min_length=1, max_length=DEFAULT_MAX_INPUT_CHARS)
    supplementary_requirements: str = Field(default="", alias="supplementaryRequirements", max_length=DEFAULT_MAX_INPUT_CHARS)
    template_code: str = Field(default="", alias="templateCode", max_length=128)
    template_summary: str = Field(default="", alias="templateSummary", max_length=1_000)
    memory_reuse: str = Field(default="", alias="memoryReuse", max_length=2_000)
    evidence: list[TeachingDraftEvidence] = Field(default_factory=list, max_length=DEFAULT_MAX_EVIDENCE)
    deadline_epoch_ms: int | None = Field(default=None, alias="deadlineEpochMs", ge=0)

    def compact(self) -> "TeachingDraftRequest":
        """Creates the bounded prompt input shared by all provider attempts."""
        return self.model_copy(update={
            "contract_version": self.contract_version.strip()[:40] or DEFAULT_CONTRACT_VERSION,
            "writing_goal": self.writing_goal.strip()[:DEFAULT_MAX_INPUT_CHARS],
            "question_text": self.question_text.strip()[:DEFAULT_MAX_INPUT_CHARS],
            "supplementary_requirements": self.supplementary_requirements.strip()[:DEFAULT_MAX_INPUT_CHARS],
            "template_code": self.template_code.strip()[:128],
            "template_summary": " ".join(self.template_summary.split())[:1_000],
            "memory_reuse": self.memory_reuse.strip()[:2_000],
            "evidence": [item.compact() for item in self.evidence[:DEFAULT_MAX_EVIDENCE]],
        })


@dataclass(frozen=True)
class ParsedTeachingDraft:
    """Normalized provider result used by the Java-compatible response adapter."""

    teacher_explanation: str
    student_hint: str
    knowledge_points: list[str]
    follow_up_questions: list[str]


class TeachingDraftRuntime:
    """Executes one real provider-backed draft without accessing Java business data."""

    def __init__(self) -> None:
        # A session keeps the Java-to-provider relay connection reusable across teaching tasks.
        self._session = requests.Session()
        self._ledger = UsageLedger()

    def execute(self, payload: TeachingDraftRequest) -> dict[str, Any]:
        """Attempts the configured provider order and returns one versioned, structured result."""
        started_at = datetime.now(timezone.utc).isoformat()
        started_clock = time.perf_counter()
        request = payload.compact()
        providers = self._provider_order()
        review_controller = BoundedModelReviewController("executor", profile="agent_run")
        max_retries = review_controller.max_turns - 1
        total_usage = {"promptTokens": 0, "completionTokens": 0, "totalTokens": 0, "estimatedCost": 0.0}
        usage_known = True
        events: list[dict[str, Any]] = []
        attempt_metrics: list[dict[str, Any]] = []
        attempt_index = 0
        last_error = "provider did not return a structured draft"

        for provider in providers:
            model = self._model(provider)
            attempt_started = time.perf_counter()
            try:
                parsed, usages, review = self._review_draft(request, provider, model, review_controller, attempt_index)
                attempt_index += len(usages)
                for usage in usages:
                    for key in ("promptTokens", "completionTokens", "totalTokens"):
                        total_usage[key] += int(usage.get(key, 0))
                    if float(usage.get("estimatedCost", -1.0)) < 0:
                        usage_known = False
                    elif usage_known:
                        total_usage["estimatedCost"] += float(usage.get("estimatedCost", 0.0))
                attempt_metrics.append({
                    "provider": provider,
                    "model": model,
                    "attempt": attempt_index,
                    "status": "SUCCESS",
                    "elapsedMs": round((time.perf_counter() - attempt_started) * 1000),
                })
                events.append(self._event("MODEL_CALL_SUCCEEDED", provider, model, review.turns - 1, False, True, "structured teaching draft received"))
                return self._success(
                    request, provider, model, parsed, total_usage, usage_known, review, events,
                    started_at, started_clock, attempt_metrics)
            except ModelReviewExhausted:
                last_error = "MODEL_REVIEW_EXHAUSTED"
                events.append(self._event("STRUCTURED_OUTPUT_INVALID", provider, model, max_retries, False, last_error))
                attempt_metrics.append({
                    "provider": provider,
                    "model": model,
                    "attempt": attempt_index,
                    "status": "STRUCTURED_OUTPUT_INVALID",
                    "elapsedMs": round((time.perf_counter() - attempt_started) * 1000),
                })
            except (HTTPException, requests.RequestException, ValueError, KeyError) as exc:
                last_error = type(exc).__name__
                events.append(self._event("MODEL_CALL_FAILED", provider, model, 0, True, last_error))
                attempt_metrics.append({
                    "provider": provider,
                    "model": model,
                    "attempt": attempt_index,
                    "status": "FAILED",
                    "elapsedMs": round((time.perf_counter() - attempt_started) * 1000),
                    "error": last_error,
                })

        return {
            "contractVersion": request.contract_version,
            "runId": request.run_id,
            "taskId": request.task_id,
            "status": "FAILED",
            "providerName": "",
            "modelCode": "",
            "usage": {**total_usage, "estimatedCost": -1.0 if not usage_known else total_usage["estimatedCost"]},
            "draft": {"teacherExplanation": "", "studentHint": "", "knowledgePoints": [], "followUpQuestions": [], "content": ""},
            "parseError": last_error,
            "retryCount": max_retries,
            "maxRetries": max_retries,
            "recoveredAfterRetry": False,
            "recoveryEvents": events,
            "metrics": self._metrics(started_at, started_clock, attempt_metrics),
        }

    def _success(
            self,
            request: TeachingDraftRequest,
            provider: str,
            model: str,
            parsed: ParsedTeachingDraft,
            usage: dict[str, int | float],
            usage_known: bool,
            review: ModelReviewMetadata,
            events: list[dict[str, Any]],
            started_at: str,
            started_clock: float,
            attempt_metrics: list[dict[str, Any]],
    ) -> dict[str, Any]:
        """Builds the stable response consumed by Java without exposing prompt or source bodies in diagnostics."""
        content = json.dumps({
            "teacherExplanation": parsed.teacher_explanation,
            "studentHint": parsed.student_hint,
            "knowledgePoints": parsed.knowledge_points,
            "followUpQuestions": parsed.follow_up_questions,
        }, ensure_ascii=False, separators=(",", ":"))
        return {
            "contractVersion": request.contract_version,
            "runId": request.run_id,
            "taskId": request.task_id,
            "status": "COMPLETED",
            "providerName": provider,
            "modelCode": model,
            "usage": {**usage, "estimatedCost": usage["estimatedCost"] if usage_known else -1.0},
            "draft": {
                "teacherExplanation": parsed.teacher_explanation,
                "studentHint": parsed.student_hint,
                "knowledgePoints": parsed.knowledge_points,
                "followUpQuestions": parsed.follow_up_questions,
                "content": content,
            },
            "parseError": "",
            "retryCount": review.turns - 1,
            "maxRetries": BoundedModelReviewController("executor", profile="agent_run").max_turns - 1,
            "recoveredAfterRetry": review.turns > 1,
            "recoveryEvents": events,
            "metrics": self._metrics(started_at, started_clock, attempt_metrics),
        }

    def _review_draft(
            self,
            request: TeachingDraftRequest,
            provider: str,
            model: str,
            controller: BoundedModelReviewController,
            attempt_offset: int,
    ) -> tuple[ParsedTeachingDraft, list[dict[str, int | float]], ModelReviewMetadata]:
        """Runs a bounded self-review envelope before this structured draft reaches Java."""
        attempt = attempt_offset

        def invoke(review_prompt: str, _: int) -> tuple[Any, dict[str, int | float]]:
            nonlocal attempt
            attempt += 1
            content, usage = self._call_provider(
                request, provider, model, self._review_messages(request, review_prompt), attempt,
            )
            try:
                return json.loads(content), usage
            except json.JSONDecodeError:
                return None, usage

        candidate, usages, metadata = controller.execute(
            invoke,
            self._review_prompt,
            self._parse_review_candidate,
        )
        return candidate, usages, metadata

    @staticmethod
    def _parse_review_candidate(candidate: Any) -> ParsedTeachingDraft:
        parsed = TeachingDraftRuntime._parse(json.dumps(candidate, ensure_ascii=False))
        if parsed is None:
            raise ValueError("teaching draft candidate is invalid")
        return parsed

    def _review_messages(self, request: TeachingDraftRequest, review_prompt: str) -> list[dict[str, str]]:
        """Keeps correction prompts request-local; neither prior candidates nor review text are persisted."""
        messages = self._messages(request, "")
        messages[0] = {
            "role": "system",
            "content": messages[0]["content"] + (
                ' 只返回严格 JSON 信封 {"candidate":{...},"review":{"approved":true|false,"feedbackCodes":[]}}。'
                "feedbackCodes 只能是 ENVELOPE_INVALID、REVIEW_NOT_APPROVED、CANDIDATE_INVALID、"
                "CANDIDATE_INCOMPLETE、CANDIDATE_UNSAFE 或 CANDIDATE_MISMATCH。"
            ),
        }
        messages.append({"role": "user", "content": review_prompt})
        return messages

    @staticmethod
    def _review_prompt(turn: int, prior: str | None, active_hash: str, codes: tuple[str, ...]) -> str:
        if turn == 1:
            return "生成候选教学草稿并完成严格自审。"
        return json.dumps({
            "previousCandidate": prior or "",
            "baseCandidateHash": active_hash,
            "feedbackCodes": list(codes),
            "instruction": "仅修正候选以满足结构与学生安全合同，再返回严格信封。",
        }, ensure_ascii=False, separators=(",", ":"))

    def _call_provider(
            self,
            request: TeachingDraftRequest,
            provider: str,
            model: str,
            messages: list[dict[str, str]],
            attempt: int,
    ) -> tuple[str, dict[str, int | float]]:
        """Calls an OpenAI-compatible provider with the remaining graph deadline."""
        api_key_name = {"openai": "OPENAI_API_KEY", "deepseek": "DEEPSEEK_API_KEY", "ark": "ARK_API_KEY", "glm": "GLM_API_KEY"}.get(provider, "")
        api_key = os.getenv(api_key_name) if api_key_name else None
        if not api_key:
            raise HTTPException(status_code=503, detail=f"{provider} API key is missing")
        default_base = {
            "openai": os.getenv("OPENAI_BASE_URL", "https://api.openai.com/v1"),
            "deepseek": "https://api.deepseek.com/v1",
            "ark": "https://ark.cn-beijing.volces.com/api/v3",
            "glm": anthropic_compat.default_base_url(),
        }.get(provider, "")
        base_url = os.getenv(f"{provider.upper()}_BASE_URL", default_base).rstrip("/")
        timeout = self._remaining_timeout(request)
        try:
            if anthropic_compat.is_anthropic_provider(provider):
                # GLM Anthropic 兼容端点：请求/响应转换收敛在适配层，temperature 由适配层丢弃（与 thinking 互斥）。
                data = anthropic_compat.post_chat_completion(
                    self._session, api_key, base_url,
                    {"model": model, "messages": messages, "temperature": 0.2}, timeout,
                )
            else:
                response = self._session.post(
                    f"{base_url}/chat/completions",
                    headers={"Authorization": f"Bearer {api_key}", "Content-Type": "application/json"},
                    json={"model": model, "messages": messages, "temperature": 0.2},
                    timeout=timeout,
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
            estimated_cost = cost_for(provider, model, prompt, completion)
            self._ledger.append(UsageEvent(request.run_id, provider, model, attempt, "SUCCESS", prompt, completion, total, estimated_cost, source))
            return content, {"promptTokens": prompt, "completionTokens": completion, "totalTokens": total, "estimatedCost": estimated_cost}
        except (requests.RequestException, ValueError, KeyError) as exc:
            # Failed attempts are also durable accounting events so the report can distinguish provider failure from
            # a valid zero-token response. The unique run/provider/attempt key keeps a RabbitMQ retry idempotent.
            self._ledger.append(UsageEvent(request.run_id, provider, model, attempt, "FAILED", 0, 0, 0, 0.0, "unavailable", type(exc).__name__))
            raise

    @staticmethod
    def _parse(content: str) -> ParsedTeachingDraft | None:
        """Parses only a JSON object and applies the student safety gate before Java receives it."""
        candidate = content.strip()
        fenced = JSON_FENCE_PATTERN.match(candidate)
        if fenced:
            candidate = fenced.group(1).strip()
        start, end = candidate.find("{"), candidate.rfind("}")
        if start < 0 or end <= start:
            return None
        try:
            value = json.loads(candidate[start:end + 1])
        except json.JSONDecodeError:
            return None
        if not isinstance(value, dict):
            return None
        teacher = str(value.get("teacherExplanation") or "").strip()
        student = str(value.get("studentHint") or "").strip()
        points = TeachingDraftRuntime._string_items(value.get("knowledgePoints"))
        follow_up = TeachingDraftRuntime._string_items(value.get("followUpQuestions"))
        if not teacher or not student or not points or not follow_up:
            return None
        if len(teacher) > DEFAULT_MAX_OUTPUT_CHARS or len(student) > DEFAULT_MAX_OUTPUT_CHARS:
            return None
        if any(marker in student for marker in STUDENT_FORBIDDEN_MARKERS):
            return None
        return ParsedTeachingDraft(teacher, student, points, follow_up)

    @staticmethod
    def _string_items(value: Any) -> list[str]:
        """Normalizes provider arrays and rejects nested objects that cannot be audited safely."""
        if not isinstance(value, list):
            return []
        return [item.strip()[:DEFAULT_MAX_LIST_ITEM_CHARS] for item in value if isinstance(item, str) and item.strip()][:DEFAULT_MAX_LIST_ITEMS]

    @staticmethod
    def _messages(request: TeachingDraftRequest, parse_error: str) -> list[dict[str, str]]:
        """Creates a compact content prompt; renderer and security rules remain Java-owned."""
        evidence = [item.model_dump(by_alias=True, exclude_none=True) for item in request.evidence]
        repair = f"上一次输出未通过结构化校验，原因：{parse_error}。这次只修复 JSON 格式和字段完整性。" if parse_error else ""
        return [
            {"role": "system", "content": (
                "你是高中数学教研老师。只返回一个合法 JSON 对象，不要代码块或额外文字。"
                "teacherExplanation 必须写教师可审校的真实数学讲解；studentHint 只写真实题目提示和作答留白，禁止答案、评分点、完整解析和教师提示。"
                "knowledgePoints 和 followUpQuestions 必须是具体中文字符串数组。数学排版是硬性输出合同：所有可见字段（含标题、知识点、追问、讲解与提示）中，"
                "变量、函数、集合、区间、方程、不等式、分式、根式、角度或运算式必须完整写入 $...$ 或 $$...$$；"
                "例如“函数 $f(x)$ 的定义域”，不得写“函数 f(x) 的定义域”。分式必须写 $\\frac{分子}{分母}$，根式必须写 $\\sqrt{被开方整体}$，"
                "不得使用 /、√、裸露 ^ 或 Unicode 上标代替 LaTeX，也不得让一个数学表达式跨出定界符。不得编造来源。"
            )},
            {"role": "user", "content": json.dumps({
                "repair": repair,
                "learningGoal": request.writing_goal,
                "questionText": request.question_text,
                "supplementaryRequirements": request.supplementary_requirements,
                "template": {"code": request.template_code, "summary": request.template_summary},
                "memoryReuse": request.memory_reuse,
                "evidence": evidence,
                "schema": {
                    "teacherExplanation": "string",
                    "studentHint": "string",
                    "knowledgePoints": ["string"],
                    "followUpQuestions": ["string"],
                },
            }, ensure_ascii=False, separators=(",", ":"))},
        ]

    @staticmethod
    def _provider_order() -> list[str]:
        """Keeps provider selection explicit and compatible with the existing OpenAI-compatible environment."""
        raw = os.getenv("MATH_AGENT_TEACHING_DRAFT_PROVIDER_ORDER", "openai")
        return [item.strip().lower() for item in raw.split(",") if item.strip()] or ["openai"]

    @staticmethod
    def _model(provider: str) -> str:
        return os.getenv(
            f"MATH_AGENT_TEACHING_DRAFT_{provider.upper()}_MODEL",
            os.getenv("MATH_AGENT_TEACHING_DRAFT_MODEL", os.getenv("OPENAI_CHAT_MODEL", "gpt-5.6-luna")),
        )

    @staticmethod
    def _max_retries() -> int:
        return max(0, min(int(os.getenv("MATH_AGENT_TEACHING_DRAFT_MAX_RETRIES", str(DEFAULT_MAX_RETRIES))), 3))

    @staticmethod
    def _remaining_timeout(request: TeachingDraftRequest) -> float:
        configured = max(1.0, float(os.getenv("MATH_AGENT_TEACHING_DRAFT_TIMEOUT_SECONDS", str(DEFAULT_TIMEOUT_SECONDS))))
        if request.deadline_epoch_ms is None:
            return configured
        remaining = (request.deadline_epoch_ms / 1000.0) - time.time()
        if remaining <= 0:
            raise HTTPException(status_code=504, detail="teaching draft deadline exceeded")
        return max(1.0, min(configured, remaining))

    @staticmethod
    def _event(event_type: str, provider: str, model: str, attempt: int, retryable: bool, structured: bool, message: str) -> dict[str, Any]:
        return {
            "eventType": event_type,
            "providerName": provider,
            "modelCode": model,
            "attemptNo": attempt,
            "structured": structured,
            "retryable": retryable,
            "message": message[:300],
        }

    @staticmethod
    def _metrics(started_at: str, started_clock: float, attempts: list[dict[str, Any]]) -> dict[str, Any]:
        """Returns auditable latency/call counters without retaining prompts or source text."""
        successes = sum(1 for item in attempts if item.get("status") == "SUCCESS")
        failures = sum(1 for item in attempts if item.get("status") == "FAILED")
        invalid = sum(1 for item in attempts if item.get("status") == "STRUCTURED_OUTPUT_INVALID")
        return {
            "startedAt": started_at,
            "finishedAt": datetime.now(timezone.utc).isoformat(),
            "elapsedMs": round((time.perf_counter() - started_clock) * 1000),
            "providerCalls": len(attempts),
            "providerSuccesses": successes,
            "providerFailures": failures,
            "structuredOutputInvalid": invalid,
            "attempts": attempts,
        }
