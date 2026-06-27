from dataclasses import dataclass
import os
from typing import Mapping


@dataclass(frozen=True)
class WorkerSettings:
    processed_books_root: str | None
    openai_api_key: str | None
    qwen_api_key: str | None
    feishu_app_secret: str | None

    @classmethod
    def from_environment(cls, env: Mapping[str, str] | None = None) -> "WorkerSettings":
        source = os.environ if env is None else env
        return cls(
            processed_books_root=source.get("MATH_AGENT_PROCESSED_BOOKS_ROOT"),
            openai_api_key=source.get("OPENAI_API_KEY"),
            qwen_api_key=source.get("QWEN_API_KEY"),
            feishu_app_secret=source.get("FEISHU_APP_SECRET"),
        )
