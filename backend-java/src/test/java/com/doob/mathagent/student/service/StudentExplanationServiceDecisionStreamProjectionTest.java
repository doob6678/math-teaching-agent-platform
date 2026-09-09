package com.doob.mathagent.student.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.doob.mathagent.agent.service.AiChatStreamDelta;
import org.junit.jupiter.api.Test;

/**
 * 决策流 content 槽位可见性投影的单测（方案二，2026-09-06）。
 *
 * <p>finalDraft 正文流式只在“草稿即终稿”（无显式模型偏好）时放行；偏好绑定会丢弃草稿并强制 compose，
 * 此时必须剥离 content 增量，只保留思考，避免默认 fast 模型的草稿先流给学生再被整体替换。</p>
 */
class StudentExplanationServiceDecisionStreamProjectionTest {

    @Test
    void keepsContentWhenDraftIsFinalAnswer() {
        AiChatStreamDelta delta = new AiChatStreamDelta("openai", "gpt-5.6-terra", "思考", "{\"title\":\"解答", 0, 0, 0);
        assertThat(StudentExplanationService.projectDecisionDelta(delta, true)).isSameAs(delta);
    }

    @Test
    void stripsContentButKeepsReasoningWhenDraftWillBeRecomposed() {
        AiChatStreamDelta delta = new AiChatStreamDelta("openai", "gpt-5.6-terra", "思考", "{\"title\":\"解答", 11, 22, 33);
        AiChatStreamDelta projected = StudentExplanationService.projectDecisionDelta(delta, false);
        assertThat(projected.reasoningDelta()).isEqualTo("思考");
        assertThat(projected.contentDelta()).isEmpty();
        assertThat(projected.promptTokens()).isEqualTo(11);
        assertThat(projected.completionTokens()).isEqualTo(22);
    }

    @Test
    void passesThroughReasoningOnlyDeltas() {
        AiChatStreamDelta delta = new AiChatStreamDelta("openai", "gpt-5.6-terra", "思考", "", 0, 0, 0);
        assertThat(StudentExplanationService.projectDecisionDelta(delta, false)).isSameAs(delta);
    }
}
