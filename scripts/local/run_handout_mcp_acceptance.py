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
    ("coloring-combinatorics", "排列组合涂色问题的分类计数与容斥去重", "讲解排列组合中的涂色问题：一个地区分为5个行政区域，相邻区域不得同色，现有四种颜色可供选择，求不同着色方法；重点说明分类计数、最小涂色组合与容斥去重。"),
    ("solid-geometry", "立体几何空间向量与二面角", "讲解立体几何中的空间向量方法：如图，四棱锥 P-ABCD 中 PA⊥底面 ABCD，证明线面平行并求二面角 A-CP-D 相关线段长度，重点说明线面垂直、线面平行与二面角的向量求法。"),
)

TOPIC_REQUIRED_SOURCE_IMAGE_TARGETS = {
    "coloring-combinatorics": "IMAJES/image-001.jpg",
    "solid-geometry": "figures/q-017-01.png",
}

_PROGRESS_JOURNAL: Path | None = None


def configure_progress_journal(path: Path | None) -> None:
    global _PROGRESS_JOURNAL
    _PROGRESS_JOURNAL = path


def _append_jsonl(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("a", encoding="utf-8", newline="\n") as handle:
        handle.write(json.dumps(redact(value), ensure_ascii=False) + "\n")
        handle.flush()
        os.fsync(handle.fileno())


def now() -> str:
    return datetime.now(timezone.utc).isoformat()


def progress(event: str, **details: Any) -> None:
    """Emit one flushed, redacted milestone for every external interaction."""
    payload = redact({"at": now(), "event": event, **details})
    print(json.dumps(payload, ensure_ascii=False), flush=True)
    if _PROGRESS_JOURNAL is not None:
        _append_jsonl(_PROGRESS_JOURNAL, payload)


def redact(value: Any) -> Any:
    """Redact credentials and binary transport payloads while retaining AI interaction records."""
    if isinstance(value, dict):
        result: dict[str, Any] = {}
        for key, item in value.items():
            normalized_key = str(key).replace("-", "_").lower()
            if normalized_key in {"base64", "base64content", "base64_content", "dataurl", "image_data_url", "imagedataurl"}:
                continue
            result[str(key)] = "[REDACTED]" if normalized_key in CREDENTIAL_KEY_NAMES else redact(item)
        return result
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


def required_source_image_target(topic: str) -> str:
    """Returns the one authoritative original image target required by a topic-specific acceptance run."""
    return TOPIC_REQUIRED_SOURCE_IMAGE_TARGETS.get(topic, "")


def assert_required_source_image_retained(topic: str, workflow: dict[str, Any]) -> None:
    """Reject a completed run that dropped the exact Java-issued source image target."""
    target = required_source_image_target(topic)
    if not target:
        return
    retained_rows = re.findall(r"!\[source-image:[^]]+]\(([^)]+)\)", json.dumps(workflow, ensure_ascii=False))
    if target not in retained_rows:
        raise RuntimeError(f"{topic} workflow omitted required authorized source image target: {target}")


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
    root = ROOT.resolve().as_posix()
    if root.startswith("/mnt/"):
        return root
    drive = ROOT.drive.rstrip(":").lower()
    if not drive:
        raise RuntimeError("Workspace root must be on a mounted Windows drive for WSL Compose inspection.")
    return "/mnt/" + drive + root[2:]


def wsl_service_status(scope: str, unit: str) -> tuple[str, str]:
    """Read enabled and active state in one WSL session so startup does not restart the Compose wait gate."""
    prefix = "systemctl --user" if scope == "user" else "systemctl"
    completed = subprocess.run(
        [
            "wsl.exe", "-d", os.getenv("MATH_AGENT_WSL_DISTRO", "Ubuntu"), "--", "bash", "-lc",
            f"{prefix} is-enabled {unit}; {prefix} is-active {unit}; exit 0",
        ],
        check=False,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        timeout=30,
    )
    states = [line.strip() for line in completed.stdout.splitlines() if line.strip()]
    return (states + ["unavailable", "unavailable"])[:2]


def service_gate() -> dict[str, Any]:
    """Require one enabled Compose owner; stable_gate verifies real container health and identity afterward."""
    unit = "math-agent-rag-compose.service"
    checks = []
    for scope in ("user", "system"):
        enabled, active = wsl_service_status(scope, unit)
        checks.append({"scope": scope, "enabled": enabled, "active": active})
    owners = [item for item in checks if item["enabled"] == "enabled"]
    if len(owners) != 1:
        raise RuntimeError("exactly one enabled WSL Compose owner is required: " + json.dumps(checks, ensure_ascii=False))
    return {"unit": unit, "owner": owners[0]["scope"], "checks": checks}


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
    def __init__(self, base: str, timeout: int, timeline: list[dict[str, Any]], journal: Path | None = None) -> None:
        self.base, self.timeout, self.timeline, self.journal = base.rstrip("/"), timeout, timeline, journal
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(CookieJar()))

    def _persist(self, event: dict[str, Any]) -> None:
        if not self.journal:
            return
        with self.journal.open("a", encoding="utf-8", newline="\n") as handle:
            handle.write(json.dumps(redact(event), ensure_ascii=False) + "\n")
            handle.flush()
            os.fsync(handle.fileno())

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
                event = {"at": now(), "method": method, "path": path, "status": response.status, "durationMs": round((time.monotonic() - started) * 1000), "response": json.loads(raw.decode("utf-8-sig")) if raw else {}}
                self.timeline.append({k: v for k, v in event.items() if k != "response"})
                self._persist({"kind": "http_response", **event})
                return (event["response"], dict(response.headers.items()))
        except urllib.error.HTTPError as error:
            event = {"at": now(), "method": method, "path": path, "status": error.code, "durationMs": round((time.monotonic() - started) * 1000)}
            self.timeline.append(event)
            self._persist({"kind": "http_error", **event})
            raise RuntimeError(f"HTTP {error.code} {path}") from error
        except urllib.error.URLError as error:
            event = {"at": now(), "method": method, "path": path, "status": "network-error", "durationMs": round((time.monotonic() - started) * 1000)}
            self.timeline.append(event)
            self._persist({"kind": "http_error", **event})
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


