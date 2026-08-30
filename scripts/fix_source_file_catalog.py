#!/usr/bin/env python3
"""Validate or explicitly rebuild the Feishu source-file catalog.

The catalog is a file-backed registration for authoritative Feishu source roots. It never accepts teacher-assets,
managed uploads, or arbitrary database paths as source text. Validation is the default; ``--write`` is required for
any filesystem mutation.
"""

import argparse
import json
import os
from pathlib import Path, PurePosixPath
from typing import Any

import pymysql


PROJECT_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_STAGING_ROOT = PROJECT_ROOT / ".local-storage" / "teacher-source-imports"
DEFAULT_CATALOG_PATH = DEFAULT_STAGING_ROOT / ".source-file-catalog.json"
TEXT_SUFFIXES = {".md", ".markdown", ".txt"}


def configured_db() -> dict[str, Any]:
    return {
        "host": os.getenv("MATH_AGENT_DB_HOST", os.getenv("MYSQL_HOST", "127.0.0.1")),
        "port": int(os.getenv("MATH_AGENT_DB_PORT", os.getenv("MYSQL_PORT", "3307"))),
        "user": os.getenv("MATH_AGENT_DB_USER", os.getenv("MYSQL_USER", "root")),
        "password": os.getenv("MATH_AGENT_DB_PASSWORD", os.getenv("MYSQL_ROOT_PASSWORD", "")),
        "database": os.getenv("MATH_AGENT_DB_NAME", "math_agent_rag"),
        "charset": "utf8mb4",
    }


def configured_database_staging_root() -> str:
    return os.getenv("MATH_AGENT_DB_STAGING_ROOT", "/app/data/teacher-source-imports")


def map_database_root(local_staging_root: Path, database_staging_root: str, value: str) -> Path:
    database_root = PurePosixPath(database_staging_root.rstrip("/"))
    database_path = PurePosixPath(value)
    try:
        relative = database_path.relative_to(database_root)
    except ValueError as exception:
        raise ValueError(f"source root is outside configured database staging root: {value}") from exception
    return local_staging_root.joinpath(*relative.parts)


def source_root(staging_root: Path, value: str, database_staging_root: str) -> Path:
    candidate = map_database_root(staging_root, database_staging_root, value).expanduser().resolve(strict=True)
    staging = staging_root.resolve(strict=False)
    assets = (staging.parent / "teacher-assets").resolve(strict=False)
    if (
        candidate == staging
        or not candidate.is_relative_to(staging)
        or candidate == assets
        or candidate.is_relative_to(assets)
    ):
        raise ValueError(f"source root is outside teacher-source-imports: {value}")
    if not any(path.is_file() and path.suffix.lower() in TEXT_SUFFIXES for path in candidate.rglob("*")):
        raise ValueError(f"source root contains no Markdown/TXT file: {value}")
    return candidate


def read_rows() -> list[tuple[Any, ...]]:
    with pymysql.connect(**configured_db()) as connection:
        with connection.cursor() as cursor:
            cursor.execute("""
                SELECT id, tenant_id, local_path, checksum
                FROM source_document
                WHERE local_path IS NOT NULL AND local_path != ''
                ORDER BY id
            """)
            return list(cursor.fetchall())


def build_catalog(
        rows: list[tuple[Any, ...]], staging_root: Path, database_staging_root: str) -> dict[str, dict[str, str]]:
    catalog: dict[str, dict[str, str]] = {}
    for document_id, tenant_id, local_path, checksum in rows:
        source_root(staging_root, str(local_path), database_staging_root)
        database_path = PurePosixPath(str(local_path))
        key = f"{tenant_id}\u001f{document_id}"
        catalog[key] = {"root": str(database_path), "checksum": str(checksum or "")}
    return catalog


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--staging-root", type=Path, default=DEFAULT_STAGING_ROOT)
    parser.add_argument(
        "--database-staging-root",
        default=configured_database_staging_root(),
        help="container/deployment source root stored in source_document.local_path",
    )
    parser.add_argument("--catalog", type=Path, default=None)
    parser.add_argument("--write", action="store_true", help="replace the catalog after validation")
    args = parser.parse_args()
    staging_root = args.staging_root.resolve()
    catalog_path = (args.catalog or staging_root / ".source-file-catalog.json").resolve()
    catalog = build_catalog(read_rows(), staging_root, args.database_staging_root)
    print(json.dumps({"catalog": str(catalog_path), "entries": len(catalog), "write": args.write}, ensure_ascii=False))
    if args.write:
        catalog_path.parent.mkdir(parents=True, exist_ok=True)
        temporary = catalog_path.with_name(catalog_path.name + ".tmp")
        temporary.write_text(json.dumps(catalog, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        temporary.replace(catalog_path)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
