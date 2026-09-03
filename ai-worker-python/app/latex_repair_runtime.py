"""AI repair loop for XeLaTeX compile failures on the handout export path.

Java owns the compiler; its deterministic sanitizers fix known transport damage
(bare-math, split vectors, control-character commands). When the real XeLaTeX run
still fails, that previously meant a recovery stub PDF with no lesson content. Now
Java hands the failing full document plus the compiler's error excerpt to this
endpoint; the model — the only author allowed to touch visible teaching text —
returns a syntax-repaired copy of the same document, which Java recompiles.

The endpoint never claims success that the document cannot support: structural
checks (document envelope, image-marker count, size band, question-heading count)
must all pass or the repair is rejected with a validation report, keeping the old
recovery-stub behavior as the honest fallback. No file paths, no tools, one bounded
non-streaming call.
"""

from __future__ import annotations

import os
import re
from typing import Any

from fastapi import HTTPException
from pydantic import BaseModel, Field, field_validator

from app import provider_profiles
from app.usage import UsageEvent, UsageLedger, cost_for, fallback_tokens

DEFAULT_LATEX_REPAIR_TIMEOUT_SECONDS = 60.0
DEFAULT_LATEX_REPAIR_MAX_OUTPUT_TOKENS = 16_000
# 修复输入是 Java sanitize 后的完整 XeLaTeX 文档；上限防御异常大的任务体。
MAX_LATEX_REPAIR_SOURCE_CHARS = 200_000
MAX_COMPILER_ERROR_CHARS = 4_000

_IMAGE_MARKER = re.compile(r"\[\[HANDOUTIMAGE:[^\]]*\]\]")
# 与 Java NUMBERED_QUESTION_HEADING 的意图一致：题号小节标题是内容单位的锚点。
_NUMBERED_QUESTION_HEADING = re.compile(r"^\\(?:sub)?(?:sub)?section\*?\{\s*\d+[\.、\s]", re.MULTILINE)
_DOCUMENT_BEGIN = "\\begin{document}"
_DOCUMENT_END = "\\end{document}"


class LatexRepairRequest(BaseModel):
    """One repair round: failing document + real compiler excerpt."""

    run_id: str = Field(min_length=1, max_length=200, alias="runId")
    latex_source: str = Field(min_length=1, max_length=MAX_LATEX_REPAIR_SOURCE_CHARS, alias="latexSource")
    compiler_error: str = Field(default="", max_length=MAX_COMPILER_ERROR_CHARS, alias="compilerError")
    turn: int = Field(default=1, ge=1, le=5)

    model_config = {"populate_by_name": True}

    @field_validator("run_id")
    @classmethod
    def run_id_must_be_safe(cls, value: str) -> str:
        value = value.strip()
        if not value or any(char in value for char in "/\\\x00"):
            raise ValueError("runId must be an opaque identifier")
        return value


def repair_provider_order() -> list[str]:
    """Provider rotation for repairs, defaulting to the handout generation order env."""
    configured = os.getenv(
        "MATH_AGENT_LATEX_REPAIR_PROVIDERS",
        os.getenv("MATH_AGENT_HANDOUT_PROVIDER_ORDER", "openai"))
    return [item.strip().lower() for item in configured.split(",") if item.strip()]


def _strip_code_fences(text: str) -> str:
    """模型偶尔无视禁用围栏要求仍包一层 ```；只剥外层围栏，不信任内容。"""
    candidate = text.strip()
    if candidate.startswith("```"):
        candidate = re.sub(r"^```[a-zA-Z]*\s*", "", candidate)
        candidate = re.sub(r"\s*```\s*$", "", candidate)
    return candidate


def validate_repair(original: str, repaired: str) -> list[str]:
    """Deterministic structural checks; every violation code blocks publication of the repair."""
    problems: list[str] = []
    if _DOCUMENT_BEGIN not in repaired or _DOCUMENT_END not in repaired:
        problems.append("REPAIR_MISSING_DOCUMENT_ENVELOPE")
    if _IMAGE_MARKER.findall(original) != _IMAGE_MARKER.findall(repaired):
        # 图片标记是授权资产的唯一载体：数量或顺序变化即视为丢内容/造内容。
        problems.append("REPAIR_IMAGE_MARKERS_CHANGED")
    original_questions = _NUMBERED_QUESTION_HEADING.findall(original)
    repaired_questions = _NUMBERED_QUESTION_HEADING.findall(repaired)
    if len(repaired_questions) != len(original_questions):
        problems.append("REPAIR_QUESTION_HEADINGS_CHANGED")
    if len(repaired) < len(original) * 0.5:
        problems.append("REPAIR_TRUNCATED")
    if len(repaired) > len(original) * 1.6:
        # 纯语法修复不应显著改变文档规模；相对阈值对大小文档一视同仁。
        problems.append("REPAIR_INFLATED")
    return problems


