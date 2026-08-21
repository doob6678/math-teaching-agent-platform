from __future__ import annotations

from dataclasses import dataclass
import base64
import importlib.util
from io import BytesIO
import json
import math
import os
from pathlib import Path
import threading
import time
from typing import Iterable
from urllib.parse import urlparse

from app.settings import WorkerSettings

os.environ.setdefault("KMP_DUPLICATE_LIB_OK", "TRUE")


class EmbeddingConfigurationError(RuntimeError):
    pass


class EmbeddingProviderError(RuntimeError):
    pass


RETRIEVAL_READINESS_PROBE = "retrieval readiness"
DEFAULT_RETRIEVAL_READINESS_TIMEOUT_SECONDS = 120.0


def require_cuda_device(torch, configured_device: str):
    """Verifies that a configured CUDA device can execute a tensor operation before loading a retrieval model."""
    try:
        device = torch.device(configured_device)
    except Exception as exc:
        raise EmbeddingConfigurationError("configured retrieval device is invalid") from exc
    if device.type != "cuda":
        raise EmbeddingConfigurationError("configured retrieval device must be CUDA")
    if not torch.cuda.is_available():
        raise EmbeddingConfigurationError("configured CUDA device is unavailable")
    device_count = torch.cuda.device_count()
    if device.index is not None and (device.index < 0 or device.index >= device_count):
        raise EmbeddingConfigurationError("configured CUDA device is unavailable")
    try:
        probe = torch.ones(1, device=device)
        result = probe + probe
        if result.device.type != "cuda":
            raise EmbeddingConfigurationError("configured CUDA device did not execute the retrieval probe")
        torch.cuda.synchronize(device)
    except EmbeddingConfigurationError:
        raise
    except Exception as exc:
        raise EmbeddingConfigurationError("configured CUDA device did not execute the retrieval probe") from exc
    return device


def verify_model_cuda(torch, model, configured_device: str, model_name: str) -> None:
    """Rejects a loader that silently placed a configured retrieval model on CPU or another GPU."""
    expected_device = torch.device(configured_device)
    try:
        model_device = getattr(model, "device", None)
        if model_device is None:
            model_device = next(model.parameters()).device
        actual_device = torch.device(model_device)
    except Exception as exc:
        raise EmbeddingConfigurationError(f"{model_name} CUDA placement could not be verified") from exc
    if actual_device.type != "cuda":
        raise EmbeddingConfigurationError(f"{model_name} is not loaded on the configured CUDA device")
    if expected_device.index is not None and actual_device.index != expected_device.index:
        raise EmbeddingConfigurationError(f"{model_name} is not loaded on the configured CUDA device")


@dataclass(frozen=True)
class EmbeddingResult:
    model: str
    provider: str
    vectors: list[list[float]]
    prompt_tokens: int


@dataclass(frozen=True)
class ClipSimilarityResult:
    model: str
    provider: str
    text_vectors: list[list[float]]
    image_vectors: list[list[float]]
    similarities: list[list[float]]


@dataclass(frozen=True)
class ClipPageSearchHit:
    score: float
    doc_id: str
    book_name: str
    chapter_path: str
    page_no: int
    printed_page_no: str
    section_title: str
    source_page_image: str
    text: str


@dataclass(frozen=True)
class ClipPageSearchResult:
    model: str
    provider: str
    hits: list[ClipPageSearchHit]


@dataclass(frozen=True)
class TextPageSearchHit:
    """A public textbook page admitted by the BGE text-vector index."""

    score: float
    chunk_id: str
    section_id: str
    source_chunk_id: str
    doc_id: str
    book_name: str
    chapter_path: str
    page_no: int
    printed_page_no: str
    section_title: str
    source_page_image: str
    text: str


@dataclass(frozen=True)
class TextPageSearchResult:
    """Keeps the text-index response distinct from CLIP image retrieval in audit data."""

    model: str
    provider: str
    hits: list[TextPageSearchHit]


@dataclass(frozen=True)
class RerankResult:
    model: str
    provider: str
    scores: list[float]


@dataclass(frozen=True)
class LoadedPageImageIndex:
    processed_books_root: str
    index_dir: str
    fingerprint: str
    metadata: list[dict[str, object]]
    embeddings: object


@dataclass(frozen=True)
class LoadedPageTextIndex:
    """Immutable BGE page index loaded once per manifest fingerprint.

    The index intentionally lives beside processed_books rather than in Milvus. Teacher-resource vectors and
    textbook pages have different update lifecycles, and mixing BGE textbook vectors into the existing CLIP-backed
    512-d collection would silently corrupt its distance semantics.
    """

    processed_books_root: str
    index_dir: str
    fingerprint: str
    metadata: list[dict[str, object]]
    embeddings: object


class LocalBertVocabTokenizer:
    def __init__(self, vocab_file: str):
        self.vocab = {
            token: index
            for index, token in enumerate(Path(vocab_file).read_text(encoding="utf-8").splitlines())
            if token
        }
        self.pad_token_id = self.vocab.get("[PAD]", 0)
        self.unk_token_id = self.vocab.get("[UNK]", 1)
        self.cls_token_id = self.vocab.get("[CLS]", self.unk_token_id)
        self.sep_token_id = self.vocab.get("[SEP]", self.unk_token_id)

    def __call__(
        self,
        texts: list[str],
        padding: str,
        truncation: bool,
        max_length: int,
        return_tensors: str,
    ) -> dict[str, object]:
        if return_tensors != "pt":
            raise ValueError("LocalBertVocabTokenizer only supports return_tensors='pt'")
        rows = [self._encode(text, max_length, truncation) for text in texts]
        if padding == "max_length":
            rows = [row + [self.pad_token_id] * max(0, max_length - len(row)) for row in rows]
        else:
            width = max(len(row) for row in rows)
            rows = [row + [self.pad_token_id] * (width - len(row)) for row in rows]
        import torch

        return {"input_ids": torch.tensor(rows, dtype=torch.long)}

    def _encode(self, text: str, max_length: int, truncation: bool) -> list[int]:
        token_ids = [self.cls_token_id]
        for token in self._tokens(text):
            token_ids.append(self.vocab.get(token, self.unk_token_id))
        token_ids.append(self.sep_token_id)
        if truncation and len(token_ids) > max_length:
            token_ids = token_ids[: max_length - 1] + [self.sep_token_id]
        return token_ids

    def _tokens(self, text: str) -> list[str]:
        tokens: list[str] = []
        for char in text.lower():
            if char.isspace():
                continue
            if char in self.vocab:
                tokens.append(char)
            else:
                tokens.append("[UNK]")
        return tokens


