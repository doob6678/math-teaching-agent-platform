from __future__ import annotations

from dataclasses import dataclass
import base64
import importlib.util
from io import BytesIO
import json
import math
import os
from pathlib import Path
import time
from typing import Callable, Iterable
from urllib import request
from urllib.parse import urlparse

from app.settings import WorkerSettings

os.environ.setdefault("KMP_DUPLICATE_LIB_OK", "TRUE")


class EmbeddingConfigurationError(RuntimeError):
    pass


class EmbeddingProviderError(RuntimeError):
    pass


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
        opener: Callable[[request.Request, int], object] | None = None,
        local_clip_backend: LocalClipBackend | None = None,
    ):
        self.settings = settings
        self.opener = opener or request.urlopen
        self.local_clip_backend = local_clip_backend or LocalClipBackend(settings)

    def status(self) -> dict[str, object]:
        dashscope_status = "ready" if self.settings.dashscope_api_key else "configuration_error"
        local_clip_status = self.local_clip_backend.status()
        local_text_status = local_clip_status.get("textEmbedding", {}).get("status", local_clip_status.get("status"))
        local_image_status = local_clip_status.get("imageEmbedding", {}).get("status", local_clip_status.get("status"))
        return {
            "status": self._overall_status(),
            "levels": {
                "textEmbedding": {
                    "providers": list(self.settings.embedding_provider_order),
                    "dimension": self.settings.embedding_dimensions,
                    "defaultModel": self._default_text_embedding_model(),
                    "status": local_text_status if "local_clip" in self.settings.embedding_provider_order else dashscope_status,
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
            },
            "providers": {
                "dashscope": {
                    "status": dashscope_status,
                    "baseUrl": self.settings.dashscope_base_url,
                    "model": self.settings.dashscope_embedding_model,
                    "apiKeyConfigured": bool(self.settings.dashscope_api_key),
                },
                "local_clip": local_clip_status,
            },
        }

    def embed(self, inputs: str | list[str], model: str | None = None, dimensions: int | None = None) -> EmbeddingResult:
        texts = normalize_inputs(inputs)
        if not texts:
            raise ValueError("input must contain at least one non-empty text")
        errors: list[str] = []
        for provider in self.settings.embedding_provider_order:
            try:
                if provider == "local_clip":
                    return self.local_clip_backend.embed_text(texts, dimensions)
                if provider == "dashscope":
                    return self._embed_dashscope(texts, model, dimensions)
                errors.append(f"{provider}: unsupported provider")
            except (EmbeddingConfigurationError, EmbeddingProviderError) as exc:
                errors.append(f"{provider}: {exc}")
        raise EmbeddingProviderError("No real embedding provider succeeded: " + "; ".join(errors))

    def embed_clip_text(self, inputs: str | list[str], dimensions: int | None = None) -> EmbeddingResult:
        texts = normalize_inputs(inputs)
        return self.local_clip_backend.embed_text(texts, dimensions)

    def embed_clip_images(self, images: str | list[str], dimensions: int | None = None) -> EmbeddingResult:
        image_sources = normalize_image_inputs(images)
        return self.local_clip_backend.embed_images(image_sources, dimensions)

    def clip_similarity(self, texts: str | list[str], images: str | list[str]) -> ClipSimilarityResult:
        return self.local_clip_backend.similarity(normalize_inputs(texts), normalize_image_inputs(images))

    def _overall_status(self) -> str:
        statuses = []
        local_status = self.local_clip_backend.status().get("status")
        for provider in self.settings.embedding_provider_order:
            if provider == "local_clip":
                statuses.append(local_status == "ready")
            elif provider == "dashscope":
                statuses.append(bool(self.settings.dashscope_api_key))
            else:
                statuses.append(False)
        if statuses and all(statuses):
            return "ready"
        if any(statuses):
            return "degraded"
        return "configuration_error"

    def _default_text_embedding_model(self) -> str:
        first_provider = self.settings.embedding_provider_order[0] if self.settings.embedding_provider_order else ""
        if first_provider == "local_clip":
            return self.settings.local_clip_model_path or "local_clip"
        if first_provider == "dashscope":
            return self.settings.dashscope_embedding_model
        return first_provider or "configuration_error:no_embedding_provider"

    def _embed_dashscope(self, texts: list[str], model: str | None, dimensions: int | None) -> EmbeddingResult:
        api_key = self.settings.dashscope_api_key
        if not api_key:
            raise EmbeddingConfigurationError("DASHSCOPE_API_KEY is required for dashscope embeddings")
        selected_model = model or self.settings.dashscope_embedding_model
        payload = {
            "model": selected_model,
            "input": texts,
            "dimensions": dimensions or self.settings.embedding_dimensions,
        }
        endpoint = self.settings.dashscope_base_url.rstrip("/") + "/embeddings"
        req = request.Request(
            endpoint,
            data=json.dumps(payload).encode("utf-8"),
            headers={
                "Authorization": "Bearer " + api_key,
                "Content-Type": "application/json",
            },
            method="POST",
        )
        try:
            with self.opener(req, 60) as response:
                body = response.read().decode("utf-8")
        except Exception as exc:
            raise EmbeddingProviderError(f"DashScope embedding request failed: {exc}") from exc
        try:
            parsed = json.loads(body)
            vectors = [[float(value) for value in item["embedding"]] for item in parsed["data"]]
        except Exception as exc:
            raise EmbeddingProviderError("DashScope returned invalid embedding JSON") from exc
        if len(vectors) != len(texts):
            raise EmbeddingProviderError(f"DashScope returned {len(vectors)} vectors for {len(texts)} inputs")
        validate_dimensions(vectors, dimensions or self.settings.embedding_dimensions, "DashScope")
        return EmbeddingResult(
            model=parsed.get("model") or selected_model,
            provider="dashscope",
            vectors=vectors,
            prompt_tokens=int(parsed.get("usage", {}).get("prompt_tokens", 0)),
        )


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
