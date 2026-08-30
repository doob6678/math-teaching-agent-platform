import base64
import hashlib
import hmac
import json
import os
import time
import unittest
from unittest.mock import patch

from fastapi.testclient import TestClient

from app.server import app
from app.agent_runtime import AgentRunRequest, AgentRuntime
from app.streaming_runtime import AgentStreamingRuntime, NO_REWRITE_AFTER_VISIBLE_OUTPUT
from app.ai_run_runtime import AiRunResult, AiRunRuntime


class AgentRuntimeContractTest(unittest.TestCase):
    """Exercises the real HTTP contract without invoking a paid model."""

    def setUp(self):
        self.previous_key = os.environ.get("MATH_AGENT_WORKER_API_KEY")
        self.previous_test_switch = os.environ.get("MATH_AGENT_AI_RUNTIME_ALLOW_TEST_TOOL_REQUEST")
        os.environ["MATH_AGENT_WORKER_API_KEY"] = "worker-test-key"
        os.environ["MATH_AGENT_AI_RUNTIME_ALLOW_TEST_TOOL_REQUEST"] = "true"
        self.client = TestClient(app)

    def tearDown(self):
        if self.previous_key is None:
            os.environ.pop("MATH_AGENT_WORKER_API_KEY", None)
        else:
            os.environ["MATH_AGENT_WORKER_API_KEY"] = self.previous_key
        if self.previous_test_switch is None:
            os.environ.pop("MATH_AGENT_AI_RUNTIME_ALLOW_TEST_TOOL_REQUEST", None)
        else:
            os.environ["MATH_AGENT_AI_RUNTIME_ALLOW_TEST_TOOL_REQUEST"] = self.previous_test_switch

    def test_agent_run_rejects_a_tool_outside_the_capability_scope(self):
        response = self.client.post(
            "/v1/agent-runs",
            headers={"Authorization": "Bearer worker-test-key"},
            json={
                "runId": "run-1",
                "allowedTools": ["search_visible_resources"],
                "requestedTool": "read_resource_assets",
                "message": "请看这张图",
            },
        )

        self.assertEqual(response.status_code, 200)
        self.assertIn("event: error", response.text)
        self.assertIn("tool is not granted for this run", response.text)

    def test_agent_run_returns_a_tool_request_instead_of_reading_files_directly(self):
        response = self.client.post(
            "/v1/agent-runs",
            headers={"Authorization": "Bearer worker-test-key"},
            json={
                "runId": "run-2",
                "allowedTools": ["search_visible_resources"],
                "requestedTool": "search_visible_resources",
                "message": "我想继续学习函数单调性",
            },
        )

        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.headers["content-type"].split(";", 1)[0], "text/event-stream")
        self.assertIn("event: started", response.text)
        self.assertIn("event: tool_call", response.text)
        self.assertIn('"name":"search_visible_resources"', response.text)
        self.assertNotIn('"tenantId"', response.text)
        self.assertNotIn('"subjectId"', response.text)
        self.assertNotIn('"path"', response.text)

    def test_agent_run_rejects_identity_fields_from_the_transport_contract(self):
        for field, value in (
            ("subject", {"tenantId": "forbidden-tenant", "subjectId": "student-a", "subjectType": "student"}),
            ("tenantId", "forbidden-tenant"),
            ("subjectId", "student-a"),
            ("subjectType", "student"),
            ("capabilityToken", "forbidden-token"),
        ):
            with self.subTest(field=field):
                response = self.client.post(
                    "/v1/agent-runs/sync",
                    headers={"Authorization": "Bearer worker-test-key"},
                    json={
                        "runId": "run-identity-reject",
                        "allowedTools": ["search_visible_resources"],
                        "message": "函数单调性",
                        field: value,
                    },
                )

                self.assertEqual(response.status_code, 422)
                self.assertIn(field, response.text)

    def test_resource_asset_tool_schema_accepts_only_an_opaque_asset_id(self):
        schema = AgentRuntime._tool_parameters("read_resource_asset")

        self.assertEqual(schema["required"], ["assetId"])
        self.assertEqual(set(schema["properties"]), {"assetId"})
        self.assertFalse(schema["additionalProperties"])
    def test_ai_run_v1_rejects_identity_and_provider_secret_fields(self):
        payload = self._ai_run_payload()
        for field, value in (
            ("tenantId", "school-a"),
            ("subjectId", "teacher-001"),
            ("apiKey", "forbidden"),
            ("providerUrl", "https://forbidden.example"),
            ("path", "/etc/passwd"),
            ("sql", "select * from users"),
        ):
            with self.subTest(field=field):
                response = self.client.post(
                    "/v1/ai-runs/sync",
                    headers={"Authorization": "Bearer worker-test-key"},
                    json={**payload, field: value},
                )

                self.assertEqual(response.status_code, 422)
                self.assertIn(field, response.text)

    def test_route_grant_rejects_cross_run_and_cross_workload_reuse(self):
        payload = self._ai_run_payload()
        secret = "route-grant-test-secret"
        payload["providerRoute"]["routeGrant"] = self._route_grant(
            secret,
            payload["runId"],
            payload["workload"],
            [
                {"name": "openai", "model": "gpt-5.6-luna"},
                {"name": "dashscope", "model": "qwen3.6-flash"},
            ],
        )
        with patch.dict(os.environ, {
            "MATH_AGENT_REQUIRE_ROUTE_GRANT": "true",
            "MATH_AGENT_PROVIDER_ROUTE_GRANT_SECRET": secret,
        }, clear=False):
            for changed in (
                {"runId": "another-run"},
                {"providerRoute": {
                    **payload["providerRoute"],
                    "primary": {"name": "openai", "model": "ungranted-model"},
                }},
            ):
                with self.subTest(changed=changed):
                    response = self.client.post(
                        "/v1/ai-runs/sync",
                        headers={"Authorization": "Bearer worker-test-key"},
                        json={**payload, **changed},
                    )
                    self.assertEqual(response.status_code, 422)
                    self.assertIn("route grant", response.text)
            explanation = self.client.post(
                "/v1/student-explanations/sync",
                headers={"Authorization": "Bearer worker-test-key"},
                json={
                    "runId": payload["runId"],
                    "problem": "求函数定义域",
                    "providerRoute": payload["providerRoute"],
                },
            )
            self.assertEqual(explanation.status_code, 422)
            self.assertIn("route grant", explanation.text)

    def test_ai_run_v1_projects_python_usage_without_identity_fields(self):
        class CompletedRuntime:
            def execute(self, request):
                return type("Result", (), {
                    "status": "COMPLETED",
                    "message": "受限答案",
                    "actual_usage": {
                        "promptTokens": 11,
                        "completionTokens": 7,
                        "totalTokens": 18,
                        "estimatedCost": -1.0,
                    },
                })()

        from app.ai_run_runtime import AiRunRequest

        result = AiRunRuntime(CompletedRuntime()).execute(AiRunRequest.model_validate(self._ai_run_payload()))

        self.assertIsInstance(result, AiRunResult)
        response = result.as_response()
        self.assertEqual(response["actualUsage"]["totalTokens"], 18)
        self.assertFalse(response["costKnown"])
        self.assertNotIn("tenantId", response)
        self.assertNotIn("subjectId", response)

    def test_streaming_policy_prohibits_rewrite_after_a_visible_delta(self):
        self.assertTrue(NO_REWRITE_AFTER_VISIBLE_OUTPUT)

        class InterruptedResponse:
            def __enter__(self):
                return self

            def __exit__(self, *_):
                return False

            def raise_for_status(self):
                return None

            def iter_lines(self, chunk_size=None, decode_unicode=True):
                from requests import RequestException
                yield 'data: {"choices":[{"delta":{"content":"partial"}}]}'
                raise RequestException("interrupted")

        runtime = AgentStreamingRuntime()
        request = AgentRunRequest(runId="stream-review-boundary-1", message="解释函数定义域")
        with patch.dict(os.environ, {"OPENAI_API_KEY": "test-key"}), patch(
                "app.streaming_runtime.requests.post", return_value=InterruptedResponse()) as post:
            events = list(runtime._model_stream(request, [{"role": "user", "content": "x"}], True, runtime._zero_usage()))

        self.assertEqual(post.call_count, 1)
        self.assertEqual([event["event"] for event in events], ["provider", "delta", "error"])

    def test_agent_final_review_hides_envelope_from_the_public_result(self):
        runtime = AgentRuntime()
        request = AgentRunRequest(runId="review-run-1", message="解释函数单调性")
        with patch.object(runtime, "_call_live_model", return_value=type("Result", (), {
            "message": '{"candidate":{"message":"受限答案"},"review":{"approved":true,"feedbackCodes":[]}}',
            "provider_name": "openai",
            "model_code": "gpt-5.6-luna",
            "actual_usage": {"promptTokens": 1, "completionTokens": 1, "totalTokens": 2, "estimatedCost": 0.0},
        })()):
            result = runtime._review_final_answer(request, [{"role": "user", "content": request.message}])

        self.assertEqual(result.message, "受限答案")
        self.assertNotIn("candidate", result.as_response())
        self.assertNotIn("review", result.as_response())

    @staticmethod
    def _ai_run_payload():
        return {
            "contractVersion": "ai-run-v1",
            "runId": "run-ai-v1",
            "workload": "generic_agent",
            "idempotencyKey": "agent:run-ai-v1",
            "traceparent": "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01",
            "deadlineEpochMs": 2_000_000_000_000,
            "providerRoute": {
                "primary": {"name": "openai", "model": "gpt-5.6-luna"},
                "fallbacks": [{"name": "dashscope", "model": "qwen3.6-flash"}],
            },
            "limits": {"maxProviderCalls": 2, "maxTotalTokens": 1000, "maxOutputTokens": 300, "maxOutputChars": 1000},
            "input": {"message": "解释空间向量"},
            "evidenceRefs": ["textbook:vector-1"],
            "allowedTools": [],
        }

    @staticmethod
    def _route_grant(secret: str, run_id: str, workload: str, routes: list[dict[str, str]]) -> str:
        body = {
            "runId": run_id,
            "workload": workload,
            "expiresAt": int(time.time()) + 60,
            "routes": routes,
        }
        encoded = base64.urlsafe_b64encode(
            json.dumps(body, separators=(",", ":")).encode("utf-8")
        ).rstrip(b"=").decode("ascii")
        signature = hmac.new(secret.encode("utf-8"), encoded.encode("ascii"), hashlib.sha256).digest()
        return encoded + "." + base64.urlsafe_b64encode(signature).rstrip(b"=").decode("ascii")


if __name__ == "__main__":
    unittest.main()
