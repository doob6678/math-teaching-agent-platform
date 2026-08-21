"""True SSE agent runtime for OpenAI-compatible providers."""
from __future__ import annotations

import json
import os
from typing import Any, Iterator

from fastapi import HTTPException
import requests

from app.agent_runtime import AgentRunRequest, AgentRuntime
from app.sse import iter_sse_data_events
from app.usage import UsageEvent, UsageLedger, cost_for, fallback_tokens


# Once a delta crosses the SSE boundary it is user-visible and immutable: no review, rewrite, or provider fallback.
NO_REWRITE_AFTER_VISIBLE_OUTPUT = True


class AgentStreamingRuntime:
    """Streams model deltas while preserving provider rotation and immutable usage accounting."""

    def stream(self, request: AgentRunRequest) -> Iterator[dict[str, Any]]:
        yield {"event": "started", "data": {"runId": request.runId}}
        requested_tool = AgentRuntime._requested_tool(request)
        if requested_tool is not None:
            if requested_tool not in request.allowedTools:
                yield {"event": "error", "data": {"status": 403, "message": "tool is not granted for this run"}}
                return
            yield {"event": "tool_call", "data": self._bounded_tool_call(request, requested_tool, {"query": request.message})}
            return

        messages = self._initial_messages(request)
        if request.toolResult is not None:
            messages.append({"role": "user", "content": "Authorized tool observation:\n" + json.dumps(request.toolResult, ensure_ascii=False)})
            yield from self._model_stream(request, messages, allow_tools=False, usage_total=self._zero_usage())
            return

        internal = None
        for event in self._model_stream(request, messages, allow_tools=True, usage_total=self._zero_usage(), defer_completed=True):
            if event["event"] == "internal_result":
                internal = event["data"]
            else:
                yield event
        if not internal or not internal.get("toolCall"):
            if internal:
                yield {"event": "completed", "data": {"status": "COMPLETED", "actualUsage": internal["actualUsage"]}}
            return

        tool_call = internal["toolCall"]
        yield {"event": "tool_call", "data": tool_call}
        observation = AgentRuntime._invoke_java_tool_broker(tool_call)
        yield {"event": "tool_result", "data": {"name": tool_call["name"]}}
        final_messages = self._observation_messages(request, tool_call, observation)
        yield from self._model_stream(request, final_messages, allow_tools=False, usage_total=internal["actualUsage"])

    def _model_stream(
            self, request: AgentRunRequest, messages: list[dict[str, Any]], allow_tools: bool,
            usage_total: dict[str, int | float], defer_completed: bool = False) -> Iterator[dict[str, Any]]:
        ledger = UsageLedger()
        failures: list[str] = []
        for attempt, provider in enumerate(self._providers(), 1):
            key, base_url, model = self._provider_config(provider)
            if not key or not base_url:
                failures.append(f"{provider}:configuration")
                continue
            payload = self._payload(model, messages, request.allowedTools, allow_tools, base_url)
            emitted_content = False
            content_parts: list[str] = []
            tool_parts: dict[int, dict[str, str]] = {}
            usage: dict[str, Any] = {}
            try:
                with requests.post(
                    f"{base_url}/chat/completions",
                    headers={"Authorization": f"Bearer {key}", "Content-Type": "application/json"},
                    json=payload, stream=True,
                    timeout=float(os.getenv("MATH_AGENT_AI_RUNTIME_TIMEOUT_SECONDS", "30")),
                ) as response:
                    response.raise_for_status()
                    yield {"event": "provider", "data": {"provider": provider, "model": model, "attempt": attempt}}
                    for data in self._sse_json(response):
                        usage = data.get("usage") or usage
                        for choice in data.get("choices") or []:
                            delta = choice.get("delta") or {}
                            text = delta.get("content")
                            if text:
                                emitted_content = True
                                content_parts.append(str(text))
                                yield {"event": "delta", "data": {"content": str(text)}}
                            self._merge_tool_chunks(tool_parts, delta.get("tool_calls") or [])
                prompt, completion, total, source = self._usage(usage, messages, "".join(content_parts))
                price = cost_for(provider, model, prompt, completion)
                ledger.append(UsageEvent(request.runId, provider, model, attempt, "SUCCESS", prompt, completion, total, price, source))
                combined = self._add_usage(usage_total, prompt, completion, total, price)
                yield {"event": "usage", "data": {"provider": provider, "model": model, "usageSource": source, **combined}}
                tool_call = self._normalize_tool_call(request, tool_parts) if tool_parts else None
                result = {"toolCall": tool_call, "actualUsage": combined}
                if defer_completed:
                    yield {"event": "internal_result", "data": result}
                else:
                    yield {"event": "completed", "data": {"status": "COMPLETED", "actualUsage": combined}}
                return
            except (requests.RequestException, ValueError, KeyError) as exc:
                ledger.append(UsageEvent(request.runId, provider, model, attempt, "FAILED", 0, 0, 0, 0.0, "unavailable", type(exc).__name__))
                failures.append(f"{provider}:{type(exc).__name__}")
                if emitted_content and NO_REWRITE_AFTER_VISIBLE_OUTPUT:
                    # A second model response after a visible delta would rewrite the user's answer. The caller gets
                    # one terminal transport error instead; safe structured review is only available before streaming.
                    yield {"event": "error", "data": {"status": 503, "message": "provider stream interrupted after output", "provider": provider}}
                    return
                # Rotation is permitted only before user-visible text; otherwise a second provider could duplicate
                # or contradict a partial answer already delivered to the browser.
                yield {"event": "provider_failed", "data": {"provider": provider, "model": model, "attempt": attempt, "errorCode": type(exc).__name__}}
        yield {"event": "error", "data": {"status": 503, "message": "all providers failed", "failures": failures}}

    @staticmethod
    def _sse_json(response: requests.Response) -> Iterator[dict[str, Any]]:
        for value in iter_sse_data_events(response):
            if value == "[DONE]":
                continue
            decoded = json.loads(value)
            if isinstance(decoded, dict):
                yield decoded

    @staticmethod
    def _merge_tool_chunks(parts: dict[int, dict[str, str]], chunks: list[dict[str, Any]]) -> None:
        for chunk in chunks:
            index = int(chunk.get("index", 0))
            function = chunk.get("function") or {}
            current = parts.setdefault(index, {"name": "", "arguments": ""})
            current["name"] += str(function.get("name") or "")
            current["arguments"] += str(function.get("arguments") or "")

    @staticmethod
    def _usage(raw: dict[str, Any], messages: list[dict[str, Any]], content: str) -> tuple[int, int, int, str]:
        prompt = int(raw.get("prompt_tokens", 0) or 0)
        completion = int(raw.get("completion_tokens", 0) or 0)
        total = int(raw.get("total_tokens", 0) or 0)
        if total > 0:
            return prompt, completion, total, "provider"
        prompt, completion, total = fallback_tokens(messages, content)
        return prompt, completion, total, "fallback"

    @staticmethod
    def _payload(model: str, messages: list[dict[str, Any]], allowed_tools: list[str], allow_tools: bool, base_url: str) -> dict[str, Any]:
        payload: dict[str, Any] = {"model": model, "messages": messages, "stream": True, "stream_options": {"include_usage": True}}
        if allow_tools:
            payload["tools"] = [{"type": "function", "function": {"name": name, "description": "Request Java to execute an authorized tool.", "parameters": AgentRuntime._tool_parameters(name)}} for name in allowed_tools]
        else:
            payload["tool_choice"] = "none"
            if "api.openai.com" not in base_url:
                payload["tools"] = [{"type": "function", "function": {"name": "__no_tool__", "description": "internal compatibility schema", "parameters": {"type": "object", "properties": {}}}}]
        return payload

    @staticmethod
    def _normalize_tool_call(request: AgentRunRequest, parts: dict[int, dict[str, str]]) -> dict[str, Any]:
        function = parts[min(parts)]
        name = function["name"]
        if name not in request.allowedTools:
            raise HTTPException(status_code=403, detail="model requested a tool outside the capability scope")
        arguments = json.loads(function["arguments"] or "{}")
        allowed = AgentRuntime._tool_argument_names(name)
        normalized = {key: str(value) for key, value in arguments.items() if key in allowed}
        if set(allowed) - set(normalized):
            raise ValueError("model omitted required tool arguments")
        return AgentStreamingRuntime._bounded_tool_call(request, name, normalized)

    @staticmethod
    def _bounded_tool_call(request: AgentRunRequest, name: str, arguments: dict[str, Any]) -> dict[str, Any]:
        # Java 根据 runId 解析持久化身份；Python 只传递模型已校验的工具参数。
        return {"name": name, "arguments": {**arguments, "runId": request.runId}}

    @staticmethod
    def _initial_messages(request: AgentRunRequest) -> list[dict[str, Any]]:
        return [{"role": "system", "content": "Use tools only when authorized evidence is needed; never invent citations."}, {"role": "user", "content": request.message}]

    @staticmethod
    def _observation_messages(request: AgentRunRequest, tool_call: dict[str, Any], observation: dict[str, Any]) -> list[dict[str, Any]]:
        return [{"role": "system", "content": "Answer only from the authorized tool observation when citing resources."}, {"role": "user", "content": request.message}, {"role": "assistant", "tool_calls": [{"id": "authorized_tool_0", "type": "function", "function": {"name": tool_call["name"], "arguments": json.dumps(tool_call["arguments"], ensure_ascii=False)}}]}, {"role": "tool", "tool_call_id": "authorized_tool_0", "content": json.dumps(observation, ensure_ascii=False)}]

    @staticmethod
    def _providers() -> list[str]:
        return [item.strip().lower() for item in os.getenv("MATH_AGENT_AI_RUNTIME_PROVIDER_ORDER", os.getenv("MATH_AGENT_AI_RUNTIME_PROVIDER", "openai")).split(",") if item.strip()]

    @staticmethod
    def _provider_config(provider: str) -> tuple[str | None, str, str]:
        keys = {"openai": "OPENAI_API_KEY", "dashscope": "DASHSCOPE_API_KEY", "deepseek": "DEEPSEEK_API_KEY", "ark": "ARK_API_KEY"}
        bases = {"openai": os.getenv("OPENAI_BASE_URL", "https://api1.aisz.mom/v1"), "dashscope": "https://dashscope.aliyuncs.com/compatible-mode/v1", "deepseek": "https://api.deepseek.com/v1", "ark": "https://ark.cn-beijing.volces.com/api/v3"}
        key = os.getenv(keys.get(provider, ""))
        base = os.getenv(f"{provider.upper()}_BASE_URL", bases.get(provider, "")).rstrip("/")
        model = os.getenv(f"MATH_AGENT_AI_RUNTIME_{provider.upper()}_MODEL", os.getenv("MATH_AGENT_AI_RUNTIME_MODEL", os.getenv("OPENAI_CHAT_MODEL", "gpt-5.6-luna")))
        return key, base, model

    @staticmethod
    def _zero_usage() -> dict[str, int | float]:
        return {"promptTokens": 0, "completionTokens": 0, "totalTokens": 0, "estimatedCost": 0.0}

    @staticmethod
    def _add_usage(current: dict[str, int | float], prompt: int, completion: int, total: int, price: float) -> dict[str, int | float]:
        return {"promptTokens": int(current["promptTokens"]) + prompt, "completionTokens": int(current["completionTokens"]) + completion, "totalTokens": int(current["totalTokens"]) + total, "estimatedCost": float(current["estimatedCost"]) + price}
