#!/usr/bin/env python3
"""Read and optionally resume one existing handout workflow without creating a new task."""
import json
import os
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts" / "local"))

import importlib.util
spec = importlib.util.spec_from_file_location("run_handout_mcp_acceptance", ROOT / "scripts" / "local" / "run_handout_mcp_acceptance.py")
module = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = module
spec.loader.exec_module(module)

Http = module.Http
Mcp = module.Mcp
configured_credentials = module.configured_credentials
redact = module.redact
progress = module.progress

WORKFLOW_ID = "26c40475-9373-42c6-bddc-3953e5474129"
BASE_URL = os.getenv("MATH_AGENT_ACCEPTANCE_BASE_URL", "http://127.0.0.1:8080")
HTTP_TIMEOUT = 30
POLL_INTERVAL = 15
MAX_POLLS = 40

def main():
    timeline = []
    http = Http(BASE_URL, HTTP_TIMEOUT, timeline)
    username, password = configured_credentials()

    progress("login_started")
    http.request("POST", "/api/auth/login", {"username": username, "password": password})
    progress("login_completed")

    progress("mcp_key_created_started")
    key, _ = http.request("POST", "/api/mcp/keys", {})
    key_id, secret = str(key["keyId"]), str(key["secretKey"])
    progress("mcp_key_created", keyId=key_id)

    mcp = Mcp(http, secret)

    try:
        progress("workflow_status_read", workflowId=WORKFLOW_ID)
        status = mcp.call("get_multi_agent_writing_status", {"workflowId": WORKFLOW_ID})
        print(json.dumps(redact(status), ensure_ascii=False, indent=2))

        current_status = str(status.get("status", "")).upper()
        print(f"\nCurrent status: {current_status}", file=sys.stderr)

        if current_status in {"COMPLETED"}:
            print("Workflow already completed; exporting PDFs directly.", file=sys.stderr)
            return status

        if current_status in {"FAILED"}:
            progress("workflow_resume_started", workflowId=WORKFLOW_ID)
            resumed = mcp.call("resume_multi_agent_writing", {"workflowId": WORKFLOW_ID})
            print(f"\nResumed: {json.dumps(redact(resumed), ensure_ascii=False, indent=2)}", file=sys.stderr)
            time.sleep(5)

        for poll_count in range(MAX_POLLS):
            progress("workflow_status_poll", workflowId=WORKFLOW_ID, poll=poll_count + 1)
            status = mcp.call("get_multi_agent_writing_status", {"workflowId": WORKFLOW_ID})
            current_status = str(status.get("status", "")).upper()
            stages = status.get("stages", [])
            message = status.get("message", "")

            print(f"\n[Poll {poll_count + 1}/{MAX_POLLS}] Status: {current_status}", file=sys.stderr)
            if stages:
                print(f"Stages: {json.dumps(stages, ensure_ascii=False)}", file=sys.stderr)
            if message:
                print(f"Message: {message}", file=sys.stderr)

            if current_status in {"COMPLETED", "FAILED", "WAITING_REVIEW"}:
                print(f"\nTerminal status reached: {current_status}", file=sys.stderr)
                return status

            time.sleep(POLL_INTERVAL)

        print(f"\nPolling budget exhausted; last status: {current_status}", file=sys.stderr)
        return status

    finally:
        try:
            http.request("POST", f"/api/mcp/keys/{key_id}/revoke", {})
            progress("mcp_key_revoked", keyId=key_id)
        except Exception as e:
            print(f"Key revocation error: {type(e).__name__}", file=sys.stderr)

if __name__ == "__main__":
    result = main()
    sys.exit(0 if str(result.get("status", "")).upper() == "COMPLETED" else 1)
