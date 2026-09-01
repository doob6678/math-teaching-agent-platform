"""Anthropic-format GLM bridge contract tests.

Shapes mirror the live z.ai/api/anthropic responses recorded on 2026-08-30 (model
glm-5.3-flash): thinking blocks always precede text, tool_use arrives as a block,
and the SSE stream is framed with typed Anthropic events.
"""

from __future__ import annotations

import json

from app import anthropic_compat


def test_is_anthropic_provider_matches_glm_only():
    assert anthropic_compat.is_anthropic_provider("glm")
    assert anthropic_compat.is_anthropic_provider("GLM")
    assert not anthropic_compat.is_anthropic_provider("openai")
    assert not anthropic_compat.is_anthropic_provider("")


def test_build_payload_moves_system_and_drops_openai_only_fields():
    openai_payload = {
        "model": "glm-5.3-flash",
        "messages": [
            {"role": "system", "content": "只输出合法 JSON。"},
            {"role": "user", "content": "生成讲义"},
        ],
        "temperature": 0.2,
        "max_tokens": 256,
        "response_format": {"type": "json_object"},
        "stream": True,
        "stream_options": {"include_usage": True},
    }
    converted = anthropic_compat.build_messages_payload(openai_payload)
    assert converted["model"] == "glm-5.3-flash"
    assert converted["system"] == "只输出合法 JSON。"
    assert converted["messages"] == [{"role": "user", "content": "生成讲义"}]
    # glm-5.3-flash 强制思考且与 temperature 互斥；思考共享 max_tokens，须抬到部署下限。
    assert "temperature" not in converted
    assert "response_format" not in converted
    assert "stream_options" not in converted
    assert converted["stream"] is True
    assert converted["max_tokens"] == 2048
    assert converted["thinking"] == {"type": "enabled", "effort": "low"}


def test_build_payload_keeps_requested_max_tokens_above_floor():
    converted = anthropic_compat.build_messages_payload({"model": "glm-5.3-flash", "messages": [{"role": "user", "content": "hi"}], "max_tokens": 8000})
    assert converted["max_tokens"] == 8000


def test_build_payload_converts_tools_and_observation_turn():
    openai_payload = {
        "model": "glm-5.3-flash",
        "messages": [
            {"role": "system", "content": "answer from evidence"},
            {"role": "user", "content": "搜索二次函数"},
            {"role": "assistant", "tool_calls": [{"id": "authorized_tool_0", "type": "function", "function": {"name": "search_visible_resources", "arguments": "{\"query\": \"二次函数\"}"}}]},
            {"role": "tool", "tool_call_id": "authorized_tool_0", "content": "{\"items\": []}"},
        ],
        "tools": [{"type": "function", "function": {"name": "search_visible_resources", "description": "Request Java to execute an authorized tool.", "parameters": {"type": "object", "properties": {"query": {"type": "string"}}, "required": ["query"]}}}],
    }
    converted = anthropic_compat.build_messages_payload(openai_payload)
    assert converted["tools"] == [{
        "name": "search_visible_resources",
        "description": "Request Java to execute an authorized tool.",
        "input_schema": {"type": "object", "properties": {"query": {"type": "string"}}, "required": ["query"]},
    }]
    assert converted["messages"][1] == {"role": "assistant", "content": [
        {"type": "tool_use", "id": "authorized_tool_0", "name": "search_visible_resources", "input": {"query": "二次函数"}},
    ]}
    assert converted["messages"][2] == {"role": "user", "content": [
        {"type": "tool_result", "tool_use_id": "authorized_tool_0", "content": "{\"items\": []}"},
    ]}


def test_build_payload_drops_no_tool_marker_with_tool_choice_none():
    converted = anthropic_compat.build_messages_payload({
        "model": "glm-5.3-flash",
        "messages": [{"role": "user", "content": "hi"}],
        "tool_choice": "none",
        "tools": [{"type": "function", "function": {"name": "__no_tool__", "description": "internal compatibility schema", "parameters": {"type": "object", "properties": {}}}}],
    })
    assert "tools" not in converted
    assert "tool_choice" not in converted


