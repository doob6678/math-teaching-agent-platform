import unittest
from unittest.mock import patch

from app.teaching_draft_runtime import TeachingDraftRequest, TeachingDraftRuntime


class TeachingDraftReviewRuntimeTest(unittest.TestCase):
    def test_approved_envelope_returns_existing_draft_contract_without_review_metadata(self):
        request = TeachingDraftRequest.model_validate({
            "runId": "teaching-review-run-1",
            "taskId": "task-1",
            "writingGoal": "讲解函数定义域",
            "questionText": "求函数的定义域",
        })
        response_body = (
            '{"candidate":{"teacherExplanation":"先判断限制条件。","studentHint":"先列出限制条件。",'
            '"knowledgePoints":["函数定义域"],"followUpQuestions":["分母何时为零？"]},'
            '"review":{"approved":true,"feedbackCodes":[]}}'
        )
        runtime = TeachingDraftRuntime()
        with patch.object(runtime, "_provider_order", return_value=["openai"]), patch.object(
                runtime, "_call_provider", return_value=(response_body, {
                    "promptTokens": 1, "completionTokens": 1, "totalTokens": 2, "estimatedCost": 0.0,
                })):
            result = runtime.execute(request)

        self.assertEqual(result["status"], "COMPLETED")
        self.assertEqual(result["draft"]["teacherExplanation"], "先判断限制条件。")
        self.assertNotIn("review", result)
        self.assertNotIn("review", result["metrics"]["attempts"][0])


if __name__ == "__main__":
    unittest.main()