class LocalClipBackend:
    def __init__(self, settings: WorkerSettings):
        self.settings = settings
        self._model = None
        self._processor = None
        self._tokenizer = None
        self._modelscope_model = None
        self._direct_text_model = None
        self._direct_text_tokenizer = None
        self._direct_text_projection = None
        self._direct_image_model = None
        self._direct_image_resolution = None
        self._torch = None
        self._image_module = None

    def status(self) -> dict[str, object]:
        if not self.settings.local_clip_model_path:
            return {
                "provider": "local_clip",
                "status": "configuration_error",
                "reason": "No local CLIP model path was configured or auto-detected",
                "modelPathConfigured": False,
                "dimension": self.settings.local_clip_dimension,
                "device": self.settings.local_clip_device,
            }
        model_path = self.settings.local_clip_model_path
        is_modelscope_model = self._is_modelscope_model(model_path)
        try:
            text_status = self._text_dependency_status(model_path, is_modelscope_model)
            image_status = self._image_dependency_status(model_path, is_modelscope_model)
        except EmbeddingConfigurationError as exc:
            return {
                "provider": "local_clip",
                "status": "configuration_error",
                "reason": str(exc),
                "modelPathConfigured": True,
                "dimension": self.settings.local_clip_dimension,
                "device": self.settings.local_clip_device,
            }
        overall_status = "ready" if text_status["status"] == "ready" else "configuration_error"
        return {
            "provider": "local_clip",
            "status": overall_status,
            "modelPathConfigured": True,
            "modelPath": self.settings.local_clip_model_path,
            "dimension": self.settings.local_clip_dimension,
            "device": self.settings.local_clip_device,
            "textEmbedding": text_status,
            "imageEmbedding": image_status,
        }

    def embed_text(self, texts: list[str], dimensions: int | None = None) -> EmbeddingResult:
        if not texts:
            raise ValueError("input must contain at least one non-empty text")
        self._load_text()
        expected = dimensions or self.settings.local_clip_dimension
        try:
            torch = self._torch
            assert torch is not None
            with torch.no_grad():
                if self._direct_text_model is not None:
                    text_tensor = self._direct_modelscope_text_tensor(texts).to(self.settings.local_clip_device)
                    attention_mask = text_tensor.ne(self._direct_text_tokenizer.pad_token_id).to(
                        self.settings.local_clip_device
                    )
                    output = self._direct_text_model(input_ids=text_tensor, attention_mask=attention_mask)
                    features = output.last_hidden_state[:, 0, :] @ self._direct_text_projection
                elif self._modelscope_model is not None:
                    text_tensor = self._modelscope_text_tensor(texts).to(self.settings.local_clip_device)
                    output = self._modelscope_model.forward({"text": text_tensor})
                    features = output["text_embedding"]
                else:
                    tokens = self._tokenizer(texts, padding=True, truncation=True, return_tensors="pt")
                    tokens = {key: value.to(self.settings.local_clip_device) for key, value in tokens.items()}
                    features = self._model.get_text_features(**tokens)
                vectors = fit_vectors_to_dimension(normalize_tensor(features).cpu().tolist(), expected)
        except Exception as exc:
            raise EmbeddingProviderError(f"local CLIP text embedding failed: {exc}") from exc
        validate_dimensions(vectors, expected, "local CLIP text")
        return EmbeddingResult(
            model=self.settings.local_clip_model_path or "local_clip",
            provider="local_clip",
            vectors=vectors,
            prompt_tokens=sum(rough_token_count(text) for text in texts),
        )

    def embed_images(self, images: list[str], dimensions: int | None = None) -> EmbeddingResult:
        if not images:
            raise ValueError("input must contain at least one image")
        self._load()
        expected = dimensions or self.settings.local_clip_dimension
        try:
            torch = self._torch
            assert torch is not None
            pil_images = [self._load_image(source) for source in images]
            with torch.no_grad():
                if self._modelscope_model is not None:
                    image_tensor = self._modelscope_image_tensor(pil_images).to(self.settings.local_clip_device)
                    output = self._modelscope_model.forward({"img": image_tensor})
                    features = output["img_embedding"]
                elif self._direct_image_model is not None:
                    image_tensor = self._direct_image_tensor(pil_images).to(self.settings.local_clip_device)
                    features = self._direct_image_model(image_tensor)
                else:
                    inputs = self._processor(images=pil_images, return_tensors="pt")
                    inputs = {key: value.to(self.settings.local_clip_device) for key, value in inputs.items()}
                    features = self._model.get_image_features(**inputs)
                vectors = fit_vectors_to_dimension(normalize_tensor(features).cpu().tolist(), expected)
        except Exception as exc:
            raise EmbeddingProviderError(f"local CLIP image embedding failed: {exc}") from exc
        validate_dimensions(vectors, expected, "local CLIP image")
        return EmbeddingResult(
            model=self.settings.local_clip_model_path or "local_clip",
            provider="local_clip",
            vectors=vectors,
            prompt_tokens=0,
        )

    def similarity(self, texts: list[str], images: list[str]) -> ClipSimilarityResult:
        text_result = self.embed_text(texts)
        image_result = self.embed_images(images)
        similarities = [
            [cosine_similarity(text_vector, image_vector) for image_vector in image_result.vectors]
            for text_vector in text_result.vectors
        ]
        return ClipSimilarityResult(
            model=text_result.model,
            provider="local_clip",
            text_vectors=text_result.vectors,
            image_vectors=image_result.vectors,
            similarities=similarities,
        )

    def _load(self) -> None:
        if self._model is not None or self._modelscope_model is not None or self._direct_image_model is not None:
            return
        self._load_full_clip()

    def _load_text(self) -> None:
        if self._model is not None or self._modelscope_model is not None or self._direct_text_model is not None:
            return
        model_path = self.settings.local_clip_model_path
        if not model_path:
            raise EmbeddingConfigurationError("MATH_AGENT_LOCAL_CLIP_MODEL_PATH is required for local_clip")
        if not Path(model_path).exists():
            raise EmbeddingConfigurationError(f"local CLIP model path does not exist: {model_path}")
        is_modelscope_model = self._is_modelscope_model(model_path)
        self._load_common_dependencies()
        if is_modelscope_model:
            self._load_modelscope_direct_text(model_path)
            return
        self._load_huggingface_clip(model_path)

    def _load_full_clip(self) -> None:
        model_path = self.settings.local_clip_model_path
        if not model_path:
            raise EmbeddingConfigurationError("MATH_AGENT_LOCAL_CLIP_MODEL_PATH is required for local_clip")
        if not Path(model_path).exists():
            raise EmbeddingConfigurationError(f"local CLIP model path does not exist: {model_path}")
        is_modelscope_model = self._is_modelscope_model(model_path)
        self._load_common_dependencies()
        if is_modelscope_model:
            self._load_modelscope_direct_image(model_path)
        else:
            self._load_huggingface_clip(model_path)

    def _load_common_dependencies(self) -> None:
        try:
            import torch
            from PIL import Image
        except Exception as exc:
            raise EmbeddingConfigurationError("torch and Pillow are required for local_clip") from exc
        self._torch = torch
        self._image_module = Image

    def _text_dependency_status(self, model_path: str, is_modelscope_model: bool) -> dict[str, object]:
        self._load_common_dependencies()
        if is_modelscope_model:
            self._require_python_modules(
                ("transformers",),
                "transformers is required for direct local ModelScope CLIP text embedding",
            )
            required_files = ("pytorch_model.bin", "text_model_config.json", "vocab.txt")
            missing = [name for name in required_files if not (Path(model_path) / name).exists()]
            if missing:
                raise EmbeddingConfigurationError("local ModelScope CLIP text files are missing: " + ", ".join(missing))
            return {"status": "ready", "backend": "modelscope_text_direct", "requiresAddict": False}
        self._require_python_modules(
            ("transformers",),
            "transformers is required for this local HuggingFace CLIP directory",
        )
        return {"status": "ready", "backend": "huggingface_clip"}

    def _image_dependency_status(self, model_path: str, is_modelscope_model: bool) -> dict[str, object]:
        self._load_common_dependencies()
        if is_modelscope_model:
            required_files = ("pytorch_model.bin", "vision_model_config.json")
            missing = [name for name in required_files if not (Path(model_path) / name).exists()]
            if missing:
                return {
                    "status": "configuration_error",
                    "reason": "local ModelScope CLIP vision files are missing: " + ", ".join(missing),
                    "backend": "modelscope_visual_direct",
                }
            return {"status": "ready", "backend": "modelscope_visual_direct", "requiresAddict": False}
        try:
            self._require_python_modules(
                ("transformers",),
                "transformers is required for this local HuggingFace CLIP directory",
            )
        except EmbeddingConfigurationError as exc:
            return {"status": "configuration_error", "reason": str(exc), "backend": "huggingface_clip"}
        return {"status": "ready", "backend": "huggingface_clip"}

    def _load_modelscope_direct_text(self, model_path: str) -> None:
        self._require_python_modules(
            ("transformers",),
            "transformers is required for direct local ModelScope CLIP text embedding",
        )
        try:
            from transformers import BertConfig, BertModel

            torch = self._torch
            assert torch is not None
            config_path = Path(model_path) / "text_model_config.json"
            with config_path.open("r", encoding="utf-8") as handle:
                text_config = json.load(handle)
            config = BertConfig(
                vocab_size=text_config["vocab_size"],
                hidden_size=text_config["text_hidden_size"],
                num_hidden_layers=text_config["text_num_hidden_layers"],
                num_attention_heads=text_config["text_num_attention_heads"],
                intermediate_size=text_config["text_intermediate_size"],
                hidden_act=text_config["text_hidden_act"],
                hidden_dropout_prob=text_config["text_hidden_dropout_prob"],
                attention_probs_dropout_prob=text_config["text_attention_probs_dropout_prob"],
                max_position_embeddings=text_config["text_max_position_embeddings"],
                type_vocab_size=text_config["text_type_vocab_size"],
                initializer_range=text_config["text_initializer_range"],
                layer_norm_eps=1e-12,
            )
            model = BertModel(config)
            checkpoint = torch.load(str(Path(model_path) / "pytorch_model.bin"), map_location="cpu")
            state_dict = checkpoint.get("state_dict", checkpoint)
            bert_state = {
                key.removeprefix("module.bert."): value
                for key, value in state_dict.items()
                if key.startswith("module.bert.")
            }
            missing, unexpected = model.load_state_dict(bert_state, strict=False)
            if unexpected:
                raise EmbeddingProviderError("unexpected BERT weights in local CLIP checkpoint: " + ", ".join(unexpected))
            non_pooler_missing = [key for key in missing if not key.startswith("pooler.")]
            if non_pooler_missing:
                raise EmbeddingProviderError("missing BERT weights in local CLIP checkpoint: " + ", ".join(non_pooler_missing))
            projection = state_dict.get("module.text_projection")
            if projection is None:
                projection = state_dict.get("text_projection")
            if projection is None:
                raise EmbeddingProviderError("local CLIP checkpoint is missing text_projection")
            tokenizer = LocalBertVocabTokenizer(str(Path(model_path) / "vocab.txt"))
            model.to(self.settings.local_clip_device)
            projection = projection.to(self.settings.local_clip_device)
            model.eval()
            self._direct_text_model = model
            self._direct_text_projection = projection
            self._direct_text_tokenizer = tokenizer
        except (EmbeddingConfigurationError, EmbeddingProviderError):
            raise
        except Exception as exc:
            raise EmbeddingProviderError(f"direct local ModelScope CLIP text load failed: {exc}") from exc

    def _load_modelscope_direct_image(self, model_path: str) -> None:
        try:
            torch = self._torch
            assert torch is not None
            config_path = Path(model_path) / "vision_model_config.json"
            with config_path.open("r", encoding="utf-8") as handle:
                vision_config = json.load(handle)
            model = build_direct_vision_transformer(torch, vision_config)
            checkpoint = torch.load(str(Path(model_path) / "pytorch_model.bin"), map_location="cpu")
            state_dict = checkpoint.get("state_dict", checkpoint)
            visual_state = {
                key.removeprefix("module.visual."): value
                for key, value in state_dict.items()
                if key.startswith("module.visual.")
            }
            if not visual_state:
                raise EmbeddingProviderError("local CLIP checkpoint is missing visual weights")
            missing, unexpected = model.load_state_dict(visual_state, strict=True)
            if missing:
                raise EmbeddingProviderError("missing visual weights in local CLIP checkpoint: " + ", ".join(missing))
            if unexpected:
                raise EmbeddingProviderError("unexpected visual weights in local CLIP checkpoint: " + ", ".join(unexpected))
            model.to(self.settings.local_clip_device)
            model.eval()
            self._direct_image_model = model
            self._direct_image_resolution = int(vision_config["image_resolution"])
        except (EmbeddingConfigurationError, EmbeddingProviderError):
            raise
        except Exception as exc:
            raise EmbeddingProviderError(f"direct local ModelScope CLIP image load failed: {exc}") from exc

    def _load_huggingface_clip(self, model_path: str) -> None:
        self._require_python_modules(
            ("transformers",),
            "transformers is required for this local HuggingFace CLIP directory",
        )
        try:
            from transformers import CLIPModel, CLIPProcessor, CLIPTokenizer

            self._tokenizer = CLIPTokenizer.from_pretrained(model_path, local_files_only=True)
            self._processor = CLIPProcessor.from_pretrained(model_path, local_files_only=True)
            self._model = CLIPModel.from_pretrained(model_path, local_files_only=True)
            self._model.to(self.settings.local_clip_device)
            self._model.eval()
        except Exception as exc:
            raise EmbeddingProviderError(f"local CLIP model load failed: {exc}") from exc

    @staticmethod
    def _is_modelscope_model(model_path: str) -> bool:
        path = Path(model_path)
        return (path / "configuration.json").exists() and (path / "pytorch_model.bin").exists()

    @staticmethod
    def _require_python_modules(module_names: tuple[str, ...], message: str) -> None:
        missing = [module_name for module_name in module_names if importlib.util.find_spec(module_name) is None]
        if missing:
            raise EmbeddingConfigurationError(message + ": missing " + ", ".join(missing))

    def _modelscope_text_tensor(self, texts: list[str]):
        torch = self._torch
        assert torch is not None
        tokenizer = self._modelscope_model.tokenizer
        context_length = 52
        rows = []
        for text in texts:
            token_ids = (
                [tokenizer.vocab["[CLS]"]]
                + tokenizer.convert_tokens_to_ids(tokenizer.tokenize(text))[: context_length - 2]
                + [tokenizer.vocab["[SEP]"]]
            )
            rows.append(token_ids)
        result = torch.zeros(len(rows), context_length, dtype=torch.long)
        for index, token_ids in enumerate(rows):
            result[index, : len(token_ids)] = torch.tensor(token_ids, dtype=torch.long)
        return result

    def _direct_modelscope_text_tensor(self, texts: list[str]):
        torch = self._torch
        assert torch is not None
        tokenizer = self._direct_text_tokenizer
        context_length = 52
        encoded = tokenizer(
            texts,
            padding="max_length",
            truncation=True,
            max_length=context_length,
            return_tensors="pt",
        )
        return encoded["input_ids"].to(dtype=torch.long)

    def _modelscope_image_tensor(self, images: list):
        torch = self._torch
        assert torch is not None
        resolution = int(self._modelscope_model.model_info.get("image_resolution", 224))
        mean = torch.tensor([0.48145466, 0.4578275, 0.40821073], dtype=torch.float32).view(3, 1, 1)
        std = torch.tensor([0.26862954, 0.26130258, 0.27577711], dtype=torch.float32).view(3, 1, 1)
        tensors = []
        for image in images:
            resized = image.convert("RGB").resize((resolution, resolution))
            array = torch.frombuffer(bytearray(resized.tobytes()), dtype=torch.uint8)
            array = array.view(resolution, resolution, 3).permute(2, 0, 1).float().div(255.0)
            tensors.append((array - mean) / std)
        return torch.stack(tensors, dim=0)

    def _direct_image_tensor(self, images: list):
        torch = self._torch
        assert torch is not None
        if self._direct_image_resolution is None:
            raise EmbeddingConfigurationError("direct local CLIP image model is not loaded")
        resolution = self._direct_image_resolution
        mean = torch.tensor([0.48145466, 0.4578275, 0.40821073], dtype=torch.float32).view(3, 1, 1)
        std = torch.tensor([0.26862954, 0.26130258, 0.27577711], dtype=torch.float32).view(3, 1, 1)
        tensors = []
        for image in images:
            resized = image.convert("RGB").resize((resolution, resolution))
            array = torch.frombuffer(bytearray(resized.tobytes()), dtype=torch.uint8)
            array = array.view(resolution, resolution, 3).permute(2, 0, 1).float().div(255.0)
            tensors.append((array - mean) / std)
        return torch.stack(tensors, dim=0)

    def _load_image(self, source: str):
        value = source.strip()
        if not value:
            raise ValueError("image source cannot be empty")
        image_bytes = decode_allowed_image_source(value)
        image_module = self._image_module
        assert image_module is not None
        return image_module.open(BytesIO(image_bytes)).convert("RGB")


