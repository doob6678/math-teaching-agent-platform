import json
import tempfile
import unittest
from pathlib import Path

from benchmarks.report import write_benchmark_report


class ReportTest(unittest.TestCase):
    def test_write_benchmark_report_creates_machine_and_resume_outputs(self):
        metrics = {
            "environment": {"backendBaseUrl": "http://127.0.0.1:8080"},
            "rag": {
                "sampleCount": 2,
                "teacherResource": {
                    "recall@5": 0.5,
                    "recall@10": 1.0,
                    "evidenceHitRate": 1.0,
                },
                "latency": {"avgMs": 46, "p95Ms": 60},
                "milvus": {"rowCount": 128, "status": "searchable"},
                "teacherParsedBlockCount": 256,
            },
            "agent": {
                "runCount": 2,
                "successRate": 0.5,
                "providerFallbackCount": 1,
                "jsonRepairRecoveredCount": 1,
                "avgTotalTokens": 123,
                "latency": {"avgMs": 1500, "p95Ms": 1800},
            },
            "security": {
                "capabilityReplay": {"rejectionRate": 0.95},
                "rateLimit": {"rateLimitedCount": 7},
                "agentConcurrency": {"rejectedCount": 2},
            },
        }
        with tempfile.TemporaryDirectory() as tmpdir:
            output = write_benchmark_report(metrics, Path(tmpdir))

            metrics_path = output / "metrics.json"
            resume_path = output / "resume_numbers.md"

            self.assertTrue(metrics_path.exists())
            self.assertTrue(resume_path.exists())
            self.assertEqual(json.loads(metrics_path.read_text(encoding="utf-8"))["rag"]["sampleCount"], 2)
            resume_text = resume_path.read_text(encoding="utf-8")
            self.assertIn("Recall@1=0.0%", resume_text)
            self.assertIn("Recall@3=0.0%", resume_text)
            self.assertIn("Recall@5=50.0%", resume_text)
            self.assertIn("平均检索延迟 46ms", resume_text)
            self.assertIn("教师资料 block 数 256", resume_text)
            self.assertIn("Capability 重放拦截率 95.0%", resume_text)


if __name__ == "__main__":
    unittest.main()