def stream_probe_process(command: list[str], output_path: Path, timeout: int) -> list[str]:
    """Stream the worker probe output to a durable log while retaining only its JSON result line."""
    output_path.parent.mkdir(parents=True, exist_ok=True)
    lines: list[str] = []
    started = time.monotonic()
    with output_path.open("a", encoding="utf-8", newline="\n") as log:
        process = subprocess.Popen(
            command,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            encoding="utf-8",
            errors="replace",
            bufsize=1,
        )
        assert process.stdout is not None
        try:
            for line in process.stdout:
                log.write(line)
                log.flush()
                os.fsync(log.fileno())
                progress("resource_curation_probe_output", line=line.rstrip("\n"))
                if line.strip():
                    lines.append(line.strip())
            return_code = process.wait(timeout=max(1, timeout - int(time.monotonic() - started)))
        except BaseException:
            process.kill()
            process.wait()
            raise
    if return_code != 0:
        raise RuntimeError(f"resource curation probe process failed with exit {return_code}; see {output_path}")
    return lines


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
evidence_snapshot = source_state.get("evidence") if isinstance(source_state, dict) else None
evidence_refs = [
    str(item.get("ref"))
    for item in (evidence_snapshot.get("items", []) if isinstance(evidence_snapshot, dict) else [])
    if isinstance(item, dict) and str(item.get("ref", "")).startswith("ev_")
]
if not evidence_refs:
    evidence_refs = [
        str(reference)
        for reference in (source_request.get("evidenceRefs") or [])
        if str(reference).startswith("ev_")
    ]
if not evidence_refs:
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
    authorized_items = []
    rejected_refs = []
    for reference in payload.get("evidenceRefs", []):
        single = {{**forwarded, "evidenceRefs": [reference]}}
        try:
            result = original_context(single, **kwargs)
            items = [item for item in result.get("items", []) if isinstance(item, dict)]
            authorized_items.extend(items)
            broker_trace.append({{"operation": "handout-context", "request": single, "response": result}})
        except Exception as exc:
            rejected_refs.append(reference)
            broker_trace.append({{"operation": "handout-context", "request": single,
                                 "errorType": type(exc).__name__, "error": str(exc)}})
    # The probe must assess the deep-read chain, not let unrelated retained snippets satisfy its deliberately
    # narrow source-verification goal. These are unchanged, already-authorized context entries, only filtered.
    readable_items = [item for item in authorized_items if item.get("documentRef")]
    return {{"query": "", "items": readable_items, "source": "java-broker",
            "rejectedEvidenceRefs": rejected_refs}}
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
        "ai-worker python -u -c " + shlex.quote("import base64;exec(base64.b64decode(" + repr(encoded) + "))")
    )
    started = time.monotonic()
    probe_log = run_dir / "resource-curation-probe.log"
    completed_lines = stream_probe_process(
        ["wsl.exe", "-d", os.getenv("MATH_AGENT_WSL_DISTRO", "Ubuntu"), "--", "bash", "-lc", command],
        probe_log,
        args.timeout + 15,
    )
    elapsed_seconds = round(time.monotonic() - started, 3)
    lines = [line for line in completed_lines if line.strip()]
    if not lines:
        raise RuntimeError("resource curation probe returned no result")
    result = json.loads(lines[-1])
    result["elapsedSeconds"] = elapsed_seconds
    result["probeLog"] = str(probe_log)
    return result


