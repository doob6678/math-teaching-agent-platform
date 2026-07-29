"""Send one real, hash-identified source-page PNG to Luna and retain the full non-secret interaction."""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import mimetypes
import os
import time
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import urljoin

import requests

LUNA_MODEL = "gpt-5.6-luna"
REQUEST_TIMEOUT_SECONDS = 120


def sha256(path: Path) -> str:
    """Hashes evidence bytes so a report identifies the exact rendered page Luna inspected."""
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser(description="Run one real Luna visual audit over a rendered exam page")
    parser.add_argument("--image", type=Path, required=True)
    parser.add_argument("--evidence-root", type=Path, required=True)
    parser.add_argument("--paper", required=True)
    parser.add_argument("--page", type=int, required=True)
    parser.add_argument("--evidence-name", default="luna-2024-visual-page-audit.json", help="evidence filename under --evidence-root")
    arguments = parser.parse_args()
    image = arguments.image
    if not image.is_file():
        raise FileNotFoundError(image)
    image_bytes = image.read_bytes()
    image_mime = mimetypes.guess_type(image.name)[0] or "application/octet-stream"
    if image_mime not in {"image/jpeg", "image/png", "image/webp"}:
        raise ValueError(f"unsupported visual evidence MIME type: {image_mime}")
    prompt = {
        "task": "Audit this rendered source page for question-boundary evidence only.",
        "paper": arguments.paper,
        "page": arguments.page,
        "requiredOutput": {
            "visibleTopLevelQuestionNumbers": ["string"],
            "layout": "single-column|multi-column|uncertain",
            "boundaryRisks": ["string"],
            "publicationRecommendation": "PENDING_VISUAL_REVIEW|SAFE_FOR_REVIEW_QUEUE"
        },
        "constraints": [
            "Inspect only the supplied image.",
            "Do not infer answers, solutions, hidden pages, or official correctness.",
            "Do not approve publication; human review remains mandatory."
        ]
    }
    request = {
        "model": LUNA_MODEL,
        "temperature": 0,
        "response_format": {"type": "json_object"},
        "messages": [
            {"role": "system", "content": "Return only valid JSON. State uncertainty explicitly."},
            {"role": "user", "content": [
                {"type": "text", "text": json.dumps(prompt, ensure_ascii=False)},
                {"type": "image_url", "image_url": {"url": "data:" + image_mime + ";base64," + base64.b64encode(image_bytes).decode("ascii")}}
            ]}
        ]
    }
    api_key = os.environ.get("OPENAI_API_KEY", "")
    base_url = os.environ.get("OPENAI_BASE_URL", "").rstrip("/") + "/"
    if not api_key or not base_url:
        raise RuntimeError("Docker must provide OPENAI_API_KEY and OPENAI_BASE_URL")
    started = time.perf_counter()
    response = requests.post(urljoin(base_url, "chat/completions"), headers={"Authorization": f"Bearer {api_key}", "Content-Type": "application/json"}, json=request, timeout=REQUEST_TIMEOUT_SECONDS)
    elapsed_ms = round((time.perf_counter() - started) * 1000)
    try:
        response_body: object = response.json()
    except ValueError:
        response_body = {"nonJsonBody": response.text}
    arguments.evidence_root.mkdir(parents=True, exist_ok=True)
    output = arguments.evidence_root / arguments.evidence_name
    output.write_text(json.dumps({"timestampUtc": datetime.now(timezone.utc).isoformat(), "model": LUNA_MODEL, "paper": arguments.paper, "page": arguments.page, "image": {"fileName": image.name, "mimeType": image_mime, "bytes": len(image_bytes), "sha256": sha256(image)}, "request": request, "responseHttpStatus": response.status_code, "response": response_body, "elapsedMs": elapsed_ms, "timeoutSeconds": REQUEST_TIMEOUT_SECONDS, "credentialHandling": "Authorization value was used for transport and omitted from evidence."}, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({"evidence": str(output), "httpStatus": response.status_code, "elapsedMs": elapsed_ms}, ensure_ascii=False))
    response.raise_for_status()


if __name__ == "__main__":
    main()
