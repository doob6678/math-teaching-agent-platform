"""Audits rendered handout PDFs before visual sign-off.

This script is intentionally read-only for input PDFs and writes a JSON report only.  It measures each page's
text density, raster-image coverage, and 16:10 geometry so a visually broken export cannot be accepted only because
XeLaTeX exited successfully.  Human PNG inspection remains mandatory after this structural gate.
"""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re

import fitz  # PyMuPDF


# A figure page must carry the same visible atomic question.  Counting embedded images alone cannot detect the
# historic failure where question 17 ended at the bottom of one page and its geometry diagram started on the next.
QUESTION_NUMBER = re.compile(r"(?m)^\s*(\d{1,3})[.．、]")


def image_pixel_digest(document: fitz.Document, xref: int) -> str:
    """Hashes decoded RGB samples, so XeLaTeX metadata/recompression cannot hide a wrong source image."""
    image = document.extract_image(xref)
    pixmap = fitz.Pixmap(image["image"])
    return hashlib.sha256(pixmap.samples).hexdigest()


def page_metrics(
        page: fitz.Page,
        document: fitz.Document,
        expected_image_digests: dict[str, str]) -> dict[str, object]:
    rect = page.rect
    page_area = rect.width * rect.height
    blocks = page.get_text("blocks")
    text_area = sum(max(0.0, block[2] - block[0]) * max(0.0, block[3] - block[1]) for block in blocks)
    image_area = 0.0
    question_numbers = QUESTION_NUMBER.findall(page.get_text())
    unique_xrefs = []
    for image in page.get_images(full=True):
        if image[0] not in unique_xrefs:
            unique_xrefs.append(image[0])
        for bbox in page.get_image_rects(image[0]):
            image_area += max(0.0, bbox.width) * max(0.0, bbox.height)
    image_digests = [image_pixel_digest(document, xref) for xref in unique_xrefs]
    matched_expected_question = next(
        (number for number in question_numbers if number in expected_image_digests), None)
    expected_digest = expected_image_digests.get(matched_expected_question, "")
    figure_lineage = {
        "question_numbers": question_numbers,
        "has_question_stem": bool(question_numbers),
        "expected_question": matched_expected_question,
        "source_image_match": expected_digest in image_digests if expected_digest else None,
        # For a supplied expected image, the same page must contain its actual source number. Enumeration lines
        # ("1.", "2.") are not accepted as a substitute for the missing question-17 stem.
        "expected_binding_pass": bool(matched_expected_question and expected_digest in image_digests),
    }
    return {
        "number": page.number + 1,
        "width": round(rect.width, 2),
        "height": round(rect.height, 2),
        "aspect": round(rect.width / rect.height, 4),
        "text_chars": len(page.get_text().strip()),
        "text_area_ratio": round(text_area / page_area, 4) if page_area else 0.0,
        "image_area_ratio": round(image_area / page_area, 4) if page_area else 0.0,
        "image_count": len(unique_xrefs),
        "figure_lineage": figure_lineage,
    }


def inspect(pdf: Path, expected_image_digests: dict[str, str]) -> dict[str, object]:
    with fitz.open(pdf) as document:
        pages = [page_metrics(page, document, expected_image_digests) for page in document]
    return {"pdf": str(pdf), "pages": len(pages), "page_metrics": pages}


def expected_image_digests(items: list[str]) -> dict[str, str]:
    """Loads the approved local source images supplied as question-number=absolute-path pairs."""
    digests: dict[str, str] = {}
    for item in items:
        question, separator, source = item.partition("=")
        if not separator or not question.strip() or not source.strip():
            raise ValueError(f"Expected --expected-image QUESTION=PATH, got: {item}")
        source_path = Path(source).expanduser().resolve()
        if not source_path.is_file():
            raise FileNotFoundError(f"Expected source image does not exist: {source_path}")
        pixmap = fitz.Pixmap(str(source_path))
        digests[question.strip()] = hashlib.sha256(pixmap.samples).hexdigest()
    return digests


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("pdf", nargs="+", type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument(
        "--expected-image",
        action="append",
        default=[],
        metavar="QUESTION=PATH",
        help="Approved atomic source image expected beside this question; may be passed more than once.",
    )
    arguments = parser.parse_args()
    digests = expected_image_digests(arguments.expected_image)
    report = {"expected_image_questions": sorted(digests), "reports": [inspect(path.resolve(), digests) for path in arguments.pdf]}
    arguments.output.parent.mkdir(parents=True, exist_ok=True)
    arguments.output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
