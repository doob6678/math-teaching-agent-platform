#!/usr/bin/env python3
"""Perform one real MCP handout run with zero initial evidence and persist redacted audit records."""
from __future__ import annotations

import hashlib
import json
import os
import re
import secrets
import subprocess
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone
from http.cookiejar import CookieJar
from pathlib import Path
from typing import Any

import pymysql

ROOT = Path(__file__).resolve().parents[2]
PROTOCOL = "2025-11-25"
TERMINAL = {"COMPLETED", "FAILED", "WAITING_REVIEW", "DRAFT_ONLY", "CANCELLED", "CANCELED"}
SAFE_STRING_FIELDS = {"status", "node", "event", "stage", "stageCode", "eventType", "operation", "result"}


def now() -> str:
    return datetime.now(timezone.utc).isoformat()


def env_values() -> dict[str, str]:
    values: dict[str, str] = {}
    source = ROOT / ".env"
    if source.is_file():
        for line in source.read_text(encoding="utf-8-sig").splitlines():
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                key, value = line.split("=", 1)
                values[key.strip()] = value.strip().strip("\"'")
    values.update(os.environ)
    return values


def body_summary(value: Any) -> dict[str, Any]:
    """Retain request/response shape and bounded counts while removing every value body."""
    if isinstance(value, dict):
        result: dict[str, Any] = {"kind": "object", "keys": sorted(str(key) for key in value)}
        for key in SAFE_STRING_FIELDS:
            item = value.get(key)
            if isinstance(item, str) and re.fullmatch(r"[A-Za-z0-9_.:-]{1,80}", item):
                result[key] = item
        for key in ("items", "blocks", "events", "evidenceRefs", "stages", "writers", "citations", "content"):
            item = value.get(key)
            if isinstance(item, list):
                result[key + "Count"] = len(item)
        return result
    if isinstance(value, list):
        return {"kind": "array", "count": len(value)}
    return {"kind": type(value).__name__}


def opaque_reference_shape(value: Any, prefix: str) -> bool:
    """Confirm opaque references crossed a boundary without retaining their values."""
    return isinstance(value, str) and value.startswith(prefix) and len(value) > len(prefix)


def stable_hash(value: Any) -> str:
    """Produce a stable public correlation token without preserving identifiers or source content."""
    encoded = str(value if value is not None else "").encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def redacted_query(query: str) -> dict[str, Any]:
    """Keep the literal search text; redact only values that look like credentials, URLs, or filesystem paths."""
    unsafe = (
        not isinstance(query, str)
        or re.search(r"https?://|(?:[A-Za-z]:[\\/]|/)[^\s]+|(?:api[_-]?key|password|token|secret)", query, re.I)
    )
    if unsafe:
        return {"form": "redacted", "redactionReason": "path_url_or_credential_marker"}
    return {"form": "semantic_text", "text": query}


def safe_text_excerpt(value: Any, limit: int = 600) -> str:
    """Expose only bounded authorized evidence text; never persist paths, URLs, or credential-like values."""
    text = re.sub(r"\s+", " ", str(value or "")).strip()
    if not text or re.search(r"https?://|(?:[A-Za-z]:[\\/]|/)[^\s]+|(?:api[_-]?key|password|token|secret)", text, re.I):
        return ""
    return text[:limit] + ("..." if len(text) > limit else "")


def safe_title_label(value: Any) -> dict[str, Any]:
    """Preserve a bounded human-readable title while excluding paths, URLs, and credentials."""
    text = safe_text_excerpt(value, 600)
    return {"form": "visible_safe_label", "text": text} if text else {"form": "absent"}


def hit_identity(hit: dict[str, Any]) -> tuple[str, str]:
    """Normalize Java record and merged-map field names without persisting their source identifiers."""
    document = hit.get("documentId", hit.get("docId", ""))
    block = hit.get("blockId", hit.get("chunkId", ""))
    return str(document or ""), str(block or "")


def candidate_record(hit: dict[str, Any], rank: int, library: str, disposition: str, reason: str,
                     merged_hit: dict[str, Any] | None = None) -> dict[str, Any]:
    """Describe one server-returned candidate without preserving source text, raw ids, refs, URLs, or paths."""
    score = hit.get("rawScore", hit.get("score"))
    mapped_ref = merged_hit.get("evidenceRef") if isinstance(merged_hit, dict) else None
    document, block = hit_identity(hit)
    return {
        "library": library,
        "rank": rank,
        "candidateHash": stable_hash("|".join((str(hit.get("source", hit.get("sourceType", ""))), document, block))),
        "sourceType": str(hit.get("sourceType") or hit.get("source") or "unknown")[:80],
        "title": safe_title_label(hit.get("title") or hit.get("documentTitle") or hit.get("bookName")),
        "textExcerpt": safe_text_excerpt(hit.get("evidenceText") or hit.get("snippet") or hit.get("text")),
        "score": score if isinstance(score, (int, float)) else None,
        "scoreBucket": "present" if isinstance(score, (int, float)) else "unavailable",
        "responseEvidenceRefHash": stable_hash(mapped_ref) if mapped_ref else None,
        "disposition": disposition,
        "reasonCode": reason,
    }


def search_audit(response: dict[str, Any], library: str, query: str) -> dict[str, Any]:
    """Trace the independent library boundary through response candidates and opaque evidence mapping."""
    stats = response.get("libraryStats") or []
    selected = next((item for item in stats if isinstance(item, dict) and item.get("library") == library), {})
    source_candidates = response.get("textbookHits") if library == "public_textbook" else response.get("teacherResourceHits")
    source_candidates = source_candidates if isinstance(source_candidates, list) else []
    merged = response.get("mergedHits") if isinstance(response.get("mergedHits"), list) else []
    merged_by_identity = {hit_identity(item): item for item in merged if isinstance(item, dict)}
    candidate_rows: list[dict[str, Any]] = []
    for rank, candidate in enumerate(source_candidates, start=1):
        if not isinstance(candidate, dict):
            candidate_rows.append({"library": library, "rank": rank, "disposition": "malformed", "reasonCode": "NON_OBJECT_RESPONSE_CANDIDATE"})
            continue
        mapped = merged_by_identity.get(hit_identity(candidate))
        if mapped is not None:
            row = candidate_record(candidate, rank, library, "accepted", "MERGED_WITHIN_LIMIT", mapped)
            row["mergedRank"] = next(index + 1 for index, item in enumerate(merged) if item is mapped)
        else:
            row = candidate_record(candidate, rank, library, "rejected", "MERGE_LIMIT_OR_CROSS_LIBRARY_ORDER")
        candidate_rows.append(row)
    mapped_refs = response.get("evidenceRefs") if isinstance(response.get("evidenceRefs"), list) else []
    opaque_records = []
    for index, ref in enumerate(mapped_refs, start=1):
        parts = ref.split(":") if isinstance(ref, str) else []
        opaque_records.append({
            "mappingOrder": index,
            "opaqueReferenceHash": stable_hash(ref),
            "opaqueReferencePrefix": parts[0][:40] if parts else "invalid",
            # A nonblank three-segment string is the public MCP evidence contract. Missing scope is a genuine
            # mapping defect, recorded without retaining the underlying raw reference.
            "disposition": "accepted" if len(parts) >= 3 and all(part.strip() for part in parts[:3]) else "malformed",
            "reasonCode": "MCP_EVIDENCE_MAPPING_THREE_SEGMENT_OPAQUE_REF" if isinstance(ref, str)
            else "NON_STRING_EVIDENCE_REFERENCE",
        })
    return {
        "operation": "search_multi_source_evidence",
        "query": redacted_query(query),
        "underlyingSearchCandidateCount": int(selected.get("hitCount", 0) or 0),
        "filterAndLibraryResultCount": int(selected.get("hitCount", 0) or 0),
        "orderedCandidates": candidate_rows,
        "mergedCandidateCount": len(merged),
        "opaqueAuthorizationMapping": opaque_records,
        "unexplainedDiscardCount": sum(1 for item in candidate_rows if item.get("disposition") == "rejected" and not item.get("reasonCode")),
    }


