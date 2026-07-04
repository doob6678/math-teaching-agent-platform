from dataclasses import dataclass
import os
from pathlib import Path
from typing import Mapping


@dataclass(frozen=True)
class WorkerSettings:
    worker_api_key: str | None
    processed_books_root: str | None
    openai_api_key: str | None
    qwen_api_key: str | None
    feishu_app_secret: str | None
    embedding_provider_order: tuple[str, ...]
    local_clip_model_path: str | None
    local_clip_device: str
    local_clip_dimension: int
    local_clip_provider_order: tuple[str, ...]
    dashscope_base_url: str
    dashscope_api_key: str | None
    dashscope_embedding_model: str
    embedding_dimensions: int

    @classmethod
    def from_environment(cls, env: Mapping[str, str] | None = None) -> "WorkerSettings":
        source = os.environ if env is None else env
        provider_order = tuple(
            item.strip()
            for item in source.get("MATH_AGENT_EMBEDDING_PROVIDER_ORDER", "local_clip").split(",")
            if item.strip()
        )
        clip_provider_order = tuple(
            item.strip()
            for item in source.get("MATH_AGENT_CLIP_PROVIDER_ORDER", "local_clip").split(",")
            if item.strip()
        )
        return cls(
            worker_api_key=source.get("MATH_AGENT_WORKER_API_KEY") or source.get("MATH_AGENT_EMBEDDING_API_KEY"),
            processed_books_root=source.get("MATH_AGENT_PROCESSED_BOOKS_ROOT"),
            openai_api_key=source.get("OPENAI_API_KEY"),
            qwen_api_key=source.get("QWEN_API_KEY"),
            feishu_app_secret=source.get("FEISHU_APP_SECRET"),
            embedding_provider_order=provider_order,
            local_clip_model_path=resolve_local_clip_model_path(source),
            local_clip_device=source.get("MATH_AGENT_LOCAL_CLIP_DEVICE", "cpu"),
            local_clip_dimension=int(source.get("MATH_AGENT_LOCAL_CLIP_DIMENSION", "512")),
            local_clip_provider_order=clip_provider_order,
            dashscope_base_url=source.get("DASHSCOPE_BASE_URL", "https://dashscope.aliyuncs.com/compatible-mode/v1"),
            dashscope_api_key=source.get("DASHSCOPE_API_KEY") or source.get("QWEN_API_KEY"),
            dashscope_embedding_model=source.get("MATH_AGENT_DASHSCOPE_EMBEDDING_MODEL", "text-embedding-v4"),
            embedding_dimensions=int(source.get("MATH_AGENT_EMBEDDING_DIMENSION", "512")),
        )


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
