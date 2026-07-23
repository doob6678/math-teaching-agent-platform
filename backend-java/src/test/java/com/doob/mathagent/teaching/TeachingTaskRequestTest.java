package com.doob.mathagent.teaching;

import com.doob.mathagent.teaching.dto.TeachingTaskRequest;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Verifies that the handout UI's supplementary field cannot leak into printable question content. */
class TeachingTaskRequestTest {

    /**
     * Evidence count is a retrieval preference, not a permission boundary.  It must not reject a real handout
     * request solely because it contains more than the old UI default of ten sources.
     */
    @Test
    void doesNotImposeAnArbitraryMaximumOnEvidenceRetrieval() {
        TeachingTaskRequest request = new TeachingTaskRequest(
                "req-eleven-evidence", "二次函数", "二次函数最值", 11);

        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(request)).isEmpty();
        }
    }

    @Test
    void treatsLayoutRequirementsAsInstructionsInsteadOfQuestion() {
        TeachingTaskRequest request = new TeachingTaskRequest(
                "req-normalize-requirements",
                "教师版含原题答案；学生版一题一页；16:10一页一道题；标准LaTeX；禁止占位符。",
                "二次函数最值",
                10);

        assertThat(request.normalize().questionText()).isEqualTo("二次函数最值");
    }

    @Test
    void preservesARealMathQuestion() {
        TeachingTaskRequest request = new TeachingTaskRequest(
                "req-normalize-question",
                "已知 f(x)=x^2-2x+1，求 f(3)。",
                "二次函数求值",
                10);

        assertThat(request.normalize().questionText()).contains("求 f(3)");
    }
}
