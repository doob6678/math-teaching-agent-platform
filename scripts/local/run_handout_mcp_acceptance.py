#!/usr/bin/env python3
"""Run one real, durable MCP handout/PDF acceptance flow against the current stack.

This command intentionally has no POST retry. If task submission is uncertain, it
writes a non-secret correlation record and uses read-only MCP status/list evidence;
operators must resume that evidence rather than run another submission.
"""
from __future__ import annotations

import argparse
import base64
import hashlib
import hmac
import json
import os
import re
import secrets
import shlex
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from datetime import datetime, timezone
from http.cookiejar import CookieJar
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[2]
PROTOCOL = "2025-11-25"
TERMINAL = {"COMPLETED", "FAILED", "WAITING_REVIEW", "DRAFT_ONLY", "CANCELLED", "CANCELED"}
FRESH_FAILURE_WORDS = ("cache", "cached", "memory reuse", "reused memory", "reused")
CREDENTIAL_KEY_NAMES = frozenset({
    "password",
    "secret",
    "secretkey",
    "token",
    "authorization",
    "apikey",
    "api_key",
    "cookie",
})
TOPICS = (
    ("parabola", "抛物线的定义、标准方程与焦点弦", "讲解抛物线的定义、标准方程与焦点弦的来源。"),
    ("hyperbola", "双曲线的定义、标准方程与渐近线", "讲解双曲线的定义、标准方程与渐近线的关系。"),
    ("independence-test", "独立性检验与列联表", "讲解独立性检验中列联表和统计推断的基本方法。"),
)


def now() -> str:
    return datetime.now(timezone.utc).isoformat()


def progress(event: str, **details: Any) -> None:
    """Emit one flushed, redacted milestone for every external interaction."""
    print(json.dumps(redact({"at": now(), "event": event, **details}), ensure_ascii=False), flush=True)


def redact(value: Any) -> Any:
    """Redact credential values while retaining non-secret operational counters such as token usage."""
    if isinstance(value, dict):
        return {
            str(key): "[REDACTED]" if str(key).replace("-", "_").lower() in CREDENTIAL_KEY_NAMES else redact(item)
            for key, item in value.items()
        }
    if isinstance(value, list):
        return [redact(item) for item in value]
    return value


def terminal_status(status: str | None) -> bool:
    return (status or "").upper() in TERMINAL


def topic_for(run_label: str, requested: str | None) -> tuple[str, str, str]:
    if requested:
        for item in TOPICS:
            if item[0] == requested:
                return item
        raise ValueError("--topic must be one of: " + ", ".join(item[0] for item in TOPICS))
    digest = int(hashlib.sha256(run_label.encode("utf-8")).hexdigest()[:8], 16)
    return TOPICS[digest % len(TOPICS)]


def utc_run_timestamp() -> str:
    """Return one UTC timestamp that names and correlates a single fresh acceptance run."""
    return datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")


def idempotency_key(topic: str, run_timestamp: str) -> str:
    """Bind an MCP task to its topic and UTC-second run identity without affecting visible handout content."""
    return f"mcp-acceptance:{topic}:{run_timestamp}:{secrets.token_hex(8)}"


def contains_nonfresh_signal(value: Any) -> bool:
    """Reject backend evidence that indicates a cached or memory-reused generation."""
    rendered = json.dumps(value, ensure_ascii=False).lower()
    return any(word in rendered for word in FRESH_FAILURE_WORDS)


def submit_once(mcp: "Mcp", arguments: dict[str, Any], record: dict[str, Any]) -> Any:
    """Submit only once; after uncertainty, durable correlation is the recovery path."""
    if record["taskCreationPosts"] != 0:
        raise RuntimeError("A second task submission was blocked.")
    record["taskCreationPosts"] = 1
    try:
        return mcp.call("start_multi_agent_writing", arguments)
    except Exception as error:
        record["submissionUncertain"] = True
        record["submissionError"] = str(redact(str(error)))
        raise RuntimeError("Task submission outcome is uncertain. Evidence was saved; runner did not issue a second POST.") from error


