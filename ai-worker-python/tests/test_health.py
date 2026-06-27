import unittest

from app.health import health_response


class HealthTest(unittest.TestCase):
    def test_health_response_names_worker_service(self):
        self.assertEqual(
            health_response(),
            {"status": "UP", "service": "math-agent-rag-worker"},
        )


if __name__ == "__main__":
    unittest.main()
