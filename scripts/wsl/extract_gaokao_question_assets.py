"""Produce hash-bound question figure assets with installed local GPU PaddleOCR predictors.

PDFBox produces each source page. PP-DocLayout-L identifies only figure-like regions and
PP-OCRv5 detector/recognizer establishes nearby printed question-number anchors. OCR output
never becomes canonical content; it is used solely for asset binding. Unbound regions are not
published, preventing a visual asset from being attributed to an invented question.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
import zipfile
from pathlib import Path
from typing import Any

from PIL import Image

PROJECT_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_CONFIG = PROJECT_ROOT / "config" / "gaokao-ingestion-2024.json"
RENDERER_CLASS = "RenderPdfEvidencePage"
QUESTION_NUMBER = re.compile(r"(?:^|\s)([1-9]|[12]\d|3[0-9])(?:[.、．]|\s)")
FIGURE_LABELS = {"figure", "image", "chart", "table"}


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def resolve_files(config: dict[str, Any]) -> list[Path]:
    root = Path(config["sourceRootWsl"]).resolve()
    files = [(root / selected).resolve() for selected in config["selectedFiles"]]
    if len(files) != 12 or any(not file.is_relative_to(root) or not file.is_file() or file.suffix.lower() != ".pdf" for file in files):
        raise RuntimeError("the configured canonical Gaokao source whitelist must contain exactly 12 readable PDFs")
    return files


def ensure_renderer() -> Path:
    root = PROJECT_ROOT / ".local-run" / "gaokao-pdf-renderer"
    helper = PROJECT_ROOT / "scripts" / "wsl" / f"{RENDERER_CLASS}.java"
    if (root / f"{RENDERER_CLASS}.class").is_file():
        return root
    jar, libraries = root / "math-agent-rag.jar", root / "lib"
    root.mkdir(parents=True, exist_ok=True)
    subprocess.run(["docker", "cp", "math-agent-rag-backend-1:/app/math-agent-rag.jar", str(jar)], check=True)
    with zipfile.ZipFile(jar) as archive:
        for member in archive.namelist():
            if member.startswith("BOOT-INF/lib/") and member.endswith(".jar"):
                target = libraries / Path(member).name
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(archive.read(member))
    subprocess.run(["javac", "-cp", str(libraries / "*"), "-d", str(root), str(helper)], check=True)
    return root


def render_page(renderer: Path, pdf: Path, page: int, output: Path) -> None:
    output.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run(["java", "-cp", f"{renderer}:{renderer / 'lib'}/*", RENDERER_CLASS, str(pdf), str(page), str(output)], check=True, capture_output=True, text=True, encoding="utf-8")
    if not output.is_file():
        raise RuntimeError(f"PDFBox did not render page {page}")


def count_pages(pdf: Path) -> int:
    """Reads page count with the publisher's pre-existing system Python, not a new dependency in the GPU venv."""
    result = subprocess.run(
        ["python3", "-c", "from pypdf import PdfReader; import sys; print(len(PdfReader(sys.argv[1]).pages))", str(pdf)],
        check=True, capture_output=True, text=True, encoding="utf-8")
    page_total = int(result.stdout.strip())
    if page_total < 1:
        raise RuntimeError(f"source PDF has no pages: {pdf.name}")
    return page_total


def require_gpu() -> None:
    import paddle
    if not paddle.is_compiled_with_cuda() or paddle.device.cuda.device_count() < 1:
        raise RuntimeError("PaddleOCR GPU CUDA runtime is required")
    paddle.set_device("gpu:0")
    value = paddle.to_tensor([1.0], place="gpu:0") + paddle.to_tensor([1.0], place="gpu:0")
    if "gpu" not in str(value.place).lower() or float(value.numpy()[0]) != 2.0:
        raise RuntimeError("PaddleOCR GPU tensor verification failed")


def prediction(predictor: Any, image: Path) -> dict[str, Any]:
    result = next(iter(predictor.predict(str(image))))
    value = result.json if hasattr(result, "json") else result
    if not isinstance(value, dict) or not isinstance(value.get("res"), dict):
        raise RuntimeError("installed Paddle predictor returned an invalid result contract")
    return value["res"]


def polygon_box(points: Any) -> tuple[int, int, int, int] | None:
    if not isinstance(points, list) or len(points) < 4:
        return None
    if all(isinstance(value, (int, float)) for value in points[:4]):
        return tuple(int(value) for value in points[:4])
    if not all(isinstance(point, list) and len(point) >= 2 for point in points):
        return None
    xs, ys = [int(point[0]) for point in points], [int(point[1]) for point in points]
    return min(xs), min(ys), max(xs), max(ys)


def crop_box(box: tuple[int, int, int, int], image: Image.Image, padding: int) -> tuple[int, int, int, int] | None:
    left, top, right, bottom = box
    bounded = max(0, left - padding), max(0, top - padding), min(image.width, right + padding), min(image.height, bottom + padding)
    return bounded if bounded[2] - bounded[0] >= 24 and bounded[3] - bounded[1] >= 24 else None


