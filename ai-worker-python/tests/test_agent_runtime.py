import os
import unittest

from fastapi.testclient import TestClient

from app.server import app
from app.agent_runtime import AgentRuntime


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


if __name__ == "__main__":
    unittest.main()
