import json
import os
import tempfile
import unittest
from unittest.mock import patch

from fastapi import HTTPException
from fastapi.testclient import TestClient

from app.server import app
from app.student_explanation_runtime import DurableStudentExplanationRuntime, StudentExplanationRunStore
from app.workload_runtime import MigratedWorkloadRuntime, StudentExplanationRunRequest


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

    def test_mysql_lock_name_hashes_long_run_ids_within_mysql_limit(self):
        run_id = "e2e-route-model-retest-20260809:react:" + "x" * 200

        lock_name = StudentExplanationRunStore._mysql_lock_name(run_id)

        self.assertLessEqual(len(lock_name), 64)
        self.assertEqual(lock_name, StudentExplanationRunStore._mysql_lock_name(run_id))
        self.assertNotEqual(lock_name, StudentExplanationRunStore._mysql_lock_name(run_id + "-next"))

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

    def test_react_final_without_cards_allows_java_compose_fallback(self):
        with patch("app.workload_runtime.MigratedWorkloadRuntime._call_json", return_value=(
            '{"decision":"final"}',
            type("Result", (), {"provider": "openai", "model": "gpt-5.6-luna", "usage": lambda self: {"promptTokens": 5, "completionTokens": 6, "totalTokens": 11, "estimatedCost": -1.0}})(),
        )):
            response = self.client.post(
                "/v1/student-explanations/sync",
                headers={"Authorization": "Bearer worker-test-key"},
                json={
                    "runId": "explanation-react-planner-final-1",
                    "mode": "react",
                    "problem": "求函数定义域",
                    "providerRoute": self.route(),
                },
            )
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["decision"], "final")
        self.assertEqual(response.json()["cards"], [])

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

    def test_durable_stream_replays_from_cursor_without_second_provider_call(self):
        with tempfile.TemporaryDirectory() as directory, patch.dict(
            os.environ,
            {"MATH_AGENT_STUDENT_EXPLANATION_CHECKPOINT_DB": os.path.join(directory, "runs.sqlite3")},
        ):
            calls = []

            def stream_executor(request):
                calls.append(request.runId)
                yield {"event": "started", "data": {"runId": request.runId}}
                yield {"event": "delta", "data": {"runId": request.runId, "content": "第一段"}}
                yield {"event": "delta", "data": {"runId": request.runId, "content": "第二段"}}
                yield {"event": "completed", "data": {"runId": request.runId, "status": "COMPLETED", "cards": []}}

            runtime = DurableStudentExplanationRuntime(lambda _: {}, stream_executor)
            request = StudentExplanationRunRequest.model_validate({
                "runId": "durable-stream-replay-1",
                "problem": "求定义域",
                "providerRoute": self.route(),
            })
            first = list(runtime.stream_events(request))
            replay = list(runtime.stream_events(request, after_id=first[1][0]))

            self.assertEqual(calls, [request.runId])
            self.assertEqual([event[1]["event"] for event in first], ["started", "delta", "delta", "completed"])
            self.assertEqual([event[1]["event"] for event in replay], ["delta", "completed"])

    def test_durable_stream_persists_error_when_executor_has_no_terminal_event(self):
        with tempfile.TemporaryDirectory() as directory, patch.dict(
            os.environ,
            {"MATH_AGENT_STUDENT_EXPLANATION_CHECKPOINT_DB": os.path.join(directory, "runs.sqlite3")},
        ):
            def stream_executor(request):
                yield {"event": "started", "data": {"runId": request.runId}}
                yield {"event": "delta", "data": {"runId": request.runId, "content": "第一段"}}

            runtime = DurableStudentExplanationRuntime(lambda _: {}, stream_executor)
            request = StudentExplanationRunRequest.model_validate({
                "runId": "durable-stream-no-terminal-1",
                "problem": "求定义域",
                "providerRoute": self.route(),
            })
            events = list(runtime.stream_events(request))

            self.assertEqual([event[1]["event"] for event in events], ["started", "delta", "error"])
            self.assertIn("STREAM_ENDED_WITHOUT_TERMINAL_EVENT", events[-1][1]["data"]["message"])

    def test_durable_stream_resumes_an_interrupted_run_before_visible_output(self):
        with tempfile.TemporaryDirectory() as directory, patch.dict(
            os.environ,
            {"MATH_AGENT_STUDENT_EXPLANATION_CHECKPOINT_DB": os.path.join(directory, "runs.sqlite3")},
        ):
            calls = []

            def stream_executor(request):
                calls.append(request.runId)
                yield {"event": "started", "data": {"runId": request.runId}}
                yield {"event": "completed", "data": {"runId": request.runId, "status": "COMPLETED", "cards": []}}

            request = StudentExplanationRunRequest.model_validate({
                "runId": "durable-stream-resume-1",
                "problem": "求定义域",
                "providerRoute": self.route(),
            })
            first = DurableStudentExplanationRuntime(lambda _: {}, stream_executor)
            fingerprint = first._fingerprint(request)
            first._store.save(request.runId, fingerprint, "RUNNING", None, {
                "event": "started", "data": {"runId": request.runId},
            })
            resumed = DurableStudentExplanationRuntime(lambda _: {}, stream_executor)
            events = list(resumed.stream_events(request))

            self.assertEqual(calls, [request.runId])
            self.assertEqual([event[1]["event"] for event in events], ["started", "resumed", "started", "completed"])

    def test_durable_stream_does_not_restart_an_interrupted_run(self):
        with tempfile.TemporaryDirectory() as directory, patch.dict(
            os.environ,
            {"MATH_AGENT_STUDENT_EXPLANATION_CHECKPOINT_DB": os.path.join(directory, "runs.sqlite3")},
        ):
            calls = []

            def stream_executor(request):
                calls.append(request.runId)
                yield {"event": "started", "data": {"runId": request.runId}}

            request = StudentExplanationRunRequest.model_validate({
                "runId": "durable-stream-interrupted-1",
                "problem": "求定义域",
                "providerRoute": self.route(),
            })
            first = DurableStudentExplanationRuntime(lambda _: {}, stream_executor)
            fingerprint = first._fingerprint(request)
            first._store.save(request.runId, fingerprint, "RUNNING", None, {
                "event": "delta", "data": {"runId": request.runId, "content": "第一段"},
            })
            resumed = DurableStudentExplanationRuntime(lambda _: {}, stream_executor)
            replay = list(resumed.stream_events(request))

            self.assertEqual(calls, [])
            self.assertEqual([event[1]["event"] for event in replay], ["delta", "error"])
            self.assertIn("STUDENT_EXPLANATION_RUN_INTERRUPTED", replay[-1][1]["data"]["message"])

    def test_student_explanation_stream_retries_503_before_visible_output(self):
        runtime = MigratedWorkloadRuntime()
        route = StudentExplanationRunRequest.model_validate({
            "runId": "explanation-retry-1",
            "problem": "求定义域",
            "providerRoute": self.route(),
        }).providerRoute
        response_lines = [
            'data: {"choices":[{"delta":{"content":"{\\"conversationTitle\\":\\"定义域\\",\\"cards\\":[]}"}}]}',
            "data: [DONE]",
        ]

        class FailedResponse:
            status_code = 503

            def __enter__(self):
                return self

            def __exit__(self, *_):
                return False

            def raise_for_status(self):
                from requests import HTTPError
                error = HTTPError("unavailable")
                error.response = self
                raise error

        class SuccessfulResponse:
            def __enter__(self):
                return self

            def __exit__(self, *_):
                return False

            def raise_for_status(self):
                return None

            def iter_lines(self, decode_unicode=True):
                return iter(response_lines)

        with patch.dict(os.environ, {"OPENAI_API_KEY": "test-key", "MATH_AGENT_STUDENT_EXPLANATION_RETRY_BACKOFF_SECONDS": "0"}), patch.object(
                runtime._session, "post", side_effect=[FailedResponse(), SuccessfulResponse()]), patch.object(runtime._ledger, "append") as append:
            events = list(runtime._stream_call_json("explanation-retry-1", route, [{"role": "user", "content": "x"}], True))

        self.assertEqual(len(events), 1)
        self.assertEqual(events[0]["attempt"], 2)
        self.assertEqual(append.call_args.args[0].attempt, 1)
        self.assertEqual(append.call_args.args[0].status, "FAILED")

    def test_student_explanation_stream_retries_non_json_before_visible_output(self):
        runtime = MigratedWorkloadRuntime()
        route = StudentExplanationRunRequest.model_validate({
            "runId": "explanation-invalid-json-retry-1",
            "problem": "求定义域",
            "providerRoute": self.route(),
        }).providerRoute

        class Response:
            def __init__(self, lines):
                self.lines = lines

            def __enter__(self):
                return self

            def __exit__(self, *_):
                return False

            def raise_for_status(self):
                return None

            def iter_lines(self, decode_unicode=True):
                return iter(self.lines)

        valid = 'data: {"choices":[{"delta":{"content":"{\\"conversationTitle\\":\\"定义域\\",\\"cards\\":[]}"}}]}\ndata: [DONE]'
        with patch.dict(os.environ, {
            "OPENAI_API_KEY": "test-key",
            "MATH_AGENT_STUDENT_EXPLANATION_MODEL_ATTEMPTS": "2",
            "MATH_AGENT_STUDENT_EXPLANATION_RETRY_BACKOFF_SECONDS": "0",
        }), patch.object(runtime._session, "post", side_effect=[
            Response(['data: {"choices":[{"delta":{"content":"not JSON"}}]}', "data: [DONE]"]),
            Response(valid.splitlines()),
        ]) as post:
            events = list(runtime._stream_call_json(
                "explanation-invalid-json-retry-1", route, [{"role": "user", "content": "x"}], True))

        self.assertEqual(post.call_count, 2)
        self.assertEqual(events[-1]["content"], '{"conversationTitle":"定义域","cards":[]}')
        self.assertEqual(events[-1]["attempt"], 2)

    def test_student_explanation_stream_ignores_relay_keepalive_frames(self):
        runtime = MigratedWorkloadRuntime()
        route = StudentExplanationRunRequest.model_validate({
            "runId": "explanation-relay-keepalive-1",
            "problem": "求定义域",
            "providerRoute": self.route(),
        }).providerRoute

        class Response:
            def __enter__(self):
                return self

            def __exit__(self, *_):
                return False

            def raise_for_status(self):
                return None

            def iter_lines(self, decode_unicode=True):
                return iter([
                    "data: ping",
                    'data: {"choices":[{"delta":{"content":"{\\"conversationTitle\\":\\"定义域\\",\\"cards\\":[]}"}}]}',
                    "data: keep-alive",
                    "data: [DONE]",
                ])

        with patch.dict(os.environ, {"OPENAI_API_KEY": "test-key"}), patch.object(
                runtime._session, "post", return_value=Response()) as post:
            events = list(runtime._stream_call_json(
                "explanation-relay-keepalive-1", route, [{"role": "user", "content": "x"}], True))

        self.assertEqual(post.call_count, 1)
        self.assertEqual(events[-1]["content"], '{"conversationTitle":"定义域","cards":[]}')

    def test_student_explanation_stream_logs_unknown_relay_frames_without_secrets(self):
        runtime = MigratedWorkloadRuntime()
        route = StudentExplanationRunRequest.model_validate({
            "runId": "explanation-unknown-relay-frame-1",
            "problem": "求定义域",
            "providerRoute": self.route(),
        }).providerRoute
        unknown_frame = "relay status authorization=Bearer secret-value api_key=another-secret"
        valid = 'data: {"choices":[{"delta":{"content":"{\\"conversationTitle\\":\\"定义域\\",\\"cards\\":[]}"}}]}\ndata: [DONE]'

        class Response:
            status_code = 200
            headers = {"Content-Type": "text/event-stream", "X-Request-ID": "relay-request-1"}

            def __init__(self, lines):
                self.lines = lines

            def __enter__(self):
                return self

            def __exit__(self, *_):
                return False

            def raise_for_status(self):
                return None

            def iter_lines(self, decode_unicode=True):
                return iter(self.lines)

        with patch.dict(os.environ, {
            "OPENAI_API_KEY": "test-key",
            "MATH_AGENT_STUDENT_EXPLANATION_MODEL_ATTEMPTS": "2",
            "MATH_AGENT_STUDENT_EXPLANATION_RETRY_BACKOFF_SECONDS": "0",
        }), patch.object(runtime._session, "post", side_effect=[
            Response(["data: " + unknown_frame]),
            Response(valid.splitlines()),
        ]) as post, patch("app.workload_runtime.logger.warning") as warning:
            events = list(runtime._stream_call_json(
                "explanation-unknown-relay-frame-1", route, [{"role": "user", "content": "x"}], True))

        self.assertEqual(post.call_count, 2)
        self.assertEqual(events[-1]["attempt"], 2)
        logged = warning.call_args.args[0]
        self.assertIn('"event": "provider_sse_non_json_frame"', logged)
        self.assertIn('"frameLength": ' + str(len(unknown_frame)), logged)
        self.assertIn('"requestId": "relay-request-1"', logged)
        self.assertNotIn("secret-value", logged)
        self.assertNotIn("another-secret", logged)

    def test_student_explanation_stream_accepts_multiline_sse_data(self):
        runtime = MigratedWorkloadRuntime()
        route = StudentExplanationRunRequest.model_validate({
            "runId": "explanation-multiline-sse-1",
            "problem": "求定义域",
            "providerRoute": self.route(),
        }).providerRoute

        class Response:
            def __enter__(self):
                return self

            def __exit__(self, *_):
                return False

            def raise_for_status(self):
                return None

            def iter_lines(self, decode_unicode=True):
                return iter([
                    "event: chat.completion.chunk",
                    'data: {"choices":[{"delta":',
                    'data: {"content":"{\\"conversationTitle\\":\\"定义域\\",\\"cards\\":[]}"}}]}',
                    "",
                    "data: [DONE]",
                ])

        with patch.dict(os.environ, {"OPENAI_API_KEY": "test-key"}), patch.object(
                runtime._session, "post", return_value=Response()) as post:
            events = list(runtime._stream_call_json(
                "explanation-multiline-sse-1", route, [{"role": "user", "content": "x"}], True))

        self.assertEqual(post.call_count, 1)
        self.assertEqual(events[-1]["content"], '{"conversationTitle":"定义域","cards":[]}')

    def test_student_explanation_stream_does_not_retry_after_visible_output(self):
        runtime = MigratedWorkloadRuntime()
        route = StudentExplanationRunRequest.model_validate({
            "runId": "explanation-visible-interrupt-1",
            "problem": "求定义域",
            "providerRoute": self.route(),
        }).providerRoute

        class InterruptedResponse:
            def __enter__(self):
                return self

            def __exit__(self, *_):
                return False

            def raise_for_status(self):
                return None

            def iter_lines(self, decode_unicode=True):
                from requests import RequestException
                yield 'data: {"choices":[{"delta":{"content":"partial"}}]}'
                raise RequestException("interrupted")

        with patch.dict(os.environ, {"OPENAI_API_KEY": "test-key"}), patch.object(runtime._session, "post", return_value=InterruptedResponse()) as post:
            with self.assertRaises(HTTPException) as error:
                list(runtime._stream_call_json("explanation-visible-interrupt-1", route, [{"role": "user", "content": "x"}]))

        self.assertIn("after visible output", str(error.exception.detail))
        self.assertEqual(post.call_count, 1)

    def test_student_explanation_stream_emits_incremental_sse_events(self):
        from app.server import migrated_workload_runtime
        migrated_workload_runtime.cache_clear()
        response_lines = [
            'data: {"choices":[{"delta":{"content":"{\\"decision\\":\\"final\\","}}]}',
            'data: {"choices":[{"delta":{"content":"\\"conversationTitle\\":\\"定义域\\","}}]}',
            'data: {"choices":[{"delta":{"content":"\\"cards\\":[{\\"summary\\":\\"先看分母。\\"}]}"}}]}',
            'data: [DONE]',
        ]
        class FakeResponse:
            def __enter__(self):
                return self
            def __exit__(self, *_):
                return False
            def raise_for_status(self):
                return None
            def iter_lines(self, decode_unicode=True):
                return iter(response_lines)
        with patch.object(migrated_workload_runtime(), "_session") as session, patch(
            "app.workload_runtime.fallback_tokens", return_value=(2, 3, 5)
        ):
            session.post.return_value = FakeResponse()
            response = self.client.post(
                "/v1/student-explanations/stream",
                headers={"Authorization": "Bearer worker-test-key"},
                json={
                    "runId": "explanation-stream-1",
                    "mode": "react",
                    "problem": "求函数定义域",
                    "availableTools": [],
                    "evidence": [],
                    "providerRoute": self.route(),
                },
            )
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.headers["content-type"].split(";")[0], "text/event-stream")
        self.assertEqual(response.text.count("event: delta"), 3)
        self.assertIn("id: 1\nevent: started\n", response.text)
        self.assertIn("id: 2\nevent: delta\n", response.text)
        self.assertIn("event: completed\n", response.text)
        self.assertIn('"providerName":"openai"', response.text)

    def test_student_explanation_compose_stream_returns_cards(self):
        from app.server import migrated_workload_runtime
        migrated_workload_runtime.cache_clear()
        content = json.dumps({
            "conversationTitle": "函数定义域",
            "cards": [{"cardKey": "domain", "summary": "先看分母。", "renderMode": "text"}],
        }, ensure_ascii=False)
        response_lines = [
            "data: " + json.dumps({"choices": [{"delta": {"content": content[:20]}}]}, ensure_ascii=False),
            "data: " + json.dumps({"choices": [{"delta": {"content": content[20:]}}]}, ensure_ascii=False),
            "data: " + json.dumps({"usage": {"prompt_tokens": 2, "completion_tokens": 3, "total_tokens": 5}}),
        ]

        class FakeResponse:
            def __enter__(self):
                return self

            def __exit__(self, *_):
                return False

            def raise_for_status(self):
                return None

            def iter_lines(self, decode_unicode=True):
                return iter(response_lines)

        with patch.object(migrated_workload_runtime(), "_session") as session, patch.object(
            migrated_workload_runtime(), "_result_from_stream", return_value=type(
                "Result", (), {"usage": lambda self: {"promptTokens": 2, "completionTokens": 3, "totalTokens": 5, "estimatedCost": -1.0}}
            )()
        ):
            session.post.return_value = FakeResponse()
            response = self.client.post(
                "/v1/student-explanations/stream",
                headers={"Authorization": "Bearer worker-test-key"},
                json={
                    "runId": "explanation-compose-stream-1",
                    "mode": "compose",
                    "problem": "求函数定义域",
                    "evidence": [],
                    "providerRoute": self.route(),
                },
            )
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.text.count("event: delta"), 0)
        self.assertIn('"decision":"final"', response.text)
        self.assertIn('"cardKey":"domain"', response.text)

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
