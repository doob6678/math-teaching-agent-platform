"""Versioned internal contract for Java-owned generic AI runs."""

from __future__ import annotations

from dataclasses import dataclass
import base64
import hashlib
import hmac
import json
import os
import time
from typing import Any, Literal

from fastapi import HTTPException
from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from app.agent_runtime import AgentRunRequest, AgentRunResult, AgentRuntime


CONTRACT_VERSION = "ai-run-v1"
MAX_EVIDENCE_REFS = 24
MAX_ALLOWED_TOOLS = 8


class ProviderSelection(BaseModel):
    """Java allow-list 签发的一项 provider/model 组合。"""

    model_config = ConfigDict(extra="forbid")

    name: Literal["openai", "dashscope", "deepseek", "ark"]
    model: str = Field(min_length=1, max_length=160)


class ProviderRoute(BaseModel):
    """Java 签发的有限 provider 路由；生产模式要求短期签名 grant。"""

    model_config = ConfigDict(extra="forbid")

    primary: ProviderSelection
    fallbacks: list[ProviderSelection] = Field(default_factory=list, max_length=3)
    routeGrant: str | None = Field(default=None, max_length=2_048)

    @model_validator(mode="after")
    def unique_providers(self) -> "ProviderRoute":
        values = [(item.name, item.model) for item in [self.primary, *self.fallbacks]]
        if len(set(values)) != len(values):
            raise ValueError("provider route contains a duplicate provider/model route")
        return self

    def verify_for(self, run_id: str, workload: str) -> None:
        """验证当前请求绑定的短期 route grant，避免跨运行或跨 workload 复用。"""
        if os.getenv("MATH_AGENT_REQUIRE_ROUTE_GRANT", "false").lower() != "true":
            return
        if not self.routeGrant:
            raise ValueError("provider route grant is required")
        granted = verify_route_grant(self.routeGrant, run_id, workload)
        expected = [(item.name, item.model) for item in [self.primary, *self.fallbacks]]
        if granted != expected:
            raise ValueError("provider route grant does not match route")


def verify_route_grant(value: str, run_id: str, workload: str) -> list[tuple[str, str]]:
    """验证 Java HMAC-SHA-256 route grant，并只返回 worker allow-list 中的 route。"""
    try:
        encoded_payload, encoded_signature = value.split(".", 1)
        secret = os.getenv("MATH_AGENT_PROVIDER_ROUTE_GRANT_SECRET", "").encode("utf-8")
        if not secret:
            raise ValueError("route grant secret is unavailable")
        expected_signature = hmac.new(secret, encoded_payload.encode("ascii"), hashlib.sha256).digest()
        signature = base64.urlsafe_b64decode(encoded_signature + "=" * (-len(encoded_signature) % 4))
        if not hmac.compare_digest(signature, expected_signature):
            raise ValueError("route grant signature is invalid")
        payload = json.loads(base64.urlsafe_b64decode(encoded_payload + "=" * (-len(encoded_payload) % 4)))
        if str(payload.get("runId") or "") != run_id:
            raise ValueError("route grant run is invalid")
        if str(payload.get("workload") or "") != workload:
            raise ValueError("route grant workload is invalid")
        if int(payload.get("expiresAt", 0)) < int(time.time()):
            raise ValueError("route grant is expired")
        routes = payload.get("routes")
        if not isinstance(routes, list) or not routes or len(routes) > 4:
            raise ValueError("route grant routes are invalid")
        allowed = {"openai", "dashscope", "deepseek", "ark"}
        result = []
        for route in routes:
            if not isinstance(route, dict) or route.get("name") not in allowed:
                raise ValueError("route grant provider is invalid")
            model = str(route.get("model") or "")
            if not model or len(model) > 160:
                raise ValueError("route grant model is invalid")
            result.append((str(route["name"]), model))
        return result
    except (ValueError, KeyError, TypeError, json.JSONDecodeError, UnicodeDecodeError) as exc:
        raise ValueError("provider route grant is invalid") from exc


class RunLimits(BaseModel):
    """Java policy 预先裁剪的执行限额。"""

    model_config = ConfigDict(extra="forbid")

    maxProviderCalls: int = Field(ge=1, le=4)
    maxTotalTokens: int = Field(ge=1, le=128_000)
    maxOutputChars: int = Field(ge=1, le=64_000)


