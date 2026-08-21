#!/usr/bin/env python3
"""直接测试 Markdown → LaTeX 转换，绕过复杂的 Java 处理。"""
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

BASE_URL = "http://127.0.0.1:8080"
WORKFLOW_ID = "fe814d79-7407-43a5-a9e3-3504fbdfe6a7"

timeline = []
http = Http(BASE_URL, 30, timeline)
username, password = configured_credentials()

http.request("POST", "/api/auth/login", {"username": username, "password": password})
key, _ = http.request("POST", "/api/mcp/keys", {})
key_id, secret = str(key["keyId"]), str(key["secretKey"])

try:
    # 读取 checkpoint 中的 writer 文档
    checkpoint_dir = ROOT / "output" / "acceptance" / "handout-mcp" / "recovered-fe814d79" / "checkpoint-writers"
    
    teacher_md = (checkpoint_dir / "teacher_writer.md").read_text(encoding="utf-8")
    
    print("=== Teacher Markdown (first 500 chars) ===")
    print(teacher_md[:500])
    print()
    
    # 简单测试：将 Markdown 的前几行手动转为 LaTeX
    lines = teacher_md.split("\n")
    
    print("=== First 10 lines ===")
    for i, line in enumerate(lines[:10], 1):
        print(f"{i}: {line}")
    
    print("\n=== Issue Detection ===")
    print(f"Total lines: {len(lines)}")
    print(f"Lines with $: {sum(1 for line in lines if '$' in line)}")
    print(f"Lines with $$: {sum(1 for line in lines if '$$' in line)}")
    print(f"Lines with \\[: {sum(1 for line in lines if '\\[' in line)}")
    print(f"Lines with \\]: {sum(1 for line in lines if '\\]' in line)}")
    
finally:
    http.request("POST", f"/api/mcp/keys/{key_id}/revoke", {})
