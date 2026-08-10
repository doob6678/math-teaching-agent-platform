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


class HandoutMetricsLedger:
    """Writes Python graph timing/load evidence using only the restricted runtime database account.

    Java owns business task state, authorization and publication. This ledger can upsert only the dedicated
    telemetry tables, so an AI worker cannot change a task's owner, publication decision, or handout content.
    """

    @staticmethod
    def _required() -> bool:
        return os.getenv("MATH_AGENT_METRICS_REQUIRED", "false").strip().lower() in {"1", "true", "yes"}

    @staticmethod
    def _connection():
        import pymysql
        return pymysql.connect(
            host=os.getenv("MATH_AGENT_DB_HOST", "mysql"),
            port=int(os.getenv("MATH_AGENT_DB_PORT", "3306")),
            user=os.getenv("MATH_AGENT_DB_USERNAME", "ai_runtime"),
            password=os.getenv("MATH_AGENT_DB_PASSWORD", ""),
            database=os.getenv("MATH_AGENT_DB_NAME", "math_agent_rag"),
            autocommit=True,
            charset="utf8mb4",
        )

    @staticmethod
    def _database_timestamp(value: Any) -> datetime | None:
        """Converts ISO-8601 transport timestamps to MySQL-safe UTC datetimes.

        Python graph events intentionally use timezone-aware ISO strings on the wire. Passing those strings directly
        to MySQL relies on server-specific coercion and can reject the trailing offset, so the ledger normalizes the
        value before a fail-closed metrics insert.
        """
        if not isinstance(value, str) or not value.strip():
            return None
        parsed = datetime.fromisoformat(value.strip().replace("Z", "+00:00"))
        if parsed.tzinfo is not None:
            parsed = parsed.astimezone(timezone.utc).replace(tzinfo=None)
        return parsed

    def append(self, request: Any, metrics: Any, result_status: str) -> None:
        """Upserts run/node telemetry without turning absent operational fields into zeroes."""
        try:
            run = metrics.model_dump(by_alias=True) if hasattr(metrics, "model_dump") else dict(metrics)
            samples = list(run.get("systemLoad") or [])
            cpu_samples = [{"timestamp": item.get("timestamp"), "cpuPercent": item.get("cpu_percent")} for item in samples]
            rss_samples = [{"timestamp": item.get("timestamp"), "rssBytes": item.get("rss_bytes")} for item in samples]
            gpu_samples = [{"timestamp": item.get("timestamp"), "gpu": item.get("gpu", [])} for item in samples]
            now = datetime.now(timezone.utc).replace(tzinfo=None)
            # Use the graph's wall-clock terminal timestamp so Java/Python timing joins do not hide transport delay.
            finished_at = self._database_timestamp(run.get("finishedAt")) or now
            completed_at = finished_at if result_status == "COMPLETED" else None
            failed_at = finished_at if result_status != "COMPLETED" else None
            with self._connection() as conn:
                with conn.cursor() as cursor:
                    cursor.execute(
                        "INSERT INTO handout_run_metrics (run_id,task_id,workflow_code,result_status,trace_id,python_started_at,completed_at,failed_at,request_bytes,response_bytes,retry_count,cpu_samples_json,rss_samples_json,gpu_samples_json,updated_at) VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s) ON DUPLICATE KEY UPDATE result_status=VALUES(result_status),trace_id=COALESCE(VALUES(trace_id),trace_id),python_started_at=COALESCE(VALUES(python_started_at),python_started_at),completed_at=COALESCE(VALUES(completed_at),completed_at),failed_at=COALESCE(VALUES(failed_at),failed_at),request_bytes=VALUES(request_bytes),response_bytes=VALUES(response_bytes),retry_count=GREATEST(COALESCE(retry_count,0),COALESCE(VALUES(retry_count),0)),cpu_samples_json=VALUES(cpu_samples_json),rss_samples_json=VALUES(rss_samples_json),gpu_samples_json=VALUES(gpu_samples_json),updated_at=VALUES(updated_at)",
                        (request.run_id, request.task_id, "handout", result_status, request.trace_id, self._database_timestamp(run.get("startedAt")), completed_at, failed_at, len(json.dumps(request.model_dump(by_alias=True), ensure_ascii=False).encode("utf-8")), len(json.dumps(run, ensure_ascii=False).encode("utf-8")), 0, json.dumps(cpu_samples, ensure_ascii=False), json.dumps(rss_samples, ensure_ascii=False), json.dumps(gpu_samples, ensure_ascii=False), now),
                    )
                    for node in run.get("nodeMetrics") or []:
                        cost = float(node.get("estimatedCost", -1.0))
                        cursor.execute(
                            "INSERT INTO handout_node_metrics (run_id,node_code,started_at,finished_at,elapsed_ms,provider,model_code,provider_calls,java_requests,payload_bytes,prompt_tokens,cached_prompt_tokens,completion_tokens,total_tokens,estimated_cost,cost_known,error_code) VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s) ON DUPLICATE KEY UPDATE started_at=VALUES(started_at),finished_at=VALUES(finished_at),elapsed_ms=VALUES(elapsed_ms),provider=VALUES(provider),model_code=VALUES(model_code),provider_calls=VALUES(provider_calls),java_requests=VALUES(java_requests),payload_bytes=VALUES(payload_bytes),prompt_tokens=VALUES(prompt_tokens),cached_prompt_tokens=VALUES(cached_prompt_tokens),completion_tokens=VALUES(completion_tokens),total_tokens=VALUES(total_tokens),estimated_cost=VALUES(estimated_cost),cost_known=VALUES(cost_known),error_code=VALUES(error_code)",
                            (request.run_id, node.get("node"), self._database_timestamp(node.get("startedAt")), self._database_timestamp(node.get("finishedAt")), node.get("elapsedMs"), node.get("provider") or None, node.get("model") or None, node.get("providerCalls", 0), node.get("javaRequests", 0), node.get("payloadBytes", 0), node.get("promptTokens", 0), node.get("cachedPromptTokens", 0), node.get("completionTokens", 0), node.get("totalTokens", 0), None if cost < 0 else cost, cost >= 0, node.get("error")),
                        )
        except Exception as error:
            if self._required():
                raise UsagePersistenceError("Handout metrics could not be persisted") from error


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