def parse_env(path: Path) -> dict[str, str]:
    """Read simple existing .env values without printing or exporting their contents."""
    result: dict[str, str] = {}
    if not path.is_file():
        return result
    for raw in path.read_text(encoding="utf-8-sig").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        key = key.strip()
        value = value.strip()
        if key and re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", key):
            if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
                value = value[1:-1]
            result[key] = value
    return result


def configured_credentials() -> tuple[str, str]:
    values = {**parse_env(ROOT / ".env"), **os.environ}
    username = values.get("MATH_AGENT_ACCEPTANCE_USERNAME") or values.get("MATH_AGENT_LOCAL_ACCEPTANCE_ACCOUNT_USERNAME")
    password = values.get("MATH_AGENT_ACCEPTANCE_PASSWORD") or values.get("MATH_AGENT_LOCAL_ACCEPTANCE_ACCOUNT_PASSWORD")
    if not username or not password:
        raise RuntimeError("Configured local acceptance credentials are unavailable (set them in existing .env or process environment).")
    return username, password


def command_output(command: list[str], timeout: int = 30) -> str:
    return subprocess.run(command, check=True, capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=timeout).stdout


def wsl(command: str) -> str:
    return command_output(["wsl.exe", "-d", os.getenv("MATH_AGENT_WSL_DISTRO", "Ubuntu"), "--", "bash", "-lc", command])


def wsl_repository_root() -> str:
    """Convert the workspace path without depending on Windows argument rewriting."""
    drive = ROOT.drive.rstrip(":").lower()
    if not drive:
        raise RuntimeError("Workspace root must be on a mounted Windows drive for WSL Compose inspection.")
    return "/mnt/" + drive + ROOT.as_posix()[2:]


def service_gate() -> dict[str, Any]:
    """Check the sole WSL compose owner; never invoke compose lifecycle commands."""
    unit = "math-agent-rag-compose.service"
    enabled = wsl(f"systemctl --user is-enabled {unit}").strip()
    active = wsl(f"systemctl --user is-active {unit}").strip()
    if enabled != "enabled" or active != "active":
        raise RuntimeError(f"WSL compose owner must be enabled and active(exited): {unit}")
    return {"unit": unit, "enabled": enabled, "active": active}


def docker_snapshot() -> dict[str, Any]:
    """Read health and identity only; this never runs a Compose lifecycle command."""
    raw = wsl(f"cd {shlex.quote(wsl_repository_root())} && docker compose ps --format json backend ai-worker")
    rows = [json.loads(line) for line in raw.splitlines() if line.strip()]
    result: dict[str, Any] = {}
    for row in rows:
        service = row.get("Service")
        if service not in {"backend", "ai-worker"}:
            continue
        identity = row.get("ID") or row.get("Id") or row.get("ContainerID")
        if not identity:
            continue
        inspect = json.loads(wsl("docker inspect --format '{{json .}}' " + shlex.quote(str(identity))))
        result[service] = {
            "id": str(identity),
            "state": str(row.get("State", "")).lower(),
            "health": str(row.get("Health") or inspect.get("State", {}).get("Health", {}).get("Status", "")).lower(),
            "restartCount": int(inspect.get("RestartCount", 0)),
        }
    return result


class Http:
    """Cookie-backed JSON client that records only non-secret request metadata."""
    def __init__(self, base: str, timeout: int, timeline: list[dict[str, Any]]) -> None:
        self.base, self.timeout, self.timeline = base.rstrip("/"), timeout, timeline
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(CookieJar()))

    def request(self, method: str, path: str, body: Any | None = None, headers: dict[str, str] | None = None) -> tuple[Any, dict[str, str]]:
        progress("http_request_started", method=method, path=path)
        data = None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8")
        request = urllib.request.Request(self.base + path, data=data, method=method, headers={"Accept": "application/json", **(headers or {})})
        if data is not None:
            request.add_header("Content-Type", "application/json; charset=utf-8")
        started = time.monotonic()
        try:
            with self.opener.open(request, timeout=self.timeout) as response:
                raw = response.read()
                self.timeline.append({"at": now(), "method": method, "path": path, "status": response.status, "durationMs": round((time.monotonic() - started) * 1000)})
                return (json.loads(raw.decode("utf-8-sig")) if raw else {}, dict(response.headers.items()))
        except urllib.error.HTTPError as error:
            self.timeline.append({"at": now(), "method": method, "path": path, "status": error.code, "durationMs": round((time.monotonic() - started) * 1000)})
            raise RuntimeError(f"HTTP {error.code} {path}") from error
        except urllib.error.URLError as error:
            self.timeline.append({"at": now(), "method": method, "path": path, "status": "network-error", "durationMs": round((time.monotonic() - started) * 1000)})
            raise RuntimeError(f"Network error for {path}: {type(error.reason).__name__}") from error