def test_to_openai_completion_isolates_thinking_in_reasoning_content():
    anthropic_response = {
        "id": "msg_1", "type": "message", "role": "assistant", "model": "glm-5.3-flash",
        "content": [
            {"type": "thinking", "thinking": "private reasoning", "signature": "sig"},
            {"type": "text", "text": "收到"},
        ],
        "stop_reason": "end_turn",
        "usage": {"input_tokens": 17, "output_tokens": 79, "cache_read_input_tokens": 3},
    }
    converted = anthropic_compat.to_openai_completion(anthropic_response)
    choice = converted["choices"][0]
    assert choice["message"]["content"] == "收到"
    assert "tool_calls" not in choice["message"]
    # 2026-08-31 契约变更：thinking 单独落入 reasoning_content 供私有诊断落盘，绝不并入可见 content。
    assert choice["message"]["reasoning_content"] == "private reasoning"
    assert choice["message"]["content"] == "收到"
    assert choice["finish_reason"] == "stop"
    assert converted["usage"]["prompt_tokens"] == 17
    assert converted["usage"]["completion_tokens"] == 79
    assert converted["usage"]["total_tokens"] == 96
    assert converted["usage"]["prompt_tokens_details"]["cached_tokens"] == 3


def test_to_openai_completion_maps_tool_use_and_length():
    converted = anthropic_compat.to_openai_completion({
        "content": [{"type": "tool_use", "id": "call_1", "name": "search_visible_resources", "input": {"query": "函数"}}],
        "stop_reason": "tool_use",
        "usage": {"input_tokens": 10, "output_tokens": 5},
    })
    message = converted["choices"][0]["message"]
    assert message["content"] == ""
    assert message["tool_calls"][0]["function"]["name"] == "search_visible_resources"
    assert json.loads(message["tool_calls"][0]["function"]["arguments"]) == {"query": "函数"}
    assert converted["choices"][0]["finish_reason"] == "tool_calls"


def _sse_response(frames: list[str]):
    class FakeResponse:
        def __init__(self, frames):
            self._frames = frames

        def iter_lines(self, *args, **kwargs):
            return iter(self._frames)

    return FakeResponse(frames)


def test_openai_sse_data_lines_translates_stream_and_separates_thinking():
    frames = [
        "event: message_start",
        'data: {"type": "message_start", "message": {"id": "msg_1", "usage": {"input_tokens": 12, "output_tokens": 0}}}',
        "event: ping",
        'data: {"type": "ping"}',
        "event: content_block_start",
        'data: {"type": "content_block_start", "index": 0, "content_block": {"type": "thinking", "thinking": "", "signature": ""}}',
        "event: content_block_delta",
        'data: {"type": "content_block_delta", "index": 0, "delta": {"type": "thinking_delta", "thinking": "private"}}',
        "event: content_block_stop",
        'data: {"type": "content_block_stop", "index": 0}',
        "event: content_block_start",
        'data: {"type": "content_block_start", "index": 1, "content_block": {"type": "text", "text": ""}}',
        "event: content_block_delta",
        'data: {"type": "content_block_delta", "index": 1, "delta": {"type": "text_delta", "text": "收到"}}',
        "event: content_block_stop",
        'data: {"type": "content_block_stop", "index": 1}',
        "event: message_delta",
        'data: {"type": "message_delta", "delta": {"stop_reason": "end_turn"}, "usage": {"input_tokens": 17, "output_tokens": 79}}',
        "event: message_stop",
        'data: {"type": "message_stop"}',
    ]
    lines = list(anthropic_compat.openai_sse_data_lines(_sse_response(frames)))
    assert lines[-1] == "[DONE]"
    decoded = [json.loads(line) for line in lines[:-1]]
    contents = "".join((item["choices"][0]["delta"].get("content") or "") for item in decoded)
    assert contents == "收到"
    # thinking 增量只能以 reasoning_content 形状出现（私有落盘通道），content 通道必须干净。
    reasoning = "".join(
        (item["choices"][0]["delta"].get("reasoning_content") or "") for item in decoded)
    assert reasoning == "private"
    for item in decoded:
        delta = item["choices"][0].get("delta") or {}
        assert "private" not in (delta.get("content") or "")
    final = decoded[-1]
    assert final["choices"][0]["finish_reason"] == "stop"
    assert final["usage"]["prompt_tokens"] == 17
    assert final["usage"]["completion_tokens"] == 79
    # 中间帧不携带 usage，避免把 message_start 的占位 0 写进账本。
    for item in decoded[:-1]:
        assert "usage" not in item


