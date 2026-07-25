from __future__ import annotations

import base64
from dataclasses import dataclass
from io import BytesIO
import json
import re
from typing import Any

import requests
from PIL import Image, UnidentifiedImageError

from app.settings import WorkerSettings


FORMULA_SYSTEM_PROMPT = """You transcribe only the mathematical expression visible in one supplied image.
Do not explain or solve it. Return one JSON object with exactly status, latex, plainText, confidence.
status must be recognized, uncertain, or not_formula. Use recognized only when every symbol, bound,
subscript, superscript, fraction bar, radical, delimiter, and matrix entry is legible. latex and plainText
must be empty for uncertain/not_formula. confidence is a number from 0 to 1."""
MAX_RESPONSE_CHARACTERS = 8_192
SUPPORTED_IMAGE_MEDIA_TYPES = frozenset({"image/png", "image/jpeg", "image/webp", "image/gif", "image/wmf", "image/x-wmf"})
VISION_RASTER_MEDIA_TYPE = "image/png"
# Word often stores an equation as a tiny vector WMF measured in document points. Rasterizing at that native size makes
# superscripts and fraction bars illegible to a vision model, so enlarge only the in-memory request representation.
MIN_WMF_VISION_HEIGHT = 256
PAGE_BATCH_PROMPT = """The image is a vertical contact sheet of numbered document pages. Return JSON only:
{"pages":[{"pageIndex":0,"formulas":[{"latex":"","plainText":"","confidence":0.0}]}]}.
Extract only fully legible formulas; omit uncertain ones. pageIndex is zero-based in the supplied page batch. Do not solve."""


class FormulaRecognitionError(RuntimeError):
    """Raised when a formula image cannot be verified as usable retrieval evidence."""


@dataclass(frozen=True)
class FormulaRecognitionResult:
    status: str
    latex: str
    plain_text: str
    confidence: float
    model: str