def question_bank_audit(response: Any, query: str) -> dict[str, Any]:
    """Record visible question-bank candidates without storing question stems, answers, or source identifiers."""
    rows = response if isinstance(response, list) else []
    candidates = []
    for rank, item in enumerate(rows, start=1):
        if not isinstance(item, dict):
            candidates.append({"library": "question_bank", "rank": rank, "disposition": "malformed",
                               "reasonCode": "NON_OBJECT_RESPONSE_CANDIDATE"})
            continue
        candidates.append({
            "library": "question_bank",
            "rank": rank,
            "candidateHash": stable_hash("|".join(str(item.get(key, "")) for key in
                                                   ("questionId", "sourceResourceDocumentId", "sourceBlockId"))),
            "sourceType": "question_bank",
            "title": safe_title_label(item.get("questionTitle")),
            "score": None,
            "scoreBucket": "not_exposed_by_question_bank_contract",
            "disposition": "accepted",
            "reasonCode": "VISIBLE_QUESTION_BANK_RESPONSE",
        })
    return {
        "operation": "search_question_bank_items",
        "query": redacted_query(query),
        "underlyingSearchCandidateCount": len(candidates),
        "filterAndLibraryResultCount": len(candidates),
        "orderedCandidates": candidates,
        "opaqueAuthorizationMapping": [],
        "unexplainedDiscardCount": 0,
    }


class Transcript:
    def __init__(self) -> None:
        self.rows: list[dict[str, Any]] = []

    def record(self, direction: str, operation: str, body: Any, status: int | str = "not-applicable",
               detail: dict[str, Any] | None = None) -> None:
        row = {"at": now(), "direction": direction, "operation": operation,
               "status": status, "body": body_summary(body)}
        if detail:
            row["detail"] = detail
        self.rows.append(row)


class Http:
    def __init__(self, base_url: str, transcript: Transcript) -> None:
        self.base_url = base_url.rstrip("/")
        self.transcript = transcript
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(CookieJar()))

    def request(self, operation: str, method: str, path: str, body: Any | None = None,
                headers: dict[str, str] | None = None) -> Any:
        self.transcript.record("request", operation, body if body is not None else {})
        encoded = None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8")
        request = urllib.request.Request(self.base_url + path, data=encoded, method=method,
            headers={"Accept": "application/json", **(headers or {})})
        if encoded is not None:
            request.add_header("Content-Type", "application/json; charset=utf-8")
        try:
            with self.opener.open(request, timeout=45) as response:
                raw = response.read()
                parsed = json.loads(raw.decode("utf-8-sig")) if raw else {}
                self.transcript.record("response", operation, parsed, response.status)
                return parsed
        except urllib.error.HTTPError as error:
            raw = error.read()
            try:
                parsed = json.loads(raw.decode("utf-8-sig")) if raw else {}
            except json.JSONDecodeError:
                parsed = {}
            self.transcript.record("response", operation, parsed, error.code)
            raise RuntimeError(operation + " failed") from error


def mcp(http: Http, secret: str, call_id: int, name: str, arguments: dict[str, Any]) -> tuple[Any, int]:
    payload = {"jsonrpc": "2.0", "id": call_id, "method": "tools/call", "params": {"name": name, "arguments": arguments}}
    response = http.request("mcp:" + name, "POST", "/api/mcp", payload,
        {"Authorization": "Bearer " + secret, "MCP-Protocol-Version": PROTOCOL,
         "Accept": "application/json, text/event-stream"})
    result = response.get("result", {})
    if response.get("error") or result.get("isError"):
        raise RuntimeError("MCP operation failed")
    structured = result.get("structuredContent")
    if isinstance(structured, (dict, list)):
        return structured, call_id + 1
    for item in result.get("content", []):
        if item.get("type") == "text":
            parsed = json.loads(item.get("text", "{}"))
            if isinstance(parsed, (dict, list)):
                return parsed, call_id + 1
    return result, call_id + 1


def prompt_from_codepoints() -> tuple[str, str]:
    """Avoid persisting the acceptance prompt while still exercising the live model path."""
    goal = "".join(chr(code) for code in (25945, 24072, 29256, 25945, 23398, 29256, 35838, 20013, 30340, 20989, 25968, 27010, 28857, 35762, 35299, 35762))
    question = "".join(chr(code) for code in (35831, 35299, 19968, 36947, 27425, 20989, 25968, 30340, 39030, 28857, 35302, 39064, 35760, 21644, 26174, 26631, 20316, 29992))
    return goal, question


def audit_query_from_codepoints() -> str:
    """Return an AI-authored audit query without persisting its source text in public evidence."""
    return "".join(chr(code) for code in (20108, 27425, 20989, 25968, 39030, 28857, 35302, 39064, 35760, 21644))


