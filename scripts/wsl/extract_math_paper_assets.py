"""Create source-bound Markdown, text and figure assets from explicitly configured mathematics PDFs.

The script is deliberately a pre-vision stage.  PP-DocLayout-L and PP-OCR run on
the verified local GPU to identify visual regions and question-number anchors.  A
later Terra or Luna page transcription uses the preserved page image, while this
script's JSONL manifest binds its recognized question number to the original crop.
"""
from __future__ import annotations

import argparse
from dataclasses import dataclass
import hashlib
import json
from pathlib import Path
import re
import shutil
import sys
from typing import Any, Iterable

PROJECT_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_CONFIG = PROJECT_ROOT / "config" / "math-paper-ingestion-liaoning-2026-05.json"
ALLOWED_OUTPUT_ROOT = PROJECT_ROOT / "output" / "math-paper-assets"
PDF_POINTS_PER_INCH = 72
FIRST_PAGE_NUMBER = 1
VISUAL_LABELS = frozenset({"chart", "image"})
QUESTION_NUMBER_PATTERN = re.compile(r"^\s*(?P<number>[1-9]\d?)\s*(?:[\.．、]\s*|(?=[\u4e00-\u9fff]))")


@dataclass(frozen=True)
class BBox:
    """A positive rectangle in the same pixel coordinate system as one rendered page."""

    x0: float
    y0: float
    x1: float
    y1: float

    def __post_init__(self) -> None:
        """Reject invalid geometry before it can create an off-page or empty crop."""
        if self.x1 <= self.x0 or self.y1 <= self.y0:
            raise ValueError("bounding box must have positive width and height")

    @property
    def center_y(self) -> float:
        """Return the reading-order coordinate used to connect a figure to a question."""
        return (self.y0 + self.y1) / 2

    def padded(self, page_width: int, page_height: int, padding_pixels: int) -> "BBox":
        """Preserve a small white border without allowing crop coordinates outside the source page."""
        return BBox(
            max(0, self.x0 - padding_pixels),
            max(0, self.y0 - padding_pixels),
            min(page_width, self.x1 + padding_pixels),
            min(page_height, self.y1 + padding_pixels),
        )


@dataclass(frozen=True)
class QuestionAnchor:
    """A real OCR-detected question number, including its page and reading column."""

    number: int
    page_index: int
    bbox: BBox
    column: int


@dataclass(frozen=True)
class FigureCandidate:
    """A local GPU layout detection eligible for a source-image crop."""

    page_index: int
    bbox: BBox
    column: int
    label: str
    score: float


def sha256_file(path: Path) -> str:
    """Hash source and generated files in chunks so audit does not depend on file metadata."""
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def question_asset_record(
    *,
    question_number: int,
    page_number: int,
    source_sha256: str,
    source_page_image: str,
    relative_asset_path: str,
    asset_path: Path,
    layout_label: str,
    layout_score: float,
    bbox_pixels: list[float],
    crop_bbox_pixels: list[float],
    binding_method: str,
) -> dict[str, Any]:
    """Create the immutable manifest row consumed by visual ingestion and published Markdown."""
    if "cross_page" in binding_method:
        raise ValueError("cross-page figures require an explicit review record before publication")
    return {
        "questionNumber": str(question_number),
        "pageNumber": page_number,
        "sourceSha256": source_sha256,
        "sourcePageImage": source_page_image,
        "relativeAssetPath": relative_asset_path,
        "assetSha256": sha256_file(asset_path),
        "layoutLabel": layout_label,
        "layoutScore": round(layout_score, 6),
        "bboxPixels": bbox_pixels,
        "cropBboxPixels": crop_bbox_pixels,
        "bindingMethod": binding_method,
    }


def require_gpu(device: str) -> None:
    """Fail closed when Paddle cannot execute an actual tensor operation on the requested GPU."""
    if not device.startswith("gpu:"):
        raise ValueError("the local asset pipeline only accepts a gpu:* device")
    import paddle

    if not paddle.is_compiled_with_cuda() or paddle.device.cuda.device_count() < 1:
        raise RuntimeError("Paddle CUDA runtime is unavailable; CPU fallback is prohibited")
    paddle.set_device(device)
    result = paddle.to_tensor([1.0], place=device) + paddle.to_tensor([1.0], place=device)
    if "gpu" not in str(result.place).lower() or float(result.numpy()[0]) != 2.0:
        raise RuntimeError("GPU tensor verification failed")


def load_predictors(models: dict[str, str]) -> tuple[Any, Any]:
    """Load the checked local layout/OCR weights once, avoiding downloads and CPU fallback."""
    for key in ("layoutModelDir", "ocrDetectionModelDir", "ocrRecognitionModelDir"):
        model_dir = Path(models[key])
        if not (model_dir / "inference.pdiparams").is_file():
            raise FileNotFoundError(f"missing local model weights: {model_dir}")
    if sys.platform == "win32":
        import torch  # noqa: F401
    from paddleocr import LayoutDetection, PaddleOCR

    device = models["device"]
    layout = LayoutDetection(model_name="PP-DocLayout-L", model_dir=models["layoutModelDir"], device=device)
    ocr = PaddleOCR(
        text_detection_model_name="PP-OCRv5_mobile_det",
        text_detection_model_dir=models["ocrDetectionModelDir"],
        text_recognition_model_name="PP-OCRv5_mobile_rec",
        text_recognition_model_dir=models["ocrRecognitionModelDir"],
        use_doc_orientation_classify=False,
        use_doc_unwarping=False,
        use_textline_orientation=False,
        device=device,
    )
    return layout, ocr


