from dataclasses import dataclass
import os
from pathlib import Path
from typing import Mapping


# CPU cross-encoders scale quadratically with sequence length. This is an explicit deployment budget, not a ranking
# weight; operators can raise it for GPU deployments without touching retrieval semantics.
DEFAULT_LOCAL_RERANK_MAX_TOKENS = 128
# Page OCR is executed only for an explicitly authorized AI parse. Use the verified multimodal model and allow a
# bounded three-minute relay window, so a slow real response is not silently discarded as an empty recognition.
DEFAULT_FORMULA_VISION_MODEL = "gpt-5.6-luna"
DEFAULT_FORMULA_VISION_TIMEOUT_SECONDS = 180
DEFAULT_FORMULA_VISION_MAX_IMAGE_BYTES = 4 * 1024 * 1024
DEFAULT_FORMULA_VISION_MINIMUM_CONFIDENCE = 0.9
# These provider orders are security invariants, not operator preferences.  Embedding and reranking must stay on
# locally installed models even when a process inherits remote-provider keys or legacy provider-order variables.
LOCAL_TEXT_EMBEDDING_PROVIDER_ORDER = ("local_bge_embedding",)
LOCAL_RERANK_PROVIDER_ORDER = ("local_bge_reranker",)
LOCAL_CLIP_PROVIDER_ORDER = ("local_clip",)


@dataclass(frozen=True)
class WorkerSettings:
    worker_api_key: str | None
    processed_books_root: str | None
    openai_api_key: str | None
    openai_base_url: str
    qwen_api_key: str | None
    feishu_app_secret: str | None
    embedding_provider_order: tuple[str, ...]
    rerank_provider_order: tuple[str, ...]
    local_clip_model_path: str | None
    local_clip_device: str
    local_clip_dimension: int
    local_clip_provider_order: tuple[str, ...]
    local_rerank_model_path: str | None
    local_rerank_device: str
    local_rerank_max_tokens: int
    local_text_embedding_model_path: str | None
    local_text_embedding_device: str
    embedding_dimensions: int
    formula_vision_model: str
    formula_vision_timeout_seconds: int
    formula_vision_max_image_bytes: int
    formula_vision_minimum_confidence: float

    @classmethod
    def from_environment(cls, env: Mapping[str, str] | None = None) -> "WorkerSettings":
        source = os.environ if env is None else env
        return cls(
            worker_api_key=source.get("MATH_AGENT_WORKER_API_KEY") or source.get("MATH_AGENT_EMBEDDING_API_KEY"),
            processed_books_root=resolve_processed_books_root(source),
            openai_api_key=source.get("OPENAI_API_KEY"),
            openai_base_url=source.get("OPENAI_BASE_URL", "https://api.openai.com/v1"),
            qwen_api_key=source.get("QWEN_API_KEY"),
            feishu_app_secret=source.get("FEISHU_APP_SECRET"),
            # 禁止使用 DashScope embedding 模型/当你读到这里禁止恢复：环境变量不得改变本地 provider。
            embedding_provider_order=LOCAL_TEXT_EMBEDDING_PROVIDER_ORDER,
            rerank_provider_order=LOCAL_RERANK_PROVIDER_ORDER,
            local_clip_model_path=resolve_local_clip_model_path(source),
            local_clip_device=source.get("MATH_AGENT_LOCAL_CLIP_DEVICE", "cpu"),
            local_clip_dimension=int(source.get("MATH_AGENT_LOCAL_CLIP_DIMENSION", "512")),
            local_clip_provider_order=LOCAL_CLIP_PROVIDER_ORDER,
            local_rerank_model_path=resolve_local_rerank_model_path(source),
            local_rerank_device=source.get("MATH_AGENT_LOCAL_RERANK_DEVICE", "cpu"),
            local_rerank_max_tokens=max(1, int(source.get("MATH_AGENT_LOCAL_RERANK_MAX_TOKENS", DEFAULT_LOCAL_RERANK_MAX_TOKENS))),
            local_text_embedding_model_path=resolve_local_text_embedding_model_path(source),
            local_text_embedding_device=source.get("MATH_AGENT_LOCAL_TEXT_EMBEDDING_DEVICE", "cpu"),
            embedding_dimensions=int(source.get("MATH_AGENT_EMBEDDING_DIMENSION", "512")),
            # AI parse is explicitly selected per source document. These settings only determine its real provider,
            # request limit and confidence floor; they never affect ordinary TEXT ingestion costs.
            formula_vision_model=source.get(
                "MATH_AGENT_FORMULA_VISION_MODEL",
                source.get("OPENAI_FORMULA_VISION_MODEL", DEFAULT_FORMULA_VISION_MODEL),
            ),
            formula_vision_timeout_seconds=max(
                1,
                int(source.get("MATH_AGENT_FORMULA_VISION_TIMEOUT_SECONDS", DEFAULT_FORMULA_VISION_TIMEOUT_SECONDS)),
            ),
            formula_vision_max_image_bytes=max(
                1,
                int(source.get("MATH_AGENT_FORMULA_VISION_MAX_IMAGE_BYTES", DEFAULT_FORMULA_VISION_MAX_IMAGE_BYTES)),
            ),
            formula_vision_minimum_confidence=min(
                1.0,
                max(
                    0.0,
                    float(source.get("MATH_AGENT_FORMULA_VISION_MINIMUM_CONFIDENCE", DEFAULT_FORMULA_VISION_MINIMUM_CONFIDENCE)),
                ),
            ),
        )


