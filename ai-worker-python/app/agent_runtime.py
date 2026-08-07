"""Python-owned Agent runtime with an explicit Java tool boundary."""

from __future__ import annotations

from dataclasses import dataclass
import json
import os
from typing import Any, TypedDict

from fastapi import HTTPException
from langgraph.graph import END, START, StateGraph
from pydantic import BaseModel, ConfigDict, Field
import requests
from app.usage import UsageEvent, UsageLedger, cost_for, fallback_tokens


class AgentRunRequest(BaseModel):
    """Python AI 执行协议只接收运行标识和受限输入，身份由 Java 按 runId 反查。"""

    model_config = ConfigDict(extra="forbid")

    runId: str = Field(min_length=1)
    allowedTools: list[str] = Field(min_length=1)
    message: str = Field(min_length=1)
    toolResult: dict[str, Any] | None = None
    # 仅供确定性传输测试使用；生产环境中的工具选择必须来自模型调用。
    requestedTool: str | None = None


@dataclass(frozen=True)
class AgentRunResult:
    status: str
    message: str | None = None
    tool_call: dict[str, Any] | None = None
    actual_usage: dict[str, int | float] | None = None

    def as_response(self) -> dict[str, Any]:
        response: dict[str, Any] = {"status": self.status}
        if self.message is not None:
            response["message"] = self.message
        if self.tool_call is not None:
            response["toolCall"] = self.tool_call
        if self.actual_usage is not None:
            response["actualUsage"] = self.actual_usage
        return response


class SupervisorState(TypedDict):
    """Small durable graph state; source data remains in Java and never enters the checkpoint payload by path."""

    request: AgentRunRequest
    result: AgentRunResult