def column_for_x(x0: float, page_width: int) -> int:
    """Use the physical page midpoint so the binding logic works for single and double column papers."""
    return 1 if x0 >= page_width / 2 else 0


def extract_anchors(ocr_result: dict[str, Any], page_index: int, page_width: int) -> list[QuestionAnchor]:
    """Accept only OCR lines that begin with a valid exam question number, never a footer page number."""
    anchors: list[QuestionAnchor] = []
    for text, box in zip(ocr_result["rec_texts"], ocr_result["rec_boxes"], strict=True):
        match = QUESTION_NUMBER_PATTERN.match(str(text))
        if not match:
            continue
        bbox = BBox(*(float(value) for value in box))
        anchors.append(QuestionAnchor(int(match.group("number")), page_index, bbox, column_for_x(bbox.x0, page_width)))
    return anchors


def extract_figures(boxes: Iterable[dict[str, Any]], page_index: int, page_width: int, minimum_score: float) -> list[FigureCandidate]:
    """Keep only genuine visual layout classes, excluding text and formula regions by construction."""
    figures: list[FigureCandidate] = []
    for box in boxes:
        label = str(box.get("label", ""))
        score = float(box.get("score", 0))
        if label not in VISUAL_LABELS or score < minimum_score:
            continue
        bbox = BBox(*(float(value) for value in box["coordinate"]))
        figures.append(FigureCandidate(page_index, bbox, column_for_x(bbox.x0, page_width), label, score))
    return figures


def bind_figure(figure: FigureCandidate, anchors: list[QuestionAnchor]) -> tuple[QuestionAnchor, str] | None:
    """Bind by preceding same-column anchor, using only the immediately prior page for page-top continuations."""
    same_page = [anchor for anchor in anchors if anchor.page_index == figure.page_index and anchor.column == figure.column and anchor.bbox.center_y <= figure.bbox.center_y]
    if same_page:
        return max(same_page, key=lambda anchor: anchor.bbox.center_y), "same_page_same_column_preceding_question_anchor"
    previous_page = [anchor for anchor in anchors if anchor.page_index == figure.page_index - 1 and anchor.column == figure.column]
    if previous_page:
        return max(previous_page, key=lambda anchor: anchor.bbox.center_y), "previous_page_same_column_question_anchor"
    return None


def render_page(page: Any, target: Path, dpi: int) -> Any:
    """Render and persist once so inference, crop coordinates and later visual transcription share the same evidence."""
    from PIL import Image

    pixmap = page.get_pixmap(dpi=dpi, alpha=False)
    target.parent.mkdir(parents=True, exist_ok=True)
    pixmap.save(target)
    with Image.open(target) as image:
        return image.convert("RGB").copy()


def write_markdown_index(paper_root: Path, manifest: list[dict[str, Any]]) -> None:
    """Provide a deterministic Markdown asset index without pretending OCR is an authoritative question transcript."""
    grouped: dict[str, list[dict[str, Any]]] = {}
    for item in manifest:
        grouped.setdefault(item["questionNumber"], []).append(item)
    lines = ["# 数学试卷题图资产索引", "", "> 题图由本地 GPU 版面检测绑定；题干正文必须以同源 Terra/Luna 页级转写为准。", ""]
    for question_number in sorted(grouped, key=lambda value: int(value)):
        lines.extend([f"## 第 {question_number} 题", ""])
        for item in grouped[question_number]:
            lines.extend([f"![第{question_number}题图]({item['relativeAssetPath']})", "", f"- 页码：{item['pageNumber']}；绑定：`{item['bindingMethod']}`；版面置信度：`{item['layoutScore']}`。", ""])
    (paper_root / "assets.md").write_text("\n".join(lines), encoding="utf-8")


