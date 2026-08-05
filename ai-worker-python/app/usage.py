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


class UsagePersistenceError(RuntimeError):
    """Raised when production cannot persist provider usage before a workflow may be ACKed."""


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
    cached_prompt_tokens: int = 0
    price_version: str | None = None

    def payload(self) -> dict[str, Any]:
        return {**asdict(self), "created_at": datetime.now(timezone.utc).isoformat()}


class UsageLedger:
    """Persists immutable usage events without allowing accounting to break an AI response."""

    _lock = Lock()

    def __init__(self) -> None:
        self._jsonl = os.getenv("MATH_AGENT_USAGE_JSONL_PATH", "")

    @staticmethod
    def _insert_usage(cursor: Any, event: UsageEvent, payload: dict[str, Any]) -> None:
        """Writes the newest schema first, then preserves accounting on the deployed pre-V33 table.

        The runtime never issues DDL. A running deployment can legitimately have the original immutable usage table
        while the Java-owned schema release has not been applied; that table cannot retain cached-token metadata, but
        it can and must still receive exactly one durable attempt row. Only MySQL's explicit unknown-column error is
        eligible for this compatibility path, so permission and availability failures remain fail-closed.
        """
        extended_params = (
            event.run_id, event.provider, event.model, event.attempt, event.status,
            event.prompt_tokens, event.cached_prompt_tokens, event.completion_tokens, event.total_tokens,
            None if event.estimated_cost < 0 else event.estimated_cost, event.estimated_cost >= 0,
            event.price_version, event.usage_source, event.error_code, payload["created_at"],
        )
        try:
            cursor.execute(
                "INSERT INTO ai_usage_event "
                "(run_id, provider, model_code, attempt_no, status, prompt_tokens, cached_prompt_tokens, "
                "completion_tokens, total_tokens, estimated_cost, cost_known, price_version, usage_source, "
                "error_code, created_at) VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s) "
                "ON DUPLICATE KEY UPDATE usage_event_id=usage_event_id",
                extended_params,
            )
            return
        except Exception as error:
            # MySQL 1054 is the precise signal for an existing pre-V33 immutable table. Do not hide any other error.
            if getattr(error, "args", (None,))[0] != 1054:
                raise

        cursor.execute(
            "INSERT INTO ai_usage_event "
            "(run_id, provider, model_code, attempt_no, status, prompt_tokens, completion_tokens, total_tokens, "
            "estimated_cost, usage_source, error_code, created_at) VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s) "
            "ON DUPLICATE KEY UPDATE usage_event_id=usage_event_id",
            (
                event.run_id, event.provider, event.model, event.attempt, event.status,
                event.prompt_tokens, event.completion_tokens, event.total_tokens,
                # The legacy non-null DECIMAL column uses the documented unknown-cost sentinel rather than zero.
                event.estimated_cost if event.estimated_cost >= 0 else -1.0,
                event.usage_source, event.error_code, payload["created_at"],
            ),
        )

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
                    # This worker must never silently regain broad database access when configuration is incomplete.
                    user=os.getenv("MATH_AGENT_DB_USERNAME", "ai_runtime"),
                    password=os.getenv("MATH_AGENT_DB_PASSWORD", ""),
                    database=os.getenv("MATH_AGENT_DB_NAME", "math_agent_rag"),
                    autocommit=True,
                    charset="utf8mb4",
                )
                try:
                    with conn.cursor() as cursor:
                        # The unique key makes a RabbitMQ redelivery of the same provider attempt idempotent.
                        self._insert_usage(cursor, event, payload)
                finally:
                    conn.close()
            except Exception:
                # Local unit tests may run without MySQL, but production must fail closed: an AI result without an
                # immutable usage row cannot be ACKed because its token/cost audit would be irrecoverably missing.
                if os.getenv("MATH_AGENT_USAGE_REQUIRED", "false").strip().lower() in {"1", "true", "yes"}:
                    raise UsagePersistenceError("AI usage could not be persisted")
                return


def fallback_tokens(messages: list[dict[str, Any]], content: str = "") -> tuple[int, int, int]:
    """Conservative tokenizer fallback used only when a provider omits usage metadata."""
    prompt = sum(max(1, len(str(item.get("content") or "")) // 4) for item in messages)
    completion = max(0, len(content) // 4)
    return prompt, completion, prompt + completion


def cost_for(provider: str, model: str, prompt: int, completion: int) -> float:
    """Calculate deployment pricing; return -1 when no provider/model price is configured."""
    try:
        prices = json.loads(os.getenv("MATH_AGENT_AI_PRICES_JSON", "{}"))
        price = prices.get(f"{provider}/{model}", prices.get(model, prices.get("default")))
        if not isinstance(price, dict):
            return -1.0
        input_rate = price.get("inputPerMillion", price.get("prompt_per_million"))
        output_rate = price.get("outputPerMillion", price.get("completion_per_million"))
        if input_rate is None or output_rate is None:
            return -1.0
        return (prompt * float(input_rate) + completion * float(output_rate)) / 1_000_000
    except (TypeError, ValueError, json.JSONDecodeError):
        return -1.0
