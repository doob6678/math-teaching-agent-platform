import unittest

from app.provider_diagnostics import (
    CredentialKind,
    RouteDisposition,
    classify_credential_kind,
    classify_three_attempts,
    ensure_api_key_compatible,
    safe_http_failure,
)


class ProviderDiagnosticsTest(unittest.TestCase):
    def test_codex_oauth_or_session_shape_is_rejected(self):
        self.assertEqual(classify_credential_kind("header.payload.signature"), CredentialKind.OAUTH_OR_SESSION)
        with self.assertRaises(ValueError):
            ensure_api_key_compatible("header.payload.signature")

    def test_openai_api_key_shape_is_accepted_without_revealing_value(self):
        self.assertEqual(classify_credential_kind("sk-" + "a" * 32), CredentialKind.API_KEY)
        ensure_api_key_compatible("sk-" + "a" * 32)

    def test_three_matching_failures_are_required(self):
        self.assertEqual(
            classify_three_attempts(["HTTP_5XX", "HTTP_5XX", "HTTP_5XX"]),
            RouteDisposition.FAILED,
        )
        self.assertEqual(
            classify_three_attempts(["HTTP_5XX", "HTTP_429", "HTTP_5XX"]),
            RouteDisposition.TRANSIENT_MIXED,
        )
        self.assertEqual(classify_three_attempts(["HTTP_5XX", "HTTP_5XX"]), RouteDisposition.INCONCLUSIVE)

    def test_http_failure_keeps_only_safe_schema_and_hash(self):
        class Response:
            status_code = 503
            headers = {"Content-Type": "application/json", "X-Request-ID": "request-1"}
            content = b'{"error":{"type":"server_error","code":"upstream_unavailable"}}'

            def json(self):
                return {"error": {"type": "server_error", "code": "upstream_unavailable"}}

        summary = safe_http_failure(Response())

        self.assertEqual(summary["status"], 503)
        self.assertEqual(summary["category"], "provider_error_json")
        self.assertEqual(summary["errorType"], "server_error")
        self.assertEqual(summary["errorCode"], "upstream_unavailable")
        self.assertNotIn("content", summary)
        self.assertNotIn("Authorization", str(summary))


if __name__ == "__main__":
    unittest.main()
