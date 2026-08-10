import unittest

from benchmarks.metrics import (
    compute_latency_summary,
    compute_recall_summary,
    count_agent_diagnostics,
    summarize_security_results,
)
from benchmarks.rag_eval import _recall_from_rows


class MetricsTest(unittest.TestCase):
    def test_compute_recall_summary_counts_strict_document_hits(self):
        cases = [
            {
                "expectedDocumentId": "doc-a",
                "hits": [{"documentId": "doc-a"}, {"documentId": "doc-b"}],
            },
            {
                "expectedDocumentId": "doc-c",
                "hits": [{"documentId": "doc-a"}, {"documentId": "doc-c"}],
            },
            {
                "expectedDocumentId": "doc-x",
                "hits": [{"documentId": "doc-a"}],
            },
        ]

        summary = compute_recall_summary(cases, cutoffs=(1, 2))

        self.assertEqual(summary["sampleCount"], 3)
        self.assertAlmostEqual(summary["recall@1"], 1 / 3)
        self.assertAlmostEqual(summary["recall@2"], 2 / 3)
        self.assertEqual(summary["hitCount@2"], 2)

    def test_compute_latency_summary_reports_average_and_p95(self):
        summary = compute_latency_summary([100, 50, 200, 300, 150])

        self.assertEqual(summary["count"], 5)
        self.assertEqual(summary["minMs"], 50)
        self.assertEqual(summary["maxMs"], 300)
        self.assertEqual(summary["avgMs"], 160)
        self.assertEqual(summary["p95Ms"], 300)

    def test_rag_recall_summary_uses_only_rows_with_ground_truth_for_strict_recall(self):
        rows = [
            {
                "expectedBlockId": "block-a",
                "hitCount": 1,
                "hits": [{"blockId": "block-a"}],
            },
            {
                "hitCount": 1,
                "hits": [{"blockId": "block-b"}],
            },
        ]

        summary = _recall_from_rows(rows)

        self.assertEqual(summary["sampleCount"], 1)
        self.assertEqual(summary["totalSampleCount"], 2)
        self.assertEqual(summary["hitCount@1"], 1)
        self.assertEqual(summary["hitCount@3"], 1)
        self.assertEqual(summary["hitCount@5"], 1)
        self.assertAlmostEqual(summary["evidenceHitRate"], 1.0)

    def test_count_agent_diagnostics_groups_retry_and_fallback_events(self):
        runs = [
            {
                "ok": True,
                "actualUsage": {"promptTokens": 10, "completionTokens": 5, "totalTokens": 15},
                "elapsedMs": 1200,
                "diagnosticEvents": [
                    {"eventType": "JSON_PARSE_FAILED"},
                    {"eventType": "RETRY_SCHEDULED"},
                    {"eventType": "JSON_PARSE_SUCCEEDED"},
                ],
            },
            {
                "ok": False,
                "actualUsage": {"promptTokens": 3, "completionTokens": 0, "totalTokens": 3},
                "elapsedMs": 900,
                "diagnosticEvents": [
                    {"eventType": "MODEL_CALL_FAILED"},
                    {"eventType": "PROVIDER_ROTATED"},
                ],
            },
        ]

        summary = count_agent_diagnostics(runs)

        self.assertEqual(summary["runCount"], 2)
        self.assertEqual(summary["successCount"], 1)
        self.assertEqual(summary["jsonParseFailureCount"], 1)
        self.assertEqual(summary["jsonRepairRecoveredCount"], 1)
        self.assertEqual(summary["providerFallbackCount"], 1)
        self.assertEqual(summary["modelCallFailureCount"], 1)
        self.assertEqual(summary["totalTokens"], 18)

    def test_summarize_security_results_reports_rejection_rates(self):
        summary = summarize_security_results(
            {
                "authenticatedExecution": [{"status": 200}],
                "duplicateSubmission": [{"status": 200}, {"status": 403}, {"status": 403}],
                "rateLimit": [{"status": 200}, {"status": 429}, {"status": 429}],
                "agentConcurrency": [{"status": 200}, {"status": 429}],
            }
        )

        self.assertEqual(summary["duplicateSubmission"]["attemptCount"], 3)
        self.assertEqual(summary["duplicateSubmission"]["successCount"], 1)
        self.assertAlmostEqual(summary["duplicateSubmission"]["rejectionRate"], 2 / 3)
        self.assertEqual(summary["rateLimit"]["rateLimitedCount"], 2)
        self.assertEqual(summary["agentConcurrency"]["rejectedCount"], 1)


if __name__ == "__main__":
    unittest.main()
