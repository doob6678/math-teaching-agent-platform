import json
from pathlib import Path
import tempfile
import unittest

from app.embeddings import (
    ClipPageSearchResult,
    ClipPageSearchHit,
    EmbeddingProviderError,
    EmbeddingService,
    LocalBertVocabTokenizer,
    clip_page_search_response,
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


class StubLocalClipBackend:
    def status(self):
        return {
            "status": "ready",
            "textEmbedding": {"status": "ready"},
            "imageEmbedding": {"status": "ready"},
        }

    def embed_text(self, texts, dimensions=None):
        if texts == ["monotonicity"]:
            vectors = [[1.0, 0.0, 0.0]]
        else:
            vectors = [[0.0, 1.0, 0.0] for _ in texts]
        return type("EmbeddingResult", (), {
            "model": "local-clip-test",
            "provider": "local_clip",
            "vectors": vectors,
            "prompt_tokens": 1,
        })()

    def embed_images(self, images, dimensions=None):
        vectors = [[0.0, 1.0, 0.0] for _ in images]
        return type("EmbeddingResult", (), {
            "model": "local-clip-test",
            "provider": "local_clip",
            "vectors": vectors,
            "prompt_tokens": 0,
        })()


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

        def opener(req, *, timeout):
            captured["url"] = req.full_url
            captured["auth"] = req.headers["Authorization"]
            captured["body"] = json.loads(req.data.decode("utf-8"))
            captured["timeout"] = timeout
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
        self.assertEqual(captured["timeout"], 60)
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

    def test_clip_page_search_reuses_existing_page_index(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir) / "processed_books"
            index_dir = root / "_page_image_index"
            index_dir.mkdir(parents=True)
            (index_dir / "manifest.json").write_text(json.dumps({
                "kind": "page_image_clip_index",
                "row_count": 2,
                "fingerprint": "fp-1",
            }), encoding="utf-8")
            (index_dir / "metadata.jsonl").write_text("\n".join([
                json.dumps({
                    "doc_id": "book-a",
                    "book_name": "Book A",
                    "chapter_path": "Functions",
                    "page_no": 12,
                    "printed_page_no": "10",
                    "section_title": "Monotonicity",
                    "source_page_image": "pages/p012.png",
                    "text": "function monotonicity page",
                }, ensure_ascii=False),
                json.dumps({
                    "doc_id": "book-b",
                    "book_name": "Book B",
                    "chapter_path": "Vectors",
                    "page_no": 33,
                    "printed_page_no": "31",
                    "section_title": "Vector product",
                    "source_page_image": "pages/p033.png",
                    "text": "space vector page",
                }, ensure_ascii=False),
            ]), encoding="utf-8")
            import numpy as np

            np.save(index_dir / "page_embeddings.npy", np.array([
                [1.0, 0.0, 0.0],
                [0.0, 1.0, 0.0],
            ], dtype=np.float32))
            settings = WorkerSettings.from_environment(env={
                "MATH_AGENT_PROCESSED_BOOKS_ROOT": str(root),
                "MATH_AGENT_LOCAL_CLIP_MODEL_PATH": temp_dir,
            })
            service = EmbeddingService(settings, local_clip_backend=StubLocalClipBackend())

            result = service.search_page_images(texts="monotonicity", limit=2)

            self.assertEqual(result.provider, "local_clip")
            self.assertEqual([hit.doc_id for hit in result.hits], ["book-a", "book-b"])
            self.assertGreater(result.hits[0].score, result.hits[1].score)
            self.assertEqual(result.hits[0].source_page_image, "pages/p012.png")

    def test_clip_page_search_can_filter_doc_ids(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir) / "processed_books"
            index_dir = root / "_page_image_index"
            index_dir.mkdir(parents=True)
            (index_dir / "manifest.json").write_text(json.dumps({
                "kind": "page_image_clip_index",
                "row_count": 2,
                "fingerprint": "fp-2",
            }), encoding="utf-8")
            (index_dir / "metadata.jsonl").write_text("\n".join([
                json.dumps({"doc_id": "book-a", "book_name": "Book A", "chapter_path": "Functions", "page_no": 12,
                            "printed_page_no": "10", "section_title": "Monotonicity", "source_page_image": "pages/p012.png",
                            "text": "function monotonicity page"}, ensure_ascii=False),
                json.dumps({"doc_id": "book-b", "book_name": "Book B", "chapter_path": "Vectors", "page_no": 33,
                            "printed_page_no": "31", "section_title": "Vector product", "source_page_image": "pages/p033.png",
                            "text": "space vector page"}, ensure_ascii=False),
            ]), encoding="utf-8")
            import numpy as np

            np.save(index_dir / "page_embeddings.npy", np.array([
                [1.0, 0.0, 0.0],
                [0.0, 1.0, 0.0],
            ], dtype=np.float32))
            settings = WorkerSettings.from_environment(env={
                "MATH_AGENT_PROCESSED_BOOKS_ROOT": str(root),
                "MATH_AGENT_LOCAL_CLIP_MODEL_PATH": temp_dir,
            })
            service = EmbeddingService(settings, local_clip_backend=StubLocalClipBackend())

            result = service.search_page_images(texts="monotonicity", limit=3, doc_ids=["book-b"])

            self.assertEqual(len(result.hits), 1)
            self.assertEqual(result.hits[0].doc_id, "book-b")

    def test_clip_page_search_handles_legacy_index_dimension_mismatch(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir) / "processed_books"
            index_dir = root / "_page_image_index"
            index_dir.mkdir(parents=True)
            (index_dir / "manifest.json").write_text(json.dumps({
                "kind": "page_image_clip_index",
                "row_count": 2,
                "fingerprint": "fp-3",
            }), encoding="utf-8")
            (index_dir / "metadata.jsonl").write_text("\n".join([
                json.dumps({"doc_id": "book-a", "book_name": "Book A", "chapter_path": "Functions", "page_no": 12,
                            "printed_page_no": "10", "section_title": "Monotonicity", "source_page_image": "pages/p012.png",
                            "text": "function monotonicity page"}, ensure_ascii=False),
                json.dumps({"doc_id": "book-b", "book_name": "Book B", "chapter_path": "Vectors", "page_no": 33,
                            "printed_page_no": "31", "section_title": "Vector product", "source_page_image": "pages/p033.png",
                            "text": "space vector page"}, ensure_ascii=False),
            ]), encoding="utf-8")
            import numpy as np

            np.save(index_dir / "page_embeddings.npy", np.array([
                [1.0, 0.0, 0.0, 5.0],
                [0.0, 1.0, 0.0, 5.0],
            ], dtype=np.float32))
            settings = WorkerSettings.from_environment(env={
                "MATH_AGENT_PROCESSED_BOOKS_ROOT": str(root),
                "MATH_AGENT_LOCAL_CLIP_MODEL_PATH": temp_dir,
            })
            service = EmbeddingService(settings, local_clip_backend=StubLocalClipBackend())

            result = service.search_page_images(texts="monotonicity", limit=2)

            self.assertEqual(result.hits[0].doc_id, "book-a")

    def test_clip_page_search_response_keeps_public_page_metadata(self):
        response = clip_page_search_response(ClipPageSearchResult(
            model="local-clip",
            provider="local_clip",
            hits=[
                ClipPageSearchHit(
                    score=0.98,
                    doc_id="book-a",
                    book_name="Book A",
                    chapter_path="Functions",
                    page_no=12,
                    printed_page_no="10",
                    section_title="Monotonicity",
                    source_page_image="pages/p012.png",
                    text="function monotonicity page",
                )
            ],
        ))

        self.assertEqual(response["object"], "clip.page_search")
        self.assertEqual(response["hits"][0]["docId"], "book-a")
        self.assertEqual(response["hits"][0]["sourcePageImage"], "pages/p012.png")


if __name__ == "__main__":
    unittest.main()
