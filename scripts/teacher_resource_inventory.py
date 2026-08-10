"""Read-only inventory for the four teacher-resource storage layers.

The script deliberately reads MySQL and Milvus directly instead of reusing an HTTP search endpoint. That keeps the
report independent from retrieval post-filters and makes missing file identity, duplicate blocks, and tenant drift
visible. Credentials come from the process environment; a local .env is only used when the variable is not exported.
"""

from __future__ import annotations

import argparse
import base64
import datetime as dt
import json
import os
import subprocess
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any

import pymilvus
try:
    import pymysql
except ImportError:  # WSL inventory uses the container's mysql CLI instead.
    pymysql = None


CANONICAL_TYPES = {"feishu", "teacher_resource", "gaokao", "mock_exam"}
COLLECTION_ENV = "MATH_AGENT_MILVUS_COLLECTION_NAME"


def load_local_env(workspace: Path) -> None:
    """Load only missing settings, preserving the operator's exported environment as the source of truth."""
    env_path = workspace / ".env"
    if not env_path.is_file():
        return
    for raw_line in env_path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        name, value = line.split("=", 1)
        os.environ.setdefault(name.strip(), value.strip().strip('"'))


def required_env(name: str) -> str:
    value = os.environ.get(name, "").strip()
    if not value:
        raise RuntimeError(f"configuration_error component=environment variable={name} message=missing")
    return value


def mysql_rows(connection: Any, sql: str) -> list[dict[str, Any]]:
    """Execute one read-only query and return dictionaries; query failures retain the SQL stage in the error."""
    try:
        with connection.cursor() as cursor:
            cursor.execute(sql)
            columns = [column[0] for column in cursor.description]
            return [dict(zip(columns, row)) for row in cursor.fetchall()]
    except Exception as exc:  # pragma: no cover - exercised against real infrastructure
        raise RuntimeError(f"query_error component=mysql stage={sql.splitlines()[0][:80]} message={exc}") from exc


def mysql_cli_rows(sql: str, columns: list[str]) -> list[dict[str, Any]]:
    """Read through the running MySQL container when WSL does not expose its published Windows port."""
    encoded = base64.b64encode(sql.encode("utf-8")).decode("ascii")
    command = (
        f"printf %s {encoded} | base64 -d | mysql -uroot -p\"$MYSQL_ROOT_PASSWORD\" "
        "--default-character-set=utf8mb4 --batch --raw --skip-column-names math_agent_rag"
    )
    executable = ["docker", "exec", "-i", "math-agent-rag-mysql-1", "sh", "-lc", command]
    try:
        result = subprocess.run(executable, capture_output=True, text=True, encoding="utf-8", check=True)
    except Exception as exc:  # pragma: no cover - exercised against real infrastructure
        raise RuntimeError(f"query_error component=mysql stage=container_cli message={exc}") from exc
    rows = []
    for line in result.stdout.splitlines():
        values = line.split("\t")
        rows.append(dict(zip(columns, values)))
    return rows


def normalize(value: Any) -> str:
    return str(value or "").strip()


def category(source_type: Any) -> str:
    value = normalize(source_type).lower()
    return value if value in CANONICAL_TYPES else ("legacy_inferred" if value in {"", "local_path"} else value)


def image_count(raw: Any) -> int:
    if raw in (None, "", "null"):
        return 0
    try:
        parsed = json.loads(raw) if isinstance(raw, str) else raw
        return len(parsed) if isinstance(parsed, list) else 0
    except (TypeError, ValueError):
        return 0