def test_openai_sse_data_lines_streams_tool_arguments():
    frames = [
        'data: {"type": "message_start", "message": {"usage": {"input_tokens": 0, "output_tokens": 0}}}',
        "event: content_block_start",
        'data: {"type": "content_block_start", "index": 0, "content_block": {"type": "tool_use", "id": "call_1", "name": "search_visible_resources"}}',
        "event: content_block_delta",
        'data: {"type": "content_block_delta", "index": 0, "delta": {"type": "input_json_delta", "partial_json": "{\\"query\\": "}}',
        "event: content_block_delta",
        'data: {"type": "content_block_delta", "index": 0, "delta": {"type": "input_json_delta", "partial_json": "\\"函数\\"}"}}',
        "event: message_delta",
        'data: {"type": "message_delta", "delta": {"stop_reason": "tool_use"}, "usage": {"output_tokens": 30}}',
        "event: message_stop",
        'data: {"type": "message_stop"}',
    ]
    lines = list(anthropic_compat.openai_sse_data_lines(_sse_response(frames)))
    decoded = [json.loads(line) for line in lines[:-1]]
    tool_chunks = [item["choices"][0]["delta"]["tool_calls"][0] for item in decoded if item["choices"][0]["delta"].get("tool_calls")]
    assert tool_chunks[0]["function"]["name"] == "search_visible_resources"
    arguments = "".join(chunk["function"].get("arguments") or "" for chunk in tool_chunks)
    assert json.loads(arguments) == {"query": "函数"}
    assert decoded[-1]["choices"][0]["finish_reason"] == "tool_calls"


def test_openai_sse_data_lines_raises_on_error_frame():
    frames = ['data: {"type": "error", "error": {"type": " overloaded_error ", "message": "slow down"}}']
    try:
        list(anthropic_compat.openai_sse_data_lines(_sse_response(frames)))
        raised = False
    except ValueError:
        raised = True
    assert raised


def test_openai_sse_data_frames_yields_dicts_for_streaming_runtime():
    """streaming_runtime 直接消费 dict chunk（与 _sse_json 同形状）。"""
    frames = [
        'data: {"type": "message_start", "message": {"usage": {"input_tokens": 9, "output_tokens": 0}}}',
        'data: {"type": "content_block_delta", "index": 1, "delta": {"type": "text_delta", "text": "你好"}}',
        'data: {"type": "message_delta", "delta": {"stop_reason": "end_turn"}, "usage": {"output_tokens": 11}}',
        'data: {"type": "message_stop"}',
    ]
    decoded = list(anthropic_compat.openai_sse_data_frames(_sse_response(frames)))
    assert all(isinstance(item, dict) for item in decoded)
    assert decoded[-1]["usage"]["prompt_tokens"] == 9
    assert decoded[-1]["choices"][0]["finish_reason"] == "stop"


def test_openai_sse_data_lines_omits_done_on_truncated_stream():
    """缺少 message_stop 时不发 [DONE]，保持 workload 截断判失败的既有语义。"""
    frames = [
        'data: {"type": "message_start", "message": {"usage": {"input_tokens": 5, "output_tokens": 0}}}',
        'data: {"type": "content_block_delta", "index": 0, "delta": {"type": "text_delta", "text": "部分"}}',
    ]
    lines = list(anthropic_compat.openai_sse_data_lines(_sse_response(frames)))
    assert lines[-1] != "[DONE]"
    decoded = [json.loads(line) for line in lines]
    # 截断时仅 message_start 报过 input_tokens：末帧带已知 usage 但没有 finish_reason。
    assert decoded[-1]["usage"]["prompt_tokens"] == 5
    assert not decoded[-1]["choices"][0].get("finish_reason")
