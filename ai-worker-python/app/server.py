from __future__ import annotations

from functools import lru_cache
import secrets
from concurrent.futures import Future, ThreadPoolExecutor
import os
import threading
import time

try:
    from fastapi import Depends, FastAPI, Header, HTTPException, Query
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
from app.agent_runtime import AgentRunRequest, AgentRuntime
from app.ai_run_runtime import AiRunRequest, AiRunRuntime
from app.handout_runtime import (
    DEFAULT_EVENT_PAGE_LIMIT,
    MAX_EVENT_PAGE_LIMIT,
    HandoutRunRequest,
    HandoutRuntime,
)
from app.teaching_draft_runtime import TeachingDraftRequest, TeachingDraftRuntime
from app.workload_runtime import (
    ImageTranscriptionRunRequest,
    IntentRunRequest,
    MigratedWorkloadRuntime,
    ProviderHealthRunRequest,
    StudentExplanationRunRequest,
)
from app.student_explanation_runtime import DurableStudentExplanationRuntime
from app.streaming_runtime import AgentStreamingRuntime
from app.tokenizer import count_texts
from fastapi.responses import StreamingResponse
import json


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


class TokenizeRequest(BaseModel):
    texts: list[str]
    model: str = ""


app = FastAPI(title="math-agent-rag-worker")
DEFAULT_HANDOUT_SSE_TIMEOUT_SECONDS = 900.0
MIN_HANDOUT_SSE_TIMEOUT_SECONDS = 60.0
HANDOUT_SSE_POLL_INTERVAL_SECONDS = 0.25

# The sync endpoint is used by the durable Java Worker.  SSE must not execute a second graph in the request thread,
# so it submits once and only reads the shared checkpoint event cursor until the same run reaches a terminal event.
_handout_executor = ThreadPoolExecutor(
    max_workers=max(1, int(os.getenv("MATH_AGENT_HANDOUT_SSE_WORKERS", "4"))),
    thread_name_prefix="handout-graph",
)
_handout_futures: dict[str, Future] = {}
_handout_futures_lock = threading.Lock()


@lru_cache(maxsize=1)
def embedding_service() -> EmbeddingService:
    return EmbeddingService(WorkerSettings.from_environment())


@lru_cache(maxsize=1)
def formula_recognition_service() -> FormulaRecognitionService:
    return FormulaRecognitionService(WorkerSettings.from_environment())


@lru_cache(maxsize=1)
def agent_runtime() -> AgentRuntime:
    """Keeps Python stateless: Java remains the only authority for tenant data and files."""
    return AgentRuntime()


@lru_cache(maxsize=1)
def ai_run_runtime() -> AiRunRuntime:
    """每个请求使用 Java 签发的受限 provider route 创建 generic AI runtime。"""
    return AiRunRuntime()


@lru_cache(maxsize=1)
def agent_streaming_runtime() -> AgentStreamingRuntime:
    """Owns the production SSE protocol; provider sockets remain open only for the active response."""
    return AgentStreamingRuntime()


@lru_cache(maxsize=1)
def handout_runtime() -> HandoutRuntime:
    """Keeps one connection-pooled LangGraph runtime and one durable checkpoint store per worker process."""
    return HandoutRuntime()


@lru_cache(maxsize=1)
def teaching_draft_runtime() -> TeachingDraftRuntime:
    """Keeps one provider connection pool for the legacy teaching-task draft contract."""
    return TeachingDraftRuntime()


@lru_cache(maxsize=1)
def migrated_workload_runtime() -> MigratedWorkloadRuntime:
    """统一承接已迁出 Java 的非讲义模型执行负载。"""
    return MigratedWorkloadRuntime()


@lru_cache(maxsize=1)
def durable_student_explanation_runtime() -> DurableStudentExplanationRuntime:
    """为学生讲解提供运行级幂等、终态缓存和有限事件重放。"""
    runtime = migrated_workload_runtime()
    return DurableStudentExplanationRuntime(runtime.explain_student_problem)


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