def question_anchors(detection: dict[str, Any], recognizer: Any, page_image: Path, scratch: Path) -> list[tuple[int, int, str]]:
    anchors: list[tuple[int, int, str]] = []
    scratch.mkdir(parents=True, exist_ok=True)
    polygons = detection.get("dt_polys", [])
    with Image.open(page_image) as source:
        for index, polygon in enumerate(polygons, start=1):
            box = polygon_box(polygon)
            if box is None:
                continue
            clipped = crop_box(box, source, 1)
            if clipped is None:
                continue
            line = scratch / f"line-{index:04d}.png"
            source.crop(clipped).convert("RGB").save(line, format="PNG")
            recognized = prediction(recognizer, line)
            text = str(recognized.get("rec_text", "")).strip()
            matched = QUESTION_NUMBER.search(text)
            if matched:
                anchors.append((clipped[0], clipped[1], matched.group(1)))
    return anchors


def select_question(anchors: list[tuple[int, int, str]], box: tuple[int, int, int, int]) -> str | None:
    above = [anchor for anchor in anchors if anchor[1] <= (box[1] + box[3]) // 2]
    if not above:
        return None
    return max(above, key=lambda anchor: (anchor[1], -abs(anchor[0] - box[0])))[2]


def main() -> None:
    parser = argparse.ArgumentParser(description="Create canonical Gaokao question assets through installed GPU PaddleOCR")
    parser.add_argument("--config", type=Path, default=DEFAULT_CONFIG)
    args = parser.parse_args()
    config = json.loads(args.config.read_text(encoding="utf-8"))
    models = config["localGpuModels"]
    for key in ("layoutModelDir", "ocrDetectionModelDir", "ocrRecognitionModelDir"):
        if not Path(models[key]).is_dir():
            raise RuntimeError(f"required local model is unavailable: {key}")
    require_gpu()
    from paddlex.inference.models import create_predictor
    # Direct installed predictors accept the actual local mobile model identities; high-level PPStructure does not.
    layout = create_predictor("PP-DocLayout-L", models["layoutModelDir"], device="gpu:0")
    detector = create_predictor("PP-OCRv5_mobile_det", models["ocrDetectionModelDir"], device="gpu:0")
    recognizer = create_predictor("PP-OCRv5_mobile_rec", models["ocrRecognitionModelDir"], device="gpu:0")
    renderer, root = ensure_renderer(), (PROJECT_ROOT / config["assetOutputRoot"]).resolve()
    minimum_score, padding = float(config["figureExtraction"]["minimumLayoutScore"]), int(config["figureExtraction"]["paddingPixels"])
    summaries: list[dict[str, Any]] = []
    for pdf in resolve_files(config):
        target = root / pdf.stem
        if target.exists():
            raise FileExistsError(f"refusing to overwrite staged assets: {target}")
        pages, figures, scratch = target / "page-images", target / "figures", target / ".ocr-lines"
        pages.mkdir(parents=True)
        source_hash, entries, page_records = sha256_file(pdf), [], []
        for page_no in range(1, count_pages(pdf) + 1):
            page = pages / f"page-{page_no:03d}.png"
            render_page(renderer, pdf, page_no, page)
            layout_result, detection_result = prediction(layout, page), prediction(detector, page)
            anchors = question_anchors(detection_result, recognizer, page, scratch / f"page-{page_no:03d}")
            regions = []
            for item in layout_result.get("boxes", []):
                if str(item.get("label", "")).lower() not in FIGURE_LABELS or float(item.get("score", 0)) < minimum_score:
                    continue
                box = polygon_box(item.get("coordinate"))
                if box is not None:
                    regions.append(box)
            with Image.open(page) as source:
                for ordinal, region in enumerate(regions, start=1):
                    bounded = crop_box(region, source, padding)
                    question = select_question(anchors, bounded) if bounded else None
                    if bounded is None or question is None:
                        continue
                    output = figures / f"page-{page_no:03d}-region-{ordinal:02d}.png"
                    output.parent.mkdir(parents=True, exist_ok=True)
                    source.crop(bounded).convert("RGB").save(output, format="PNG")
                    entries.append({"sourceSha256": source_hash, "questionNumber": question, "pageNumber": page_no, "bboxPixels": {"left": bounded[0], "top": bounded[1], "right": bounded[2], "bottom": bounded[3]}, "relativeAssetPath": output.relative_to(target).as_posix(), "assetSha256": sha256_file(output), "bindingMethod": "GPU_PP_DOCLAYOUT_L_PP_OCRV5_NEAREST_PRINTED_QUESTION_NUMBER"})
            page_records.append({"pageNumber": page_no, "pageImage": page.relative_to(target).as_posix(), "pageImageSha256": sha256_file(page), "layoutRegionCount": len(regions), "boundAssetCount": sum(1 for entry in entries if entry["pageNumber"] == page_no)})
        if scratch.exists():
            import shutil
            shutil.rmtree(scratch)
        (target / "question-assets.jsonl").write_text("".join(json.dumps(entry, ensure_ascii=False) + "\n" for entry in entries), encoding="utf-8")
        report = {"sourceFile": pdf.name, "sourceSha256": source_hash, "producer": "GPU_PADDLEOCR_PP_DOCLAYOUT_L", "device": "gpu:0", "pageCount": len(page_records), "assetCount": len(entries), "pages": page_records}
        (target / "asset-report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
        summaries.append({"sourceFile": pdf.name, "sourceSha256": source_hash, "pageCount": len(page_records), "assetCount": len(entries)})
    print(json.dumps({"paperCount": len(summaries), "papers": summaries}, ensure_ascii=False))


if __name__ == "__main__":
    main()