class LocalTextEmbeddingBackend:
    """Loads a local BGE sentence embedding model for semantic text recall."""

    def __init__(self, settings: WorkerSettings):
        self.settings = settings
        self._model = None

    def status(self) -> dict[str, object]:
        model_path = self.settings.local_text_embedding_model_path
        if not model_path:
            return {
                "provider": "local_bge_embedding",
                "status": "configuration_error",
                "reason": "No complete local BGE embedding model was detected",
                "modelPathConfigured": False,
                "device": self.settings.local_text_embedding_device,
            }
        try:
            self._import_dependencies()
        except EmbeddingConfigurationError as exc:
            return {
                "provider": "local_bge_embedding",
                "status": "configuration_error",
                "reason": str(exc),
                "modelPathConfigured": True,
                "modelPath": model_path,
                "device": self.settings.local_text_embedding_device,
            }
        return {
            "provider": "local_bge_embedding",
            "status": "ready",
            "modelPathConfigured": True,
            "modelPath": model_path,
            "device": self.settings.local_text_embedding_device,
        }

    def embed_text(self, texts: list[str], dimensions: int | None = None) -> EmbeddingResult:
        normalized_texts = normalize_inputs(texts)
        if not normalized_texts:
            raise ValueError("input must contain at least one non-empty text")
        self._load()
        try:
            matrix = self._model.encode(
                normalized_texts,
                batch_size=len(normalized_texts),
                convert_to_numpy=True,
                normalize_embeddings=True,
                show_progress_bar=False,
            )
            vectors = [[float(value) for value in row] for row in matrix.tolist()]
        except Exception as exc:
            raise EmbeddingProviderError(f"local BGE embedding failed: {exc}") from exc
        expected_dimension = dimensions or self.settings.embedding_dimensions
        validate_dimensions(vectors, expected_dimension, "local BGE embedding")
        return EmbeddingResult(
            model=self.settings.local_text_embedding_model_path or "local_bge_embedding",
            provider="local_bge_embedding",
            vectors=vectors,
            prompt_tokens=sum(len(value) for value in normalized_texts),
        )

    def verify_gpu_readiness(self) -> None:
        """Loads the configured local BGE model and runs one bounded CUDA-backed encoding before readiness."""
        self._load()
        try:
            self.embed_text([RETRIEVAL_READINESS_PROBE], self.settings.embedding_dimensions)
            import torch

            torch.cuda.synchronize(torch.device(self.settings.local_text_embedding_device))
        except (EmbeddingConfigurationError, EmbeddingProviderError):
            raise
        except Exception as exc:
            raise EmbeddingConfigurationError("local BGE embedding readiness probe failed") from exc

    def _load(self) -> None:
        if self._model is not None:
            return
        self._import_dependencies()
        model_path = self.settings.local_text_embedding_model_path
        if not model_path:
            raise EmbeddingConfigurationError("MATH_AGENT_LOCAL_TEXT_EMBEDDING_MODEL_PATH requires complete model weights")
        try:
            import torch
            from sentence_transformers import SentenceTransformer

            device = require_cuda_device(torch, self.settings.local_text_embedding_device)
            model = SentenceTransformer(model_path, device=str(device), local_files_only=True)
            verify_model_cuda(torch, model, str(device), "local BGE embedding model")
            self._model = model
        except EmbeddingConfigurationError:
            raise
        except Exception as exc:
            raise EmbeddingConfigurationError(f"failed to load local BGE embedding model: {exc}") from exc

    @staticmethod
    def _import_dependencies() -> None:
        if importlib.util.find_spec("sentence_transformers") is None:
            raise EmbeddingConfigurationError("sentence-transformers is required for local BGE embedding")


