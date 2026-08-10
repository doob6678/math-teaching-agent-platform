package com.doob.mathagent.student;

import static org.assertj.core.api.Assertions.assertThat;

import com.doob.mathagent.student.service.StudentExplanationConversationContextCacheProperties;
import com.doob.mathagent.student.service.StudentExplanationConversationContextMessage;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class StudentExplanationConversationContextCachePropertiesTest {

    @Test
    void normalizesBlankPrefixAndInvalidTtl() {
        StudentExplanationConversationContextCacheProperties properties =
                new StudentExplanationConversationContextCacheProperties(true, " ", Duration.ZERO);

        assertThat(properties.normalizedKeyPrefix()).isEqualTo("math-agent:student:conversation-context:v1");
        assertThat(properties.normalizedTtl()).isEqualTo(Duration.ofMinutes(30));
    }

    @Test
    void contextMessageContainsOnlyModelSafeFields() {
        StudentExplanationConversationContextMessage message = new StudentExplanationConversationContextMessage(
                "explanation-1", "求函数值域", "先配方再判断顶点", LocalDateTime.parse("2026-08-08T09:30:00"));

        assertThat(List.of(message)).extracting(
                StudentExplanationConversationContextMessage::explanationId,
                StudentExplanationConversationContextMessage::questionText,
                StudentExplanationConversationContextMessage::answerText)
                .containsExactly(tuple("explanation-1", "求函数值域", "先配方再判断顶点"));
    }

    private static org.assertj.core.groups.Tuple tuple(Object... values) {
        return org.assertj.core.groups.Tuple.tuple(values);
    }
}
