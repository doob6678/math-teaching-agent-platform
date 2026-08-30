#!/usr/bin/env python3
"""Audit writer-owned image placements against real handout run artifacts.

The report separates three facts that are easy to conflate:
- the writer Markdown heading and the insertion line selected by the placement;
- the authorized source evidence and opaque asset IDs;
- the pages and image objects present in each compiled PDF.

It reads persisted acceptance artifacts only. It never resolves an asset ID from the
local filesystem and never treats a missing PDF inspection command as a pass.
"""
from __future__ import annotations

import argparse
import collections
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path
from typing import Any

HEADING = re.compile(r"^#{1,6}\s+[^0-9\r\n]*?(\d+)[^0-9\r\n]*$")
IMAGE = re.compile(r"!\[[^]]*\]\(/api/teacher/resources/assets/([A-Za-z0-9-]{8,80})\)")
VARIANTS = ("teacher_writer", "student_writer", "lecture_writer")
PDF_NAMES = {
    "teacher_writer": ("final/teacher-figures-only.pdf", "teacher/teacher.pdf"),
    "student_writer": ("final/student.pdf", "student/student.pdf"),
    "lecture_writer": ("final/lecture.pdf", "lecture/lecture.pdf"),
}


def read_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def stage_snapshot(run_dir: Path, status_name: str | None) -> tuple[Path, dict[str, Any]]:
    candidates = [run_dir / status_name] if status_name else []
    candidates.extend((run_dir / name for name in ("latest-status.json", "resume-response.json")))
    for candidate in candidates:
        if candidate and candidate.is_file():
            value = read_json(candidate)
            if isinstance(value, dict) and isinstance(value.get("stages"), list):
                return candidate, value
    raise FileNotFoundError("no status JSON with a stages array was found")


def numbered_heading(lines: list[str], question_number: int) -> list[tuple[int, str, int]]:
    result = []
    for index, line in enumerate(lines):
        match = HEADING.fullmatch(line.strip())
        if match and int(match.group(1)) == question_number:
            result.append((index, line, len(line) - len(line.lstrip("#"))))
    return result


def next_peer_line(lines: list[str], heading_index: int, depth: int) -> int:
    for index in range(heading_index + 1, len(lines)):
        candidate = lines[index].strip()
        candidate_depth = len(candidate) - len(candidate.lstrip("#"))
        if candidate_depth and candidate_depth <= depth and candidate.startswith("#" * candidate_depth + " "):
            return index
    return len(lines)


def writer_audit(stage: dict[str, Any], variant: str) -> dict[str, Any]:
    markdown = str(stage.get("generatedContent") or "").replace("\r\n", "\n").replace("\r", "\n")
    lines = markdown.split("\n")
    placements = [item for item in stage.get("assetPlacements", []) if isinstance(item, dict)]
    rows = []
    errors = []
    for placement in placements:
        variants = placement.get("variants") or []
        if variant not in variants:
            continue
        assets = [str(value) for value in placement.get("assetIds", []) if str(value).strip()]
        question_number = placement.get("questionNumber")
        matches = numbered_heading(lines, int(question_number)) if isinstance(question_number, int) else []
        row: dict[str, Any] = {
            "questionNumber": question_number,
            "anchor": placement.get("anchor"),
            "layout": placement.get("layout"),
            "assetIds": assets,
            "caption": placement.get("caption") or "",
            "headingMatches": [
                {"line": index + 1, "text": text, "depth": depth}
                for index, text, depth in matches
            ],
        }
        if len(matches) != 1:
            errors.append("missing-heading" if not matches else "duplicate-heading")
        else:
            heading_index, heading_text, depth = matches[0]
            if placement.get("anchor") == "question":
                row["insertionLine"] = heading_index + 2
            elif placement.get("anchor") == "explanation_after_question":
                row["insertionLine"] = next_peer_line(lines, heading_index, depth) + 1
            else:
                errors.append("unsupported-anchor")
            row["headingText"] = heading_text
        rows.append(row)
    markdown_assets = IMAGE.findall(markdown)
    expected_assets = [asset for row in rows for asset in row["assetIds"]]
    if collections.Counter(markdown_assets) != collections.Counter(expected_assets):
        # A writer may intentionally leave image injection to the Java export boundary;
        # this is informational rather than a placement failure.
        markdown_asset_note = "writer-markdown-does-not-contain-all-structured-assets"
    else:
        markdown_asset_note = "structured-assets-already-present"
    return {
        "stageCode": stage.get("stageCode"),
        "markdownLineCount": len(lines),
        "placements": rows,
        "structuredAssetCount": len(expected_assets),
        "writerMarkdownAssetCount": len(markdown_assets),
        "writerMarkdownAssetNote": markdown_asset_note,
        "errors": errors,
    }


def command(name: str) -> str | None:
    configured = os.environ.get(name.upper() + "_BIN")
    return configured if configured and shutil.which(configured) else shutil.which(name)


def parse_pdfimages(listing: str) -> list[dict[str, Any]]:
    rows = []
    for line in listing.splitlines():
        match = re.match(r"^\s*(\d+)\s+(\d+)\s+image\s+(\d+)\s+(\d+)\s+\S+\s+\S+\s+\S+\s+\S+\s+\S+\s+(\d+)\s+", line)
        if match:
            rows.append({
                "page": int(match.group(1)),
                "index": int(match.group(2)),
                "width": int(match.group(3)),
                "height": int(match.group(4)),
                "line": line.strip(),
            })
    return rows


