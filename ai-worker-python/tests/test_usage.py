"""Accounting invariants that do not require a model provider or database fixture."""

from __future__ import annotations

import json
import os
from pathlib import Path
import sys
import tempfile
import types
import unittest
from datetime import datetime

from app.handout_runtime import HandoutMetrics, HandoutRunRequest, NodeMetric
from app.usage import HandoutMetricsLedger, UsageEvent, UsageLedger, cost_for


class UsageLedgerTest(unittest.TestCase):
    """Verifies immutable event fields preserve unknown pricing and cached-token information."""

    def test_unknown_price_is_not_reported_as_zero_cost(self):
        previous = os.environ.pop("MATH_AGENT_AI_PRICES_JSON", None)
        try:
            self.assertEqual(cost_for("openai", "unpriced-model", 100, 50), -1.0)
        finally:
            if previous is not None:
                os.environ["MATH_AGENT_AI_PRICES_JSON"] = previous

    def test_jsonl_event_keeps_cached_tokens(self):
        with tempfile.TemporaryDirectory() as directory:
            ledger_path = Path(directory) / "usage.jsonl"
            previous = os.environ.get("MATH_AGENT_USAGE_JSONL_PATH")
            os.environ["MATH_AGENT_USAGE_JSONL_PATH"] = str(ledger_path)
            try:
                UsageLedger().append(UsageEvent(
                    "run-usage-001", "openai", "model", 1, "SUCCESS", 10, 4, 14,
                    -1.0, "provider", cached_prompt_tokens=3,
                ))
                persisted = json.loads(ledger_path.read_text(encoding="utf-8"))
                self.assertEqual(persisted["cached_prompt_tokens"], 3)
                self.assertEqual(persisted["estimated_cost"], -1.0)
            finally:
                if previous is None:
                    os.environ.pop("MATH_AGENT_USAGE_JSONL_PATH", None)
                else:
                    os.environ["MATH_AGENT_USAGE_JSONL_PATH"] = previous

    def test_metrics_ledger_writes_cached_tokens_and_node_timestamps(self):
        """The SQL contract retains nullable timestamps and known/unknown price truth without database mocks."""
        statements = []

        class Cursor:
            def __enter__(self):
                return self

            def __exit__(self, *_):
                return False

            def execute(self, sql, params):
                statements.append((sql, params))

        class Connection:
            def __enter__(self):
                return self

            def __exit__(self, *_):
                return False

            def cursor(self):
                return Cursor()

            def close(self):
                return None

        fake_pymysql = types.SimpleNamespace(connect=lambda **_: Connection())
        previous_module = sys.modules.get("pymysql")
        previous_required = os.environ.get("MATH_AGENT_METRICS_REQUIRED")
        sys.modules["pymysql"] = fake_pymysql
        os.environ["MATH_AGENT_METRICS_REQUIRED"] = "true"
        try:
            request = HandoutRunRequest(
                runId="run-metrics-001", taskId="task-metrics-001", writingGoal="函数", questionText="题目 1")
            metrics = HandoutMetrics(
                startedAt="2026-08-06T00:00:00+00:00",
                nodeMetrics=[NodeMetric(
                    node="teacher_writer", status="SUCCESS", startedAt="2026-08-06T00:00:01+00:00",
                    finishedAt="2026-08-06T00:00:02+00:00", elapsedMs=1000, providerCalls=1,
                    promptTokens=10, cachedPromptTokens=4, completionTokens=6, totalTokens=16, estimatedCost=-1.0)],
            )
            HandoutMetricsLedger().append(request, metrics, "COMPLETED")
            self.assertEqual(len(statements), 2)
            node_sql, node_params = statements[1]
            self.assertIn("started_at", node_sql)
            self.assertIn("cached_prompt_tokens", node_sql)
            self.assertEqual(node_params[2:4], (
                datetime.fromisoformat("2026-08-06T00:00:01"), datetime.fromisoformat("2026-08-06T00:00:02")))
            self.assertEqual(node_params[11], 4)
            self.assertIsNone(node_params[14])
            self.assertFalse(node_params[15])
        finally:
            if previous_module is None:
                sys.modules.pop("pymysql", None)
            else:
                sys.modules["pymysql"] = previous_module
            if previous_required is None:
                os.environ.pop("MATH_AGENT_METRICS_REQUIRED", None)
            else:
                os.environ["MATH_AGENT_METRICS_REQUIRED"] = previous_required
