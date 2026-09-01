"""Anthropic-format provider bridge for the OpenAI-shaped runtime layer.

All runtimes in this worker build OpenAI chat-completions payloads and parse
OpenAI responses/SSE. GLM (智谱 Z.ai) is reached through its Anthropic-compatible
endpoint, which speaks a different wire format, so this module translates at the
transport boundary: callers keep building OpenAI shapes and only branch on
`is_anthropic_provider` when POSTing.

Live contract verified against https://api.z.ai/api/anthropic on 2026-08-30 with
model glm-5.3-flash:
- glm-5.3-flash always reasons; `thinking` cannot be disabled (gateway error 1210)
  and only accepts effort levels low/high/max. We default to the weak "low" level
  because the boss wants fast answers, not disabled thinking.
- Reasoning shares the completion budget, so a small OpenAI-style max_tokens would
  be consumed by hidden thinking before any visible text (stop_reason=max_tokens
  with empty content). A bounded floor keeps short interactive turns usable
  without unbounding Java's signed budget.
- temperature is rejected together with thinking, so it is dropped here.
"""

from __future__ import annotations

import json
import os
from typing import Any, Iterator

import requests

from app.sse import iter_sse_data_events


# Providers whose endpoint speaks the Anthropic Messages format instead of OpenAI chat completions.
ANTHROPIC_FORMAT_PROVIDERS = frozenset({"glm"})

# z.ai 的 Anthropic 兼容端点固定版本头；升级端点时只需改这里。
ANTHROPIC_VERSION = "2023-06-01"

# OpenAI runtime 里的内部占位工具（tool_choice=none 的兼容 schema），Anthropic 侧直接省略 tools。
_INTERNAL_NO_TOOL_NAME = "__no_tool__"


def is_anthropic_provider(provider: str) -> bool:
    """Returns True when the named provider must be called in Anthropic Messages format."""
    return str(provider or "").strip().lower() in ANTHROPIC_FORMAT_PROVIDERS


def default_base_url() -> str:
    """GLM Anthropic-compatible base URL; GLM_BASE_URL must not carry a /v1 suffix."""
    return os.getenv("GLM_BASE_URL", "https://api.z.ai/api/anthropic").rstrip("/")


def anthropic_headers(api_key: str) -> dict[str, str]:
    """Anthropic endpoints authenticate with x-api-key, not an OpenAI bearer header."""
    return {"x-api-key": api_key, "anthropic-version": ANTHROPIC_VERSION, "Content-Type": "application/json"}


def _thinking_effort() -> str:
    effort = os.getenv("MATH_AGENT_GLM_THINKING_EFFORT", "low").strip().lower()
    return effort if effort in {"low", "high", "max"} else "low"


def _minimum_max_tokens() -> int:
    return max(1, int(os.getenv("MATH_AGENT_GLM_MIN_MAX_TOKENS", "2048")))


def _convert_tool_call_arguments(raw: Any) -> dict[str, Any]:
    if isinstance(raw, dict):
        return raw
    if isinstance(raw, str) and raw.strip():
        try:
            decoded = json.loads(raw)
            return decoded if isinstance(decoded, dict) else {}
        except json.JSONDecodeError:
            return {}
    return {}


def build_messages_payload(openai_payload: dict[str, Any]) -> dict[str, Any]:
    """Converts an OpenAI chat-completions payload into an Anthropic Messages request.

    System messages move into the top-level `system` field, OpenAI function tools map to
    `input_schema` tools, and the observation turn (assistant tool_calls + role=tool result)
    maps to assistant tool_use blocks followed by a user tool_result turn.
    """
    system_parts: list[str] = []
    anthropic_messages: list[dict[str, Any]] = []
    for message in openai_payload.get("messages") or []:
        role = str(message.get("role") or "")
        content = message.get("content")
        if role == "system":
            if isinstance(content, str) and content.strip():
                system_parts.append(content)
            continue
        if role == "assistant":
            blocks: list[dict[str, Any]] = []
            if isinstance(content, str) and content:
                blocks.append({"type": "text", "text": content})
            for tool_call in message.get("tool_calls") or []:
                function = tool_call.get("function") or {}
                blocks.append({
                    "type": "tool_use",
                    "id": str(tool_call.get("id") or "authorized_tool_0"),
                    "name": str(function.get("name") or ""),
                    "input": _convert_tool_call_arguments(function.get("arguments")),
                })
            anthropic_messages.append({"role": "assistant", "content": blocks or ""})
            continue
        if role == "tool":
            # OpenAI 观察 turn 是 role=tool；Anthropic 要求 tool_result 作为 user turn 紧跟 tool_use。
            anthropic_messages.append({"role": "user", "content": [{
                "type": "tool_result",
                "tool_use_id": str(message.get("tool_call_id") or "authorized_tool_0"),
                "content": str(content if content is not None else ""),
            }]})
            continue
        anthropic_messages.append({"role": "user", "content": str(content if content is not None else "")})

    tools = []
    for tool in openai_payload.get("tools") or []:
        function = tool.get("function") or {}
        name = str(function.get("name") or "")
        if not name or name == _INTERNAL_NO_TOOL_NAME:
            continue
        tools.append({
            "name": name,
            "description": str(function.get("description") or ""),
            "input_schema": function.get("parameters") or {"type": "object", "properties": {}},
        })
    # tool_choice=none（最终回答 turn）映射为完全不声明 tools，避免模型再请求工具。
    if str(openai_payload.get("tool_choice") or "") == "none":
        tools = []

    requested_max_tokens = openai_payload.get("max_tokens")
    payload: dict[str, Any] = {
        "model": openai_payload.get("model"),
        "messages": anthropic_messages,
        "max_tokens": max(int(requested_max_tokens or 0), _minimum_max_tokens()),
        # glm-5.3-flash 强制思考（网关禁用 disabled）；按部署偏好走弱思考档。
        "thinking": {"type": "enabled", "effort": _thinking_effort()},
    }
    if system_parts:
        payload["system"] = "\n\n".join(system_parts)
    if tools:
        payload["tools"] = tools
    if openai_payload.get("stream"):
        payload["stream"] = True
    return payload