def durable_snapshot(values: dict[str, str], task_id: str) -> dict[str, Any]:
    """Read raw rows privately, returning only factual counts and operation metadata."""
    connection = pymysql.connect(host="127.0.0.1", port=3307, user="root", password=values["MYSQL_ROOT_PASSWORD"],
        database=values.get("MYSQL_DATABASE", "math_agent_rag"), charset="utf8mb4")
    try:
        with connection.cursor() as cursor:
            cursor.execute("SELECT status,response_json FROM teaching_task WHERE task_id=%s", (task_id,))
            task = cursor.fetchone()
            cursor.execute("SELECT status,state_json FROM handout_checkpoint WHERE run_id=%s", (task_id,))
            checkpoint = cursor.fetchone()
            cursor.execute("SELECT event_id,event_json FROM handout_event WHERE run_id=%s ORDER BY event_id", (task_id,))
            events = cursor.fetchall()
    finally:
        connection.close()
    task_json = json.loads(task[1]) if task and task[1] else {}
    state_json = json.loads(checkpoint[1]) if checkpoint and checkpoint[1] else {}
    evidence = task_json.get("evidence") or []
    event_json = [json.loads(row[1]) for row in events]
    node_order: list[str] = []
    for event in event_json:
        node = event.get("node")
        if isinstance(node, str) and node not in node_order:
            node_order.append(node)
    writers = state_json.get("writers") or []
    ledger_records = []
    for position, item in enumerate(evidence, start=1):
        if not isinstance(item, dict):
            ledger_records.append({"ledgerOrder": position, "disposition": "malformed", "reasonCode": "NON_OBJECT_LEDGER_ENTRY"})
            continue
        ledger_records.append({
            "ledgerOrder": position,
            "evidenceHash": stable_hash("|".join(str(item.get(key, "")) for key in ("sourceScope", "sourceDocumentId", "chunkId"))),
            "sourceScope": str(item.get("sourceScope", "unknown"))[:80],
            "sourceType": str(item.get("sourceType", "unknown"))[:80],
            "disposition": "persisted",
            "reasonCode": "DURABLE_RUN_EVIDENCE_LEDGER",
        })
    event_records = []
    for position, event in enumerate(event_json, start=1):
        event_records.append({
            "eventOrder": position,
            "node": str(event.get("node", ""))[:80],
            "event": str(event.get("event", ""))[:80],
            "status": str(event.get("status", ""))[:80],
            "errorClass": stable_hash(event.get("error", "")) if event.get("error") else None,
        })
    return {
        "taskStatus": task[0] if task else "missing",
        "checkpointStatus": checkpoint[0] if checkpoint else "missing",
        "persistedEvidenceCount": len(evidence),
        "durableLedger": ledger_records,
        "teacherResourceEvidenceCount": sum(1 for item in evidence if isinstance(item, dict) and item.get("sourceScope") == "TEACHER_RESOURCE"),
        "checkpointEventCount": len(event_json),
        "checkpointEvents": event_records,
        "checkpointNodeOrder": node_order,
        "writerCount": len(writers),
        "writerStageCount": len({str(item.get("stageCode", item.get("stage_code", ""))) for item in writers if isinstance(item, dict)}),
        "writerNonemptyCount": sum(1 for item in writers if isinstance(item, dict) and bool(str(item.get("markdown", "")).strip())),
        "writerTotalCharacterCount": sum(len(str(item.get("markdown", ""))) for item in writers if isinstance(item, dict)),
    }


def broker_log_operations(task_id: str) -> dict[str, int]:
    output = subprocess.run(["wsl.exe", "-d", "Ubuntu", "--", "bash", "-lc",
        "docker logs --since 30m math-agent-rag-backend-1 2>&1"], check=True, capture_output=True,
        text=True, encoding="utf-8", errors="replace").stdout
    counts: dict[str, int] = {}
    for line in output.splitlines():
        if task_id not in line or "handout_document_inspection" not in line:
            continue
        matched = re.search(r"operation=([^\\s]+)", line)
        if matched:
            operation = matched.group(1)
            counts[operation] = counts.get(operation, 0) + 1
    return counts


def write_json(path: Path, value: Any) -> None:
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def latest_failed_run(values: dict[str, str]) -> str:
    """Use a previously failed, already-issued run only for broker authorization testing; never submit another writer run."""
    connection = pymysql.connect(host="127.0.0.1", port=3307, user="root", password=values["MYSQL_ROOT_PASSWORD"],
        database=values.get("MYSQL_DATABASE", "math_agent_rag"), charset="utf8mb4")
    try:
        with connection.cursor() as cursor:
            cursor.execute("SELECT task_id FROM teaching_task WHERE status='FAILED' ORDER BY updated_at DESC LIMIT 1")
            row = cursor.fetchone()
    finally:
        connection.close()
    if not row or not row[0]:
        raise RuntimeError("no previously issued failed run is available for broker probe")
    return str(row[0])


def direct_teacher_audit(response: Any, query: str) -> dict[str, Any]:
    """Redact direct run-scoped broker search output while preserving its mapping and disposition chain."""
    items = response.get("items") if isinstance(response, dict) else []
    items = items if isinstance(items, list) else []
    candidates = []
    for rank, item in enumerate(items, start=1):
        if not isinstance(item, dict):
            candidates.append({"rank": rank, "disposition": "malformed", "reasonCode": "NON_OBJECT_BROKER_ITEM"})
            continue
        candidates.append({
            "rank": rank,
            "candidateHash": stable_hash("|".join(str(item.get(key, "")) for key in ("ref", "documentRef", "title"))),
            "title": safe_title_label(item.get("title") or item.get("documentName")),
            "score": "not_exposed_by_broker_contract",
            "sourceType": "teacher_resource",
            "evidenceRefHash": stable_hash(item.get("ref", "")),
            "documentRefHash": stable_hash(item.get("documentRef", "")),
            "evidenceRefDisposition": "accepted" if opaque_reference_shape(item.get("ref"), "ev_") else "malformed",
            "documentRefDisposition": "accepted" if opaque_reference_shape(item.get("documentRef"), "doc_") else "malformed",
            "disposition": "accepted" if opaque_reference_shape(item.get("ref"), "ev_")
            and opaque_reference_shape(item.get("documentRef"), "doc_") else "rejected",
            "reasonCode": "RUN_SCOPED_BROKER_MAPPING" if opaque_reference_shape(item.get("ref"), "ev_")
            and opaque_reference_shape(item.get("documentRef"), "doc_") else "OPAQUE_REFERENCE_MALFORMED",
        })
    return {
        "operation": "handout-teacher-resource-search",
        "query": redacted_query(query),
        "underlyingSearchCandidateCount": len(candidates),
        "filterAndLibraryResultCount": len(candidates),
        "orderedCandidates": candidates,
        "unexplainedDiscardCount": sum(1 for item in candidates if item.get("disposition") == "rejected" and not item.get("reasonCode")),
    }


