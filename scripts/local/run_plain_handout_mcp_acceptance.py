#!/usr/bin/env python3
"""明文、可恢复的真实 MCP 讲义验收运行器。

非凭据 MCP 响应和 AI Writer Markdown 以 UTF-8 原文持久化；MCP key 仅驻留内存。
新任务只提交一次；使用 --workflow-id 可从既有任务继续轮询和导出。
"""
from __future__ import annotations

import argparse
import base64
import json
import os
import re
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts" / "local" / "run_handout_mcp_acceptance.py"
import importlib.util
spec = importlib.util.spec_from_file_location("current_runner", SCRIPT)
assert spec and spec.loader
runner = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = runner
spec.loader.exec_module(runner)

TERMINAL = {"COMPLETED", "FAILED", "WAITING_REVIEW", "DRAFT_ONLY", "CANCELLED", "CANCELED"}


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def env_credentials() -> tuple[str, str]:
    values = {**runner.parse_env(ROOT / ".env"), **os.environ}
    username = values.get("MATH_AGENT_ACCEPTANCE_USERNAME") or values.get("MATH_AGENT_LOCAL_ACCEPTANCE_ACCOUNT_USERNAME")
    password = values.get("MATH_AGENT_ACCEPTANCE_PASSWORD") or values.get("MATH_AGENT_LOCAL_ACCEPTANCE_ACCOUNT_PASSWORD")
    if not username or not password:
        raise RuntimeError("acceptance credentials are unavailable in existing .env/process environment")
    return username, password