def pdf_audit(run_dir: Path, variant: str, expected_count: int) -> dict[str, Any]:
    selected = next((run_dir / name for name in PDF_NAMES[variant] if (run_dir / name).is_file()), None)
    result: dict[str, Any] = {"variant": variant, "expectedAssetCount": expected_count, "pdf": str(selected.relative_to(run_dir)) if selected else None}
    if selected is None:
        result["errors"] = ["missing-pdf"]
        return result
    listing_path = selected.with_name("images.txt")
    listing = listing_path.read_text(encoding="utf-8", errors="replace") if listing_path.is_file() else None
    if listing is None:
        tool = command("pdfimages")
        if not tool:
            result["errors"] = ["pdfimages-unavailable"]
            return result
        completed = subprocess.run([tool, "-list", str(selected)], check=False, capture_output=True, text=True, encoding="utf-8", errors="replace")
        if completed.returncode != 0:
            result["errors"] = ["pdfimages-failed"]
            result["commandError"] = completed.stderr[-500:]
            return result
        listing = completed.stdout
    images = parse_pdfimages(listing)
    result["imageCount"] = len(images)
    result["imagePages"] = [item["page"] for item in images]
    result["images"] = images
    result["errors"] = []
    if len(images) != expected_count:
        result["errors"].append("pdf-image-count-mismatch")
    return result


def source_audit(run_dir: Path, asset_ids: set[str]) -> dict[str, Any]:
    source_path = run_dir / "retrieval-source-original.json"
    payloads: list[Any] = []
    if source_path.is_file():
        payloads.append(read_json(source_path))
    else:
        # A read-only workflow re-export may contain only latest-status.json. Its
        # resource-curation stage still carries the same signed source snapshot.
        for fallback in (run_dir / "latest-status.json", run_dir / "resume-response.json"):
            if fallback.is_file():
                payloads.append(read_json(fallback))
    if not payloads:
        return {"errors": ["missing-retrieval-source-original.json"], "matches": []}
    matches: dict[tuple[str, str], dict[str, Any]] = {}

    def visit(value: Any) -> None:
        if isinstance(value, str):
            stripped = value.strip()
            if stripped.startswith("{") or stripped.startswith("["):
                try:
                    visit(json.loads(stripped))
                except (TypeError, ValueError, json.JSONDecodeError):
                    pass
            return
        if isinstance(value, dict):
            found = set(str(item) for item in (value.get("imageAssetIds") or value.get("assetIds") or []) if str(item).strip())
            found.update(str(item.get("assetId")) for item in (value.get("assetRefs") or [])
                         if isinstance(item, dict) and item.get("assetId"))
            overlap = sorted(found & asset_ids)
            if overlap:
                evidence_ref = str(value.get("transparentRef") or value.get("transparentReference")
                                   or value.get("evidenceRef") or value.get("ref") or "")
                document_ref = str(value.get("documentRef") or value.get("documentId") or "")
                file_name = str(value.get("fileName") or value.get("documentName") or value.get("title") or "")
                if not evidence_ref and not document_ref and not file_name:
                    for child in value.values():
                        visit(child)
                    return
                key = (document_ref, ",".join(overlap))
                candidate = {
                    "evidenceRef": evidence_ref,
                    "documentRef": document_ref,
                    "fileName": file_name,
                    "assetIds": overlap,
                }
                current = matches.get(key)
                # Nested merged hits can repeat the same block. Prefer richer fields from
                # later records while retaining the backend-issued transparent reference.
                if current is None:
                    matches[key] = candidate
                else:
                    for field in ("evidenceRef", "documentRef", "fileName"):
                        if not current[field] and candidate[field]:
                            current[field] = candidate[field]
            for child in value.values():
                visit(child)
        elif isinstance(value, list):
            for child in value:
                visit(child)

    for payload in payloads:
        visit(payload)
    result = list(matches.values())
    return {"matches": result, "errors": [] if result else ["placement-assets-have-no-source-evidence"]}


def build_report(run_dir: Path, status_name: str | None) -> dict[str, Any]:
    status_path, status = stage_snapshot(run_dir, status_name)
    stages = {str(stage.get("stageCode")): stage for stage in status.get("stages", []) if isinstance(stage, dict)}
    writers = {}
    all_assets: set[str] = set()
    for variant in VARIANTS:
        audit = writer_audit(stages.get(variant, {}), variant)
        writers[variant] = audit
        all_assets.update(asset for row in audit["placements"] for asset in row["assetIds"])
    pdfs = {
        variant: pdf_audit(run_dir, variant, writers[variant]["structuredAssetCount"])
        for variant in VARIANTS
    }
    errors = []
    for value in writers.values():
        errors.extend(value["errors"])
    for value in pdfs.values():
        errors.extend(f"{value['variant']}:{error}" for error in value.get("errors", []))
    sources = source_audit(run_dir, all_assets)
    errors.extend("source:" + error for error in sources["errors"])
    return {
        "statusFile": status_path.name,
        "workflowId": status.get("workflowId"),
        "writers": writers,
        "sourceEvidence": sources,
        "pdfs": pdfs,
        "errors": sorted(set(errors)),
        "passed": not errors,
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("run_dir", type=Path)
    parser.add_argument("--status", help="status JSON filename or path relative to run_dir")
    parser.add_argument("--output", type=Path, help="optional JSON report path")
    parser.add_argument("--strict", action="store_true", help="return exit code 1 when any audit error exists")
    args = parser.parse_args(argv)
    try:
        report = build_report(args.run_dir.resolve(), args.status)
    except (OSError, ValueError, TypeError, json.JSONDecodeError) as error:
        print(json.dumps({"passed": False, "errors": [str(error)]}, ensure_ascii=False, indent=2))
        return 1
    rendered = json.dumps(report, ensure_ascii=False, indent=2) + "\n"
    if args.output:
        args.output.write_text(rendered, encoding="utf-8")
    print(rendered, end="")
    return 1 if args.strict and not report["passed"] else 0


if __name__ == "__main__":
    raise SystemExit(main())