class LocalRerankBackend:
    def __init__(self, settings: WorkerSettings):
        self.settings = settings
        self._model = None
        self._tokenizer = None
        self._torch = None

    def status(self) -> dict[str, object]:
        if not self.settings.local_rerank_model_path:
            return {
                "provider": "local_bge_reranker",
                "status": "configuration_error",
                "reason": "No local rerank model path was configured or auto-detected",
                "modelPathConfigured": False,
                "device": self.settings.local_rerank_device,
            }
        try:
            self._import_dependencies()
        except EmbeddingConfigurationError as exc:
            return {
                "provider": "local_bge_reranker",
                "status": "configuration_error",
                "reason": str(exc),
                "modelPathConfigured": True,
                "modelPath": self.settings.local_rerank_model_path,
                "device": self.settings.local_rerank_device,
            }
        return {
            "provider": "local_bge_reranker",
            "status": "ready",
            "modelPathConfigured": True,
            "modelPath": self.settings.local_rerank_model_path,
            "device": self.settings.local_rerank_device,
        }

    def rerank(self, query: str, documents: list[str]) -> RerankResult:
        normalized_query = text_or_default(query, "")
        normalized_documents = normalize_inputs(documents)
        if not normalized_query:
            raise ValueError("query must contain non-empty text")
        if not normalized_documents:
            raise ValueError("documents must contain at least one non-empty text")
        self._load()
        torch = self._torch
        assert torch is not None
        try:
            encoded = self._tokenizer(
                [normalized_query] * len(normalized_documents),
                normalized_documents,
                padding=True,
                truncation=True,
                max_length=self.settings.local_rerank_max_tokens,
                return_tensors="pt",
            )
            encoded = {key: value.to(self.settings.local_rerank_device) for key, value in encoded.items()}
            with torch.no_grad():
                logits = self._model(**encoded).logits
            if logits.device.type != "cuda":
                raise EmbeddingProviderError("local rerank did not execute on CUDA")
            if getattr(logits, "ndim", 0) == 2 and int(logits.shape[1]) > 1:
                values = logits[:, -1]
            else:
                values = logits.reshape(-1)
            scores = [float(score) for score in values.detach().cpu().tolist()]
        except Exception as exc:
            raise EmbeddingProviderError(f"local rerank failed: {exc}") from exc
        if len(scores) != len(normalized_documents):
            raise EmbeddingProviderError(
                f"local rerank returned {len(scores)} scores for {len(normalized_documents)} documents"
            )
        return RerankResult(
            model=self.settings.local_rerank_model_path or "local_bge_reranker",
            provider="local_bge_reranker",
            scores=scores,
        )

    def verify_gpu_readiness(self) -> None:
        """Loads the configured local reranker and verifies one real scoring pass remains on CUDA."""
        self._load()
        try:
            self.rerank(RETRIEVAL_READINESS_PROBE, [RETRIEVAL_READINESS_PROBE])
            torch = self._torch
            assert torch is not None
            torch.cuda.synchronize(torch.device(self.settings.local_rerank_device))
        except (EmbeddingConfigurationError, EmbeddingProviderError):
            raise
        except Exception as exc:
            raise EmbeddingConfigurationError("local reranker readiness probe failed") from exc

    def _load(self) -> None:
        if self._model is not None and self._tokenizer is not None:
            return
        self._import_dependencies()
        model_path = self.settings.local_rerank_model_path
        if not model_path:
            raise EmbeddingConfigurationError("MATH_AGENT_LOCAL_RERANK_MODEL_PATH is required for local rerank")
        try:
            from transformers import AutoModelForSequenceClassification, AutoTokenizer

            torch = self._torch
            assert torch is not None
            device = require_cuda_device(torch, self.settings.local_rerank_device)
            tokenizer = AutoTokenizer.from_pretrained(model_path, local_files_only=True)
            model = AutoModelForSequenceClassification.from_pretrained(model_path, local_files_only=True)
            model.eval()
            model.to(device)
            verify_model_cuda(torch, model, str(device), "local reranker model")
            self._tokenizer = tokenizer
            self._model = model
        except EmbeddingConfigurationError:
            raise
        except Exception as exc:
            raise EmbeddingConfigurationError("failed to load local reranker model") from exc

    def _import_dependencies(self) -> None:
        if self._torch is not None:
            return
        try:
            import torch
            import transformers  # noqa: F401
        except Exception as exc:
            raise EmbeddingConfigurationError("torch and transformers are required for local rerank") from exc
        self._torch = torch


