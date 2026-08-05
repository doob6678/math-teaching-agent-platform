"""Accounting invariants that do not require a model provider or database fixture."""

from __future__ import annotations

import json
import os
from pathlib import Path
import tempfile
import unittest

from app.usage import UsageEvent, UsageLedger, cost_for


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