def inspect_pdf(pdf: Path, destination: Path) -> dict[str, Any]:
    destination.mkdir(parents=True, exist_ok=True)

    def windows_path(path: Path) -> str:
        value = str(path.resolve()).replace("\\\\", "/")
        match = re.match(r"^/mnt/([A-Za-z])/(.*)$", value)
        if match:
            return match.group(1).upper() + ":/" + match.group(2)
        return value

    def command(name: str, *arguments: str) -> tuple[list[str], bool]:
        configured_name = {
            "pdfinfo": "PDFINFO_BIN",
            "pdftoppm": "PDFTOPPM_BIN",
            "pdftotext": "PDFTOTEXT_BIN",
            "pdfimages": "PDFIMAGES_BIN",
        }[name]
        configured = os.getenv(configured_name, "")
        native = configured if configured and Path(configured).is_file() else shutil.which(name)
        if native:
            return [native, *arguments], native.lower().endswith(".exe")
        if os.name == "posix":
            candidates = []
            for user_root in Path("/mnt/c/Users").glob("*"):
                candidates.append(user_root / "AppData/Local/Programs/MiKTeX/miktex/bin/x64" / f"{name}.exe")
            candidates.append(Path(f"/mnt/c/Program Files/Git/mingw64/bin/{name}.exe"))
            for candidate in candidates:
                if candidate.is_file():
                    return [str(candidate), *arguments], True
        if os.name == "nt":
            return ["wsl.exe", "-d", os.getenv("MATH_AGENT_WSL_DISTRO", "Ubuntu"), "--", "/usr/bin/" + name, *arguments], False
        return [name, *arguments], False

    pdf_command, pdf_is_windows = command("pdfinfo")
    pdf_argument = windows_path(pdf) if pdf_is_windows else str(pdf)
    pdf_command = [*pdf_command[:1], pdf_argument, *pdf_command[1:]]
    info = subprocess.run(
        pdf_command,
        check=True,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    ).stdout
    pages = int(re.search(r"^Pages:\s+(\d+)", info, re.M).group(1))
    ppm_command, ppm_is_windows = command("pdftoppm")
    ppm_pdf_argument = windows_path(pdf) if ppm_is_windows else str(pdf)
    ppm_output_prefix = windows_path(destination / "page") if ppm_is_windows else str(destination / "page")
    subprocess.run(
        [*ppm_command, "-png", "-r", "144", ppm_pdf_argument, ppm_output_prefix],
        check=True,
        capture_output=True,
    )
    text_command, text_is_windows = command("pdftotext")
    text_pdf_argument = windows_path(pdf) if text_is_windows else str(pdf)
    text = subprocess.run(
        [*text_command, "-layout", text_pdf_argument, "-"],
        check=True,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    ).stdout
    (destination / "extracted.txt").write_text(text, encoding="utf-8")
    image_command, image_is_windows = command("pdfimages")
    image_pdf_argument = windows_path(pdf) if image_is_windows else str(pdf)
    listing = subprocess.run(
        [*image_command, "-list", image_pdf_argument],
        check=True,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    ).stdout
    image_count = max(0, len([line for line in listing.splitlines() if re.match(r"^\s*\d+\s+\d+\s+", line)]))
    (destination / "images.txt").write_text(listing, encoding="utf-8")
    return {
        "sha256": hashlib.sha256(pdf.read_bytes()).hexdigest(),
        "pages": pages,
        "renderedPages": [p.name for p in sorted(destination.glob("page-*.png"))],
        "textChars": len(text),
        "imageCount": image_count,
        "metadata": info,
    }