def inventory(workspace: Path) -> dict[str, Any]:
    """Build the complete report in memory; no INSERT, UPDATE, DELETE, or Milvus mutation is issued."""
    load_local_env(workspace)
    mysql_password = required_env("MYSQL_ROOT_PASSWORD")
    mysql_host = os.environ.get("MATH_AGENT_INVENTORY_MYSQL_HOST", "127.0.0.1")
    mysql_port = int(os.environ.get("MATH_AGENT_INVENTORY_MYSQL_PORT", "3307"))
    connection = None
    try:
        if pymysql is None:
            raise RuntimeError("pymysql unavailable")
        connection = pymysql.connect(
            host=mysql_host,
            port=mysql_port,
            user=os.environ.get("MATH_AGENT_INVENTORY_MYSQL_USER", "root"),
            password=mysql_password,
            database=os.environ.get("MATH_AGENT_INVENTORY_MYSQL_DATABASE", "math_agent_rag"),
            charset="utf8mb4",
            read_timeout=30,
            write_timeout=30,
        )
    except Exception:
        # Docker is authoritative in this workspace and may publish only inside WSL's NAT namespace.
        connection = None

    try:
        read = mysql_rows if connection is not None else lambda sql, columns: mysql_cli_rows(sql, columns)
        roots = read("""
            SELECT sync_root_id, tenant_id, root_url, source_type, permission_scope, authorization_status
            FROM teacher_source_sync_root ORDER BY tenant_id, sync_root_id
        """, ["sync_root_id", "tenant_id", "root_url", "source_type", "permission_scope", "authorization_status"])
        manifests = read("""
            SELECT sync_root_id, tenant_id, provider_item_id, parent_provider_item_id, logical_path, item_type,
                   document_id, archive_status, sync_status, indexed_at
            FROM teacher_source_sync_manifest ORDER BY tenant_id, sync_root_id, logical_path
        """, ["sync_root_id", "tenant_id", "provider_item_id", "parent_provider_item_id", "logical_path", "item_type", "document_id", "archive_status", "sync_status", "indexed_at"])
        documents = read("""
            SELECT id AS document_id, tenant_id, source_type, title, permission_scope, sync_status, parse_status,
                   embedding_status, metadata_json
            FROM source_document WHERE sync_status <> 'archived' ORDER BY tenant_id, id
        """, ["document_id", "tenant_id", "source_type", "title", "permission_scope", "sync_status", "parse_status", "embedding_status", "metadata_json"])
        blocks = read("""
            SELECT db.source_document_id AS document_id, db.id AS block_id, db.source_path, db.page_no,
                   db.block_order, db.checksum, db.image_refs, db.status
            FROM document_block db JOIN source_document sd ON sd.id=db.source_document_id
            WHERE sd.sync_status <> 'archived' ORDER BY sd.tenant_id, db.source_document_id, db.source_path, db.block_order
        """, ["document_id", "block_id", "source_path", "page_no", "block_order", "checksum", "image_refs", "status"])
    finally:
        if connection is not None:
            connection.close()

    document_by_id = {str(row["document_id"]): row for row in documents}
    manifests_by_document_path: dict[tuple[str, str], list[dict[str, Any]]] = defaultdict(list)
    for manifest in manifests:
        if normalize(manifest["document_id"]):
            manifests_by_document_path[(normalize(manifest["document_id"]), normalize(manifest["logical_path"]).replace("\\", "/"))].append(manifest)

    block_groups: dict[tuple[str, str], list[dict[str, Any]]] = defaultdict(list)
    missing_source_path: list[dict[str, Any]] = []
    for block in blocks:
        document_id = str(block["document_id"])
        path = normalize(block["source_path"]).replace("\\", "/")
        if normalize(block["status"]).lower() != "active":
            continue
        if not path:
            missing_source_path.append({"documentId": document_id, "blockId": str(block["block_id"])})
        block_groups[(document_id, path)].append(block)

    duplicate_by_position = []
    duplicate_by_checksum = []
    for (document_id, path), file_blocks in block_groups.items():
        positions = Counter(normalize(block["block_order"]) for block in file_blocks if normalize(block["block_order"]))
        checksums = Counter(normalize(block["checksum"]) for block in file_blocks if normalize(block["checksum"]))
        repeated_positions = sorted(value for value, count in positions.items() if count > 1)
        repeated_checksums = sorted(value for value, count in checksums.items() if count > 1)
        if repeated_positions:
            duplicate_by_position.append({
                "documentId": document_id,
                "sourcePath": path,
                "duplicateBlockOrders": repeated_positions,
            })
        if repeated_checksums:
            duplicate_by_checksum.append({
                "documentId": document_id,
                "sourcePath": path,
                "duplicateChecksums": repeated_checksums,
            })

    # A path repeated under multiple source documents is not necessarily corruption, but it is a duplicate-ingestion
    # candidate and must be visible separately from normal multi-block files and repeated block checksums.
    document_ids_by_tenant_path: dict[tuple[str, str], set[str]] = defaultdict(set)
    for (document_id, path) in block_groups:
        document = document_by_id.get(document_id, {})
        document_ids_by_tenant_path[(normalize(document.get("tenant_id")), path)].add(document_id)
    same_file_multiple_documents = [
        {"tenantId": tenant_id, "sourcePath": path, "documentIds": sorted(document_ids)}
        for (tenant_id, path), document_ids in sorted(document_ids_by_tenant_path.items())
        if path and len(document_ids) > 1
    ]

    root_by_id = {normalize(root["sync_root_id"]): root for root in roots}
    files = []
    for (document_id, path), file_blocks in sorted(block_groups.items()):
        document = document_by_id.get(document_id, {})
        manifest_rows = manifests_by_document_path.get((document_id, path), [])
        files.append({
            "fileName": Path(path).name if path else "",
            "sourcePath": path,
            "documentId": document_id,
            "tenantId": normalize(document.get("tenant_id")),
            "sourceType": category(document.get("source_type")),
            "providerItemIds": sorted({normalize(row["provider_item_id"]) for row in manifest_rows if normalize(row["provider_item_id"])}),
            "textBlockCount": len(file_blocks),
            "imageCount": sum(image_count(block["image_refs"]) for block in file_blocks),
            "embeddingStatus": normalize(document.get("embedding_status")),
            "indexStatus": normalize(document.get("metadata_json")),
            "indexedManifestCount": sum(1 for row in manifest_rows if normalize(row["indexed_at"])),
        })

    tenant_document_pairs = Counter((normalize(row["tenant_id"]), str(row["document_id"])) for row in documents)
    cross_tenant_document_ids = [document_id for (_, document_id), count in tenant_document_pairs.items() if count > 1]
    root_summaries = []
    for root in roots:
        root_id = normalize(root["sync_root_id"])
        root_manifests = [row for row in manifests if normalize(row["sync_root_id"]) == root_id and normalize(row["item_type"]).lower() == "file"]
        document_ids = sorted({normalize(row["document_id"]) for row in root_manifests if normalize(row["document_id"])})
        root_summaries.append({
            "syncRootId": root_id,
            "tenantId": normalize(root["tenant_id"]),
            "rootUrl": normalize(root["root_url"]),
            "sourceType": category(root["source_type"]),
            "documentIds": document_ids,
            "documentIdCount": len(document_ids),
            "actualFileCount": len(root_manifests),
            "files": [file for file in files if file["tenantId"] == normalize(root["tenant_id"]) and file["documentId"] in document_ids],
        })

    collection_name = os.environ.get(COLLECTION_ENV, "math_agent_teacher_text_blocks_bge")
    milvus_rows: list[dict[str, Any]] = []
    try:
        uri = os.environ.get("MATH_AGENT_INVENTORY_MILVUS_URI", "")
        if not uri and os.name != "nt":
            # WSL may see a stale published localhost proxy while the live compose service is reachable by its
            # container IP. Resolve that IP from Docker at runtime; no address is hard-coded or changed.
            milvus_ip = subprocess.check_output(
                ["docker", "inspect", "-f", "{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}", "math-agent-rag-milvus-1"],
                text=True,
            ).strip()
            uri = f"http://{milvus_ip}:19530"
        uri = uri or "http://127.0.0.1:19531"
        token = os.environ.get("MATH_AGENT_MILVUS_TOKEN", "")
        if not token:
            token = "root:" + required_env("MATH_AGENT_MILVUS_ROOT_PASSWORD")
        pymilvus.connections.connect(alias="teacher_inventory", uri=uri, token=token)
        collection = pymilvus.Collection(collection_name, using="teacher_inventory")
        iterator = collection.query_iterator(batch_size=1000, expr="id != ''", output_fields=["id", "metadata"], using="teacher_inventory")
        while True:
            batch = iterator.next()
            if not batch:
                break
            milvus_rows.extend(batch)
        iterator.close()
    except Exception as exc:  # pragma: no cover - exercised against real infrastructure
        raise RuntimeError(f"query_error component=milvus collection={collection_name} stage=metadata_query message={exc}") from exc
    finally:
        try:
            pymilvus.connections.disconnect("teacher_inventory")
        except Exception:
            pass

    vector_tenants = Counter()
    vector_tenant_mismatches = []
    vector_missing_source_path = 0
    vector_duplicate_ids = [item for item, count in Counter(normalize(row.get("id")) for row in milvus_rows).items() if item and count > 1]
    for row in milvus_rows:
        metadata = row.get("metadata") or {}
        tenant_id = normalize(metadata.get("tenantId"))
        document_id = normalize(metadata.get("documentId"))
        vector_tenants[tenant_id] += 1
        if not normalize(metadata.get("sourcePath")) and not normalize(metadata.get("providerItemId")):
            vector_missing_source_path += 1
        document = document_by_id.get(document_id)
        if document and normalize(document.get("tenant_id")) != tenant_id:
            vector_tenant_mismatches.append({"vectorId": normalize(row.get("id")), "metadataTenantId": tenant_id, "documentTenantId": normalize(document.get("tenant_id")), "documentId": document_id})

    return {
        "generatedAt": dt.datetime.now(dt.timezone.utc).isoformat(),
        "collection": {"name": collection_name, "rowCount": len(milvus_rows)},
        "tenants": sorted({normalize(row["tenant_id"]) for row in documents}),
        "roots": root_summaries,
        "files": files,
        "quality": {
            "missingSourcePathBlocks": missing_source_path,
            "duplicateBlockPositions": duplicate_by_position,
            "duplicateBlockChecksums": duplicate_by_checksum,
            "sameFileMultipleDocuments": same_file_multiple_documents,
            "crossTenantDocumentIds": sorted(cross_tenant_document_ids),
            "milvusTenantRowCounts": dict(vector_tenants),
            "milvusRowsMissingFileIdentity": vector_missing_source_path,
            "milvusTenantMismatches": vector_tenant_mismatches,
            "milvusDuplicateIds": vector_duplicate_ids,
        },
    }