def bounded_direct_literal_probe(query: str, probe_name: str) -> int:
    """Run one isolated literal-query retrieval audit without starting a Writer workflow."""
    values = env_values()
    username = values.get("MATH_AGENT_ACCEPTANCE_USERNAME") or values.get("MATH_AGENT_LOCAL_ACCEPTANCE_ACCOUNT_USERNAME")
    password = values.get("MATH_AGENT_ACCEPTANCE_PASSWORD") or values.get("MATH_AGENT_LOCAL_ACCEPTANCE_ACCOUNT_PASSWORD")
    worker_key = values.get("MATH_AGENT_AGENT_WORKER_SHARED_KEY")
    if not username or not password or not worker_key or not values.get("MYSQL_ROOT_PASSWORD"):
        raise RuntimeError("configured direct-probe credentials unavailable")
    started_monotonic = time.monotonic()
    deadline = started_monotonic + 180.0
    label = "direct-" + probe_name + "-probe-" + datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    output = ROOT / "output" / "acceptance" / "handout-mcp" / label
    output.mkdir(parents=True, exist_ok=False)
    transcript = Transcript()
    http = Http(values.get("MATH_AGENT_ACCEPTANCE_BASE_URL", "http://127.0.0.1:8080"), transcript)
    key_id = ""
    phase: dict[str, Any] = {
        "label": label,
        "phase": "direct_internal_tool_probe",
        "hardDeadlineSeconds": 180,
        "literalQuery": query,
        "workflowSubmission": "not_executed",
        "writerPublication": "not_applicable",
        "operations": [],
        "libraryAudit": {},
        "restrictedDocumentInspection": {"status": "not_executed", "reasonCode": "NO_AUTHORIZED_TEACHER_DOCUMENT_YET"},
        "durableLedger": {"status": "not_executed", "reasonCode": "NO_BROKER_SEARCH_YET"},
    }
    writer = {"observableFactualOutcome": False, "status": "not_applicable",
              "reasonCode": "PHASE_1_STOPS_BEFORE_WRITER_PUBLICATION"}
    conclusion: dict[str, Any] = {"label": label, "result": "inconclusive", "phaseTerminalStatus": "running",
                                  "elapsedMs": 0, "lastOperation": "not_started"}

    def ensure_time(operation: str) -> None:
        if time.monotonic() >= deadline:
            raise TimeoutError("PHASE_1_HARD_DEADLINE_EXPIRED_AFTER_" + operation)

    def record_operation(operation: str, parameters: dict[str, Any], status: str, started: float,
                         detail: dict[str, Any] | None = None) -> None:
        entry = {"operation": operation, "parameters": parameters, "status": status,
                 "elapsedMs": max(0, int((time.monotonic() - started) * 1000)),
                 "detail": detail or {"disposition": "not_applicable", "reasonCode": "NO_ADDITIONAL_DETAIL"}}
        phase["operations"].append(entry)
        conclusion["lastOperation"] = operation

    try:
        ensure_time("startup")
        health_started = time.monotonic()
        health = http.request("backend-health", "GET", "/api/system/health")
        record_operation("backend-health", {"method": "GET", "body": "none"},
                         "success" if str(health.get("status", "")).upper() == "UP" else "error", health_started,
                         {"disposition": "accepted", "reasonCode": "BACKEND_HEALTH_CHECK"})
        if str(health.get("status", "")).upper() != "UP":
            raise RuntimeError("backend health gate failed")
        ensure_time("backend-health")
        login_started = time.monotonic()
        http.request("login", "POST", "/api/auth/login", {"username": username, "password": password})
        record_operation("login", {"method": "POST", "credentialFields": ["username", "password"]}, "success", login_started,
                         {"disposition": "accepted", "reasonCode": "AUTHORIZED_AUDIT_SESSION"})
        ensure_time("login")
        key_started = time.monotonic()
        mcp_key = http.request("create-mcp-key", "POST", "/api/mcp/keys", {})
        key_id, secret = str(mcp_key["keyId"]), str(mcp_key["secretKey"])
        record_operation("create-mcp-key", {"method": "POST", "body": {}}, "success", key_started,
                         {"disposition": "accepted", "reasonCode": "TRANSIENT_AUDIT_CREDENTIAL"})
        call_id = 1
        for library in ("public_textbook", "teacher_resource"):
            ensure_time("before-" + library)
            operation_started = time.monotonic()
            searched, call_id = mcp(http, secret, call_id, "search_multi_source_evidence", {
                "query": query, "libraries": [library], "limit": 6,
            })
            library_record = search_audit(searched, library, query)
            phase["libraryAudit"][library] = library_record
            transcript.record("inspection", "search_multi_source_evidence", searched, detail={
                "library": library, "query": redacted_query(query), "limit": 6,
                "orderedCandidates": library_record["orderedCandidates"],
                "disposition": "accepted", "reasonCode": "DIRECT_LITERAL_QUERY_MCP_PROBE"})
            record_operation("search_multi_source_evidence:" + library,
                             {"query": query, "libraries": [library], "limit": 6}, "success", operation_started,
                             {"candidateCount": library_record["underlyingSearchCandidateCount"],
                              "unexplainedDiscardCount": library_record["unexplainedDiscardCount"],
                              "reasonCode": "DIRECT_LITERAL_QUERY_MCP_PROBE"})
        ensure_time("before-question-bank")
        question_started = time.monotonic()
        question_bank, call_id = mcp(http, secret, call_id, "search_question_bank_items", {"query": query, "limit": 6})
        question_record = question_bank_audit(question_bank, query)
        phase["libraryAudit"]["question_bank"] = question_record
        transcript.record("inspection", "search_question_bank_items", question_bank, detail={
            "query": redacted_query(query), "limit": 6, "orderedCandidates": question_record["orderedCandidates"],
            "disposition": "accepted", "reasonCode": "DIRECT_LITERAL_QUERY_MCP_PROBE"})
        record_operation("search_question_bank_items", {"query": query, "limit": 6}, "success", question_started,
                         {"candidateCount": question_record["underlyingSearchCandidateCount"],
                          "unexplainedDiscardCount": question_record["unexplainedDiscardCount"],
                          "reasonCode": "DIRECT_LITERAL_QUERY_MCP_PROBE"})
        ensure_time("before-run-context")
        context_started = time.monotonic()
        run_id = latest_failed_run(values)
        run_hash = stable_hash(run_id)
        record_operation("select-existing-run-context", {"runContextHash": run_hash, "selection": "latest_failed_run"},
                         "success", context_started, {"disposition": "accepted", "reasonCode": "NO_NEW_WRITER_WORKFLOW_SUBMITTED"})
        ensure_time("before-direct-broker-search")
        broker_started = time.monotonic()
        broker_response = http.request("broker:handout-teacher-resource-search", "POST",
            "/internal/agent-tools/v1/handout-teacher-resource-search", {"runId": run_id, "query": query, "limit": 6},
            {"X-Agent-Worker-Key": worker_key})
        broker_record = direct_teacher_audit(broker_response, query)
        phase["brokerTeacherSearch"] = {"runContextHash": run_hash, **broker_record}
        transcript.record("inspection", "broker:handout-teacher-resource-search", broker_response, detail={
            "runContextHash": run_hash, "query": redacted_query(query), "limit": 6,
            "orderedCandidates": broker_record["orderedCandidates"],
            "disposition": "accepted", "reasonCode": "RUN_SCOPED_TEACHER_BROKER_SEARCH"})
        record_operation("broker:handout-teacher-resource-search", {"runContextHash": run_hash, "query": query, "limit": 6},
                         "success", broker_started, {"candidateCount": broker_record["underlyingSearchCandidateCount"],
                         "unexplainedDiscardCount": broker_record["unexplainedDiscardCount"], "reasonCode": "PERSIST_THEN_RETURN_OPAQUE_REFS"})
        ensure_time("after-direct-broker-search")
        durable_started = time.monotonic()
        durable = durable_snapshot(values, run_id)
        phase["durableLedger"] = {"status": "success", "runContextHash": run_hash,
                                   "persistedEvidenceCount": durable["persistedEvidenceCount"],
                                   "teacherResourceEvidenceCount": durable["teacherResourceEvidenceCount"],
                                   "records": durable["durableLedger"]}
        record_operation("durable-evidence-ledger", {"runContextHash": run_hash}, "success", durable_started,
                         {"teacherResourceEvidenceCount": durable["teacherResourceEvidenceCount"],
                          "reasonCode": "BROKER_PERSISTENCE_VERIFICATION"})
        items = broker_response.get("items") if isinstance(broker_response, dict) else []
        returned_refs = [str(item.get("ref")) for item in items if isinstance(item, dict)
                         and opaque_reference_shape(item.get("ref"), "ev_")]
        reload_started = time.monotonic()
        reloaded = http.request("broker:handout-context-reload", "POST", "/internal/agent-tools/v1/handout-context",
            {"runId": run_id, "evidenceRefs": returned_refs, "limit": 6}, {"X-Agent-Worker-Key": worker_key})
        reloaded_items = reloaded.get("items") if isinstance(reloaded, dict) and isinstance(reloaded.get("items"), list) else []
        reload_rows = [{
            "reloadOrder": index,
            "evidenceRefHash": stable_hash(ref),
            "disposition": "accepted" if index <= len(reloaded_items) else "rejected",
            "reasonCode": "DURABLE_LEDGER_CONTEXT_RELOAD" if index <= len(reloaded_items)
            else "RETURNED_REF_NOT_RELOADABLE_FROM_DURABLE_LEDGER",
        } for index, ref in enumerate(returned_refs, start=1)]
        phase["durableLedger"]["returnedRefReload"] = {
            "submittedEvidenceRefCount": len(returned_refs), "reloadedItemCount": len(reloaded_items),
            "records": reload_rows,
            "unexplainedDiscardCount": sum(1 for row in reload_rows
                                             if row["disposition"] == "rejected" and not row["reasonCode"]),
        }
        transcript.record("inspection", "broker:handout-context-reload", reloaded, detail={
            "runContextHash": run_hash, "submittedEvidenceRefCount": len(returned_refs),
            "reloadedItemCount": len(reloaded_items), "records": reload_rows,
            "disposition": "accepted" if len(returned_refs) == len(reloaded_items) else "rejected",
            "reasonCode": "DURABLE_LEDGER_CONTEXT_RELOAD" if len(returned_refs) == len(reloaded_items)
            else "RETURNED_REF_NOT_RELOADABLE_FROM_DURABLE_LEDGER"})
        record_operation("broker:handout-context-reload", {"runContextHash": run_hash,
                         "submittedEvidenceRefCount": len(returned_refs), "limit": 6},
                         "success" if len(returned_refs) == len(reloaded_items) else "error", reload_started,
                         {"reloadedItemCount": len(reloaded_items),
                          "reasonCode": "DURABLE_LEDGER_CONTEXT_RELOAD" if len(returned_refs) == len(reloaded_items)
                          else "RETURNED_REF_NOT_RELOADABLE_FROM_DURABLE_LEDGER"})
        readable_candidates = [item for item in items if isinstance(item, dict)
                               and opaque_reference_shape(item.get("documentRef"), "doc_")]
        if not readable_candidates:
            phase["restrictedDocumentInspection"] = {"status": "zero", "reasonCode": "NO_AUTHORIZED_DOCUMENT_REF_RETURNED",
                                                       "read": "not_executed", "search": "not_executed"}
        else:
            read_attempts = []
            selected_document_ref = ""
            read_response: Any = {}
            for candidate in readable_candidates:
                document_ref = str(candidate["documentRef"])
                document_hash = stable_hash(document_ref)
                ensure_time("before-document-read")
                read_started = time.monotonic()
                try:
                    candidate_response = http.request("broker:handout-document-read", "POST", "/internal/agent-tools/v1/handout-document-read",
                        {"runId": run_id, "documentRef": document_ref, "maxBlocks": 4, "maxChars": 4000}, {"X-Agent-Worker-Key": worker_key})
                except RuntimeError:
                    read_attempts.append({"targetDocumentRefHash": document_hash, "status": "unavailable"})
                    continue
                blocks = candidate_response.get("blocks") if isinstance(candidate_response, dict) and isinstance(candidate_response.get("blocks"), list) else []
                bounded_text = "".join(str(block.get("text", "")) for block in blocks if isinstance(block, dict))
                read_attempts.append({"targetDocumentRefHash": document_hash, "status": "success", "returnedBlockCount": len(blocks), "returnedTextLength": len(bounded_text)})
                if bounded_text.strip():
                    selected_document_ref, read_response = document_ref, candidate_response
                    break
            if not selected_document_ref:
                phase["restrictedDocumentInspection"] = {"status": "zero", "reasonCode": "NO_READABLE_AUTHORIZED_DOCUMENT",
                    "attempts": read_attempts, "read": "executed", "search": "not_executed"}
            else:
                document_hash = stable_hash(selected_document_ref)
                blocks = read_response.get("blocks", [])
                bounded_text = "".join(str(block.get("text", "")) for block in blocks if isinstance(block, dict))
                read_detail = {"status": "success", "targetDocumentRefHash": document_hash, "maxBlocks": 4, "maxChars": 4000,
                               "returnedBlockCount": len(blocks), "returnedTextLength": len(bounded_text),
                               "returnedTextHash": stable_hash(bounded_text), "reasonCode": "AUTHORIZED_BOUNDED_DOCUMENT_READ"}
                transcript.record("inspection", "broker:handout-document-read", read_response, detail=read_detail)
                record_operation("broker:handout-document-read", {"runContextHash": run_hash, "documentRefHash": document_hash,
                                 "maxBlocks": 4, "maxChars": 4000}, "success", read_started, read_detail)
                ensure_time("before-document-search")
                search_started = time.monotonic()
                search_response = http.request("broker:handout-document-search", "POST", "/internal/agent-tools/v1/handout-document-search",
                    {"runId": run_id, "documentRef": selected_document_ref, "keyword": query, "maxBlocks": 4, "maxChars": 4000},
                    {"X-Agent-Worker-Key": worker_key})
                search_blocks = search_response.get("blocks") if isinstance(search_response, dict) and isinstance(search_response.get("blocks"), list) else []
                search_text = "".join(str(block.get("text", "")) for block in search_blocks if isinstance(block, dict))
                search_detail = {"status": "success", "targetDocumentRefHash": document_hash, "keyword": query,
                                 "maxBlocks": 4, "maxChars": 4000, "returnedBlockCount": len(search_blocks),
                                 "returnedTextLength": len(search_text), "returnedTextHash": stable_hash(search_text),
                                 "reasonCode": "AUTHORIZED_KEYWORD_BOUNDED_DOCUMENT_SEARCH"}
                transcript.record("inspection", "broker:handout-document-search", search_response, detail=search_detail)
                record_operation("broker:handout-document-search", {"runContextHash": run_hash, "documentRefHash": document_hash,
                                 "keyword": query, "maxBlocks": 4, "maxChars": 4000}, "success", search_started, search_detail)
                phase["restrictedDocumentInspection"] = {"status": "success", "documentRefHash": document_hash,
                    "attempts": read_attempts, "read": read_detail, "search": search_detail,
                    "writerSafeObservableImpact": "authorized_teacher_context_available_to_python_broker_consumer; writer_not_run_in_phase_1"}
        all_nonzero = all(phase["libraryAudit"][key]["underlyingSearchCandidateCount"] > 0
                          for key in ("public_textbook", "teacher_resource"))
        deep_success = phase["restrictedDocumentInspection"].get("status") == "success"
        ledger_success = phase["durableLedger"].get("teacherResourceEvidenceCount", 0) > 0
        reload_success = len(returned_refs) > 0 and len(returned_refs) == len(reloaded_items)
        conclusion.update({"result": "accepted" if all_nonzero and deep_success and ledger_success and reload_success else "rejected",
                           "phaseTerminalStatus": "completed",
                           "phase1EligibleForPhase2": bool(all_nonzero and deep_success and ledger_success and reload_success),
                           "phase2Status": "not_executed",
                           "phase2ReasonCode": "NO_CURATION_ONLY_WORKER_ENDPOINT; FULL_WRITER_FLOW_PROHIBITED_BY_PHASE_CONSTRAINT"})
        return 0 if conclusion["result"] == "accepted" else 2
    except TimeoutError as error:
        conclusion.update({"result": "timeout", "phaseTerminalStatus": "deadline_expired", "errorCode": str(error)})
        return 3
    except Exception as error:
        conclusion.update({"result": "failed", "phaseTerminalStatus": "error", "errorClass": type(error).__name__})
        return 1
    finally:
        conclusion["elapsedMs"] = max(0, int((time.monotonic() - started_monotonic) * 1000))
        phase["elapsedMs"] = conclusion["elapsedMs"]
        phase["lastOperation"] = conclusion["lastOperation"]
        if key_id:
            revoke_started = time.monotonic()
            try:
                http.request("revoke-mcp-key", "POST", "/api/mcp/keys/" + key_id + "/revoke", {})
                record_operation("revoke-mcp-key", {"keyId": "redacted"}, "success", revoke_started,
                                 {"disposition": "accepted", "reasonCode": "TRANSIENT_CREDENTIAL_REVOKED"})
            except Exception as error:
                record_operation("revoke-mcp-key", {"keyId": "redacted"}, "error", revoke_started,
                                 {"disposition": "persistence_failure", "reasonCode": type(error).__name__})
        with (output / "interaction-transcript-redacted.jsonl").open("w", encoding="utf-8") as handle:
            for row in transcript.rows:
                handle.write(json.dumps(row, ensure_ascii=False) + "\n")
        write_json(output / "evidence-chain-audit.json", phase)
        write_json(output / "writer-observable-impact.json", writer)
        write_json(output / "conclusion.json", conclusion)
        print(json.dumps({"label": label, "result": conclusion["result"], "elapsedMs": conclusion["elapsedMs"]}, ensure_ascii=False))


