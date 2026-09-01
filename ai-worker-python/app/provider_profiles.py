"""Provider capability registry and transport seam shared by every worker runtime.

Before this module each runtime duplicated the provider->env-key map, the base-URL
defaults and the `if is_anthropic_provider` wire-format branch, and each copy adapted
a slightly different capability (deepseek `enable_thinking`, GLM forced thinking,
JSON-object mode support). That made adding a provider a five-file change and left
hidden per-provider quirks scattered through the code.

The contract here:
- one `ProviderProfile` per supported provider declares key env, base URL, wire format
  and every capability the runtimes are allowed to branch on;
- transport helpers (`post_completion`, `open_stream`, `sse_data_lines`) always return
  OpenAI-shaped data so downstream parsing stays provider-independent;
- reasoning output is separated from answer text here: `extract_message` and
  `delta_fields` return `(content, reasoning)` so runtimes can persist thinking traces
  without ever letting them leak into visible content paths.

Anthropic wire conversion itself stays in `anthropic_compat`; this module only decides
which provider uses it.
"""

from __future__ import annotations

import json
import os
from dataclasses import dataclass
from typing import Any, Iterator

import requests

from app import anthropic_compat
from app.sse import iter_sse_data_events

WIRE_OPENAI = "openai"
WIRE_ANTHROPIC = "anthropic"


@dataclass(frozen=True)
class ProviderProfile:
    """Static facts about one provider; never holds secrets, only env variable names."""

    name: str
    api_key_env: str
    default_base_url: str
    wire_format: str
    # OpenAI-compatible switch that turns hidden reasoning off when the endpoint allows it
    # (deepseek `enable_thinking`). None means the provider exposes no such parameter.
    thinking_toggle_param: str | None = None
    # Whether the endpoint honors `response_format={"type":"json_object"}`.
    json_object_mode: bool = True
    # Anthropic-style forced thinking (GLM): the gateway rejects thinking-disabled, and
    # temperature is mutually exclusive with thinking, so the transport layer owns both.
    forced_thinking: bool = False

    def supports_anthropic_wire(self) -> bool:
        return self.wire_format == WIRE_ANTHROPIC


# Defaults are literals resolved lazily in `credentials()` so test env changes take
# effect without re-import. The openai default points at the self-hosted gateway
# (api1.aisz.mom), not api.openai.com, because deployments set OPENAI_BASE_URL to the
# gateway; the literal only matters if operators remove that env entirely.
PROFILES: dict[str, ProviderProfile] = {
    profile.name: profile
    for profile in (
        ProviderProfile("openai", "OPENAI_API_KEY", "https://api1.aisz.mom/v1", WIRE_OPENAI),
        ProviderProfile("dashscope", "DASHSCOPE_API_KEY", "https://dashscope.aliyuncs.com/compatible-mode/v1", WIRE_OPENAI),
        # deepseek-compatible relays expose the hidden-reasoning switch as `enable_thinking`.
        ProviderProfile("deepseek", "DEEPSEEK_API_KEY", "https://api.deepseek.com/v1", WIRE_OPENAI,
                        thinking_toggle_param="enable_thinking"),
        ProviderProfile("ark", "ARK_API_KEY", "https://ark.cn-beijing.volces.com/api/v3", WIRE_OPENAI),
        # GLM_BASE_URL doubles as this provider's {PROVIDER}_BASE_URL override below;
        # the literal matches anthropic_compat.default_base_url()'s fallback.
        ProviderProfile("glm", "GLM_API_KEY", "https://api.z.ai/api/anthropic", WIRE_ANTHROPIC,
                        json_object_mode=False, forced_thinking=True),
    )
}


def profile(provider: str) -> ProviderProfile:
    """Resolves one provider name case-insensitively; unknown names fail fast at config time."""
    resolved = PROFILES.get(str(provider or "").strip().lower())
    if resolved is None:
        raise ValueError(f"provider {provider!r} is not registered in provider_profiles")
    return resolved


def is_supported(provider: str) -> bool:
    return str(provider or "").strip().lower() in PROFILES


def credentials(provider: str) -> tuple[str | None, str]:
    """Returns (api_key, base_url) applying the {PROVIDER}_BASE_URL override convention.

    base_url is rstripped of '/' exactly like every previous call site so endpoint
    concatenation (`/chat/completions`, `/v1/messages`) keeps working unchanged.
    """
    resolved = profile(provider)
    api_key = os.getenv(resolved.api_key_env)
    base_url = os.getenv(f"{resolved.name.upper()}_BASE_URL", resolved.default_base_url).rstrip("/")
    return api_key, base_url


def default_model_chain(provider: str) -> str:
    """Standard chat-model env chain used by the interactive runtimes.

    {PROVIDER}_CHAT_MODEL > MATH_AGENT_AI_RUNTIME_{PROVIDER}_MODEL > MATH_AGENT_AI_RUNTIME_MODEL
    > OPENAI_CHAT_MODEL > built-in default. Runs whose Java request signs an explicit
    model (workload/teaching routes) resolve it upstream and never call this.
    """
    resolved = profile(provider)
    upper = resolved.name.upper()
    return os.getenv(f"{upper}_CHAT_MODEL", os.getenv(
        f"MATH_AGENT_AI_RUNTIME_{upper}_MODEL",
        os.getenv("MATH_AGENT_AI_RUNTIME_MODEL", os.getenv("OPENAI_CHAT_MODEL", "gpt-5.6-luna"))))


