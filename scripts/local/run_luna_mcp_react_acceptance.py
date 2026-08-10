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
import shutil
import subprocess
import sys
import time
import traceback
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
# ReAct turns only decide the next MCP action and historically complete within seconds.  Keeping this separate from
# the workflow/PDF polling budget makes a relay stall visible promptly instead of masking it as a ten-minute run.
DEFAULT_MODEL_TIMEOUT_SECONDS = 90
DEFAULT_STATUS_POLL_INTERVAL_SECONDS = 5
MCP_PROTOCOL_VERSION = "2025-11-25"
APPLICATION_USER_AGENT = "math-agent-rag-luna-react/1.0"
TERMINAL_STATUSES = {"completed", "failed", "cancelled", "canceled"}
SENSITIVE_ARGUMENT_NAMES = {"authorization", "api_key", "apikey", "password", "secret", "secretkey", "token"}
COOKIE_JAR = urllib.request.HTTPCookieProcessor()
HTTP_OPENER = urllib.request.build_opener(COOKIE_JAR)


def utc_now() -> str:
    """Return an ISO timestamp for stable cross-machine acceptance records."""
    return datetime.now(timezone.utc).isoformat()


class LiveTrace:
    """Durably mirrors each acceptance boundary to stdout and JSONL.

    The real provider or worker may outlive a terminal timeout.  Writing and flushing each
    event immediately means the last observable boundary is retained for root-cause analysis.
    """

    def __init__(self, path: Path) -> None:
        self.path = path
        self.path.parent.mkdir(parents=True, exist_ok=True)

    def emit(self, event: str, **fields: Any) -> None:
        entry = {"at": utc_now(), "event": event, **compact_value(fields)}
        line = json.dumps(entry, ensure_ascii=False)
        print(line, flush=True)
        with self.path.open("a", encoding="utf-8") as stream:
            stream.write(line + "\n")
            stream.flush()
            os.fsync(stream.fileno())


HTTP_TRACE: LiveTrace | None = None


def http_trace(event: str, **fields: Any) -> None:
    """Emit per-request transport evidence without copying credentials or request bodies."""
    if HTTP_TRACE is not None:
        HTTP_TRACE.emit(event, **fields)


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
    started = time.perf_counter()
    method = "POST" if data is not None else "GET"
    try:
        with HTTP_OPENER.open(request, timeout=timeout) as response:
            body = response.read().decode("utf-8-sig")
            headers = {key.lower(): value for key, value in response.headers.items()}
            headers["x-acceptance-http-status"] = str(response.status)
            http_trace("http.response", method=method, url=url, statusCode=response.status,
                       latencyMs=round((time.perf_counter() - started) * 1000))
            return json.loads(body), headers
    except urllib.error.HTTPError as error:
        detail = error.read().decode("utf-8", errors="replace")
        http_trace("http.response", method=method, url=url, statusCode=error.code,
                   latencyMs=round((time.perf_counter() - started) * 1000),
                   failureKind="http_error", responseExcerpt=detail[:500])
        raise RuntimeError(f"HTTP {error.code} from {url}: {detail[:1000]}") from error
    except urllib.error.URLError as error:
        http_trace("http.transport_failure", method=method, url=url,
                   latencyMs=round((time.perf_counter() - started) * 1000),
                   failureKind="network_or_dns", errorType=type(error.reason).__name__)
        raise RuntimeError(f"Network error from {url}: {type(error.reason).__name__}") from error
    except TimeoutError as error:
        http_trace("http.transport_failure", method=method, url=url,
                   latencyMs=round((time.perf_counter() - started) * 1000),
                   failureKind="client_timeout", errorType=type(error).__name__)
        raise RuntimeError(f"Client timeout from {url}") from error


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
        {},
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


