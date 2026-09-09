"""2026-09-09 AI 讲解链路稳定性修复的定向回归测试。

覆盖本次两处改动（只测这两处语义，不改其它行为）：
1. workload_runtime._stream_call_json：provider 侧 429 在重试/回退用尽后必须透传为 HTTPException(429)
   （带 Retry-After），其它失败仍为 503——避免真实限流被误报成"通道不可用"。
2. student_explanation_runtime：
   - 流式讲解并发池大小可经 env 配置，默认 4（等价旧硬编码）。
   - 池满时"有界等待"；无法准入时以携带 status 的终态 error 事件干净收尾，而不是让 HTTPException 逃出
     生成器（server.py 用 StreamingResponse 惰性消费，逃出的 409/429 会崩流→Java EOF→500）。
"""
from __future__ import annotations

import os
import tempfile
import unittest
from unittest.mock import patch

import requests
from fastapi import HTTPException

from app.student_explanation_runtime import DurableStudentExplanationRuntime
from app.workload_runtime import MigratedWorkloadRuntime, StudentExplanationRunRequest


class _RateLimitedResponse:
    """模拟 provider 返回 429：raise_for_status 抛带 response 的 HTTPError，携带 Retry-After。"""

    def __init__(self, status_code: int, headers: dict[str, str] | None = None):
        self.status_code = status_code
        self.headers = headers or {}

    def __enter__(self):
        return self

    def __exit__(self, *_):
        return False

    def raise_for_status(self):
        raise requests.HTTPError("provider rejected", response=self)


def _route():
    return {"primary": {"name": "openai", "model": "gpt-5.6-luna"}, "fallbacks": []}


class Provider429PassthroughTest(unittest.TestCase):
    """_stream_call_json 的 429 透传语义。"""

    def test_provider_429_surfaces_as_http_429_with_retry_after(self):
        runtime = MigratedWorkloadRuntime()
        route = StudentExplanationRunRequest.model_validate(
            {"runId": "429-passthrough-1", "problem": "求定义域", "providerRoute": _route()}).providerRoute
        responses = [_RateLimitedResponse(429, {"Retry-After": "3"}), _RateLimitedResponse(429, {"Retry-After": "3"})]
        with patch.dict(os.environ, {
            "OPENAI_API_KEY": "test-key",
            "MATH_AGENT_STUDENT_EXPLANATION_MODEL_ATTEMPTS": "2",
            "MATH_AGENT_STUDENT_EXPLANATION_RETRY_BACKOFF_SECONDS": "0",
        }), patch.object(runtime._ledger, "append"):
            with patch.object(runtime._session, "post", side_effect=responses):
                with self.assertRaises(HTTPException) as caught:
                    list(runtime._stream_call_json("429-passthrough-1", route, [{"role": "user", "content": "x"}], True))
        self.assertEqual(caught.exception.status_code, 429)
        self.assertIn("rate limited", str(caught.exception.detail).lower())
        self.assertEqual((caught.exception.headers or {}).get("Retry-After"), "3")

    def test_provider_503_only_still_surfaces_as_http_503(self):
        # 保证 429 透传没有把普通不可用误升级：只有观测到 429 才回 429，否则维持 503。
        runtime = MigratedWorkloadRuntime()
        route = StudentExplanationRunRequest.model_validate(
            {"runId": "503-preserve-1", "problem": "求定义域", "providerRoute": _route()}).providerRoute
        responses = [_RateLimitedResponse(503), _RateLimitedResponse(503)]
        with patch.dict(os.environ, {
            "OPENAI_API_KEY": "test-key",
            "MATH_AGENT_STUDENT_EXPLANATION_MODEL_ATTEMPTS": "2",
            "MATH_AGENT_STUDENT_EXPLANATION_RETRY_BACKOFF_SECONDS": "0",
        }), patch.object(runtime._ledger, "append"):
            with patch.object(runtime._session, "post", side_effect=responses):
                with self.assertRaises(HTTPException) as caught:
                    list(runtime._stream_call_json("503-preserve-1", route, [{"role": "user", "content": "x"}], True))
        self.assertEqual(caught.exception.status_code, 503)


