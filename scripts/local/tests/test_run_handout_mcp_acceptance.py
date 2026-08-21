"""Pure safety-boundary tests for the MCP handout acceptance runner."""
from __future__ import annotations

import importlib.util
from pathlib import Path
import sys
import unittest


SCRIPT = Path(__file__).parents[1] / "run_handout_mcp_acceptance.py"
SPEC = importlib.util.spec_from_file_location("handout_acceptance_runner", SCRIPT)
assert SPEC and SPEC.loader
runner = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = runner
SPEC.loader.exec_module(runner)


class FakeMcp:
    def __init__(self, result=None, error=None):
        self.result = result
        self.error = error
        self.calls = []

    def call(self, name, arguments):
        self.calls.append((name, arguments))
        if self.error:
            raise self.error
        return self.result


class SubmissionTest(unittest.TestCase):
    def test_only_one_submit_is_allowed(self):
        mcp = FakeMcp({"workflowId": "workflow-12345678"})
        record = {"taskCreationPosts": 0}
        self.assertEqual("workflow-12345678", runner.submit_once(mcp, {"questionText": "q"}, record)["workflowId"])
        self.assertEqual(1, record["taskCreationPosts"])
        with self.assertRaisesRegex(RuntimeError, "second task submission"):
            runner.submit_once(mcp, {"questionText": "q"}, record)
        self.assertEqual(1, len(mcp.calls))

    def test_uncertain_submit_keeps_single_post_correlation(self):
        mcp = FakeMcp(error=RuntimeError("connection reset"))
        record = {"taskCreationPosts": 0}
        with self.assertRaisesRegex(RuntimeError, "outcome is uncertain"):
            runner.submit_once(mcp, {"questionText": "q"}, record)
        self.assertEqual(1, record["taskCreationPosts"])
        self.assertTrue(record["submissionUncertain"])
        self.assertEqual(1, len(mcp.calls))


class TopicAndSafetyTest(unittest.TestCase):
    def test_rotating_topic_is_repeatable_but_idempotency_is_fresh(self):
        self.assertEqual(runner.topic_for("same-label", None), runner.topic_for("same-label", None))
        timestamp = "20260819T123456Z"
        one = runner.idempotency_key("parabola", timestamp)
        two = runner.idempotency_key("parabola", timestamp)
        self.assertTrue(one.startswith("mcp-acceptance:parabola:20260819T123456Z:"))
        self.assertNotEqual(one, two)

    def test_default_run_label_contains_topic_and_utc_second_timestamp(self):
        args = runner.parse_args(["--topic", "parabola"])
        self.assertIsNone(args.run_label)
        self.assertRegex(runner.utc_run_timestamp(), r"^\d{8}T\d{6}Z$")

    def test_correlation_uses_the_mcp_client_request_field(self):
        timestamp = "20260819T123456Z"
        correlation = {
            "runLabel": "handout-mcp-parabola-" + timestamp,
            "topic": "parabola",
            "runTimestamp": timestamp,
            "clientRequestId": runner.idempotency_key("parabola", timestamp),
        }
        self.assertNotIn("idempotencyKey", correlation)
        self.assertRegex(correlation["clientRequestId"], r"^mcp-acceptance:parabola:20260819T123456Z:[0-9a-f]{16}$")

    def test_redaction_and_status_handling(self):
        result = runner.redact({"secretKey": "s", "nested": [{"token": "t"}], "visible": "ok"})
        self.assertEqual("[REDACTED]", result["secretKey"])
        self.assertEqual("[REDACTED]", result["nested"][0]["token"])
        self.assertEqual("ok", result["visible"])
        self.assertTrue(runner.terminal_status("WAITING_REVIEW"))
        self.assertFalse(runner.terminal_status("RUNNING"))
        self.assertTrue(runner.contains_nonfresh_signal({"message": "cached result"}))
        self.assertFalse(runner.contains_nonfresh_signal({"message": "fresh generation"}))


if __name__ == "__main__":
    unittest.main()