_STOP_REASON_TO_FINISH = {"end_turn": "stop", "stop_sequence": "stop", "tool_use": "tool_calls", "max_tokens": "length"}


def _openai_usage(usage: dict[str, Any]) -> dict[str, int] | None:
    """Maps Anthropic usage fields to the OpenAI keys every runtime already reads."""
    prompt = int(usage.get("input_tokens", 0) or 0)
    completion = int(usage.get("output_tokens", 0) or 0)
    if prompt <= 0 and completion <= 0:
        return None
    return {
        "prompt_tokens": prompt,
        "completion_tokens": completion,
        "total_tokens": prompt + completion,
        "prompt_tokens_details": {"cached_tokens": int(usage.get("cache_read_input_tokens", 0) or 0)},
    }


def to_openai_completion(anthropic_response: dict[str, Any]) -> dict[str, Any]:
    """Converts a full Anthropic Messages response into the OpenAI completion shape.

    thinking blocks are mapped to the OpenAI-compatible `reasoning_content` message field
    so runtimes can persist a private thinking trace; they are never merged into answer
    text, and visible-content code paths only ever read `content`.
    """
    text_parts: list[str] = []
    reasoning_parts: list[str] = []
    tool_calls: list[dict[str, Any]] = []
    for block in anthropic_response.get("content") or []:
        block_type = str(block.get("type") or "")
        if block_type == "text" and block.get("text"):
            text_parts.append(str(block["text"]))
        elif block_type == "thinking" and block.get("thinking"):
            reasoning_parts.append(str(block["thinking"]))
        elif block_type == "tool_use":
            tool_calls.append({
                "id": str(block.get("id") or ""),
                "type": "function",
                "function": {
                    "name": str(block.get("name") or ""),
                    "arguments": json.dumps(block.get("input") or {}, ensure_ascii=False),
                },
            })
    message: dict[str, Any] = {"role": "assistant", "content": "".join(text_parts)}
    if reasoning_parts:
        message["reasoning_content"] = "".join(reasoning_parts)
    if tool_calls:
        message["tool_calls"] = tool_calls
    choice: dict[str, Any] = {"index": 0, "message": message}
    finish_reason = _STOP_REASON_TO_FINISH.get(str(anthropic_response.get("stop_reason") or ""))
    if finish_reason:
        choice["finish_reason"] = finish_reason
    completion: dict[str, Any] = {"choices": [choice]}
    usage = _openai_usage(anthropic_response.get("usage") or {})
    if usage:
        completion["usage"] = usage
    return completion


class _StreamingToolState:
    """Maps Anthropic streaming block indexes to sequential OpenAI tool_call indexes."""

    def __init__(self) -> None:
        self._tool_index_by_block: dict[int, int] = {}
        self._next_tool_index = 0

    def open_block(self, block_index: int) -> int:
        tool_index = self._next_tool_index
        self._next_tool_index += 1
        self._tool_index_by_block[block_index] = tool_index
        return tool_index

    def tool_index(self, block_index: int) -> int:
        return self._tool_index_by_block.get(block_index, 0)


