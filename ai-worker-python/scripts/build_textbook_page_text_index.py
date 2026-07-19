"""Build the immutable BGE page-text index used by textbook stage-one retrieval.

This is an ingestion command, not an online fallback. It derives every row from processed_books/jsonl_ai/chunks.jsonl,
hashes the exact metadata payload, and replaces the index atomically only after all real vectors are written. Running it
again against unchanged books is intentionally a no-op so uploads and Feishu refreshes do not repeatedly invoke BGE.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import sys
import tempfile
from typing import Iterable


DEFAULT_BATCH_SIZE = 16
DEFAULT_MAX_TEXT_CHARACTERS = 1_600
DEFAULT_EMBEDDING_DEVICE_ENVIRONMENT_VARIABLE = "MATH_AGENT_LOCAL_TEXT_EMBEDDING_DEVICE"
INDEX_DIRECTORY_NAME = "_page_text_index"
INDEX_MANIFEST_FILE = "manifest.json"
INDEX_METADATA_FILE = "metadata.jsonl"
INDEX_VECTORS_FILE = "page_embeddings.npy"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Build a local BGE textbook page-text index from real processed_books chunks")
    parser.add_argument("--processed-books-root", required=True, type=Path)
    parser.add_argument("--model-path", required=True, type=Path)
    parser.add_argument("--batch-size", type=int, default=DEFAULT_BATCH_SIZE)
    parser.add_argument("--max-text-characters", type=int, default=DEFAULT_MAX_TEXT_CHARACTERS)
    # The worker's configured BGE runtime is authoritative.  Desktop shells can
    # otherwise select a CPU-only Python while the serving worker uses CUDA.
    parser.add_argument("--device", default=os.environ.get(DEFAULT_EMBEDDING_DEVICE_ENVIRONMENT_VARIABLE, "cpu"))
    parser.add_argument("--force", action="store_true", help="rebuild even when the source fingerprint is unchanged")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    root = args.processed_books_root.expanduser().resolve()
    model_path = args.model_path.expanduser().resolve()
    if args.batch_size < 1 or args.max_text_characters < 1:
        raise ValueError("batch-size and max-text-characters must be positive")
    if not root.is_dir():
        raise ValueError(f"processed books root does not exist: {root}")
    if not (model_path / "config.json").is_file() or not (model_path / "pytorch_model.bin").is_file():
        raise ValueError(f"BGE model is incomplete: {model_path}")

    metadata = list(load_page_metadata(root, args.max_text_characters))
    if not metadata:
        raise ValueError(f"no jsonl_ai/chunks.jsonl rows found under: {root}")
    fingerprint = source_fingerprint(metadata, model_path, args.max_text_characters)
    index_dir = root / INDEX_DIRECTORY_NAME
    manifest_path = index_dir / INDEX_MANIFEST_FILE
    if not args.force and existing_fingerprint(manifest_path) == fingerprint:
        print(json.dumps({"status": "unchanged", "index": str(index_dir), "rows": len(metadata)}, ensure_ascii=False))
        return 0

    # SentenceTransformer is imported only for index construction, keeping worker process startup free of batch work.
    os.environ.setdefault("KMP_DUPLICATE_LIB_OK", "TRUE")
    from sentence_transformers import SentenceTransformer
    import numpy as np

    model = SentenceTransformer(str(model_path), device=args.device)
    vectors = model.encode(
        [row["embedding_text"] for row in metadata],
        batch_size=args.batch_size,
        convert_to_numpy=True,
        normalize_embeddings=True,
        show_progress_bar=True,
    ).astype(np.float32)
    if vectors.shape[0] != len(metadata) or vectors.shape[1] <= 0:
        raise RuntimeError("BGE returned an invalid textbook page embedding matrix")

    write_index_atomically(
        index_dir=index_dir,
        metadata=metadata,
        vectors=vectors,
        fingerprint=fingerprint,
        model_path=model_path,
        max_text_characters=args.max_text_characters,
    )
    print(json.dumps({"status": "rebuilt", "index": str(index_dir), "rows": len(metadata), "dimension": int(vectors.shape[1])}, ensure_ascii=False))
    return 0


def load_page_metadata(root: Path, max_text_characters: int) -> Iterable[dict[str, object]]:
    """Reads parsed AI chunks rather than filenames, so unknown teacher/book naming never becomes a retrieval feature."""
    for chunks_path in sorted(root.glob("*/jsonl_ai/chunks.jsonl")):
        for line in chunks_path.read_text(encoding="utf-8").splitlines():
            if not line.strip():
                continue
            chunk = json.loads(line)
            doc_id = text(chunk.get("doc_id"))
            page_no = int(chunk.get("page_no") or 0)
            body = text(chunk.get("text"))
            if not doc_id or page_no <= 0 or not body:
                continue
            chapter_path = " / ".join(text(part) for part in chunk.get("chapter_path", []) if text(part))
            formula_text = text(chunk.get("formula_text"))
            embedding_text = compact_text("\n".join(filter(None, [
                text(chunk.get("book_name")),
                text(chunk.get("volume")),
                chapter_path,
                text(chunk.get("section_title")),
                body,
                formula_text,
            ])), max_text_characters)
            yield {
                "chunk_id": text(chunk.get("chunk_id")),
                "section_id": text(chunk.get("section_id") or chunk.get("chunk_id")),
                # The section builder keeps every contributing page chunk as a list.  The public worker contract is
                # scalar, so expose the first immutable source identity rather than serializing Python's list repr
                # into metadata (which would make Java-side fallback matching impossible).
                "source_chunk_id": source_chunk_id_text(chunk),
                "doc_id": doc_id,
                "book_name": text(chunk.get("book_name")),
                "chapter_path": chapter_path,
                "page_no": page_no,
                "printed_page_no": text(chunk.get("printed_page_no")),
                "section_title": text(chunk.get("section_title")),
                "source_page_image": text(chunk.get("source_page_image")),
                "text": body,
                "embedding_text": embedding_text,
            }


def source_fingerprint(metadata: list[dict[str, object]], model_path: Path, max_text_characters: int) -> str:
    # The model path and payload limit affect vectors, so both must participate in the idempotency decision.
    digest = hashlib.sha256()
    digest.update(str(model_path).encode("utf-8"))
    digest.update(str(max_text_characters).encode("ascii"))
    for row in metadata:
        digest.update(json.dumps(row, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8"))
    return digest.hexdigest()


def existing_fingerprint(manifest_path: Path) -> str:
    if not manifest_path.is_file():
        return ""
    try:
        return text(json.loads(manifest_path.read_text(encoding="utf-8")).get("fingerprint"))
    except (OSError, json.JSONDecodeError):
        return ""


def write_index_atomically(
    index_dir: Path,
    metadata: list[dict[str, object]],
    vectors,
    fingerprint: str,
    model_path: Path,
    max_text_characters: int,
) -> None:
    import numpy as np

    parent = index_dir.parent
    staging = Path(tempfile.mkdtemp(prefix=".page_text_index-", dir=parent))
    try:
        public_metadata = [{key: value for key, value in row.items() if key != "embedding_text"} for row in metadata]
        (staging / INDEX_METADATA_FILE).write_text(
            "\n".join(json.dumps(row, ensure_ascii=False, separators=(",", ":")) for row in public_metadata) + "\n",
            encoding="utf-8",
        )
        np.save(staging / INDEX_VECTORS_FILE, vectors)
        manifest = {
            "kind": "page_text_bge_index",
            "embedding_model": str(model_path),
            "dimension": int(vectors.shape[1]),
            "row_count": len(public_metadata),
            "fingerprint": fingerprint,
            "metadata": str(index_dir / INDEX_METADATA_FILE),
            "vectors": str(index_dir / INDEX_VECTORS_FILE),
            "max_text_characters": max_text_characters,
        }
        (staging / INDEX_MANIFEST_FILE).write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        if index_dir.exists():
            backup = parent / (index_dir.name + ".previous")
            if backup.exists():
                shutil.rmtree(backup)
            index_dir.replace(backup)
            staging.replace(index_dir)
            shutil.rmtree(backup)
        else:
            staging.replace(index_dir)
    except Exception:
        shutil.rmtree(staging, ignore_errors=True)
        raise


def compact_text(value: str, max_text_characters: int) -> str:
    normalized = " ".join(value.split())
    return normalized[:max_text_characters].strip()


def text(value: object) -> str:
    return "" if value is None else str(value).strip()


def source_chunk_id_text(chunk: dict[str, object]) -> str:
    direct = text(chunk.get("source_chunk_id"))
    if direct:
        return direct
    values = chunk.get("source_chunk_ids")
    if isinstance(values, list):
        return next((text(value) for value in values if text(value)), "")
    return text(values)


if __name__ == "__main__":
    sys.exit(main())
