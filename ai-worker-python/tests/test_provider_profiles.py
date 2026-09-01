"""Provider capability registry contract tests (2026-08-31 abstraction layer).

These lock the single source of truth that replaced the per-runtime duplicated
provider maps: env resolution, wire-format selection, thinking/JSON capabilities,
and the OpenAI-shaped normalization that keeps reasoning separated from content.
"""

from __future__ import annotations

import json
import os
from unittest.mock import patch

import pytest

from app import provider_profiles


def test_profile_resolution_is_case_insensitive_and_rejects_unknown():
    assert provider_profiles.profile("GLM").wire_format == provider_profiles.WIRE_ANTHROPIC
    assert provider_profiles.is_supported(" deepseek ")
    assert not provider_profiles.is_supported("claude")
    with pytest.raises(ValueError):
        provider_profiles.profile("claude")


def test_credentials_apply_provider_base_url_override():
    with patch.dict(os.environ, {"DEEPSEEK_API_KEY": "sk-d", "DEEPSEEK_BASE_URL": "https://proxy.example/v1/"}):
        key, base = provider_profiles.credentials("deepseek")
    assert key == "sk-d"
    assert base == "https://proxy.example/v1"


def test_anthropic_endpoint_headers_and_body_go_through_the_bridge():
    resolved = provider_profiles.profile("glm")
    payload = {"model": "glm-5.3-flash", "messages": [{"role": "user", "content": "hi"}], "max_tokens": 4000}
    url = provider_profiles.completion_endpoint(resolved, "https://api.z.ai/api/anthropic")
    headers = provider_profiles.request_headers(resolved, "key")
    body = provider_profiles.build_request(resolved, payload)
    assert url.endswith("/v1/messages")
    assert headers["x-api-key"] == "key"
    # GLM 强制思考：桥接层注入 thinking 并抬升 max_tokens 下限，这里只验证已生效。
    assert body["thinking"]["type"] == "enabled"
    assert body["max_tokens"] >= 2048


def test_openai_credentials_keep_gateway_default():
    with patch.dict(os.environ, {"OPENAI_API_KEY": "sk-o"}, clear=False):
        os.environ.pop("OPENAI_BASE_URL", None)
        _key, base = provider_profiles.credentials("openai")
    assert base == "https://api1.aisz.mom/v1"


def test_json_object_and_thinking_capabilities_are_provider_gated():
    openai = provider_profiles.profile("openai")
    deepseek = provider_profiles.profile("deepseek")
    glm = provider_profiles.profile("glm")
    provider_profiles.apply_json_object_mode(openai, openai_payload := {"model": "m"})
    assert openai_payload["response_format"] == {"type": "json_object"}
    provider_profiles.apply_json_object_mode(glm, glm_payload := {})
    assert "response_format" not in glm_payload
    provider_profiles.apply_thinking_off(deepseek, ds_payload := {})
    assert ds_payload["enable_thinking"] is False
    with patch.dict(os.environ, {"MATH_AGENT_WORKER_DISABLE_THINKING": "false"}):
        provider_profiles.apply_thinking_off(deepseek, keep := {})
    assert "enable_thinking" not in keep
    provider_profiles.apply_thinking_off(glm, forced := {})
    provider_profiles.apply_thinking_off(openai, plain := {})
    assert forced == {} and plain == {}


def test_model_chain_prefers_provider_specific_env():
    with patch.dict(os.environ, {
        "DEEPSEEK_CHAT_MODEL": "deepseek-v4-flash",
        "MATH_AGENT_AI_RUNTIME_MODEL": "ignored",
    }):
        assert provider_profiles.default_model_chain("deepseek") == "deepseek-v4-flash"
    with patch.dict(os.environ, {}, clear=True):
        assert provider_profiles.default_model_chain("glm") == "gpt-5.6-luna"


def test_extract_and_delta_separate_reasoning_from_visible_content():
    data = {"choices": [{"message": {"content": "答案", "reasoning_content": "思考"}}]}
    assert provider_profiles.extract_message(data) == ("答案", "思考")
    assert provider_profiles.delta_fields({"delta": {"content": "a"}}) == ("a", None)
    assert provider_profiles.delta_fields({"delta": {"reasoning_content": "b"}}) == (None, "b")
    assert provider_profiles.delta_fields({"delta": {}}) == (None, None)


def _line_response(lines):
    class Response:
        def iter_lines(self, *_args, **_kwargs):
            return (line.encode("utf-8") for line in lines)
    return Response()


def test_sse_data_frames_openai_path_skips_done_and_bad_json():
    openai = provider_profiles.profile("openai")
    frames = [
        'data: {"choices": [{"delta": {"content": "x"}}]}',
        "data: not-json",
        "data: [DONE]",
    ]
    decoded = list(provider_profiles.sse_data_frames(openai, _line_response(frames)))
    assert all(isinstance(item, dict) for item in decoded)
    assert any((item.get("choices") or [{}])[0]["delta"].get("content") == "x" for item in decoded)


def test_sse_data_lines_anthropic_path_translates_reasoning_without_content_leak():
    glm = provider_profiles.profile("glm")
    frames = [
        'data: {"type": "message_start", "message": {"usage": {"input_tokens": 5, "output_tokens": 0}}}',
        'data: {"type": "content_block_delta", "index": 0, "delta": {"type": "thinking_delta", "thinking": "秘密"}}',
        'data: {"type": "content_block_delta", "index": 1, "delta": {"type": "text_delta", "text": "好"}}',
        'data: {"type": "message_stop"}',
    ]
    lines = list(provider_profiles.sse_data_lines(glm, _line_response(frames)))
    assert lines[-1] == "[DONE]"
    parsed = [json.loads(line) for line in lines[:-1]]
    visible = "".join(
        ((item.get("choices") or [{}])[0].get("delta") or {}).get("content") or "" for item in parsed)
    assert visible == "好"
    reasoning = "".join(
        ((item.get("choices") or [{}])[0].get("delta") or {}).get("reasoning_content") or "" for item in parsed)
    assert reasoning == "秘密"


def test_post_completion_uses_session_and_raise_for_status():
    captured = {}

    class Response:
        def raise_for_status(self):
            return None

        def json(self):
            return {"choices": [{"message": {"content": "ok"}}]}

    class Session:
        def post(self, url, **kwargs):
            captured["url"] = url
            captured["headers"] = kwargs["headers"]
            return Response()

    data = provider_profiles.post_completion(
        provider_profiles.profile("deepseek"), Session(), "k", "https://ds.example/v1",
        {"model": "m", "messages": []}, 5.0)
    assert captured["url"] == "https://ds.example/v1/chat/completions"
    assert captured["headers"]["Authorization"] == "Bearer k"
    assert data["choices"][0]["message"]["content"] == "ok"
