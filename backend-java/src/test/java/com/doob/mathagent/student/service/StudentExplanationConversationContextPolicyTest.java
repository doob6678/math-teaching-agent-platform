package com.doob.mathagent.student.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class StudentExplanationConversationContextPolicyTest {

    @Test
    void bypassesRemoteCompressionForOneShortPersistedChineseTurn() {
        List<StudentExplanationConversationContextMessage> messages = List.of(
                new StudentExplanationConversationContextMessage(
                        "turn-1",
                        "有没有解析几何讲解的",
                        "可以从点到点距离公式开始。$P(x,y)$ 到另一点的距离为 $\\sqrt{(x_1-x_2)^2+(y_1-y_2)^2}$。",
                        LocalDateTime.parse("2026-08-17T08:00:00")));

        assertThat(StudentExplanationService.canUseRawRecentContext(messages, null)).isTrue();
        String packed = StudentExplanationService.packRawRecentContext("继续讲距离公式", messages);

        assertThat(packed)
                .contains("最近会话：", "有没有解析几何讲解的", "P(x,y)", "\\sqrt{(x_1-x_2)^2+(y_1-y_2)^2}")
                .doesNotContain("\uFFFD", "ext");
    }

    @Test
    void keepsRemotePreparationForSummaryOrContextPressure() {
        List<StudentExplanationConversationContextMessage> messages = List.of(
                new StudentExplanationConversationContextMessage("turn-1", "短问题", "短回答", LocalDateTime.now()));
        StudentExplanationContextSummary summary = new StudentExplanationContextSummary(
                "turn-0", "turn-0", 1, "a".repeat(64), "已有摘要", LocalDateTime.now());

        assertThat(StudentExplanationService.canUseRawRecentContext(messages, summary)).isFalse();
        assertThat(StudentExplanationService.canUseRawRecentContext(List.of(
                messages.getFirst(), messages.getFirst(), messages.getFirst(), messages.getFirst(), messages.getFirst()), null))
                .isFalse();
    }
}
