from __future__ import annotations

from functools import lru_cache
import secrets

try:
    from fastapi import Depends, FastAPI, Header, HTTPException
    from pydantic import BaseModel
except Exception as exc:  # pragma: no cover - import failure is explicit at service startup.
    raise RuntimeError("fastapi and pydantic are required to run the worker API") from exc

from app.embeddings import (
    EmbeddingProviderError,
    EmbeddingService,
    clip_page_search_response,
    clip_similarity_response,
    openai_embedding_response,
    rerank_response,
    text_page_search_response,
)
from app.health import health_response
from app.formula_recognition import FormulaRecognitionError, FormulaRecognitionService
from app.settings import WorkerSettings


class EmbeddingRequest(BaseModel):
    input: str | list[str]
    model: str | None = None
    dimensions: int | None = None


class ClipImageEmbeddingRequest(BaseModel):
    images: str | list[str]
    dimensions: int | None = None


class ClipSimilarityRequest(BaseModel):
    texts: str | list[str]
    images: str | list[str]


class ClipPageSearchRequest(BaseModel):
    texts: str | list[str] | None = None
    images: str | list[str] | None = None
    limit: int = 10
    docIds: list[str] | None = None


class TextPageSearchRequest(BaseModel):
    query: str
    limit: int = 10
    docIds: list[str] | None = None


class RerankRequest(BaseModel):
    query: str
    documents: list[str]


class FormulaRecognitionRequest(BaseModel):
    imageDataUrl: str
    mimeType: str


class FormulaPageBatchRequest(BaseModel):
    pages: list[FormulaRecognitionRequest]


app = FastAPI(title="math-agent-rag-worker")


@lru_cache(maxsize=1)
def embedding_service() -> EmbeddingService:
    return EmbeddingService(WorkerSettings.from_environment())


@lru_cache(maxsize=1)
def formula_recognition_service() -> FormulaRecognitionService:
    return FormulaRecognitionService(WorkerSettings.from_environment())


def require_worker_key(
    authorization: str | None = Header(default=None),
    x_worker_api_key: str | None = Header(default=None),
) -> None:
    settings = WorkerSettings.from_environment()
    expected = settings.worker_api_key
    if not expected:
        raise HTTPException(status_code=503, detail="MATH_AGENT_WORKER_API_KEY is required")
    presented = None
    if authorization and authorization.startswith("Bearer "):
        presented = authorization.removeprefix("Bearer ").strip()
    if not presented and x_worker_api_key:
        presented = x_worker_api_key.strip()
    if not presented or not secrets.compare_digest(presented, expected):
        raise HTTPException(status_code=401, detail="invalid worker API key")


@app.get("/health")
def health() -> dict[str, str]:
    return health_response()


@app.get("/v1/capabilities", dependencies=[Depends(require_worker_key)])
def capabilities() -> dict:
    return embedding_service().status()


@app.post("/v1/embeddings", dependencies=[Depends(require_worker_key)])
def embeddings(payload: EmbeddingRequest) -> dict:
    try:
        result = embedding_service().embed(payload.input, payload.model, payload.dimensions)
    except (ValueError, EmbeddingProviderError) as exc:
        raise HTTPException(status_code=503, detail=str(exc)) from exc
    return openai_embedding_response(result)


@app.post("/v1/clip/text-embeddings", dependencies=[Depends(require_worker_key)])
def clip_text_embeddings(payload: EmbeddingRequest) -> dict:
    try:
        result = embedding_service().embed_clip_text(payload.input, payload.dimensions)
    except (ValueError, EmbeddingProviderError) as exc:
        raise HTTPException(status_code=503, detail=str(exc)) from exc
    return openai_embedding_response(result)


@app.post("/v1/clip/image-embeddings", dependencies=[Depends(require_worker_key)])
def clip_image_embeddings(payload: ClipImageEmbeddingRequest) -> dict:
    try:
        result = embedding_service().embed_clip_images(payload.images, payload.dimensions)
    except (ValueError, EmbeddingProviderError) as exc:
        raise HTTPException(status_code=503, detail=str(exc)) from exc
    return openai_embedding_response(result)


@app.post("/v1/clip/similarity", dependencies=[Depends(require_worker_key)])
def clip_similarity(payload: ClipSimilarityRequest) -> dict:
    try:
        result = embedding_service().clip_similarity(payload.texts, payload.images)
    except (ValueError, EmbeddingProviderError) as exc:
        raise HTTPException(status_code=503, detail=str(exc)) from exc
    return clip_similarity_response(result)


@app.post("/v1/clip/page-search", dependencies=[Depends(require_worker_key)])
def clip_page_search(payload: ClipPageSearchRequest) -> dict:
    try:
        result = embedding_service().search_page_images(
            texts=payload.texts,
            images=payload.images,
            limit=payload.limit,
            doc_ids=payload.docIds,
        )
    except (ValueError, EmbeddingProviderError) as exc:
        raise HTTPException(status_code=503, detail=str(exc)) from exc
    return clip_page_search_response(result)


@app.post("/v1/text/page-search", dependencies=[Depends(require_worker_key)])
def text_page_search(payload: TextPageSearchRequest) -> dict:
    try:
        result = embedding_service().search_page_text(
            query=payload.query,
            limit=payload.limit,
            doc_ids=payload.docIds,
        )
    except (ValueError, EmbeddingProviderError) as exc:
        raise HTTPException(status_code=503, detail=str(exc)) from exc
    return text_page_search_response(result)


@app.post("/v1/rerank", dependencies=[Depends(require_worker_key)])
def rerank(payload: RerankRequest) -> dict:
    try:
        result = embedding_service().rerank(payload.query, payload.documents)
    except (ValueError, EmbeddingProviderError) as exc:
        raise HTTPException(status_code=503, detail=str(exc)) from exc
    return rerank_response(result)


@app.post("/v1/formula-recognition", dependencies=[Depends(require_worker_key)])
def formula_recognition(payload: FormulaRecognitionRequest) -> dict:
    """Recognizes one raster formula through the configured real visual model, never through a local heuristic."""
    try:
        result = formula_recognition_service().recognize(payload.imageDataUrl, payload.mimeType)
    except FormulaRecognitionError as exc:
        raise HTTPException(status_code=422, detail=str(exc)) from exc
    return {
        "object": "formula.recognition",
        "model": result.model,
        "data": {
            "status": result.status,
            "latex": result.latex,
            "plainText": result.plain_text,
            "confidence": result.confidence,
        },
    }


@app.post("/v1/formula-page-batch", dependencies=[Depends(require_worker_key)])
def formula_page_batch(payload: FormulaPageBatchRequest) -> dict:
    """Performs one real visual-model call over a page batch and returns formulas grouped by source page index."""
    try:
        data = formula_recognition_service().recognize_page_batch(
            [(page.imageDataUrl, page.mimeType) for page in payload.pages]
        )
    except FormulaRecognitionError as exc:
        raise HTTPException(status_code=422, detail=str(exc)) from exc
    return {"object": "formula.page_batch", "model": WorkerSettings.from_environment().formula_vision_model, "data": data}
