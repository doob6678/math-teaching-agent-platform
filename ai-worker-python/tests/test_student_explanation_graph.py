import json
import unittest

from app.student_explanation_graph import (
    DEFAULT_MAX_INPUT_TOKENS,
    DEFAULT_RESERVED_OUTPUT_TOKENS,
    DEFAULT_SUMMARY_TRIGGER_TOKENS,
    ConversationContextMessage,
    ConversationContextSnapshot,
    StudentExplanationContextGraph,
    StudentExplanationGraphLimits,
    StudentExplanationGraphRequest,
)

# 哨兵：省略 limits 字段，让契约默认值（130k 触发/131072 窗口）生效。
_OMIT = object()


class StudentExplanationContextGraphTest(unittest.TestCase):
    @staticmethod
    def request(messages, max_input=2_000, trigger=300, limits=None):
        payload = {
            "contractVersion": "student-explanation-ai-v2",
            "runId": "context-run-1",
            "deadlineEpochMs": 4_000_000_000_000,
            "problem": "求二次函数的值域",
            "context": {
                "schemaVersion": "student-conversation-context-v1",
                "revision": "r1",
                "messages": [message.model_dump() for message in messages],
            },
            "providerRoute": {"primary": {"name": "openai", "model": "gpt-4o"}, "fallbacks": []},
        }
        # limits=None 时省略该字段，走契约默认值，用于验证 130k 兜底阈值本身。
        payload["limits"] = limits if limits is not None else {
            "maxInputTokens": max_input,
            "reservedOutputTokens": 256,
            "summaryTriggerTokens": trigger,
            "maxProviderCalls": 1,
        }
        if limits is _OMIT:
            payload.pop("limits")
        return StudentExplanationGraphRequest.model_validate(payload)

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

    def test_limits_default_to_the_prefix_cache_friendly_thresholds(self):
        # 2026-09-02 决策：触发阈值抬到 130k、窗口预算 131072（旧上限 4000/8000 与旧契约
        # 上限 100_000/120_000 会直接拒绝新量级，因此默认值与上限同时写入契约常量）。
        limits = StudentExplanationGraphLimits()
        self.assertEqual(DEFAULT_SUMMARY_TRIGGER_TOKENS, 130_000)
        self.assertEqual(DEFAULT_MAX_INPUT_TOKENS, 131_072)
        self.assertEqual(limits.summaryTriggerTokens, 130_000)
        self.assertEqual(limits.maxInputTokens, 131_072)
        self.assertEqual(limits.reservedOutputTokens, DEFAULT_RESERVED_OUTPUT_TOKENS)

    def test_default_trigger_never_compresses_or_calls_the_model_for_normal_history(self):
        # 压缩是兜底：常态会话不改写消息前缀、不产生摘要模型调用，provider prefix cache 保持有效。
        calls = []

        def summarizer(run_id, route, messages):
            calls.append(run_id)
            return self.structured_payload()

        messages = [
            ConversationContextMessage(
                messageId=f"m{i}", questionText=f"第{i}轮问题", answerText=f"第{i}轮解答",
                createdAt=f"2026-08-08T{i % 24:02d}:00:00")
            for i in range(40)
        ]
        prepared = StudentExplanationContextGraph(summarizer=summarizer).prepare(
            self.request(messages, limits=_OMIT))
        self.assertIsNone(prepared["memoryUpdate"])
        self.assertEqual(calls, [])
        self.assertEqual(len(prepared["selectedMessageIds"]), 40)
        self.assertLessEqual(prepared["inputTokens"], 131_072 - DEFAULT_RESERVED_OUTPUT_TOKENS)

    @staticmethod
    def structured_payload() -> str:
        return json.dumps({
            "goal": "理解二次函数值域",
            "completed": "已讲配方法步骤",
            "readSources": "教材 textbook://b1/chunk-9 第 12 页",
            "decisions": "选择配方法而非判别式法",
            "conclusions": "值域为 [1,+∞)",
        }, ensure_ascii=False)

    @staticmethod
    def long_history():
        return [
            ConversationContextMessage(
                messageId=f"m{i}", questionText="很长的数学题" * 40, answerText="结论" * 40,
                createdAt=f"2026-08-08T0{i}:00:00")
            for i in range(3)
        ]

    def test_five_dimension_structured_summary_is_preferred_over_extractive(self):
        calls = []

        def summarizer(run_id, route, messages):
            calls.append({"runId": run_id, "route": route, "messages": messages})
            return self.structured_payload()

        prepared = StudentExplanationContextGraph(summarizer=summarizer).prepare(
            self.request(self.long_history(), max_input=2_000, trigger=256))
        update = prepared["memoryUpdate"]
        self.assertIsNotNone(update)
        # 摘要调用必须带 Java 签发的 runId 与 route，供 UsageLedger 记账归属。
        self.assertEqual(len(calls), 1)
        self.assertEqual(calls[0]["runId"], "context-run-1")
        self.assertEqual(calls[0]["messages"][0]["role"], "system")
        self.assertIn("五个维度", calls[0]["messages"][0]["content"])
        transcript = json.loads(calls[0]["messages"][1]["content"])
        # 只压缩未摘要区间：最新一轮保持 verbatim，不送进摘要。
        self.assertEqual(len(transcript["turns"]), 2)
        self.assertEqual(transcript["previousSummary"], "")
        for label in ("目标：", "已完成：", "已阅读资料：", "选择：", "结论："):
            self.assertIn(label, update["content"])
        self.assertIn("textbook://b1/chunk-9", update["content"])
        self.assertEqual(update["summaryFromMessageId"], "m0")
        self.assertEqual(update["summaryToMessageId"], "m1")
        self.assertEqual(update["summaryVersion"], 1)
        self.assertIn("配方法", prepared["packedContext"])
        self.assertIn("已确认的较早会话摘要", prepared["packedContext"])
        # 最新一轮保持 verbatim 进入窗口，而不是被摘要吞掉。
        self.assertEqual(prepared["selectedMessageIds"], ["m2"])

    def test_structured_summary_failure_falls_back_to_extractive(self):
        def failing(run_id, route, messages):
            raise RuntimeError("provider down")

        prepared = StudentExplanationContextGraph(summarizer=failing).prepare(
            self.request(self.long_history(), max_input=2_000, trigger=256))
        update = prepared["memoryUpdate"]
        self.assertIsNotNone(update)
        # 回退后仍是确定性抽取式产物（"用户：/助手：" 前缀、每条 480 字截断），压缩兜底不因模型故障失效。
        self.assertIn("用户：", update["content"])
        self.assertNotIn("目标：", update["content"])

    def test_structured_summary_rejects_output_outside_the_five_field_contract(self):
        for bad_output in (
                "对不起，我直接复述对话。",
                json.dumps({"goal": "只有目标", "unexpected": 1}, ensure_ascii=False)):
            prepared = StudentExplanationContextGraph(
                summarizer=lambda run_id, route, messages, output=bad_output: output).prepare(
                self.request(self.long_history(), max_input=2_000, trigger=256))
            update = prepared["memoryUpdate"]
            self.assertIsNotNone(update)
            self.assertIn("用户：", update["content"])

    def test_structured_summary_folds_previous_summary_and_extends_interval(self):
        messages = self.long_history() + [
            ConversationContextMessage(
                messageId="m3", questionText="追加一轮讨论" * 40, answerText="新的结论" * 40,
                createdAt="2026-08-08T04:00:00"),
            ConversationContextMessage(
                messageId="m4", questionText="最新一轮" * 40, answerText="最新结论" * 40,
                createdAt="2026-08-08T05:00:00"),
        ]
        payload = self.request(messages, max_input=4_000, trigger=256).model_dump()
        payload["context"]["summary"] = {
            "summaryFromMessageId": "m0",
            "summaryToMessageId": "m2",
            "summaryVersion": 2,
            "contentHash": "b" * 64,
            "content": "目标：旧摘要",
        }
        seen = {}

        def summarizer(run_id, route, messages_):
            seen["user"] = json.loads(messages_[1]["content"])
            return self.structured_payload()

        prepared = StudentExplanationContextGraph(summarizer=summarizer).prepare(
            StudentExplanationGraphRequest.model_validate(payload))
        update = prepared["memoryUpdate"]
        # 区间从既有限尾之后继续推进，fromMessageId 与 version 的持久化协议不因新算法改变。
        self.assertEqual(seen["user"]["previousSummary"], "目标：旧摘要")
        self.assertEqual(update["summaryFromMessageId"], "m0")
        self.assertEqual(update["summaryToMessageId"], "m3")
        self.assertEqual(update["summaryVersion"], 3)
        # 结构化产物整体替换旧摘要（提示词已要求折叠），不再追加堆叠。
        self.assertNotIn("旧摘要", update["content"])


if __name__ == "__main__":
    unittest.main()
