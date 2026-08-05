"""Regression coverage for the real, non-fabricating handout acceptance parser."""

from __future__ import annotations

import importlib.util
import os
from pathlib import Path
import sys
import unittest


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
SUMMARY_SCRIPT = REPOSITORY_ROOT / "scripts" / "local" / "summarize-handout-runs.py"
REAL_RUN_DIRECTORY = Path(os.getenv(
    "MATH_AGENT_REAL_HANDOUT_RUN_DIR",
    REPOSITORY_ROOT / "output" / "acceptance" / "python-langgraph-handout" / "run-real-luna-20260804-final-v6",
))


def _load_summary_module():
    """Loads the executable report script without requiring it to be packaged as application code."""
    specification = importlib.util.spec_from_file_location("handout_baseline_summary", SUMMARY_SCRIPT)
    if specification is None or specification.loader is None:
        raise RuntimeError("handout summary script is not importable")
    module = importlib.util.module_from_spec(specification)
    # Dataclass resolves postponed annotations through sys.modules during module execution.
    sys.modules[specification.name] = module
    specification.loader.exec_module(module)
    return module


class HandoutBaselineSummaryTest(unittest.TestCase):
    """Uses the checked-in real Luna acceptance output; no provider or metric is simulated here."""

    def test_real_run_reports_observed_values_and_missing_operational_fields(self):
        self.assertTrue(REAL_RUN_DIRECTORY.is_dir(), "real acceptance evidence is required for this parser test")
        summary = _load_summary_module().summarize_run_directory(REAL_RUN_DIRECTORY)

        self.assertEqual(summary["attemptCount"], 1)
        self.assertEqual(summary["successCount"], 1)
        self.assertEqual(summary["providerSuccessCount"], 3)
        self.assertGreater(summary["elapsedMilliseconds"]["p50"], 0)
        self.assertGreater(summary["tokens"]["total"], 0)
        self.assertFalse(summary["costKnown"])
        self.assertIn("queueWaitMilliseconds", summary["missingFields"])
        self.assertIn("pdfMilliseconds", summary["missingFields"])