class LatexRepairRuntime:
    """Single-call model repair with provider rotation and fail-closed structural validation."""

    def __init__(self, session: Any | None = None) -> None:
        # 与其余 runtime 一样允许注入 session（requests.Session 或测试替身）。
        self._session = session
        self._ledger = UsageLedger()

    def _prompt(self, request: LatexRepairRequest) -> str:
        return "\n".join([
            "以下 LaTeX 文档被真实 XeLaTeX 编译拒绝。请输出修复后的同一份完整文档。",
            "合同（违反任何一条即修复无效）：",
            "1. 只能修复 TeX 语法：补齐定界符、转义 _ # % & { }、修复 \\left/\\right 配对、环境配对。",
            "2. 题目、解析、图片标记 [[HANDOUTIMAGE:...]]、章节结构必须逐字保留，禁止增删改写教学内容。",
            "3. 直接输出完整文档，不要 Markdown 围栏、不要解释。",
            f"编译器错误摘录（第 {request.turn} 轮）：",
            request.compiler_error or "(未提供错误摘录：请检查未配对定界符与数学模式)",
            "文档：",
            request.latex_source,
        ])

    def repair(self, request: LatexRepairRequest) -> dict[str, Any]:
        """Returns {status, repairedLatex, provider, model, problems} — status REPAIRED only when validated."""
        messages = [
            {"role": "system", "content": "你是受控的 LaTeX 语法修复器，不是内容作者。只输出修复后的完整文档。"},
            {"role": "user", "content": self._prompt(request)},
        ]
        timeout = max(5.0, float(os.getenv(
            "MATH_AGENT_LATEX_REPAIR_TIMEOUT_SECONDS", str(DEFAULT_LATEX_REPAIR_TIMEOUT_SECONDS))))
        max_tokens = max(1024, int(os.getenv(
            "MATH_AGENT_LATEX_REPAIR_MAX_OUTPUT_TOKENS", str(DEFAULT_LATEX_REPAIR_MAX_OUTPUT_TOKENS))))
        failures: list[str] = []
        for attempt, provider in enumerate(repair_provider_order(), 1):
            resolved = provider_profiles.profile(provider)
            api_key, base_url = provider_profiles.credentials(provider)
            if not api_key or not base_url:
                failures.append(f"{provider}:configuration")
                continue
            model = provider_profiles.default_model_chain(provider)
            payload = {
                "model": model,
                "messages": messages,
                "temperature": 0.0,
                "max_tokens": max_tokens,
            }
            try:
                data = provider_profiles.post_completion(
                    resolved, self._session, api_key, base_url, payload, timeout)
                content, reasoning = provider_profiles.extract_message(data)
                repaired = _strip_code_fences(content)
                problems = validate_repair(request.latex_source, repaired)
                usage = data.get("usage") or {}
                prompt_tokens = int(usage.get("prompt_tokens", 0) or 0)
                completion_tokens = int(usage.get("completion_tokens", 0) or 0)
                total_tokens = int(usage.get("total_tokens", 0) or 0)
                source = "provider"
                if total_tokens <= 0:
                    prompt_tokens, completion_tokens, total_tokens = fallback_tokens(messages, content)
                    source = "fallback"
                self._ledger.append(UsageEvent(
                    request.run_id, provider, model, attempt, "SUCCESS", prompt_tokens, completion_tokens,
                    total_tokens, cost_for(provider, model, prompt_tokens, completion_tokens), source))
                if problems:
                    # 结构校验失败是内容安全事件，不轮换 provider 重试：让 Java 走既有 recovery-stub 路径。
                    return {"status": "REJECTED", "problems": problems, "provider": provider, "model": model,
                            "reasoningChars": len(reasoning)}
                return {"status": "REPAIRED", "repairedLatex": repaired, "provider": provider, "model": model,
                        "reasoningChars": len(reasoning), "problems": []}
            except Exception as exc:
                # 传输层失败可轮换；异常类型即诊断码，provider 响应体不外传。
                failures.append(f"{provider}:{type(exc).__name__}")
                self._ledger.append(UsageEvent(
                    request.run_id, provider, model, attempt, "FAILED", 0, 0, 0, -1.0, "unavailable",
                    type(exc).__name__))
        raise HTTPException(status_code=503, detail="latex repair providers unavailable: " + ",".join(failures))
