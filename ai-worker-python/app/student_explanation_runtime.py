"""Durable execution and replay support for student explanation model runs."""
from __future__ import annotations

from contextlib import closing
import hashlib
import json
import os
from pathlib import Path
import sqlite3
import threading
from typing import Any, Callable

from fastapi import HTTPException

from app.workload_runtime import StudentExplanationRunRequest


MAX_EVENT_PAGE_LIMIT = 100


class StudentExplanationRunStore:
    """Persists worker-only run state so retries and reconnects reuse one model result.

    Java remains the source of truth for the requesting subject and evidence authorization. This store contains only
    opaque run identifiers, a fingerprint of the bounded worker payload, safe lifecycle events, and the final result.
    """

    def __init__(self) -> None:
        configured = os.getenv(
            "MATH_AGENT_STUDENT_EXPLANATION_CHECKPOINT_DB",
            "/app/data/student-explanation-checkpoints.sqlite3",
        )
        self.path = Path(configured)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self._lock = threading.Lock()
        self._run_locks: dict[str, threading.Lock] = {}
        self._run_locks_guard = threading.Lock()
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

    def run_lock(self, run_id: str) -> threading.Lock:
        with self._run_locks_guard:
            return self._run_locks.setdefault(run_id, threading.Lock())

    def load(self, run_id: str) -> tuple[str, str, dict[str, Any] | None] | None:
        with self._lock, closing(self._connect()) as connection:
            row = connection.execute(
                "SELECT request_fingerprint,status,response_json FROM student_explanation_checkpoint WHERE run_id=?",
                (run_id,),
            ).fetchone()
        if row is None:
            return None
        response = json.loads(row["response_json"]) if row["response_json"] else None
        return str(row["request_fingerprint"]), str(row["status"]), response

    def save(self, run_id: str, fingerprint: str, status: str, response: dict[str, Any] | None, event: dict[str, Any]) -> None:
        response_json = json.dumps(response, ensure_ascii=False, separators=(",", ":")) if response is not None else None
        event_json = json.dumps(event, ensure_ascii=False, separators=(",", ":"))
        with self._lock, closing(self._connect()) as connection:
            connection.execute(
                "INSERT INTO student_explanation_checkpoint(run_id,request_fingerprint,status,response_json,updated_at) "
                "VALUES(?,?,?,?,CURRENT_TIMESTAMP) "
                "ON CONFLICT(run_id) DO UPDATE SET status=excluded.status,response_json=excluded.response_json,updated_at=CURRENT_TIMESTAMP",
                (run_id, fingerprint, status, response_json),
            )
            connection.execute(
                "INSERT INTO student_explanation_event(run_id,event_json) VALUES(?,?)",
                (run_id, event_json),
            )
            connection.commit()

    def events_after(self, run_id: str, after_id: int, limit: int) -> list[tuple[int, dict[str, Any]]]:
        bounded_limit = max(1, min(int(limit), MAX_EVENT_PAGE_LIMIT))
        with self._lock, closing(self._connect()) as connection:
            rows = connection.execute(
                "SELECT event_id,event_json FROM student_explanation_event WHERE run_id=? AND event_id>? "
                "ORDER BY event_id LIMIT ?",
                (run_id, max(0, int(after_id)), bounded_limit),
            ).fetchall()
        return [(int(row["event_id"]), json.loads(row["event_json"])) for row in rows]


class DurableStudentExplanationRuntime:
    """Adds idempotency and event replay around the typed Python explanation runtime."""

    def __init__(self, executor: Callable[[StudentExplanationRunRequest], dict[str, Any]]) -> None:
        self._executor = executor
        self._store = StudentExplanationRunStore()

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
                self._store.save(request.runId, fingerprint, "FAILED", None, {
                    "event": "failed", "status": exc.status_code, "code": str(exc.detail)[:160],
                })
                raise
            except Exception as exc:
                self._store.save(request.runId, fingerprint, "FAILED", None, {
                    "event": "failed", "status": 503, "code": type(exc).__name__,
                })
                raise HTTPException(status_code=503, detail="STUDENT_EXPLANATION_RUN_FAILED") from exc

    def event_page(self, run_id: str, after_id: int = 0, limit: int = 50) -> list[tuple[int, dict[str, Any]]]:
        return self._store.events_after(run_id, after_id, limit)

    @staticmethod
    def _fingerprint(request: StudentExplanationRunRequest) -> str:
        # The provider route is part of the immutable run contract: changing it on a retry must not bypass routing
        # policy or turn a resumed call into a new billable model request.
        payload = request.model_dump(mode="json")
        encoded = json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
        return hashlib.sha256(encoded).hexdigest()