def resolve_processed_books_root(source: Mapping[str, str]) -> str | None:
    configured = source.get("MATH_AGENT_PROCESSED_BOOKS_ROOT")
    if configured:
        return configured
    for candidate in (
        # Must match application.yml: c2 carries the searchable small-heading
        # chunks while retaining page images through its shared page index.
        "C:\\Users\\doob\\Desktop\\个人资料\\高中数学\\下载课本代码\\tchMaterial-parser-main\\tchMaterial-parser-main\\processed_books_section_shadow_all_mini_c2",
        "C:\\Users\\doob\\Desktop\\个人资料\\高中数学\\下载课本代码\\tchMaterial-parser-main\\tchMaterial-parser-main\\processed_books_section_shadow_all_mini_b4",
        "C:\\Users\\doob\\Desktop\\个人资料\\高中数学\\下载课本代码\\tchMaterial-parser-main\\tchMaterial-parser-main\\processed_books",
        "C:\\Users\\doob\\Desktop\\code\\dev\\math_agent_rag\\processed_books",
    ):
        path = Path(candidate)
        if path.is_dir() and (path / "_page_image_index").is_dir():
            return str(path)
    return None


def resolve_local_clip_model_path(source: Mapping[str, str]) -> str | None:
    configured = source.get("MATH_AGENT_LOCAL_CLIP_MODEL_PATH")
    if configured:
        return configured
    for candidate in (
        "D:\\ModelScope\\models\\damo\\multi-modal_clip-vit-large-patch14_zh",
        "D:\\ModelScope\\models\\damo\\multi-modal_clip-vit-large-patch14_336_zh",
        "D:\\project2026\\hf_cache\\hub\\models--OFA-Sys--chinese-clip-vit-large-patch14\\snapshots\\660941af70c6ff89ce658a1735404c0f3e536c38",
    ):
        path = Path(candidate)
        if (path / "pytorch_model.bin").exists() or (path / "model.safetensors").exists():
            return str(path)
    return None


def resolve_local_rerank_model_path(source: Mapping[str, str]) -> str | None:
    configured = source.get("MATH_AGENT_LOCAL_RERANK_MODEL_PATH")
    if configured:
        return configured
    candidates = (
        "D:\\ModelScope\\models\\BAAI\\bge-reranker-v2-m3",
        "D:\\ModelScope\\models\\BAAI\\bge-reranker-base",
        "D:\\project2026\\hf_cache\\hub\\models--BAAI--bge-reranker-v2-m3\\snapshots",
        "D:\\project2026\\hf_cache\\hub\\models--BAAI--bge-reranker-base\\snapshots",
    )
    for candidate in candidates:
        resolved = resolve_model_snapshot(Path(candidate))
        if resolved is not None:
            return str(resolved)
    return None


def resolve_local_text_embedding_model_path(source: Mapping[str, str]) -> str | None:
    configured = source.get("MATH_AGENT_LOCAL_TEXT_EMBEDDING_MODEL_PATH")
    if configured:
        resolved = resolve_model_snapshot(Path(configured))
        return str(resolved) if resolved is not None else None
    for candidate in (
        "D:\\ModelScope\\models\\BAAI\\bge-small-zh-v1.5",
        "D:\\ModelScope\\models\\BAAI\\bge-m3",
    ):
        resolved = resolve_model_snapshot(Path(candidate))
        if resolved is not None:
            return str(resolved)
    return None


def resolve_model_snapshot(path: Path) -> Path | None:
    if (path / "config.json").exists() and ((path / "model.safetensors").exists() or (path / "pytorch_model.bin").exists()):
        return path
    if path.name == "snapshots" and path.is_dir():
        for child in sorted(path.iterdir(), reverse=True):
            if child.is_dir() and (child / "config.json").exists() and ((child / "model.safetensors").exists() or (child / "pytorch_model.bin").exists()):
                return child
    return None