def model_tool_context(name: str, result: Any, is_error: bool) -> Any:
    """Give Luna identifiers and state, while keeping large evidence/artifacts in the audit record."""
    summary = tool_result_summary(name, result, is_error)
    if name == "search_multi_source_evidence" and isinstance(result, dict):
        summary["evidenceRefs"] = list(result.get("evidenceRefs") or [])[:4]
    if name == "get_multi_agent_writing_artifact" and isinstance(result, dict):
        summary["artifactAvailable"] = True
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


def workflow_id_from_record(record: dict[str, Any]) -> str:
    """Find the completed workflow id from persisted MCP tool summaries without trusting model prose."""
    for turn in reversed(record.get("turns", [])):
        for call in reversed(turn.get("toolCalls", [])):
            result = call.get("result") or {}
            if call.get("name") == "get_multi_agent_writing_status" and result.get("status") == "COMPLETED":
                return str(result.get("workflowId") or "")
    raise RuntimeError("Completed workflow id is missing from the MCP record")


def windows_pdf_tool(name: str) -> str:
    """Resolve a real Windows Poppler tool for screenshots and text checks, never a browser simulation."""
    configured = os.getenv(name, "").strip()
    if configured and Path(configured).is_file():
        return configured
    executable = f"{name.lower().replace('_bin', '')}.exe"
    miktex = Path(os.getenv("MIKTEX_BIN", r"C:\Users\doob\AppData\Local\Programs\MiKTeX\miktex\bin\x64")) / executable
    return str(miktex) if miktex.is_file() else shutil.which(executable.removesuffix(".exe")) or executable


def export_and_audit_pdfs(client: McpClient, record: dict[str, Any], evidence_dir: Path) -> list[dict[str, Any]]:
    """Export all three owned variants, verify checksums, and create Windows-rendered page PNG evidence."""
    workflow_id = workflow_id_from_record(record)
    source = (record.get("sharedRootEvidence") or [{}])[0]
    source_title = str(source.get("title") or "").strip()
    text_tool = windows_pdf_tool("PDFTOTEXT_BIN")
    render_tool = windows_pdf_tool("PDFTOPPM_BIN")
    auditor = Path(__file__).with_name("audit_handout_layout.py")
    outputs: list[dict[str, Any]] = []
    for variant, export_format in (("teacher", "pdf-teacher"), ("student", "pdf-student"), ("lecture", "pdf-lecture")):
        result, is_error = client.call_tool("export_multi_agent_writing_artifact", {
            "workflowId": workflow_id, "format": export_format,
        })
        if is_error:
            raise RuntimeError(f"{variant} PDF export failed: {compact_value(result)}")
        variant_dir = evidence_dir / "pdf" / variant
        exported = save_export(result, variant_dir)
        if exported is None:
            raise RuntimeError(f"{variant} PDF export returned no bytes")
        pdf = Path(exported["path"])
        rendered_prefix = variant_dir / "page"
        subprocess.run([render_tool, "-png", "-r", "144", str(pdf), str(rendered_prefix)], check=True, capture_output=True)
        # Poppler emits UTF-8 but Windows may select GBK by default; force UTF-8 for Chinese PDF evidence.
        extracted = subprocess.run([text_tool, "-layout", str(pdf), "-"], check=True, capture_output=True,
                                   text=True, encoding="utf-8", errors="replace").stdout
        (variant_dir / "extracted.txt").write_text(extracted, encoding="utf-8")
        command = [sys.executable, str(auditor), str(pdf), "--profile", variant, "--output", str(variant_dir / "layout-audit.json")]
        if variant == "teacher":
            if not source_title:
                raise RuntimeError("Shared-root search did not return a readable source title")
            command.extend(["--required-text", source_title])
        audit = subprocess.run(command, capture_output=True, text=True, encoding="utf-8", errors="replace")
        (variant_dir / "layout-audit.stdout.txt").write_text(audit.stdout + audit.stderr, encoding="utf-8")
        if audit.returncode != 0:
            raise RuntimeError(f"{variant} PDF layout gate failed; see {variant_dir / 'layout-audit.stdout.txt'}")
        outputs.append({"variant": variant, "export": exported, "pdf": str(pdf), "textChars": len(extracted),
                        "renderedPages": sorted(path.name for path in variant_dir.glob("page-*.png"))})
    return outputs