class AgentRuntime:
    """Executes only model decisions and asks Java to handle all protected data access."""

    def execute(self, request: AgentRunRequest) -> AgentRunResult:
        return self._graph().invoke({"request": request})["result"]

    def _graph(self):
        """Creates the single-supervisor LangGraph; tool calls are bounded by one Java-mediated step per turn."""
        graph = StateGraph(SupervisorState)
        graph.add_node("supervise", self._supervise)
        graph.add_edge(START, "supervise")
        graph.add_edge("supervise", END)
        return graph.compile()

    def _supervise(self, state: SupervisorState) -> SupervisorState:
        request = state["request"]
        tool_name = self._requested_tool(request)
        if tool_name is not None:
            if tool_name not in request.allowedTools:
                raise HTTPException(status_code=403, detail="tool is not granted for this run")
            return {"request": request, "result": AgentRunResult(
                status="TOOL_REQUESTED",
                tool_call={
                    "name": tool_name,
                    # Only opaque run data crosses the boundary; paths, SQL, and provider secrets never do.
                    "arguments": {"query": request.message, "runId": request.runId},
                },
            )}
        if request.toolResult is not None:
            # A tool observation is evidence, not an answer.  Returning it verbatim would make the Java caller
            # become the hidden orchestration layer and could expose more source content than the final answer needs.
            return {"request": request, "result": self._complete_with_observation(request, request.toolResult)}
        model_result = self._call_live_model(request)
        if model_result.tool_call is None:
            return {"request": request, "result": model_result}
        # The model selects a tool, but Java executes it under tenant visibility and never grants Python filesystem
        # access. The returned observation is supplied to the next bounded conversation turn by Java.
        observation = self._invoke_java_tool_broker(model_result.tool_call)
        final = self._complete_with_observation(request, observation, model_result.tool_call)
        return {"request": request, "result": self._merge_usage(model_result, final)}

    @staticmethod
    def _merge_usage(first: AgentRunResult, second: AgentRunResult) -> AgentRunResult:
        """Adds usage from tool selection and final answer so provider rotations cannot hide partial spend."""
        if not first.actual_usage or not second.actual_usage:
            return second
        keys = ("promptTokens", "completionTokens", "totalTokens", "estimatedCost")
        usage = {key: first.actual_usage.get(key, 0) + second.actual_usage.get(key, 0) for key in keys}
        return AgentRunResult(status=second.status, message=second.message, tool_call=second.tool_call, actual_usage=usage)

    def _complete_with_observation(
            self,
            request: AgentRunRequest,
            observation: dict[str, Any],
            tool_call: dict[str, Any] | None = None) -> AgentRunResult:
        """Feeds Java-authorized evidence back to the same real model for its final, citation-aware answer."""
        evidence = json.dumps(observation, ensure_ascii=False, separators=(",", ":"))
        messages: list[dict[str, Any]] = [
            {"role": "system", "content": (
                "Answer only from the authorized tool observation when citing resources. "
                "Give concise Chinese teaching guidance and cite documentId/blockId or assetId present in it."
            )},
            {"role": "user", "content": request.message},
        ]
        if tool_call is not None:
            # OpenAI-compatible providers require a tool_call_id in the observation turn.  The broker never
            # returns a filesystem path, database connection string, or provider credential.
            messages.append({
                "role": "assistant",
                "tool_calls": [{
                    "id": "authorized_tool_0",
                    "type": "function",
                    "function": {
                        "name": str(tool_call["name"]),
                        "arguments": json.dumps(tool_call.get("arguments", {}), ensure_ascii=False),
                    },
                }],
            })
            messages.append({"role": "tool", "tool_call_id": "authorized_tool_0", "content": evidence})
        else:
            messages.append({"role": "user", "content": "Authorized tool observation:\n" + evidence})
        return self._call_live_model(request, messages=messages, allow_tools=False)

    @staticmethod
    def _requested_tool(request: AgentRunRequest) -> str | None:
        enabled = os.getenv("MATH_AGENT_AI_RUNTIME_ALLOW_TEST_TOOL_REQUEST", "false").lower() == "true"
        return request.requestedTool if enabled else None

    @staticmethod
    def _call_live_model(
            request: AgentRunRequest,
            messages: list[dict[str, Any]] | None = None,
            allow_tools: bool = True) -> AgentRunResult:
        """Uses a real OpenAI-compatible tool-call endpoint; no local heuristic infers the user's intent."""
        providers = [item.strip().lower() for item in os.getenv("MATH_AGENT_AI_RUNTIME_PROVIDER_ORDER", os.getenv("MATH_AGENT_AI_RUNTIME_PROVIDER", "openai")).split(",") if item.strip()]
        tools = [{"type": "function", "function": {
            "name": name,
            "description": "Request Java to execute an authorized tool.",
            "parameters": AgentRuntime._tool_parameters(name),
        }} for name in request.allowedTools]
        request_messages = messages or [
            {"role": "system", "content": "Use tools only when authorized evidence is needed; never invent citations."},
            {"role": "user", "content": request.message},
        ]
        ledger = UsageLedger()
        failures: list[str] = []
        for attempt, provider in enumerate(providers, 1):
            key_name = {"openai": "OPENAI_API_KEY", "dashscope": "DASHSCOPE_API_KEY", "deepseek": "DEEPSEEK_API_KEY", "ark": "ARK_API_KEY"}.get(provider, "")
            api_key = os.getenv(key_name) if key_name else None
            if not api_key:
                failures.append(f"{provider}:missing_key")
                continue
            default_base_url = {
                "openai": os.getenv("OPENAI_BASE_URL", "https://api1.aisz.mom/v1"),
                "dashscope": "https://dashscope.aliyuncs.com/compatible-mode/v1",
                "deepseek": "https://api.deepseek.com/v1",
                "ark": "https://ark.cn-beijing.volces.com/api/v3",
            }.get(provider, "")
            base_url = os.getenv(f"{provider.upper()}_BASE_URL", default_base_url).rstrip("/")
            model = os.getenv(f"MATH_AGENT_AI_RUNTIME_{provider.upper()}_MODEL", os.getenv("MATH_AGENT_AI_RUNTIME_MODEL", os.getenv("OPENAI_CHAT_MODEL", "gpt-5.6-terra")))
            payload: dict[str, Any] = {"model": model, "messages": request_messages}
            if allow_tools:
                payload["tools"] = tools
            else:
                # A bounded final turn prevents unbounded model/tool loops and keeps model cost predictable.
                payload["tool_choice"] = "none"
                # Some Responses-to-Chat relays reject a null tools field even for tool_choice=none.
                if "api.openai.com" not in base_url:
                    payload["tools"] = [{"type": "function", "function": {"name": "__no_tool__", "description": "internal compatibility schema", "parameters": {"type": "object", "properties": {}}}}]
            try:
                response = requests.post(f"{base_url}/chat/completions", headers={"Authorization": f"Bearer {api_key}", "Content-Type": "application/json"}, json=payload, timeout=float(os.getenv("MATH_AGENT_AI_RUNTIME_TIMEOUT_SECONDS", "30")))
                response.raise_for_status()
                data = response.json()
                message = data["choices"][0]["message"]
                usage = data.get("usage") or {}
                prompt = int(usage.get("prompt_tokens", 0) or 0)
                completion = int(usage.get("completion_tokens", 0) or 0)
                total = int(usage.get("total_tokens", 0) or 0)
                source = "provider"
                if total <= 0:
                    prompt, completion, total = fallback_tokens(request_messages, str(message.get("content") or ""))
                    source = "fallback"
                ledger.append(UsageEvent(request.runId, provider, model, attempt, "SUCCESS", prompt, completion, total, cost_for(provider, model, prompt, completion), source))
                actual = {"promptTokens": prompt, "completionTokens": completion, "totalTokens": total, "estimatedCost": cost_for(provider, model, prompt, completion)}
                break
            except (KeyError, ValueError, requests.RequestException) as exc:
                failures.append(f"{provider}:{type(exc).__name__}")
                ledger.append(UsageEvent(request.runId, provider, model, attempt, "FAILED", 0, 0, 0, 0.0, "unavailable", type(exc).__name__))
        else:
            raise HTTPException(status_code=503, detail="Live agent model call failed: " + ",".join(failures))
        tool_calls = message.get("tool_calls") or []
        if tool_calls and not allow_tools:
            raise HTTPException(status_code=503, detail="Live agent returned an unexpected second tool call")
        if tool_calls and allow_tools:
            tool = tool_calls[0].get("function", {})
            name = tool.get("name")
            if name not in request.allowedTools:
                raise HTTPException(status_code=403, detail="model requested a tool outside the capability scope")
            try:
                arguments = json.loads(tool.get("arguments") or "{}")
            except json.JSONDecodeError as exc:
                raise HTTPException(status_code=422, detail="model returned invalid tool arguments") from exc
            allowed_arguments = AgentRuntime._tool_argument_names(name)
            normalized_arguments = {key: str(value) for key, value in arguments.items() if key in allowed_arguments}
            if set(AgentRuntime._tool_parameters(name)["required"]) - set(normalized_arguments):
                raise HTTPException(status_code=422, detail="model omitted required tool arguments")
            return AgentRunResult(
                status="TOOL_REQUESTED",
                tool_call={
                    "name": name,
                    "arguments": {
                        **normalized_arguments,
                        "runId": request.runId,
                    },
                },
                actual_usage=actual,
            )
        return AgentRunResult(status="COMPLETED", message=str(message.get("content") or ""), actual_usage=actual)

    @staticmethod
    def _tool_parameters(name: str) -> dict[str, Any]:
        """Keeps each Java broker tool schema explicit; no tool accepts an arbitrary path or URL."""
        field_by_tool = {
            "search_visible_resources": "query",
            "read_resource_blocks": "documentId",
            "read_resource_asset": "assetId",
        }
        field = field_by_tool.get(name)
        if field is None:
            raise HTTPException(status_code=422, detail="No schema is registered for the requested tool")
        return {"type": "object", "properties": {field: {"type": "string", "minLength": 1}},
                "required": [field], "additionalProperties": False}

    @staticmethod
    def _tool_argument_names(name: str) -> set[str]:
        return set(AgentRuntime._tool_parameters(name)["required"])

    @staticmethod
    def _invoke_java_tool_broker(tool_call: dict[str, Any]) -> dict[str, Any]:
        """Calls only the two Java tool routes that accept opaque ids/query values, never a model-selected URL."""
        base_url = os.getenv("MATH_AGENT_TOOL_BROKER_BASE_URL", "http://127.0.0.1:8080").rstrip("/")
        worker_key = os.getenv("MATH_AGENT_AGENT_WORKER_SHARED_KEY")
        if not worker_key:
            raise HTTPException(status_code=503, detail="MATH_AGENT_AGENT_WORKER_SHARED_KEY is required for tool execution")
        route_by_tool = {
            "search_visible_resources": "/internal/agent-tools/v1/search-visible-resources",
            "read_resource_blocks": "/internal/agent-tools/v1/read-resource-blocks",
            "read_resource_asset": "/internal/agent-tools/v1/read-resource-asset",
        }
        route = route_by_tool.get(str(tool_call.get("name")))
        if route is None:
            raise HTTPException(status_code=422, detail="No Java broker route is registered for the requested tool")
        payload = dict(tool_call.get("arguments") or {})
        try:
            response = requests.post(
                f"{base_url}{route}",
                headers={"X-Agent-Worker-Key": worker_key, "Content-Type": "application/json"},
                json=payload,
                timeout=float(os.getenv("MATH_AGENT_TOOL_BROKER_TIMEOUT_SECONDS", "15")),
            )
            response.raise_for_status()
            return response.json()
        except (ValueError, requests.RequestException) as exc:
            raise HTTPException(status_code=503, detail="Java tool broker call failed") from exc