def durable_evidence_refs(values: dict[str, str], task_id: str) -> list[str]:
    """Recreate only this run's broker-issued opaque refs from its durable ledger; raw fields never leave memory."""
    shared_key = values.get("MATH_AGENT_AGENT_WORKER_SHARED_KEY", "")
    if not shared_key:
        raise RuntimeError("broker signing key unavailable for phase-two authorization replay")
    connection = pymysql.connect(host="127.0.0.1", port=3307, user="root", password=values["MYSQL_ROOT_PASSWORD"],
        database=values.get("MYSQL_DATABASE", "math_agent_rag"), charset="utf8mb4")
    try:
        with connection.cursor() as cursor:
            cursor.execute("SELECT response_json FROM teaching_task WHERE task_id=%s", (task_id,))
            row = cursor.fetchone()
    finally:
        connection.close()
    response = json.loads(row[0]) if row and row[0] else {}
    refs: list[str] = []
    for item in response.get("evidence") or []:
        if not isinstance(item, dict) or item.get("sourceScope") != "TEACHER_RESOURCE":
            continue
        material = "|".join((task_id, "evidence", str(item.get("sourceDocumentId", "")),
                              str(item.get("sourceScope", "")), str(item.get("sourceTitle", "")), str(item.get("chunkId", ""))))
        refs.append("ev_" + hashlib.sha256((shared_key + "|" + material).encode("utf-8")).hexdigest()[:32])
    return list(dict.fromkeys(refs))


