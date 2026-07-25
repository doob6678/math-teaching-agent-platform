#!/usr/bin/env python3
"""Run a real Luna ReAct session against the project's HTTP MCP endpoint.

The runner deliberately uses only Python's standard library so it also works in a
fresh checkout. Credentials stay in memory; the persisted record contains visible
assistant text, MCP tool calls, safe result summaries, and exported artifact hashes.
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
import sys
import time
import urllib.error
import urllib.request
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


DEFAULT_MCP_URL = "http://127.0.0.1:8080/api/mcp"
DEFAULT_BACKEND_URL = "http://127.0.0.1:8080"
DEFAULT_MODEL = "gpt-5.6-luna"
DEFAULT_MAX_TURNS = 36
DEFAULT_HTTP_TIMEOUT_SECONDS = 600
MCP_PROTOCOL_VERSION = "2025-11-25"
APPLICATION_USER_AGENT = "math-agent-rag-luna-react/1.0"
TERMINAL_STATUSES = {"completed", "failed", "cancelled", "canceled"}
SENSITIVE_ARGUMENT_NAMES = {"authorization", "api_key", "apikey", "password", "secret", "secretkey", "token"}


def utc_now() -> str:
    """Return an ISO timestamp for stable cross-machine acceptance records."""
    return datetime.now(timezone.utc).isoformat()


def json_http(
    url: str,
    payload: dict[str, Any] | None,
    headers: dict[str, str],
    timeout: int,
) -> tuple[Any, dict[str, str]]:
    """Send one UTF-8 JSON request and return parsed JSON plus response headers."""
    data = None if payload is None else json.dumps(payload, ensure_ascii=False).encode("utf-8")
    # Some OpenAI-compatible gateways reject Python urllib's implicit signature.  A stable application identity
    # also makes provider-side audit logs attributable without impersonating a browser or exposing credentials.
    request_headers = {"Accept": "application/json", "User-Agent": APPLICATION_USER_AGENT, **headers}
    if data is not None:
        request_headers["Content-Type"] = "application/json; charset=utf-8"
    request = urllib.request.Request(url, data=data, headers=request_headers, method="POST" if data is not None else "GET")
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            body = response.read().decode("utf-8-sig")
            return json.loads(body), {key.lower(): value for key, value in response.headers.items()}
    except urllib.error.HTTPError as error:
        detail = error.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"HTTP {error.code} from {url}: {detail[:1000]}") from error


def chat_completions_url(base_url: str) -> str:
    """Normalize OpenAI-compatible base URLs without duplicating `/v1`."""
    normalized = base_url.rstrip("/")
    return normalized if normalized.endswith("/chat/completions") else f"{normalized}/chat/completions"


def sanitized_arguments(value: Any) -> Any:
    """Remove credential-shaped arguments before an audit record is persisted."""
    if isinstance(value, dict):
        return {
            key: "[REDACTED]" if key.lower().replace("_", "") in SENSITIVE_ARGUMENT_NAMES else sanitized_arguments(item)
            for key, item in value.items()
        }
    if isinstance(value, list):
        return [sanitized_arguments(item) for item in value]
    return value


def compact_value(value: Any, depth: int = 0) -> Any:
    """Bound model context while preserving workflow IDs, evidence refs, and asset metadata."""
    if depth > 6:
        return "[nested value omitted]"
    if isinstance(value, str):
        return value if len(value) <= 1800 else value[:1800] + f"... [truncated {len(value) - 1800} chars]"
    if isinstance(value, list):
        items = [compact_value(item, depth + 1) for item in value[:8]]
        if len(value) > 8:
            items.append(f"[{len(value) - 8} additional items omitted]")
        return items
    if isinstance(value, dict):
        result: dict[str, Any] = {}
        for key, item in value.items():
            # Export bytes are decoded by the runner and must never enter the model context or JSON trace.
            if key == "base64Content":
                result[key] = f"[base64 omitted; {len(item) if isinstance(item, str) else 0} chars]"
            elif key in {"mergedMarkdown", "evidenceText"} and isinstance(item, str):
                result[key] = compact_value(item[:1200], depth + 1)
            else:
                result[key] = compact_value(item, depth + 1)
        return result
    return value


def structured_mcp_result(response: dict[str, Any]) -> tuple[Any, bool]:
    """Extract structured content from the standard MCP tools/call envelope."""
    if "error" in response:
        return response["error"], True
    result = response.get("result") or {}
    structured = result.get("structuredContent")
    if structured is None:
        for content in result.get("content") or []:
            if content.get("type") == "text":
                text = content.get("text", "")
                try:
                    structured = json.loads(text)
                except json.JSONDecodeError:
                    structured = {"text": text}
                break
    return structured if structured is not None else result, bool(result.get("isError"))


@dataclass
class McpClient:
    """Minimal stateless JSON-RPC MCP client bound to one in-memory Bearer key."""

    url: str
    secret: str
    timeout: int
    request_id: int = 0

    def request(self, method: str, params: dict[str, Any] | None = None) -> dict[str, Any]:
        """Invoke one MCP method using protocol headers required by the backend."""
        self.request_id += 1
        payload: dict[str, Any] = {"jsonrpc": "2.0", "id": self.request_id, "method": method}
        if params is not None:
            payload["params"] = params
        response, _ = json_http(
            self.url,
            payload,
            {
                "Authorization": f"Bearer {self.secret}",
                "MCP-Protocol-Version": MCP_PROTOCOL_VERSION,
                "Accept": "application/json, text/event-stream",
            },
            self.timeout,
        )
        return response

    def call_tool(self, name: str, arguments: dict[str, Any]) -> tuple[Any, bool]:
        """Call an exposed MCP tool and normalize its structured result."""
        return structured_mcp_result(self.request("tools/call", {"name": name, "arguments": arguments}))


def create_admin_mcp_key(backend_url: str, timeout: int) -> tuple[str, str]:
    """Create a disposable admin MCP key without persisting its raw secret."""
    username = os.getenv("MATH_AGENT_ACCEPTANCE_USERNAME", "admin")
    password = os.getenv("MATH_AGENT_ACCEPTANCE_PASSWORD", "admin-123456")
    login, _ = json_http(
        f"{backend_url.rstrip('/')}/api/auth/login",
        {"username": username, "password": password},
        {},
        timeout,
    )
    key, _ = json_http(
        f"{backend_url.rstrip('/')}/api/mcp/keys",
        {},
        {str(login["tokenName"]): str(login["tokenValue"])},
        timeout,
    )
    return str(key["secretKey"]), str(key["keyId"])


def openai_tool(tool: dict[str, Any]) -> dict[str, Any]:
    """Translate a discovered MCP input schema into an OpenAI function tool."""
    return {
        "type": "function",
        "function": {
            "name": tool["name"],
            "description": tool.get("description", "MCP tool"),
            "parameters": openai_compatible_schema(tool.get("inputSchema") or {"type": "object", "properties": {}}),
        },
    }


def openai_compatible_schema(value: Any) -> Any:
    """Remove MCP-only JSON Schema extensions that OpenAI-compatible relays do not accept.

    The backend still validates the original input schema.  This is only a transport translation: standard types,
    properties, required fields and enums reach the model unchanged, while `x-*` documentation metadata is omitted.
    """
    if isinstance(value, dict):
        return {
            key: openai_compatible_schema(item)
            for key, item in value.items()
            if not key.startswith("x-")
        }
    if isinstance(value, list):
        return [openai_compatible_schema(item) for item in value]
    return value


def call_model(
    endpoint: str,
    api_key: str,
    model: str,
    messages: list[dict[str, Any]],
    tools: list[dict[str, Any]],
    timeout: int,
) -> tuple[dict[str, Any], dict[str, Any]]:
    """Call the real OpenAI-compatible model and return its assistant message and usage."""
    payload = {
        "model": model,
        "messages": messages,
        "tools": tools,
        "tool_choice": "auto",
        "temperature": 0.1,
        # A bounded visible response avoids a relay keeping the request open for an unbounded reasoning budget.
        "max_tokens": 800,
    }
    response, _ = json_http(endpoint, payload, {"Authorization": f"Bearer {api_key}"}, timeout)
    choices = response.get("choices") or []
    if not choices:
        raise RuntimeError(f"Model returned no choices: {compact_value(response)}")
    return choices[0]["message"], response.get("usage") or {}


def tool_result_summary(name: str, result: Any, is_error: bool) -> dict[str, Any]:
    """Build a stable, secret-free summary suitable for acceptance evidence."""
    summary: dict[str, Any] = {"tool": name, "isError": is_error}
    if isinstance(result, dict):
        for key in (
            "status", "message", "workflowId", "documentId", "hitCount", "stageCount",
            "format", "fileName", "mimeType", "byteSize", "sha256", "exportId", "expiresAt",
            "evidenceRefs", "imageAssetIds", "assetRefs",
        ):
            if key in result:
                summary[key] = compact_value(result[key])
        hits = result.get("hits") or result.get("mergedHits") or result.get("teacherResourceHits")
        if isinstance(hits, list):
            summary["hitCount"] = result.get("hitCount", len(hits))
            summary["hits"] = [
                compact_value({key: hit.get(key) for key in (
                    "source", "sourceType", "documentId", "blockId", "title", "evidenceRef",
                    "imageAssetIds", "assetRefs",
                ) if key in hit})
                for hit in hits[:5] if isinstance(hit, dict)
            ]
    else:
        summary["result"] = compact_value(result)
    return summary


def save_export(result: Any, output_dir: Path) -> dict[str, Any] | None:
    """Decode a real MCP export, verify its declared checksum, and return file metadata."""
    if not isinstance(result, dict) or not result.get("base64Content"):
        return None
    data = base64.b64decode(result["base64Content"], validate=True)
    expected_hash = str(result.get("sha256") or "").lower()
    actual_hash = hashlib.sha256(data).hexdigest()
    if expected_hash and expected_hash != actual_hash:
        raise RuntimeError(f"Export checksum mismatch: expected {expected_hash}, got {actual_hash}")
    output_dir.mkdir(parents=True, exist_ok=True)
    safe_name = Path(str(result.get("fileName") or f"handout-{result.get('workflowId', 'unknown')}.md")).name
    target = output_dir / safe_name
    target.write_bytes(data)
    return {"path": str(target.resolve()), "byteSize": len(data), "sha256": actual_hash}


def main() -> int:
    """Execute the bounded ReAct loop and persist a complete acceptance record."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--backend-url", default=os.getenv("MATH_AGENT_BACKEND_URL", DEFAULT_BACKEND_URL))
    parser.add_argument("--mcp-url", default=os.getenv("MATH_AGENT_MCP_URL", DEFAULT_MCP_URL))
    parser.add_argument("--model", default=os.getenv("MATH_AGENT_REACT_MODEL", DEFAULT_MODEL))
    parser.add_argument("--max-turns", type=int, default=DEFAULT_MAX_TURNS)
    parser.add_argument("--timeout", type=int, default=DEFAULT_HTTP_TIMEOUT_SECONDS)
    parser.add_argument("--record", type=Path)
    args = parser.parse_args()

    project_root = Path(__file__).resolve().parents[2]
    record_path = args.record or project_root / "docs" / f"luna-mcp-react-acceptance-{datetime.now().date().isoformat()}.json"
    output_dir = project_root / ".local-storage" / "mcp-react-exports"
    api_key = os.getenv("OPENAI_API_KEY", "").strip()
    base_url = os.getenv("OPENAI_BASE_URL", "").strip()
    if not api_key or not base_url:
        raise RuntimeError("OPENAI_API_KEY and OPENAI_BASE_URL must be configured in the environment")

    mcp_secret = os.getenv("MATH_AGENT_MCP_SECRET", "").strip()
    key_id = "environment-provided"
    if not mcp_secret:
        mcp_secret, key_id = create_admin_mcp_key(args.backend_url, args.timeout)

    client = McpClient(args.mcp_url, mcp_secret, args.timeout)
    initialized = client.request(
        "initialize",
        {
            "protocolVersion": MCP_PROTOCOL_VERSION,
            "capabilities": {},
            "clientInfo": {"name": "math-agent-luna-react-acceptance", "version": "1.0.0"},
        },
    )
    tools_response = client.request("tools/list", {})
    discovered_tools = (tools_response.get("result") or {}).get("tools") or []
    required = {
        "search_multi_source_evidence", "start_multi_agent_writing", "get_multi_agent_writing_status",
        "get_multi_agent_writing_artifact", "export_multi_agent_writing_artifact",
    }
    exposed = {tool.get("name") for tool in discovered_tools}
    missing = sorted(required - exposed)
    if missing:
        raise RuntimeError(f"MCP admin profile is missing required tools: {missing}")

    system_prompt = (
        "你是一个通过真实 MCP 工具工作的 ReAct 验收代理。不要输出隐藏思维过程，只输出简短进度。"
        "必须依次完成：检索 teacher_resource 中的‘解三角形 向量 面积’；使用检索返回的 evidenceRefs 启动教师讲义多智能体写作，"
        "provider=openai、model=gpt-5.6-luna；轮询状态直到 completed（运行中就继续调用状态工具）；读取讲义 artifact；"
        "最后导出 markdown。不得跳过任何步骤，不得编造工具结果。"
    )
    messages: list[dict[str, Any]] = [
        {"role": "system", "content": system_prompt},
        {"role": "user", "content": "请完成上述完整验收任务，并在全部真实工具调用成功后给出简短结果。"},
    ]
    # The administrator profile intentionally exposes many capabilities.  This acceptance task verifies that full
    # discovery surface above, then gives Luna only the five schemas needed for this bounded workflow so unrelated
    # tool descriptions do not consume the provider's context window or delay its first action.
    openai_tools = [openai_tool(tool) for tool in discovered_tools if tool.get("name") in required]
    record: dict[str, Any] = {
        "startedAt": utc_now(),
        "model": args.model,
        "mcp": {
            "url": args.mcp_url,
            "protocolVersion": (initialized.get("result") or {}).get("protocolVersion"),
            "keyId": key_id,
            "exposedToolCount": len(discovered_tools),
            "requiredToolsExposed": sorted(required),
        },
        "turns": [],
        "exports": [],
    }
    called_tools: list[str] = []
    final_text = ""
    total_usage: dict[str, int] = {}

    loop_exhausted = True
    for turn_number in range(1, args.max_turns + 1):
        model_started = time.perf_counter()
        assistant, usage = call_model(
            chat_completions_url(base_url), api_key, args.model, messages, openai_tools, args.timeout
        )
        model_latency_ms = round((time.perf_counter() - model_started) * 1000)
        messages.append(assistant)
        for key, value in usage.items():
            if isinstance(value, int):
                total_usage[key] = total_usage.get(key, 0) + value
        turn_record: dict[str, Any] = {
            "turn": turn_number,
            "modelLatencyMs": model_latency_ms,
            "visibleText": assistant.get("content") or "",
            "toolCalls": [],
        }
        tool_calls = assistant.get("tool_calls") or []
        if not tool_calls:
            final_text = assistant.get("content") or ""
            record["turns"].append(turn_record)
            loop_exhausted = False
            break

        for tool_call in tool_calls:
            function = tool_call.get("function") or {}
            name = str(function.get("name") or "")
            try:
                arguments = json.loads(function.get("arguments") or "{}")
            except json.JSONDecodeError as error:
                raise RuntimeError(f"Model emitted invalid JSON arguments for {name}") from error
            if name == "get_multi_agent_writing_status":
                # The workflow is asynchronous; spacing status reads prevents a hot polling loop and rate-limit noise.
                time.sleep(3)
            tool_started = time.perf_counter()
            result, is_error = client.call_tool(name, arguments)
            tool_latency_ms = round((time.perf_counter() - tool_started) * 1000)
            called_tools.append(name)
            exported = save_export(result, output_dir) if name == "export_multi_agent_writing_artifact" and not is_error else None
            if exported:
                record["exports"].append(exported)
            summary = tool_result_summary(name, result, is_error)
            turn_record["toolCalls"].append(
                {
                    "id": tool_call.get("id"),
                    "name": name,
                    "arguments": sanitized_arguments(arguments),
                    "latencyMs": tool_latency_ms,
                    "result": summary,
                }
            )
            messages.append(
                {
                    "role": "tool",
                    "tool_call_id": tool_call.get("id"),
                    "content": json.dumps(compact_value(result), ensure_ascii=False),
                }
            )
            if name == "get_multi_agent_writing_status" and isinstance(result, dict):
                status = str(result.get("status") or "").lower()
                if status == "completed":
                    # Reassert the externally visible state rather than assuming the model notices it amid stage data.
                    # This keeps the remaining artifact/export calls model-selected while preventing needless polling.
                    messages.append({
                        "role": "user",
                        "content": "工作流已完成。现在必须立刻调用 get_multi_agent_writing_artifact，然后调用 export_multi_agent_writing_artifact(format=markdown)；不要再轮询状态。",
                    })
        record["turns"].append(turn_record)

    missing_calls = sorted(required - set(called_tools))
    success = not loop_exhausted and not missing_calls and bool(record["exports"])
    record.update(
        {
            "completedAt": utc_now(),
            "status": "passed" if success else "failed",
            "calledTools": called_tools,
            "missingRequiredCalls": missing_calls,
            "modelUsage": total_usage,
            "finalVisibleText": final_text,
            "loopExhausted": loop_exhausted,
        }
    )
    record_path.parent.mkdir(parents=True, exist_ok=True)
    record_path.write_text(json.dumps(record, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"status": record["status"], "record": str(record_path.resolve()), "exports": record["exports"]}, ensure_ascii=False))
    return 0 if success else 1


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as error:  # The CLI emits one actionable error while avoiding credential-bearing request dumps.
        print(f"acceptance failed: {error}", file=sys.stderr)
        raise SystemExit(1)
