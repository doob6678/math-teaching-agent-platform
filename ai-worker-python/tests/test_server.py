import os
import unittest
from unittest.mock import patch

from fastapi.testclient import TestClient

from app.server import app, embedding_service


class WorkerServerAuthTest(unittest.TestCase):
    def setUp(self):
        self.original_key = os.environ.get("MATH_AGENT_WORKER_API_KEY")

    def tearDown(self):
        embedding_service.cache_clear()
        if self.original_key is None:
            os.environ.pop("MATH_AGENT_WORKER_API_KEY", None)
        else:
            os.environ["MATH_AGENT_WORKER_API_KEY"] = self.original_key

    def test_capabilities_requires_configured_worker_key(self):
        os.environ.pop("MATH_AGENT_WORKER_API_KEY", None)
        client = TestClient(app)

        response = client.get("/v1/capabilities")

        self.assertEqual(response.status_code, 503)

    def test_capabilities_rejects_wrong_worker_key(self):
        os.environ["MATH_AGENT_WORKER_API_KEY"] = "local-key"
        client = TestClient(app)

        response = client.get("/v1/capabilities", headers={"Authorization": "Bearer wrong"})

        self.assertEqual(response.status_code, 401)

    def test_capabilities_accepts_bearer_worker_key(self):
        os.environ["MATH_AGENT_WORKER_API_KEY"] = "local-key"
        client = TestClient(app)

        with patch("app.embeddings.LocalClipBackend.status", return_value={"status": "configuration_error"}):
            response = client.get("/v1/capabilities", headers={"Authorization": "Bearer local-key"})

        self.assertEqual(response.status_code, 200)


if __name__ == "__main__":
    unittest.main()