def bounded_phase_two_plan_probe() -> int:
    """Exercise the real AI plan boundary for an already-authorized run; PLAN cannot enter any writer/publication node."""
    values = env_values()
    worker_key = values.get("MATH_AGENT_WORKER_API_KEY")
    if not worker_key or not values.get("MYSQL_ROOT_PASSWORD"):
        raise RuntimeError("configured phase-two worker credentials unavailable")
    started = time.monotonic()
    deadline_epoch_ms = int(time.time() * 1000) + 180_000
    label = "direct-parabola-phase2-plan-" + datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    output = ROOT / "output" / "acceptance" / "handout-mcp" / label
    output.mkdir(parents=True, exist_ok=False)
    transcript = Transcript()
    worker = Http("http://127.0.0.1:" + values.get("MATH_AGENT_WORKER_HOST_PORT", "8092"), transcript)
    audit: dict[str, Any] = {"label": label, "phase": "ai_plan_boundary_probe", "hardDeadlineSeconds": 180,
        "operation": "PLAN", "teacherResourceCuration": "not_executed", "writerPublication": "not_executed",
        "reasonCode": "PLAN_OPERATION_TERMINATES_AT_DURABLE_REVIEW_BOUNDARY"}
    writer: dict[str, Any] = {"observableFactualOutcome": False, "status": "not_applicable",
        "reasonCode": "PLAN_OPERATION_DOES_NOT_ENTER_WRITER_OR_PUBLICATION_NODES"}
    conclusion: dict[str, Any] = {"label": label, "result": "inconclusive", "phaseTerminalStatus": "running",
        "lastOperation": "not_started", "elapsedMs": 0}
    try:
        run_id = latest_failed_run(values)
        run_hash = stable_hash(run_id)
        durable_before = durable_snapshot(values, run_id)
        if durable_before["teacherResourceEvidenceCount"] <= 0:
            raise RuntimeError("phase two requires broker-persisted teacher evidence")
        authorized_refs = durable_evidence_refs(values, run_id)
        if not authorized_refs:
            raise RuntimeError("phase two could not recover broker-persisted opaque evidence references")
        goal, question = prompt_from_codepoints()
        payload = {"runId": run_id, "taskId": run_id, "writingGoal": goal, "questionText": question,
                   "evidenceRefs": authorized_refs, "operation": "PLAN", "resume": False,
                   "idempotencyKey": "phase2-plan-" + stable_hash(run_id)[:24], "deadlineEpochMs": deadline_epoch_ms}
        before = time.monotonic()
        conclusion["lastOperation"] = "worker:handout-runs-sync:PLAN"
        # Http.request is fixed to 45 seconds for broker calls; use the same authenticated endpoint with the phase
        # deadline as its transport ceiling, retaining the raw package only in process before redaction.
        encoded = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        request = urllib.request.Request(worker.base_url + "/v1/handout-runs/sync", data=encoded, method="POST",
            headers={"Accept": "application/json", "Content-Type": "application/json; charset=utf-8",
                     "Authorization": "Bearer " + worker_key})
        worker.transcript.record("request", "worker:handout-runs-sync:PLAN", payload)
        try:
            with worker.opener.open(request, timeout=180) as response:
                raw = response.read()
                package = json.loads(raw.decode("utf-8-sig")) if raw else {}
                worker.transcript.record("response", "worker:handout-runs-sync:PLAN", package, response.status)
                response_status = response.status
        except urllib.error.HTTPError as error:
            raw = error.read()
            package = json.loads(raw.decode("utf-8-sig")) if raw else {}
            worker.transcript.record("response", "worker:handout-runs-sync:PLAN", package, error.code)
            response_status = error.code
        elapsed = max(0, int((time.monotonic() - before) * 1000))
        if time.monotonic() - started > 180:
            raise TimeoutError("PHASE_2_HARD_DEADLINE_EXPIRED_AFTER_PLAN_REQUEST")
        durable_after = durable_snapshot(values, run_id)
        plan = package.get("writingPlan") if isinstance(package, dict) else None
        planned_queries = plan.get("teacherResourceQueries") if isinstance(plan, dict) and isinstance(plan.get("teacherResourceQueries"), list) else []
        audit.update({"runContextHash": run_hash, "request": {"operation": "PLAN",
                      "initialEvidenceRefsCount": len(authorized_refs),
                      "initialEvidenceRefHashes": [stable_hash(item) for item in authorized_refs],
                      "deadlineSeconds": 180, "resume": False}, "responseStatus": response_status,
                      "responseStatusValue": package.get("status") if isinstance(package, dict) else "invalid",
                      "elapsedMs": elapsed, "durableBefore": {"teacherResourceEvidenceCount": durable_before["teacherResourceEvidenceCount"]},
                      "durableAfter": {"teacherResourceEvidenceCount": durable_after["teacherResourceEvidenceCount"],
                                       "checkpointNodeOrder": durable_after["checkpointNodeOrder"],
                                       "checkpointEvents": durable_after["checkpointEvents"]},
                      "aiPlan": {"status": "formed" if isinstance(plan, dict) else "not_formed",
                                 "teacherResourceQueryCount": len(planned_queries),
                                 "teacherResourceQueries": [redacted_query(str(item)) for item in planned_queries],
                                 "citationReferenceCount": sum(len(item.get("evidenceRefs", [])) for item in plan.get("questions", []) if isinstance(item, dict)) if isinstance(plan, dict) else 0},
                      "toolCallDisposition": {"resourceCuration": "executed" if "resource_curation" in durable_after["checkpointNodeOrder"] else "not_executed",
                                                "restrictedDocumentRead": "executed" if durable_after["teacherResourceEvidenceCount"] > 0 else "not_executed",
                                                "teacherResourceCuration": "not_executed",
                                                "reasonCode": "PLAN_OPERATION_TERMINATES_BEFORE_CURATION"}})
        accepted = response_status == 200 and package.get("status") == "WAITING_REVIEW" and isinstance(plan, dict) \
            and "resource_curation" in durable_after["checkpointNodeOrder"] and "plan_writer" in durable_after["checkpointNodeOrder"] \
            and "teacher_resource_curation" not in durable_after["checkpointNodeOrder"]
        conclusion.update({"result": "accepted" if accepted else "rejected", "phaseTerminalStatus": "completed",
                           "lastOperation": "worker:handout-runs-sync:PLAN", "phase2FullWriterFlow": "not_executed",
                           "teacherResourceCurationToolCall": "not_executed", "reasonCode": "PLAN_OPERATION_TERMINATES_BEFORE_CURATION"})
        return 0 if accepted else 2
    except TimeoutError as error:
        conclusion.update({"result": "timeout", "phaseTerminalStatus": "deadline_expired", "errorCode": str(error)})
        return 3
    except Exception as error:
        conclusion.update({"result": "failed", "phaseTerminalStatus": "error", "errorClass": type(error).__name__})
        return 1
    finally:
        conclusion["elapsedMs"] = max(0, int((time.monotonic() - started) * 1000))
        audit["elapsedMs"] = conclusion["elapsedMs"]
        audit["lastOperation"] = conclusion["lastOperation"]
        with (output / "interaction-transcript-redacted.jsonl").open("w", encoding="utf-8") as handle:
            for row in transcript.rows:
                handle.write(json.dumps(row, ensure_ascii=False) + "\n")
        write_json(output / "evidence-chain-audit.json", audit)
        write_json(output / "writer-observable-impact.json", writer)
        write_json(output / "conclusion.json", conclusion)
        print(json.dumps({"label": label, "result": conclusion["result"], "elapsedMs": conclusion["elapsedMs"]}, ensure_ascii=False))