class StreamConcurrencyPoolTest(unittest.TestCase):
    """流式讲解池大小 env 可配 + 有界等待 + 冲突以 error 事件干净收尾。"""

    @staticmethod
    def _runtime(checkpoint_dir, **extra_env):
        env = {"MATH_AGENT_STUDENT_EXPLANATION_CHECKPOINT_DB": os.path.join(checkpoint_dir, "runs.sqlite3"), **extra_env}
        with patch.dict(os.environ, env):
            def stream_executor(request):
                yield {"event": "started", "data": {"runId": request.runId}}
                yield {"event": "completed", "data": {"runId": request.runId, "status": "COMPLETED", "cards": []}}
            return DurableStudentExplanationRuntime(lambda _: {}, stream_executor)

    def test_default_concurrency_is_four(self):
        with tempfile.TemporaryDirectory() as directory:
            for key in ("MATH_AGENT_STUDENT_EXPLANATION_STREAM_CONCURRENCY",):
                os.environ.pop(key, None)
            runtime = self._runtime(directory)
        self.assertEqual(runtime._stream_concurrency, 4)

    def test_concurrency_is_env_configurable(self):
        with tempfile.TemporaryDirectory() as directory:
            runtime = self._runtime(directory, MATH_AGENT_STUDENT_EXPLANATION_STREAM_CONCURRENCY="2")
        self.assertEqual(runtime._stream_concurrency, 2)

    def test_fingerprint_conflict_yields_409_error_event_without_raising(self):
        # 关键回归：同一 run 幂等指纹冲突不得让 HTTPException 逃出 stream_events（否则崩流→500）；
        # 必须以携带 status 409 的终态 error 事件收尾。
        with tempfile.TemporaryDirectory() as directory:
            runtime = self._runtime(directory)
            base = {"runId": "fp-conflict-stream-1", "problem": "求定义域", "providerRoute": _route()}
            first = StudentExplanationRunRequest.model_validate(base)
            events_first = list(runtime.stream_events(first))  # 完成并持久化 COMPLETED
            self.assertEqual(events_first[-1][1]["event"], "completed")
            conflict = StudentExplanationRunRequest.model_validate({**base, "problem": "求值域"})  # 同 run 不同指纹
            # list() 若内部 raise 会抛出；这里必须正常返回。
            events = list(runtime.stream_events(conflict))
            self.assertEqual(events[-1][1]["event"], "error")
            self.assertEqual(events[-1][1]["data"]["status"], 409)
            self.assertEqual(events[-1][1]["data"]["message"], "STUDENT_EXPLANATION_RUN_FINGERPRINT_MISMATCH")

    def test_queue_full_yields_429_error_event_without_raising(self):
        # 池=1 且手动占满唯一槽位、queue_wait=0（不排队直接繁忙）：新讲解必须以 429 error 事件收尾，不崩流。
        with tempfile.TemporaryDirectory() as directory:
            runtime = self._runtime(
                directory,
                MATH_AGENT_STUDENT_EXPLANATION_STREAM_CONCURRENCY="1",
                MATH_AGENT_STUDENT_EXPLANATION_STREAM_QUEUE_WAIT_SECONDS="0",
            )
            self.assertTrue(runtime._stream_slots.acquire())  # 模拟已有一个 worker 占住唯一槽位
            try:
                req = StudentExplanationRunRequest.model_validate(
                    {"runId": "queue-full-stream-1", "problem": "求定义域", "providerRoute": _route()})
                events = list(runtime.stream_events(req))  # 若 raise 会在此抛出
                self.assertEqual(events[-1][1]["event"], "error")
                self.assertEqual(events[-1][1]["data"]["status"], 429)
                self.assertEqual(events[-1][1]["data"]["message"], "STUDENT_EXPLANATION_QUEUE_FULL")
            finally:
                runtime._stream_slots.release()

    def test_start_worker_bounded_wait_times_out_then_raises_429(self):
        # 直接验证 _start_stream_worker 的有界等待语义：池满且等待到点 → 抛 429（由上层转成事件）。
        with tempfile.TemporaryDirectory() as directory:
            runtime = self._runtime(
                directory,
                MATH_AGENT_STUDENT_EXPLANATION_STREAM_CONCURRENCY="1",
                MATH_AGENT_STUDENT_EXPLANATION_STREAM_QUEUE_WAIT_SECONDS="0",
            )
            runtime._stream_slots.acquire()
            try:
                req = StudentExplanationRunRequest.model_validate(
                    {"runId": "queue-wait-1", "problem": "求定义域", "providerRoute": _route()})
                with self.assertRaises(HTTPException) as caught:
                    runtime._start_stream_worker(req, "fp")
                self.assertEqual(caught.exception.status_code, 429)
            finally:
                runtime._stream_slots.release()


if __name__ == "__main__":
    unittest.main()
