"""Live end-to-end check of the GLM Anthropic bridge (real network calls).

Run: python scripts/live_glm_bridge_check.py
Verifies, against the real z.ai Anthropic endpoint:
1. Non-streaming OpenAI-shaped conversion through anthropic_compat.post_chat_completion.
2. Streaming SSE translation through post_streaming + openai_sse_data_lines.
3. Full AgentRuntime._call_live_model with provider order glm (includes the bounded
   self-review envelope contract, exactly the production agent path).
"""
from __future__ import annotations

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from app import anthropic_compat
from app.agent_runtime import AgentRunRequest, AgentRuntime


def main() -> None:
    api_key = os.environ["GLM_API_KEY"]
    base_url = anthropic_compat.default_base_url()
    print(f"base_url={base_url}")

    # 1) Non-streaming with a tool: expect either a direct answer or a converted tool_calls entry.
    payload = {
        "model": os.getenv("GLM_CHAT_MODEL", "glm-5.3-flash"),
        "messages": [
            {"role": "system", "content": "只依据授权证据回答，不要编造引用。"},
            {"role": "user", "content": "请用工具搜索：二次函数顶点式"},
        ],
        "max_tokens": 2048,
        "tools": [{"type": "function", "function": {
            "name": "search_visible_resources",
            "description": "Request Java to execute an authorized tool.",
            "parameters": {"type": "object", "properties": {"query": {"type": "string"}}, "required": ["query"], "additionalProperties": False},
        }}],
    }
    data = anthropic_compat.post_chat_completion(None, api_key, base_url, payload, 120.0)
    message = data["choices"][0]["message"]
    print(f"[1] non-stream finish={data['choices'][0].get('finish_reason')} usage={data.get('usage')}")
    assert message.get("tool_calls"), f"expected converted tool_calls, got: {message}"
    call = message["tool_calls"][0]
    assert call["function"]["name"] == "search_visible_resources"
    assert "query" in call["function"]["arguments"]
    print(f"[1] tool_call ok: name={call['function']['name']} arguments={call['function']['arguments']}")

    # 2) Streaming: expect visible content deltas plus a final usage frame and [DONE].
    # 2026-08-31 reasoning 落盘合同：thinking_delta 必须单独成为 reasoning_content 帧，
    # content 通道不得混入思考；GLM 强制思考，真实调用应同时出现两路。
    stream_payload = {
        "model": payload["model"],
        "messages": [{"role": "user", "content": "只回复两个字：收到"}],
        "max_tokens": 1024,
        "stream": True,
        "stream_options": {"include_usage": True},
    }
    response = anthropic_compat.post_streaming(None, api_key, base_url, stream_payload, 120.0)
    response.raise_for_status()
    chunks = list(anthropic_compat.openai_sse_data_lines(response))
    assert chunks[-1] == "[DONE]", chunks[-3:]
    decoded = [json.loads(frame) for frame in chunks[:-1]]
    text = "".join(item["choices"][0]["delta"].get("content") or "" for item in decoded)
    reasoning = "".join(item["choices"][0]["delta"].get("reasoning_content") or "" for item in decoded)
    final = decoded[-1]
    print(f"[2] stream text={text!r} finish={final['choices'][0].get('finish_reason')} usage={final.get('usage')}")
    print(f"[2] reasoning_chars={len(reasoning)}")
    assert "收到" in text
    assert len(reasoning) > 0, "GLM 强制思考，streaming 应出现 reasoning_content 帧（落盘前提）"
    assert final.get("usage", {}).get("total_tokens", 0) > 0

    # 3) Full agent runtime path with provider order glm (production agent loop, real review envelope).
    os.environ["MATH_AGENT_AI_RUNTIME_PROVIDER_ORDER"] = "glm"
    os.environ.pop("MATH_AGENT_AI_RUNTIME_MODEL", None)
    result = AgentRuntime().execute(AgentRunRequest(
        runId="live-glm-bridge-check-20260830",
        allowedTools=["search_visible_resources"],
        message="用一句话说明什么是二次函数的顶点式。",
        maxOutputTokens=2048,
    ))
    print(f"[3] agent status={result.status} provider={result.provider_name} model={result.model_code}")
    print(f"[3] usage={result.actual_usage}")
    print(f"[3] message={str(result.message)[:200]}")
    assert result.status == "COMPLETED"
    assert result.provider_name == "glm"
    assert result.message and result.message.strip()
    print("ALL LIVE CHECKS PASSED")


if __name__ == "__main__":
    import json
    main()
