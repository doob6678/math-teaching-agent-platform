#!/usr/bin/env python3
"""Run an authenticated Feishu synchronization that backfills missing image bindings."""
import http.cookiejar
import json
import pathlib
import time
import urllib.error
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[2]
EVIDENCE = ROOT / "output" / "acceptance" / "feishu-image-binding-backfill-20260829"
EVIDENCE.mkdir(parents=True, exist_ok=True)
DOCUMENT_ID = "2091761180343918593"
BASE_URL = "http://127.0.0.1:8080"


def load_env() -> dict[str, str]:
    values: dict[str, str] = {}
    for raw in (ROOT / ".env").read_text(encoding="utf-8-sig").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        value = value.strip()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
            value = value[1:-1]
        values[key.strip()] = value
    return values


def main() -> None:
    values = load_env()
    username = values["MATH_AGENT_LOCAL_ACCEPTANCE_ACCOUNT_USERNAME"]
    password = values["MATH_AGENT_LOCAL_ACCEPTANCE_ACCOUNT_PASSWORD"]
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
    record: dict[str, object] = {"documentId": DOCUMENT_ID, "requests": []}

    def call(method: str, path: str, body: object | None = None) -> object:
        payload = None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8")
        request = urllib.request.Request(
            BASE_URL + path,
            data=payload,
            method=method,
            headers={"Content-Type": "application/json", "Accept": "application/json"},
        )
        started = time.monotonic()
        try:
            with opener.open(request, timeout=120) as response:
                response_body = json.loads(response.read().decode("utf-8"))
                record["requests"].append({
                    "method": method,
                    "path": path,
                    "status": response.status,
                    "elapsedMs": int((time.monotonic() - started) * 1000),
                    "response": response_body,
                })
                return response_body
        except urllib.error.HTTPError as error:
            text = error.read().decode("utf-8", "replace")
            record["requests"].append({"method": method, "path": path, "status": error.code, "error": text})
            raise

    call("POST", "/api/auth/login", {"username": username, "password": password})
    job = call("POST", f"/api/teacher/resources/{DOCUMENT_ID}/sync-jobs")
    job_id = job["jobId"]
    call("POST", f"/api/teacher/resources/{DOCUMENT_ID}/sync-jobs/{job_id}/execute")
    deadline = time.monotonic() + 2400
    while time.monotonic() < deadline:
        jobs = call("GET", f"/api/teacher/resources/{DOCUMENT_ID}/sync-jobs")
        current = next((item for item in jobs if item.get("jobId") == job_id), None)
        if current is None:
            raise RuntimeError("submitted sync job disappeared")
        print(json.dumps({"jobId": job_id, "status": current["status"], "phase": current["phase"]}, ensure_ascii=False), flush=True)
        if current["status"] == "completed":
            record["syncJob"] = current
            break
        if current["status"] in {"failed", "paused"}:
            raise RuntimeError(json.dumps(current, ensure_ascii=False))
        time.sleep(10)
    else:
        raise TimeoutError("image binding backfill exceeded 40 minutes")
    record["finishedAt"] = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
    (EVIDENCE / "http.json").write_text(json.dumps(record, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(record["syncJob"], ensure_ascii=False))


if __name__ == "__main__":
    main()
