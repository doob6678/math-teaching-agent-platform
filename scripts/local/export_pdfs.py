#!/usr/bin/env python3
"""Export teacher, student, and lecture PDFs from a completed workflow."""
import base64
import json
import sys
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
progress = module.progress
redact = module.redact

WORKFLOW_ID = "fe814d79-7407-43a5-a9e3-3504fbdfe6a7"
BASE_URL = "http://127.0.0.1:8080"
HTTP_TIMEOUT = 60
OUTPUT_DIR = ROOT / "output" / "acceptance" / "handout-mcp" / f"recovered-{WORKFLOW_ID[:8]}"

def main():
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
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
    artifacts = {}
    
    try:
        for variant, fmt in [("teacher", "pdf-teacher"), ("student", "pdf-student"), ("lecture", "pdf-lecture")]:
            progress("pdf_export_started", workflowId=WORKFLOW_ID, variant=variant, format=fmt)
            exported = mcp.call("export_multi_agent_writing_artifact", {"workflowId": WORKFLOW_ID, "format": fmt})
            
            data = base64.b64decode(exported["base64Content"], validate=True)
            pdf_path = OUTPUT_DIR / f"{variant}.pdf"
            pdf_path.write_bytes(data)
            
            metadata = {k: v for k, v in exported.items() if k != "base64Content"}
            metadata["path"] = str(pdf_path)
            metadata["sizeBytes"] = len(data)
            artifacts[variant] = metadata
            
            progress("pdf_export_completed", workflowId=WORKFLOW_ID, variant=variant, path=str(pdf_path), sizeBytes=len(data))
            print(f"✓ {variant}: {pdf_path} ({len(data):,} bytes)", file=sys.stderr)
        
        summary = {
            "workflowId": WORKFLOW_ID,
            "outputDirectory": str(OUTPUT_DIR),
            "artifacts": artifacts,
            "exportedAt": module.now()
        }
        
        summary_path = OUTPUT_DIR / "export-summary.json"
        summary_path.write_text(json.dumps(redact(summary), ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        progress("export_summary_written", path=str(summary_path))
        
        print(f"\n✓ All PDFs exported to: {OUTPUT_DIR}", file=sys.stderr)
        print(json.dumps(summary, ensure_ascii=False, indent=2))
        return 0
        
    finally:
        try:
            http.request("POST", f"/api/mcp/keys/{key_id}/revoke", {})
            progress("mcp_key_revoked", keyId=key_id)
        except Exception as e:
            print(f"Key revocation error: {type(e).__name__}", file=sys.stderr)

if __name__ == "__main__":
    sys.exit(main())