@app.post("/v1/tokenize", dependencies=[Depends(require_worker_key)])
def tokenize(payload: TokenizeRequest) -> dict:
    """Returns real tokenizer id counts for context admission, separate from provider usage."""
    try:
        counts, encoding = count_texts(payload.texts, payload.model)
    except (ImportError, ValueError) as exc:
        raise HTTPException(status_code=503, detail=f"tokenizer unavailable: {type(exc).__name__}") from exc
    return {"object": "token.count", "model": payload.model, "encoding": encoding, "counts": counts, "total": sum(counts)}


@app.post("/v1/agent-runs", dependencies=[Depends(require_worker_key)])
def agent_run(payload: AgentRunRequest) -> StreamingResponse:
    """Streams typed SSE events; usage is emitted only after the provider's final usage chunk is persisted."""
    def encoded_events():
        for item in agent_streaming_runtime().stream(payload):
            yield f"event: {item['event']}\ndata: {json.dumps(item['data'], ensure_ascii=False, separators=(',', ':'))}\n\n"
    return StreamingResponse(encoded_events(), media_type="text/event-stream", headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"})


@app.post("/v1/agent-runs/sync", dependencies=[Depends(require_worker_key)])
def agent_run_sync(payload: AgentRunRequest) -> dict:
    """Temporary compatibility adapter for callers that cannot consume SSE yet."""
    return agent_runtime().execute(payload).as_response()


@app.post("/v1/ai-runs/sync", dependencies=[Depends(require_worker_key)])
def ai_run_sync(payload: AiRunRequest) -> dict:
    """执行版本化通用 AI 协议，供 Java facade 投影已有公共 API。"""
    return ai_run_runtime().execute(payload).as_response()


@app.post("/v1/handout-runs/sync", dependencies=[Depends(require_worker_key)])
def handout_run_sync(payload: HandoutRunRequest) -> dict:
    """Executes the complete handout graph in one Java-to-Python request."""
    return handout_runtime().execute(payload).model_dump(by_alias=True, exclude_none=True)


@app.post("/v1/teaching-drafts/sync", dependencies=[Depends(require_worker_key)])
def teaching_draft_sync(payload: TeachingDraftRequest) -> dict:
    """Runs one bounded teaching draft; Java remains responsible for evidence and publication authorization."""
    return teaching_draft_runtime().execute(payload)


@app.post("/v1/learning-intents/sync", dependencies=[Depends(require_worker_key)])
def learning_intent_sync(payload: IntentRunRequest) -> dict:
    """执行受限学习意图分类，不接收学生身份或知识库访问权限。"""
    return migrated_workload_runtime().recognize_intent(payload)


@app.post("/v1/student-explanations/sync", dependencies=[Depends(require_worker_key)])
def student_explanation_sync(payload: StudentExplanationRunRequest) -> dict:
    """执行学生解释卡片生成，引用只能来自 Java 已授权证据。"""
    return durable_student_explanation_runtime().execute(payload)


@app.get("/v1/student-explanations/{run_id}/events", dependencies=[Depends(require_worker_key)])
def student_explanation_events(
    run_id: str,
    after_id: int = Query(default=0, alias="afterId", ge=0),
    limit: int = Query(default=50, ge=1, le=100),
) -> dict:
    """按游标读取学生讲解运行事件，重连只读取已有事件而不触发模型调用。"""
    rows = durable_student_explanation_runtime().event_page(run_id, after_id, limit)
    return {
        "runId": run_id,
        "events": [{"eventId": event_id, **event} for event_id, event in rows],
        "nextAfterId": rows[-1][0] if rows else after_id,
    }


@app.post("/v1/image-transcriptions/sync", dependencies=[Depends(require_worker_key)])
def image_transcription_sync(payload: ImageTranscriptionRunRequest) -> dict:
    """转写 Java 已授权且内联传输的图片，不接受本地路径。"""
    return migrated_workload_runtime().transcribe_image(payload)


@app.post("/v1/provider-health/sync", dependencies=[Depends(require_worker_key)])
def provider_health_sync(payload: ProviderHealthRunRequest) -> dict:
    """返回脱敏 provider 探测结果，provider 调用仅在 Python 内发生。"""
    return migrated_workload_runtime().provider_health(payload)


def _submit_handout_once(payload: HandoutRunRequest) -> Future:
    """Deduplicates an SSE-triggered graph so browser reconnects cannot spend another provider budget."""
    with _handout_futures_lock:
        current = _handout_futures.get(payload.run_id)
        if current is not None and not current.done():
            return current
        future = _handout_executor.submit(handout_runtime().execute, payload)
        _handout_futures[payload.run_id] = future

    def remove_completed(done: Future) -> None:
        # Keep only active in-process de-duplication entries; durable MySQL remains the resume authority after this.
        with _handout_futures_lock:
            if _handout_futures.get(payload.run_id) is done:
                _handout_futures.pop(payload.run_id, None)

    # Register outside the lock because Future invokes this callback synchronously when the task already completed.
    future.add_done_callback(remove_completed)
    return future


@app.post("/v1/handout-runs", dependencies=[Depends(require_worker_key)])
def handout_run(payload: HandoutRunRequest) -> StreamingResponse:
    """Starts one graph in the background and streams durable event-store pages by cursor."""
    future = _submit_handout_once(payload)

    def encoded_events():
        runtime = handout_runtime()
        cursor = 0
        started_at = time.monotonic()
        stream_timeout = max(MIN_HANDOUT_SSE_TIMEOUT_SECONDS, float(os.getenv("MATH_AGENT_HANDOUT_SSE_TIMEOUT_SECONDS", str(DEFAULT_HANDOUT_SSE_TIMEOUT_SECONDS))))
        terminal_events = {"completed", "failed"}
        while time.monotonic() - started_at < stream_timeout:
            rows = runtime.event_page(payload.run_id, cursor, DEFAULT_EVENT_PAGE_LIMIT)
            for event_id, event in rows:
                cursor = event_id
                event_name = str(event.get("event", "progress"))
                data = {"runId": payload.run_id, "eventId": event_id, **event}
                yield "event: " + event_name + "\ndata: " + json.dumps(data, ensure_ascii=False, separators=(",", ":")) + "\n\n"
                if event_name in terminal_events:
                    return
            if future.done():
                # Runtime normally persists a terminal event before completing. If a process-level failure happens
                # before that write, surface a bounded operational error rather than leaving the browser hanging.
                try:
                    future.result()
                except HTTPException as exc:
                    yield "event: error\ndata: " + json.dumps({"runId": payload.run_id, "status": exc.status_code, "message": exc.detail}, ensure_ascii=False, separators=(",", ":")) + "\n\n"
                except Exception:
                    yield "event: error\ndata: " + json.dumps({"runId": payload.run_id, "status": 503, "message": "Handout graph failed"}, ensure_ascii=False, separators=(",", ":")) + "\n\n"
                return
            yield ": heartbeat\n\n"
            time.sleep(HANDOUT_SSE_POLL_INTERVAL_SECONDS)
        yield "event: error\ndata: " + json.dumps({"runId": payload.run_id, "status": 504, "message": "Handout event stream timed out"}, ensure_ascii=False, separators=(",", ":")) + "\n\n"
    return StreamingResponse(encoded_events(), media_type="text/event-stream", headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"})


@app.get("/v1/handout-runs/{run_id}/events", dependencies=[Depends(require_worker_key)])
def handout_run_events(
    run_id: str,
    after_id: int = Query(default=0, alias="afterId", ge=0),
    limit: int = Query(default=DEFAULT_EVENT_PAGE_LIMIT, ge=1, le=MAX_EVENT_PAGE_LIMIT),
) -> dict:
    """Reads operational event pages for resume/debugging without exposing prompt or source bodies."""
    rows = handout_runtime().event_page(run_id, after_id, limit)
    return {
        "runId": run_id,
        "events": [{"eventId": event_id, **event} for event_id, event in rows],
        "nextAfterId": rows[-1][0] if rows else after_id,
    }


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