@dataclass
class Mcp:
    http: Http
    secret: str
    call_id: int = 0

    def call(self, name: str, arguments: dict[str, Any]) -> Any:
        self.call_id += 1
        payload = {"jsonrpc": "2.0", "id": self.call_id, "method": "tools/call", "params": {"name": name, "arguments": arguments}}
        response, _ = self.http.request("POST", "/api/mcp", payload, {"Authorization": "Bearer " + self.secret, "MCP-Protocol-Version": PROTOCOL, "Accept": "application/json, text/event-stream"})
        if "error" in response:
            raise RuntimeError("MCP error: " + str(redact(response["error"])))
        result = response.get("result", {})
        if result.get("isError"):
            raise RuntimeError("MCP tool error: " + str(redact(result)))
        structured = result.get("structuredContent")
        if structured is not None:
            return structured
        for content in result.get("content", []):
            if content.get("type") == "text":
                try:
                    return json.loads(content.get("text", ""))
                except json.JSONDecodeError:
                    return {"text": content.get("text", "")}
        return result


def stable_gate(http: Http, phase: str, evidence: list[dict[str, Any]], sample_seconds: int = 30) -> None:
    """Require stable IDs, healthy containers, zero restarts, and backend readiness."""
    first = docker_snapshot()
    time.sleep(sample_seconds)
    second = docker_snapshot()
    health, _ = http.request("GET", "/api/system/health")
    services = ("backend", "ai-worker")
    valid = all(
        snapshot.get(service, {}).get("state") == "running"
        and snapshot.get(service, {}).get("health") == "healthy"
        and snapshot.get(service, {}).get("restartCount") == 0
        for snapshot in (first, second) for service in services
    )
    valid = valid and all(first.get(service, {}).get("id") and first.get(service, {}).get("id") == second.get(service, {}).get("id") for service in services)
    valid = valid and str(health.get("status", "")).upper() == "UP"
    evidence.append({"at": now(), "phase": phase, "first": first, "second": second, "backendHealth": redact(health), "sampleSeconds": sample_seconds, "passed": valid})
    if not valid:
        raise RuntimeError("Compose stability gate failed; no task was submitted.")