def markdown(report: dict[str, Any]) -> str:
    lines = ["# Teacher Resource Inventory", "", f"Generated: `{report['generatedAt']}`", f"Milvus collection: `{report['collection']['name']}` ({report['collection']['rowCount']} rows)", ""]
    for root in report["roots"]:
        lines.extend([f"## Tenant `{root['tenantId']}` / Feishu root `{root['syncRootId']}`", f"URL: `{root['rootUrl']}`", f"sourceType: `{root['sourceType']}`; documentIds: **{root['documentIdCount']}**; actual files: **{root['actualFileCount']}**", ""])
        for file in root["files"]:
            lines.append(f"- `{file['fileName']}` | sourcePath=`{file['sourcePath']}` | documentId=`{file['documentId']}` | blocks={file['textBlockCount']} | images={file['imageCount']} | embedding={file['embeddingStatus']}")
        lines.append("")
    rooted_document_ids = {file["documentId"] for root in report["roots"] for file in root["files"]}
    unattached_files = [file for file in report["files"] if file["documentId"] not in rooted_document_ids]
    if unattached_files:
        lines.extend(["## Documents without a registered Feishu root", "", "These records remain visible in the inventory but are not assigned to a `teacher_source_sync_root`; this is a data-quality finding, not a synthetic root.", ""])
        for file in unattached_files:
            lines.append(f"- `{file['fileName']}` | sourcePath=`{file['sourcePath']}` | documentId=`{file['documentId']}` | tenant=`{file['tenantId']}` | sourceType=`{file['sourceType']}` | blocks={file['textBlockCount']}")
        lines.append("")
    quality = report["quality"]
    lines.extend([
        "## Data quality",
        f"- Missing sourcePath blocks: **{len(quality['missingSourcePathBlocks'])}**",
        f"- Duplicate block positions within a file: **{len(quality['duplicateBlockPositions'])}**",
        f"- Duplicate block checksums within a file: **{len(quality['duplicateBlockChecksums'])}**",
        f"- Same file path under multiple documents: **{len(quality['sameFileMultipleDocuments'])}**",
        f"- Cross-tenant document IDs: **{len(quality['crossTenantDocumentIds'])}**",
        f"- Milvus rows missing file identity: **{quality['milvusRowsMissingFileIdentity']}**",
        f"- Milvus tenant mismatches: **{len(quality['milvusTenantMismatches'])}**",
        f"- Duplicate Milvus IDs: **{len(quality['milvusDuplicateIds'])}**",
        "",
    ])
    return "\n".join(lines)


