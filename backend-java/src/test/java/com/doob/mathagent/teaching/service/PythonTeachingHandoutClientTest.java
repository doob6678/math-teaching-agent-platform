package com.doob.mathagent.teaching.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.doob.mathagent.teaching.vo.TeachingTaskResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/** Ensures the teaching-task adapter consumes the one Python handout graph, not the retired draft contract. */
class PythonTeachingHandoutClientTest {

    @Test
    void projectsAllThreeAudienceDocumentsAndUsageFromTheHandoutGraph() throws Exception {
        TeachingTaskResponse.AiDraft draft = PythonTeachingHandoutClient.project(new ObjectMapper().readTree("""
                {
                  "status":"COMPLETED",
                  "documents":{
                    "teacher_writer":{"markdown":"教师逐题讲解"},
                    "student_writer":{"markdown":"学生练习"},
                    "lecture_writer":{"markdown":"课堂投影"}
                  },
                  "metrics":{
                    "promptTokens":120,
                    "completionTokens":80,
                    "totalTokens":200,
                    "nodeMetrics":[{"node":"teacher_writer","provider":"openai","model":"gpt-5.6-luna","status":"COMPLETED"}]
                  }
                }
                """));

        assertThat(draft.structured()).isTrue();
        assertThat(draft.teacherExplanation()).isEqualTo("教师逐题讲解");
        assertThat(draft.studentHint()).isEqualTo("学生练习");
        assertThat(draft.lectureContent()).isEqualTo("课堂投影");
        assertThat(draft.followUpQuestions()).isEmpty();
        assertThat(draft.providerName()).isEqualTo("openai");
        assertThat(draft.modelCode()).isEqualTo("gpt-5.6-luna");
        assertThat(draft.totalTokens()).isEqualTo(200);
    }
}