class FormulaRecognitionService:
    """Calls the configured OpenAI-compatible visual model only for explicit AI parse requests."""

    def __init__(self, settings: WorkerSettings):
        self._settings = settings

    def recognize(self, data_url: str, mime_type: str | None) -> FormulaRecognitionResult:
        normalized_mime = (mime_type or "").strip().lower()
        vision_data_url = normalize_image_for_vision(
            data_url,
            normalized_mime,
            self._settings.formula_vision_max_image_bytes,
        )
        if not self._settings.openai_api_key:
            raise FormulaRecognitionError("OPENAI_API_KEY is required for AI formula recognition")
        endpoint = chat_completions_endpoint(self._settings)
        request_body: dict[str, Any] = {
            "model": self._settings.formula_vision_model,
            "temperature": 0,
            "max_tokens": 400,
            "response_format": {"type": "json_object"},
            "messages": [
                {"role": "system", "content": FORMULA_SYSTEM_PROMPT},
                {
                    "role": "user",
                    "content": [
                        {"type": "text", "text": "Transcribe this one image."},
                        {"type": "image_url", "image_url": {"url": vision_data_url, "detail": "high"}},
                    ],
                },
            ],
        }
        # A number of OpenAI-compatible relays require a non-null tool schema even for plain JSON output.
        if "api.openai.com" not in endpoint:
            request_body["tools"] = [{"type": "function", "function": {"name": "__no_tool__", "description": "internal compatibility schema", "parameters": {"type": "object", "properties": {}}}}]
            request_body["tool_choice"] = "none"
        try:
            response = requests.post(
                endpoint,
                headers={"Authorization": f"Bearer {self._settings.openai_api_key}", "Content-Type": "application/json"},
                json=request_body,
                timeout=self._settings.formula_vision_timeout_seconds,
            )
        except requests.RequestException as exc:
            raise FormulaRecognitionError(f"formula vision request failed: {exc}") from exc
        if not response.ok:
            raise FormulaRecognitionError(f"formula vision request failed: HTTP {response.status_code}: {response.text[:512]}")
        try:
            content = response.json()["choices"][0]["message"]["content"]
        except (KeyError, IndexError, TypeError, ValueError) as exc:
            raise FormulaRecognitionError("formula vision response has no chat completion content") from exc
        result = parse_formula_response(content, self._settings.formula_vision_minimum_confidence)
        return FormulaRecognitionResult(
            status=result.status,
            latex=result.latex,
            plain_text=result.plain_text,
            confidence=result.confidence,
            model=self._settings.formula_vision_model,
        )

    def recognize_page_batch(self, pages: list[tuple[str, str]]) -> list[dict[str, Any]]:
        """Uses one visual request for a two/four-page contact sheet instead of calling once per equation asset."""
        if not pages:
            return []
        if not self._settings.openai_api_key:
            raise FormulaRecognitionError("OPENAI_API_KEY is required for AI formula recognition")
        contact_sheet = compose_contact_sheet(pages, self._settings.formula_vision_max_image_bytes)
        body = {
            "model": self._settings.formula_vision_model,
            "temperature": 0,
            "max_tokens": 1200,
            "response_format": {"type": "json_object"},
            "messages": [
                {"role": "system", "content": PAGE_BATCH_PROMPT},
                {"role": "user", "content": [{"type": "image_url", "image_url": {"url": contact_sheet, "detail": "high"}}]},
            ],
        }
        try:
            response = requests.post(chat_completions_endpoint(self._settings), headers={"Authorization": f"Bearer {self._settings.openai_api_key}"}, json=body, timeout=self._settings.formula_vision_timeout_seconds)
            response.raise_for_status()
            payload = decode_json_object(response.json()["choices"][0]["message"]["content"])
        except (requests.RequestException, KeyError, IndexError, TypeError, ValueError) as exc:
            raise FormulaRecognitionError(f"page batch formula vision request failed: {exc}") from exc
        accepted = []
        for page in payload.get("pages", []):
            if not isinstance(page, dict) or not isinstance(page.get("pageIndex"), int):
                continue
            formulas = []
            for formula in page.get("formulas", []):
                if not isinstance(formula, dict):
                    continue
                try:
                    latex, plain_text, confidence = text_field(formula, "latex"), text_field(formula, "plainText"), number_field(formula, "confidence")
                    if latex and plain_text and confidence >= self._settings.formula_vision_minimum_confidence:
                        formulas.append({"latex": latex, "plainText": plain_text, "confidence": confidence})
                except FormulaRecognitionError:
                    continue
            if formulas:
                accepted.append({"pageIndex": page["pageIndex"], "formulas": formulas})
        return accepted


def chat_completions_endpoint(settings: WorkerSettings) -> str:
    base_url = (settings.openai_base_url or "").rstrip("/")
    if not base_url:
        raise FormulaRecognitionError("OPENAI_BASE_URL is empty")
    return base_url if base_url.endswith("/chat/completions") else f"{base_url}/chat/completions"


def normalize_image_for_vision(data_url: str, mime_type: str, maximum_bytes: int) -> str:
    match = re.fullmatch(r"data:([^;]+);base64,([A-Za-z0-9+/=]+)", data_url or "", re.DOTALL)
    if match is None:
        raise FormulaRecognitionError("formula image must be a base64 data URL")
    media_type = match.group(1).lower()
    if media_type != mime_type or media_type not in SUPPORTED_IMAGE_MEDIA_TYPES:
        raise FormulaRecognitionError(f"formula image media type is not supported: {media_type}")
    try:
        content = base64.b64decode(match.group(2), validate=True)
    except ValueError as exc:
        raise FormulaRecognitionError("formula image has invalid base64 content") from exc
    if not content:
        raise FormulaRecognitionError("formula image is empty")
    if len(content) > maximum_bytes:
        raise FormulaRecognitionError(f"formula image exceeds configured byte limit of {maximum_bytes}")
    if media_type not in {"image/wmf", "image/x-wmf"}:
        return data_url
    try:
        with Image.open(BytesIO(content)) as vector_image:
            vector_image.load()
            target_height = max(vector_image.height, MIN_WMF_VISION_HEIGHT)
            if target_height != vector_image.height:
                target_width = max(1, round(vector_image.width * target_height / vector_image.height))
                vector_image = vector_image.resize((target_width, target_height), Image.Resampling.LANCZOS)
            raster = BytesIO()
            # The provider accepts raster image data URLs, whereas DOCX often stores equations as WMF vectors.
            vector_image.convert("RGB").save(raster, format="PNG")
    except (UnidentifiedImageError, OSError, ValueError) as exc:
        raise FormulaRecognitionError("WMF formula image cannot be converted to PNG") from exc
    return "data:" + VISION_RASTER_MEDIA_TYPE + ";base64," + base64.b64encode(raster.getvalue()).decode("ascii")


