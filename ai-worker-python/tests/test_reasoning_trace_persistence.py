"""Reasoning-trace persistence: private checkpoint write + teacher-side diagnostic projection.

The 2026-08-31 reasoning feature writes hidden thinking into the durable private
model-turn diagnostics (checkpoint) and exposes it only through the bounded
model-diagnostics projection. Answer text, prompts and raw responses must stay inside
the private store; the projection must never carry them out.
"""

from __future__ import annotations

import json
import os
import tempfile
import threading
from unittest.mock import patch

from app.handout_runtime import HandoutRuntime, HandoutRunRequest, _CheckpointStore


def _frames(*events: dict) -> list[bytes]:
    lines: list[bytes] = []
    for event in events:
        lines.append(f"data: {json.dumps(event, ensure_ascii=False)}".encode("utf-8"))
        lines.append(b"")
    lines.append(b"data: [DONE]")
    lines.append(b"")
    return lines


class _StreamResponse:
    def __init__(self, lines):
        self._lines = lines
        self.headers = {"Content-Type": "text/event-stream"}

    def raise_for_status(self):
        return None

    def iter_lines(self, *_args, **_kwargs):
        return iter(self._lines)

    def close(self):
        return None


def _runtime_with_sqlite_checkpoint(directory: str) -> HandoutRuntime:
    runtime = HandoutRuntime.__new__(HandoutRuntime)
    runtime._checkpoint = _CheckpointStore()
    runtime._telemetry_lock = threading.Lock()
    runtime._telemetry_by_run = {}
    return runtime


def test_reasoning_stream_persists_to_private_checkpoint_and_projection_stays_bounded():
    with tempfile.TemporaryDirectory() as directory:
        with patch.dict(os.environ, {
            "MATH_AGENT_HANDOUT_CHECKPOINT_BACKEND": "sqlite",
            "MATH_AGENT_HANDOUT_CHECKPOINT_DB": os.path.join(directory, "cp.sqlite3"),
            "OPENAI_API_KEY": "test-key",
            "OPENAI_BASE_URL": "https://relay.example/v1",
            "OPENAI_CHAT_MODEL": "gpt-5.6-terra",
            "MATH_AGENT_HANDOUT_PROVIDER_ORDER": "openai",
            "MATH_AGENT_HANDOUT_MODEL_ATTEMPTS": "1",
        }, clear=False), patch("app.handout_runtime.UsageLedger.append"), \
                patch("app.handout_runtime.cost_for", return_value=0.0):
            runtime = _runtime_with_sqlite_checkpoint(directory)
            runtime._session = type("Session", (), {"post": lambda *_a, **_k: _StreamResponse(_frames(
                {"choices": [{"delta": {"reasoning_content": "先配方，再判符号。"}}]},
                {"choices": [{"delta": {"content": '{"ready":true}'}}]},
                {"choices": [{"delta": {}, "finish_reason": "stop"}],
                 "usage": {"prompt_tokens": 4, "completion_tokens": 6, "total_tokens": 10}},
            ))})()
            request = HandoutRunRequest(
                runId="run-reasoning-001", taskId="task-reasoning-001",
                writingGoal="二次函数", questionText="【题目 1】求顶点。")
            runtime._telemetry_by_run[request.run_id] = type(
                "Telemetry", (), {"reserve_provider_call": lambda *_args: None})()
            payload, _usage, _provider, _model = runtime._invoke_json_model(request, "plan_writer", "{}")
            assert payload == {"ready": True}

            private = runtime._checkpoint.load_private_state(request.run_id)
            turns = private["modelTurnDiagnostics"]
            traces = [update.get("reasoningTrace") for update in turns.values()
                      if isinstance(update, dict) and update.get("reasoningTrace")]
            assert traces and "先配方" in traces[-1]
            assert traces[-1] not in json.dumps(payload)

            projected = runtime.model_turn_diagnostics(request.run_id, excerpt_chars=10)
            assert projected
            item = next(entry for entry in projected if entry.get("reasoningExcerpt"))
            assert len(item["reasoningExcerpt"]) <= 10
            flat = json.dumps(projected, ensure_ascii=False)
            assert "先配方" in flat
            # 诊断投影不得携带 prompt/原文/答案字段。
            for forbidden in ("requestPayload", "rawResponse", "extractedContent", "extractedJson"):
                assert forbidden not in flat


def test_reasoning_absent_writes_zero_chars_without_fake_trace():
    with tempfile.TemporaryDirectory() as directory:
        with patch.dict(os.environ, {
            "MATH_AGENT_HANDOUT_CHECKPOINT_BACKEND": "sqlite",
            "MATH_AGENT_HANDOUT_CHECKPOINT_DB": os.path.join(directory, "cp.sqlite3"),
            "OPENAI_API_KEY": "test-key",
            "OPENAI_BASE_URL": "https://relay.example/v1",
            "MATH_AGENT_HANDOUT_PROVIDER_ORDER": "openai",
            "MATH_AGENT_HANDOUT_MODEL_ATTEMPTS": "1",
        }, clear=False), patch("app.handout_runtime.UsageLedger.append"), \
                patch("app.handout_runtime.cost_for", return_value=0.0):
            runtime = _runtime_with_sqlite_checkpoint(directory)
            runtime._session = type("Session", (), {"post": lambda *_a, **_k: _StreamResponse(_frames(
                {"choices": [{"delta": {"content": "{}"}, "finish_reason": "stop"}]},
            ))})()
            request = HandoutRunRequest(
                runId="run-reasoning-002", taskId="task-reasoning-002",
                writingGoal="g", questionText="【题目 1】x")
            runtime._telemetry_by_run[request.run_id] = type(
                "Telemetry", (), {"reserve_provider_call": lambda *_args: None})()
            try:
                runtime._invoke_json_model(request, "plan_writer", "{}")
            except Exception:
                pass
            turns = runtime._checkpoint.load_private_state(request.run_id).get("modelTurnDiagnostics", {})
            records = [u for u in turns.values() if isinstance(u, dict) and "reasoningChars" in u]
            assert all(record.get("reasoningTrace") is None and record["reasoningChars"] == 0 for record in records)
