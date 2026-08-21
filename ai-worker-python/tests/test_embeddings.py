import json
from pathlib import Path
import tempfile
import unittest

from app.embeddings import (
    ClipPageSearchResult,
    ClipPageSearchHit,
    EmbeddingConfigurationError,
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

    def test_retrieval_readiness_requires_embedding_and_reranker_probes(self):
        settings = WorkerSettings.from_environment(env={})
        calls = []
        embedding_backend = type("EmbeddingBackend", (), {
            "verify_gpu_readiness": lambda self: calls.append("embedding"),
            "status": lambda self: {"status": "ready"},
        })()
        rerank_backend = type("RerankBackend", (), {
            "verify_gpu_readiness": lambda self: calls.append("reranker"),
            "status": lambda self: {"status": "ready"},
        })()
        service = EmbeddingService(
            settings,
            local_clip_backend=StubLocalClipBackend(),
            local_text_embedding_backend=embedding_backend,
            local_rerank_backend=rerank_backend,
        )

        self.assertFalse(service.is_retrieval_ready())
        service.initialize_retrieval_models()

        self.assertTrue(service.is_retrieval_ready())
        self.assertEqual(calls, ["embedding", "reranker"])
        service.initialize_retrieval_models()
        self.assertEqual(calls, ["embedding", "reranker"])

    def test_retrieval_readiness_does_not_mark_ready_when_reranker_probe_fails(self):
        settings = WorkerSettings.from_environment(env={})
        embedding_backend = type("EmbeddingBackend", (), {
            "verify_gpu_readiness": lambda self: None,
            "status": lambda self: {"status": "ready"},
        })()
        rerank_backend = type("RerankBackend", (), {
            "verify_gpu_readiness": lambda self: (_ for _ in ()).throw(
                EmbeddingConfigurationError("configured CUDA device is unavailable")
            ),
            "status": lambda self: {"status": "ready"},
        })()
        service = EmbeddingService(
            settings,
            local_clip_backend=StubLocalClipBackend(),
            local_text_embedding_backend=embedding_backend,
            local_rerank_backend=rerank_backend,
        )

        with self.assertRaisesRegex(EmbeddingConfigurationError, "CUDA device is unavailable"):
            service.initialize_retrieval_models()

        self.assertFalse(service.is_retrieval_ready())

    def test_cuda_requirement_rejects_cpu_device_before_model_load(self):
        fake_torch = type("FakeTorch", (), {
            "device": staticmethod(lambda value: type("Device", (), {"type": value, "index": None})()),
        })()

        from app.embeddings import require_cuda_device

        with self.assertRaisesRegex(EmbeddingConfigurationError, "must be CUDA"):
            require_cuda_device(fake_torch, "cpu")

    def test_fails_when_local_bge_is_not_configured(self):
        settings = WorkerSettings.from_environment(env={
            "MATH_AGENT_EMBEDDING_PROVIDER_ORDER": "dashscope",
            "DASHSCOPE_API_KEY": "must-not-be-used",
            "MATH_AGENT_LOCAL_TEXT_EMBEDDING_MODEL_PATH": "Z:\\missing\\local-bge",
        })
        service = EmbeddingService(settings)

        with self.assertRaisesRegex(
            EmbeddingConfigurationError,
            "LOCAL_TEXT_EMBEDDING_MODEL_PATH|local text embedding model path|torch and transformers",
        ):
            service.embed("function monotonicity")

    def test_status_never_advertises_remote_embedding_provider(self):
        settings = WorkerSettings.from_environment(env={
            "MATH_AGENT_EMBEDDING_PROVIDER_ORDER": "dashscope",
            "DASHSCOPE_API_KEY": "dashscope-key",
        })
        service = EmbeddingService(settings)

        status = service.status()

        self.assertEqual(status["levels"]["textEmbedding"]["dimension"], 512)
        self.assertEqual(status["levels"]["clipImageEmbedding"]["dimension"], 512)
        self.assertEqual(status["levels"]["textEmbedding"]["providers"], ["local_bge_embedding"])
        self.assertNotIn("dashscope", status["providers"])
        self.assertNotIn("dashscope-key", json.dumps(status))

    def test_remote_key_and_model_cannot_trigger_http_embedding(self):
        settings = WorkerSettings.from_environment(env={
            "MATH_AGENT_EMBEDDING_PROVIDER_ORDER": "dashscope",
            "DASHSCOPE_API_KEY": "dashscope-key",
        })
        calls = []
        backend = type("LocalBgeStub", (), {
            "embed_text": lambda self, texts, dimensions=None: calls.append((texts, dimensions)) or type(
                "Result", (), {"provider": "local_bge_embedding", "vectors": [[1.0]], "model": "local-bge", "prompt_tokens": 1}
            )(),
            "status": lambda self: {"status": "ready"},
        })()
        service = EmbeddingService(settings, local_text_embedding_backend=backend)

        result = service.embed(["function monotonicity"], model="text-embedding-v4")

        self.assertEqual(result.provider, "local_bge_embedding")
        self.assertEqual(calls, [(["function monotonicity"], None)])

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
