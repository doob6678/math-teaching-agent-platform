from __future__ import annotations

import csv
import json
import tempfile
import unittest
from pathlib import Path

from benchmarks.python_worker_load_test import (
    LoadTestConfig,
    RequestRecord,
    detect_models,
    sanitize_environment,
    summarize_resources,
    summarize_records,
    write_report_artifacts,
)


class PythonWorkerLoadTestUnitTest(unittest.TestCase):
    """Exercises deterministic report helpers without replacing any real HTTP inference request."""

    def test_summarize_records_calculates_percentiles_and_failures(self) -> None:
        records = [
            RequestRecord("bge", "warm", 1, 10.0, 200, "local", "model", 512, ""),
            RequestRecord("bge", "warm", 1, 20.0, 200, "local", "model", 512, ""),
            RequestRecord("bge", "warm", 1, 30.0, 503, "", "", None, "model unavailable"),
        ]

        summary = summarize_records(records, elapsed_seconds=0.06)

        self.assertEqual(summary["requestCount"], 3)
        self.assertEqual(summary["successCount"], 2)
        self.assertEqual(summary["errorCount"], 1)
        self.assertEqual(summary["p50Ms"], 20.0)
        self.assertEqual(summary["p95Ms"], 30.0)
        self.assertEqual(summary["p99Ms"], 30.0)
        self.assertEqual(summary["dimensions"], [512])
        self.assertEqual(summary["qps"], 50.0)

    def test_sanitize_environment_masks_all_sensitive_values(self) -> None:
        sanitized = sanitize_environment(
            {
                "MATH_AGENT_WORKER_API_KEY": "secret",
                "OPENAI_API_KEY": "secret",
                "MATH_AGENT_LOCAL_TEXT_EMBEDDING_MODEL_PATH": "D:\\models\\bge",
            }
        )

        self.assertEqual(sanitized["MATH_AGENT_WORKER_API_KEY"], "<set>")
        self.assertEqual(sanitized["OPENAI_API_KEY"], "<set>")
        self.assertEqual(sanitized["MATH_AGENT_LOCAL_TEXT_EMBEDDING_MODEL_PATH"], "D:\\models\\bge")

    def test_summarize_resources_reports_continuous_gpu_peak_and_average(self) -> None:
        summary = summarize_resources(
            [
                {"gpu_utilization_percent": 10.0, "gpu_memory_used_mb": 1000.0},
                {"gpu_utilization_percent": 90.0, "gpu_memory_used_mb": 2000.0},
            ]
        )

        self.assertEqual(summary["sampleCount"], 2)
        self.assertEqual(summary["gpuUtilizationAvgPercent"], 50.0)
        self.assertEqual(summary["gpuUtilizationMaxPercent"], 90.0)
        self.assertEqual(summary["gpuMemoryMaxMb"], 2000.0)

    def test_detect_models_marks_absent_weight_unavailable(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            available = root / "bge-small-zh-v1.5"
            available.mkdir()
            (available / "config.json").write_text("{}", encoding="utf-8")
            (available / "model.safetensors").write_text("weights", encoding="utf-8")

            models = detect_models({"MATH_AGENT_LOCAL_TEXT_EMBEDDING_MODEL_PATH": str(available)}, root)

        self.assertEqual(models["bge-small-zh-v1.5"]["status"], "available")
        self.assertEqual(models["bge-reranker-base"]["status"], "unavailable")

    def test_detect_models_accepts_modelscope_clip_weight_layout(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            clip = Path(temporary_directory) / "clip"
            clip.mkdir()
            (clip / "configuration.json").write_text("{}", encoding="utf-8")
            (clip / "pytorch_model.bin").write_text("weights", encoding="utf-8")

            models = detect_models({"MATH_AGENT_LOCAL_CLIP_MODEL_PATH": str(clip)}, Path(temporary_directory))

        self.assertEqual(models["chinese-clip"]["status"], "available")

    def test_write_report_artifacts_creates_readable_and_machine_readable_outputs(self) -> None:
        config = LoadTestConfig(output_dir=Path("unused"))
        run = {
            "environment": {"python": "3.12"},
            "models": {"bge-small-zh-v1.5": {"status": "available", "path": "D:/model"}},
            "scenarios": [{"model": "bge-small-zh-v1.5", "name": "bge", "concurrency": 1, "summary": {"requestCount": 1, "successRate": 1.0, "errorRate": 0.0, "p50Ms": 12.0, "p95Ms": 12.0, "p99Ms": 12.0, "qps": 1.0, "errorCount": 0}}],
            "resources": [],
            "stops": [],
            "healthChecks": {"worker": {"health": {"status": 200}}},
            "cacheValidation": {"status": "ready", "firstMs": 20.0, "repeatMs": 2.0},
        }
        with tempfile.TemporaryDirectory() as temporary_directory:
            output_dir = Path(temporary_directory)
            write_report_artifacts(output_dir, config, run, "python benchmark")
            self.assertTrue((output_dir / "report.md").is_file())
            self.assertIn("缓存验证", (output_dir / "report.md").read_text(encoding="utf-8"))
            self.assertEqual(json.loads((output_dir / "results.json").read_text(encoding="utf-8"))["environment"]["python"], "3.12")
            with (output_dir / "summary.csv").open(encoding="utf-8", newline="") as stream:
                self.assertEqual(next(csv.DictReader(stream))["scenario"], "bge")


if __name__ == "__main__":
    unittest.main()