def persist_retrieval_evidence(payload: Any, run_dir: Path) -> dict[str, int]:
    """Persist source-bearing fields found in recovery status/trace without reading files or resolving IDs locally."""
    buckets = {"textbook": [], "feishu": [], "gaokao": []}

    def visit(value: Any) -> None:
        if isinstance(value, dict):
            for key, bucket in (("textbookHits", "textbook"), ("teacherResourceHits", "feishu"), ("gaokaoHits", "gaokao")):
                rows = value.get(key)
                if isinstance(rows, list):
                    buckets[bucket].extend(row for row in rows if isinstance(row, dict))
            for child in value.values():
                visit(child)
        elif isinstance(value, list):
            for child in value:
                visit(child)

    def row_key(item: dict[str, Any]) -> str:
        for key in ("evidenceRef", "ref", "transparentReference", "transparentRef"):
            value = item.get(key)
            if value is not None and str(value).strip():
                return str(value).strip()
        return json.dumps(item, ensure_ascii=False, sort_keys=True, separators=(",", ":"))

    def unique_rows(rows: list[dict[str, Any]]) -> list[dict[str, Any]]:
        seen: set[str] = set()
        result: list[dict[str, Any]] = []
        for item in rows:
            key = row_key(item)
            if key in seen:
                continue
            seen.add(key)
            result.append(item)
        return result

    visit(payload)
    buckets = {name: unique_rows(rows) for name, rows in buckets.items()}
    counts = {name: len(rows) for name, rows in buckets.items()}
    existing_source = run_dir / "retrieval-source-original.json"
    if existing_source.is_file():
        try:
            persisted = json.loads(existing_source.read_text(encoding="utf-8"))
            persisted_buckets = {"textbook": [], "feishu": [], "gaokao": []}

            def visit_persisted(value: Any) -> None:
                if isinstance(value, dict):
                    for key, bucket in (("textbookHits", "textbook"), ("teacherResourceHits", "feishu"), ("gaokaoHits", "gaokao")):
                        rows = value.get(key)
                        if isinstance(rows, list):
                            persisted_buckets[bucket].extend(row for row in rows if isinstance(row, dict))
                    for child in value.values():
                        visit_persisted(child)
                elif isinstance(value, list):
                    for child in value:
                        visit_persisted(child)

            visit_persisted(persisted)
            persisted_buckets = {name: unique_rows(rows) for name, rows in persisted_buckets.items()}
            persisted_counts = {name: len(rows) for name, rows in persisted_buckets.items()}
            if any(persisted_counts.values()):
                # The copied fresh source response is authoritative; status payloads repeat nested stage evidence.
                buckets, counts = persisted_buckets, persisted_counts
        except (OSError, ValueError, TypeError):
            pass
    original_blocks = []
    for source, rows in buckets.items():
        for item in rows:
            text = item.get("evidenceText") or item.get("snippet") or item.get("text") or item.get("excerpt") or ""
            if not str(text).strip():
                continue
            title = item.get("title") or item.get("documentTitle") or item.get("bookName") or source
            reference = item.get("transparentReference") or item.get("sourceReference") or item.get("evidenceRef") or ""
            original_blocks.append(f"## [{source}] {title}\n\nreference: {reference}\n\n{text}\n")
    if original_blocks:
        (run_dir / "resource-original.md").write_text("\n".join(original_blocks), encoding="utf-8")
    snapshot = {"sourceCounts": counts, "payload": payload}
    if not existing_source.is_file() or any(counts.values()):
        write_json(run_dir / "retrieval-original.json", snapshot)
    else:
        # A status response can contain only the persisted workflow snapshot. Never replace fresh retrieval evidence with that empty view.
        write_json(run_dir / "retrieval-status-snapshot.json", snapshot)
    return counts


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
    parser.add_argument("--workflow-id", help="Resume an existing workflow without submitting a new task.")
    parser.add_argument("--source-evidence", help="Fresh retrieval JSON copied into a recovery run before resume.")
    parser.add_argument("--resume-failed", action="store_true", help="Resume an existing failed workflow once.")
    args = parser.parse_args(argv)
    if args.preflight_only and args.resource_curation_only:
        parser.error("--preflight-only and --resource-curation-only cannot be combined")
    if args.resource_curation_only and not args.authorized_run_id:
        parser.error("--resource-curation-only requires --authorized-run-id")
    if args.timeout <= 0 or args.http_timeout <= 0 or args.poll_interval_seconds <= 0 or args.stability_sample_seconds < 0:
        parser.error("timeouts must be positive; stability sample seconds may be zero only for tests")
    return args


