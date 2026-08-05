import os
import tempfile
import unittest

from fastapi import HTTPException
from pydantic import ValidationError
from pydantic import ValidationError

from app.handout_runtime import HandoutRunRequest, HandoutRuntime


class HandoutGraphContractTest(unittest.TestCase):
    """Checks graph topology, structural validation, and durable lifecycle events without a paid model call."""

    def test_complete_graph_has_one_context_request_and_three_documents(self):
        with tempfile.TemporaryDirectory() as directory:
            previous = os.environ.get("MATH_AGENT_HANDOUT_CHECKPOINT_DB")
            os.environ["MATH_AGENT_HANDOUT_CHECKPOINT_DB"] = os.path.join(directory, "checkpoints.sqlite3")
            try:
                runtime = HandoutRuntime()
                context_calls = []
                runtime._java_context = lambda payload: context_calls.append(payload) or {
                    "query": payload["query"],
                    "items": [{"ref": "doc:block", "title": "函数", "excerpt": "定义域与单调性"}],
                }

                def provider(request, node, prompt):
                    return (
                        {
                            "stageCode": node,
                            "title": node,
                            "markdown": f"# {node}\n\n{request.question_text}\n\n{node} keeps the submitted problems in order and explains the shared method.",
                            "citations": ["doc:block"],
                            "warnings": [],
                        },
                        {"promptTokens": 10, "completionTokens": 12, "totalTokens": 22, "estimatedCost": 0.0},
                        "test-provider",
                        "test-model",
                    )

                runtime._invoke_json_model = provider
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
                self.assertGreaterEqual(len(runtime.events("run-contract-001")), 2)
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
                documents = [
                    {
                        "stageCode": stage,
                        "title": stage,
                        "markdown": f"# {stage}\n\n{questions}\n\n完整节点内容已持久化。",
                        "citations": [],
                        "warnings": [],
                    }
                    for stage in ("teacher_writer", "student_writer", "lecture_writer")
                ]
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

    def test_resume_skips_checkpointed_evidence_and_writer(self):
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
                    runId="run-resume-001",
                    taskId="task-resume-001",
                    writingGoal="函数与空间向量综合讲义",
                    questionText=questions,
                    resume=True,
                )
                evidence = {"query": "函数", "items": [{"ref": "doc:block", "title": "函数", "excerpt": "定义域"}]}
                teacher = HandoutRuntime._normalize_writer_payload(
                    {"stageCode": "teacher_writer", "title": "教师版", "markdown": f"# 教师版\n\n{questions}\n\n教师讲解四道题的关键方法，说明取等条件、评分点和检查步骤。"},
                    "teacher_writer",
                    request.question_text,
                )
                runtime._checkpoint.save(
                    request.run_id,
                    "FAILED",
                    {"request": request, "evidence": evidence, "writers": [teacher]},
                    {"event": "seeded_partial"},
                )
                runtime._java_context = lambda payload: self.fail("resumed evidence must not call Java")
                calls = []

                def provider(req, node, prompt):
                    calls.append(node)
                    stage = node.removesuffix("_repair")
                    return ({"stageCode": stage, "title": stage, "markdown": f"{req.question_text}\n{stage} 对函数最小值进行说明，并补充取等条件和检查步骤。"}, {"promptTokens": 1, "completionTokens": 1, "totalTokens": 2, "estimatedCost": 0.0}, "test-provider", "test-model")

                runtime._invoke_json_model = provider
                package = runtime.execute(request)
                self.assertEqual(package.status, "COMPLETED")
                self.assertEqual(set(calls), {"student_writer", "lecture_writer"})
            finally:
                if previous is None:
                    os.environ.pop("MATH_AGENT_HANDOUT_CHECKPOINT_DB", None)
                else:
                    os.environ["MATH_AGENT_HANDOUT_CHECKPOINT_DB"] = previous

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
            finally:
                if previous is None:
                    os.environ.pop("MATH_AGENT_HANDOUT_CHECKPOINT_DB", None)
                else:
                    os.environ["MATH_AGENT_HANDOUT_CHECKPOINT_DB"] = previous

    def test_contract_rejects_identity_and_path_overrides(self):
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
        payload = {
            "runId": "run-contract-reject-001",
            "taskId": "task-contract-reject-001",
            "writingGoal": "函数讲义",
            "questionText": "【题目 1】已知函数 f(x)=x^2，求最小值。",
            "idempotencyKey": "idempotency-contract-reject-001",
            "tenantId": "forbidden-tenant",
        }
        with self.assertRaises(ValidationError):
            HandoutRunRequest.model_validate(payload)


if __name__ == "__main__":
    unittest.main()
