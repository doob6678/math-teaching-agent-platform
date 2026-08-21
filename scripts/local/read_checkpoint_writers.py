#!/usr/bin/env python3
"""Read writer documents directly from Python checkpoint."""
import json
import os
import sys
import pymysql
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts" / "local"))

# Get MySQL credentials from .env
env_path = ROOT / ".env"
env_vars = {}
if env_path.exists():
    for line in env_path.read_text(encoding="utf-8-sig").splitlines():
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            key, value = line.split("=", 1)
            key = key.strip()
            value = value.strip().strip("\"'")
            if key:
                env_vars[key] = value

password = env_vars.get("MYSQL_ROOT_PASSWORD", os.getenv("MYSQL_ROOT_PASSWORD", ""))
if not password:
    print("MYSQL_ROOT_PASSWORD not found", file=sys.stderr)
    sys.exit(1)

RUN_ID = "fe814d79-7407-43a5-a9e3-3504fbdfe6a7"

conn = pymysql.connect(
    host="127.0.0.1",
    port=3307,
    user="root",
    password=password,
    database="math_agent_rag",
    charset="utf8mb4"
)

try:
    with conn.cursor() as cursor:
        cursor.execute(
            "SELECT state_json FROM handout_checkpoint WHERE run_id LIKE %s ORDER BY updated_at DESC LIMIT 1",
            (f"%{RUN_ID}%",)
        )
        row = cursor.fetchone()
        
        if not row:
            print(f"No checkpoint found for {RUN_ID}", file=sys.stderr)
            sys.exit(1)
        
        state = json.loads(row[0])
        writers = state.get("writers", [])
        
        print(f"Found {len(writers)} writer documents", file=sys.stderr)
        
        output_dir = ROOT / "output" / "acceptance" / "handout-mcp" / "recovered-fe814d79" / "checkpoint-writers"
        output_dir.mkdir(parents=True, exist_ok=True)
        
        for writer in writers:
            stage = writer.get("stageCode", writer.get("stage_code", "unknown"))
            title = writer.get("title", "")
            markdown = writer.get("markdown", "")
            citations = writer.get("citations", [])
            warnings = writer.get("warnings", [])
            
            print(f"\n=== {stage} ===", file=sys.stderr)
            print(f"Title: {title}", file=sys.stderr)
            print(f"Markdown length: {len(markdown)} chars", file=sys.stderr)
            print(f"Citations: {len(citations)}", file=sys.stderr)
            print(f"Warnings: {len(warnings)}", file=sys.stderr)
            
            if markdown:
                print(f"First 200 chars: {markdown[:200]}", file=sys.stderr)
            
            writer_path = output_dir / f"{stage}.json"
            writer_path.write_text(json.dumps(writer, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
            
            md_path = output_dir / f"{stage}.md"
            md_path.write_text(markdown, encoding="utf-8")
            
            print(f"Saved to: {md_path}", file=sys.stderr)
        
        summary = {
            "runId": RUN_ID,
            "writerCount": len(writers),
            "writers": [
                {
                    "stage": w.get("stageCode", w.get("stage_code")),
                    "title": w.get("title"),
                    "markdownChars": len(w.get("markdown", "")),
                    "citationCount": len(w.get("citations", [])),
                }
                for w in writers
            ]
        }
        
        print(json.dumps(summary, ensure_ascii=False, indent=2))
        
finally:
    conn.close()
