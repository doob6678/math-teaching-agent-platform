import unittest

from app.health import health_response


class HealthTest(unittest.TestCase):
    def test_health_response_is_down_until_retrieval_is_ready(self):
        self.assertEqual(
            health_response(False),
            {"status": "DOWN", "service": "math-agent-rag-worker"},
        )

    def test_health_response_names_ready_worker_service(self):
        self.assertEqual(
            health_response(True),
            {"status": "UP", "service": "math-agent-rag-worker"},
        )


if __name__ == "__main__":
    unittest.main()