def main() -> None:
    parser = argparse.ArgumentParser(description="Read-only teacher resource and Milvus inventory")
    parser.add_argument("--workspace", default=str(Path(__file__).resolve().parents[1]))
    parser.add_argument("--date", default=dt.date.today().isoformat())
    args = parser.parse_args()
    if os.name == "nt" and os.environ.get("MATH_AGENT_INVENTORY_IN_WSL") != "1":
        # Milvus and MySQL are WSL services in this workspace. Re-run the same real script inside WSL so published
        # service names/ports are reachable without changing Windows networking or DNS configuration.
        command = ["wsl.exe", "-e", "env", "MATH_AGENT_INVENTORY_IN_WSL=1", "python3", "/mnt/c/Users/doob/Desktop/code/dev/math_agent_rag/scripts/teacher_resource_inventory.py", "--workspace", "/mnt/c/Users/doob/Desktop/code/dev/math_agent_rag", "--date", args.date]
        raise SystemExit(subprocess.run(command, check=False).returncode)
    report = inventory(Path(args.workspace).resolve())
    output_dir = Path(args.workspace).resolve() / "output" / "benchmarks"
    output_dir.mkdir(parents=True, exist_ok=True)
    json_path = output_dir / f"teacher-resource-inventory-{args.date}.json"
    markdown_path = output_dir / f"teacher-resource-inventory-{args.date}.md"
    json_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    markdown_path.write_text(markdown(report), encoding="utf-8")
    print(json.dumps({"json": str(json_path), "markdown": str(markdown_path), "milvusRows": report["collection"]["rowCount"]}, ensure_ascii=False))


if __name__ == "__main__":
    main()
