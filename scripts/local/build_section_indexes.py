"""Build the searchable indexes for a completed all-book section library.

The section extractor writes the corpus first and this script is intentionally a
separate step: an interrupted model build can resume without leaving a partially
indexed corpus that looks complete to the evaluator.  BM25 keeps the external
parser's normal tokenization, while BGE uses the same text contract and local
model as the immutable page library.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import subprocess
import sys
from pathlib import Path
from typing import Any

import numpy as np


DEFAULT_PARENT = Path(
    r"C:\Users\doob\Desktop\个人资料\高中数学\下载课本代码\tchMaterial-parser-main\tchMaterial-parser-main"
)
DEFAULT_MODEL = Path(r"D:\ModelScope\models\BAAI\bge-small-zh-v1.5")
DEFAULT_BATCH_SIZE = 16
MAX_EMBED_TEXT = 1600
WORKER_PYTHON_ENVIRONMENT_VARIABLE = "MATH_AGENT_WORKER_PYTHON"
RUNTIME_REEXECUTION_MARKER = "MATH_AGENT_SECTION_INDEX_RUNTIME"


def read_json(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def read_jsonl(path: Path) -> list[dict[str, Any]]:
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def write_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def write_jsonl(path: Path, rows: list[dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("\n".join(json.dumps(row, ensure_ascii=False, separators=(",", ":")) for row in rows) + "\n", encoding="utf-8")


def ensure_embedding_runtime() -> None:
    """Re-execute under the configured worker Python before loading BGE.

    The desktop shell may point at a CPU-only Conda interpreter while the local
    worker has the CUDA-enabled Torch installation selected by environment.
    Reading that explicit runtime setting keeps every rebuilt index compatible
    with the real serving model and avoids silently falling back to a different
    embedding stack.
    """
    configured = os.environ.get(WORKER_PYTHON_ENVIRONMENT_VARIABLE, "").strip()
    if not configured or os.environ.get(RUNTIME_REEXECUTION_MARKER) == "1":
        return
    worker_python = Path(configured).expanduser().resolve()
    if not worker_python.exists() or worker_python == Path(sys.executable).resolve():
        return
    environment = dict(os.environ)
    environment[RUNTIME_REEXECUTION_MARKER] = "1"
    completed = subprocess.run([str(worker_python), str(Path(__file__).resolve()), *sys.argv[1:]], env=environment, check=False)
    raise SystemExit(completed.returncode)


def load_rows(root: Path) -> list[dict[str, Any]]:
    """Read every completed book in catalog order so metadata and vectors stay aligned."""
    catalog = read_json(root / "catalog.json")
    rows: list[dict[str, Any]] = []
    for item in catalog.get("books", []):
        path = root / str(item["doc_id"]) / "jsonl_ai" / "chunks.jsonl"
        if not path.exists():
            raise FileNotFoundError(f"section chunks missing: {path}")
        rows.extend(read_jsonl(path))
    if not rows:
        raise RuntimeError(f"no section rows under {root}")
    return rows


def embedding_text(row: dict[str, Any]) -> str:
    chapter = row.get("chapter_path", [])
    chapter_text = " / ".join(str(item) for item in chapter) if isinstance(chapter, list) else str(chapter or "")
    return "\n".join(
        filter(
            None,
            (
                str(row.get("book_name") or ""),
                str(row.get("volume") or ""),
                chapter_text,
                str(row.get("section_title") or ""),
                str(row.get("text") or ""),
                str(row.get("formula_text") or ""),
            ),
        )
    )[:MAX_EMBED_TEXT]


def build_bm25(parent: Path, root: Path) -> dict[str, Any]:
    """Use the production parser index implementation instead of a test-only scorer."""
    sys.path.insert(0, str(parent))
    import OCR测试方案.bm25_index as bm25
    import OCR测试方案.search_core as search_core

    # bm25_index delegates row loading to search_core; both module roots must be
    # changed together or the persisted manifest silently indexes the old corpus.
    bm25.PROCESSED_ROOT = root
    search_core.PROCESSED_ROOT = root
    search_core.BOOK_ROOT = root / "math_b_xuanze_bixiu_3"
    index_dir = root / "_bm25_index"
    bm25.DEFAULT_INDEX_DIR = index_dir
    manifest = bm25.build_and_save_bm25_index(index_dir=index_dir)
    return manifest if isinstance(manifest, dict) else {"indexDir": str(index_dir)}


def build_bge(root: Path, rows: list[dict[str, Any]], model_path: Path, batch_size: int, device: str) -> dict[str, Any]:
    """Encode real section text with the local BGE model and persist aligned metadata."""
    from sentence_transformers import SentenceTransformer

    model = SentenceTransformer(str(model_path), device=device)
    vectors = model.encode(
        [embedding_text(row) for row in rows],
        batch_size=batch_size,
        convert_to_numpy=True,
        normalize_embeddings=True,
        show_progress_bar=True,
    ).astype(np.float32)
    index_dir = root / "_section_bge_index"
    index_dir.mkdir(parents=True, exist_ok=True)
    np.save(index_dir / "embeddings.npy", vectors)
    write_jsonl(index_dir / "metadata.jsonl", rows)
    fingerprint = hashlib.sha256((root / "catalog.json").read_bytes() + (root / "catalog.jsonl").read_bytes()).hexdigest()
    manifest = {
        "kind": "bge_section_chunk_library",
        "model": str(model_path.resolve()),
        "device": device,
        "dimension": int(vectors.shape[1]),
        "row_count": len(rows),
        "source_library": str(root.resolve()),
        "source_fingerprint": fingerprint,
        "metadata": "metadata.jsonl",
        "vectors": "embeddings.npy",
        "text_contract_max_characters": MAX_EMBED_TEXT,
    }
    write_json(index_dir / "manifest.json", manifest)
    return manifest


def main() -> None:
    ensure_embedding_runtime()
    parser = argparse.ArgumentParser(description="Build BM25 and BGE indexes for the all-book section library")
    parser.add_argument("--library-parent", type=Path, default=DEFAULT_PARENT)
    parser.add_argument(
        "--root-name",
        required=True,
        help="Explicit c2 section-library directory name used by production retrieval and its parent-child index.",
    )
    parser.add_argument("--model", type=Path, default=DEFAULT_MODEL)
    parser.add_argument("--batch-size", type=int, default=DEFAULT_BATCH_SIZE)
    parser.add_argument("--device", default=os.environ.get("MATH_AGENT_REBUILD_BGE_DEVICE", "cuda"))
    args = parser.parse_args()
    parent = args.library_parent.expanduser().resolve()
    root = parent / args.root_name
    rows = load_rows(root)
    bm25_manifest = build_bm25(parent, root)
    bge_manifest = build_bge(root, rows, args.model.expanduser().resolve(), max(1, args.batch_size), args.device)
    print(json.dumps({"root": str(root), "rows": len(rows), "bm25": bm25_manifest, "bge": bge_manifest}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
