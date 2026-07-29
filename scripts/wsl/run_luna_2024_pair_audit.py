"""Run one real Luna audit over the explicitly configured 2024 paper-pair metadata.

The source path is read only from config/gaokao-ingestion-2024.json.  Secrets remain in .env and are redacted from
the evidence; the emitted JSON deliberately retains the complete user-visible prompt, request shape, response,
HTTP status, usage and elapsed time so the report is reproducible without exposing credentials.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import time
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import urljoin

import requests

# The script is normally run from ``scripts/wsl`` but is deliberately copied to
# ``/tmp`` for the Docker-network experiment.  Resolving the project root only
# when the local config path is needed keeps the prepared-request mode portable.
SCRIPT_PATH = Path(__file__).resolve()
PROJECT_ROOT = SCRIPT_PATH.parents[2] if len(SCRIPT_PATH.parents) > 2 else None
CONFIG_PATH = PROJECT_ROOT / "config" / "gaokao-ingestion-2024.json" if PROJECT_ROOT else None
LUNA_MODEL = "gpt-5.6-luna"
REQUEST_TIMEOUT_SECONDS = 120


def load_dotenv(path: Path) -> dict[str, str]:
    """Reads only simple key/value entries so directory configuration never depends on process environment."""
    values: dict[str, str] = {}
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        if not raw_line or raw_line.lstrip().startswith("#") or "=" not in raw_line:
            continue
        key, value = raw_line.split("=", 1)
        values[key.strip()] = value.strip().strip('"').strip("'")
    return values


def sha256(path: Path) -> str:
    """Streams an input document so the report identity is derived from actual bytes."""
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while chunk := stream.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser(description="Run a real Luna 2024 paper-pair metadata audit")
    parser.add_argument("--config", type=Path, default=CONFIG_PATH)
    parser.add_argument("--prepare-only", action="store_true", help="write real file metadata and request JSON without network I/O")
    parser.add_argument("--request-file", type=Path, help="execute a previously prepared request inside the Docker backend")
    parser.add_argument("--evidence-root", type=Path, help="explicit evidence destination for Docker execution")
    args = parser.parse_args()
    if args.request_file:
        prepared = json.loads(args.request_file.read_text(encoding="utf-8"))
        config = prepared["config"]
        source_root = Path(prepared["sourceRootWsl"])
        selected = prepared["selectedFiles"]
        request_body = prepared["request"]
    else:
        if args.config is None:
            raise RuntimeError("--config is required when the script is not run from the project scripts/wsl directory")
        config = json.loads(args.config.read_text(encoding="utf-8"))
        source_root = Path(config["sourceRootWsl"])
        selected = []
        missing = []
        for name in config["selectedFileNames"]:
            path = source_root / name
            if not path.is_file():
                missing.append(name)
                continue
            selected.append({"fileName": name, "bytes": path.stat().st_size, "sha256": sha256(path)})
        if missing:
            raise RuntimeError(f"Configured real-paper files are missing: {missing}")
        prompt = {
            "task": "Audit metadata-only blank-paper/solution-paper pairing. Do not claim to read page content.",
            "paperType": config["paperType"], "files": selected,
            "requiredOutput": {"pairs": [{"blankFile": "string", "solutionFile": "string", "relationship": "SAME_QUESTION|UNDECIDABLE", "reason": "string"}], "scopeWarnings": ["string"]},
            "scope": config["excludedScopeExplanation"]
        }
        request_body = {"model": LUNA_MODEL, "temperature": 0, "response_format": {"type": "json_object"}, "messages": [{"role": "system", "content": "Return only valid JSON. Use only supplied metadata; never invent page content."}, {"role": "user", "content": json.dumps(prompt, ensure_ascii=False)}]}
    if args.evidence_root:
        evidence_root = args.evidence_root
    elif PROJECT_ROOT:
        evidence_root = PROJECT_ROOT / config["evidenceRoot"]
    else:
        raise RuntimeError("--evidence-root is required when executing a prepared request outside the project tree")
    evidence_root.mkdir(parents=True, exist_ok=True)
    prepared_path = evidence_root / "luna-2024-pair-request.json"
    prepared_path.write_text(json.dumps({"config": config, "sourceRootWsl": str(source_root), "selectedFiles": selected, "request": request_body}, ensure_ascii=False, indent=2), encoding="utf-8")
    if args.prepare_only:
        print(json.dumps({"preparedRequest": str(prepared_path), "selectedFileCount": len(selected), "selectedBytes": sum(item["bytes"] for item in selected)}, ensure_ascii=False))
        return
    dotenv_path = PROJECT_ROOT / ".env" if PROJECT_ROOT else None
    secrets = load_dotenv(dotenv_path) if dotenv_path and dotenv_path.is_file() else {}
    api_key = secrets.get("OPENAI_API_KEY") or os.environ.get("OPENAI_API_KEY", "")
    base_url = (secrets.get("OPENAI_BASE_URL") or os.environ.get("OPENAI_BASE_URL", "")).rstrip("/") + "/"
    if not api_key or not base_url:
        raise RuntimeError("OPENAI_API_KEY and OPENAI_BASE_URL must be configured in .env")
    started = time.perf_counter()
    response = requests.post(
        urljoin(base_url, "chat/completions"),
        headers={"Authorization": f"Bearer {api_key}", "Content-Type": "application/json"},
        json=request_body,
        timeout=REQUEST_TIMEOUT_SECONDS,
    )
    elapsed_ms = round((time.perf_counter() - started) * 1000)
    response_body: object
    try:
        response_body = response.json()
    except ValueError:
        response_body = {"nonJsonBody": response.text}
    evidence = {
        "timestampUtc": datetime.now(timezone.utc).isoformat(),
        "model": LUNA_MODEL,
        # Docker execution consumes an immutable prepared request rather than a
        # host config path; recording that distinction makes the evidence trace
        # truthful and avoids leaking a container-inapplicable local location.
        "sourceConfig": str(args.config.relative_to(PROJECT_ROOT)) if args.config and PROJECT_ROOT else "prepared-request",
        "sourceRootWsl": str(source_root),
        "selectedFileCount": len(selected),
        "selectedBytes": sum(item["bytes"] for item in selected),
        "request": request_body,
        "responseHttpStatus": response.status_code,
        "response": response_body,
        "elapsedMs": elapsed_ms,
        "timeoutSeconds": REQUEST_TIMEOUT_SECONDS,
        "credentialHandling": "Authorization value was used for transport and omitted from evidence."
    }
    output = evidence_root / "luna-2024-pair-audit.json"
    output.write_text(json.dumps(evidence, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({"evidence": str(output), "httpStatus": response.status_code, "elapsedMs": elapsed_ms, "selectedFileCount": len(selected)}, ensure_ascii=False))
    response.raise_for_status()


if __name__ == "__main__":
    main()
