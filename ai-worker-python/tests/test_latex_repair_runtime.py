"""XeLaTeX model-repair loop tests: structural gate keeps a repair from dropping content."""

from __future__ import annotations

import json
import os
from unittest.mock import patch

from fastapi import HTTPException
import pytest

from app import latex_repair_runtime
from app.latex_repair_runtime import LatexRepairRequest, LatexRepairRuntime, validate_repair


DOCUMENT = (
    "\\documentclass[UTF8]{ctexart}\n\\begin{document}\n"
    "\\section{1 例题}\n$\\frac{1}{2}$ [[HANDOUTIMAGE:assets/a.png:题干图]]\n"
    "\\section{2 变式}\n$x^2$ 的取值范围。\n"
    "\\end{document}\n"
)


def _request(**overrides) -> LatexRepairRequest:
    base = {"runId": "run-latex-001", "latexSource": DOCUMENT, "compilerError": "! Missing $ inserted."}
    base.update(overrides)
    return LatexRepairRequest.model_validate(base)


def test_validate_repair_accepts_syntax_only_changes():
    repaired = DOCUMENT.replace("$\\frac{1}{2}$", "$\\frac{1}{2}$ ")
    assert validate_repair(DOCUMENT, repaired) == []


def test_validate_repair_rejects_dropped_markers_and_envelope():
    missing_image = DOCUMENT.replace("[[HANDOUTIMAGE:assets/a.png:题干图]]", "")
    problems = validate_repair(DOCUMENT, missing_image)
    assert "REPAIR_IMAGE_MARKERS_CHANGED" in problems
    assert "REPAIR_IMAGE_MARKERS_CHANGED" in validate_repair(DOCUMENT, "\\begin{document}x\\end{document}")
    no_heading = DOCUMENT.replace("\\section{2 变式}", "变式")
    assert "REPAIR_QUESTION_HEADINGS_CHANGED" in validate_repair(DOCUMENT, no_heading)
    assert "REPAIR_TRUNCATED" in validate_repair(DOCUMENT, DOCUMENT[:50] + "\\end{document}")
    assert "REPAIR_INFLATED" in validate_repair(DOCUMENT, DOCUMENT + "a" * (len(DOCUMENT) * 2))


class _Response:
    def __init__(self, payload):
        self._payload = payload
        self.text = json.dumps(payload)

    def raise_for_status(self):
        return None

    def json(self):
        return self._payload


def _session_factory(responses):
    """Fake session where each POST pops the next canned completion (OpenAI shape)."""
    queue = iter(responses)

    class Session:
        def post(self, *args, **kwargs):
            return _Response(next(queue))
    return Session()


def test_repair_returns_document_after_stripping_fences():
    fenced = "```latex\n" + DOCUMENT.replace("! Missing $ inserted.", "") + "\n```"
    runtime = LatexRepairRuntime(session=_session_factory(
        [{"choices": [{"message": {"content": fenced}}], "usage": {}}]))
    with patch.dict(os.environ, {
        "OPENAI_API_KEY": "k", "MATH_AGENT_LATEX_REPAIR_PROVIDERS": "openai",
        "MATH_AGENT_USAGE_JSONL_PATH": "",
    }, clear=False), patch("app.latex_repair_runtime.UsageLedger.append"):
        result = runtime.repair(_request())
    assert result["status"] == "REPAIRED"
    assert result["repairedLatex"].startswith("\\documentclass")
    assert "```" not in result["repairedLatex"]


def test_repair_is_rejected_without_ending_up_as_visible_success():
    runtime = LatexRepairRuntime(session=_session_factory(
        [{"choices": [{"message": {"content": "随便写一点东西"}}], "usage": {}}]))
    with patch.dict(os.environ, {"OPENAI_API_KEY": "k", "MATH_AGENT_LATEX_REPAIR_PROVIDERS": "openai"}, clear=False), \
            patch("app.latex_repair_runtime.UsageLedger.append"):
        result = runtime.repair(_request())
    assert result["status"] == "REJECTED"
    assert "REPAIR_MISSING_DOCUMENT_ENVELOPE" in result["problems"]
    assert "repairedLatex" not in result


def test_repair_rotates_providers_on_transport_failure_then_503():
    import requests

    class BrokenSession:
        def post(self, *args, **kwargs):
            raise requests.ConnectionError("dns down")

    runtime = LatexRepairRuntime(session=BrokenSession())
    with patch.dict(os.environ, {
        "OPENAI_API_KEY": "k", "DEEPSEEK_API_KEY": "k2",
        "MATH_AGENT_LATEX_REPAIR_PROVIDERS": "openai,deepseek",
    }, clear=False), patch("app.latex_repair_runtime.UsageLedger.append"):
        with pytest.raises(HTTPException) as error:
            runtime.repair(_request())
    assert error.value.status_code == 503
    assert "openai:ConnectionError" in error.value.detail
    assert "deepseek:ConnectionError" in error.value.detail