def main() -> int:
    """Execute the bounded ReAct loop and persist a complete acceptance record."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--backend-url", default=os.getenv("MATH_AGENT_BACKEND_URL", DEFAULT_BACKEND_URL))
    parser.add_argument("--mcp-url", default=os.getenv("MATH_AGENT_MCP_URL", DEFAULT_MCP_URL))
    parser.add_argument("--model", default=os.getenv("MATH_AGENT_REACT_MODEL", DEFAULT_MODEL))
    parser.add_argument("--max-turns", type=int, default=DEFAULT_MAX_TURNS)
    parser.add_argument("--timeout", type=int, default=DEFAULT_HTTP_TIMEOUT_SECONDS)
    parser.add_argument(
        "--model-timeout",
        type=int,
        default=int(os.getenv("MATH_AGENT_REACT_MODEL_TIMEOUT_SECONDS", DEFAULT_MODEL_TIMEOUT_SECONDS)),
        help="Hard timeout for one Luna ReAct decision; workflow and MCP calls continue to use --timeout.",
    )
    parser.add_argument("--record", type=Path)
    args = parser.parse_args()
    if args.model != DEFAULT_MODEL:
        raise RuntimeError(f"Luna acceptance requires model={DEFAULT_MODEL}; received {args.model}")

    project_root = Path(__file__).resolve().parents[2]
    evidence_dir = Path(os.getenv(
        "MATH_AGENT_ACCEPTANCE_EVIDENCE_DIR",
        project_root / "output" / "acceptance" / f"{datetime.now().date().isoformat()}-luna-evidence",
    ))
    record_path = args.record or evidence_dir / "luna-mcp-react.json"
    output_dir = evidence_dir / "exports"
    global HTTP_TRACE
    trace = LiveTrace(evidence_dir / "luna-mcp-react.live.jsonl")
    HTTP_TRACE = trace
    trace.emit("acceptance.started", backendUrl=args.backend_url, mcpUrl=args.mcp_url,
               model=args.model, httpTimeoutSeconds=args.timeout, modelTimeoutSeconds=args.model_timeout,
               maxTurns=args.max_turns)
    api_key = os.getenv("OPENAI_API_KEY", "").strip()
    base_url = os.getenv("OPENAI_BASE_URL", "").strip()
    if not api_key or not base_url:
        raise RuntimeError("OPENAI_API_KEY and OPENAI_BASE_URL must be configured in the environment")

    mcp_secret = os.getenv("MATH_AGENT_MCP_SECRET", "").strip()
    key_id = "environment-provided"
    if not mcp_secret:
        trace.emit("auth.login.started")
        mcp_secret, key_id = create_admin_mcp_key(args.backend_url, args.timeout)
        trace.emit("auth.login.completed", keyId=key_id)

    client = McpClient(args.mcp_url, mcp_secret, args.timeout)
    trace.emit("mcp.initialize.started")
    initialized = client.request(
        "initialize",
        {
            "protocolVersion": MCP_PROTOCOL_VERSION,
            "capabilities": {},
            "clientInfo": {"name": "math-agent-luna-react-acceptance", "version": "1.0.0"},
        },
    )
    trace.emit("mcp.initialize.completed", responseStatus=(initialized.get("result") or {}).get("protocolVersion"))
    trace.emit("mcp.tools_list.started")
    tools_response = client.request("tools/list", {})
    discovered_tools = (tools_response.get("result") or {}).get("tools") or []
    required = {
        "search_multi_source_evidence", "start_multi_agent_writing", "get_multi_agent_writing_status",
        "get_multi_agent_writing_artifact", "export_multi_agent_writing_artifact",
    }
    exposed = {tool.get("name") for tool in discovered_tools}
    missing = sorted(required - exposed)
    trace.emit("mcp.tools_list.completed", discoveredToolCount=len(discovered_tools), missingRequiredTools=missing)
    if missing:
        raise RuntimeError(f"MCP admin profile is missing required tools: {missing}")

    system_prompt = (
        "你是一个通过真实 MCP 工具工作的 ReAct 验收代理。不要输出隐藏思维过程，只输出简短进度。"
        "必须依次完成：检索 teacher_resource 中的‘解三角形 向量 面积’；使用检索返回的 evidenceRefs 启动教师讲义多智能体写作，"
        f"provider=openai、model={args.model}；轮询状态直到 completed（运行中就继续调用状态工具）；读取讲义 artifact；"
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
        "sharedRootEvidence": [],
    }
    # Create the snapshot before the first provider request; an interrupted run remains inspectable.
    record_path.parent.mkdir(parents=True, exist_ok=True)
    record_path.write_text(json.dumps(record, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    called_tools: list[str] = []
    final_text = ""
    total_usage: dict[str, int] = {}

    loop_exhausted = True
    for turn_number in range(1, args.max_turns + 1):
        model_started = time.perf_counter()
        trace.emit("model.request.started", turn=turn_number, messageCount=len(messages), toolSchemaCount=len(openai_tools))
        try:
            assistant, usage = call_model(
                chat_completions_url(base_url), api_key, args.model, messages, openai_tools, args.model_timeout
            )
        except RuntimeError as error:
            trace.emit("model.request.failed", turn=turn_number,
                       latencyMs=round((time.perf_counter() - model_started) * 1000), error=str(error)[:2000])
            # Provider capacity is an acceptance result, not a runner crash. Persist the exact bounded relay response
            # so nobody can replace Luna with another model and still label the run as a Luna pass.
            record.update({
                "completedAt": utc_now(),
                "status": "provider_model_unavailable",
                "calledTools": called_tools,
                "missingRequiredCalls": sorted(required - set(called_tools)),
                "modelUsage": total_usage,
                "finalVisibleText": "",
                "loopExhausted": False,
                "providerError": str(error)[:2000],
                "credentialsPersisted": False,
            })
            record_path.parent.mkdir(parents=True, exist_ok=True)
            record_path.write_text(json.dumps(record, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
            print(json.dumps({
                "status": record["status"],
                "record": str(record_path.resolve()),
                "exports": [],
            }, ensure_ascii=False))
            return 2
        model_latency_ms = round((time.perf_counter() - model_started) * 1000)
        trace.emit("model.request.completed", turn=turn_number, latencyMs=model_latency_ms,
                   visibleText=(assistant.get("content") or "")[:600], toolCallCount=len(assistant.get("tool_calls") or []), usage=usage)
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
            tool_started = time.perf_counter()
            trace.emit("mcp.tool.started", turn=turn_number, tool=name, arguments=sanitized_arguments(arguments))
            result, is_error = client.call_tool(name, arguments)
            tool_latency_ms = round((time.perf_counter() - tool_started) * 1000)
            trace.emit("mcp.tool.completed", turn=turn_number, tool=name, latencyMs=tool_latency_ms,
                       isError=is_error, result=tool_result_summary(name, result, is_error))
            called_tools.append(name)
            polls: list[dict[str, Any]] = []
            if name == "get_multi_agent_writing_status" and not is_error:
                # Luna chooses the status action once. The MCP executor then performs ordinary asynchronous polling
                # without spending another full model turn for every unchanged RUNNING response.
                poll_deadline = time.monotonic() + args.timeout
                while (
                    isinstance(result, dict)
                    and str(result.get("status") or "").lower() not in TERMINAL_STATUSES
                    and time.monotonic() < poll_deadline
                ):
                    time.sleep(DEFAULT_STATUS_POLL_INTERVAL_SECONDS)
                    poll_started = time.perf_counter()
                    trace.emit("workflow.poll.started", workflowId=arguments.get("workflowId"), poll=len(polls) + 1)
                    result, is_error = client.call_tool(name, arguments)
                    called_tools.append(name)
                    polls.append({
                        "latencyMs": round((time.perf_counter() - poll_started) * 1000),
                        "result": tool_result_summary(name, result, is_error),
                    })
                    trace.emit("workflow.poll.completed", workflowId=arguments.get("workflowId"), poll=len(polls),
                               latencyMs=polls[-1]["latencyMs"], isError=is_error, result=polls[-1]["result"])
                    if is_error:
                        break
            exported = save_export(result, output_dir) if name == "export_multi_agent_writing_artifact" and not is_error else None
            if exported:
                record["exports"].append(exported)
            if name == "search_multi_source_evidence" and isinstance(result, dict) and not is_error:
                # Keep only the source proof required for a later body-citation assertion; this is public metadata,
                # not a credential or a full private document export.
                record["sharedRootEvidence"] = [
                    {
                        "title": hit.get("title"),
                        "documentId": hit.get("documentId"),
                        "blockId": hit.get("blockId"),
                        "evidenceExcerpt": str(hit.get("evidenceText") or "")[:240],
                        "imageAssetIds": hit.get("imageAssetIds") or [],
                    }
                    for hit in (result.get("hits") or result.get("mergedHits") or [])[:4]
                    if isinstance(hit, dict) and str(hit.get("sourceType") or "").lower() == "feishu"
                ]
            summary = tool_result_summary(name, result, is_error)
            tool_record = {
                    "id": tool_call.get("id"),
                    "name": name,
                    "arguments": sanitized_arguments(arguments),
                    "latencyMs": tool_latency_ms,
                    "result": summary,
                }
            if polls:
                tool_record["polls"] = polls
            turn_record["toolCalls"].append(tool_record)
            messages.append(
                {
                    "role": "tool",
                    "tool_call_id": tool_call.get("id"),
                    "content": json.dumps(model_tool_context(name, result, is_error), ensure_ascii=False),
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
        record_path.write_text(json.dumps(record, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        trace.emit("record.checkpoint", turn=turn_number, completedTurns=len(record["turns"]))

    missing_calls = sorted(required - set(called_tools))
    success = not loop_exhausted and not missing_calls and bool(record["exports"])
    if success:
        try:
            if not record["sharedRootEvidence"]:
                raise RuntimeError("MCP search did not return a Feishu shared-root evidence hit")
            # Relevance-ranked text hits need not carry an image even when the shared root has image assets.
            # Preserve that observable fact, but do not reject a valid source-cited PDF solely for this mismatch.
            record["sharedRootImageHitPresent"] = any(item.get("imageAssetIds") for item in record["sharedRootEvidence"])
            record["pdfExports"] = export_and_audit_pdfs(client, record, evidence_dir)
            trace.emit("pdf.audit.completed", variants=record["pdfExports"])
        except Exception as error:
            record["postWorkflowValidationTraceback"] = traceback.format_exc(limit=12)
            trace.emit("pdf.audit.failed", error=str(error)[:2000], traceback=record["postWorkflowValidationTraceback"])
            success = False
            record["postWorkflowValidationError"] = str(error)
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
    trace.emit("acceptance.completed", status=record["status"], record=str(record_path.resolve()),
               modelUsage=total_usage, missingRequiredCalls=missing_calls)
    print(json.dumps({"status": record["status"], "record": str(record_path.resolve()), "exports": record["exports"]}, ensure_ascii=False))
    return 0 if success else 1


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as error:  # The CLI emits one actionable error while avoiding credential-bearing request dumps.
        print(f"acceptance failed: {error}", file=sys.stderr)
        raise SystemExit(1)