def compose_contact_sheet(pages: list[tuple[str, str]], maximum_bytes: int) -> str:
    """Composes page order vertically in memory, preserving private assets and bounding one provider call per batch."""
    images = []
    for data_url, mime_type in pages:
        normalized = normalize_image_for_vision(data_url, mime_type, maximum_bytes)
        encoded = normalized.split(",", 1)[1]
        with Image.open(BytesIO(base64.b64decode(encoded))) as image:
            image.load()
            page = image.convert("RGB")
            page.thumbnail((1200, 1600), Image.Resampling.LANCZOS)
            images.append(page)
    if not images:
        raise FormulaRecognitionError("formula page batch has no supported images")
    sheet = Image.new("RGB", (max(image.width for image in images), sum(image.height for image in images)), "white")
    offset = 0
    for image in images:
        sheet.paste(image, (0, offset))
        offset += image.height
    output = BytesIO()
    sheet.save(output, format="JPEG", quality=85, optimize=True)
    return "data:image/jpeg;base64," + base64.b64encode(output.getvalue()).decode("ascii")


def parse_formula_response(content: str, minimum_confidence: float) -> FormulaRecognitionResult:
    payload = decode_json_object(content)
    status = text_field(payload, "status").lower()
    latex = text_field(payload, "latex")
    plain_text = text_field(payload, "plainText")
    confidence = number_field(payload, "confidence")
    if status != "recognized":
        raise FormulaRecognitionError(f"formula vision did not verify a formula: {status or 'missing_status'}")
    if not latex or not plain_text:
        raise FormulaRecognitionError("recognized formula response is missing latex or plainText")
    if confidence < minimum_confidence:
        raise FormulaRecognitionError(
            f"formula vision confidence {confidence:.3f} is below configured minimum {minimum_confidence:.3f}"
        )
    return FormulaRecognitionResult(status=status, latex=latex, plain_text=plain_text, confidence=confidence, model="")


def decode_json_object(content: str) -> dict[str, Any]:
    value = (content or "").strip()
    if len(value) > MAX_RESPONSE_CHARACTERS:
        raise FormulaRecognitionError("formula vision response exceeds the safety limit")
    if value.startswith("```"):
        value = re.sub(r"^```(?:json)?\s*|\s*```$", "", value, flags=re.IGNORECASE)
    try:
        decoded = json.loads(value)
    except json.JSONDecodeError as exc:
        raise FormulaRecognitionError("formula vision response is not valid JSON") from exc
    if not isinstance(decoded, dict):
        raise FormulaRecognitionError("formula vision response must be a JSON object")
    return decoded


def text_field(payload: dict[str, Any], name: str) -> str:
    value = payload.get(name)
    return value.strip() if isinstance(value, str) else ""


def number_field(payload: dict[str, Any], name: str) -> float:
    value = payload.get(name)
    if not isinstance(value, (float, int)) or isinstance(value, bool):
        raise FormulaRecognitionError(f"formula vision response has no numeric {name}")
    number = float(value)
    if number < 0 or number > 1:
        raise FormulaRecognitionError(f"formula vision {name} must be between 0 and 1")
    return number