def build_direct_vision_transformer(torch, config: dict):
    nn = torch.nn

    class QuickGELU(nn.Module):
        def forward(self, value):
            return value * torch.sigmoid(1.702 * value)

    class ResidualAttentionBlock(nn.Module):
        def __init__(self, width: int, heads: int):
            super().__init__()
            self.attn = nn.MultiheadAttention(width, heads)
            self.ln_1 = nn.LayerNorm(width)
            self.mlp = nn.Sequential()
            self.mlp.add_module("c_fc", nn.Linear(width, width * 4))
            self.mlp.add_module("gelu", QuickGELU())
            self.mlp.add_module("c_proj", nn.Linear(width * 4, width))
            self.ln_2 = nn.LayerNorm(width)

        def forward(self, value):
            attended, _ = self.attn(self.ln_1(value), self.ln_1(value), self.ln_1(value), need_weights=False)
            value = value + attended
            return value + self.mlp(self.ln_2(value))

    class Transformer(nn.Module):
        def __init__(self, width: int, layers: int, heads: int):
            super().__init__()
            self.resblocks = nn.ModuleList([ResidualAttentionBlock(width, heads) for _ in range(layers)])

        def forward(self, value):
            for block in self.resblocks:
                value = block(value)
            return value

    class DirectVisionTransformer(nn.Module):
        def __init__(self, image_resolution: int, patch_size: int, width: int, layers: int, output_dim: int):
            super().__init__()
            self.conv1 = nn.Conv2d(in_channels=3, out_channels=width, kernel_size=patch_size, stride=patch_size, bias=False)
            grid_size = image_resolution // patch_size
            scale = width ** -0.5
            self.class_embedding = nn.Parameter(scale * torch.randn(width))
            self.positional_embedding = nn.Parameter(scale * torch.randn(grid_size * grid_size + 1, width))
            self.ln_pre = nn.LayerNorm(width)
            self.transformer = Transformer(width, layers, width // 64)
            self.ln_post = nn.LayerNorm(width)
            self.proj = nn.Parameter(scale * torch.randn(width, output_dim))

        def forward(self, image):
            value = self.conv1(image)
            value = value.reshape(value.shape[0], value.shape[1], -1).permute(0, 2, 1)
            cls = self.class_embedding.to(value.dtype).unsqueeze(0).unsqueeze(0).expand(value.shape[0], 1, -1)
            value = torch.cat([cls, value], dim=1)
            value = value + self.positional_embedding.to(value.dtype)
            value = self.ln_pre(value)
            value = value.permute(1, 0, 2)
            value = self.transformer(value)
            value = value.permute(1, 0, 2)
            value = self.ln_post(value[:, 0, :])
            return value @ self.proj

    return DirectVisionTransformer(
        image_resolution=int(config["image_resolution"]),
        patch_size=int(config["vision_patch_size"]),
        width=int(config["vision_width"]),
        layers=int(config["vision_layers"]),
        output_dim=int(config["embed_dim"]),
    )


class EmbeddingService:
    def __init__(
        self,
        settings: WorkerSettings,
        local_clip_backend: LocalClipBackend | None = None,
        local_rerank_backend: LocalRerankBackend | None = None,
        local_text_embedding_backend: LocalTextEmbeddingBackend | None = None,
    ):
        self.settings = settings
        self.local_clip_backend = local_clip_backend or LocalClipBackend(settings)
        self.local_rerank_backend = local_rerank_backend or LocalRerankBackend(settings)
        self.local_text_embedding_backend = local_text_embedding_backend or LocalTextEmbeddingBackend(settings)
        self._page_image_index: LoadedPageImageIndex | None = None
        self._page_text_index: LoadedPageTextIndex | None = None
        self._retrieval_ready = False
        self._retrieval_readiness_lock = threading.Lock()

    def initialize_retrieval_models(self) -> None:
        """Makes readiness contingent on local embedding and reranking models completing real CUDA inference."""
        with self._retrieval_readiness_lock:
            if self._retrieval_ready:
                return
            self.local_text_embedding_backend.verify_gpu_readiness()
            self.local_rerank_backend.verify_gpu_readiness()
            self._retrieval_ready = True

    def is_retrieval_ready(self) -> bool:
        return self._retrieval_ready

    def status(self) -> dict[str, object]:
        local_clip_status = self.local_clip_backend.status()
        local_rerank_status = self.local_rerank_backend.status()
        local_text_embedding_status = self.local_text_embedding_backend.status()
        local_text_status = local_clip_status.get("textEmbedding", {}).get("status", local_clip_status.get("status"))
        local_image_status = local_clip_status.get("imageEmbedding", {}).get("status", local_clip_status.get("status"))
        return {
            "status": self._overall_status(),
            "levels": {
                "textEmbedding": {
                    "providers": list(self.settings.embedding_provider_order),
                    "dimension": self.settings.embedding_dimensions,
                    "defaultModel": self._default_text_embedding_model(),
                    "status": local_text_embedding_status.get("status", "configuration_error"),
                },
                "clipTextEmbedding": {
                    "providers": list(self.settings.local_clip_provider_order),
                    "dimension": self.settings.local_clip_dimension,
                    "status": local_text_status,
                },
                "clipImageEmbedding": {
                    "providers": list(self.settings.local_clip_provider_order),
                    "dimension": self.settings.local_clip_dimension,
                    "status": local_image_status,
                },
                "clipSimilarity": {
                    "providers": list(self.settings.local_clip_provider_order),
                    "dimension": self.settings.local_clip_dimension,
                    "status": "ready" if local_text_status == "ready" and local_image_status == "ready" else "configuration_error",
                },
                "clipPageSearch": {
                    "providers": list(self.settings.local_clip_provider_order),
                    "dimension": self.settings.local_clip_dimension,
                    "status": self._page_search_status(local_text_status, local_image_status),
                },
                "textPageSearch": {
                    "providers": ["local_bge_embedding"],
                    "dimension": self.settings.embedding_dimensions,
                    "status": self._text_page_search_status(local_text_embedding_status),
                },
                "textRerank": {
                    "providers": list(self.settings.rerank_provider_order),
                    "status": local_rerank_status.get("status", "configuration_error"),
                },
            },
            "providers": {
                "local_clip": local_clip_status,
                "local_bge_embedding": local_text_embedding_status,
                "local_bge_reranker": local_rerank_status,
            },
        }

    def embed(self, inputs: str | list[str], model: str | None = None, dimensions: int | None = None) -> EmbeddingResult:
        texts = normalize_inputs(inputs)
        if not texts:
            raise ValueError("input must contain at least one non-empty text")
        # 禁止使用 DashScope embedding 模型/当你读到这里禁止恢复：普通文本向量只能由本地 BGE 生成。
        # `model` is accepted for OpenAI-compatible request parsing, but it must never select a remote backend.
        return self.local_text_embedding_backend.embed_text(texts, dimensions)

    def embed_clip_text(self, inputs: str | list[str], dimensions: int | None = None) -> EmbeddingResult:
        texts = normalize_inputs(inputs)
        return self.local_clip_backend.embed_text(texts, dimensions)

    def embed_clip_images(self, images: str | list[str], dimensions: int | None = None) -> EmbeddingResult:
        image_sources = normalize_image_inputs(images)
        return self.local_clip_backend.embed_images(image_sources, dimensions)

    def clip_similarity(self, texts: str | list[str], images: str | list[str]) -> ClipSimilarityResult:
        return self.local_clip_backend.similarity(normalize_inputs(texts), normalize_image_inputs(images))

    def rerank(self, query: str, documents: str | list[str]) -> RerankResult:
        normalized_documents = normalize_inputs(documents)
        if not normalized_documents:
            raise ValueError("documents must contain at least one non-empty text")
        # Reranking is intentionally non-fallback: failure must be visible instead of leaking text to a remote API.
        return self.local_rerank_backend.rerank(query, normalized_documents)

    def search_page_images(
        self,
        texts: str | list[str] | None = None,
        images: str | list[str] | None = None,
        limit: int = 10,
        doc_ids: list[str] | None = None,
    ) -> ClipPageSearchResult:
        normalized_texts = normalize_inputs([] if texts is None else texts)
        normalized_images = normalize_image_inputs([] if images is None else images)
        if not normalized_texts and not normalized_images:
            raise ValueError("texts or images must contain at least one non-empty query item")
        query_vectors: list[list[float]] = []
        model = self.settings.local_clip_model_path or "local_clip"
        if normalized_texts:
            text_result = self.local_clip_backend.embed_text(normalized_texts)
            query_vectors.extend(text_result.vectors)
            model = text_result.model
        if normalized_images:
            image_result = self.local_clip_backend.embed_images(normalized_images)
            query_vectors.extend(image_result.vectors)
            model = image_result.model
        index = self._load_page_image_index()
        metadata = index.metadata
        embeddings = index.embeddings
        import numpy as np

        candidate_indexes = [
            idx for idx, item in enumerate(metadata)
            if not doc_ids or str(item.get("doc_id", "")).strip() in doc_ids
        ]
        if not candidate_indexes:
            return ClipPageSearchResult(model=model, provider="local_clip", hits=[])
        embedding_rows = embeddings[np.array(candidate_indexes)]
        query_matrix = np.array(query_vectors, dtype=np.float32)
        if embedding_rows.shape[1] != query_matrix.shape[1]:
            # Historical page-image indexes may have been built with an older local CLIP export dimension. Trim both
            # sides to the common prefix and renormalize so the worker can reuse the existing index instead of forcing
            # a full rebuild every time the query-side model dimension changes.
            target_dim = min(int(embedding_rows.shape[1]), int(query_matrix.shape[1]))
            if target_dim <= 0:
                raise EmbeddingProviderError("page image index and query embeddings do not share a usable dimension")
            embedding_rows = normalize_numpy_rows(embedding_rows[:, :target_dim])
            query_matrix = normalize_numpy_rows(query_matrix[:, :target_dim])
        score_matrix = embedding_rows @ query_matrix.T
        best_scores = score_matrix.max(axis=1)
        top_limit = max(1, min(50, int(limit)))
        ranked_indexes = np.argsort(-best_scores)[:top_limit]
        hits = []
        for rank_idx in ranked_indexes.tolist():
            metadata_index = candidate_indexes[int(rank_idx)]
            item = metadata[metadata_index]
            hits.append(ClipPageSearchHit(
                score=float(best_scores[rank_idx]),
                doc_id=text_or_default(item.get("doc_id"), ""),
                book_name=text_or_default(item.get("book_name"), ""),
                chapter_path=text_or_default(item.get("chapter_path"), ""),
                page_no=int(item.get("page_no", 0) or 0),
                printed_page_no=text_or_default(item.get("printed_page_no"), ""),
                section_title=text_or_default(item.get("section_title"), ""),
                source_page_image=text_or_default(item.get("source_page_image"), ""),
                text=text_or_default(item.get("text"), ""),
            ))
        return ClipPageSearchResult(model=model, provider="local_clip", hits=hits)

    def search_page_text(
        self,
        query: str,
        limit: int = 10,
        doc_ids: list[str] | None = None,
    ) -> TextPageSearchResult:
        """Retrieves real textbook pages from a BGE index without re-encoding corpus text.

        Query-time work is exactly one BGE encoding plus an in-memory matrix product. The builder owns all page
        encodings and fingerprints their source, so this path stays inside the online latency budget and an updated
        textbook cannot be confused with a stale vector file.
        """
        normalized_query = text_or_default(query, "").strip()
        if not normalized_query:
            raise ValueError("query must contain non-empty text")
        query_result = self.local_text_embedding_backend.embed_text([normalized_query])
        index = self._load_page_text_index()
        import numpy as np

        candidate_indexes = [
            idx for idx, item in enumerate(index.metadata)
            if not doc_ids or text_or_default(item.get("doc_id"), "") in doc_ids
        ]
        if not candidate_indexes:
            return TextPageSearchResult(
                model=query_result.model,
                provider=query_result.provider,
                hits=[],
            )
        embedding_rows = index.embeddings[np.array(candidate_indexes)]
        query_vector = np.asarray(query_result.vectors[0], dtype=np.float32)
        if embedding_rows.shape[1] != query_vector.shape[0]:
            raise EmbeddingProviderError(
                "text page index dimension does not match the configured BGE embedding model; rebuild the index"
            )
        best_scores = embedding_rows @ query_vector
        requested_limit = max(1, int(limit))
        # The section corpus can contain a heading, figure caption and prose
        # block with one shared section id.  Returning all siblings here would
        # spend the fixed page-recall budget on one concept before Java can
        # choose its richest evidence block.  Keep the highest vector match per
        # stable section and let later distinct sections compete downstream.
        ranked_indexes = np.argsort(-best_scores)
        hits = []
        seen_sections: set[str] = set()
        for rank_idx in ranked_indexes.tolist():
            metadata_index = candidate_indexes[int(rank_idx)]
            item = index.metadata[metadata_index]
            doc_id = text_or_default(item.get("doc_id"), "")
            section_id = text_or_default(item.get("section_id"), text_or_default(item.get("chunk_id"), ""))
            section_key = doc_id + "#" + section_id
            if not section_id or section_key in seen_sections:
                continue
            seen_sections.add(section_key)
            hits.append(TextPageSearchHit(
                score=float(best_scores[rank_idx]),
                chunk_id=text_or_default(item.get("chunk_id"), ""),
                section_id=section_id,
                source_chunk_id=text_or_default(item.get("source_chunk_id"), ""),
                doc_id=doc_id,
                book_name=text_or_default(item.get("book_name"), ""),
                chapter_path=text_or_default(item.get("chapter_path"), ""),
                page_no=int(item.get("page_no", 0) or 0),
                printed_page_no=text_or_default(item.get("printed_page_no"), ""),
                section_title=text_or_default(item.get("section_title"), ""),
                source_page_image=text_or_default(item.get("source_page_image"), ""),
                text=text_or_default(item.get("text"), ""),
            ))
            if len(hits) >= requested_limit:
                break
        return TextPageSearchResult(model=query_result.model, provider=query_result.provider, hits=hits)

    def _page_search_status(self, text_status: object, image_status: object) -> str:
        if text_status != "ready" and image_status != "ready":
            return "configuration_error"
        try:
            self._page_image_index_root()
            return "ready"
        except EmbeddingConfigurationError:
            return "configuration_error"

    def _text_page_search_status(self, embedding_status: dict[str, object]) -> str:
        if embedding_status.get("status") != "ready":
            return "configuration_error"
        try:
            self._page_text_index_root()
            return "ready"
        except EmbeddingConfigurationError:
            return "configuration_error"

    def _load_page_image_index(self) -> LoadedPageImageIndex:
        import numpy as np

        index_dir = self._page_image_index_root()
        manifest_path = index_dir / "manifest.json"
        metadata_path = index_dir / "metadata.jsonl"
        embeddings_path = index_dir / "page_embeddings.npy"
        try:
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        except Exception as exc:
            raise EmbeddingConfigurationError(f"page image index manifest is unreadable: {manifest_path}") from exc
        fingerprint = text_or_default(manifest.get("fingerprint"), "")
        cache = self._page_image_index
        if cache is not None and cache.index_dir == str(index_dir) and cache.fingerprint == fingerprint:
            return cache
        try:
            metadata = [
                json.loads(line)
                for line in metadata_path.read_text(encoding="utf-8").splitlines()
                if line.strip()
            ]
        except Exception as exc:
            raise EmbeddingConfigurationError(f"page image index metadata is unreadable: {metadata_path}") from exc
        try:
            embeddings = np.load(embeddings_path)
        except Exception as exc:
            raise EmbeddingConfigurationError(f"page image embeddings are unreadable: {embeddings_path}") from exc
        if len(metadata) != int(manifest.get("row_count", len(metadata))):
            raise EmbeddingConfigurationError("page image index row_count does not match metadata rows")
        if getattr(embeddings, "shape", (0, 0))[0] != len(metadata):
            raise EmbeddingConfigurationError("page image index embedding rows do not match metadata rows")
        embeddings = normalize_numpy_rows(embeddings)
        loaded = LoadedPageImageIndex(
            processed_books_root=text_or_default(self.settings.processed_books_root, ""),
            index_dir=str(index_dir),
            fingerprint=fingerprint,
            metadata=metadata,
            embeddings=embeddings,
        )
        self._page_image_index = loaded
        return loaded

    def _page_image_index_root(self) -> Path:
        if not self.settings.processed_books_root:
            raise EmbeddingConfigurationError("MATH_AGENT_PROCESSED_BOOKS_ROOT is required for clip page search")
        root = Path(self.settings.processed_books_root).expanduser().resolve()
        index_dir = root / "_page_image_index"
        if not index_dir.is_dir():
            raise EmbeddingConfigurationError(f"page image index directory does not exist: {index_dir}")
        return index_dir

    def _load_page_text_index(self) -> LoadedPageTextIndex:
        import numpy as np

        index_dir = self._page_text_index_root()
        manifest_path = index_dir / "manifest.json"
        metadata_path = index_dir / "metadata.jsonl"
        embeddings_path = index_dir / "page_embeddings.npy"
        try:
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        except Exception as exc:
            raise EmbeddingConfigurationError(f"page text index manifest is unreadable: {manifest_path}") from exc
        if text_or_default(manifest.get("kind"), "") != "page_text_bge_index":
            raise EmbeddingConfigurationError("page text index manifest has an unexpected kind")
        fingerprint = text_or_default(manifest.get("fingerprint"), "")
        cache = self._page_text_index
        if cache is not None and cache.index_dir == str(index_dir) and cache.fingerprint == fingerprint:
            return cache
        try:
            metadata = [
                json.loads(line)
                for line in metadata_path.read_text(encoding="utf-8").splitlines()
                if line.strip()
            ]
            embeddings = np.load(embeddings_path)
        except Exception as exc:
            raise EmbeddingConfigurationError(f"page text index data is unreadable: {index_dir}") from exc
        expected_rows = int(manifest.get("row_count", -1))
        if expected_rows != len(metadata) or embeddings.shape[0] != len(metadata):
            raise EmbeddingConfigurationError("page text index row_count does not match metadata and embeddings")
        expected_dimension = int(manifest.get("dimension", 0))
        if expected_dimension <= 0 or embeddings.shape[1] != expected_dimension:
            raise EmbeddingConfigurationError("page text index dimension does not match its manifest")
        loaded = LoadedPageTextIndex(
            processed_books_root=text_or_default(self.settings.processed_books_root, ""),
            index_dir=str(index_dir),
            fingerprint=fingerprint,
            metadata=metadata,
            embeddings=normalize_numpy_rows(embeddings),
        )
        self._page_text_index = loaded
        return loaded

    def _page_text_index_root(self) -> Path:
        if not self.settings.processed_books_root:
            raise EmbeddingConfigurationError("MATH_AGENT_PROCESSED_BOOKS_ROOT is required for text page search")
        index_dir = Path(self.settings.processed_books_root).expanduser().resolve() / "_page_text_index"
        if not index_dir.is_dir():
            raise EmbeddingConfigurationError(f"page text index directory does not exist: {index_dir}")
        return index_dir

    def _overall_status(self) -> str:
        statuses = [
            self.local_text_embedding_backend.status().get("status") == "ready",
            self.local_clip_backend.status().get("status") == "ready",
        ]
        if statuses and all(statuses):
            return "ready"
        if any(statuses):
            return "degraded"
        return "configuration_error"

    def _default_text_embedding_model(self) -> str:
        return self.settings.local_text_embedding_model_path or "local_bge_embedding"


def normalize_inputs(inputs: str | Iterable[str]) -> list[str]:
    if isinstance(inputs, str):
        values = [inputs]
    else:
        values = list(inputs)
    return [value.strip() for value in values if isinstance(value, str) and value.strip()]


def normalize_image_inputs(inputs: str | Iterable[str]) -> list[str]:
    if isinstance(inputs, str):
        values = [inputs]
    else:
        values = list(inputs)
    return [value.strip() for value in values if isinstance(value, str) and value.strip()]


def decode_allowed_image_source(source: str) -> bytes:
    value = source.strip()
    if not value:
        raise ValueError("image source cannot be empty")
    lowered = value.lower()
    if lowered.startswith("http://") or lowered.startswith("https://"):
        raise ValueError("remote image URLs are not accepted by the local CLIP worker")
    if lowered.startswith("data:image/"):
        if "," not in value:
            raise ValueError("image data URL must contain base64 payload")
        header, encoded = value.split(",", 1)
        if ";base64" not in header.lower():
            raise ValueError("image data URL must be base64 encoded")
        return decode_base64_image(encoded)
    try:
        return decode_base64_image(value)
    except ValueError as exc:
        if looks_like_local_path(value):
            raise ValueError("local image paths are not accepted by the local CLIP worker") from exc
        raise ValueError("image must be a base64 data URL or raw base64 image bytes") from exc


def decode_base64_image(encoded: str) -> bytes:
    try:
        return base64.b64decode(encoded, validate=True)
    except Exception as exc:
        raise ValueError("invalid base64 image payload") from exc


def looks_like_local_path(value: str) -> bool:
    parsed = urlparse(value)
    if parsed.scheme and parsed.scheme not in ("http", "https", "data"):
        return True
    if value.startswith(("/", "\\")):
        return True
    if len(value) >= 3 and value[1] == ":" and value[2] in ("\\", "/"):
        return True
    return "\\" in value or "/" in value


def normalize_tensor(features):
    return features / features.norm(dim=-1, keepdim=True).clamp(min=1e-12)


def normalize_numpy_rows(matrix):
    import numpy as np

    rows = np.asarray(matrix, dtype=np.float32)
    norms = np.linalg.norm(rows, axis=1, keepdims=True)
    norms = np.clip(norms, 1e-12, None)
    return rows / norms


def validate_dimensions(vectors: list[list[float]], expected: int, label: str) -> None:
    if vectors and len(vectors[0]) != expected:
        raise EmbeddingProviderError(f"{label} dimension mismatch: expected {expected}, got {len(vectors[0])}")


def fit_vectors_to_dimension(vectors: list[list[float]], expected: int) -> list[list[float]]:
    fitted = []
    for vector in vectors:
        if len(vector) < expected:
            raise EmbeddingProviderError(f"local CLIP dimension mismatch: expected {expected}, got {len(vector)}")
        if len(vector) == expected:
            fitted.append(vector)
            continue
        trimmed = vector[:expected]
        norm = math.sqrt(sum(value * value for value in trimmed))
        if norm <= 1e-12:
            fitted.append(trimmed)
        else:
            fitted.append([value / norm for value in trimmed])
    return fitted


def rough_token_count(text: str) -> int:
    return max(1, len(text) // 4)


def cosine_similarity(left: list[float], right: list[float]) -> float:
    numerator = sum(a * b for a, b in zip(left, right))
    left_norm = math.sqrt(sum(a * a for a in left))
    right_norm = math.sqrt(sum(b * b for b in right))
    if left_norm == 0 or right_norm == 0:
        return 0.0
    return numerator / (left_norm * right_norm)


def text_or_default(value: object, default: str) -> str:
    if value is None:
        return default
    text = str(value).strip()
    return text if text else default


def openai_embedding_response(result: EmbeddingResult) -> dict:
    return {
        "object": "list",
        "model": result.model,
        "provider": result.provider,
        "created": int(time.time()),
        "data": [
            {
                "object": "embedding",
                "index": index,
                "embedding": vector,
            }
            for index, vector in enumerate(result.vectors)
        ],
        "usage": {
            "prompt_tokens": result.prompt_tokens,
            "total_tokens": result.prompt_tokens,
        },
    }


def clip_similarity_response(result: ClipSimilarityResult) -> dict:
    return {
        "object": "clip.similarity",
        "model": result.model,
        "provider": result.provider,
        "created": int(time.time()),
        "similarities": result.similarities,
        "textEmbeddings": result.text_vectors,
        "imageEmbeddings": result.image_vectors,
    }


def clip_page_search_response(result: ClipPageSearchResult) -> dict:
    return {
        "object": "clip.page_search",
        "model": result.model,
        "provider": result.provider,
        "created": int(time.time()),
        "hits": [
            {
                "score": hit.score,
                "docId": hit.doc_id,
                "bookName": hit.book_name,
                "chapterPath": hit.chapter_path,
                "pageNo": hit.page_no,
                "printedPageNo": hit.printed_page_no,
                "sectionTitle": hit.section_title,
                "sourcePageImage": hit.source_page_image,
                "text": hit.text,
            }
            for hit in result.hits
        ],
    }


def text_page_search_response(result: TextPageSearchResult) -> dict:
    """Serializes BGE page retrieval with the same public fields as the CLIP route.

    Keeping the payload shape aligned lets Java choose the right coarse-recall route without leaking worker-specific
    index details to agents, while `object` and `provider` preserve truthful audit attribution.
    """
    return {
        "object": "text.page_search",
        "model": result.model,
        "provider": result.provider,
        "hits": [
            {
                "score": hit.score,
                "chunkId": hit.chunk_id,
                "sectionId": hit.section_id,
                "sourceChunkId": hit.source_chunk_id,
                "docId": hit.doc_id,
                "bookName": hit.book_name,
                "chapterPath": hit.chapter_path,
                "pageNo": hit.page_no,
                "printedPageNo": hit.printed_page_no,
                "sectionTitle": hit.section_title,
                "sourcePageImage": hit.source_page_image,
                "text": hit.text,
            }
            for hit in result.hits
        ],
    }


def rerank_response(result: RerankResult) -> dict:
    return {
        "object": "rerank.result",
        "model": result.model,
        "provider": result.provider,
        "created": int(time.time()),
        "data": [
            {
                "index": index,
                "score": score,
            }
            for index, score in enumerate(result.scores)
        ],
    }
