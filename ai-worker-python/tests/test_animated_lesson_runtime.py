# -*- coding: utf-8 -*-
"""animated_lesson workload 合同与执行体测试（2026-09-09）。

纪律：校验回路用 tools/animated-lesson 的真实分镜文件跑真实校验器（原型即主链路底座，
schema 漂移必须在这里炸出来）；provider 调用与 manim 渲染不在单测里烧钱/烧分钟——
生成引擎的真实验证由 q-016 实验与端到端联调承担，这里只锁合同、路径与失败语义。
"""
from __future__ import annotations

import json
import os
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest import mock

from fastapi import HTTPException
from pydantic import ValidationError

from app.animated_lesson_runtime import (
    AnimatedLessonRuntime,
    AnimatedLessonRunRequest,
    _extract_json,
    _sanitize_problem_text,
)

REPO_ROOT = Path(__file__).resolve().parents[2]
TOOLS_DIR = REPO_ROOT / "tools" / "animated-lesson"
REAL_LESSONS = ["tangent-min", "two-circle-tangents", "tangent-angle", "ellipse-moving-line"]


def _route() -> dict:
    return {"primary": {"name": "openai", "model": "gpt-5.6-terra"}, "fallbacks": []}


class ContractTests(unittest.TestCase):
    def test_extra_fields_rejected(self):
        with self.assertRaises(ValidationError):
            AnimatedLessonRunRequest(runId="r1", providerRoute=_route(),
                                     problemText="已知椭圆" + "x" * 20, evil="drop table")

    def test_lesson_id_slug_blocks_path_injection(self):
        # lessonId 会进产物目录名与子进程参数，非 slug 值必须在合同层就 422。
        with self.assertRaises(ValidationError):
            AnimatedLessonRunRequest(runId="r1", providerRoute=_route(), problemText="题干" * 10,
                                     lessonId="../evil")

    def test_short_problem_rejected(self):
        with self.assertRaises(ValidationError):
            AnimatedLessonRunRequest(runId="r1", providerRoute=_route(), problemText="短")

    def test_route_grant_required_when_enforced(self):
        with mock.patch.dict(os.environ, {"MATH_AGENT_REQUIRE_ROUTE_GRANT": "true"}):
            with self.assertRaises(ValidationError):
                AnimatedLessonRunRequest(runId="r1", providerRoute=_route(), problemText="题干" * 10)


class ValidatorAlignmentTests(unittest.TestCase):
    """worker 加载的原型校验器必须认可全部 4 份已验收分镜——schema 漂移的哨兵。"""

    def test_real_lessons_pass_worker_loaded_validator(self):
        from app.animated_lesson_runtime import _load_validator
        validate = _load_validator(TOOLS_DIR)
        for lid in REAL_LESSONS:
            sb = json.loads((TOOLS_DIR / "lessons" / f"{lid}.json").read_text(encoding="utf-8"))
            self.assertEqual(validate(sb), [], f"{lid} 校验应无错误")


class SanitizerTests(unittest.TestCase):
    def test_corpus_markers_normalized_for_gateway_safety(self):
        # Terra 内容安全对"答案/解析"字样确定性 403（q-016 实锤），词表与原型 gen_storyboard 对齐。
        raw = "题干。\n【答案】13\n【解析】\n【分析】利用离心率"
        clean = _sanitize_problem_text(raw)
        self.assertNotIn("【答案】", clean)
        self.assertNotIn("【解析】", clean)
        self.assertIn("【结论】13", clean)
        self.assertIn("【推导过程】", clean)


class ExtractJsonTests(unittest.TestCase):
    def test_fence_and_raw_and_garbage(self):
        self.assertEqual(_extract_json('```json\n{"a": 1}\n```'), {"a": 1})
        self.assertEqual(_extract_json('{"a": 1}'), {"a": 1})
        self.assertIsNone(_extract_json("对不起，我无法"))
        self.assertIsNone(_extract_json("[1,2]"))  # 顶层数组不是分镜对象


