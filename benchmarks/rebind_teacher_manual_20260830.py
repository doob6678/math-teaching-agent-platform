"""Re-bind the human-annotated teacher manual cases onto the current live corpus snapshot.

The 20260830 verification run proved the previous 20260826 binding was stale: the
teacher library was re-synced and every physical FILE documentId/blockId changed,
so the old oracle matched nothing (doc@3=0).  This script re-pulls the live
snapshot the same way `build_current_teacher_split_oracle.py` does and re-binds
each case by the stable human anchor `expected_source_path + expected_block_order`,
keeping the human query (including the 20260830 terminology-dense rewrites) and
the annotation fields untouched.
"""

from __future__ import annotations

import hashlib
import json
import os
import sys
import time
from pathlib import Path
from typing import Any

if __package__ in {None, ""}:
    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from benchmarks.http_client import MathAgentClient

ROOT = Path(__file__).resolve().parents[1]
DATASET = ROOT / "benchmarks/datasets/teacher_math_manual_annotated_20260830.json"
SNAPSHOT_DIR = ROOT / "output/benchmarks/teacher-oracle-rebind-20260830"
DEFAULT_BACKEND_URL = "http://127.0.0.1:8080"


def _text(value: Any) -> str:
    return "" if value is None else str(value).strip()


def _normalized(value: Any) -> str:
    return " ".join(_text(value).replace("[Markdown image block; no extractable text]", "").split())


def _split_fingerprint(blocks: list[dict[str, Any]]) -> str:
    parts = []
    for block in blocks:
        body = _normalized(block.get("normalizedText") or block.get("rawText"))
        parts.append("|".join((
            _text(block.get("blockType")), _text(block.get("blockRole")),
            _text(block.get("pageNo")), _text(block.get("blockOrder")),
            str(len(body)), hashlib.sha256(body.encode("utf-8")).hexdigest(),
        )))
    return hashlib.sha256("\n".join(parts).encode("utf-8")).hexdigest()


def pull_snapshot(client: MathAgentClient) -> dict[str, Any]:
    resources_attempt = client.get("/api/teacher/resources")
    if resources_attempt.status != 200 or not isinstance(resources_attempt.body, list):
        raise RuntimeError(f"cannot list teacher resources: HTTP {resources_attempt.status}")
    resources: list[dict[str, Any]] = []
    blocks_by_document: dict[str, list[dict[str, Any]]] = {}
    for raw in resources_attempt.body:
        if not isinstance(raw, dict) or _text(raw.get("sourceType")).lower() != "feishu":
            continue
        root_id = _text(raw.get("documentId"))
        files_attempt = client.get(f"/api/teacher/resources/{root_id}/files", params={"limit": 512})
        if files_attempt.status != 200 or not isinstance(files_attempt.body, list):
            raise RuntimeError(f"cannot list files for root {root_id}: HTTP {files_attempt.status}")
        for raw_file in files_attempt.body:
            if not isinstance(raw_file, dict):
                continue
            file_id = _text(raw_file.get("documentId"))
            if not file_id:
                continue
            resource = dict(raw_file)
            resource["fileDocumentId"] = file_id
            resource["rootDocumentId"] = _text(raw_file.get("rootDocumentId") or root_id)
            resource["documentKind"] = "FILE"
            blocks_attempt = client.get(f"/api/teacher/resources/{file_id}/blocks", params={"limit": 512})
            blocks = blocks_attempt.body if blocks_attempt.status == 200 and isinstance(blocks_attempt.body, list) else []
            resource["blockCount"] = len(blocks)
            resource["splitFingerprint"] = _text(raw_file.get("splitFingerprint")) or _split_fingerprint(blocks)
            resources.append(resource)
            blocks_by_document[file_id] = [
                {key: block.get(key) for key in (
                    "blockId", "externalBlockId", "blockType", "blockOrder", "chapter", "section",
                    "pageNo", "printedPageNo", "sourcePath", "blockRole", "rawText", "normalizedText",
                    "imageRefs", "formulaRefs", "checksum", "status", "confidence",
                    "graphNodeIdsJson", "graphTagNamesJson")}
                for block in blocks if isinstance(block, dict)
            ]
            time.sleep(0.05)
    return {
        "generatedAt": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
        "source": {"backendUrl": client.base_url, "authenticatedAs": "live-api", "positiveOnly": True},
        "resources": resources,
        "blocks": blocks_by_document,
    }


