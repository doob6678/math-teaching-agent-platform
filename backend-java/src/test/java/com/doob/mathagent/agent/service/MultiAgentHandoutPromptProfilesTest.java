package com.doob.mathagent.agent.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MultiAgentHandoutPromptProfilesTest {

    /**
     * Structured writer stages are validated by the shared executor, so their prompt must make the required JSON
     * envelope explicit before Luna begins composing classroom prose.
     */
    @Test
    void publishableWriterPromptsRequireOnlyOneJsonObjectWithTheirStageKey() {
        assertThat(MultiAgentHandoutPromptProfiles.instructionsFor("teacher_writer"))
                .contains("exactly one JSON object", "teacherExplanation");
        assertThat(MultiAgentHandoutPromptProfiles.instructionsFor("student_writer"))
                .contains("exactly one JSON object", "studentWorksheet");
        assertThat(MultiAgentHandoutPromptProfiles.instructionsFor("lecture_writer"))
                .contains("exactly one JSON object", "lectureCards");
    }

    /** A supplied multi-question batch must be explained as submitted, not inflated with unsupported exercises. */
    @Test
    void teacherPromptPreservesEverySubmittedBatchQuestionInsteadOfRequiringExtraOriginalProblems() {
        String prompt = MultiAgentHandoutPromptProfiles.instructionsFor(
                "teacher_writer",
                "【题目 1】\n函数最值\n\n---\n\n【题目 2】\n空间线面角");

        assertThat(prompt)
                .contains("every submitted question", "Do not add invented replacement questions")
                .doesNotContain("Required original-problem count");
    }
}
