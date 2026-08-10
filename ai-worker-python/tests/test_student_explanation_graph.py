import unittest

from app.student_explanation_graph import (
    ConversationContextMessage,
    ConversationContextSnapshot,
    StudentExplanationContextGraph,
    StudentExplanationGraphLimits,
    StudentExplanationGraphRequest,
)


class StudentExplanationContextGraphTest(unittest.TestCase):
    @staticmethod
    def request(messages, max_input=2_000, trigger=300):
        return StudentExplanationGraphRequest.model_validate({
            "contractVersion": "student-explanation-ai-v2",
            "runId": "context-run-1",
            "deadlineEpochMs": 4_000_000_000_000,
            "problem": "求二次函数的值域",
            "context": {
                "schemaVersion": "student-conversation-context-v1",
                "revision": "r1",
                "messages": [message.model_dump() for message in messages],
            },
            "limits": {
                "maxInputTokens": max_input,
                "reservedOutputTokens": 256,
                "summaryTriggerTokens": trigger,
                "maxProviderCalls": 1,
            },
            "providerRoute": {"primary": {"name": "openai", "model": "gpt-4o"}, "fallbacks": []},
        })

    def test_orders_messages_and_keeps_recent_context_under_budget(self):
        messages = [
            ConversationContextMessage(messageId="m2", questionText="第二轮", answerText="答二", createdAt="2026-08-08T10:00:00"),
            ConversationContextMessage(messageId="m1", questionText="第一轮", answerText="答一", createdAt="2026-08-08T09:00:00"),
        ]
        prepared = StudentExplanationContextGraph().prepare(self.request(messages))
        self.assertLessEqual(prepared["inputTokens"], 1_744)
        self.assertEqual(prepared["selectedMessageIds"], ["m1", "m2"])
        self.assertIn("第一轮", prepared["packedContext"])

    def test_emits_idempotent_summary_update_when_history_exceeds_trigger(self):
        messages = [
            ConversationContextMessage(messageId=f"m{i}", questionText="很长的数学题" * 40, answerText="结论" * 40, createdAt=f"2026-08-08T0{i}:00:00")
            for i in range(3)
        ]
        prepared = StudentExplanationContextGraph().prepare(self.request(messages, max_input=2_000, trigger=256))
        self.assertIsNotNone(prepared["memoryUpdate"])
        self.assertEqual(prepared["memoryUpdate"]["summaryFromMessageId"], "m0")
        self.assertEqual(prepared["memoryUpdate"]["summaryToMessageId"], "m1")

    def test_does_not_repeat_messages_covered_by_confirmed_summary(self):
        messages = [
            ConversationContextMessage(messageId="m1", questionText="第一轮", answerText="答一", createdAt="2026-08-08T09:00:00"),
            ConversationContextMessage(messageId="m2", questionText="第二轮", answerText="答二", createdAt="2026-08-08T10:00:00"),
            ConversationContextMessage(messageId="m3", questionText="第三轮", answerText="答三", createdAt="2026-08-08T11:00:00"),
        ]
        payload = self.request(messages).model_dump()
        payload["context"]["summary"] = {
            "summaryFromMessageId": "m1",
            "summaryToMessageId": "m2",
            "summaryVersion": 1,
            "contentHash": "a" * 64,
            "content": "此前已经讨论了两轮。",
        }
        prepared = StudentExplanationContextGraph().prepare(StudentExplanationGraphRequest.model_validate(payload))
        self.assertEqual(prepared["selectedMessageIds"], ["m3"])
        self.assertNotIn("第一轮", prepared["packedContext"])
        self.assertIn("此前已经讨论了两轮", prepared["packedContext"])

    def test_rejects_extra_identity_fields(self):
        payload = self.request([]).model_dump()
        payload["tenantId"] = "school-a"
        with self.assertRaises(Exception):
            StudentExplanationGraphRequest.model_validate(payload)


if __name__ == "__main__":
    unittest.main()
