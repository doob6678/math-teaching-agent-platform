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
        self.assertIsNone(settings.worker_api_key)
        self.assertEqual(settings.embedding_provider_order, ("local_clip",))
        self.assertEqual(settings.local_clip_provider_order, ("local_clip",))
        self.assertEqual(settings.local_clip_device, "cpu")
        self.assertEqual(settings.local_clip_dimension, 512)
        self.assertEqual(settings.dashscope_embedding_model, "text-embedding-v4")
        self.assertEqual(settings.embedding_dimensions, 512)

    def test_worker_api_key_falls_back_to_embedding_api_key(self):
        settings = WorkerSettings.from_environment(env={"MATH_AGENT_EMBEDDING_API_KEY": "local-key"})

        self.assertEqual(settings.worker_api_key, "local-key")


if __name__ == "__main__":
    unittest.main()
