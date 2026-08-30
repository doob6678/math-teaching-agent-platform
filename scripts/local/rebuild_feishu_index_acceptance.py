#!/usr/bin/env python3
import json
import pathlib
import time
import urllib.error
import urllib.request
import http.cookiejar

ROOT = pathlib.Path(__file__).resolve().parents[2]
EVIDENCE = ROOT / "output" / "acceptance" / "feishu-index-rebuild-20260825"
EVIDENCE.mkdir(parents=True, exist_ok=True)
DOCUMENT_ID = "2091761180343918593"
FAILED_JOB_ID = "0a35f994-3a23-4fef-8d9f-56eff5484920"
BASE_URL = "http://127.0.0.1:8080"

def load_env():
    result = {}
    for raw in (ROOT / ".env").read_text(encoding="utf-8-sig").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        value = value.strip()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
            value = value[1:-1]
        result[key.strip()] = value
    return result

def main():
    values = load_env()
    username = values["MATH_AGENT_LOCAL_ACCEPTANCE_ACCOUNT_USERNAME"]
    password = values["MATH_AGENT_LOCAL_ACCEPTANCE_ACCOUNT_PASSWORD"]
    jar = http.cookiejar.CookieJar()
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))
    record = {
        "startedAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "documentId": DOCUMENT_ID,
        "reusedFailedJobId": FAILED_JOB_ID,
        "requests": [],
    }

    def call(method, path, body=None):
        payload = None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8")
        request = urllib.request.Request(
            BASE_URL + path,
            data=payload,
            method=method,
            headers={"Content-Type": "application/json", "Accept": "application/json"},
        )
        started = time.monotonic()
        try:
            with opener.open(request, timeout=900) as response:
                text = response.read().decode("utf-8", "replace")
                item = {
                    "method": method,
                    "path": path,
                    "status": response.status,
                    "elapsedMs": int((time.monotonic() - started) * 1000),
                }
                try:
                    item["response"] = json.loads(text)
                except json.JSONDecodeError:
                    item["responseText"] = text[:2000]
                record["requests"].append(item)
                return item
        except urllib.error.HTTPError as error:
            text = error.read().decode("utf-8", "replace")
            item = {
                "method": method,
                "path": path,
                "status": error.code,
                "elapsedMs": int((time.monotonic() - started) * 1000),
                "error": text[:2000],
            }
            record["requests"].append(item)
            raise

    call("POST", "/api/auth/login", {"username": username, "password": password})
    resources = call("GET", "/api/teacher/resources")
    visible = [item for item in resources.get("response", []) if str(item.get("documentId")) == DOCUMENT_ID]
    if len(visible) != 1:
        raise RuntimeError("target Feishu resource is not uniquely visible after login")
    target = visible[0].get("response", visible[0]) if isinstance(visible[0], dict) else {}
    record["targetResource"] = {
        "visible": True,
        "sourceType": target.get("sourceType"),
        "syncStatus": target.get("syncStatus"),
        "parseStatus": target.get("parseStatus"),
        "embeddingStatus": target.get("embeddingStatus"),
        "indexStatus": target.get("indexStatus"),
    }
    rebuild = call("POST", "/api/vector-index/teacher-resources/" + DOCUMENT_ID + "/rebuild")
    response = rebuild.get("response")
    record["rebuildResponse"] = response
    record["finishedAt"] = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
    (EVIDENCE / "http.json").write_text(json.dumps(record, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({
        "httpStatus": rebuild["status"],
        "elapsedMs": rebuild["elapsedMs"],
        "status": response.get("status") if isinstance(response, dict) else None,
        "embeddedCount": response.get("embeddedCount") if isinstance(response, dict) else None,
        "upsertedCount": response.get("upsertedCount") if isinstance(response, dict) else None,
    }, ensure_ascii=False))

if __name__ == "__main__":
    main()
