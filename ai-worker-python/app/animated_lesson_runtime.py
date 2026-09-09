# -*- coding: utf-8 -*-
"""animated_lesson workload——「一题一课」动画讲题 agent 的 Python 执行体（2026-09-09）。

链路：题干文本 → Terra 生成 lesson-plan/v1 分镜（封闭算子集，AI 是教学正文唯一作者）
→ 结构校验（validate_storyboard）错误回喂自修 → render_lesson.py 子进程渲染
（Manim 逐笔动画 + edge-tts 配音 + 章节封装）→ 返回产物路径与章节表给 Java。

边界纪律（AGENTS.md）：
- 本模块不写任何教学文字、不选图、不落数据库；产物写文件系统，Java 按任务读取并负责鉴权分发。
- provider 调用必须走注入的 chat 可调用（migrated_workload_runtime.chat_messages），
  保证 UsageLedger 记账与 route grant 语义和讲解/摘要一致。
- 渲染管线复用 tools/animated-lesson 原型（老板 2026-09-08 拍板"已做好的当主链路"）；
  worker 只负责合同、校验回路、子进程编排。容器基底尚无 manim/ffmpeg/xelatex 时，
  通过 ANIMATED_LESSON_TOOLS_DIR / ANIMATED_LESSON_PYTHON 指向具备渲染环境的解释器，
  缺环境直接 503 fail-closed，不做无声降级。
"""
from __future__ import annotations

import importlib.util
import json
import logging
import os
import re
import subprocess
import sys
from pathlib import Path
from typing import Any, Callable
from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from fastapi import HTTPException

from app.ai_run_runtime import ProviderRoute
from app.workload_runtime import ProviderResult

logger = logging.getLogger(__name__)

# 仓库根：ai-worker-python/app/ 上两级。tools 原型与 output 产物都相对它定位。
REPO_ROOT = Path(__file__).resolve().parents[2]
LESSON_ID_RE = re.compile(r"^[a-z0-9][a-z0-9-]{0,62}$")
FENCE_RE = re.compile(r"```(?:json)?\s*\n(.*?)```", re.S)
# 分镜 JSON 约 6-10k token。预算取原型 gen_storyboard 验证过的 8000：2026-09-09 实测
# Terra 网关拒绝过 16000 的 max_tokens（与上下文窗口校验冲突），盲目加大反而整单失败。
STORYBOARD_MAX_TOKENS = int(os.getenv("ANIMATED_LESSON_MAX_TOKENS", "8000"))
# 2026-09-09 Terra 网关劣化（ping 7.9s、长调用挂死）教训：单次调用超时封顶 300s，
# 超时即由 _call_json 换 fallback provider，绝不无限等；存活预检走既有 /v1/provider-health/sync。
GENERATION_TIMEOUT_SECONDS = float(os.getenv("ANIMATED_LESSON_CHAT_TIMEOUT_SECONDS", "300"))


class AnimatedLessonRunRequest(BaseModel):
    """Java 签发的动画讲题运行请求。problemText 是唯一教学输入；storyboard 允许跳过生成只渲染。"""

    model_config = ConfigDict(extra="forbid")

    runId: str = Field(min_length=1, max_length=128)
    providerRoute: ProviderRoute
    problemText: str = Field(min_length=10, max_length=20_000)
    lessonId: str | None = Field(default=None, max_length=64)
    render: bool = True
    storyboard: dict[str, Any] | None = None

    @field_validator("lessonId")
    @classmethod
    def lesson_id_slug(cls, value: str | None) -> str | None:
        # 产物目录名与 chapters.json 的 id 同源，必须防路径注入（值会进文件路径与子进程参数）。
        if value is not None and not LESSON_ID_RE.match(value):
            raise ValueError("lessonId must be a lowercase slug [a-z0-9-]")
        return value

    @model_validator(mode="after")
    def verify_route(self) -> "AnimatedLessonRunRequest":
        # 与 learning_intent/student_explanation 等合同同位：grant 绑定校验在模型层完成，
        # 跨运行/跨 workload 复用直接 422，不进执行体。
        self.providerRoute.verify_for(self.runId, "animated_lesson")
        return self


