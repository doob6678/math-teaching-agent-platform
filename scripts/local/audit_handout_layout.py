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
LECTURE_ASPECT_RATIO = 1.6
LECTURE_ASPECT_TOLERANCE = 0.01
LECTURE_MAX_TEXT_CHARS = 2_400
BASE_FORBIDDEN_MARKERS = ("<TODO>", "[PLACEHOLDER]", "system prompt", "内部提示词", "promptTokens", "model_call_")
STUDENT_FORBIDDEN_MARKERS = ("最终答案", "教师批注", "资料依据", "trace", "workflowId")
# The projection is a multi-page classroom sequence.  It intentionally keeps the submitted problem stems and
# knowledge spine, but must never become a worksheet or expose the model/editor transport protocol.
LECTURE_FORBIDDEN_MARKERS = (
    "最终答案", "完整解答", "资料依据", "教师批注", "h1（", "<wait>", "teacherPrompt",
    "MATHAGENTHTMLSPACER", "MATHAGENTFILLBLANKRULE", "---", "___", "____",
)
HTML_TAG = re.compile(r"</?[A-Za-z][^>]*>")


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


def validate(report: dict[str, object], profile: str, required_text: list[str]) -> list[str]:
    """Return explicit acceptance violations instead of treating a parseable PDF as a pass."""
    pdf_path = Path(str(report["pdf"]))
    with fitz.open(pdf_path) as document:
        extracted = "\n".join(page.get_text() for page in document)
    compact = extracted.replace("\r", "")
    violations = [f"missing required text: {value}" for value in required_text if value not in compact]
    forbidden = list(BASE_FORBIDDEN_MARKERS)
    if profile == "student":
        forbidden.extend(STUDENT_FORBIDDEN_MARKERS)
    if profile == "lecture":
        forbidden.extend(LECTURE_FORBIDDEN_MARKERS)
        # The previous one-page contract caused four submitted questions to collapse into unrelated fragments or to
        # be clipped.  A classroom projection is allowed to span pages; the invariant is readability per page and
        # retention of every required problem, which the caller supplies through --required-text.
        if report["pages"] < 1:
            violations.append("lecture export contains no pages")
        for metric in report["page_metrics"]:
            if abs(float(metric["aspect"]) - LECTURE_ASPECT_RATIO) > LECTURE_ASPECT_TOLERANCE:
                violations.append(f"lecture page {metric['number']} is not 16:10: {metric['aspect']}")
            if int(metric["text_chars"]) > LECTURE_MAX_TEXT_CHARS:
                violations.append(f"lecture page {metric['number']} exceeds text budget: {metric['text_chars']}")
    if profile == "teacher" and not any("资料依据：" in line for line in compact.splitlines()):
        violations.append("teacher handout is missing readable Feishu attribution")
    # Browser-editor transport tags are never valid classroom text.  This catches <br> and future rich-text leakage,
    # including forms that are not already covered by the fixed marker list above.
    if HTML_TAG.search(compact):
        violations.append("visible HTML transport markup")
    for marker in forbidden:
        if marker.lower() in compact.lower():
            violations.append(f"forbidden visible marker: {marker}")
    return violations


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
    parser.add_argument("--profile", choices=("teacher", "student", "lecture"), default="teacher")
    parser.add_argument("--required-text", action="append", default=[], help="Visible text required by this handout profile.")
    parser.add_argument(
        "--expected-image",
        action="append",
        default=[],
        metavar="QUESTION=PATH",
        help="Approved atomic source image expected beside this question; may be passed more than once.",
    )
    arguments = parser.parse_args()
    digests = expected_image_digests(arguments.expected_image)
    reports = [inspect(path.resolve(), digests) for path in arguments.pdf]
    for report in reports:
        report["profile"] = arguments.profile
        report["violations"] = validate(report, arguments.profile, arguments.required_text)
        report["passed"] = not report["violations"]
    report = {"expected_image_questions": sorted(digests), "reports": reports, "passed": all(item["passed"] for item in reports)}
    arguments.output.parent.mkdir(parents=True, exist_ok=True)
    arguments.output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))
    if not report["passed"]:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