def process_pdf(pdf_path: Path, output_root: Path, config: dict[str, Any]) -> dict[str, Any]:
    """Generate source-bound page, figure, JSONL and Markdown assets for one explicitly selected mathematics PDF."""
    import fitz

    extraction = config["figureExtraction"]
    output_root.mkdir(parents=True, exist_ok=False)
    page_dir = output_root / "page-images"
    figure_dir = output_root / "figures"
    layout, ocr = load_predictors(config["localGpuModels"])
    source_sha256 = sha256_file(pdf_path)
    anchors: list[QuestionAnchor] = []
    figures: list[FigureCandidate] = []
    rendered_pages: dict[int, Any] = {}
    ocr_pages: list[dict[str, Any]] = []
    with fitz.open(pdf_path) as document:
        for page_index, page in enumerate(document):
            page_number = page_index + FIRST_PAGE_NUMBER
            image = render_page(page, page_dir / f"page-{page_number:03d}.png", int(extraction["renderDpi"]))
            rendered_pages[page_index] = image
            page_path = page_dir / f"page-{page_number:03d}.png"
            layout_result = list(layout.predict(str(page_path)))
            if len(layout_result) != 1:
                raise RuntimeError(f"layout predictor returned {len(layout_result)} results for page {page_number}")
            layout_boxes = list(layout_result[0].json["res"]["boxes"])
            ocr_result_list = list(ocr.predict(str(page_path)))
            if len(ocr_result_list) != 1:
                raise RuntimeError(f"OCR predictor returned {len(ocr_result_list)} results for page {page_number}")
            ocr_result = ocr_result_list[0].json["res"]
            anchors.extend(extract_anchors(ocr_result, page_index, image.width))
            figures.extend(extract_figures(layout_boxes, page_index, image.width, float(extraction["minimumLayoutScore"])))
            ocr_pages.append({"pageNumber": page_number, "text": list(ocr_result["rec_texts"]), "boxesPixels": list(ocr_result["rec_boxes"])})
    manifest: list[dict[str, Any]] = []
    for ordinal, figure in enumerate(figures, start=1):
        bound = bind_figure(figure, anchors)
        if bound is None:
            continue
        anchor, binding_method = bound
        page_number = figure.page_index + FIRST_PAGE_NUMBER
        crop_box = figure.bbox.padded(rendered_pages[figure.page_index].width, rendered_pages[figure.page_index].height, int(extraction["paddingPixels"]))
        file_name = f"q{anchor.number:03d}_p{page_number:03d}_f{ordinal:02d}.png"
        figure_dir.mkdir(parents=True, exist_ok=True)
        rendered_pages[figure.page_index].crop(tuple(round(value) for value in (crop_box.x0, crop_box.y0, crop_box.x1, crop_box.y1))).save(figure_dir / file_name, format="PNG")
        asset_path = figure_dir / file_name
        manifest.append(question_asset_record(
            question_number=anchor.number,
            page_number=page_number,
            source_sha256=source_sha256,
            source_page_image=f"page-images/page-{page_number:03d}.png",
            relative_asset_path=f"figures/{file_name}",
            asset_path=asset_path,
            layout_label=figure.label,
            layout_score=figure.score,
            bbox_pixels=[figure.bbox.x0, figure.bbox.y0, figure.bbox.x1, figure.bbox.y1],
            crop_bbox_pixels=[crop_box.x0, crop_box.y0, crop_box.x1, crop_box.y1],
            binding_method=binding_method,
        ))
    manifest.sort(key=lambda item: (int(item["questionNumber"]), item["pageNumber"], item["relativeAssetPath"]))
    (output_root / "question-assets.jsonl").write_text("".join(json.dumps(item, ensure_ascii=False) + "\n" for item in manifest), encoding="utf-8")
    (output_root / "ocr-pages.json").write_text(json.dumps(ocr_pages, ensure_ascii=False, indent=2), encoding="utf-8")
    write_markdown_index(output_root, manifest)
    report = {"sourceFile": str(pdf_path), "sourceSha256": source_sha256, "paperType": config["paperType"], "subject": config["subject"], "renderDpi": extraction["renderDpi"], "pageCount": len(rendered_pages), "questionAnchorCount": len(anchors), "figureCandidateCount": len(figures), "boundFigureCount": len(manifest), "assetManifest": "question-assets.jsonl", "markdownIndex": "assets.md"}
    (output_root / "asset-report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    return report


def main() -> None:
    """Run only explicitly listed MATHEMATICS PDFs and write assets beneath the project-owned evidence root."""
    parser = argparse.ArgumentParser(description="Extract source-bound mathematics question figure assets on GPU.")
    parser.add_argument("--config", type=Path, default=DEFAULT_CONFIG)
    parser.add_argument("--replace", action="store_true", help="Replace this config's prior derived asset directory after its path is validated.")
    arguments = parser.parse_args()
    config = json.loads(arguments.config.read_text(encoding="utf-8"))
    if config.get("subject") != "MATHEMATICS":
        raise ValueError("only MATHEMATICS configurations are accepted")
    output_root = (PROJECT_ROOT / config["assetOutputRoot"]).resolve()
    if ALLOWED_OUTPUT_ROOT not in output_root.parents:
        raise ValueError("assetOutputRoot must remain under output/math-paper-assets")
    if output_root.exists():
        if not arguments.replace:
            raise FileExistsError(f"asset output already exists: {output_root}; use --replace after reviewing it")
        shutil.rmtree(output_root)
    require_gpu(config["localGpuModels"]["device"])
    source_root = Path(config["sourceRootWsl"])
    reports = []
    for file_name in config["selectedFileNames"]:
        pdf_path = source_root / file_name
        if not pdf_path.is_file() or pdf_path.suffix.lower() != ".pdf":
            raise FileNotFoundError(pdf_path)
        reports.append(process_pdf(pdf_path, output_root / pdf_path.stem, config))
    summary = {"config": str(arguments.config), "paperCount": len(reports), "papers": reports}
    (output_root / "run-report.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(summary, ensure_ascii=False))


if __name__ == "__main__":
    main()
