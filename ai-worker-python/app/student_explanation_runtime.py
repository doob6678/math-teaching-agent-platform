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

    def __init__(self, executor: Callable[[StudentExplanationRunRequest], dict[str, Any]], stream_executor: Callable[[StudentExplanationRunRequest], Any] | None = None) -> None:
        self._executor = executor
        self._stream_executor_method = stream_executor
        self._store = StudentExplanationRunStore()
        self._stream_lock = threading.Lock()
        self._stream_executor = ThreadPoolExecutor(max_workers=4, thread_name_prefix="student-explanation")
        self._stream_futures: dict[str, Any] = {}

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
        cursor = max(0, int(after_id))
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
        with self._stream_lock:
            future = self._stream_futures.get(request.runId)
            if future is not None and not future.done():
                return
            self._stream_futures[request.runId] = self._stream_executor.submit(self._run_stream_worker, request, fingerprint)

    def _run_stream_worker(self, request: StudentExplanationRunRequest, fingerprint: str) -> None:
        try:
            for item in self._executor_stream(request):
                event = {"event": str(item.get("event", "progress")), "data": item.get("data", {})}
                if event["event"] in {"completed", "error"}:
                    self._store.save(request.runId, fingerprint, "COMPLETED" if event["event"] == "completed" else "FAILED", event["data"] if event["event"] == "completed" else None, event)
                    return
                self._store.save(request.runId, fingerprint, "RUNNING", None, event)
            self._store.save(request.runId, fingerprint, "FAILED", None, {"event": "error", "data": {"runId": request.runId, "status": 503, "message": "STUDENT_EXPLANATION_STREAM_ENDED_WITHOUT_TERMINAL_EVENT"}})
        except Exception as exc:
            self._store.save(request.runId, fingerprint, "FAILED", None, {"event": "error", "data": {"runId": request.runId, "status": 503, "message": "STUDENT_EXPLANATION_RUN_FAILED"}, "cause": type(exc).__name__})

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