def openai_sse_data_frames(response: Any, completion: dict[str, bool] | None = None) -> Iterator[dict[str, Any]]:
    """Reads an Anthropic SSE stream and yields OpenAI-shaped chunk dicts.

    Frames match what the OpenAI runtimes already parse: content deltas, merged
    tool_call argument deltas, and a final frame carrying cumulative usage and
    finish_reason. Reasoning deltas are emitted as OpenAI-style
    `delta.reasoning_content` frames: visible-content parsers never read that key, so
    thinking reaches only runtimes that explicitly persist private diagnostics.
    `completion` receives True only when the provider sent message_stop, so string-framed
    consumers can keep the original "stream ended before [DONE]" truncation guard.
    """
    usage: dict[str, Any] = {}
    stop_reason: str | None = None
    tools = _StreamingToolState()
    for value in iter_sse_data_events(response):
        if value == "[DONE]":
            break
        try:
            event = json.loads(value)
        except json.JSONDecodeError:
            continue
        if not isinstance(event, dict):
            continue
        event_type = str(event.get("type") or "")
        if event_type == "message_start":
            message = event.get("message") or {}
            start_usage = message.get("usage") or {}
            if int(start_usage.get("input_tokens", 0) or 0) > 0:
                usage["input_tokens"] = int(start_usage["input_tokens"])
        elif event_type == "content_block_start":
            block = event.get("content_block") or {}
            if str(block.get("type") or "") == "tool_use":
                tool_index = tools.open_block(int(event.get("index", 0) or 0))
                yield {"choices": [{"index": 0, "delta": {"tool_calls": [{
                    "index": tool_index, "id": str(block.get("id") or ""), "type": "function",
                    "function": {"name": str(block.get("name") or ""), "arguments": ""},
                }]}}]}
        elif event_type == "content_block_delta":
            delta = event.get("delta") or {}
            delta_type = str(delta.get("type") or "")
            if delta_type == "text_delta" and delta.get("text"):
                yield {"choices": [{"index": 0, "delta": {"content": str(delta["text"])}}]}
            elif delta_type == "input_json_delta" and delta.get("partial_json"):
                tool_index = tools.tool_index(int(event.get("index", 0) or 0))
                yield {"choices": [{"index": 0, "delta": {"tool_calls": [{
                    "index": tool_index, "function": {"arguments": str(delta["partial_json"])},
                }]}}]}
            elif delta_type == "thinking_delta" and delta.get("thinking"):
                # 思考增量单独走 reasoning_content，禁止进入可见 content；signature_delta 仍是内部签名，直接丢弃。
                yield {"choices": [{"index": 0, "delta": {"reasoning_content": str(delta["thinking"])}}]}
        elif event_type == "message_delta":
            delta = event.get("delta") or {}
            if delta.get("stop_reason"):
                stop_reason = str(delta["stop_reason"])
            delta_usage = event.get("usage") or {}
            for key in ("input_tokens", "output_tokens"):
                if int(delta_usage.get(key, 0) or 0) > 0:
                    usage[key] = int(delta_usage[key])
        elif event_type == "error":
            # 网关级错误帧（限流/超时）以异常抛出，交给调用方既有的 provider 轮换处理。
            error = event.get("error") or {}
            raise ValueError(f"anthropic stream error: {error.get('type') or 'unknown'}")
        elif event_type == "message_stop":
            if completion is not None:
                completion["message_stop"] = True
            yield _final_stream_frame(usage, stop_reason)
            return
    # 流在 message_stop 之前中断：不发结束帧直接结束，调用方按截断处理并轮换。
    if usage or stop_reason:
        yield _final_stream_frame(usage, stop_reason)


def _final_stream_frame(usage: dict[str, Any], stop_reason: str | None) -> dict[str, Any]:
    """Builds the terminal OpenAI chunk with finish_reason and cumulative usage."""
    choice: dict[str, Any] = {"index": 0, "delta": {}}
    finish_reason = _STOP_REASON_TO_FINISH.get(stop_reason or "")
    if finish_reason:
        choice["finish_reason"] = finish_reason
    frame: dict[str, Any] = {"choices": [choice]}
    openai_usage = _openai_usage(usage)
    if openai_usage:
        frame["usage"] = openai_usage
    return frame


def openai_sse_data_lines(response: Any) -> Iterator[str]:
    """String-framed variant of `openai_sse_data_frames` for consumers that parse
    `data:` strings and rely on the `[DONE]` sentinel (handout/workload streams).
    `[DONE]` is only emitted after a real provider message_stop, preserving the
    upstream truncation semantics."""
    completion: dict[str, bool] = {}
    for frame in openai_sse_data_frames(response, completion):
        yield json.dumps(frame, ensure_ascii=False)
    if completion.get("message_stop"):
        yield "[DONE]"


def post_chat_completion(
        session: Any | None,
        api_key: str,
        base_url: str,
        openai_payload: dict[str, Any],
        timeout: float) -> dict[str, Any]:
    """Performs a non-streaming Anthropic call and returns the OpenAI completion shape."""
    poster = session.post if session is not None else requests.post
    response = poster(
        f"{base_url.rstrip('/')}/v1/messages",
        headers=anthropic_headers(api_key),
        json=build_messages_payload(openai_payload),
        timeout=timeout,
    )
    response.raise_for_status()
    return to_openai_completion(response.json())


def post_streaming(
        session: Any | None,
        api_key: str,
        base_url: str,
        openai_payload: dict[str, Any],
        timeout: float) -> Any:
    """Opens a streaming Anthropic call; consume with `openai_sse_data_lines`."""
    poster = session.post if session is not None else requests.post
    payload = build_messages_payload(openai_payload)
    payload["stream"] = True
    return poster(
        f"{base_url.rstrip('/')}/v1/messages",
        headers=anthropic_headers(api_key),
        json=payload,
        stream=True,
        timeout=timeout,
    )