def main() -> None:
    username = os.environ.get("MATH_AGENT_BENCHMARK_ADMIN_USERNAME", "local-browser-acceptance")
    password = os.environ.get("MATH_AGENT_BENCHMARK_ADMIN_PASSWORD", "")
    if not password:
        raise RuntimeError("set MATH_AGENT_BENCHMARK_ADMIN_PASSWORD")
    backend_url = os.environ.get("MATH_AGENT_BENCHMARK_BACKEND_URL", DEFAULT_BACKEND_URL)
    client = MathAgentClient(backend_url, timeout=120.0)
    client.login(username, password)

    SNAPSHOT_DIR.mkdir(parents=True, exist_ok=True)
    snapshot = pull_snapshot(client)
    (SNAPSHOT_DIR / "source_snapshot.json").write_text(
        json.dumps(snapshot, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    by_path = {_text(item.get("sourcePath")): item for item in snapshot["resources"]}
    blocks_by_doc = {doc_id: blocks for doc_id, blocks in snapshot["blocks"].items()}

    payload = json.loads(DATASET.read_text(encoding="utf-8-sig"))
    missing_paths: dict[str, str] = {}
    missing_orders: list[str] = []
    rebound = 0
    for case in payload["cases"]:
        path = _text(case.get("expected_source_path"))
        resource = by_path.get(path)
        if resource is None:
            missing_paths[case["case_id"]] = path
            continue
        file_id = _text(resource.get("documentId"))
        blocks = blocks_by_doc.get(file_id, [])
        order = int(case.get("expected_block_order") or case.get("block_order") or 0)
        candidates = [block for block in blocks if int(block.get("blockOrder") or 0) == order]
        if not candidates:
            missing_orders.append(f"{case['case_id']}:{path}#{order}")
            continue
        block = candidates[0]
        fingerprint = _text(resource.get("splitFingerprint")) or _split_fingerprint(blocks)
        case.update({
            "expected_document_id": file_id,
            "expected_root_document_id": _text(resource.get("rootDocumentId")),
            "expected_file_document_id": _text(resource.get("fileDocumentId") or file_id),
            "expected_provider_item_id": _text(resource.get("providerItemId")),
            "expected_block_id": _text(block.get("blockId")),
            "expected_block_order": order,
            "expected_library": "feishu",
            "requested_library": "feishu",
            "expected_role": _text(block.get("blockRole")),
            "expected_scope": _text(resource.get("permissionScope")),
            "split_fingerprint": fingerprint,
            "split_group": f"feishu:{file_id}:{fingerprint[:16]}",
            "document_title": _text(resource.get("title")),
            "source_excerpt": _normalized(block.get("normalizedText") or block.get("rawText"))[:600],
            "resolved_section": _text(block.get("section")),
            "resolved_block_type": _text(block.get("blockType")),
        })
        rebound += 1

    payload["binding"] = {
        "snapshot": str(SNAPSHOT_DIR.relative_to(ROOT) / "source_snapshot.json"),
        "status": "bound_to_current_snapshot_20260830" if rebound == len(payload["cases"]) else "partially_bound",
        "sourcePathAndBlockOrderOnlyForBinding": True,
        "reboundCaseCount": rebound,
        "missingSourcePathCount": len(missing_paths),
        "missingBlockOrderCount": len(missing_orders),
    }
    DATASET.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({
        "total": len(payload["cases"]), "rebound": rebound,
        "missingSourcePaths": missing_paths,
        "missingBlockOrders": missing_orders[:20],
    }, ensure_ascii=False, indent=1))


if __name__ == "__main__":
    main()