def completion_endpoint(resolved: ProviderProfile, base_url: str) -> str:
    if resolved.supports_anthropic_wire():
        return f"{base_url}/v1/messages"
    return f"{base_url}/chat/completions"


def request_headers(resolved: ProviderProfile, api_key: str) -> dict[str, str]:
    if resolved.supports_anthropic_wire():
        return anthropic_compat.anthropic_headers(api_key)
    return {"Authorization": f"Bearer {api_key}", "Content-Type": "application/json"}


def build_request(resolved: ProviderProfile, payload: dict[str, Any]) -> dict[str, Any]:
    """Adapts an OpenAI-shaped payload to the provider wire; OpenAI routes pass through."""
    if resolved.supports_anthropic_wire():
        return anthropic_compat.build_messages_payload(payload)
    return payload


def post_completion(
        resolved: ProviderProfile,
        session: Any | None,
        api_key: str,
        base_url: str,
        payload: dict[str, Any],
        timeout: float) -> dict[str, Any]:
    """Performs one non-streaming completion and returns the OpenAI completion shape."""
    poster = session.post if session is not None else requests.post
    if resolved.supports_anthropic_wire():
        return anthropic_compat.post_chat_completion(session, api_key, base_url, payload, timeout)
    response = poster(
        completion_endpoint(resolved, base_url),
        headers=request_headers(resolved, api_key),
        json=payload,
        timeout=timeout,
    )
    response.raise_for_status()
    return response.json()


def open_stream(
        resolved: ProviderProfile,
        session: Any | None,
        api_key: str,
        base_url: str,
        payload: dict[str, Any],
        timeout: float):
    """Opens a streaming completion; consume with `sse_data_lines`/`sse_data_frames`."""
    poster = session.post if session is not None else requests.post
    request_payload = dict(payload)
    request_payload["stream"] = True
    return poster(
        completion_endpoint(resolved, base_url),
        headers=request_headers(resolved, api_key),
        json=build_request(resolved, request_payload),
        stream=True,
        timeout=timeout,
    )


def sse_data_lines(resolved: ProviderProfile, response: Any) -> Iterator[str]:
    """Yields `data:` JSON strings (and the trailing `[DONE]` sentinel) in OpenAI shape."""
    if resolved.supports_anthropic_wire():
        yield from anthropic_compat.openai_sse_data_lines(response)
    else:
        yield from iter_sse_data_events(response)


def sse_data_frames(resolved: ProviderProfile, response: Any) -> Iterator[dict[str, Any]]:
    """Yields decoded OpenAI chunk dicts; skips [DONE] and undecodable keep-alive frames."""
    if resolved.supports_anthropic_wire():
        yield from anthropic_compat.openai_sse_data_frames(response)
        return
    for value in iter_sse_data_events(response):
        if value == "[DONE]":
            continue
        try:
            decoded = json.loads(value)
        except json.JSONDecodeError:
            continue
        if isinstance(decoded, dict):
            yield decoded


def extract_message(data: dict[str, Any]) -> tuple[str, str]:
    """Splits a full OpenAI-shaped completion into (answer content, private reasoning).

    reasoning_content is the OpenAI-compatible field where both deepseek-style relays and
    the Anthropic bridge report hidden thinking. Callers persist it into private
    diagnostics only; it must never enter visible content, checkpoints or SSE.
    """
    message = ((data.get("choices") or [{}])[0]).get("message") or {}
    content = str(message.get("content") or "")
    reasoning = str(message.get("reasoning_content") or "")
    return content, reasoning


def delta_fields(choice: dict[str, Any]) -> tuple[str | None, str | None]:
    """Extracts (content_delta, reasoning_delta) from one streamed OpenAI choice.

    Runtimes must only yield `content` to visible streams; `reasoning_content` is the
    private thinking channel (deepseek relays and the Anthropic bridge both report here).
    """
    delta = choice.get("delta") or {}
    content = delta.get("content")
    reasoning = delta.get("reasoning_content")
    return (str(content) if content else None, str(reasoning) if reasoning else None)


def apply_json_object_mode(resolved: ProviderProfile, payload: dict[str, Any]) -> dict[str, Any]:
    """Adds response_format json_object only where the endpoint supports it (GLM has none)."""
    if resolved.json_object_mode:
        payload["response_format"] = {"type": "json_object"}
    return payload


def apply_thinking_off(resolved: ProviderProfile, payload: dict[str, Any]) -> dict[str, Any]:
    """Disables hidden thinking where the provider exposes a toggle; forced-thinking is a no-op.

    Operators globally control this with MATH_AGENT_WORKER_DISABLE_THINKING (default true):
    reasoning-model tokens share the completion budget, and the handout/workload contracts
    need the visible answer inside a bounded window.
    """
    if resolved.thinking_toggle_param is None or resolved.forced_thinking:
        return payload
    flag = os.getenv("MATH_AGENT_WORKER_DISABLE_THINKING", "true")
    if flag.strip().lower() in {"1", "true", "yes"}:
        payload[resolved.thinking_toggle_param] = False
    return payload
