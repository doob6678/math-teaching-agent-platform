#!/usr/bin/env python3
import json
import os
import pathlib
import time
import urllib.error
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[2]
EVIDENCE = ROOT / "output" / "acceptance" / "handout-mcp" / "feishu-image-refresh-20260825"
EVIDENCE.mkdir(parents=True, exist_ok=True)
RUN_ID = "90d7ea10-263e-4830-be40-fda6ebc4905c"
QUERY = "解析几何 抛物线 焦点 准线 标准方程 教师资料"

def env_values():
    values = {}
    for raw in (ROOT / ".env").read_text(encoding="utf-8-sig").splitlines():
        line = raw.strip()
        if line and not line.startswith("#") and "=" in line:
            key, value = line.split("=", 1)
            values[key.strip()] = value.strip().strip("\"'")
    return values

def call(path, body, key):
    request = urllib.request.Request(
        "http://127.0.0.1:8080" + path,
        data=json.dumps(body, ensure_ascii=False).encode("utf-8"),
        method="POST",
        headers={"Content-Type": "application/json", "Accept": "application/json", "X-Agent-Worker-Key": key},
    )
    try:
        with urllib.request.urlopen(request, timeout=180) as response:
            return {"status": response.status, "response": json.loads(response.read().decode("utf-8"))}
    except urllib.error.HTTPError as error:
        text = error.read().decode("utf-8", "replace")
        return {"status": error.code, "error": text[:3000]}

def main():
    key = env_values()["MATH_AGENT_AGENT_WORKER_SHARED_KEY"]
    search = call(
        "/internal/agent-tools/v1/handout-teacher-resource-search",
        {"runId": RUN_ID, "query": QUERY, "limit": 6},
        key,
    )
    response = search.get("response") if isinstance(search.get("response"), dict) else {}
    items = response.get("items", []) if isinstance(response.get("items"), list) else []
    reads = []
    for item in items[:10]:
        document_ref = item.get("documentRef") if isinstance(item, dict) else ""
        if not isinstance(document_ref, str) or not document_ref:
            continue
        reads.append(call(
            "/internal/agent-tools/v1/handout-document-read",
            {"runId": RUN_ID, "documentRef": document_ref, "maxBlocks": 8, "maxChars": 8000},
            key,
        ))
    result = {
        "runId": RUN_ID,
        "query": QUERY,
        "search": search,
        "documentReads": reads,
        "searchHitCount": len(items),
        "imageHitCount": sum(1 for item in items if isinstance(item, dict) and item.get("assetIds")),
        "readBlockCount": sum(len((item.get("response") or {}).get("blocks", [])) for item in reads if isinstance(item, dict)),
        "createdAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
    }
    (EVIDENCE / "broker-retrieval.json").write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({
        "searchStatus": search.get("status"),
        "searchHitCount": result["searchHitCount"],
        "imageHitCount": result["imageHitCount"],
        "readBlockCount": result["readBlockCount"],
        "readStatuses": [item.get("status") for item in reads],
    }, ensure_ascii=False))

if __name__ == "__main__":
    main()
