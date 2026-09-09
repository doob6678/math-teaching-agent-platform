"""Durable execution and replay support for student explanation model runs."""
from __future__ import annotations

from contextlib import closing, contextmanager
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
import os
from pathlib import Path
import sqlite3
import threading
import time
from typing import Any, Callable

from fastapi import HTTPException

from app.workload_runtime import StudentExplanationRunRequest


MAX_EVENT_PAGE_LIMIT = 100


class StudentExplanationRunStore:
    """Stores opaque worker run state in shared MySQL or a SQLite development fallback."""

    def __init__(self) -> None:
        backend = os.getenv("MATH_AGENT_STUDENT_EXPLANATION_CHECKPOINT_BACKEND", "sqlite").strip().lower()
        self.backend = backend if backend in {"mysql", "sqlite"} else "sqlite"
        self._lock = threading.Lock()
        self._run_locks: dict[str, threading.Lock] = {}
        self._run_locks_guard = threading.Lock()
        if self.backend == "mysql":
            self._ensure_mysql_schema()
            return
        configured = os.getenv("MATH_AGENT_STUDENT_EXPLANATION_CHECKPOINT_DB", "/app/data/student-explanation-checkpoints.sqlite3")
        self.path = Path(configured)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        with closing(self._connect()) as connection:
            connection.executescript(
                """
                CREATE TABLE IF NOT EXISTS student_explanation_checkpoint (
                    run_id TEXT PRIMARY KEY,
                    request_fingerprint TEXT NOT NULL,
                    status TEXT NOT NULL,
                    response_json TEXT NULL,
                    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
                );
                CREATE TABLE IF NOT EXISTS student_explanation_event (
                    event_id INTEGER PRIMARY KEY AUTOINCREMENT,
                    run_id TEXT NOT NULL,
                    event_json TEXT NOT NULL,
                    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
                );
                CREATE INDEX IF NOT EXISTS idx_student_explanation_event_run_cursor
                    ON student_explanation_event(run_id, event_id);
                """
            )

    def _connect(self) -> sqlite3.Connection:
        connection = sqlite3.connect(self.path, timeout=30, check_same_thread=False)
        connection.row_factory = sqlite3.Row
        return connection

    @staticmethod
    def _mysql_connection():
        import pymysql
        return pymysql.connect(
            host=os.getenv("MATH_AGENT_DB_HOST", "mysql"),
            port=int(os.getenv("MATH_AGENT_DB_PORT", "3306")),
            user=os.getenv("MATH_AGENT_DB_USERNAME", "ai_runtime"),
            password=os.getenv("MATH_AGENT_DB_PASSWORD", ""),
            database=os.getenv("MATH_AGENT_DB_NAME", "math_agent_rag"),
            autocommit=False,
            charset="utf8mb4",
        )

    def _ensure_mysql_schema(self) -> None:
        import pymysql
        try:
            with self._mysql_connection() as connection:
                with connection.cursor() as cursor:
                    cursor.execute("SELECT 1 FROM student_explanation_checkpoint LIMIT 1")
                    cursor.execute("SELECT 1 FROM student_explanation_event LIMIT 1")
        except pymysql.MySQLError as exc:
            raise RuntimeError("shared student explanation checkpoint schema or restricted account is unavailable") from exc

    @contextmanager
    def run_lock(self, run_id: str):
        if self.backend != "mysql":
            with self._run_locks_guard:
                lock = self._run_locks.setdefault(run_id, threading.Lock())
            with lock:
                yield
            return
        wait_seconds = max(0, int(os.getenv("MATH_AGENT_STUDENT_EXPLANATION_RUN_LOCK_WAIT_SECONDS", "0")))
        lock_name = self._mysql_lock_name(run_id)
        with self._mysql_connection() as connection:
            with connection.cursor() as cursor:
                cursor.execute("SELECT GET_LOCK(%s,%s)", (lock_name, wait_seconds))
                row = cursor.fetchone()
            if not row or int(row[0] or 0) != 1:
                raise HTTPException(status_code=409, detail="STUDENT_EXPLANATION_RUN_LOCK_TIMEOUT")
            try:
                yield
            finally:
                with connection.cursor() as cursor:
                    cursor.execute("SELECT RELEASE_LOCK(%s)", (lock_name,))

    @staticmethod
    def _mysql_lock_name(run_id: str) -> str:
        """Returns a deterministic advisory-lock key within MySQL's 64-character limit."""
        digest = hashlib.sha256(run_id.encode("utf-8")).hexdigest()[:40]
        return f"ma:student-explanation:{digest}"

    def load(self, run_id: str) -> tuple[str, str, dict[str, Any] | None] | None:
        if self.backend == "mysql":
            with self._mysql_connection() as connection:
                with connection.cursor() as cursor:
                    cursor.execute(
                        "SELECT request_fingerprint,status,response_json FROM student_explanation_checkpoint WHERE run_id=%s",
                        (run_id,),
                    )
                    row = cursor.fetchone()
            return (str(row[0]), str(row[1]), json.loads(row[2]) if row[2] else None) if row else None
        with self._lock, closing(self._connect()) as connection:
            row = connection.execute(
                "SELECT request_fingerprint,status,response_json FROM student_explanation_checkpoint WHERE run_id=?", (run_id,)
            ).fetchone()
        return (str(row["request_fingerprint"]), str(row["status"]), json.loads(row["response_json"]) if row["response_json"] else None) if row else None

    def save(self, run_id: str, fingerprint: str, status: str, response: dict[str, Any] | None, event: dict[str, Any]) -> None:
        response_json = json.dumps(response, ensure_ascii=False, separators=(",", ":")) if response is not None else None
        event_json = json.dumps(event, ensure_ascii=False, separators=(",", ":"))
        if self.backend == "mysql":
            with self._lock, self._mysql_connection() as connection:
                with connection.cursor() as cursor:
                    cursor.execute(
                        "INSERT INTO student_explanation_checkpoint(run_id,request_fingerprint,status,response_json,updated_at) "
                        "VALUES(%s,%s,%s,%s,CURRENT_TIMESTAMP(6)) "
                        "ON DUPLICATE KEY UPDATE request_fingerprint=VALUES(request_fingerprint),status=VALUES(status),"
                        "response_json=VALUES(response_json),updated_at=CURRENT_TIMESTAMP(6)",
                        (run_id, fingerprint, status, response_json),
                    )
                    cursor.execute("INSERT INTO student_explanation_event(run_id,event_json,created_at) VALUES(%s,%s,CURRENT_TIMESTAMP(6))", (run_id, event_json))
                connection.commit()
            return
        with self._lock, closing(self._connect()) as connection:
            connection.execute(
                "INSERT INTO student_explanation_checkpoint(run_id,request_fingerprint,status,response_json,updated_at) VALUES(?,?,?,?,CURRENT_TIMESTAMP) "
                "ON CONFLICT(run_id) DO UPDATE SET status=excluded.status,response_json=excluded.response_json,updated_at=CURRENT_TIMESTAMP",
                (run_id, fingerprint, status, response_json),
            )
            connection.execute("INSERT INTO student_explanation_event(run_id,event_json) VALUES(?,?)", (run_id, event_json))
            connection.commit()

    def update_response(self, run_id: str, fingerprint: str, status: str, response: dict[str, Any]) -> None:
        existing = self.load(run_id)
        if existing is None:
            raise RuntimeError("student explanation run is missing")
        self.save(run_id, fingerprint, status, response, {"event": "response_updated"})

    def events_after(self, run_id: str, after_id: int, limit: int) -> list[tuple[int, dict[str, Any]]]:
        bounded_limit = max(1, min(int(limit), MAX_EVENT_PAGE_LIMIT))
        if self.backend == "mysql":
            with self._mysql_connection() as connection:
                with connection.cursor() as cursor:
                    cursor.execute(
                        "SELECT event_id,event_json FROM student_explanation_event WHERE run_id=%s AND event_id>%s ORDER BY event_id LIMIT %s",
                        (run_id, max(0, int(after_id)), bounded_limit),
                    )
                    rows = cursor.fetchall()
            return [(int(row[0]), json.loads(row[1])) for row in rows]
        with self._lock, closing(self._connect()) as connection:
            rows = connection.execute(
                "SELECT event_id,event_json FROM student_explanation_event WHERE run_id=? AND event_id>? ORDER BY event_id LIMIT ?",
                (run_id, max(0, int(after_id)), bounded_limit),
            ).fetchall()
        return [(int(row["event_id"]), json.loads(row["event_json"])) for row in rows]


