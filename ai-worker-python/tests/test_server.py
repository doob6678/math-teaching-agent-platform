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

    def test_capabilities_rejects_missing_presented_key(self):
        os.environ.pop("MATH_AGENT_WORKER_API_KEY", None)
        client = TestClient(app)

        response = client.get("/v1/capabilities")

        # Production intentionally has a non-empty local default key so a clean Docker start is usable;
        # without the presented header the request is unauthorized, not a fake capability response.
        self.assertEqual(response.status_code, 401)

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

    def test_clip_page_search_requires_worker_key(self):
        os.environ["MATH_AGENT_WORKER_API_KEY"] = "local-key"
        client = TestClient(app)

        response = client.post("/v1/clip/page-search", json={"texts": "函数单调性"})

        self.assertEqual(response.status_code, 401)

    def test_clip_page_search_accepts_bearer_worker_key(self):
        os.environ["MATH_AGENT_WORKER_API_KEY"] = "local-key"
        client = TestClient(app)

        fake_result = type("Result", (), {
            "model": "local-clip",
            "provider": "local_clip",
            "hits": [],
        })()
        with patch("app.embeddings.EmbeddingService.search_page_images", return_value=fake_result):
            response = client.post(
                "/v1/clip/page-search",
                headers={"Authorization": "Bearer local-key"},
                json={"texts": "函数单调性", "limit": 3},
            )

        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["object"], "clip.page_search")

    def test_rerank_accepts_bearer_worker_key(self):
        os.environ["MATH_AGENT_WORKER_API_KEY"] = "local-key"
        client = TestClient(app)

        fake_result = type("Result", (), {
            "model": "local-bge-reranker",
            "provider": "local_bge_reranker",
            "scores": [0.9, 0.2],
        })()
        with patch("app.embeddings.EmbeddingService.rerank", return_value=fake_result):
            response = client.post(
                "/v1/rerank",
                headers={"Authorization": "Bearer local-key"},
                json={"query": "函数单调性", "documents": ["先看端点", "随便文本"]},
            )

        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["object"], "rerank.result")
        self.assertEqual(response.json()["data"][0]["score"], 0.9)

    def test_tokenize_returns_real_encoder_counts(self):
        os.environ["MATH_AGENT_WORKER_API_KEY"] = "local-key"
        response = TestClient(app).post(
            "/v1/tokenize",
            headers={"Authorization": "Bearer local-key"},
            json={"texts": ["函数 $x^2$", ""], "model": "gpt-4o"},
        )
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["total"], sum(response.json()["counts"]))
        self.assertGreater(response.json()["counts"][0], 0)


if __name__ == "__main__":
    unittest.main()
