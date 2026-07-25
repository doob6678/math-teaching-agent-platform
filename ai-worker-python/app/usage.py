"""Durable, per-model-call usage accounting owned by the Python AI worker.

The ledger records every attempt (including failures and provider switches).  A
MySQL table is used in production; an explicit JSONL sink is available for
local deployments that have no database yet and keeps the model path usable.
"""
from __future__ import annotations

from dataclasses import asdict, dataclass
from datetime import datetime, timezone
import json
import os
from pathlib import Path
from threading import Lock
from typing import Any


@dataclass(frozen=True)
class UsageEvent:
    run_id: str
    provider: str
    model: str
    attempt: int
    status: str
    prompt_tokens: int
    completion_tokens: int
    total_tokens: int
    estimated_cost: float
    usage_source: str
    error_code: str | None = None

    def payload(self) -> dict[str, Any]:
        return {**asdict(self), "created_at": datetime.now(timezone.utc).isoformat()}


class UsageLedger:
    """Persists immutable usage events without allowing accounting to break an AI response."""

    _lock = Lock()

    def __init__(self) -> None:
        self._jsonl = os.getenv("MATH_AGENT_USAGE_JSONL_PATH", "")

    def append(self, event: UsageEvent) -> None:
        payload = event.payload()
        with self._lock:
            try:
                if self._jsonl:
                    path = Path(self._jsonl)
                    path.parent.mkdir(parents=True, exist_ok=True)
                    with path.open("a", encoding="utf-8") as stream:
                        stream.write(json.dumps(payload, ensure_ascii=False) + "\n")
                    return
                import pymysql  # optional in tests, required by production image
                conn = pymysql.connect(
                    host=os.getenv("MATH_AGENT_DB_HOST", "mysql"),
                    port=int(os.getenv("MATH_AGENT_DB_PORT", "3306")),
                    user=os.getenv("MATH_AGENT_DB_USERNAME", "root"),
                    password=os.getenv("MATH_AGENT_DB_PASSWORD", ""),
                    database=os.getenv("MATH_AGENT_DB_NAME", "math_agent_rag"),
                    autocommit=True,
                    charset="utf8mb4",
                )
                try:
                    with conn.cursor() as cursor:
                        cursor.execute(
                            "INSERT INTO ai_usage_event "
                            "(run_id, provider, model_code, attempt_no, status, prompt_tokens, completion_tokens, total_tokens, estimated_cost, usage_source, error_code, created_at) "
                            "VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)",
                            (event.run_id, event.provider, event.model, event.attempt, event.status,
                             event.prompt_tokens, event.completion_tokens, event.total_tokens,
                             event.estimated_cost, event.usage_source, event.error_code, payload["created_at"]),
                        )
                finally:
                    conn.close()
            except Exception:
                # Accounting is deliberately best-effort; the event is still returned in actualUsage.
                return


def fallback_tokens(messages: list[dict[str, Any]], content: str = "") -> tuple[int, int, int]:
    """Conservative tokenizer fallback used only when a provider omits usage metadata."""
    prompt = sum(max(1, len(str(item.get("content") or "")) // 4) for item in messages)
    completion = max(0, len(content) // 4)
    return prompt, completion, prompt + completion


def cost_for(provider: str, model: str, prompt: int, completion: int) -> float:
    """Calculate vendor pricing from environment JSON, never from a hard-coded magic formula."""
    try:
        prices = json.loads(os.getenv("MATH_AGENT_AI_PRICES_JSON", "{}"))
        price = prices.get(f"{provider}/{model}", prices.get("default", {}))
        return (prompt * float(price.get("prompt_per_million", 0)) + completion * float(price.get("completion_per_million", 0))) / 1_000_000
    except (TypeError, ValueError, json.JSONDecodeError):
        return 0.0
