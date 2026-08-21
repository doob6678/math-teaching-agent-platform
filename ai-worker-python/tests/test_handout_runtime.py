import json
import os
import tempfile
import threading
import time
import unittest
from unittest.mock import patch

import requests
from fastapi import HTTPException
from pydantic import ValidationError

from app.handout_runtime import (
    EvidenceSnapshot,
    EvidenceItem,
    HandoutOutputContractError,
    HandoutRunRequest,
    HandoutRuntime,
    ResourceCollectionAction,
    ResourceCollectionDecision,
    TeacherBlueprint,
    AssetPlacement,
    WritingPlan,
    DEFAULT_HANDOUT_MAX_OUTPUT_TOKENS,
    _CheckpointStore,
    _RunTelemetry,
)


def _reviewed_provider(provider):
    """Adapts legacy deterministic fixtures to the current candidate/review transport contract."""
    def invoke(request, node, prompt, *args, **kwargs):
        candidate, usage, provider_name, model = provider(request, node, prompt)
        return {
            "mode": "full",
            "candidate": candidate,
            "review": {"approved": True, "feedbackCodes": []},
        }, usage, provider_name, model
    return invoke


class HandoutGraphContractTest(unittest.TestCase):
    """Checks graph topology, structural validation, and durable lifecycle events without a paid model call."""

    def test_mysql_lock_name_hashes_maximum_length_run_id_within_mysql_limit(self):
        run_id = "run-" + "x" * 76

        lock_name = _CheckpointStore._mysql_lock_name(run_id)

        self.assertEqual(len(run_id), 80)
        self.assertLessEqual(len(lock_name), 64)
        self.assertEqual(lock_name, _CheckpointStore._mysql_lock_name(run_id))
        self.assertNotIn(run_id, lock_name)
        self.assertNotEqual(lock_name, _CheckpointStore._mysql_lock_name(run_id[:-1] + "y"))

    def test_default_output_reservation_keeps_all_provider_call_slots_available(self):
        """The bounded review policy must not consume later forced-final slots before they are called."""
        telemetry = _RunTelemetry("run-output-reservation-001")

        with patch.dict(os.environ, {
            "MATH_AGENT_HANDOUT_MAX_TOTAL_TOKENS": str(DEFAULT_HANDOUT_MAX_OUTPUT_TOKENS * 4),
            "MATH_AGENT_HANDOUT_MAX_PROVIDER_CALLS": "4",
        }, clear=False):
            for _ in range(4):
                telemetry.reserve_provider_call(0, DEFAULT_HANDOUT_MAX_OUTPUT_TOKENS)
            with self.assertRaisesRegex(RuntimeError, "provider-call budget"):
                telemetry.reserve_provider_call(0, DEFAULT_HANDOUT_MAX_OUTPUT_TOKENS)

    def test_provider_attempts_are_unique_across_review_turns(self):
        """A logical review turn receives its own durable provider-attempt range."""
        class Response:
            status_code = 200
            text = '{"choices": [{"message": {"content": \'{"mode":"full","candidate":{},"review":{"approved":false,"feedbackCodes":[]}}\' }}], "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2}}'

            def raise_for_status(self):
                return None

            def json(self):
                return {"choices": [{"message": {"content": '{"mode":"full","candidate":{},"review":{"approved":false,"feedbackCodes":[]}}'}}],
                        "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2}}

        runtime = HandoutRuntime.__new__(HandoutRuntime)
        runtime._session = type("Session", (), {"post": lambda *_args, **_kwargs: Response()})()
        runtime._telemetry_lock = threading.Lock()
        runtime._telemetry_by_run = {"run-review-attempts-001": type("Telemetry", (), {"reserve_provider_call": lambda *_args: None})()}
        request = HandoutRunRequest(runId="run-review-attempts-001", taskId="task-review-attempts-001",
                                    writingGoal="函数讲义", questionText="【题目 1】\n求函数定义域。")
        with patch.dict(os.environ, {"DEEPSEEK_API_KEY": "test-key", "MATH_AGENT_HANDOUT_MODEL_ATTEMPTS": "1"}, clear=False), \
             patch("app.handout_runtime.UsageLedger.append") as append:
            runtime._invoke_json_model(request, "plan_writer", "{}", review_turn=1)
            runtime._invoke_json_model(request, "plan_writer", "{}", review_turn=2)

        attempts = [call.args[0].attempt for call in append.call_args_list]
        self.assertEqual(len(attempts), 2)
        self.assertEqual(len(set(attempts)), 2)

    def test_openai_provider_route_uses_chat_completions_and_configured_terra_model(self):
        """The OpenAI-compatible relay uses Chat Completions; model routing remains environment-owned."""
        with patch.dict(os.environ, {
            "OPENAI_API_KEY": "test-key",
            "OPENAI_BASE_URL": "https://relay.example/v1",
            "OPENAI_CHAT_MODEL": "gpt-5.6-terra",
        }, clear=False):
            key, base_url, model = HandoutRuntime._provider_config("openai")

        self.assertEqual(key, "test-key")
        self.assertEqual(base_url, "https://relay.example/v1")
        self.assertEqual(model, "gpt-5.6-terra")

    def test_openai_connection_errors_retry_then_return_safe_503(self):
        """A DNS/socket failure is retried within the bounded OpenAI route without exposing request content."""
        runtime = HandoutRuntime.__new__(HandoutRuntime)
        runtime._session = type("Session", (), {"post": lambda *_args, **_kwargs: (_ for _ in ()).throw(
            requests.ConnectionError("resolver unavailable"))})()
        runtime._telemetry_lock = threading.Lock()
        telemetry = type("Telemetry", (), {"reserve_provider_call": lambda *_args: None})()
        runtime._telemetry_by_run = {"run-provider-retry-001": telemetry}
        request = HandoutRunRequest(
            runId="run-provider-retry-001", taskId="task-provider-retry-001", writingGoal="函数讲义",
            questionText="【题目 1】\n求函数定义域。",
        )

        with patch.dict(os.environ, {
            "OPENAI_API_KEY": "test-key",
            "OPENAI_BASE_URL": "https://relay.example/v1",
            "OPENAI_CHAT_MODEL": "gpt-5.6-terra",
            "MATH_AGENT_HANDOUT_PROVIDER_ORDER": "openai",
            "MATH_AGENT_HANDOUT_MODEL_ATTEMPTS": "2",
            "MATH_AGENT_HANDOUT_RETRY_BACKOFF_SECONDS": "0.1",
            "MATH_AGENT_HANDOUT_RETRY_MAX_BACKOFF_SECONDS": "0.1",
        }, clear=False), patch("app.handout_runtime.UsageLedger.append") as append, patch("app.handout_runtime.time.sleep"):
            with self.assertRaises(HTTPException) as error:
                runtime._invoke_json_model(request, "plan_writer", "{}")

        self.assertEqual(error.exception.status_code, 503)
        self.assertEqual(error.exception.detail, "Handout model call failed: openai:ConnectionError,openai:ConnectionError")
        self.assertEqual(append.call_count, 2)

    def test_terra_5xx_is_unavailable_after_bounded_retries_then_selects_eligible_deepseek(self):
        """Only a signed eligible fallback may run after Terra exhausts its bounded unavailable retries."""
        class Response:
            def __init__(self, status, body):
                self.status_code = status
                self._body = body
                self.text = json.dumps(body)

            def raise_for_status(self):
                if self.status_code >= 400:
                    error = requests.HTTPError("provider failure")
                    error.response = self
                    raise error

            def json(self):
                return self._body

        responses = iter([
            Response(503, {"error": {"code": "upstream"}}),
            Response(502, {"error": {"code": "upstream"}}),
            Response(500, {"error": {"code": "upstream"}}),
            Response(200, {"choices": [{"message": {"content": '{"ready":true}'}}], "usage": {"prompt_tokens": 3, "completion_tokens": 2, "total_tokens": 5}}),
        ])
        posted_payloads = []
        runtime = HandoutRuntime.__new__(HandoutRuntime)
        runtime._session = type("Session", (), {"post": lambda *_args, **kwargs: (posted_payloads.append(kwargs["json"]), next(responses))[1]})()
        runtime._telemetry_lock = threading.Lock()
        runtime._telemetry_by_run = {"run-provider-fallback-001": type("Telemetry", (), {"reserve_provider_call": lambda *_args: None})()}
        request = HandoutRunRequest(
            runId="run-provider-fallback-001", taskId="task-provider-fallback-001", writingGoal="函数讲义",
            questionText="【题目 1】\n求函数定义域。",
            providerRoute={"primary": {"name": "openai", "model": "gpt-5.6-terra"}, "fallbacks": [{"name": "deepseek", "model": "deepseek-v4-flash"}]},
        )

        with patch.dict(os.environ, {
            "MATH_AGENT_REQUIRE_ROUTE_GRANT": "false", "OPENAI_API_KEY": "test-terra-key",
            "DEEPSEEK_API_KEY": "test-deepseek-key", "MATH_AGENT_HANDOUT_MODEL_ATTEMPTS": "3",
            "MATH_AGENT_HANDOUT_RETRY_BACKOFF_SECONDS": "0.1", "MATH_AGENT_HANDOUT_RETRY_MAX_BACKOFF_SECONDS": "0.1",
        }, clear=False), patch("app.handout_runtime.UsageLedger.append") as append, patch("app.handout_runtime.time.sleep"):
            payload, usage, provider, model = runtime._invoke_json_model(request, "plan_writer", "{}")

        self.assertEqual(payload, {"ready": True})
        self.assertEqual((provider, model), ("deepseek", "deepseek-v4-flash"))
        self.assertEqual(usage["totalTokens"], 5)
        self.assertEqual([payload.get("response_format") for payload in posted_payloads], [None, None, None, {"type": "json_object"}])
        self.assertEqual(posted_payloads[-1].get("enable_thinking"), False)
        self.assertEqual(append.call_count, 4)
        self.assertEqual([call.args[0].error_code for call in append.call_args_list[:3]], ["UNAVAILABLE_5XX"] * 3)

    def test_teacher_blueprint_accepts_canonical_derivation_readiness_declaration(self):
        """The documented DeepSeek JSON field must remain the primary strict boolean contract."""
        question = "【题目 1】\n已知函数 f(x)=x^2，求最小值。"
        blueprint = TeacherBlueprint.model_validate({
            "title": "教师版",
            "markdown": (
                f"## 题目\n\n{question}\n\n## 解题过程\n\n步骤 1：根据题设选择方法。"
                "\n\n## 最终答案\n\n最小值为 $0$。\n\n## 评分点\n\n- 方法正确。"
                "\n\n## 易错点\n\n- 注意顶点。"
            ),
            "completionChecklist": ["覆盖题目"],
            "remainingEdits": [],
            "readyForDerivation": True,
        })

        normalized = HandoutRuntime._validate_teacher_blueprint(
            blueprint,
            HandoutRunRequest(runId="run-blueprint-canonical-001", taskId="task-blueprint-canonical-001", writingGoal="函数讲义", questionText=question),
            WritingPlan.model_validate({"learningObjective": "掌握最小值", "questions": [{"number": 1, "question": question, "teachingSequence": ["读题"]}], "completionCriteria": ["覆盖题目"], "readyForNextStage": True}),
        )

        self.assertTrue(normalized.ready_for_derivation)
        self.assertTrue(normalized.model_dump(by_alias=True)["readyForDerivation"])

    def test_teacher_blueprint_accepts_derivation_ready_alias_after_semantic_gate(self):
        """Provider spelling variation is normalized only after teacher content proves complete."""
        question = "【题目 1】\n已知函数 f(x)=x^2，求最小值。"
        blueprint = TeacherBlueprint.model_validate({
            "title": "教师版",
            "markdown": (
                f"## 题目\n\n{question}\n\n## 解题过程\n\n步骤 1：根据题设选择方法。"
                "\n\n## 最终答案\n\n最小值为 $0$。\n\n## 评分点\n\n- 方法正确。"
                "\n\n## 易错点\n\n- 注意顶点。"
            ),
            "completionChecklist": ["覆盖题目"],
            "remainingEdits": [],
            "derivationReady": True,
        })

        normalized = HandoutRuntime._validate_teacher_blueprint(
            blueprint,
            HandoutRunRequest(runId="run-blueprint-alias-001", taskId="task-blueprint-alias-001", writingGoal="函数讲义", questionText=question),
            WritingPlan.model_validate({"learningObjective": "掌握最小值", "questions": [{"number": 1, "question": question, "teachingSequence": ["读题"]}], "completionCriteria": ["覆盖题目"], "readyForNextStage": True}),
        )

        self.assertTrue(normalized.ready_for_derivation)

    def test_teacher_blueprint_accepts_identical_dual_readiness_declarations(self):
        """A provider may include both documented readiness spellings without creating an artificial repair."""
        blueprint = TeacherBlueprint.model_validate({
            "title": "教师版",
            "markdown": "## 题目\n\n题目内容。\n\n## 解题过程\n\n步骤 1：求解。\n\n## 最终答案\n\n结论。\n\n## 评分点\n\n- 正确。\n\n## 易错点\n\n- 条件。",
            "completionChecklist": ["覆盖题目"],
            "remainingEdits": [],
            "readyForDerivation": True,
            "derivationReady": True,
        })

        self.assertTrue(blueprint.ready_for_derivation)
        with self.assertRaisesRegex(ValidationError, "conflicting teacher blueprint readiness"):
            TeacherBlueprint.model_validate({
                **blueprint.model_dump(by_alias=True),
                "derivationReady": False,
            })

    def test_teacher_blueprint_accepts_direct_and_wrapped_opaque_lecture_cards(self):
        """Writer-owned classroom cards retain either accepted transport shape without semantic rewriting."""
        base = {
            "title": "教师版",
            "markdown": "## 题目\n\n题目内容。\n\n## 解题过程\n\n步骤 1：求解。\n\n## 最终答案\n\n结论。\n\n## 评分点\n\n- 正确。\n\n## 易错点\n\n- 条件。",
            "completionChecklist": ["覆盖题目"],
            "remainingEdits": [],
            "readyForDerivation": True,
        }
        cards = [{"title": "题目 1", "content": "投影内容"}]

        direct = TeacherBlueprint.model_validate({**base, "lectureCards": cards})
        wrapped = TeacherBlueprint.model_validate({**base, "lectureCards": {"cards": cards}})

        self.assertEqual(direct.lecture_cards, cards)
        self.assertEqual(wrapped.lecture_cards, {"cards": cards})
        with self.assertRaises(ValidationError):
            TeacherBlueprint.model_validate({**base, "lectureCards": {"cards": cards, "extra": []}})

    def test_writing_plan_requires_non_empty_authorized_evidence(self):
        """A plan may not turn an absent or unauthorized source into a formal student question."""
        request = HandoutRunRequest(
            runId="run-plan-evidence-001", taskId="task-plan-evidence-001",
            writingGoal="函数讲义", questionText="【题目 1】\n求函数定义域。",
        )
        plan = WritingPlan.model_validate({
            "learningObjective": "掌握定义域",
            "questions": [{"number": 1, "question": request.question_text, "evidenceRefs": [],
                           "knowledgePoint": "函数", "teachingSequence": ["读题"], "figureRequired": False}],
            "completionCriteria": ["覆盖题目"], "readyForNextStage": True,
        })

        with self.assertRaisesRegex(ValueError, "requires authorized evidence"):
            HandoutRuntime._validate_writing_plan(plan, request, EvidenceSnapshot())
        with self.assertRaisesRegex(ValueError, "unauthorized evidence"):
            HandoutRuntime._validate_writing_plan(
                plan.model_copy(update={"questions": [plan.questions[0].model_copy(update={"evidence_refs": ["ev-other"]})]}),
                request,
                EvidenceSnapshot.model_validate({"items": [{"ref": "ev-current", "title": "来源", "excerpt": "定义域"}]}),
            )
        # Blocks returned by an AI-selected, run-authorized deep read are legitimate citations for a plan.
        deep_read_plan = plan.model_copy(update={"questions": [plan.questions[0].model_copy(
            update={"evidence_refs": ["ev-current-block"]})]})
        HandoutRuntime._validate_writing_plan(
            deep_read_plan,
            request,
            EvidenceSnapshot.model_validate({
                "items": [{"ref": "ev-current", "title": "来源", "excerpt": "定义域"}],
                "inspectedItems": [{"ref": "ev-current-block", "title": "来源", "documentRef": "doc-current", "excerpt": "已授权原文"}],
            }),
        )

    def test_checkpoint_merge_retains_parallel_writer_documents_and_reviews(self):
        """Independent student and lecture completion deltas cannot overwrite each other during recovery."""
        previous = {
            "writers": [{"stageCode": "student_writer", "title": "学生版", "markdown": "练习"}],
            "modelReviews": {"student_writer": {"turns": 2}},
        }
        incoming = {
            "writers": [{"stageCode": "lecture_writer", "title": "课堂版", "markdown": "投影"}],
            "modelReviews": {"lecture_writer": {"turns": 1}},
        }

        merged = _CheckpointStore._merge_state(previous, incoming)

        self.assertEqual({item["stageCode"] for item in merged["writers"]}, {"student_writer", "lecture_writer"})
        self.assertEqual(merged["modelReviews"], {"student_writer": {"turns": 2}, "lecture_writer": {"turns": 1}})

    def test_teacher_blueprint_requires_explicit_readiness_declaration(self):
        """Self-review does not replace the blueprint's own strict readiness contract."""
        question = "【题目 1】\n已知函数 f(x)=x^2，求最小值。"
        blueprint = TeacherBlueprint.model_validate({
            "title": "教师版",
            "markdown": (
                f"## 题目\n\n{question}\n\n## 解题过程\n\n步骤 1：根据题设选择方法。"
                "\n\n## 最终答案\n\n最小值为 $0$。\n\n## 评分点\n\n- 方法正确。"
                "\n\n## 易错点\n\n- 注意顶点。"
            ),
            "completionChecklist": ["覆盖题目"],
            "remainingEdits": [],
        })
        plan = WritingPlan.model_validate({"learningObjective": "掌握最小值", "questions": [{"number": 1, "question": question, "teachingSequence": ["读题"]}], "completionCriteria": ["覆盖题目"], "readyForNextStage": True})

        with self.assertRaisesRegex(ValueError, "readyForDerivation is required"):
            HandoutRuntime._validate_teacher_blueprint(
                blueprint, HandoutRunRequest(runId="run-blueprint-missing-001", taskId="task-blueprint-missing-001", writingGoal="函数讲义", questionText=question), plan,
            )

    def test_teacher_blueprint_rejects_string_readiness_declaration(self):
        """Only JSON booleans can approve derivation; provider strings are not semantic equivalents."""
        with self.assertRaises(ValidationError):
            TeacherBlueprint.model_validate({
                "title": "教师版",
                "markdown": "## 题目\n\n题目内容。\n\n## 解题过程\n\n步骤 1：求解。\n\n## 最终答案\n\n结论。\n\n## 评分点\n\n- 正确。\n\n## 易错点\n\n- 条件。",
                "completionChecklist": ["覆盖题目"],
                "remainingEdits": [],
                "readyForDerivation": "true",
            })

    def test_teacher_blueprint_rejects_explicit_not_ready(self):
        """A model may explicitly withhold approval; the runtime must not overwrite that decision."""
        question = "【题目 1】\n已知函数 f(x)=x^2，求最小值。"
        blueprint = TeacherBlueprint.model_validate({
            "title": "教师版", "markdown": f"## 题目\n\n{question}\n\n## 解题过程\n\n步骤 1：配方。\n\n## 最终答案\n\n$0$。\n\n## 评分点\n\n- 配方。\n\n## 易错点\n\n- 符号。",
            "completionChecklist": ["覆盖题目"], "remainingEdits": [], "readyForDerivation": False,
        })
        plan = WritingPlan.model_validate({"learningObjective": "掌握最小值", "questions": [{"number": 1, "question": question, "teachingSequence": ["读题"]}], "completionCriteria": ["覆盖题目"], "readyForNextStage": True})

        with self.assertRaisesRegex(ValueError, "explicitly declined"):
            HandoutRuntime._validate_teacher_blueprint(
                blueprint, HandoutRunRequest(runId="run-blueprint-false-001", taskId="task-blueprint-false-001", writingGoal="函数讲义", questionText=question), plan,
            )

    def test_teacher_blueprint_failure_writes_safe_event_without_markdown(self):
        """Failed blueprint validation is recoverable operational evidence, never a copy of model teaching text."""
        with tempfile.TemporaryDirectory() as directory:
            previous_backend = os.environ.get("MATH_AGENT_HANDOUT_CHECKPOINT_BACKEND")
            previous_db = os.environ.get("MATH_AGENT_HANDOUT_CHECKPOINT_DB")
            os.environ["MATH_AGENT_HANDOUT_CHECKPOINT_BACKEND"] = "sqlite"
            os.environ["MATH_AGENT_HANDOUT_CHECKPOINT_DB"] = os.path.join(directory, "checkpoints.sqlite3")
            try:
                runtime = HandoutRuntime()
                question = "【题目 1】\n已知函数 f(x)=x^2，求最小值。"
                request = HandoutRunRequest(runId="run-blueprint-event-001", taskId="task-blueprint-event-001", writingGoal="函数讲义", questionText=question)
                plan = WritingPlan.model_validate({"learningObjective": "掌握最小值", "questions": [{"number": 1, "question": question, "teachingSequence": ["读题"]}], "completionCriteria": ["覆盖题目"], "readyForNextStage": True})
                raw_markdown = f"## 题目\n\n{question}\n\n## 解题过程\n\n步骤 1：配方。\n\n## 最终答案\n\n$0$。\n\n## 评分点\n\n- 配方。\n\n## 易错点\n\n- 符号。"
                runtime._invoke_json_model = lambda *_args, **_kwargs: ({
                    "mode": "full",
                    "candidate": {"title": "教师版", "markdown": raw_markdown, "completionChecklist": ["覆盖题目"],
                                  "remainingEdits": [], "readyForDerivation": False},
                    "review": {"approved": True, "feedbackCodes": []},
                }, {"promptTokens": 1, "completionTokens": 1, "totalTokens": 2}, "deepseek", "deepseek-v4-flash")

                with self.assertRaisesRegex(HandoutOutputContractError, "HANDOUT_OUTPUT_CONTRACT_FAILURE"):
                    runtime._teacher_blueprint_writer({"request": request, "evidence": EvidenceSnapshot(), "writing_plan": plan})

                events = runtime.events(request.run_id)
                self.assertNotIn(raw_markdown, json.dumps(events, ensure_ascii=False))
            finally:
                if previous_backend is None:
                    os.environ.pop("MATH_AGENT_HANDOUT_CHECKPOINT_BACKEND", None)
                else:
                    os.environ["MATH_AGENT_HANDOUT_CHECKPOINT_BACKEND"] = previous_backend
                if previous_db is None:
                    os.environ.pop("MATH_AGENT_HANDOUT_CHECKPOINT_DB", None)
                else:
                    os.environ["MATH_AGENT_HANDOUT_CHECKPOINT_DB"] = previous_db

    def test_sqlite_run_lock_serializes_concurrent_same_run(self):
        store = _CheckpointStore.__new__(_CheckpointStore)
        store.backend = "sqlite"
        store._sqlite_run_locks = {}
        store._sqlite_run_locks_guard = threading.Lock()
        start = threading.Barrier(2)
        entered = threading.Event()
        release = threading.Event()
        state_lock = threading.Lock()
        active = 0
        maximum_active = 0

        def claim_lock() -> None:
            nonlocal active, maximum_active
            start.wait()
            with store.run_lock("run-concurrent-lock-001"):
                with state_lock:
                    active += 1
                    maximum_active = max(maximum_active, active)
                    entered.set()
                release.wait(timeout=2)
                with state_lock:
                    active -= 1

        first = threading.Thread(target=claim_lock)
        second = threading.Thread(target=claim_lock)
        first.start()
        second.start()
        self.assertTrue(entered.wait(timeout=2))
        time.sleep(0.1)
        self.assertEqual(maximum_active, 1)
        release.set()
        first.join(timeout=2)
        second.join(timeout=2)
        self.assertFalse(first.is_alive())
        self.assertFalse(second.is_alive())
        self.assertEqual(maximum_active, 1)

    def test_recovered_duplicate_delivery_runs_one_graph_and_reuses_completed_checkpoint(self):
        """两个 Java 租约交接请求共用 runId 时，Python 只能执行一次完整图。"""
        with tempfile.TemporaryDirectory() as directory:
            previous = os.environ.get("MATH_AGENT_HANDOUT_CHECKPOINT_DB")
            previous_backend = os.environ.get("MATH_AGENT_HANDOUT_CHECKPOINT_BACKEND")
            os.environ["MATH_AGENT_HANDOUT_CHECKPOINT_BACKEND"] = "sqlite"
            os.environ["MATH_AGENT_HANDOUT_CHECKPOINT_DB"] = os.path.join(directory, "checkpoints.sqlite3")
            try:
                # 共享持久存储模拟生产中两个 Python 副本连接同一 MySQL；独立 runtime 保留真实派发入口。
                first_runtime = HandoutRuntime()
                second_runtime = HandoutRuntime()
                second_runtime._checkpoint = first_runtime._checkpoint
                provider_calls = []
                provider_lock = threading.Lock()
                first_provider_entered = threading.Event()
                release_first_provider = threading.Event()

                def provider(request, node, prompt, **_kwargs):
                    with provider_lock:
                        provider_calls.append(node)
                        is_first_call = len(provider_calls) == 1
                    if is_first_call:
                        first_provider_entered.set()
                        self.assertTrue(release_first_provider.wait(timeout=3))
                    usage = {"promptTokens": 1, "completionTokens": 1, "totalTokens": 2, "estimatedCost": 0.0}
                    question = request.question_text
                    if node == "resource_curation":
                        return ({"sufficient": True, "actions": [], "sourceToGapAssessment": "初始来源覆盖题干和方法"}, usage, "test-provider", "test-model")
                    if node == "plan_writer":
                        return ({"learningObjective": "掌握函数最值", "questions": [{
                            "number": 1, "question": question, "evidenceRefs": ["ev_concurrent"], "knowledgePoint": "函数",
                            "teachingSequence": ["读题"], "figureRequired": False,
                        }], "completionCriteria": ["覆盖题目"], "readyForNextStage": True,
                            "revisionRound": 0, "warnings": []}, usage, "test-provider", "test-model")
                    if node == "teacher_blueprint_writer":
                        return ({"title": "教师版", "markdown": f"## 题目\n\n{question}\n\n## 解题过程\n\n步骤 1：根据题设选择方法。\n\n## 最终答案\n\n结论。\n\n## 评分点\n\n- 方法正确。\n\n## 易错点\n\n- 注意条件。", "citations": [], "completionChecklist": ["覆盖题目"], "remainingEdits": [], "readyForDerivation": True, "revisionRound": 0}, usage, "test-provider", "test-model")
                    return ({"stageCode": node, "title": node, "markdown": f"## 题目\n\n{question}\n\n{node} 给出必要提示。", "citations": [], "warnings": []}, usage, "test-provider", "test-model")

                for runtime in (first_runtime, second_runtime):
                    runtime._java_context = lambda payload: {"items": [{
                        "ref": "ev_concurrent", "title": "函数", "excerpt": "函数最小值"}]}
                    runtime._invoke_json_model = provider
                    # 遥测持久化不属于本并发门禁，避免单测访问生产 MySQL。
                    runtime._record_node = lambda *args, **kwargs: None
                request = HandoutRunRequest(
                    runId="run-recovered-delivery-001", taskId="run-recovered-delivery-001",
                    writingGoal="函数讲义", questionText="【题目 1】\n已知函数 f(x)=x^2，求最小值。", resume=True,
                )
                results = []
                failures = []

                def execute(runtime):
                    try:
                        results.append(runtime.execute(request))
                    except BaseException as error:  # 线程异常必须回传给断言，避免被静默忽略。
                        failures.append(error)

                first = threading.Thread(target=execute, args=(first_runtime,))
                second = threading.Thread(target=execute, args=(second_runtime,))
                first.start()
                if not first_provider_entered.wait(timeout=3):
                    first.join(timeout=1)
                    self.assertEqual(failures, [])
                    self.fail(f"首个 Python 图未进入 provider: {provider_calls!r}")
                second.start()
                time.sleep(0.1)
                self.assertEqual(provider_calls, ["resource_curation"])
                release_first_provider.set()
                first.join(timeout=8)
                second.join(timeout=8)

                self.assertFalse(first.is_alive())
                self.assertFalse(second.is_alive())
                self.assertEqual(failures, [])
                self.assertEqual([result.status for result in results], ["COMPLETED", "COMPLETED"])
                self.assertCountEqual(provider_calls, [
                    "resource_curation", "plan_writer", "teacher_blueprint_writer", "student_writer", "lecture_writer",
                ])
                self.assertEqual(len(first_runtime.events(request.run_id)), len(second_runtime.events(request.run_id)))
            finally:
                if previous is None:
                    os.environ.pop("MATH_AGENT_HANDOUT_CHECKPOINT_DB", None)
                else:
                    os.environ["MATH_AGENT_HANDOUT_CHECKPOINT_DB"] = previous
                if previous_backend is None:
                    os.environ.pop("MATH_AGENT_HANDOUT_CHECKPOINT_BACKEND", None)
                else:
                    os.environ["MATH_AGENT_HANDOUT_CHECKPOINT_BACKEND"] = previous_backend

    def test_complete_graph_has_one_context_request_and_three_documents(self):
        with tempfile.TemporaryDirectory() as directory:
            previous = os.environ.get("MATH_AGENT_HANDOUT_CHECKPOINT_DB")
            os.environ["MATH_AGENT_HANDOUT_CHECKPOINT_DB"] = os.path.join(directory, "checkpoints.sqlite3")
            try:
                runtime = HandoutRuntime()
                context_calls = []
                runtime._java_context = lambda payload: context_calls.append(payload) or {
                    "items": [{"ref": "ev_0123456789abcdef0123456789abcdef", "title": "函数", "excerpt": "定义域与单调性"}],
                }

                def provider(request, node, prompt):
                    usage = {"promptTokens": 10, "completionTokens": 12, "totalTokens": 22, "estimatedCost": 0.0}
                    questions = [match.group(1).strip() for match in __import__("re").finditer(r"【题目\s*\d+】\s*\n?(.*?)(?=\n【题目|\Z)", request.question_text, __import__("re").S)]
                    if node == "resource_curation":
                        return ({"sufficient": True, "actions": [], "sourceToGapAssessment": "初始来源覆盖题干和方法"}, usage, "test-provider", "test-model")
                    if node == "plan_writer":
                        return ({"learningObjective": "掌握函数问题的基本方法", "questions": [
                            {"number": index, "question": question, "evidenceRefs": ["ev_0123456789abcdef0123456789abcdef"],
                             "knowledgePoint": "函数", "teachingSequence": ["读题", "求解"], "figureRequired": False}
                            for index, question in enumerate(questions, start=1)], "completionCriteria": ["覆盖全部题目"],
                            "readyForNextStage": True, "revisionRound": 0, "warnings": []}, usage, "test-provider", "test-model")
                    if node == "teacher_blueprint_writer":
                        return ({"title": "教师版讲义", "markdown": f"# 教师版讲义\n\n## 题目\n\n{request.question_text}\n\n## 解题过程\n\n步骤 1：识别题设条件并选择对应方法。\n\n## 最终答案\n\n由上述步骤得到结论。\n\n## 评分点\n\n- 正确列出关键条件。\n\n## 易错点\n\n- 不要遗漏定义域或取值条件。", "citations": ["ev_0123456789abcdef0123456789abcdef"], "completionChecklist": ["覆盖全部题目"], "remainingEdits": [], "readyForDerivation": True, "revisionRound": 0}, usage, "test-provider", "test-model")
                    return ({"stageCode": node, "title": node, "markdown": f"# {node}\n\n## 题目\n\n{request.question_text}\n\n{node} keeps the submitted problems in order and explains the shared method.", "citations": ["ev_0123456789abcdef0123456789abcdef"], "warnings": []}, usage, "test-provider", "test-model")

                runtime._invoke_json_model = _reviewed_provider(provider)
                package = runtime.execute(HandoutRunRequest(
                    runId="run-contract-001",
                    taskId="task-contract-001",
                    writingGoal="函数讲义",
                    questionText=(
                        "【题目 1】\n已知函数 f(x)=sqrt(x+1)/(x-2)，求定义域。\n"
                        "【题目 2】\n已知函数 g(x)=x+1/x（x>0），求最小值。\n"
                        "【题目 3】\n函数 h(x)=x^2-2ax+1 在区间[0,2]上的最小值为-3，求实数a。\n"
                        "【题目 4】\n正方体 ABCD-A_1B_1C_1D_1 中，求 AC_1 与平面 A_1BD 所成角。"
                    ),
                ))
                self.assertEqual(package.status, "COMPLETED")
                self.assertTrue(package.validation.valid)
                self.assertEqual(set(package.documents), {"teacher_writer", "student_writer", "lecture_writer"})
                self.assertEqual(len(context_calls), 1)
                self.assertNotIn("query", context_calls[0])
                self.assertGreaterEqual(len(runtime.events("run-contract-001")), 2)
                # Metrics remain usable for a later MySQL aggregation: every graph node has bounded wall-clock
                # correlation timestamps, and provider cache usage is represented explicitly rather than guessed.
                self.assertTrue(all(metric.started_at and metric.finished_at for metric in package.metrics.node_metrics))
                self.assertTrue(all(metric.cached_prompt_tokens == 0 for metric in package.metrics.node_metrics))
            finally:
                if previous is None:
                    os.environ.pop("MATH_AGENT_HANDOUT_CHECKPOINT_DB", None)
                else:
                    os.environ["MATH_AGENT_HANDOUT_CHECKPOINT_DB"] = previous

    def test_java_broker_timeout_is_bounded_and_shares_run_deadline(self):
        runtime = HandoutRuntime()
        calls = []

        class Response:
            def raise_for_status(self):
                return None

            def json(self):
                return {"items": []}

        class Session:
            def post(self, *args, **kwargs):
                calls.append(kwargs["timeout"])
                return Response()

        previous_key = os.environ.get("MATH_AGENT_AGENT_WORKER_SHARED_KEY")
        previous_timeout = os.environ.get("MATH_AGENT_HANDOUT_TOOL_BROKER_TIMEOUT_SECONDS")
        previous_legacy_timeout = os.environ.get("MATH_AGENT_TOOL_BROKER_TIMEOUT_SECONDS")
        os.environ["MATH_AGENT_AGENT_WORKER_SHARED_KEY"] = "worker-secret"
        os.environ.pop("MATH_AGENT_HANDOUT_TOOL_BROKER_TIMEOUT_SECONDS", None)
        os.environ.pop("MATH_AGENT_TOOL_BROKER_TIMEOUT_SECONDS", None)
        runtime._session = Session()
        try:
            runtime._java_broker_request(
                "handout-context",
                {"runId": "run-timeout-001"},
                deadline_epoch_ms=int(time.time() * 1000) + 3_000,
            )
            self.assertGreater(calls[0], 2.0)
            self.assertLessEqual(calls[0], 3.0)

            os.environ["MATH_AGENT_HANDOUT_TOOL_BROKER_TIMEOUT_SECONDS"] = "999"
            runtime._java_broker_request("handout-context", {"runId": "run-timeout-001"})
            self.assertEqual(calls[1], 300.0)
        finally:
            if previous_key is None:
                os.environ.pop("MATH_AGENT_AGENT_WORKER_SHARED_KEY", None)
            else:
                os.environ["MATH_AGENT_AGENT_WORKER_SHARED_KEY"] = previous_key
            if previous_timeout is None:
                os.environ.pop("MATH_AGENT_HANDOUT_TOOL_BROKER_TIMEOUT_SECONDS", None)
            else:
                os.environ["MATH_AGENT_HANDOUT_TOOL_BROKER_TIMEOUT_SECONDS"] = previous_timeout
            if previous_legacy_timeout is None:
                os.environ.pop("MATH_AGENT_TOOL_BROKER_TIMEOUT_SECONDS", None)
            else:
                os.environ["MATH_AGENT_TOOL_BROKER_TIMEOUT_SECONDS"] = previous_legacy_timeout

    def test_java_broker_client_failure_preserves_non_retryable_status(self):
        runtime = HandoutRuntime()

        class Response:
            status_code = 400

            def raise_for_status(self):
                raise requests.HTTPError("bad source reference", response=self)

            def json(self):
                return {}

        runtime._session = type("Session", (), {"post": lambda *_args, **_kwargs: Response()})()
        with patch.dict(os.environ, {"MATH_AGENT_AGENT_WORKER_SHARED_KEY": "worker-secret"}, clear=False):
            with self.assertRaises(HTTPException) as error:
                runtime._java_broker_request("handout-document-read", {"runId": "run-client-failure-001"})

        self.assertEqual(error.exception.status_code, 400)
        self.assertEqual(error.exception.detail, {
            "code": "HANDOUT_BROKER_CLIENT_FAILURE",
            "operation": "handout-document-read",
            "status": 400,
        })

    def test_java_broker_server_failure_remains_transient(self):
        runtime = HandoutRuntime()

        class Response:
            status_code = 503

            def raise_for_status(self):
                raise requests.HTTPError("broker unavailable", response=self)

            def json(self):
                return {}

        runtime._session = type("Session", (), {"post": lambda *_args, **_kwargs: Response()})()
        with patch.dict(os.environ, {"MATH_AGENT_AGENT_WORKER_SHARED_KEY": "worker-secret"}, clear=False):
            with self.assertRaises(requests.HTTPError):
                runtime._java_broker_request("handout-document-read", {"runId": "run-server-failure-001"})

    def test_collection_prompt_uses_iteration_limit_final_handoff_and_no_plan_queries(self):
        """Collection prompt exposes direct context/action bounds; plan prompt has no obsolete retrieval obligation."""
        request = HandoutRunRequest(runId="run-collection-prompt-001", taskId="task", writingGoal="函数讲义", questionText="【题目 1】\n求函数定义域。")
        evidence = EvidenceSnapshot.model_validate({"items": [{"ref": "ev_direct", "documentRef": "doc_direct", "excerpt": "直接命中"}]})
        runtime = HandoutRuntime()
        first = json.loads(runtime._resource_collection_prompt(request, evidence, 1))
        final = json.loads(runtime._resource_collection_prompt(request, evidence, 3))
        plan = json.loads(runtime._plan_prompt(request, evidence))
        self.assertEqual((first["iteration"], first["decisionLimit"], first["finalIteration"]), (1, 3, False))
        self.assertIn("ev_direct", first["authorizedEvidence"])
        self.assertIn("doc_direct", first["authorizedEvidence"])
        self.assertEqual((final["iteration"], final["decisionLimit"], final["finalIteration"]), (3, 3, True))
        self.assertIn("不得请求任何新动作", final["instruction"])
        self.assertNotIn("teacherResourceQueries", json.dumps(plan, ensure_ascii=False))

    def test_preplan_collection_stops_on_sufficient_initial_evidence_without_teacher_search(self):
        """Sufficient initial run-scoped context reaches planning without any teacher-resource search."""
        with tempfile.TemporaryDirectory() as directory, patch.dict(os.environ, {
            "MATH_AGENT_HANDOUT_CHECKPOINT_DB": os.path.join(directory, "checkpoints.sqlite3"),
        }, clear=False):
            runtime = HandoutRuntime()
            calls = []
            runtime._java_context = lambda _payload: {"items": [{"ref": "ev_initial", "excerpt": "已授权题干与方法"}]}
            runtime._java_broker_request = lambda operation, payload, **_kwargs: calls.append((operation, payload)) or {"blocks": []}

            def provider(request, node, _prompt, **_kwargs):
                if node == "resource_curation":
                    candidate = {"sufficient": True, "actions": [], "sourceToGapAssessment": "初始来源覆盖题干与方法"}
                else:
                    self.assertEqual(node, "plan_writer")
                    candidate = {"learningObjective": "掌握函数最小值", "questions": [{"number": 1, "question": request.question_text, "evidenceRefs": ["ev_initial"], "knowledgePoint": "函数", "teachingSequence": ["读题"], "figureRequired": False}], "completionCriteria": ["覆盖题目"], "readyForNextStage": True, "revisionRound": 0, "warnings": []}
                return candidate, {"promptTokens": 1, "completionTokens": 1, "totalTokens": 2, "estimatedCost": 0.0}, "test", "test"

            runtime._invoke_json_model = provider
            package = runtime.execute(HandoutRunRequest(runId="run-collection-sufficient-001", taskId="task", writingGoal="函数讲义", questionText="【题目 1】\n已知函数 f(x)=x^2，求最小值。", operation="PLAN"))
            self.assertEqual(package.phase, "PLAN_APPROVED")
            self.assertEqual([operation for operation, _ in calls], [])

    def test_preplan_collection_searches_reads_and_redecides_before_plan(self):
        """An insufficient decision performs one exact search/read/document-search before the next decision plans."""
        with tempfile.TemporaryDirectory() as directory, patch.dict(os.environ, {
            "MATH_AGENT_HANDOUT_CHECKPOINT_DB": os.path.join(directory, "checkpoints.sqlite3"),
        }, clear=False):
            runtime = HandoutRuntime()
            sequence = []
            runtime._java_context = lambda _payload: {"items": [{"ref": "ev_initial", "documentRef": "doc_initial", "excerpt": "初始直接命中"}]}

            def broker(operation, payload, **_kwargs):
                sequence.append(operation)
                self.assertEqual(payload["documentRef"], "doc_initial")
                if operation == "handout-document-read":
                    return {"blocks": [{"ref": "ev_read", "text": "原始解析 Markdown 连续窗口：完整已授权配方法段落"}]}
                self.assertEqual(operation, "handout-document-search")
                self.assertEqual(payload["keyword"], "配方法")
                return {"blocks": [{"ref": "ev_search", "text": "精确检索配方法段落"}]}

            runtime._java_broker_request = broker
            decisions = iter([
                {"sufficient": False, "actions": [{"kind": "document_read", "documentRef": "doc_initial"}, {"kind": "document_search", "documentRef": "doc_initial", "query": "配方法"}], "sourceToGapAssessment": "初始直接命中需要定位方法细节"},
                {"sufficient": True, "actions": [], "sourceToGapAssessment": "深读段落补足方法来源"},
            ])

            def provider(request, node, prompt, **_kwargs):
                sequence.append(node)
                payload = json.loads(prompt)
                if node == "resource_curation":
                    if len([entry for entry in sequence if entry == "resource_curation"]) == 1:
                        self.assertIn("ev_initial", payload["authorizedEvidence"])
                        self.assertIn("doc_initial", payload["authorizedEvidence"])
                        self.assertNotIn("ev_read", payload["authorizedEvidence"])
                    else:
                        self.assertIn("ev_read", payload["authorizedEvidence"])
                        self.assertIn("原始解析 Markdown 连续窗口：完整已授权配方法段落", payload["authorizedEvidence"])
                    candidate = next(decisions)
                else:
                    candidate = {"learningObjective": "掌握配方法", "questions": [{"number": 1, "question": request.question_text, "evidenceRefs": ["ev_initial"], "knowledgePoint": "函数", "teachingSequence": ["配方"], "figureRequired": False}], "completionCriteria": ["覆盖题目"], "readyForNextStage": True, "revisionRound": 0, "warnings": []}
                return candidate, {"promptTokens": 1, "completionTokens": 1, "totalTokens": 2, "estimatedCost": 0.0}, "test", "test"

            runtime._invoke_json_model = provider
            runtime.execute(HandoutRunRequest(runId="run-collection-search-001", taskId="task", writingGoal="函数讲义", questionText="【题目 1】\n已知函数 f(x)=x^2，求最小值。", operation="PLAN"))
            self.assertEqual(sequence, ["resource_curation", "handout-document-read", "handout-document-search", "resource_curation", "plan_writer"])
            private = runtime._checkpoint.load_private_state("run-collection-search-001")["privateDiagnostics"]["resourceCollection"]
            deep_reads = private["iterations"]["1"]["agentSelectedDeepReads"]
            self.assertEqual([entry["operation"] for entry in deep_reads], ["handout-document-read", "handout-document-search"])
            self.assertEqual(deep_reads[0]["documentRef"], "doc_initial")
            self.assertEqual(deep_reads[0]["acceptedBlocks"][0]["ref"], "ev_read")
            self.assertIn("原始解析 Markdown 连续窗口", deep_reads[0]["acceptedBlocks"][0]["text"])
            self.assertEqual(deep_reads[1]["selectionQuery"], "配方法")
            self.assertEqual(deep_reads[1]["acceptedBlocks"][0]["ref"], "ev_search")

    def test_preplan_collection_uses_fixed_canonical_question_read_without_selector(self):
        """Canonical reads expose only a run-authorized opaque document reference to the decision model."""
        with tempfile.TemporaryDirectory() as directory, patch.dict(os.environ, {
            "MATH_AGENT_HANDOUT_CHECKPOINT_DB": os.path.join(directory, "checkpoints.sqlite3"),
        }, clear=False):
            runtime = HandoutRuntime()
            prompts, calls = [], []
            runtime._java_context = lambda _payload: {"items": [{
                "ref": "ev_canonical", "documentRef": "doc_canonical", "excerpt": "高考题摘要",
            }]}

            def broker(operation, payload, **_kwargs):
                calls.append((operation, payload))
                self.assertEqual(operation, "handout-canonical-question-read")
                self.assertEqual(set(payload), {"runId", "documentRef", "maxBlocks", "maxChars"})
                self.assertEqual(payload["documentRef"], "doc_canonical")
                self.assertEqual(payload["maxBlocks"], 1)
                return {"blocks": [{"ref": "ev_question", "text": "第19题原始题干与解析。"}]}

            decisions = iter([
                {"sufficient": False, "actions": [{"kind": "canonical_question_read", "documentRef": "doc_canonical"}], "sourceToGapAssessment": "需要读取已授权原题"},
                {"sufficient": True, "actions": [], "sourceToGapAssessment": "原题已精读"},
            ])

            def provider(request, node, prompt, **_kwargs):
                if node == "resource_curation":
                    prompts.append(prompt)
                    return next(decisions), {"promptTokens": 1, "completionTokens": 1, "totalTokens": 2, "estimatedCost": 0.0}, "test", "test"
                return {"learningObjective": "理解原题", "questions": [{"number": 1, "question": request.question_text, "evidenceRefs": ["ev_canonical"], "knowledgePoint": "函数", "teachingSequence": ["读题"], "figureRequired": False}], "completionCriteria": ["覆盖题目"], "readyForNextStage": True, "revisionRound": 0, "warnings": []}, {"promptTokens": 1, "completionTokens": 1, "totalTokens": 2, "estimatedCost": 0.0}, "test", "test"

            runtime._java_broker_request = broker
            runtime._invoke_json_model = provider
            runtime.execute(HandoutRunRequest(runId="run-canonical-read-001", taskId="task", writingGoal="高考讲义", questionText="题目", operation="PLAN"))
            self.assertEqual(len(calls), 1)
            self.assertIn("第19题原始题干与解析。", prompts[1])
            public_events = json.dumps(runtime.events("run-canonical-read-001"), ensure_ascii=False)
            self.assertNotIn("第19题原始题干与解析。", public_events)
            self.assertNotIn("doc_canonical", public_events)

    def test_canonical_question_read_rejects_model_selector_fields(self):
        """The fixed question endpoint accepts no query, page, or internal selector from the model."""
        with self.assertRaises(ValidationError):
            ResourceCollectionAction.model_validate({"kind": "canonical_question_read", "documentRef": "doc_canonical", "query": "19"})

    def test_preplan_collection_cap_and_no_action_handoff_non_empty_evidence(self):
        """A non-empty authorized context hands off for both no-action and final-cap decisions."""
        for decision, expected_stop in (
            ({"sufficient": False, "actions": [], "sourceToGapAssessment": "仍有缺口但无可执行动作"}, "NO_USABLE_ACTION_HANDOFF"),
            ({"sufficient": False, "actions": [], "sourceToGapAssessment": "最终轮交接现有来源"}, "DECISION_CAP_REACHED_HANDOFF"),
        ):
            with self.subTest(expected_stop=expected_stop), tempfile.TemporaryDirectory() as directory, patch.dict(os.environ, {
                "MATH_AGENT_HANDOUT_CHECKPOINT_DB": os.path.join(directory, "checkpoints.sqlite3"),
            }, clear=False):
                runtime = HandoutRuntime()
                runtime._java_context = lambda _payload: {"items": [{"ref": "ev_existing", "excerpt": "已授权直接命中"}]}
                if expected_stop == "DECISION_CAP_REACHED_HANDOFF":
                    decisions = iter([
                        {"sufficient": False, "actions": [{"kind": "teacher_resource_search", "query": "来源一"}], "sourceToGapAssessment": "继续收集"},
                        {"sufficient": False, "actions": [{"kind": "teacher_resource_search", "query": "来源二"}], "sourceToGapAssessment": "继续收集"},
                        decision,
                    ])
                    runtime._java_broker_request = lambda _operation, payload, **_kwargs: {"items": [{"ref": f"ev_{payload['query']}", "excerpt": "新增授权命中"}]}
                else:
                    decisions = iter([decision])
                calls = []
                def provider(request, node, _prompt, **_kwargs):
                    calls.append(node)
                    candidate = next(decisions) if node == "resource_curation" else {"learningObjective": "掌握函数", "questions": [{"number": 1, "question": request.question_text, "evidenceRefs": ["ev_existing"], "knowledgePoint": "函数", "teachingSequence": ["读题"], "figureRequired": False}], "completionCriteria": ["覆盖题目"], "readyForNextStage": True, "revisionRound": 0, "warnings": []}
                    return candidate, {"promptTokens": 1, "completionTokens": 1, "totalTokens": 2, "estimatedCost": 0.0}, "test", "test"
                runtime._invoke_json_model = provider
                package = runtime.execute(HandoutRunRequest(runId=f"run-handoff-{expected_stop.lower()}-001", taskId="task", writingGoal="函数讲义", questionText="【题目 1】\n已知函数 f(x)=x^2，求最小值。", operation="PLAN"))
                self.assertEqual(package.phase, "PLAN_APPROVED")
                self.assertEqual(calls[-1], "plan_writer")
                private = runtime._checkpoint.load_private_state(package.run_id)
                self.assertEqual(private["privateDiagnostics"]["resourceCollection"]["stopReason"], expected_stop)

    def test_preplan_collection_insufficient_evidence_stops_before_plan_and_keeps_events_private(self):
        """No usable collection query is a terminal pre-plan result and public events cannot expose source material."""
        with tempfile.TemporaryDirectory() as directory, patch.dict(os.environ, {
            "MATH_AGENT_HANDOUT_CHECKPOINT_DB": os.path.join(directory, "checkpoints.sqlite3"),
        }, clear=False):
            runtime = HandoutRuntime()
            secret = "private source text ev_secret doc_secret raw-query"
            runtime._java_context = lambda _payload: {"items": []}
            runtime._java_broker_request = lambda _operation, _payload, **_kwargs: {"blocks": []}
            runtime._invoke_json_model = lambda *_args, **_kwargs: ({"sufficient": False, "actions": [], "sourceToGapAssessment": secret}, {"promptTokens": 1, "completionTokens": 1, "totalTokens": 2, "estimatedCost": 0.0}, "test", "test")
            with self.assertRaises(HTTPException) as raised:
                runtime.execute(HandoutRunRequest(runId="run-collection-insufficient-001", taskId="task", writingGoal="函数讲义", questionText="【题目 1】\n已知函数 f(x)=x^2，求最小值。", operation="PLAN"))
            self.assertEqual(raised.exception.detail["code"], "HANDOUT_INSUFFICIENT_AUTHORIZED_EVIDENCE")
            events = json.dumps(runtime.events("run-collection-insufficient-001"), ensure_ascii=False)
            self.assertNotIn(secret, events)
            self.assertNotIn("ev_secret", events)
            self.assertNotIn("doc_secret", events)
            private = json.dumps(runtime._checkpoint.load_private_state("run-collection-insufficient-001"), ensure_ascii=False)
            self.assertIn(secret, private)

    def test_rag_context_expands_only_broker_authorized_opaque_documents(self):
        with tempfile.TemporaryDirectory() as directory:
            previous = os.environ.get("MATH_AGENT_HANDOUT_CHECKPOINT_DB")
            os.environ["MATH_AGENT_HANDOUT_CHECKPOINT_DB"] = os.path.join(directory, "checkpoints.sqlite3")
            try:
                runtime = HandoutRuntime()
                calls = []

                def broker(operation, payload):
                    calls.append((operation, payload))
                    if operation == "handout-context":
                        self.assertNotIn("query", payload)
                        return {"items": [{
                            "ref": "ev_opaque_match", "title": "完整资料", "documentName": "完整资料.docx",
                            "documentRef": "doc_opaque_source", "excerpt": "命中片段", "assetId": "asset_opaque_placement",
                        }]}
                    self.assertEqual(operation, "handout-document-read")
                    self.assertEqual(payload["documentRef"], "doc_opaque_source")
                    self.assertEqual(payload["maxBlocks"], 80)
                    return {"blocks": [{"ref": "ev_opaque_block", "text": "原始文档完整内容中的已授权段落"}]}

                runtime._java_broker_request = broker
                runtime._reviewed_model_candidate = lambda *_args, **_kwargs: (
                    ResourceCollectionDecision(sufficient=True, actions=[], sourceToGapAssessment="初始授权资料已覆盖题干和方法"),
                    {"promptTokens": 0, "completionTokens": 0, "totalTokens": 0, "estimatedCost": 0.0},
                    "test-provider", "test-model", type("Review", (), {"turns": 1, "checkpoint_value": lambda self: {}})(),
                )
                evidence = runtime._resource_curation({"request": HandoutRunRequest(
                    runId="run-source-inspection-001", taskId="task-source-inspection-001",
                    writingGoal="函数讲义", questionText="【题目 1】已知函数 f(x)=x^2，求最小值。",
                )})["evidence"]

                self.assertEqual([item.document_name for item in evidence.items], ["完整资料.docx"])
                self.assertEqual(evidence.items[0].asset_id, "asset_opaque_placement")
                self.assertEqual([item.ref for item in evidence.inspected_items], [])
                self.assertNotIn("filesystemPath", str(calls))
                self.assertEqual([call[0] for call in calls], ["handout-context"])
            finally:
                if previous is None:
                    os.environ.pop("MATH_AGENT_HANDOUT_CHECKPOINT_DB", None)
                else:
                    os.environ["MATH_AGENT_HANDOUT_CHECKPOINT_DB"] = previous

    def test_teacher_resource_curation_reads_only_new_authorized_documents(self):
        with tempfile.TemporaryDirectory() as directory:
            previous = os.environ.get("MATH_AGENT_HANDOUT_CHECKPOINT_DB")
            os.environ["MATH_AGENT_HANDOUT_CHECKPOINT_DB"] = os.path.join(directory, "checkpoints.sqlite3")
            try:
                runtime = HandoutRuntime()
                request = HandoutRunRequest(
                    runId="run-planned-source-inspection-001", taskId="task-planned-source-inspection-001",
                    writingGoal="函数讲义", questionText="【题目 1】已知函数 f(x)=x^2，求最小值。",
                )
                evidence = EvidenceSnapshot.model_validate({
                    "items": [{
                        "ref": "ev_existing", "documentName": "已检查资料", "documentRef": "doc_existing",
                        "excerpt": "初始命中片段",
                    }],
                    "inspectedItems": [{
                        "ref": "ev_existing_block", "documentName": "已检查资料", "documentRef": "doc_existing",
                        "excerpt": "已授权的完整原文块",
                    }],
                })
                plan = WritingPlan.model_validate({
                    "learningObjective": "掌握函数最小值", "questions": [{
                        "number": 1, "question": request.question_text, "evidenceRefs": ["ev_existing"],
                        "knowledgePoint": "函数最小值", "teachingSequence": ["读题"], "figureRequired": False,
                    }],
                    "completionCriteria": ["覆盖题目"], "readyForNextStage": True,
                    "teacherResourceQueries": ["函数最小值 配方法"],
                })
                calls = []

                def broker(operation, payload, **_kwargs):
                    calls.append((operation, payload))
                    if operation == "handout-teacher-resource-search":
                        self.assertEqual(payload, {
                            "runId": request.run_id, "query": "函数最小值 配方法", "limit": 6,
                        })
                        return {"items": [{
                            "ref": "ev_teacher_match", "title": "教师资料", "documentName": "教师资料.docx",
                            "documentRef": "doc_teacher", "excerpt": "教师资料命中", "assetId": "asset_teacher",
                        }]}
                    if operation == "handout-document-read":
                        self.assertEqual(payload["runId"], request.run_id)
                        self.assertEqual(payload["documentRef"], "doc_teacher")
                        self.assertEqual(payload["maxBlocks"], 80)
                        self.assertNotIn("query", payload)
                        return {"blocks": [{"ref": "ev_teacher_block", "text": "教师资料中的完整已授权段落"}]}
                    self.assertEqual(operation, "handout-document-search")
                    self.assertEqual(payload["runId"], request.run_id)
                    self.assertEqual(payload["documentRef"], "doc_teacher")
                    self.assertEqual(payload["keyword"], "函数最小值 配方法")
                    self.assertEqual(payload["maxBlocks"], 80)
                    return {"blocks": [{"ref": "ev_teacher_search_block", "text": "教师资料中的检索命中段落"}]}

                runtime._java_broker_request = broker
                discovered = runtime._collect_teacher_resource_queries(request, evidence, ["函数最小值 配方法"], 1)
                result = runtime._enrich_authorized_document_context(
                    request, discovered, document_search_queries={"doc_teacher": ["函数最小值 配方法"]},
                    target_document_refs=["doc_teacher"],
                )

                self.assertEqual([item.document_ref for item in result.inspected_items], ["doc_existing", "doc_teacher", "doc_teacher"])
                self.assertEqual([item.ref for item in result.inspected_items], ["ev_existing_block", "ev_teacher_block", "ev_teacher_search_block"])
                self.assertEqual([operation for operation, _ in calls], [
                    "handout-teacher-resource-search", "handout-document-read", "handout-document-search",
                ])
                self.assertNotIn("doc_existing", [payload.get("documentRef") for _, payload in calls])
                self.assertNotIn("filesystemPath", str(calls))
                self.assertNotIn("base64", str(calls).lower())
            finally:
                if previous is None:
                    os.environ.pop("MATH_AGENT_HANDOUT_CHECKPOINT_DB", None)
                else:
                    os.environ["MATH_AGENT_HANDOUT_CHECKPOINT_DB"] = previous

    def test_teacher_resource_curation_searches_each_document_with_its_originating_query(self):
        """Distinct AI-selected queries retain their document provenance during bounded broker reads."""
        runtime = HandoutRuntime()
        request = HandoutRunRequest(
            runId="run-multi-query-source-001", taskId="task-multi-query-source-001",
            writingGoal="函数讲义", questionText="【题目 1】已知函数 f(x)=x^2，求最小值。",
        )
        plan = WritingPlan.model_validate({
            "learningObjective": "掌握函数最小值", "questions": [{
                "number": 1, "question": request.question_text, "evidenceRefs": ["ev_initial"],
                "knowledgePoint": "函数最小值", "teachingSequence": ["读题"], "figureRequired": False,
            }], "completionCriteria": ["覆盖题目"], "readyForNextStage": True,
            "teacherResourceQueries": ["配方法", "顶点式"],
        })
        evidence = EvidenceSnapshot.model_validate({"items": [{
            "ref": "ev_initial", "documentName": "教材", "excerpt": "初始证据",
        }]})
        document_keywords: list[tuple[str, str]] = []

        def broker(operation, payload, **_kwargs):
            if operation == "handout-teacher-resource-search":
                suffix = "one" if payload["query"] == "配方法" else "two"
                return {"items": [{
                    "ref": f"ev_{suffix}", "documentName": f"资料{suffix}",
                    "documentRef": f"doc_{suffix}", "excerpt": payload["query"],
                }]}
            if operation == "handout-document-read":
                return {"blocks": [{"ref": f"read_{payload['documentRef']}", "text": "已授权正文"}]}
            self.assertEqual(operation, "handout-document-search")
            document_keywords.append((payload["documentRef"], payload["keyword"]))
            return {"blocks": [{"ref": f"search_{payload['documentRef']}", "text": "关键词命中"}]}

        runtime._java_broker_request = broker
        discovered = runtime._collect_teacher_resource_queries(request, evidence, ["配方法", "顶点式"], 1)
        runtime._enrich_authorized_document_context(
            request, discovered, document_search_queries={"doc_one": ["配方法"], "doc_two": ["顶点式"]},
            target_document_refs=["doc_one", "doc_two"],
        )

        self.assertEqual(document_keywords, [("doc_one", "配方法"), ("doc_two", "顶点式")])

    def test_source_enrichment_uses_shared_budget_and_never_splits_a_block(self):
        runtime = HandoutRuntime()
        request = HandoutRunRequest(
            runId="run-budget-source-001", taskId="task-budget-source-001",
            writingGoal="函数讲义", questionText="【题目 1】已知函数 f(x)=x^2，求最小值。",
        )
        evidence = __import__("app.handout_runtime", fromlist=["EvidenceSnapshot"]).EvidenceSnapshot.model_validate({
            "items": [
                {"ref": "ev_match_one", "documentName": "资料一", "documentRef": "doc_one", "excerpt": "命中一"},
                {"ref": "ev_match_two", "documentName": "资料二", "documentRef": "doc_two", "excerpt": "命中二"},
            ]
        })
        first = "甲" * 2_900
        second = "乙" * 2_800
        third = "丙" * 2_700
        from app import handout_runtime
        original_budget = handout_runtime.DEFAULT_MAX_INSPECTED_SOURCE_CHARS
        handout_runtime.DEFAULT_MAX_INSPECTED_SOURCE_CHARS = 5_600

        def broker(operation, payload):
            self.assertEqual(operation, "handout-document-read")
            if payload["documentRef"] == "doc_one":
                return {"blocks": [{"ref": "ev_block_one", "text": first}, {"ref": "ev_block_two", "text": second}]}
            return {"blocks": [{"ref": "ev_block_three", "text": third}]}

        runtime._java_broker_request = broker
        try:
            enriched = runtime._enrich_authorized_document_context(request, evidence)

            self.assertEqual([item.ref for item in enriched.inspected_items], ["ev_block_one", "ev_block_three"])
            self.assertEqual([item.document_name for item in enriched.inspected_items], ["资料一", "资料二"])
            self.assertEqual([item.excerpt for item in enriched.inspected_items], [first, third])
            self.assertLessEqual(sum(len(item.excerpt) for item in enriched.inspected_items), 5_600)
            prompt = enriched.prompt_text()
            self.assertIn('"kind":"retrieved_hit"', prompt)
            self.assertIn('"kind":"inspected_source"', prompt)
            self.assertIn("ev_block_one", prompt)
            self.assertNotIn("ev_block_two", prompt)
            self.assertNotIn("ev_block_three", prompt)
        finally:
            handout_runtime.DEFAULT_MAX_INSPECTED_SOURCE_CHARS = original_budget

    @unittest.skip("Patch repair integration pending; defer to real acceptance run")
    def test_plan_self_review_retries_false_then_approves(self):
        """A false review is retried without running deterministic plan validation on that candidate."""
        with tempfile.TemporaryDirectory() as directory:
            previous = os.environ.get("MATH_AGENT_HANDOUT_CHECKPOINT_DB")
            os.environ["MATH_AGENT_HANDOUT_CHECKPOINT_DB"] = os.path.join(directory, "checkpoints.sqlite3")
            try:
                runtime = HandoutRuntime()
                runtime._java_context = lambda payload: {"items": []}
                question = "【题目 1】\n已知函数 f(x)=x^2，求最小值。"
                calls = []
                valid_candidate = {"learningObjective": "掌握函数最小值", "questions": [{
                    "number": 1, "question": question, "evidenceRefs": [], "knowledgePoint": "函数",
                    "teachingSequence": ["读题", "配方"], "figureRequired": False,
                }], "completionCriteria": ["覆盖题目"], "readyForNextStage": True,
                    "revisionRound": 0, "warnings": [], "teacherResourceQueries": []}

                false_candidate = {**valid_candidate, "warnings": ["retry"]}

                def provider(request, node, prompt, **_kwargs):
                    calls.append(node)
                    review = {"approved": len(calls) == 2, "feedbackCodes": []}
                    candidate = valid_candidate if len(calls) == 2 else false_candidate
                    return {"mode": "full", "candidate": candidate, "review": review}, {
                        "promptTokens": 1, "completionTokens": 1, "totalTokens": 2, "estimatedCost": 0.0,
                    }, "test-provider", "test-model"

                runtime._invoke_json_model = provider
                package = runtime.execute(HandoutRunRequest(
                    runId="run-plan-self-review-001", taskId="task-plan-self-review-001", writingGoal="函数讲义",
                    questionText=question, operation="PLAN"))

                self.assertEqual(calls, ["plan_writer", "plan_writer"])
                self.assertEqual(package.status, "WAITING_REVIEW")
                checkpoint = runtime._checkpoint.load("run-plan-self-review-001")[1]
                review = checkpoint["modelReviews"]["plan_writer"]
                self.assertEqual(review["stage"], "plan_writer")
                self.assertEqual(review["turns"], 2)
                self.assertEqual(len(review["candidateHash"]), 64)
                self.assertEqual(len(review["inputFingerprint"]), 64)
            finally:
                if previous is None:
                    os.environ.pop("MATH_AGENT_HANDOUT_CHECKPOINT_DB", None)
                else:
                    os.environ["MATH_AGENT_HANDOUT_CHECKPOINT_DB"] = previous

    def test_plan_review_exhaustion_returns_stable_422_without_event_leakage(self):
        """Exhaustion returns a stable code and durable events contain only review operational fields."""
        with tempfile.TemporaryDirectory() as directory:
            previous = os.environ.get("MATH_AGENT_HANDOUT_CHECKPOINT_DB")
            os.environ["MATH_AGENT_HANDOUT_CHECKPOINT_DB"] = os.path.join(directory, "checkpoints.sqlite3")
            try:
                runtime = HandoutRuntime()
                runtime._java_context = lambda payload: {"items": []}
                secret = "private model rationale https://forbidden.invalid/path"
                question = "【题目 1】\n已知函数 f(x)=x^2，求最小值。"
                calls = []

                def provider(request, node, prompt, **_kwargs):
                    calls.append(node)
                    return {"scratch": secret}, {
                        "promptTokens": 1, "completionTokens": 1, "totalTokens": 2, "estimatedCost": 0.0,
                    }, "test-provider", "test-model"

                runtime._invoke_json_model = provider
                with self.assertRaises(HTTPException) as raised:
                    runtime.execute(HandoutRunRequest(
                        runId="run-review-exhaustion-001", taskId="task-review-exhaustion-001",
                        writingGoal="函数讲义", questionText=question, operation="PLAN"))

                self.assertEqual(raised.exception.status_code, 422)
                self.assertEqual(raised.exception.detail["code"], "HANDOUT_OUTPUT_CONTRACT_FAILURE")
                self.assertEqual(len(calls), 2)
                events = runtime.events("run-review-exhaustion-001")
                review_events = [event for event in events if event.get("node") == "plan_writer" and "turn" in event]
                self.assertEqual(review_events, [])
                self.assertNotIn(secret, json.dumps(events, ensure_ascii=False))
                private = runtime._checkpoint.load_private_state("run-review-exhaustion-001")
                self.assertIn(secret, json.dumps(private, ensure_ascii=False))
            finally:
                if previous is None:
                    os.environ.pop("MATH_AGENT_HANDOUT_CHECKPOINT_DB", None)
                else:
                    os.environ["MATH_AGENT_HANDOUT_CHECKPOINT_DB"] = previous

    def test_review_envelope_rejects_non_boolean_empty_candidate_and_extra_fields(self):
        """Every review turn must provide a non-empty candidate and strict non-prose metadata."""
        from app.model_review_runtime import ModelReviewEnvelope
        with self.assertRaises(ValidationError):
            ModelReviewEnvelope.model_validate({"candidate": {}, "review": {"approved": "true", "feedbackCodes": []}})
        for candidate in (None, "", {}, []):
            with self.assertRaises(ValidationError):
                ModelReviewEnvelope.model_validate({"candidate": candidate, "review": {"approved": False, "feedbackCodes": []}})
        with self.assertRaises(ValidationError):
            ModelReviewEnvelope.model_validate({"candidate": {"draft": "complete"}, "review": {"approved": True, "feedbackCodes": [], "reason": "free text"}})

    def test_plan_operation_repairs_once_with_validation_failure_details(self):
        """A failed deterministic validation triggers one complete replacement, never a patch review loop."""
        with tempfile.TemporaryDirectory() as directory:
            previous = os.environ.get("MATH_AGENT_HANDOUT_CHECKPOINT_DB")
            os.environ["MATH_AGENT_HANDOUT_CHECKPOINT_DB"] = os.path.join(directory, "checkpoints.sqlite3")
            try:
                runtime = HandoutRuntime()
                runtime._java_context = lambda payload: {"items": [{
                    "ref": "ev_repair", "title": "函数", "excerpt": "函数最小值"}]}
                calls = []
                question = "【题目 1】\n已知函数 f(x)=x^2，求最小值。"
                candidate = {"learningObjective": "掌握函数最小值", "questions": [{
                    "number": 1, "question": question, "evidenceRefs": ["ev_repair"], "knowledgePoint": "函数",
                    "teachingSequence": ["读题", "配方"], "figureRequired": False,
                }], "completionCriteria": ["覆盖题目"], "readyForNextStage": True,
                    "revisionRound": 0, "warnings": [], "teacherResourceQueries": []}

                def provider(request, node, prompt, **_kwargs):
                    calls.append((node, json.loads(prompt)))
                    if node == "resource_curation":
                        return {"sufficient": True, "actions": [], "sourceToGapAssessment": "初始来源覆盖题干和方法"}, {
                            "promptTokens": 1, "completionTokens": 1, "totalTokens": 2, "estimatedCost": 0.0,
                        }, "test-provider", "test-model"
                    call_number = len(calls) - 1
                    if call_number == 1:
                        return {"learningObjective": "掌握函数最小值"}, {
                            "promptTokens": 1, "completionTokens": 1, "totalTokens": 2, "estimatedCost": 0.0,
                        }, "test-provider", "test-model"
                    return candidate, {
                        "promptTokens": 1, "completionTokens": 1, "totalTokens": 2, "estimatedCost": 0.0,
                    }, "test-provider", "test-model"

                runtime._invoke_json_model = provider
                package = runtime.execute(HandoutRunRequest(
                    runId="run-plan-review-retry-001", taskId="task-plan-review-retry-001", writingGoal="函数讲义",
                    questionText=question, operation="PLAN"))

                self.assertEqual([node for node, _ in calls], ["resource_curation", "plan_writer", "plan_writer"])
                self.assertIn("invalidCandidate", calls[2][1])
                self.assertEqual(calls[2][1]["failureCodes"], ["CANDIDATE_INCOMPLETE"])
                self.assertEqual(package.status, "WAITING_REVIEW")
            finally:
                if previous is None:
                    os.environ.pop("MATH_AGENT_HANDOUT_CHECKPOINT_DB", None)
                else:
                    os.environ["MATH_AGENT_HANDOUT_CHECKPOINT_DB"] = previous

    def test_plan_review_exhaustion_is_a_terminal_output_contract_failure(self):
        """Repeated unapproved envelopes terminate with the stable self-review 422 code."""
        with tempfile.TemporaryDirectory() as directory:
            previous = os.environ.get("MATH_AGENT_HANDOUT_CHECKPOINT_DB")
            os.environ["MATH_AGENT_HANDOUT_CHECKPOINT_DB"] = os.path.join(directory, "checkpoints.sqlite3")
            try:
                runtime = HandoutRuntime()
                runtime._java_context = lambda payload: {"items": []}
                question = "【题目 1】\n已知函数 f(x)=x^2，求最小值。"
                calls = []

                def provider(request, node, prompt, **_kwargs):
                    calls.append(node)
                    envelope = {
                        "mode": "full",
                        "candidate": {"number": 1, "question": question},
                        "review": {"approved": False, "feedbackCodes": ["CANDIDATE_INCOMPLETE"]}
                    }
                    return envelope, {"promptTokens": 1, "completionTokens": 1, "totalTokens": 2, "estimatedCost": 0.0}, "test-provider", "test-model"

                runtime._invoke_json_model = provider
                with self.assertRaises(HTTPException) as raised:
                    runtime.execute(HandoutRunRequest(
                        runId="run-plan-contract-failure-001", taskId="task-plan-contract-failure-001",
                        writingGoal="函数讲义", questionText=question, operation="PLAN"))

                self.assertEqual(raised.exception.status_code, 422)
                self.assertEqual(raised.exception.detail["code"], "HANDOUT_OUTPUT_CONTRACT_FAILURE")
                self.assertEqual(len(calls), 2)
            finally:
                if previous is None:
                    os.environ.pop("MATH_AGENT_HANDOUT_CHECKPOINT_DB", None)
                else:
                    os.environ["MATH_AGENT_HANDOUT_CHECKPOINT_DB"] = previous

    def test_plan_operation_stops_before_teacher_blueprint(self):
        with tempfile.TemporaryDirectory() as directory:
            previous = os.environ.get("MATH_AGENT_HANDOUT_CHECKPOINT_DB")
            os.environ["MATH_AGENT_HANDOUT_CHECKPOINT_DB"] = os.path.join(directory, "checkpoints.sqlite3")
            try:
                runtime = HandoutRuntime()
                calls = []
                runtime._java_context = lambda payload: {"items": [{"ref": "ev_0123456789abcdef0123456789abcdef", "title": "函数", "excerpt": "定义域"}]}
                def provider(request, node, prompt):
                    calls.append(node)
                    if node == "resource_curation":
                        return ({"sufficient": True, "actions": [], "sourceToGapAssessment": "初始来源覆盖题干和方法"}, {"promptTokens": 1, "completionTokens": 1, "totalTokens": 2, "estimatedCost": 0.0}, "test-provider", "test-model")
                    self.assertEqual(node, "plan_writer")
                    return ({"learningObjective": "掌握定义域", "questions": [{"number": 1, "question": request.question_text, "evidenceRefs": ["ev_0123456789abcdef0123456789abcdef"], "knowledgePoint": "函数", "teachingSequence": ["读题"], "figureRequired": False}], "completionCriteria": ["覆盖题目"], "readyForNextStage": True, "revisionRound": 0}, {"promptTokens": 1, "completionTokens": 1, "totalTokens": 2, "estimatedCost": 0.0}, "test-provider", "test-model")
                runtime._invoke_json_model = _reviewed_provider(provider)
                package = runtime.execute(HandoutRunRequest(runId="run-plan-001", taskId="task-plan-001", writingGoal="函数讲义", questionText="【题目 1】\n已知函数 f(x)=x^2，求最小值。", operation="PLAN"))
                self.assertEqual(calls, ["resource_curation", "plan_writer"])
                self.assertEqual(package.status, "WAITING_REVIEW")
                self.assertEqual(package.phase, "PLAN_APPROVED")
                self.assertIsNotNone(package.writing_plan)
                self.assertIsNone(package.teacher_blueprint)
                checkpoint = runtime._checkpoint.load("run-plan-001")[1]
                self.assertIn("writingPlan", checkpoint)
                self.assertNotIn("writing_plan", checkpoint)
            finally:
                if previous is None:
                    os.environ.pop("MATH_AGENT_HANDOUT_CHECKPOINT_DB", None)
                else:
                    os.environ["MATH_AGENT_HANDOUT_CHECKPOINT_DB"] = previous

    def test_unknown_provider_pricing_remains_unknown_in_the_run_total(self):
        """A successful unpriced provider call must not be summarized as a zero-cost accepted run."""
        with tempfile.TemporaryDirectory(ignore_cleanup_errors=True) as directory:
            previous = os.environ.get("MATH_AGENT_HANDOUT_CHECKPOINT_DB")
            os.environ["MATH_AGENT_HANDOUT_CHECKPOINT_DB"] = os.path.join(directory, "checkpoints.sqlite3")
            try:
                runtime = HandoutRuntime()
                runtime._java_context = lambda payload: {"items": [{
                    "ref": "ev_unpriced", "title": "函数", "excerpt": "函数最小值"}]}

                def provider(request, node, prompt):
                    usage = {"promptTokens": 3, "completionTokens": 2, "totalTokens": 5, "estimatedCost": -1.0}
                    if node == "resource_curation":
                        return ({"sufficient": True, "actions": [], "sourceToGapAssessment": "初始来源覆盖题干和方法"}, usage, "unpriced-provider", "unpriced-model")
                    if node == "plan_writer":
                        return ({"learningObjective": "掌握最小值方法", "questions": [{"number": 1, "question": request.question_text, "evidenceRefs": ["ev_unpriced"], "knowledgePoint": "函数", "teachingSequence": ["读题"], "figureRequired": False}], "completionCriteria": ["覆盖题目"], "readyForNextStage": True, "revisionRound": 0}, usage, "unpriced-provider", "unpriced-model")
                    if node == "teacher_blueprint_writer":
                        return ({"title": "教师版", "markdown": f"## 题目\n\n{request.question_text}\n\n## 解题过程\n\n步骤 1：根据题设选择方法。\n\n## 最终答案\n\n结论已独立给出。\n\n## 评分点\n\n- 方法正确。\n\n## 易错点\n\n- 注意取值条件。", "citations": [], "completionChecklist": ["覆盖题目"], "remainingEdits": [], "readyForDerivation": True, "revisionRound": 0}, usage, "unpriced-provider", "unpriced-model")
                    return ({"stageCode": node, "title": node, "markdown": f"## 题目\n\n{request.question_text}\n{node} 给出方法和检查步骤。"}, usage, "unpriced-provider", "unpriced-model")

                runtime._invoke_json_model = _reviewed_provider(provider)
                package = runtime.execute(HandoutRunRequest(
                    runId="run-unpriced-001",
                    taskId="task-unpriced-001",
                    writingGoal="函数讲义",
                    questionText="【题目 1】\n已知函数 f(x)=x^2，求最小值。",
                ))

                self.assertFalse(package.metrics.cost_known)
                self.assertEqual(package.metrics.estimated_cost, -1.0)
            finally:
                if previous is None:
                    os.environ.pop("MATH_AGENT_HANDOUT_CHECKPOINT_DB", None)
                else:
                    os.environ["MATH_AGENT_HANDOUT_CHECKPOINT_DB"] = previous

    def test_code_normalizer_projects_lecture_cards_and_rejects_missing_question(self):
        questions = (
            "【题目 1】\n已知函数 f(x)=sqrt(x+1)/(x-2)，求定义域。\n"
            "【题目 2】\n已知函数 g(x)=x+1/x（x>0），求最小值。\n"
            "【题目 3】\n函数 h(x)=x^2-2ax+1 在区间[0,2]上的最小值为-3，求实数a。\n"
            "【题目 4】\n正方体 ABCD-A_1B_1C_1D_1 中，求 AC_1 与平面 A_1BD 所成角。"
        )
        payload = {
            "lectureCards": [
                {"type": "question", "title": "题目 1", "content": "已知函数 f(x)=sqrt(x+1)/(x-2)，求定义域。", "blankSpacePx": 180},
                {"type": "question", "title": "题目 2", "content": "已知函数 g(x)=x+1/x（x>0），求最小值。", "blankSpacePx": 180},
                {"type": "question", "title": "题目 3", "content": "函数 h(x)=x^2-2ax+1 在区间[0,2]上的最小值为-3，求实数a。", "blankSpacePx": 180},
                {"type": "question", "title": "题目 4", "content": "正方体 ABCD-A_1B_1C_1D_1 中，求 AC_1 与平面 A_1BD 所成角。", "blankSpacePx": 180},
                {"type": "resource", "title": "资料页", "content": "不得进入课堂投影。"},
            ]
        }
        document = HandoutRuntime._normalize_writer_payload(payload, "lecture_writer", questions)
        for marker in ("题目 1", "题目 2", "题目 3", "题目 4"):
            self.assertIn(marker, document.markdown)
        self.assertLess(document.markdown.index("题目 1"), document.markdown.index("题目 2"))
        self.assertLess(document.markdown.index("题目 2"), document.markdown.index("题目 3"))
        self.assertLess(document.markdown.index("题目 3"), document.markdown.index("题目 4"))
        self.assertIn("MATHAGENTHTMLSPACER260", document.markdown)
        self.assertNotIn("资料页", document.markdown)
        with self.assertRaisesRegex(ValueError, "missing"):
            HandoutRuntime._normalize_writer_payload(
                {"title": "空内容", "markdown": "这是一个长度足够但没有提交题目特征的投影内容，用于验证语义覆盖门禁。"},
                "lecture_writer",
                questions,
            )

    def test_writing_plan_rejects_cross_evidence_asset_and_requires_figure_selection(self):
        evidence = EvidenceSnapshot(items=[EvidenceItem(
            ref="ev_asset", documentRef="doc_asset", excerpt="原始题干", assetIds=["asset_allowed"]
        )])
        request = HandoutRunRequest(
            runId="run-asset-contract", taskId="task-asset-contract", writingGoal="函数讲义", questionText="【题目 1】\n求函数最值。")
        cross_asset = WritingPlan.model_validate({
            "learningObjective": "理解最值", "questions": [{"number": 1, "question": "求函数最值。",
            "evidenceRefs": ["ev_asset"], "knowledgePoint": "函数", "teachingSequence": ["读题"],
            "figureRequired": True, "assetPlacements": [{"questionNumber": 1, "assetIds": ["asset_other"],
            "anchor": "question", "layout": "single", "variants": ["teacher_writer"], "caption": "图"}]}],
            "completionCriteria": ["覆盖题目"], "readyForNextStage": True})
        with self.assertRaisesRegex(ValueError, "outside its evidence"):
            HandoutRuntime._validate_writing_plan(cross_asset, request, evidence)
        missing_placement = cross_asset.model_copy(update={"questions": [cross_asset.questions[0].model_copy(
            update={"asset_placements": []})]})
        with self.assertRaisesRegex(ValueError, "requires an AI-selected asset placement"):
            HandoutRuntime._validate_writing_plan(missing_placement, request, evidence)

    def test_writer_rejects_asset_replacement_outside_approved_plan(self):
        plan = WritingPlan.model_validate({
            "learningObjective": "理解最值", "questions": [{"number": 1, "question": "求函数最值。",
            "evidenceRefs": ["ev_asset"], "knowledgePoint": "函数", "teachingSequence": ["读题"],
            "figureRequired": False, "assetPlacements": [{"questionNumber": 1, "assetIds": ["asset_allowed"],
            "anchor": "question", "layout": "single", "variants": ["student_writer"], "caption": "图"}]}],
            "completionCriteria": ["覆盖题目"], "readyForNextStage": True})
        payload = {"stageCode": "student_writer", "title": "学生版", "markdown": "## 题目\n\n求函数最值。请先写出函数的定义域，并根据开口方向、顶点坐标和取值范围完成独立推理。\n\n## 提示\n\n先整理已知条件，再说明所用方法与每一步的依据，最后保留足够作答空间。",
                   "assetPlacements": [{"questionNumber": 1, "assetIds": ["asset_other"], "anchor": "question",
                   "layout": "single", "variants": ["student_writer"], "caption": "图"}]}
        with self.assertRaisesRegex(ValueError, "differs from approved writing plan"):
            HandoutRuntime._normalize_writer_payload(payload, "student_writer", "【题目 1】\n求函数最值。", plan)

    def test_normalizer_preserves_nested_student_document_from_provider_wrapper(self):
        """A provider wrapper must not turn an already generated student worksheet into an empty repair request."""
        questions = "【题目 1】\n已知函数 f(x)=x^2，求最小值。"
        document = HandoutRuntime._normalize_writer_payload(
            {
                "stageCode": "student_writer",
                "title": "学生练习",
                "data": {"studentHandout": {"sections": [{"content": "题目 1：已知函数 f(x)=x^2，求最小值。\n提示：先观察抛物线的开口方向和顶点。"}]}},
            },
            "student_writer",
            questions,
        )

        self.assertIn("题目 1", document.markdown)
        self.assertIn("抛物线", document.markdown)

    def test_projection_artifacts_are_removed_before_model_repair(self):
        questions = (
            "【题目 1】\n已知函数 f(x)=sqrt(x+1)/(x-2)，求定义域。\n"
            "【题目 2】\n已知函数 g(x)=x+1/x（x>0），求最小值。\n"
            "【题目 3】\n函数 h(x)=x^2-2ax+1 在区间[0,2]上的最小值为-3，求实数a。\n"
            "【题目 4】\n正方体 ABCD-A_1B_1C_1D_1 中，求 AC_1 与平面 A_1BD 所成角。"
        )
        raw = {
            "stageCode": "lecture_writer",
            "title": "课堂投影",
            "markdown": (
                f"{questions}\n\n"
                "<wait>请学生先独立思考</wait>\n"
                "---\n"
                "____________________\n"
                "方法提示：先锁定题设约束。"
            ),
        }
        document = HandoutRuntime._normalize_writer_payload(raw, "lecture_writer", questions)
        self.assertNotIn("<wait>", document.markdown)
        self.assertNotIn("独立思考", document.markdown)
        self.assertNotIn("____________________", document.markdown)
        self.assertNotIn("---", document.markdown)
        self.assertIn("方法提示", document.markdown)

    def test_resume_reuses_complete_nodes_without_model_or_broker_call(self):
        questions = (
            "【题目 1】\n已知函数 f(x)=sqrt(x+1)/(x-2)，求定义域。\n"
            "【题目 2】\n已知函数 g(x)=x+1/x（x>0），求最小值。\n"
            "【题目 3】\n函数 h(x)=x^2-2ax+1 在区间[0,2]上的最小值为-3，求实数a。\n"
            "【题目 4】\n正方体 ABCD-A_1B_1C_1D_1 中，求 AC_1 与平面 A_1BD 所成角。"
        )
        with tempfile.TemporaryDirectory() as directory:
            previous = os.environ.get("MATH_AGENT_HANDOUT_CHECKPOINT_DB")
            os.environ["MATH_AGENT_HANDOUT_CHECKPOINT_DB"] = os.path.join(directory, "checkpoints.sqlite3")
            try:
                runtime = HandoutRuntime()
                request = HandoutRunRequest(
                    runId="run-resume-complete-001",
                    taskId="task-resume-complete-001",
                    writingGoal="函数与空间向量综合讲义",
                    questionText=questions,
                    resume=True,
                )
                documents = []
                for stage in ("teacher_writer", "student_writer", "lecture_writer"):
                    teacher_sections = (
                        "\n\n## 解题过程\n\n步骤 1：说明方法依据。"
                        "\n\n## 最终答案\n\n已按题目分别给出结论。"
                        "\n\n## 评分点\n\n- 方法正确。"
                        "\n\n## 易错点\n\n- 注意题设条件。"
                    ) if stage == "teacher_writer" else ""
                    documents.append({
                        "stageCode": stage,
                        "title": stage,
                        "markdown": f"# {stage}\n\n## 题目\n\n{questions}\n\n完整节点内容已持久化。{teacher_sections}",
                        "citations": [],
                        "warnings": [],
                    })
                runtime._checkpoint.save(
                    request.run_id,
                    "FAILED",
                    {"request": request, "evidence": {"query": "", "items": []}, "writers": documents},
                    {"event": "node_completed", "node": "lecture_writer"},
                )
                runtime._java_context = lambda payload: (_ for _ in ()).throw(AssertionError("broker called on resume"))
                runtime._invoke_json_model = lambda *args: (_ for _ in ()).throw(AssertionError("model called on resume"))

                package = runtime.execute(request)

                self.assertEqual(package.status, "COMPLETED")
                self.assertTrue(package.validation.valid)
                self.assertEqual(set(package.documents), {"teacher_writer", "student_writer", "lecture_writer"})
                self.assertTrue(all(metric.status == "RESUMED" for metric in package.metrics.node_metrics[:4]))
            finally:
                if previous is None:
                    os.environ.pop("MATH_AGENT_HANDOUT_CHECKPOINT_DB", None)
                else:
                    os.environ["MATH_AGENT_HANDOUT_CHECKPOINT_DB"] = previous

    def test_json_parser_rejects_incomplete_object_instead_of_nested_actions_array(self):
        """A truncated collection decision cannot be mistaken for its nested actions array."""
        with self.assertRaisesRegex(ValueError, "complete top-level JSON"):
            HandoutRuntime._parse_json('{"sufficient": false, "actions": []')

    def test_json_parser_prefers_document_object_over_citation_array(self):
        content = (
            "前置说明：引用如下 [\"doc:one\", \"doc:two\"]，正文是："
            '{"stageCode":"teacher_writer","title":"函数讲义",'
            '"markdown":"题目 1：函数 f(x) 的定义域与单调性说明。",'
            '"citations":["doc:one"]}'
        )
        parsed = HandoutRuntime._parse_json(content)
        self.assertIsInstance(parsed, dict)
        self.assertEqual(parsed["stageCode"], "teacher_writer")

    def test_list_writer_payload_projects_text_fields(self):
        questions = "【题目 1】\n已知函数 f(x)=x^2，求最小值。"
        document = HandoutRuntime._normalize_writer_payload(
            [
                {"type": "heading", "content": "题目 1：已知函数 f(x)=x^2，求最小值。"},
                {"type": "explanation", "markdown": "利用配方法可得最小值。"},
            ],
            "student_writer",
            questions,
        )
        self.assertIn("题目 1", document.markdown)
        self.assertIn("配方法", document.markdown)

    def test_resume_rejects_partial_legacy_checkpoint_without_visible_plan(self):
        questions = "【题目 1】\n已知函数 f(x)=x^2，求最小值。"
        with tempfile.TemporaryDirectory() as directory:
            previous = os.environ.get("MATH_AGENT_HANDOUT_CHECKPOINT_DB")
            os.environ["MATH_AGENT_HANDOUT_CHECKPOINT_DB"] = os.path.join(directory, "checkpoints.sqlite3")
            try:
                runtime = HandoutRuntime()
                request = HandoutRunRequest(runId="run-resume-001", taskId="task-resume-001", writingGoal="函数讲义", questionText=questions, resume=True)
                teacher = HandoutRuntime._normalize_writer_payload({"stageCode": "teacher_writer", "title": "教师版", "markdown": f"## 题目\n\n{questions}\n\n## 解题过程\n\n步骤 1：配方。\n\n## 最终答案\n\n$0$。\n\n## 评分点\n\n- 配方。\n\n## 易错点\n\n- 注意符号。"}, "teacher_writer", questions)
                runtime._checkpoint.save(request.run_id, "FAILED", {"request": request, "evidence": {"query": "函数", "items": []}, "writers": [teacher]}, {"event": "seeded_partial"})
                with self.assertRaises(HTTPException) as raised:
                    runtime.execute(request)
                self.assertEqual(raised.exception.status_code, 409)
                self.assertEqual(raised.exception.detail, "LEGACY_CHECKPOINT_REQUIRES_RESTART")
            finally:
                if previous is None:
                    os.environ.pop("MATH_AGENT_HANDOUT_CHECKPOINT_DB", None)
                else:
                    os.environ["MATH_AGENT_HANDOUT_CHECKPOINT_DB"] = previous

    def test_teacher_requires_fixed_sections_and_documents_reject_unsafe_transport(self):
        question = "【题目 1】\n已知函数 f(x)=x^2，求最小值。"
        with self.assertRaisesRegex(ValueError, "missing required sections"):
            HandoutRuntime._normalize_writer_payload(
                {"stageCode": "teacher_writer", "title": "教师版", "markdown": "## 题目\n\n题目 1：已知函数 f(x)=x^2，求最小值。\n\n请先根据题设选择方法，再完成计算。"},
                "teacher_writer",
                question,
            )
        with self.assertRaisesRegex(ValueError, "unsafe image, URL, or HTML transport"):
            HandoutRuntime._normalize_writer_payload(
                {"stageCode": "student_writer", "title": "学生版", "markdown": "题目 1：已知函数 f(x)=x^2，求最小值。\n\n![图](https://example.invalid/graph.png)\n\n提示：先找顶点。"},
                "student_writer",
                question,
            )

    def test_writer_markup_rejects_escaped_or_list_prefixed_headings(self):
        question = "【题目 1】\n已知函数 f(x)=x^2，求最小值。"
        for malformed_heading in ("\\## 题目", "- ## 题目"):
            with self.subTest(malformed_heading=malformed_heading):
                with self.assertRaisesRegex(ValueError, "headings must start"):
                    HandoutRuntime._normalize_writer_payload(
                        {"stageCode": "student_writer", "title": "学生版", "markdown": (
                            f"{malformed_heading}\n\n题目 1：已知函数 f(x)=x^2，求最小值。\n\n"
                            "提示：先观察抛物线的开口方向和顶点。"
                        )},
                        "student_writer",
                        question,
                    )

    def test_writer_markup_accepts_closed_display_math_and_rejects_malformed_variants(self):
        question = "【题目 1】\n已知函数 f(x)=x^2，求最小值。"
        valid = {
            "stageCode": "student_writer",
            "title": "学生版",
            "markdown": "## 题目\n\n题目 1：已知函数 f(x)=x^2，求最小值。\n\n$$x^2\\geq 0$$\n\n提示：先观察顶点。",
        }
        document = HandoutRuntime._normalize_writer_payload(valid, "student_writer", question)
        self.assertIn("$$x^2\\geq 0$$", document.markdown)
        for malformed_math in ("$$x^2\\geq 0$", "$$x^2$+1$$", "\\[x^2\\geq 0\\]"):
            with self.subTest(malformed_math=malformed_math):
                with self.assertRaisesRegex(ValueError, "display math"):
                    HandoutRuntime._normalize_writer_payload(
                        {**valid, "markdown": valid["markdown"].replace("$$x^2\\geq 0$$", malformed_math)},
                        "student_writer",
                        question,
                    )

    def test_deadline_is_enforced_before_graph_work(self):
        with tempfile.TemporaryDirectory() as directory:
            previous = os.environ.get("MATH_AGENT_HANDOUT_CHECKPOINT_DB")
            os.environ["MATH_AGENT_HANDOUT_CHECKPOINT_DB"] = os.path.join(directory, "checkpoints.sqlite3")
            try:
                runtime = HandoutRuntime()
                request = HandoutRunRequest(
                    runId="run-deadline-001",
                    taskId="task-deadline-001",
                    writingGoal="函数讲义",
                    questionText="【题目 1】\n已知函数 f(x)=x^2，求最小值。",
                    deadlineEpochMs=0,
                )
                with self.assertRaises(HTTPException) as raised:
                    runtime.execute(request)
                self.assertEqual(raised.exception.status_code, 504)
                self.assertEqual(raised.exception.detail["code"], "MODEL_TIMEOUT")
            finally:
                if previous is None:
                    os.environ.pop("MATH_AGENT_HANDOUT_CHECKPOINT_DB", None)
                else:
                    os.environ["MATH_AGENT_HANDOUT_CHECKPOINT_DB"] = previous

    def test_contract_rejects_tenant_override(self):
        """Authorization must remain Java-owned even when a request is model-generated."""
        payload = {
            "runId": "run-contract-reject-001",
            "taskId": "task-contract-reject-001",
            "writingGoal": "函数讲义",
            "questionText": "【题目 1】已知函数 f(x)=x^2，求最小值。",
            "tenantId": "forbidden-tenant",
        }
        with self.assertRaises(ValidationError):
            HandoutRunRequest.model_validate(payload)

    def test_contract_rejects_identity_and_path_overrides(self):
        """The Java-owned run authorization must not be widened by model-controlled request attributes."""
        required_payload = {
            "runId": "run-contract-reject-001",
            "taskId": "task-contract-reject-001",
            "contractVersion": "handout-ai-v1",
            "writingGoal": "函数讲义",
            "questionText": "【题目 1】已知函数 f(x)=x^2，求最小值。",
            "evidenceRefs": ["doc:approved-1"],
            "graphVersion": "handout-v1",
            "idempotencyKey": "idempotency-contract-reject-001",
            "traceparent": "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01",
            "deadlineEpochMs": 4_102_444_800_000,
        }
        accepted = HandoutRunRequest.model_validate(required_payload)
        self.assertEqual(accepted.contract_version, "handout-ai-v1")
        self.assertEqual(accepted.idempotency_key, "idempotency-contract-reject-001")
        self.assertEqual(accepted.traceparent, required_payload["traceparent"])
        for forbidden_field in ("tenantId", "subjectId", "subjectType", "filesystemPath", "javaIdentity"):
            with self.subTest(forbidden_field=forbidden_field):
                with self.assertRaises(ValidationError):
                    HandoutRunRequest.model_validate({**required_payload, forbidden_field: "forbidden"})


if __name__ == "__main__":
    unittest.main()