def _load_validator(tools_dir: Path) -> Callable[[dict], list[str]]:
    """按文件路径加载原型校验器，避免把 tools/ai_gen 塞进 sys.path 造成包名污染。"""
    spec = importlib.util.spec_from_file_location("lesson_storyboard_validator",
                                                  tools_dir / "ai_gen" / "validate_storyboard.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)  # type: ignore[union-attr]
    return module.validate


def _sanitize_problem_text(text: str) -> str:
    """corpus 题面自带的【答案】【解析】标记会触发 Terra 网关内容安全 403（2026-09-09 q-016 实锤），
    属确定性拒绝。这里只做同义标记替换（传输层净化，不改任何教学语义），与原型
    ai_gen/gen_storyboard.py 的归一保持同一词表。"""
    for src, dst in (("【答案】", "【结论】"), ("【解析】", "【推导过程】"), ("【分析】", "【思路】")):
        text = text.replace(src, dst)
    return text


def _extract_json(content: str) -> dict[str, Any] | None:
    """模型可能回围栏块（低预算通道）或裸 JSON（response_format=json_object 通道），两种都收。"""
    match = FENCE_RE.search(content)
    text = match.group(1) if match else content
    try:
        parsed = json.loads(text)
    except json.JSONDecodeError:
        return None
    return parsed if isinstance(parsed, dict) else None


class AnimatedLessonRuntime:
    """生成-校验-渲染闭环的执行体。chat 注入自 migrated_workload_runtime().chat_result，
    返回带 provider 审计字段的 ProviderResult（网关换链后上游要能看出实际用了谁家）。"""

    def __init__(self, chat: Callable[..., ProviderResult]) -> None:
        self._chat = chat

    def run(self, request: AnimatedLessonRunRequest) -> dict[str, Any]:
        tools_dir = Path(os.getenv("ANIMATED_LESSON_TOOLS_DIR") or REPO_ROOT / "tools" / "animated-lesson")
        if not (tools_dir / "render_lesson.py").exists():
            raise HTTPException(status_code=503, detail="animated-lesson pipeline unavailable")
        validate = _load_validator(tools_dir)
        lesson_id = request.lessonId or f"run-{re.sub(r'[^a-zA-Z0-9-]', '-', request.runId).lower()}"[:64]
        out_root = Path(os.getenv("ANIMATED_LESSON_OUTPUT_DIR") or REPO_ROOT / "output" / "animated-lessons")
        lesson_dir = out_root / lesson_id
        lesson_dir.mkdir(parents=True, exist_ok=True)

        stats = {"attempts": 0, "validate_rounds": 0}
        last_result: ProviderResult | None = None
        if request.storyboard is not None:
            errors = validate(request.storyboard)
            if errors:
                # 外部投喂的分镜只校验不修：修正是 AI 作者职责，Java 重投或回生成路径。
                raise HTTPException(status_code=422, detail="storyboard invalid: " + "; ".join(errors[:10]))
            storyboard = request.storyboard
        else:
            storyboard, last_result = self._generate(request, tools_dir, validate, lesson_id, stats)

        # 强制 id=lesson_id：产物目录、chapters.json、视频名全部由 Java 侧 runId 预测，
        # 模型自报的 id 只当展示文本，不给它决定文件路径的机会。
        storyboard["id"] = lesson_id
        storyboard_path = lesson_dir / "storyboard.json"
        storyboard_path.write_text(json.dumps(storyboard, ensure_ascii=False, indent=2), encoding="utf-8")

        video_path: str | None = None
        chapters_path: str | None = None
        chapters: list[dict[str, Any]] = []
        duration = 0.0
        if request.render:
            self._render(tools_dir, storyboard_path, out_root)
            chapters_path = str(lesson_dir / "chapters.json")
            manifest = json.loads((lesson_dir / "chapters.json").read_text(encoding="utf-8"))
            chapters = manifest.get("chapters", [])
            duration = float(manifest.get("total", 0.0))
            video_path = str(lesson_dir / "final.mp4")
            if not (lesson_dir / "final.mp4").exists():
                raise HTTPException(status_code=500, detail="render reported success but final.mp4 missing")

        return {
            "status": "COMPLETED",
            "lessonId": lesson_id,
            "storyboardPath": str(storyboard_path),
            "videoPath": video_path,
            "chaptersPath": chapters_path,
            "durationSec": duration,
            "chapters": chapters,
            "problem": storyboard.get("problem", {}),
            "stats": stats,
            # provider 审计三字段随最后一次模型调用上报；外部投喂分镜（零调用）时为 null。
            "providerName": last_result.provider if last_result else None,
            "modelCode": last_result.model if last_result else None,
            "usage": last_result.usage() if last_result else None,
        }

    def _generate(
            self, request: AnimatedLessonRunRequest, tools_dir: Path,
            validate: Callable[[dict], list[str]], lesson_id: str,
            stats: dict[str, int]) -> tuple[dict[str, Any], ProviderResult]:
        """题目原文 → 分镜 JSON；校验错误逐条回喂，轮数封顶后 422（与 gen_storyboard 实验同纪律）。"""
        schema_doc = (tools_dir / "SCHEMA.md").read_text(encoding="utf-8")
        example = (tools_dir / "lessons" / "tangent-min.json").read_text(encoding="utf-8")
        system = (
            "你是数学教学动画的分镜编剧。只输出一份符合下述 schema 的 lesson-plan/v1 分镜 JSON，"
            "禁止输出任何其他代码或解释。\n\n# SCHEMA\n" + schema_doc +
            "\n\n# 完整输出范例（另一道题，学其结构与教学语气，几何内容不得照抄）\n" + example
        )
        # 2026-09-09 q-016 实验教训：Terra 网关对提示词里"答案/解析"字样做内容安全 403，
        # 属确定性拒绝（重试/换家都救不了），措辞必须避开这两个词。
        user = ("请为下面这道题编写分镜。题目原文含结论与推导过程，教学步骤必须与推导一致，"
                "章节 4~6 个，每章 narration 为口语化中文 2~4 句。id 用 " + lesson_id +
                "。\n\n# 题目\n" + _sanitize_problem_text(request.problemText))
        messages: list[dict[str, Any]] = [{"role": "system", "content": system},
                                          {"role": "user", "content": user}]
        rounds = int(os.getenv("ANIMATED_LESSON_MAX_VALIDATE_ROUNDS", "3"))
        last_errors: list[str] = []
        for _ in range(rounds):
            stats["attempts"] += 1
            # 超时 300s 由 chat_result 传给 provider 传输层；Terra 这类网关挂死会按时抛出，
            # _call_json 内换 fallback provider，不在一家上干等。
            result = self._chat(request.runId, request.providerRoute, messages,
                                timeout_seconds=GENERATION_TIMEOUT_SECONDS,
                                max_tokens=STORYBOARD_MAX_TOKENS)
            parsed = _extract_json(result.content)
            if parsed is None:
                messages.append({"role": "assistant", "content": result.content[:2000]})
                messages.append({"role": "user", "content": "输出不是合法 JSON 对象，请只输出完整分镜 JSON。"})
                continue
            last_errors = validate(parsed)
            if last_errors:
                stats["validate_rounds"] += 1
                messages.append({"role": "assistant", "content": json.dumps(parsed, ensure_ascii=False)[:4000]})
                messages.append({"role": "user", "content": "校验发现以下问题，逐条修复后重新输出完整分镜：\n"
                                 + "\n".join("- " + e for e in last_errors)})
                continue
            return parsed, result
        raise HTTPException(status_code=422, detail="storyboard generation failed: "
                            + "; ".join(last_errors[:10]))

    @staticmethod
    def _render(tools_dir: Path, storyboard_path: Path, out_root: Path) -> None:
        """子进程跑确定性渲染管线；失败把日志尾部上抛，供 Java 记 FAILED 与人工排查。"""
        python_bin = os.getenv("ANIMATED_LESSON_PYTHON") or sys.executable
        env = dict(os.environ, PYTHONIOENCODING="utf-8", LESSON_OUT_DIR=str(out_root))
        timeout = float(os.getenv("ANIMATED_LESSON_RENDER_TIMEOUT_SECONDS", "2400"))
        try:
            proc = subprocess.run(
                [python_bin, str(tools_dir / "render_lesson.py"), str(storyboard_path)],
                capture_output=True, text=True, encoding="utf-8", errors="replace",
                env=env, cwd=str(tools_dir), timeout=timeout,
            )
        except subprocess.TimeoutExpired as exc:
            raise HTTPException(status_code=504, detail="render timed out") from exc
        if proc.returncode != 0:
            tail = ((proc.stdout or "") + (proc.stderr or ""))[-2000:]
            raise HTTPException(status_code=500, detail="render failed: " + tail)
