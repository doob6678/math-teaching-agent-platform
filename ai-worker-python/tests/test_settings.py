import os
import unittest

from app.settings import WorkerSettings


class WorkerSettingsTest(unittest.TestCase):
    def test_reads_processed_books_root_from_environment(self):
        original = os.environ.get("MATH_AGENT_PROCESSED_BOOKS_ROOT")
        os.environ["MATH_AGENT_PROCESSED_BOOKS_ROOT"] = "C:/local/processed_books"
        try:
            settings = WorkerSettings.from_environment()
        finally:
            if original is None:
                os.environ.pop("MATH_AGENT_PROCESSED_BOOKS_ROOT", None)
            else:
                os.environ["MATH_AGENT_PROCESSED_BOOKS_ROOT"] = original

        self.assertEqual(settings.processed_books_root, "C:/local/processed_books")

    def test_does_not_require_provider_secrets_for_local_health_checks(self):
        settings = WorkerSettings.from_environment(env={})

        self.assertIsNone(settings.openai_api_key)
        self.assertIsNone(settings.qwen_api_key)
        self.assertIsNone(settings.feishu_app_secret)


if __name__ == "__main__":
    unittest.main()