class AiRunRequest(BaseModel):
    """通用 AI 执行 wire contract；身份和业务资源始终由 Java 按 runId 解析。"""

    model_config = ConfigDict(extra="forbid")

    contractVersion: Literal[CONTRACT_VERSION]
    runId: str = Field(min_length=1, max_length=128)
    workload: Literal["generic_agent"]
    idempotencyKey: str = Field(min_length=1, max_length=256)
    traceparent: str = Field(min_length=1, max_length=128)
    deadlineEpochMs: int = Field(gt=0)
    providerRoute: ProviderRoute
    limits: RunLimits
    input: dict[str, Any]
    evidenceRefs: list[str] = Field(default_factory=list, max_length=MAX_EVIDENCE_REFS)
    allowedTools: list[Literal["search_visible_resources", "read_resource_blocks", "read_resource_asset"]] = Field(
        default_factory=list,
        max_length=MAX_ALLOWED_TOOLS,
    )

    @field_validator("evidenceRefs")
    @classmethod
    def bounded_evidence_refs(cls, value: list[str]) -> list[str]:
        normalized = [item.strip() for item in value if item and item.strip()]
        if any(len(item) > 320 for item in normalized):
            raise ValueError("evidence reference exceeds the allowed length")
        return list(dict.fromkeys(normalized))

    @model_validator(mode="after")
    def validate_safe_input(self) -> "AiRunRequest":
        self.providerRoute.verify_for(self.runId, self.workload)
        if set(self.input) != {"message"}:
            raise ValueError("generic_agent input must contain only message")
        message = self.input.get("message")
        if not isinstance(message, str) or not message.strip() or len(message) > 16_000:
            raise ValueError("generic_agent message must be a bounded non-empty string")
        return self

    def agent_request(self) -> AgentRunRequest:
        return AgentRunRequest(
            runId=self.runId,
            allowedTools=list(self.allowedTools),
            message=str(self.input["message"]).strip(),
        )


@dataclass(frozen=True)
class AiRunResult:
    status: str
    provider_name: str
    model_code: str
    message: str
    generated_content: str
    prompt_tokens: int
    completion_tokens: int
    total_tokens: int
    actual_cost: float
    cost_known: bool

    def as_response(self) -> dict[str, Any]:
        return {
            "contractVersion": CONTRACT_VERSION,
            "status": self.status,
            "providerName": self.provider_name,
            "modelCode": self.model_code,
            "message": self.message,
            "generatedContent": self.generated_content,
            "actualUsage": {
                "promptTokens": self.prompt_tokens,
                "completionTokens": self.completion_tokens,
                "totalTokens": self.total_tokens,
            },
            "actualCost": self.actual_cost,
            "costKnown": self.cost_known,
            "diagnosticEvents": [],
        }


class AiRunRuntime:
    """将版本化请求投影到受限 Agent runtime，并在边界执行限额校验。"""

    def __init__(self, agent_runtime: AgentRuntime | None = None) -> None:
        self._agent_runtime = agent_runtime

    def execute(self, request: AiRunRequest) -> AiRunResult:
        runtime = self._agent_runtime or AgentRuntime(
            [(selection.name, selection.model) for selection in [request.providerRoute.primary, *request.providerRoute.fallbacks]],
            request.limits.maxProviderCalls,
        )
        result = runtime.execute(request.agent_request())
        return self._project(request, result)

    @staticmethod
    def _project(request: AiRunRequest, result: AgentRunResult) -> AiRunResult:
        if result.status != "COMPLETED":
            raise HTTPException(status_code=503, detail="generic AI run did not complete")
        usage = result.actual_usage or {}
        prompt = max(0, int(usage.get("promptTokens", 0)))
        completion = max(0, int(usage.get("completionTokens", 0)))
        total = max(0, int(usage.get("totalTokens", prompt + completion)))
        content = str(result.message or "")
        if total > request.limits.maxTotalTokens:
            raise HTTPException(status_code=422, detail="AI run exceeded token limit")
        if len(content) > request.limits.maxOutputChars:
            raise HTTPException(status_code=422, detail="AI run exceeded output limit")
        actual_cost = float(usage.get("estimatedCost", -1.0))
        return AiRunResult(
            status="COMPLETED",
            provider_name=getattr(result, "provider_name", None) or request.providerRoute.primary.name,
            model_code=getattr(result, "model_code", None) or request.providerRoute.primary.model,
            message="Python AI run completed.",
            generated_content=content,
            prompt_tokens=prompt,
            completion_tokens=completion,
            total_tokens=total,
            actual_cost=actual_cost,
            cost_known=actual_cost >= 0.0,
        )