class DurableStudentExplanationRuntime:
    """Adds idempotency and event replay around the typed Python explanation runtime."""

    # 连续 delta 在该窗口内合并成一条检查点事件，见 _run_stream_worker。
    _DELTA_FLUSH_SECONDS = 0.08

    # 单 GPU 本地模型下同时能推进的讲解流上限；此前硬编码 4。默认保持 4 以不改变现有容量语义，
    # 运维可按显存/GPU 数经 env 上调。见下方 __init__ 读取。
    _DEFAULT_STREAM_CONCURRENCY = 4
    # 池满时新讲解请求的排队等待上限（秒）。给"有界等待"：突发并发先排队复用槽位，超过该等待才回 429，
    # 默认 15s——小于单次讲解常见耗时（8-14s）的一个身位，能在真实突发下吸收排队又不长时间占用连接。
    _DEFAULT_STREAM_QUEUE_WAIT_SECONDS = 15.0

    def __init__(self, executor: Callable[[StudentExplanationRunRequest], dict[str, Any]], stream_executor: Callable[[StudentExplanationRunRequest], Any] | None = None) -> None:
        self._executor = executor
        self._stream_executor_method = stream_executor
        self._store = StudentExplanationRunStore()
        self._stream_lock = threading.Lock()
        # 流式讲解并发池：大小来自 env，默认 4（等价旧硬编码，受单 GPU 模型/rerank 串行容量约束）。
        # 同时用同容量的 BoundedSemaphore 实现"有界等待"准入：ThreadPoolExecutor 的默认无界队列会让
        # 超额请求静默排队、最终在 Java 侧超时或被下游打爆；改为显式槽位后，池满的新请求最多等待
        # queue_wait 秒再回 429，把并发上限变成可运维、可观测的契约（老板 2026-09-09：排队改造+池可配）。
        self._stream_concurrency = self._positive_int_env(
            "MATH_AGENT_STUDENT_EXPLANATION_STREAM_CONCURRENCY", self._DEFAULT_STREAM_CONCURRENCY)
        self._stream_queue_wait_seconds = self._non_negative_float_env(
            "MATH_AGENT_STUDENT_EXPLANATION_STREAM_QUEUE_WAIT_SECONDS", self._DEFAULT_STREAM_QUEUE_WAIT_SECONDS)
        self._stream_executor = ThreadPoolExecutor(
            max_workers=self._stream_concurrency, thread_name_prefix="student-explanation")
        self._stream_slots = threading.BoundedSemaphore(self._stream_concurrency)
        self._stream_futures: dict[str, Any] = {}

    @staticmethod
    def _positive_int_env(name: str, default: int) -> int:
        """读取正整数 env；非法或非正值回退默认，避免 0/负值把并发池关死。"""
        try:
            value = int(os.getenv(name, "").strip() or default)
        except ValueError:
            return default
        return value if value > 0 else default

    @staticmethod
    def _non_negative_float_env(name: str, default: float) -> float:
        """读取非负浮点 env；非法或负值回退默认（0 合法，表示不排队直接回 429）。"""
        try:
            value = float(os.getenv(name, "").strip() or default)
        except ValueError:
            return default
        return value if value >= 0 else default

    def execute(self, request: StudentExplanationRunRequest) -> dict[str, Any]:
        fingerprint = self._fingerprint(request)
        with self._store.run_lock(request.runId):
            existing = self._store.load(request.runId)
            if existing is not None:
                saved_fingerprint, status, response = existing
                if saved_fingerprint != fingerprint:
                    raise HTTPException(status_code=409, detail="STUDENT_EXPLANATION_RUN_FINGERPRINT_MISMATCH")
                if status == "COMPLETED" and response is not None:
                    return response
                if status == "RUNNING":
                    raise HTTPException(status_code=409, detail="STUDENT_EXPLANATION_RUN_IN_PROGRESS")
            self._store.save(request.runId, fingerprint, "RUNNING", None, {"event": "started"})
            try:
                response = self._executor(request)
                self._store.save(request.runId, fingerprint, "COMPLETED", response, {"event": "completed"})
                return response
            except HTTPException as exc:
                self._store.save(request.runId, fingerprint, "FAILED", None, {"event": "failed", "status": exc.status_code, "code": str(exc.detail)[:160]})
                raise
            except Exception as exc:
                self._store.save(request.runId, fingerprint, "FAILED", None, {"event": "failed", "status": 503, "code": type(exc).__name__})
                raise HTTPException(status_code=503, detail="STUDENT_EXPLANATION_RUN_FAILED") from exc

    def event_page(self, run_id: str, after_id: int = 0, limit: int = 50) -> list[tuple[int, dict[str, Any]]]:
        return self._store.events_after(run_id, after_id, limit)

    def stream_events(self, request: StudentExplanationRunRequest, after_id: int = 0, timeout_seconds: float = 300.0):
        fingerprint = self._fingerprint(request)
        run_id = request.runId
        cursor = max(0, int(after_id))
        # 准入前置阶段（同 run 锁 / 幂等指纹 / 开流准入）可能抛 409（指纹或锁冲突）、429（队列满）。
        # 关键：本方法是生成器，且 server.py 用 encoded_events() 包裹后交给 StreamingResponse 惰性消费——
        # HTTP 响应头在生成器被消费之前就已发出。此时若 raise，异常发生在"响应已开始"之后，starlette 抛
        # RuntimeError("response already started") 并切断连接，Java 读 chunked body 收到 EOF → 映射为 500
        # （正是本次稳定性实测 conc>4 讲解流 500 的根因）。故把这些冲突转成一条携带真实状态码的终态 error
        # 事件干净收尾：409/429 语义保留在事件 data.status，流正常结束不再崩 500。老板 2026-09-09 令。
        try:
            with self._store.run_lock(run_id):
                existing = self._store.load(run_id)
                if existing is not None and existing[0] != fingerprint:
                    raise HTTPException(status_code=409, detail="STUDENT_EXPLANATION_RUN_FINGERPRINT_MISMATCH")
                if existing is None:
                    self._start_stream_worker(request, fingerprint)
                elif existing[1] == "RUNNING" and run_id not in self._stream_futures:
                    if self._can_resume_pre_delta(run_id):
                        self._store.save(run_id, fingerprint, "RUNNING", None, {
                            "event": "resumed",
                            "data": {"runId": run_id, "message": "STUDENT_EXPLANATION_RUN_RESUMED"},
                        })
                        self._start_stream_worker(request, fingerprint)
                    else:
                        self._store.save(run_id, fingerprint, "FAILED", None, {
                            "event": "error",
                            "data": {"runId": run_id, "status": 503, "message": "STUDENT_EXPLANATION_RUN_INTERRUPTED"},
                        })
        except HTTPException as exc:
            yield cursor, {"event": "error", "data": {"runId": run_id, "status": exc.status_code, "message": str(exc.detail)}}
            return
        deadline = time.monotonic() + max(1.0, min(float(timeout_seconds), 900.0))
        while time.monotonic() < deadline:
            rows = self.event_page(run_id, cursor, MAX_EVENT_PAGE_LIMIT)
            if rows:
                for event_id, event in rows:
                    cursor = event_id
                    yield event_id, event
                    if event.get("event") in {"completed", "error", "failed"}:
                        return
                continue
            future = self._stream_futures.get(run_id)
            if future is not None and future.done():
                return
            time.sleep(0.05)
        raise HTTPException(status_code=504, detail="STUDENT_EXPLANATION_STREAM_TIMEOUT")

    def _start_stream_worker(self, request: StudentExplanationRunRequest, fingerprint: str) -> None:
        # 有界等待一个流式槽位：池满时新讲解最多排队 queue_wait 秒再回 429（繁忙，可退避重试）。
        # 关键：阻塞等待必须在全局 _stream_lock 之外完成，否则等待期间会连带占住开流锁、把其它 run 也拖慢。
        # 槽位归还责任用 handed_off 标志精确配对——只有成功 submit 给 worker 后才交给 worker 的 finally 归还，
        # 其余路径（已有存活 future、submit 抛错）在本地 finally 立即归还，杜绝计数泄漏。
        # 抛出的 429 由 stream_events 前置 try 转成携带 status 的 error 事件干净收尾，不会崩流 500。
        if not self._stream_slots.acquire(timeout=self._stream_queue_wait_seconds):
            raise HTTPException(status_code=429, detail="STUDENT_EXPLANATION_QUEUE_FULL")
        handed_off = False
        try:
            with self._stream_lock:
                future = self._stream_futures.get(request.runId)
                if future is not None and not future.done():
                    # 并发重入或断线重连已有存活 worker：本次只回放事件，不占新槽位。
                    return
                self._stream_futures[request.runId] = self._stream_executor.submit(self._run_stream_worker, request, fingerprint)
                handed_off = True
        finally:
            if not handed_off:
                self._stream_slots.release()

    def _run_stream_worker(self, request: StudentExplanationRunRequest, fingerprint: str) -> None:
        try:
            pending_delta: dict[str, Any] | None = None
            pending_since = 0.0

            def flush_pending_delta() -> None:
                nonlocal pending_delta
                if pending_delta is not None:
                    self._store.save(request.runId, fingerprint, "RUNNING", None, pending_delta)
                    pending_delta = None

            for item in self._executor_stream(request):
                event = {"event": str(item.get("event", "progress")), "data": item.get("data", {})}
                if event["event"] in {"completed", "error"}:
                    flush_pending_delta()
                    self._store.save(request.runId, fingerprint, "COMPLETED" if event["event"] == "completed" else "FAILED", event["data"] if event["event"] == "completed" else None, event)
                    return
                if event["event"] == "delta":
                    now = time.monotonic()
                    if pending_delta is None:
                        pending_delta = event
                        pending_since = now
                    else:
                        # 逐 token 各写一次检查点会把事件表放大几百倍；在短窗口内合并文本增量，
                        # 读取端本来按 50ms 轮询，合并不引入可感知延迟。
                        # 2026-09-08 思考流乱码根因修复：合并必须同时拼接 reasoning。旧版只拼 content，
                        # 窗口内第二条带 reasoning 的 delta 会被静默丢弃（GLM 强制思考每窗口产出多条
                        # reasoning 增量），事件表里存的思考就成了互相不连续的碎片，前端拼接后呈现为乱码；
                        # completed 的 reasoningTrace 走内存另一条 join，掩盖了这个分叉。
                        pending_delta = {**pending_delta, "data": {
                            **pending_delta["data"],
                            "content": str(pending_delta["data"].get("content", "")) + str(event["data"].get("content", "")),
                            "reasoning": str(pending_delta["data"].get("reasoning", "")) + str(event["data"].get("reasoning", "")),
                        }}
                    if now - pending_since >= self._DELTA_FLUSH_SECONDS:
                        flush_pending_delta()
                    continue
                flush_pending_delta()
                self._store.save(request.runId, fingerprint, "RUNNING", None, event)
            flush_pending_delta()
            self._store.save(request.runId, fingerprint, "FAILED", None, {"event": "error", "data": {"runId": request.runId, "status": 503, "message": "STUDENT_EXPLANATION_STREAM_ENDED_WITHOUT_TERMINAL_EVENT", "cause": "StreamEndedWithoutTerminalEvent"}})
        except Exception as exc:
            # cause 只带异常类型名与栈尾帧（不带消息细节），足够定位失败层又不泄漏 provider 细节。
            import traceback as _traceback
            tail = ""
            try:
                tail = _traceback.format_exc().strip().splitlines()[-1][:200]
            except Exception:
                tail = ""
            self._store.save(request.runId, fingerprint, "FAILED", None, {"event": "error", "data": {"runId": request.runId, "status": 503, "message": "STUDENT_EXPLANATION_RUN_FAILED", "cause": type(exc).__name__, "where": tail}})
        finally:
            # 与 _start_stream_worker 的槽位获取成对归还：worker 无论正常收尾、无终态事件还是抛错都要释放，
            # 否则池会因泄漏逐渐填满、后续讲解全被 429 卡死。
            self._stream_slots.release()

    def _can_resume_pre_delta(self, run_id: str) -> bool:
        events = self._store.events_after(run_id, 0, MAX_EVENT_PAGE_LIMIT)
        if any(event.get("event") == "delta" for _, event in events):
            return False
        resumes = sum(1 for _, event in events if event.get("event") == "resumed")
        return resumes < max(0, int(os.getenv("MATH_AGENT_STUDENT_EXPLANATION_RESUME_ATTEMPTS", "1")))

    def _executor_stream(self, request: StudentExplanationRunRequest):
        stream_method = self._stream_executor_method or getattr(self._executor, "stream_student_explanation", None)
        if stream_method is None:
            raise RuntimeError("student explanation executor does not support streaming")
        for item in stream_method(request):
            yield {"event": item.get("event", "progress"), "data": item.get("data", {})}

    @staticmethod
    def _fingerprint(request: StudentExplanationRunRequest) -> str:
        encoded = json.dumps(request.model_dump(mode="json"), ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
        return hashlib.sha256(encoded).hexdigest()