def write_text(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


def append_event(path: Path, event: dict) -> None:
    with path.open("a", encoding="utf-8", newline="\n") as handle:
        handle.write(json.dumps(event, ensure_ascii=False) + "\n")
        handle.flush()
        os.fsync(handle.fileno())


def public_json(value):
    """Remove only credentials; preserve all other response text and identifiers verbatim."""
    if isinstance(value, dict):
        secret_names = {"secretKey", "password", "authorization", "cookie", "apiKey", "token"}
        return {str(k): "[REDACTED]" if str(k) in secret_names else public_json(v) for k, v in value.items()}
    if isinstance(value, list):
        return [public_json(v) for v in value]
    return value


def save_json(path: Path, value) -> None:
    write_text(path, json.dumps(public_json(value), ensure_ascii=False, indent=2) + "\n")


def extract_writers(status: dict, output: Path) -> dict[str, int]:
    writers = status.get("writers") or status.get("artifacts", {}).get("writers") or []
    counts: dict[str, int] = {}
    if not isinstance(writers, list):
        return counts
    for item in writers:
        if not isinstance(item, dict):
            continue
        stage = str(item.get("stageCode") or item.get("stage_code") or item.get("version") or "unknown")
        markdown = item.get("markdown")
        if not isinstance(markdown, str) or not markdown.strip():
            continue
        safe_stage = re.sub(r"[^A-Za-z0-9_.-]+", "_", stage)
        write_text(output / f"{safe_stage}.md", markdown)
        counts[stage] = len(markdown)
    return counts


def save_resource_text(status: dict, output: Path) -> int:
    """Persist raw source text fields from status and nested writer/evidence payloads."""
    candidates = []
    containers = [status]
    for container in containers:
        for key in ("evidence", "resources", "retrievedEvidence", "resourceEvidence", "sourceBlocks", "inspectedItems", "blocks"):
            value = container.get(key)
            if isinstance(value, list):
                candidates.extend(value)
    chunks = []
    for item in candidates:
        if not isinstance(item, dict):
            continue
        text = item.get("text") or item.get("evidenceText") or item.get("content") or item.get("snippet")
        if isinstance(text, str) and text.strip():
            title = item.get("title") or item.get("sourceTitle") or item.get("documentTitle") or "resource"
            chunks.append(f"## {title}\n\n{text}\n")
    if chunks:
        write_text(output / "resource-original.md", "\n".join(chunks))
    return sum(len(chunk) for chunk in chunks)


def record_mcp(mcp, name: str, arguments: dict, output: Path, events: Path):
    started = time.monotonic()
    append_event(events, {"at": utc_now(), "kind": "request", "operation": name, "arguments": public_json(arguments)})
    result = mcp.call(name, arguments)
    append_event(events, {"at": utc_now(), "kind": "response", "operation": name, "elapsedMs": round((time.monotonic() - started) * 1000), "response": public_json(result)})
    save_json(output / f"latest-{name}.json", result)
    return result


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--workflow-id")
    parser.add_argument("--topic", choices=[item[0] for item in runner.TOPICS], default="parabola")
    parser.add_argument("--run-label")
    parser.add_argument("--poll-interval-seconds", type=int, default=15)
    parser.add_argument("--timeout", type=int, default=900)
    parser.add_argument("--resume-failed", action="store_true")
    args = parser.parse_args()
    label = args.run_label or f"plain-mcp-{args.topic}-{datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')}"
    output = ROOT / "output" / "acceptance" / "handout-mcp" / label
    output.mkdir(parents=True, exist_ok=False)
    events = output / "events.jsonl"
    record = {"runLabel": label, "startedAt": utc_now(), "workflowId": args.workflow_id, "submissionCount": 0, "outputDirectory": str(output)}
    save_json(output / "run.json", record)
    timeline: list[dict] = []
    http = runner.Http(os.getenv("MATH_AGENT_ACCEPTANCE_BASE_URL", "http://127.0.0.1:8080"), 45, timeline)
    key_id = ""
    try:
        record["serviceOwner"] = runner.service_gate()
        runner.stable_gate(http, "plain-run", [], 30)
        username, password = env_credentials()
        http.request("POST", "/api/auth/login", {"username": username, "password": password})
        key, _ = http.request("POST", "/api/mcp/keys", {})
        key_id, secret = str(key["keyId"]), str(key["secretKey"])
        mcp = runner.Mcp(http, secret)
        workflow_id = args.workflow_id
        if not workflow_id:
            _, goal, question = runner.topic_for(label, args.topic)
            search = record_mcp(mcp, "search_multi_source_evidence", {"query": goal, "libraries": ["public_textbook", "feishu"], "limit": 6, "permissionScopes": ["PUBLIC_TEXTBOOK", "TEACHER_SHARED"]}, output, events)
            hits = search.get("hits", search.get("mergedHits", [])) if isinstance(search, dict) else []
            evidence_refs = [item.get("evidenceRef") for item in hits if isinstance(item, dict) and item.get("evidenceRef")]
            if not evidence_refs:
                raise RuntimeError("no authorized evidence returned; no task submitted")
            started = record_mcp(mcp, "start_multi_agent_writing", {"writingGoal": "教师版、学生版和课堂讲解版讲义", "questionText": question, "evidenceRefs": evidence_refs, "clientRequestId": runner.idempotency_key(args.topic, runner.utc_run_timestamp())}, output, events)
            workflow_id = str(started.get("workflowId", ""))
            record["submissionCount"] = 1
            if not workflow_id:
                raise RuntimeError("MCP response did not contain workflowId")
        record["workflowId"] = workflow_id
        save_json(output / "run.json", record)
        deadline = time.monotonic() + args.timeout
        status = None
        while True:
            status = record_mcp(mcp, "get_multi_agent_writing_status", {"workflowId": workflow_id}, output, events)
            save_json(output / "latest-status.json", status)
            writer_counts = extract_writers(status, output)
            resource_chars = save_resource_text(status, output)
            record.update({"lastStatusAt": utc_now(), "status": status.get("status"), "writerCharacterCounts": writer_counts, "resourceOriginalCharacters": resource_chars})
            save_json(output / "run.json", record)
            if str(status.get("status", "")).upper() == "FAILED" and args.resume_failed:
                record_mcp(mcp, "resume_multi_agent_writing", {"workflowId": workflow_id}, output, events)
                record["resumed"] = True
                args.resume_failed = False
                save_json(output / "run.json", record)
                time.sleep(args.poll_interval_seconds)
                continue
            if str(status.get("status", "")).upper() in TERMINAL:
                break
            if time.monotonic() >= deadline:
                raise TimeoutError("polling timeout; rerun with --workflow-id " + workflow_id)
            time.sleep(args.poll_interval_seconds)
        if str(status.get("status", "")).upper() != "COMPLETED":
            raise RuntimeError("workflow ended with " + str(status.get("status")))
        for variant, fmt in (("teacher", "pdf-teacher"), ("student", "pdf-student"), ("lecture", "pdf-lecture")):
            exported = record_mcp(mcp, "export_multi_agent_writing_artifact", {"workflowId": workflow_id, "format": fmt}, output, events)
            data = base64.b64decode(exported["base64Content"], validate=True)
            pdf = output / f"{variant}.pdf"
            pdf.write_bytes(data)
            save_json(output / f"{variant}-export.json", {k: v for k, v in exported.items() if k != "base64Content"})
        record.update({"completedAt": utc_now(), "result": "completed", "timeline": timeline})
        save_json(output / "run.json", record)
        return 0
    finally:
        if key_id:
            try:
                http.request("POST", f"/api/mcp/keys/{key_id}/revoke", {})
            except Exception:
                append_event(events, {"at": utc_now(), "kind": "warning", "operation": "revoke-mcp-key", "message": "revocation failed"})
        record.setdefault("completedAt", utc_now())
        record["timeline"] = timeline
        save_json(output / "run.json", record)


def main_with_existing(args, output, events, http, mcp, workflow_id, record):
    # Resume uses the same process/key and never submits a new workflow.
    args.workflow_id = workflow_id
    return 0 if main() == 0 else 1


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as error:
        print(f"plain acceptance failed: {type(error).__name__}: {error}", file=sys.stderr)
        raise SystemExit(1)
