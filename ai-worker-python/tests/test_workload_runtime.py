import os
import tempfile
import unittest
from unittest.mock import patch

from fastapi import HTTPException
from fastapi.testclient import TestClient

from app.server import app
from app.student_explanation_runtime import DurableStudentExplanationRuntime
from app.workload_runtime import StudentExplanationRunRequest


class MigratedWorkloadContractTest(unittest.TestCase):
    def setUp(self):
        self.previous_key = os.environ.get("MATH_AGENT_WORKER_API_KEY")
        self.previous_checkpoint_db = os.environ.get("MATH_AGENT_STUDENT_EXPLANATION_CHECKPOINT_DB")
        self.checkpoint_directory = tempfile.TemporaryDirectory()
        os.environ["MATH_AGENT_WORKER_API_KEY"] = "worker-test-key"
        os.environ["MATH_AGENT_STUDENT_EXPLANATION_CHECKPOINT_DB"] = os.path.join(
            self.checkpoint_directory.name, "runs.sqlite3"
        )
        from app.server import durable_student_explanation_runtime, migrated_workload_runtime
        durable_student_explanation_runtime.cache_clear()
        migrated_workload_runtime.cache_clear()
        self.client = TestClient(app)

    def tearDown(self):
        from app.server import durable_student_explanation_runtime
        durable_student_explanation_runtime.cache_clear()
        self.checkpoint_directory.cleanup()
        if self.previous_checkpoint_db is None:
            os.environ.pop("MATH_AGENT_STUDENT_EXPLANATION_CHECKPOINT_DB", None)
        else:
            os.environ["MATH_AGENT_STUDENT_EXPLANATION_CHECKPOINT_DB"] = self.previous_checkpoint_db
        if self.previous_key is None:
            os.environ.pop("MATH_AGENT_WORKER_API_KEY", None)
        else:
            os.environ["MATH_AGENT_WORKER_API_KEY"] = self.previous_key

    @staticmethod
    def route():
        return {
            "primary": {"name": "openai", "model": "gpt-5.6-luna"},
            "fallbacks": [],
        }

    def test_migrated_workload_contract_rejects_java_identity_and_secret_fields(self):
        payload = {
            "runId": "intent-run-1",
            "message": "我想复习函数单调性",
            "knowledgePoints": [{"knowledgePointId": "kp-1", "knowledgePointName": "函数单调性"}],
            "providerRoute": self.route(),
        }
        for field, value in (
            ("tenantId", "school-a"),
            ("subjectId", "student-1"),
            ("path", "C:/private/file.png"),
            ("sql", "select * from users"),
            ("apiKey", "forbidden"),
            ("providerUrl", "https://forbidden.example"),
        ):
            with self.subTest(field=field):
                response = self.client.post(
                    "/v1/learning-intents/sync",
                    headers={"Authorization": "Bearer worker-test-key"},
                    json={**payload, field: value},
                )
                self.assertEqual(response.status_code, 422)
                self.assertIn(field, response.text)

    def test_intent_runtime_accepts_only_authorized_knowledge_point(self):
        with patch("app.workload_runtime.MigratedWorkloadRuntime._call_json", return_value=(
            '{"intentCode":"TARGETED_PRACTICE","confidence":0.8,"knowledgePointId":"kp-1"}',
            type("Result", (), {"provider": "openai", "model": "gpt-5.6-luna", "usage": lambda self: {"promptTokens": 3, "completionTokens": 4, "totalTokens": 7, "estimatedCost": -1.0}})(),
        )):
            response = self.client.post(
                "/v1/learning-intents/sync",
                headers={"Authorization": "Bearer worker-test-key"},
                json={
                    "runId": "intent-run-2",
                    "message": "给我单调性练习",
                    "knowledgePoints": [{"knowledgePointId": "kp-1", "knowledgePointName": "函数单调性"}],
                    "providerRoute": self.route(),
                },
            )
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["intentCode"], "TARGETED_PRACTICE")
        self.assertEqual(response.json()["knowledgePointId"], "kp-1")

    def test_student_explanation_strips_unapproved_citations(self):
        with patch("app.workload_runtime.MigratedWorkloadRuntime._call_json", return_value=(
            '{"conversationTitle":"函数讲解","cards":[{"cardKey":"function","summary":"先判定义域。","sourceUris":["doc:allowed","doc:forbidden"],"renderMode":"text"}]}',
            type("Result", (), {"provider": "openai", "model": "gpt-5.6-luna", "usage": lambda self: {"promptTokens": 3, "completionTokens": 4, "totalTokens": 7, "estimatedCost": -1.0}})(),
        )):
            response = self.client.post(
                "/v1/student-explanations/sync",
                headers={"Authorization": "Bearer worker-test-key"},
                json={
                    "runId": "explanation-run-1",
                    "problem": "求函数定义域",
                    "evidence": [{"sourceUri": "doc:allowed", "title": "教材", "snippet": "定义域"}],
                    "providerRoute": self.route(),
                },
            )
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["cards"][0]["sourceUris"], ["doc:allowed"])

    def test_react_final_returns_cards_without_a_compose_call(self):
        from app.server import migrated_workload_runtime
        migrated_workload_runtime.cache_clear()
        with patch("app.workload_runtime.MigratedWorkloadRuntime._call_json", return_value=(
            '{"decision":"final","conversationTitle":"函数定义域","cards":[{"cardKey":"domain","summary":"先令分母不为零。","sourceUris":["doc:allowed","doc:forbidden"],"renderMode":"text"}]}',
            type("Result", (), {"provider": "openai", "model": "gpt-5.6-luna", "usage": lambda self: {"promptTokens": 5, "completionTokens": 6, "totalTokens": 11, "estimatedCost": -1.0}})(),
        )) as call_json:
            response = self.client.post(
                "/v1/student-explanations/sync",
                headers={"Authorization": "Bearer worker-test-key"},
                json={
                    "runId": "explanation-react-final-1",
                    "mode": "react",
                    "problem": "求函数定义域",
                    "availableTools": ["search_textbook"],
                    "evidence": [{"sourceUri": "doc:allowed", "title": "教材", "snippet": "定义域"}],
                    "providerRoute": self.route(),
                },
            )
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["decision"], "final")
        self.assertEqual(response.json()["cards"][0]["sourceUris"], ["doc:allowed"])
        self.assertEqual(call_json.call_count, 1)

    def test_durable_explanation_reuses_completed_result_and_replays_events(self):
        with tempfile.TemporaryDirectory() as directory, patch.dict(
            os.environ,
            {"MATH_AGENT_STUDENT_EXPLANATION_CHECKPOINT_DB": os.path.join(directory, "runs.sqlite3")},
        ):
            calls = []
            runtime = DurableStudentExplanationRuntime(lambda request: calls.append(request.runId) or {
                "status": "COMPLETED", "runId": request.runId, "cards": [{"summary": "ok"}]
            })
            request = StudentExplanationRunRequest.model_validate({
                "runId": "durable-explanation-1",
                "problem": "求定义域",
                "providerRoute": self.route(),
            })
            first = runtime.execute(request)
            second = runtime.execute(request)
            self.assertEqual(first, second)
            self.assertEqual(calls, ["durable-explanation-1"])
            events = runtime.event_page(request.runId)
            self.assertEqual([event[1]["event"] for event in events], ["started", "completed"])

    def test_durable_explanation_rejects_fingerprint_change(self):
        with tempfile.TemporaryDirectory() as directory, patch.dict(
            os.environ,
            {"MATH_AGENT_STUDENT_EXPLANATION_CHECKPOINT_DB": os.path.join(directory, "runs.sqlite3")},
        ):
            runtime = DurableStudentExplanationRuntime(lambda request: {"status": "COMPLETED"})
            base = {"runId": "durable-explanation-2", "problem": "求定义域", "providerRoute": self.route()}
            runtime.execute(StudentExplanationRunRequest.model_validate(base))
            with self.assertRaises(HTTPException) as error:
                runtime.execute(StudentExplanationRunRequest.model_validate({**base, "problem": "求值域"}))
            self.assertEqual(error.exception.status_code, 409)
            self.assertEqual(error.exception.detail, "STUDENT_EXPLANATION_RUN_FINGERPRINT_MISMATCH")

    def test_image_transcription_rejects_non_data_url_and_oversized_image(self):
        invalid = {
            "runId": "image-run-1",
            "mimeType": "image/png",
            "imageDataUrl": "https://forbidden.example/image.png",
            "providerRoute": self.route(),
        }
        response = self.client.post(
            "/v1/image-transcriptions/sync",
            headers={"Authorization": "Bearer worker-test-key"},
            json=invalid,
        )
        self.assertEqual(response.status_code, 422)
        self.assertIn("imageDataUrl", response.text)


if __name__ == "__main__":
    unittest.main()
