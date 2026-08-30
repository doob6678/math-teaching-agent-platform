#!/usr/bin/env python3
import base64
import hashlib
import json
import pathlib
import time
import urllib.error
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[2]
EVIDENCE = ROOT / "output" / "acceptance" / "handout-mcp" / "feishu-image-refresh-20260825"
RUN_ID = "90d7ea10-263e-4830-be40-fda6ebc4905c"
ENDPOINT = "http://127.0.0.1:8080/internal/agent-tools/v1/read-resource-asset"


def load_worker_key():
    for raw in (ROOT / ".env").read_text(encoding="utf-8-sig").splitlines():
        line = raw.strip()
        if line.startswith("MATH_AGENT_AGENT_WORKER_SHARED_KEY="):
            return line.split("=", 1)[1].strip().strip("\"'")
    raise RuntimeError("worker key is unavailable in existing .env")


def image_signature(value):
    return (
        value.startswith(b"\x89PNG\r\n\x1a\n")
        or value.startswith(b"\xff\xd8\xff")
        or value.startswith((b"GIF87a", b"GIF89a"))
        or value.startswith(b"BM")
        or value.startswith((b"II*\x00", b"MM\x00*"))
        or value.startswith(b"RIFF") and value[8:12] == b"WEBP"
    )


def png_dimensions(value):
    if value.startswith(b"\x89PNG") and len(value) >= 24:
        return [int.from_bytes(value[16:20], "big"), int.from_bytes(value[20:24], "big")]
    return None


def call(asset_id, key):
    payload = json.dumps({"runId": RUN_ID, "assetId": asset_id}, ensure_ascii=False).encode("utf-8")
    request = urllib.request.Request(
        ENDPOINT,
        data=payload,
        method="POST",
        headers={
            "Content-Type": "application/json",
            "Accept": "application/json",
            "X-Agent-Worker-Key": key,
        },
    )
    started = time.monotonic()
    try:
        with urllib.request.urlopen(request, timeout=180) as response:
            response_body = json.loads(response.read().decode("utf-8"))
            status = response.status
    except urllib.error.HTTPError as error:
        return {
            "assetId": asset_id,
            "status": error.code,
            "error": error.read().decode("utf-8", "replace")[:3000],
        }
    asset = response_body.get("asset") if isinstance(response_body, dict) else None
    data_url = asset.get("dataUrl", "") if isinstance(asset, dict) else ""
    prefix, separator, encoded = data_url.partition(",")
    try:
        decoded = base64.b64decode(encoded, validate=True) if separator and prefix.startswith("data:") else b""
    except Exception:
        decoded = b""
    contains_forbidden_reference = any(
        marker in data_url for marker in ("/mnt/", "C:\\", "http://", "https://", "storageKey", "base64Content")
    )
    return {
        "assetId": asset_id,
        "status": status,
        "elapsedMs": round((time.monotonic() - started) * 1000),
        "mimeType": asset.get("mimeType", "") if isinstance(asset, dict) else "",
        "fileName": asset.get("fileName", "") if isinstance(asset, dict) else "",
        "dataUrlPresent": bool(data_url),
        "dataUrlPrefix": prefix[:120],
        "decodedBytes": len(decoded),
        "sha256": hashlib.sha256(decoded).hexdigest() if decoded else "",
        "signatureHex": decoded[:12].hex() if decoded else "",
        "supportedImageSignature": image_signature(decoded),
        "pngDimensions": png_dimensions(decoded),
        "containsPathOrUrl": contains_forbidden_reference,
    }


def main():
    source = json.loads((EVIDENCE / "broker-retrieval.json").read_text(encoding="utf-8"))
    assets = []
    items = ((source.get("search") or {}).get("response") or {}).get("items", [])
    for item in items:
        if not isinstance(item, dict):
            continue
        for asset_id in item.get("assetIds", []):
            if isinstance(asset_id, str) and asset_id and asset_id not in assets:
                assets.append(asset_id)
    if not assets:
        raise RuntimeError("successful broker response contains no assetIds")
    results = [call(asset_id, load_worker_key()) for asset_id in assets]
    report = {
        "runId": RUN_ID,
        "assetIds": assets,
        "results": results,
        "assetCount": len(assets),
        "successfulCount": sum(1 for result in results if result.get("status") == 200),
        "createdAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
    }
    (EVIDENCE / "asset-materialization.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    print(json.dumps(report, ensure_ascii=False))


if __name__ == "__main__":
    main()