def resume_existing_workflow(mcp: "Mcp", workflow_id: str, args: argparse.Namespace, run_dir: Path, record: dict[str, Any], topic: str) -> int:
    """Resume one persisted workflow without creating another task, then export verified PDFs."""
    task = mcp.call("get_multi_agent_writing_status", {"workflowId": workflow_id})
    write_json(run_dir / "latest-status.json", task)
    persist_retrieval_evidence(task, run_dir)
    status = str(task.get("status", "")).upper()
    should_refresh_evidence = bool(args.source_evidence) and status == "COMPLETED"
    if status == "FAILED" or should_refresh_evidence:
        if status == "FAILED" and not args.resume_failed:
            raise RuntimeError("Existing workflow is FAILED; rerun with --resume-failed to resume it once.")
        recovery_arguments = {
            "workflowId": workflow_id,
            "writingGoal": "教师版、学生版和16:10课堂讲解版讲义",
            "questionText": TOPICS[[item[0] for item in TOPICS].index(topic)][2],
        }
        retrieval = run_dir / "retrieval-source-original.json"
        if not retrieval.is_file():
            retrieval = run_dir / "retrieval-original.json"
        if retrieval.is_file():
            saved_retrieval = json.loads(retrieval.read_text(encoding="utf-8"))
            saved_refs = saved_retrieval.get("evidenceRefs", [])
            if isinstance(saved_refs, list):
                recovery_arguments["evidenceRefs"] = [str(ref) for ref in saved_refs if str(ref).strip()]
        saved_search = json.loads(retrieval.read_text(encoding="utf-8")) if retrieval.is_file() else {}
        library_results = saved_search.get("libraryResults", {}) if isinstance(saved_search.get("libraryResults", {}), dict) else {}
        def saved_hits(name: str, key: str) -> list[dict[str, Any]]:
            direct = saved_search.get(key, [])
            if isinstance(direct, list) and direct:
                return direct
            nested = library_results.get(name, {}) if isinstance(library_results.get(name, {}), dict) else {}
            values = nested.get(key, [])
            return values if isinstance(values, list) else []
        recovery_evidence = []
        for source_name, source_key, library_name in (("textbook", "textbookHits", "public_textbook"), ("feishu", "teacherResourceHits", "feishu"), ("gaokao", "gaokaoHits", "gaokao")):
            for item in saved_hits(library_name, source_key):
                if not isinstance(item, dict):
                    continue
                recovery_evidence.append({
                    "sourceScope": {"textbook": "PUBLIC_TEXTBOOK", "feishu": "TEACHER_RESOURCE", "gaokao": "CANONICAL_MATH_PAPER"}[source_name],
                    "sourceTitle": item.get("title") or item.get("documentTitle") or item.get("bookName") or source_name,
                    "chunkId": item.get("chunkId") or item.get("blockId") or "",
                    "pageNo": item.get("pageNo") or item.get("page") or 0,
                    "snippet": item.get("evidenceText") or item.get("snippet") or item.get("text") or "",
                    "sourceDocumentId": item.get("fileDocumentId") or item.get("sourceDocumentId") or item.get("documentId") or item.get("docId") or "",
                    "fileDocumentId": item.get("fileDocumentId") or "",
                    "sourceType": item.get("sourceType") or ("feishu" if source_name == "feishu" else source_name),
                    "assetIds": [
                        str(asset) for asset in (
                            item.get("imageAssetIds")
                            or item.get("assetIds")
                            or item.get("pageAssetIds")
                            or [asset.get("assetId") for asset in (item.get("questionAssets") or item.get("assets") or []) if isinstance(asset, dict)]
                            or []
                        ) if str(asset).strip()
                    ],
                    "canonicalQuestionNumber": item.get("questionNumber") or "",
                })
        recovery_arguments["initialEvidence"] = recovery_evidence[:24]
        task = mcp.call("resume_multi_agent_writing", recovery_arguments)
        record["recovery"] = {
            "workflowId": workflow_id,
            "resumed": True,
            "evidenceRefreshed": should_refresh_evidence,
            "taskCreationPosts": 0,
            "status": task.get("status"),
        }
        write_json(run_dir / "resume-response.json", task)
        write_json(run_dir / "latest-status.json", task)
        persist_retrieval_evidence(task, run_dir)
        if str(task.get("status", "")).upper() == "COMPLETED":
            raise RuntimeError(
                "Resume returned COMPLETED without requeueing the existing workflow; refusing to export the old package."
            )
    deadline = time.monotonic() + args.timeout
    while not terminal_status(str(task.get("status"))):
        task = mcp.call("get_multi_agent_writing_status", {"workflowId": workflow_id})
        record["taskSnapshots"].append({"at": now(), "status": task.get("status"), "stages": task.get("stages"), "usage": task.get("totalUsage"), "message": task.get("message")})
        write_json(run_dir / "latest-status.json", task)
        source_counts = persist_retrieval_evidence(task, run_dir)
        record["retrievalSourceCounts"] = source_counts
        record["retrievalEvidencePersisted"] = True
        write_json(run_dir / "acceptance-live.json", record)
        stage_summary = [
            {
                "stageCode": stage.get("stageCode", ""),
                "status": stage.get("status", ""),
                "elapsedMs": stage.get("elapsedMs", 0),
            }
            for stage in task.get("stages", [])
            if isinstance(stage, dict)
        ]
        progress("handout_status_poll_completed", workflowId=workflow_id, status=task.get("status"), stages=stage_summary)
        if time.monotonic() >= deadline:
            raise RuntimeError("Polling timeout; rerun with the same workflowId.")
        time.sleep(args.poll_interval_seconds)
    if str(task.get("status", "")).upper() != "COMPLETED":
        raise RuntimeError("Task did not complete: " + str(task.get("status")))
    assert_required_source_image_retained(topic, task)
    artifacts = {}
    for variant, fmt in (("teacher", "pdf-teacher"), ("student", "pdf-student"), ("lecture", "pdf-lecture")):
        exported = mcp.call("export_multi_agent_writing_artifact", {"workflowId": workflow_id, "format": fmt})
        data = base64.b64decode(exported["base64Content"], validate=True)
        pdf = run_dir / variant / f"{variant}.pdf"
        pdf.parent.mkdir(parents=True, exist_ok=True)
        pdf.write_bytes(data)
        audit = inspect_pdf(pdf, pdf.parent)
        if not audit["renderedPages"] or audit["pages"] != len(audit["renderedPages"]):
            raise RuntimeError(f"PDF visual render incomplete for {variant}")
        artifacts[variant] = {"export": {k: v for k, v in exported.items() if k != "base64Content"}, "audit": audit}
        if topic == "parabola" or required_source_image_target(topic):
            teacher_text = (run_dir / "teacher" / "extracted.txt").read_text(encoding="utf-8")
            internal_reference_count = sum(teacher_text.count(marker) for marker in ("feishu://", "gaokao://", "textbook://"))
            image_count = int(artifacts.get("teacher", {}).get("audit", {}).get("imageCount", 0))
            record["sourceImagePdfAudit"] = {"internalReferenceCount": internal_reference_count, "teacherPdfImageCount": image_count}
            if internal_reference_count:
                raise RuntimeError(f"{topic} teacher PDF exposes internal source references")
            if image_count == 0:
                raise RuntimeError(f"{topic} teacher PDF contains no embedded source image")
        record.update({"workflowId": workflow_id, "terminal": task, "artifacts": artifacts, "result": "completed", "completedAt": now()})
    return 0


