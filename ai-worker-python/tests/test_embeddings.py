import json
from pathlib import Path
import tempfile
import unittest

from app.embeddings import (
    EmbeddingProviderError,
    EmbeddingService,
    LocalBertVocabTokenizer,
    clip_similarity_response,
    decode_allowed_image_source,
    fit_vectors_to_dimension,
    openai_embedding_response,
)
from app.settings import WorkerSettings


class FakeResponse:
    def __init__(self, body: dict):
        self.body = json.dumps(body).encode("utf-8")

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc, tb):
        return False

    def read(self):
        return self.body


class EmbeddingServiceTest(unittest.TestCase):
    def test_local_bert_vocab_tokenizer_keeps_chinese_tokens(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            vocab = Path(temp_dir) / "vocab.txt"
            vocab.write_text("[PAD]\n[UNK]\n[CLS]\n[SEP]\n导\n数\n防\n盗\n", encoding="utf-8")
            tokenizer = LocalBertVocabTokenizer(str(vocab))

            encoded = tokenizer(
                ["导数", "防盗"],
                padding="max_length",
                truncation=True,
                max_length=6,
                return_tensors="pt",
            )["input_ids"].tolist()

        self.assertEqual(encoded[0][:4], [2, 4, 5, 3])
        self.assertEqual(encoded[1][:4], [2, 6, 7, 3])
        self.assertNotEqual(encoded[0], encoded[1])

    def test_fails_when_no_real_provider_is_configured(self):
        settings = WorkerSettings.from_environment(env={
            "MATH_AGENT_EMBEDDING_PROVIDER_ORDER": "local_clip",
            "MATH_AGENT_LOCAL_CLIP_MODEL_PATH": "Z:\\missing\\local-clip",
        })
        service = EmbeddingService(settings)

        with self.assertRaisesRegex(
            EmbeddingProviderError,
            "MATH_AGENT_LOCAL_CLIP_MODEL_PATH|local CLIP model path|torch and Pillow",
        ):
            service.embed("function monotonicity")

    def test_status_reports_multilevel_capabilities_without_secrets(self):
        settings = WorkerSettings.from_environment(env={
            "MATH_AGENT_EMBEDDING_PROVIDER_ORDER": "dashscope",
            "DASHSCOPE_API_KEY": "dashscope-key",
        })
        service = EmbeddingService(settings)

        status = service.status()

        self.assertEqual(status["status"], "ready")
        self.assertEqual(status["levels"]["textEmbedding"]["dimension"], 512)
        self.assertEqual(status["levels"]["clipImageEmbedding"]["dimension"], 512)
        self.assertEqual(status["levels"]["textEmbedding"]["status"], "ready")
        self.assertTrue(status["providers"]["dashscope"]["apiKeyConfigured"])
        self.assertNotIn("dashscope-key", json.dumps(status))

    def test_dashscope_embedding_uses_real_http_contract(self):
        captured = {}

        def opener(req, timeout):
            captured["url"] = req.full_url
            captured["auth"] = req.headers["Authorization"]
            captured["body"] = json.loads(req.data.decode("utf-8"))
            return FakeResponse({
                "model": "text-embedding-v4",
                "data": [{"embedding": [0.1, 0.2, 0.3]}],
                "usage": {"prompt_tokens": 5},
            })

        settings = WorkerSettings.from_environment(env={
            "MATH_AGENT_EMBEDDING_PROVIDER_ORDER": "dashscope",
            "DASHSCOPE_API_KEY": "dashscope-key",
            "MATH_AGENT_EMBEDDING_DIMENSION": "3",
        })
        service = EmbeddingService(settings, opener=opener)

        result = service.embed(["function monotonicity"], model="text-embedding-v4", dimensions=3)

        self.assertEqual(captured["url"], "https://dashscope.aliyuncs.com/compatible-mode/v1/embeddings")
        self.assertEqual(captured["auth"], "Bearer dashscope-key")
        self.assertEqual(captured["body"]["model"], "text-embedding-v4")
        self.assertEqual(captured["body"]["dimensions"], 3)
        self.assertEqual(result.provider, "dashscope")
        self.assertEqual(result.vectors, [[0.1, 0.2, 0.3]])
        self.assertEqual(openai_embedding_response(result)["data"][0]["embedding"], [0.1, 0.2, 0.3])

    def test_dashscope_dimension_mismatch_fails(self):
        def opener(req, timeout):
            return FakeResponse({
                "model": "text-embedding-v4",
                "data": [{"embedding": [0.1, 0.2]}],
                "usage": {"prompt_tokens": 5},
            })

        settings = WorkerSettings.from_environment(env={
            "MATH_AGENT_EMBEDDING_PROVIDER_ORDER": "dashscope",
            "DASHSCOPE_API_KEY": "dashscope-key",
            "MATH_AGENT_EMBEDDING_DIMENSION": "3",
        })
        service = EmbeddingService(settings, opener=opener)

        with self.assertRaisesRegex(EmbeddingProviderError, "dimension mismatch"):
            service.embed(["function monotonicity"])

    def test_clip_similarity_response_keeps_real_vectors(self):
        response = clip_similarity_response(type("Result", (), {
            "model": "local-clip",
            "provider": "local_clip",
            "text_vectors": [[1.0, 0.0]],
            "image_vectors": [[0.5, 0.5]],
            "similarities": [[0.707]],
        })())

        self.assertEqual(response["object"], "clip.similarity")
        self.assertEqual(response["similarities"], [[0.707]])
        self.assertEqual(response["textEmbeddings"], [[1.0, 0.0]])

    def test_local_clip_vectors_can_be_reduced_to_configured_dimension(self):
        vectors = fit_vectors_to_dimension([[3.0, 4.0, 12.0]], 2)

        self.assertAlmostEqual(vectors[0][0], 0.6)
        self.assertAlmostEqual(vectors[0][1], 0.8)

        with self.assertRaisesRegex(EmbeddingProviderError, "dimension mismatch"):
            fit_vectors_to_dimension([[1.0]], 2)

    def test_local_clip_image_source_accepts_only_inline_bytes(self):
        raw = b"image-bytes"
        encoded = "aW1hZ2UtYnl0ZXM="

        self.assertEqual(decode_allowed_image_source(encoded), raw)
        self.assertEqual(
            decode_allowed_image_source("data:image/png;base64," + encoded),
            raw,
        )

        with self.assertRaisesRegex(ValueError, "remote image URLs are not accepted"):
            decode_allowed_image_source("http://127.0.0.1:19531/internal.png")
        with self.assertRaisesRegex(ValueError, "local image paths are not accepted"):
            decode_allowed_image_source("C:\\Users\\doob\\secret.png")
        with self.assertRaisesRegex(ValueError, "local image paths are not accepted"):
            decode_allowed_image_source("/mnt/d/secret.png")


if __name__ == "__main__":
    unittest.main()