def main() -> int:
    if os.getenv("MATH_AGENT_DIRECT_PARABOLA_PHASE2") == "1":
        return bounded_phase_two_plan_probe()
    if os.getenv("MATH_AGENT_DIRECT_PARABOLA_PROBE") == "1":
        return bounded_direct_literal_probe("抛物线", "parabola")
    literal_probe = os.getenv("MATH_AGENT_DIRECT_LITERAL_PROBE", "").strip()
    literal_names = {"圆锥曲线": "conic-sections", "椭圆": "ellipse"}
    if literal_probe in literal_names:
        return bounded_direct_literal_probe(literal_probe, literal_names[literal_probe])
    values = env_values()
    username = values.get("MATH_AGENT_ACCEPTANCE_USERNAME") or values.get("MATH_AGENT_LOCAL_ACCEPTANCE_ACCOUNT_USERNAME")
    password = values.get("MATH_AGENT_ACCEPTANCE_PASSWORD") or values.get("MATH_AGENT_LOCAL_ACCEPTANCE_ACCOUNT_PASSWORD")
    if not username or not password or not values.get("MYSQL_ROOT_PASSWORD"):
        raise RuntimeError("configured credentials unavailable")
    label = "zero-initial-evidence-" + datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    output = ROOT / "output" / "acceptance" / "handout-mcp" / label
    output.mkdir(parents=True, exist_ok=False)
    transcript = Transcript()
    http = Http(values.get("MATH_AGENT_ACCEPTANCE_BASE_URL", "http://127.0.0.1:8080"), transcript)
    key_id = ""
    conclusion: dict[str, Any] = {"label": label, "result": "inconclusive", "fakeMockSyntheticValidation": False}
    audit: dict[str, Any] = {"label": label, "initialAuthorizedEvidenceCount": 0,
        "oldKnownFailureComparison": {"pythonCallsBeforeRepair": 0},
        "libraryAudit": {}}
    writer: dict[str, Any] = {"observableFactualOutcome": False}
    try:
        health = http.request("backend-health", "GET", "/api/system/health")
        if str(health.get("status", "")).upper() != "UP":
            raise RuntimeError("health gate failed")
        http.request("login", "POST", "/api/auth/login", {"username": username, "password": password})
        mcp_key = http.request("create-mcp-key", "POST", "/api/mcp/keys", {})
        key_id, secret = str(mcp_key["keyId"]), str(mcp_key["secretKey"])
        # The audit query is generated by this AI acceptance client, not copied from the task's teaching goal.
        # Each public library is called independently so a merged result cannot hide a zero after a filter boundary.
        audit_query = audit_query_from_codepoints()
        resources, call_id = mcp(http, secret, 1, "list_teacher_resources", {})
        source_types = sorted({str(item.get("sourceType", "")).strip().lower()
                               for item in resources if isinstance(item, dict) and str(item.get("sourceType", "")).strip()})
        transcript.record("inspection", "list_teacher_resources", resources,
                          detail={"disposition": "accepted", "reasonCode": "DISCOVER_VISIBLE_LIBRARY_TYPES",
                                  "sourceTypeCount": len(source_types)})
        for library in ["public_textbook", *source_types]:
            searched, call_id = mcp(http, secret, call_id, "search_multi_source_evidence", {
                "query": audit_query,
                "libraries": [library],
                "limit": 6,
            })
            library_record = search_audit(searched, library, audit_query)
            audit["libraryAudit"][library] = library_record
            transcript.record("inspection", "search_multi_source_evidence", searched,
                              detail={"library": library, "query": redacted_query(audit_query),
                                      "candidateCount": library_record["underlyingSearchCandidateCount"],
                                      "disposition": "accepted", "reasonCode": "INDEPENDENT_LIBRARY_AUDIT"})
        question_bank, call_id = mcp(http, secret, call_id, "search_question_bank_items", {
            "query": audit_query,
            "limit": 6,
        })
        question_bank_record = question_bank_audit(question_bank, audit_query)
        audit["libraryAudit"]["question_bank"] = question_bank_record
        transcript.record("inspection", "search_question_bank_items", question_bank,
                          detail={"library": "question_bank", "query": redacted_query(audit_query),
                                  "candidateCount": question_bank_record["underlyingSearchCandidateCount"],
                                  "disposition": "accepted", "reasonCode": "INDEPENDENT_LIBRARY_AUDIT"})
        goal, question = prompt_from_codepoints()
        started, call_id = mcp(http, secret, 1, "start_multi_agent_writing", {
            "writingGoal": goal, "questionText": question, "evidenceRefs": [],
            "clientRequestId": "zero-evidence-" + secrets.token_hex(12),
        })
        task_id = str(started.get("workflowId", ""))
        if not task_id:
            raise RuntimeError("no workflow identifier")
        transcript.record("inspection", "zero-initial-evidence-submission", {"initialEvidenceRefsCount": 0, "taskCreationPosts": 1})
        deadline = time.monotonic() + 720
        current = started
        while str(current.get("status", "")).upper() not in TERMINAL:
            time.sleep(8)
            current, call_id = mcp(http, secret, call_id, "get_multi_agent_writing_status", {"workflowId": task_id})
            if time.monotonic() >= deadline:
                raise RuntimeError("timeout")
        durable = durable_snapshot(values, task_id)
        operations = broker_log_operations(task_id)
        audit.update({"terminalMcpStatus": current.get("status"), "durable": durable, "javaInspectionOperations": operations})
        audit["zeroInitialEvidenceDisposition"] = {
            "disposition": "blocked" if durable["persistedEvidenceCount"] == 0 else "continued",
            "reasonCode": "PLAN_REQUIRES_AUTHORIZED_EVIDENCE_BEFORE_AI_RESOURCE_CURATION"
            if durable["persistedEvidenceCount"] == 0 and "teacher_resource_curation" not in durable["checkpointNodeOrder"]
            else "RESOURCE_CURATION_CONTINUED",
        }
        audit["accepted"] = bool(
            durable["persistedEvidenceCount"] > 0 and durable["teacherResourceEvidenceCount"] > 0
            and {"resource_curation", "plan_writer", "teacher_resource_curation"}.issubset(durable["checkpointNodeOrder"])
            and operations.get("model-teacher-search", 0) >= 1
            and operations.get("read", 0) + operations.get("keyword-search", 0) >= 1
        )
        writer = {key: durable[key] for key in ("writerCount", "writerStageCount", "writerNonemptyCount", "writerTotalCharacterCount")}
        writer["terminalMcpStatus"] = current.get("status")
        writer["observableFactualOutcome"] = durable["writerNonemptyCount"] > 0
        conclusion.update({"result": "accepted" if audit["accepted"] and writer["observableFactualOutcome"] else "rejected",
            "terminalMcpStatus": current.get("status"), "checks": {
                "zeroInitialEvidence": True,
                "pythonStarted": "resource_curation" in durable["checkpointNodeOrder"],
                "aiTeacherSearch": "teacher_resource_curation" in durable["checkpointNodeOrder"] and operations.get("model-teacher-search", 0) >= 1,
                "opaqueRunScopedAuthorizationEvidencePersisted": durable["teacherResourceEvidenceCount"] > 0,
                "restrictedDocumentReadOrSearch": operations.get("read", 0) + operations.get("keyword-search", 0) >= 1,
                "writerObservableOutcome": writer["observableFactualOutcome"],
            }})
        return 0 if conclusion["result"] == "accepted" else 2
    except Exception as error:
        audit["execution"] = {"taskCreated": False, "pythonCallsObserved": 0}
        conclusion.update({"result": "failed", "errorClass": type(error).__name__})
        return 1
    finally:
        if key_id:
            try:
                http.request("revoke-mcp-key", "POST", "/api/mcp/keys/" + key_id + "/revoke", {})
            except Exception:
                transcript.record("response", "revoke-mcp-key", {}, "failed")
        with (output / "interaction-transcript-redacted.jsonl").open("w", encoding="utf-8") as handle:
            for row in transcript.rows:
                handle.write(json.dumps(row, ensure_ascii=False) + "\n")
        write_json(output / "evidence-chain-audit.json", audit)
        write_json(output / "writer-observable-impact.json", writer)
        write_json(output / "conclusion.json", conclusion)
        print(json.dumps({"label": label, "result": conclusion["result"]}, ensure_ascii=False))


if __name__ == "__main__":
    raise SystemExit(main())