def write_json(path: Path, value: Any) -> None:
    path.write_text(json.dumps(redact(value), ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def run_resource_curation_probe(args: argparse.Namespace, run_dir: Path) -> dict[str, Any]:
    """Runs only the live collector while keeping MySQL probe state separate from broker authorization state."""
    source_run_id = str(args.authorized_run_id)
    probe_run_id = f"resource-curation-probe-{utc_run_timestamp()}-{secrets.token_hex(4)}"
    program = f'''import base64
import hashlib
import hmac
import json
import os
import sys
import time
from app.ai_run_runtime import ProviderRoute
from app.handout_runtime import HandoutRunRequest, HandoutRuntime, _RunTelemetry

source_run_id = {source_run_id!r}
probe_run_id = {probe_run_id!r}
deadline_epoch_ms = int(time.time() * 1000) + {args.timeout} * 1000
runtime = HandoutRuntime()
source = runtime._checkpoint.load(source_run_id)
if source is None:
    raise RuntimeError("authorized source run is absent from MySQL checkpoint")
source_state = source[1]
source_request = source_state.get("request") if isinstance(source_state, dict) else None
if not isinstance(source_request, dict):
    raise RuntimeError("authorized source run lacks persisted request")
evidence_refs = source_request.get("evidenceRefs") or []
if not isinstance(evidence_refs, list) or not evidence_refs:
    raise RuntimeError("authorized source run has no persisted issued evidenceRefs")
secret = os.environ.get("MATH_AGENT_PROVIDER_ROUTE_GRANT_SECRET", "")
if not secret:
    raise RuntimeError("provider route grant secret is unavailable")
routes = [{{"name": "deepseek", "model": "deepseek-v4-flash"}}]
grant_payload = {{"runId": probe_run_id, "workload": "handout", "expiresAt": int(time.time()) + 300, "routes": routes}}
encoded = base64.urlsafe_b64encode(json.dumps(grant_payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")).rstrip(b"=").decode("ascii")
signature = base64.urlsafe_b64encode(hmac.new(secret.encode("utf-8"), encoded.encode("ascii"), hashlib.sha256).digest()).rstrip(b"=").decode("ascii")
provider_route = ProviderRoute.model_validate({{"primary": routes[0], "fallbacks": [], "routeGrant": encoded + "." + signature}})
request = HandoutRunRequest(runId=probe_run_id, taskId=probe_run_id, writingGoal="核验当前已授权教材的原文精读，不补充检索其他资料", questionText="读取当前已授权教材的原始解析正文，核验抛物线定义、焦点和准线；初始摘要不能替代原文阅读。", evidenceRefs=evidence_refs, providerRoute=provider_route, deadlineEpochMs=deadline_epoch_ms, operation="PLAN")
original_context = runtime._java_context
original_broker = runtime._java_broker_request
broker_trace = []
def source_context(payload, **kwargs):
    forwarded = dict(payload)
    forwarded["runId"] = source_run_id
    result = original_context(forwarded, **kwargs)
    # The probe must assess the deep-read chain, not let unrelated retained snippets satisfy its deliberately
    # narrow source-verification goal. These are unchanged, already-authorized context entries, only filtered.
    readable_items = [item for item in result.get("items", []) if isinstance(item, dict) and item.get("documentRef")]
    if readable_items:
        result = {{**result, "items": readable_items}}
    broker_trace.append({{"operation": "handout-context", "request": forwarded, "response": result}})
    return result
def source_broker(operation, payload, **kwargs):
    forwarded = dict(payload)
    forwarded["runId"] = source_run_id
    if operation == "handout-teacher-resource-search":
        # This probe validates the existing textbook authorization only.  Returning no expansion prevents a
        # model-selected search from writing newly found teacher evidence into the historical source task.
        result = {{"runId": source_run_id, "items": []}}
    else:
        result = original_broker(operation, forwarded, **kwargs)
    broker_trace.append({{"operation": operation, "request": forwarded, "response": result}})
    return result
runtime._java_context = source_context
runtime._java_broker_request = source_broker
telemetry = _RunTelemetry(probe_run_id)
with runtime._telemetry_lock:
    runtime._telemetry_by_run[probe_run_id] = telemetry
try:
    telemetry.sample_system()
    evidence = runtime._resource_curation({{"request": request}})["evidence"]
    telemetry.sample_system()
    private = runtime._checkpoint.load_private_state(probe_run_id)
    events = runtime.events(probe_run_id)
    result = {{"status": "PASSED", "sourceRunId": source_run_id, "probeRunId": probe_run_id, "sourceEvidenceRefCount": len(evidence_refs), "evidence": evidence.model_dump(by_alias=True), "brokerTrace": broker_trace, "privateDiagnostics": private, "publicEvents": events, "metrics": telemetry.finish().model_dump(by_alias=True)}}
except BaseException as exc:
    private = runtime._checkpoint.load_private_state(probe_run_id)
    events = runtime.events(probe_run_id)
    result = {{"status": "FAILED", "sourceRunId": source_run_id, "probeRunId": probe_run_id, "sourceEvidenceRefCount": len(evidence_refs), "errorType": type(exc).__name__, "error": str(exc), "brokerTrace": broker_trace, "privateDiagnostics": private, "publicEvents": events, "metrics": telemetry.finish().model_dump(by_alias=True)}}
finally:
    with runtime._telemetry_lock:
        runtime._telemetry_by_run.pop(probe_run_id, None)
print(json.dumps(result, ensure_ascii=False))
'''
    encoded = base64.b64encode(program.encode("utf-8")).decode("ascii")
    repository = shlex.quote(wsl_repository_root())
    command = (
        f"cd {repository} && docker compose exec -T "
        "-e MATH_AGENT_HANDOUT_PROVIDER_ORDER=deepseek "
        "-e MATH_AGENT_HANDOUT_DEEPSEEK_DISABLE_THINKING=true "
        "-e MATH_AGENT_HANDOUT_COLLECTION_DECISION_MAX_OUTPUT_TOKENS=12000 "
        "-e MATH_AGENT_HANDOUT_MAX_PROVIDER_CALLS=4 "
        "-e MATH_AGENT_HANDOUT_MAX_TOTAL_TOKENS=100000 "
        "ai-worker python -c " + shlex.quote("import base64;exec(base64.b64decode(" + repr(encoded) + "))")
    )
    started = time.monotonic()
    completed = subprocess.run(
        ["wsl.exe", "-d", os.getenv("MATH_AGENT_WSL_DISTRO", "Ubuntu"), "--", "bash", "-lc", command],
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        timeout=args.timeout + 15,
    )
    elapsed_seconds = round(time.monotonic() - started, 3)
    if completed.returncode != 0:
        raise RuntimeError("resource curation probe process failed: " + completed.stderr.strip())
    lines = [line for line in completed.stdout.splitlines() if line.strip()]
    if not lines:
        raise RuntimeError("resource curation probe returned no result")
    result = json.loads(lines[-1])
    result["elapsedSeconds"] = elapsed_seconds
    return result


def inspect_pdf(pdf: Path, destination: Path) -> dict[str, Any]:
    destination.mkdir(parents=True, exist_ok=True)
    def tool(name: str) -> str:
        configured = os.getenv(name, "")
        return configured if configured and Path(configured).is_file() else shutil.which(name.lower().replace("_bin", "")) or name.lower().replace("_bin", "")
    info = subprocess.run([tool("PDFINFO_BIN"), str(pdf)], check=True, capture_output=True, text=True, encoding="utf-8", errors="replace").stdout
    pages = int(re.search(r"^Pages:\s+(\d+)", info, re.M).group(1))
    subprocess.run([tool("PDFTOPPM_BIN"), "-png", "-r", "144", str(pdf), str(destination / "page")], check=True, capture_output=True)
    text = subprocess.run([tool("PDFTOTEXT_BIN"), "-layout", str(pdf), "-"], check=True, capture_output=True, text=True, encoding="utf-8", errors="replace").stdout
    (destination / "extracted.txt").write_text(text, encoding="utf-8")
    return {"sha256": hashlib.sha256(pdf.read_bytes()).hexdigest(), "pages": pages, "renderedPages": [p.name for p in sorted(destination.glob("page-*.png"))], "textChars": len(text), "metadata": info}


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--topic", choices=[item[0] for item in TOPICS])
    parser.add_argument("--run-label", default=None)
    parser.add_argument("--base-url", default=os.getenv("MATH_AGENT_ACCEPTANCE_BASE_URL", "http://127.0.0.1:8080"))
    parser.add_argument("--timeout", type=int, default=600,
                        help="Length of one active observation window in seconds; a healthy running task receives another window.")
    parser.add_argument("--http-timeout", type=int, default=30)
    parser.add_argument("--poll-interval-seconds", type=int, default=15)
    parser.add_argument("--stability-sample-seconds", type=int, default=30)
    parser.add_argument("--preflight-only", action="store_true", help="Validate owner, health, IDs, and readiness without login or task submission.")
    parser.add_argument("--resource-curation-only", action="store_true", help="Run only the bounded pre-plan resource collector against a persisted authorized run; never creates a task or enters writer nodes.")
    parser.add_argument("--authorized-run-id", help="Existing Java-issued run ID used only by --resource-curation-only.")
    args = parser.parse_args(argv)
    if args.preflight_only and args.resource_curation_only:
        parser.error("--preflight-only and --resource-curation-only cannot be combined")
    if args.resource_curation_only and not args.authorized_run_id:
        parser.error("--resource-curation-only requires --authorized-run-id")
    if args.timeout <= 0 or args.http_timeout <= 0 or args.poll_interval_seconds <= 0 or args.stability_sample_seconds < 0:
        parser.error("timeouts must be positive; stability sample seconds may be zero only for tests")
    return args


def main(args: argparse.Namespace | None = None) -> int:
    args = args or parse_args()
    run_timestamp = utc_run_timestamp()
    topic, goal, question = topic_for(args.run_label or run_timestamp, args.topic)
    args.run_label = args.run_label or f"handout-mcp-{topic}-{run_timestamp}"
    run_dir = ROOT / "output" / "acceptance" / "handout-mcp" / args.run_label
    run_dir.mkdir(parents=True, exist_ok=False)
    record: dict[str, Any] = {"runLabel": args.run_label, "runTimestamp": run_timestamp, "startedAt": now(), "serviceStates": [], "taskSnapshots": [], "timeline": [], "taskCreationPosts": 0, "reviewRecoveryExercised": False}
    key_id = ""
    http = Http(args.base_url, args.http_timeout, record["timeline"])
    try:
        progress("acceptance_started", runLabel=args.run_label, topic=topic, runDirectory=str(run_dir))
        progress("service_owner_gate_started")
        record["serviceOwner"] = service_gate()
        progress("service_owner_gate_completed", serviceOwner=record["serviceOwner"])
        progress("stability_gate_started", phase="preflight")
        stable_gate(http, "preflight", record["serviceStates"], args.stability_sample_seconds)
        progress("stability_gate_completed", phase="preflight")
        if args.preflight_only:
            record.update({"result": "preflight-passed", "completedAt": now()})
            return 0
        if args.resource_curation_only:
            progress("resource_curation_probe_started", authorizedRunId=args.authorized_run_id)
            probe = run_resource_curation_probe(args, run_dir)
            record["resourceCurationProbe"] = probe
            if probe.get("status") != "PASSED":
                raise RuntimeError("resource curation probe failed: " + str(probe.get("error")))
            evidence = probe.get("evidence", {})
            items = evidence.get("items", []) if isinstance(evidence, dict) else []
            inspected = evidence.get("inspectedItems", []) if isinstance(evidence, dict) else []
            trace = probe.get("brokerTrace", [])
            private = probe.get("privateDiagnostics", {})
            diagnostic = private.get("privateDiagnostics", {}).get("resourceCollection", {}) if isinstance(private, dict) else {}
            decisions = diagnostic.get("iterations", {}) if isinstance(diagnostic, dict) else {}
            public_events = json.dumps(probe.get("publicEvents", []), ensure_ascii=False)
            leaks = [marker for marker in ("decisionPrompt", "effectiveDecision", "documentRef", "evidenceRef", "original parsed", "http://", "https://", "file://") if marker in public_events]
            iterations = decisions if isinstance(decisions, dict) else {}
            iteration_values = [value for value in iterations.values() if isinstance(value, dict)]
            model_turns = private.get("modelTurnDiagnostics", {}) if isinstance(private, dict) else {}
            model_values = [value for value in model_turns.values() if isinstance(value, dict)] if isinstance(model_turns, dict) else []
            provider_models = {(str(value.get("provider", "")), str(value.get("model", ""))) for value in model_values}
            second_prompts = [str(value.get("decisionPrompt", "")) for value in iteration_values
                              if str(value.get("decisionPrompt", ""))]
            read_calls = [entry for entry in trace if entry.get("operation") in {"handout-document-read", "handout-document-search"}]
            original_blocks = [
                str(block.get("text", ""))
                for entry in read_calls for block in entry.get("response", {}).get("blocks", [])
                if isinstance(block, dict) and str(block.get("text", "")).strip()
            ]
            original_text = "\n".join(original_blocks)
            second_decision_saw_original = any(
                len(block.strip()) > 40 and any(block in prompt for prompt in second_prompts)
                for block in original_blocks
            )
            gates = {
                "nonzeroEvidence": bool(items),
                "nonzeroOriginalMarkdown": bool(original_text.strip()),
                "deepReadAction": bool(read_calls),
                "secondDecisionSawOriginalText": second_decision_saw_original,
                "deepseekOnly": provider_models == {("deepseek", "deepseek-v4-flash")},
                "publicEventsPrivate": not leaks,
                "noWriterNodes": not any(marker in public_events for marker in ("plan_ready", "teacher_blueprint_ready", "teacher_writer", "student_writer", "lecture_writer")),
            }
            record["resourceCurationGates"] = gates
            record["resourceCurationPrivacyLeaks"] = leaks
            if not all(gates.values()):
                raise RuntimeError("resource curation hard gate failed: " + json.dumps(gates, ensure_ascii=False))
            record.update({"result": "resource-curation-passed", "completedAt": now()})
            return 0
        username, password = configured_credentials()
        progress("login_started")
        http.request("POST", "/api/auth/login", {"username": username, "password": password})
        progress("login_completed")
        progress("mcp_key_created_started")
        key, _ = http.request("POST", "/api/mcp/keys", {})
        key_id, secret = str(key["keyId"]), str(key["secretKey"])
        progress("mcp_key_created", keyId=key_id)
        mcp = Mcp(http, secret)
        progress("evidence_retrieval_started", topic=topic)
        search = mcp.call("search_multi_source_evidence", {"query": goal, "libraries": ["public_textbook", "feishu"], "limit": 6, "permissionScopes": ["PUBLIC_TEXTBOOK", "TEACHER_SHARED"]})
        evidence_refs = [hit.get("evidenceRef") for hit in search.get("hits", search.get("mergedHits", [])) if hit.get("evidenceRef")]
        if not evidence_refs:
            raise RuntimeError("No authorized source-grounded evidence returned; task was not submitted.")
        progress("evidence_retrieval_completed", hitCount=len(evidence_refs))
        record["retrieval"] = {"topic": topic, "sourceStatuses": search.get("libraryResults", search.get("libraries", [])), "hitCount": len(evidence_refs), "evidenceRefs": evidence_refs}
        progress("stability_gate_started", phase="before-submit")
        stable_gate(http, "before-submit", record["serviceStates"], args.stability_sample_seconds)
        progress("stability_gate_completed", phase="before-submit")
        correlation = {"runLabel": args.run_label, "topic": topic, "runTimestamp": run_timestamp, "clientRequestId": idempotency_key(topic, run_timestamp), "submittedAt": now()}
        write_json(run_dir / "submission-correlation.json", correlation)
        progress("handout_submission_started", clientRequestId=correlation["clientRequestId"])
        started = submit_once(mcp, {"writingGoal": "教师版、学生版和16:10课堂讲解版讲义", "questionText": question, "evidenceRefs": evidence_refs, "clientRequestId": correlation["clientRequestId"]}, record)
        workflow_id = str(started.get("workflowId", ""))
        if not workflow_id:
            raise RuntimeError("MCP start response lacks workflowId; no retry was issued.")
        correlation["workflowId"] = workflow_id
        write_json(run_dir / "submission-correlation.json", correlation)
        progress("handout_submission_completed", workflowId=workflow_id, status=started.get("status"))
        deadline = time.monotonic() + args.timeout
        task = started
        if contains_nonfresh_signal(task):
            raise RuntimeError("Task reports cache/memory reuse; fresh-generation acceptance is rejected.")
        while not terminal_status(str(task.get("status"))):
            progress("stability_gate_started", phase="before-poll", workflowId=workflow_id)
            stable_gate(http, "before-poll", record["serviceStates"], args.stability_sample_seconds)
            progress("stability_gate_completed", phase="before-poll", workflowId=workflow_id)
            progress("handout_status_poll_started", workflowId=workflow_id)
            task = mcp.call("get_multi_agent_writing_status", {"workflowId": workflow_id})
            progress("handout_status_poll_completed", workflowId=workflow_id, status=task.get("status"), stages=task.get("stages"))
            record["taskSnapshots"].append({"at": now(), "status": task.get("status"), "stages": task.get("stages"), "usage": task.get("totalUsage"), "message": task.get("message")})
            if contains_nonfresh_signal(task):
                raise RuntimeError("Task reports cache/memory reuse; fresh-generation acceptance is rejected.")
            if time.monotonic() >= deadline:
                raise RuntimeError("Polling timeout; use the persisted workflowId with read-only MCP status, not a new submit.")
            time.sleep(args.poll_interval_seconds)
        record["terminal"] = task
        if str(task.get("status", "")).upper() == "WAITING_REVIEW":
            record["result"] = "review-required"
            raise RuntimeError("Task requires review. The runner does not approve it automatically.")
        if str(task.get("status", "")).upper() != "COMPLETED":
            raise RuntimeError("Task did not complete: " + str(task.get("status")))
        # Refresh recovery is read-only and remains scoped to this exact persisted task ID.
        recovered, _ = http.request("GET", "/api/teaching/tasks/" + urllib.parse.quote(workflow_id, safe=""))
        record["recovery"] = {"workflowId": workflow_id, "sameTask": str(recovered.get("taskId", recovered.get("workflowId", workflow_id))) == workflow_id, "status": recovered.get("status")}
        record["reviewRecoveryExercised"] = True
        artifacts = {}
        for variant, fmt in (("teacher", "pdf-teacher"), ("student", "pdf-student"), ("lecture", "pdf-lecture")):
            progress("pdf_export_started", workflowId=workflow_id, variant=variant, format=fmt)
            stable_gate(http, "before-export-" + variant, record["serviceStates"], args.stability_sample_seconds)
            exported = mcp.call("export_multi_agent_writing_artifact", {"workflowId": workflow_id, "format": fmt})
            data = base64.b64decode(exported["base64Content"], validate=True)
            pdf = run_dir / variant / f"{variant}.pdf"
            pdf.parent.mkdir(parents=True, exist_ok=True)
            pdf.write_bytes(data)
            artifacts[variant] = {"export": {k: v for k, v in exported.items() if k != "base64Content"}, "audit": inspect_pdf(pdf, pdf.parent)}
            progress("pdf_export_completed", workflowId=workflow_id, variant=variant, pdf=str(pdf), audit=artifacts[variant]["audit"])
        student_text = (run_dir / "student" / "extracted.txt").read_text(encoding="utf-8")
        forbidden = [r"答案", r"教师批注", r"trace", r"model[_ -]?call", r"sourcePath", r"assetId", r"https?://", r"[A-Za-z]:\\"]
        isolation_hits = [pattern for pattern in forbidden if re.search(pattern, student_text, re.I)]
        record["studentIsolation"] = {"passed": not isolation_hits, "forbiddenHits": isolation_hits, "safeTitleOnly": True}
        if isolation_hits:
            raise RuntimeError("Student isolation gate failed: " + ", ".join(isolation_hits))
        record.update({"artifacts": artifacts, "result": "completed", "completedAt": now()})
        return 0
    finally:
        if key_id:
            try:
                http.request("POST", f"/api/mcp/keys/{key_id}/revoke", {})
            except Exception as error:
                record["keyRevocationError"] = type(error).__name__
        record["completedAt"] = record.get("completedAt", now())
        write_json(run_dir / "acceptance.json", record)
        print(json.dumps({"result": record.get("result", "failed"), "evidence": str(run_dir), "taskCreationPosts": record["taskCreationPosts"]}, ensure_ascii=False))


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (RuntimeError, ValueError, subprocess.SubprocessError, OSError) as error:
        print("acceptance failed: " + str(redact(str(error))), file=sys.stderr)
        raise SystemExit(1)