def main(args: argparse.Namespace | None = None) -> int:
    args = args or parse_args()
    run_timestamp = utc_run_timestamp()
    topic, goal, question = topic_for(args.run_label or run_timestamp, args.topic)
    if args.workflow_id and args.source_evidence and not args.topic:
        # Evidence-refresh recovery must keep the fixed handout subject auditable instead of deriving a random topic from the run label.
        topic, goal, question = TOPICS[0]
    args.run_label = args.run_label or f"handout-mcp-{topic}-{run_timestamp}"
    run_dir = ROOT / "output" / "acceptance" / "handout-mcp" / args.run_label
    run_dir.mkdir(parents=True, exist_ok=False)
    if args.source_evidence:
        source_path = Path(args.source_evidence).resolve()
        if not source_path.is_file():
            raise RuntimeError(f"Source evidence file does not exist: {source_path}")
        shutil.copyfile(source_path, run_dir / "retrieval-source-original.json")
    events_path = run_dir / "events.jsonl"
    configure_progress_journal(events_path)
    record: dict[str, Any] = {"runLabel": args.run_label, "runTimestamp": run_timestamp, "startedAt": now(), "serviceStates": [], "taskSnapshots": [], "timeline": [], "taskCreationPosts": 0, "reviewRecoveryExercised": False, "workflowId": args.workflow_id}
    key_id = ""
    http = Http(args.base_url, args.http_timeout, record["timeline"], events_path)
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
            write_json(run_dir / "broker-deep-read-python-visible.json", {
                "probeRunId": probe.get("probeRunId"),
                "agentSelectedDeepReads": [
                    read
                    for value in ((probe.get("privateDiagnostics", {}).get("privateDiagnostics", {})
                                   .get("resourceCollection", {}).get("iterations", {}) or {}).values())
                    if isinstance(value, dict)
                    for read in value.get("agentSelectedDeepReads", [])
                    if isinstance(read, dict)
                ],
            })
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
        if args.workflow_id:
            return resume_existing_workflow(mcp, args.workflow_id, args, run_dir, record, topic)
        progress("evidence_retrieval_started", topic=topic)
        library_queries = {
            "public_textbook": goal,
            "feishu": "抛物线 教师资料 题目 标准方程 焦点",
            "gaokao": "抛物线 高考真题 题目 标准方程 焦点",
        } if topic == "parabola" else {
            "public_textbook": goal,
            "feishu": "排列组合 涂色问题 分类计数 容斥 2013年涂色问题",
        } if topic == "coloring-combinatorics" else {
            "public_textbook": goal,
            "feishu": "立体几何 四棱锥 线面垂直 二面角 教师资料",
            "gaokao": "四棱锥P-ABCD PA垂直底面 二面角A-CP-D 正弦值 求AD 高考真题",
        } if topic == "solid-geometry" else {"public_textbook": goal, "feishu": goal}
        searches: dict[str, Any] = {}
        for library, query in library_queries.items():
            progress("evidence_library_search_started", library=library, query=query)
            searches[library] = mcp.call("search_multi_source_evidence", {"query": query, "libraries": [library], "limit": 10, "permissionScopes": ["PUBLIC_TEXTBOOK", "TEACHER_SHARED", "GAOKAO_PUBLIC"]})
            progress("evidence_library_search_completed", library=library, hitCount=len(searches[library].get("mergedHits", [])))
        search = {
            "query": goal,
            "libraries": list(library_queries),
            "libraryResults": searches,
            "textbookHits": searches["public_textbook"].get("textbookHits", []),
            "teacherResourceHits": searches.get("feishu", {}).get("teacherResourceHits", []),
            "gaokaoHits": searches.get("gaokao", {}).get("gaokaoHits", []),
            "mergedHits": [hit for result in searches.values() for hit in result.get("mergedHits", [])],
            "evidenceRefs": [ref for result in searches.values() for ref in result.get("evidenceRefs", [])],
        }
        evidence_refs = list(dict.fromkeys(ref for ref in search["evidenceRefs"] if ref))
        write_json(run_dir / "retrieval-original.json", search)
        write_json(run_dir / "retrieval-source-original.json", search)
        if not evidence_refs:
            raise RuntimeError("No authorized source-grounded evidence returned; task was not submitted.")
        if topic == "parabola":
            source_counts = {
                "textbook": len(searches["public_textbook"].get("textbookHits", [])),
                "feishu": len(searches.get("feishu", {}).get("teacherResourceHits", [])),
                "gaokao": len(searches.get("gaokao", {}).get("gaokaoHits", [])),
            }
            record["retrievalSourceCounts"] = source_counts
            if any(count <= 0 for count in source_counts.values()):
                raise RuntimeError("Parabola acceptance requires nonzero textbook, feishu, and gaokao hits: " + json.dumps(source_counts, ensure_ascii=False))
        progress("evidence_retrieval_completed", hitCount=len(evidence_refs))
        write_json(run_dir / "retrieval-status-snapshot.json", search)
        original_blocks = []
        for source_name, source_items in (
                ("textbook", search.get("textbookHits", [])),
                ("feishu", search.get("teacherResourceHits", [])),
                ("gaokao", search.get("gaokaoHits", []))):
            for item in source_items:
                if not isinstance(item, dict):
                    continue
                title = item.get("title") or item.get("documentTitle") or item.get("bookName") or source_name
                transparent_ref = item.get("transparentReference") or item.get("sourceReference") or item.get("evidenceRef") or ""
                text = item.get("evidenceText") or item.get("snippet") or item.get("text") or ""
                page = item.get("pageNo") or item.get("page") or ""
                block = item.get("blockId") or item.get("questionNumber") or item.get("chunkId") or ""
                if str(text).strip():
                    original_blocks.append(
                        f"## [{source_name}] {title}\n\n"
                        f"reference: {transparent_ref}\n"
                        f"location: {block} page={page}\n\n{text}\n")
        retrieval_stats = {
            "libraryStats": {
                library: {
                    "hitCount": len(result.get("mergedHits", [])),
                    "evidenceRefCount": len(result.get("evidenceRefs", [])),
                    "textbookHitCount": len(result.get("textbookHits", [])),
                    "teacherResourceHitCount": len(result.get("teacherResourceHits", [])),
                    "gaokaoHitCount": len(result.get("gaokaoHits", [])),
                }
                for library, result in searches.items()
            },
            "textbookHitCount": len(search["textbookHits"]),
            "teacherResourceHitCount": len(search["teacherResourceHits"]),
            "gaokaoHitCount": len(search["gaokaoHits"]),
            "mergedHitCount": len(search["mergedHits"]),
            "evidenceRefCount": len(evidence_refs),
        }
        record["retrieval"] = retrieval_stats
        write_json(run_dir / "retrieval-flow.json", {
            "at": now(), "query": goal, "libraries": list(library_queries), **retrieval_stats,
            "rawResponses": searches,
        })
        (run_dir / "resource-original.md").write_text("\n".join(original_blocks), encoding="utf-8")
        progress("stability_gate_started", phase="before-submit")
        stable_gate(http, "before-submit", record["serviceStates"], args.stability_sample_seconds)
        progress("stability_gate_completed", phase="before-submit")
        correlation = {"runLabel": args.run_label, "topic": topic, "runTimestamp": run_timestamp, "clientRequestId": idempotency_key(topic, run_timestamp), "submittedAt": now()}
        write_json(run_dir / "submission-correlation.json", correlation)
        initial_evidence = []
        for source_name, source_items in (("textbook", search.get("textbookHits", [])),
                                          ("feishu", search.get("teacherResourceHits", [])),
                                          ("gaokao", search.get("gaokaoHits", []))):
            for item in source_items:
                if not isinstance(item, dict):
                    continue
                source_scope = {"textbook": "PUBLIC_TEXTBOOK", "feishu": "TEACHER_RESOURCE", "gaokao": "CANONICAL_MATH_PAPER"}[source_name]
                assets = item.get("imageAssetIds") or []
                if not isinstance(assets, list):
                    assets = []
                initial_evidence.append({
                    "sourceScope": source_scope,
                    "sourceTitle": item.get("title") or item.get("documentTitle") or item.get("bookName") or source_name,
                    "chunkId": item.get("chunkId") or item.get("blockId") or "",
                    "pageNo": item.get("pageNo") or item.get("page") or 0,
                    "snippet": item.get("evidenceText") or item.get("snippet") or item.get("text") or "",
                    "sourceDocumentId": item.get("fileDocumentId") or item.get("sourceDocumentId") or item.get("documentId") or item.get("docId") or "",
                    "fileDocumentId": item.get("fileDocumentId") or "",
                    "sourceType": item.get("sourceType") or ("feishu" if source_name == "feishu" else source_name),
                    "assetIds": [str(asset) for asset in assets if str(asset).strip()],
                    "imageRefs": [
                        {"markdownLine": str(image.get("markdownLine", "")), "logicalPath": str(image.get("logicalPath", ""))}
                        for image in (item.get("imageRefs") or [])
                        if isinstance(image, dict)
                        and str(image.get("markdownLine", "")).strip()
                        and str(image.get("logicalPath", "")).strip()
                    ][:12],
                    "canonicalQuestionNumber": item.get("questionNumber") or "",
                })
        initial_evidence = initial_evidence[:24]
        progress("handout_submission_started", clientRequestId=correlation["clientRequestId"], initialEvidenceCount=len(initial_evidence))
        started = submit_once(mcp, {"writingGoal": "教师版、学生版和16:10课堂讲解版讲义", "questionText": question, "evidenceRefs": evidence_refs, "initialEvidence": initial_evidence, "clientRequestId": correlation["clientRequestId"]}, record)
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
            progress("handout_status_poll_started", workflowId=workflow_id)
            task = mcp.call("get_multi_agent_writing_status", {"workflowId": workflow_id})
            stage_summary = [
                {
                    "stageCode": stage.get("stageCode", ""),
                    "status": stage.get("status", ""),
                    "elapsedMs": stage.get("elapsedMs", 0),
                }
                for stage in task.get("stages", [])
                if isinstance(stage, dict)
            ]
            progress("handout_status_poll_completed", workflowId=workflow_id, status=task.get("status"), stages=stage_summary)
            record["taskSnapshots"].append({"at": now(), "status": task.get("status"), "stages": task.get("stages"), "usage": task.get("totalUsage"), "message": task.get("message")})
            write_json(run_dir / "latest-status.json", task)
            writers = task.get("writers") or task.get("artifacts", {}).get("writers") or []
            for writer in writers if isinstance(writers, list) else []:
                if not isinstance(writer, dict) or not isinstance(writer.get("markdown"), str) or not writer["markdown"].strip():
                    continue
                stage = re.sub(r"[^A-Za-z0-9_.-]+", "_", str(writer.get("stageCode") or writer.get("stage_code") or "writer"))
                (run_dir / f"{stage}.md").write_text(writer["markdown"], encoding="utf-8")
            write_json(run_dir / "acceptance-live.json", record)
            if contains_nonfresh_signal(task):
                raise RuntimeError("Task reports cache/memory reuse; fresh-generation acceptance is rejected.")
            if time.monotonic() >= deadline:
                raise RuntimeError("Polling timeout; use the persisted workflowId with read-only MCP status, not a new submit.")
            time.sleep(args.poll_interval_seconds)
        record["terminal"] = task
        write_json(run_dir / "broker-deep-read-python-visible.json", {
            "workflowId": workflow_id,
            "status": task.get("status"),
            "resourceCuration": task.get("privateDiagnostics", {}).get("resourceCollection", {})
                if isinstance(task.get("privateDiagnostics"), dict) else {},
        })
        if str(task.get("status", "")).upper() == "WAITING_REVIEW":
            record["result"] = "review-required"
            raise RuntimeError("Task requires review. The runner does not approve it automatically.")
        if str(task.get("status", "")).upper() != "COMPLETED":
            raise RuntimeError("Task did not complete: " + str(task.get("status")))
        assert_required_source_image_retained(topic, task)
        # Refresh recovery is read-only and remains scoped to this exact persisted workflow ID.
        # The workflow is owned by the MCP writing service, not the legacy teaching-task table;
        # querying /api/teaching/tasks here produces a false 404 after a successful MCP run.
        recovered = mcp.call("get_multi_agent_writing_status", {"workflowId": workflow_id})
        recovered_status = str(recovered.get("status", "")).upper()
        recovered_workflow_id = str(recovered.get("workflowId", workflow_id))
        record["recovery"] = {
            "workflowId": workflow_id,
            "sameTask": recovered_workflow_id == workflow_id,
            "status": recovered.get("status"),
            "source": "mcp:get_multi_agent_writing_status",
        }
        if recovered_workflow_id != workflow_id or recovered_status != "COMPLETED":
            raise RuntimeError(
                "Read-only MCP recovery did not confirm the completed workflow: "
                + json.dumps(record["recovery"], ensure_ascii=False)
            )
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
            if not artifacts[variant]["audit"]["renderedPages"] or artifacts[variant]["audit"]["pages"] != len(artifacts[variant]["audit"]["renderedPages"]):
                raise RuntimeError(f"PDF visual render incomplete for {variant}")
            if (topic == "parabola" or required_source_image_target(topic)) and variant == "teacher":
                teacher_text = (run_dir / "teacher" / "extracted.txt").read_text(encoding="utf-8")
                internal_reference_count = sum(teacher_text.count(marker) for marker in ("feishu://", "gaokao://", "textbook://"))
                image_count = int(artifacts[variant]["audit"].get("imageCount", 0))
                record["sourceImagePdfAudit"] = {
                    "internalReferenceCount": internal_reference_count,
                    "teacherPdfImageCount": image_count,
                }
                if internal_reference_count:
                    raise RuntimeError(f"{topic} teacher PDF exposes internal source references")
                if image_count == 0:
                    raise RuntimeError(f"{topic} teacher PDF contains no embedded source image")
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