class RuntimePlumbingTests(unittest.TestCase):
    def _request(self, storyboard: dict, **kw) -> AnimatedLessonRunRequest:
        return AnimatedLessonRunRequest(
            runId="run-plumb-1", providerRoute=_route(), problemText="题干文本" * 10,
            storyboard=storyboard, render=kw.get("render", False), lessonId=kw.get("lessonId"))

    def test_provided_storyboard_skips_chat_and_writes_artifact(self):
        sb = json.loads((TOOLS_DIR / "lessons" / "tangent-angle.json").read_text(encoding="utf-8"))

        def forbidden_chat(*args, **kwargs):
            raise AssertionError("投喂分镜路径不得触达 provider")

        with TemporaryDirectory() as tmp, mock.patch.dict(os.environ, {
                "ANIMATED_LESSON_OUTPUT_DIR": tmp, "ANIMATED_LESSON_TOOLS_DIR": str(TOOLS_DIR)}):
            result = AnimatedLessonRuntime(chat=forbidden_chat).run(self._request(sb, lessonId="plumb-ok"))
            self.assertEqual(result["status"], "COMPLETED")
            self.assertEqual(result["lessonId"], "plumb-ok")
            # 模型自报 id 被强制覆盖为 Java 侧可预测的 lesson_id（路径注入防线）。
            written = json.loads(Path(result["storyboardPath"]).read_text(encoding="utf-8"))
            self.assertEqual(written["id"], "plumb-ok")
            self.assertIsNone(result["videoPath"])
            self.assertIsNone(result["providerName"])
            self.assertEqual(result["chapters"], [])

    def test_invalid_provided_storyboard_rejected_422(self):
        bad = {"id": "bad", "version": "lesson-plan/v1"}
        with TemporaryDirectory() as tmp, mock.patch.dict(os.environ, {
                "ANIMATED_LESSON_OUTPUT_DIR": tmp, "ANIMATED_LESSON_TOOLS_DIR": str(TOOLS_DIR)}):
            with self.assertRaises(HTTPException) as ctx:
                AnimatedLessonRuntime(chat=_never).run(self._request(bad))
        self.assertEqual(ctx.exception.status_code, 422)

    def test_missing_pipeline_fails_closed_503(self):
        sb = json.loads((TOOLS_DIR / "lessons" / "tangent-angle.json").read_text(encoding="utf-8"))
        with TemporaryDirectory() as tmp, mock.patch.dict(os.environ, {
                "ANIMATED_LESSON_OUTPUT_DIR": tmp, "ANIMATED_LESSON_TOOLS_DIR": str(Path(tmp) / "nope")}):
            with self.assertRaises(HTTPException) as ctx:
                AnimatedLessonRuntime(chat=_never).run(self._request(sb))
        self.assertEqual(ctx.exception.status_code, 503)


def _never(*args, **kwargs):  # pragma: no cover - 触达即测试失败
    raise AssertionError("chat must not be called")


class RouteAuthTests(unittest.TestCase):
    def test_worker_key_enforced_on_animated_lessons(self):
        from fastapi.testclient import TestClient
        with mock.patch.dict(os.environ, {"MATH_AGENT_WORKER_API_KEY": "test-key"}):
            from app import server
            client = TestClient(server.app)
            resp = client.post("/v1/animated-lessons/sync", json={
                "runId": "r", "providerRoute": _route(), "problemText": "题干" * 10})
            self.assertEqual(resp.status_code, 401)
            resp = client.post("/v1/animated-lessons/sync",
                               headers={"Authorization": "Bearer test-key"},
                               json={"runId": "r", "providerRoute": _route(), "problemText": "短"})
            self.assertEqual(resp.status_code, 422)  # 合同层拒绝过短题干


if __name__ == "__main__":
    unittest.main()
