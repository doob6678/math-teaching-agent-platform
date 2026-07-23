package com.doob.mathagent.learning;

import static org.assertj.core.api.Assertions.assertThat;

import com.doob.mathagent.infrastructure.security.RequestSubject;
import com.doob.mathagent.knowledge.service.InMemoryKnowledgeQuestionBankStore;
import com.doob.mathagent.knowledge.service.KnowledgeQuestionBankService;
import com.doob.mathagent.knowledge.service.QuestionBankItemRecord;
import com.doob.mathagent.learning.dto.TargetedPracticeRequest;
import com.doob.mathagent.learning.service.TargetedPracticeService;
import com.doob.mathagent.teaching.TeachingTaskStatus;
import com.doob.mathagent.teaching.TeachingRequestContext;
import com.doob.mathagent.teaching.dto.TeachingTaskRequest;
import com.doob.mathagent.teaching.vo.TeachingTaskResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Verifies practice generation is grounded in linked questions and exposes only the student projection. */
class TargetedPracticeServiceTest {
    @Test
    void createsGroundedStudentTaskAndRedactsTeacherDraft() {
        InMemoryStudentLearningLoopStore learningStore = new InMemoryStudentLearningLoopStore();
        InMemoryKnowledgeQuestionBankStore questionStore = new InMemoryKnowledgeQuestionBankStore();
        questionStore.saveQuestion(new QuestionBankItemRecord(
                "q-source", "tenant-a", "teacher-a", "MATH_VIP", "单调性基础题", "求函数的单调区间",
                "{\"answer\":\"x>0\"}", "medium", "active", List.of("函数单调性")));
        KnowledgeQuestionBankService questionBank = new KnowledgeQuestionBankService(questionStore);
        StudentLearningLoopService learning = new StudentLearningLoopService(
                learningStore, questionBank, Clock.fixed(Instant.parse("2026-07-23T00:00:00Z"), ZoneOffset.UTC));
        learning.recordAttempt("tenant-a", "student-a", "q-wrong", "函数单调性题", List.of("函数单调性"), false, 900);
        CapturingGateway gateway = new CapturingGateway();
        TargetedPracticeService service = new TargetedPracticeService(learning, questionBank, gateway);

        var response = service.submit(
                new TargetedPracticeRequest("practice-1", "函数单调性", 5, 3),
                new RequestSubject("tenant-a", "student", "student-a", "device-a"));

        assertThat(gateway.request.questionText()).contains("q-source", "求函数的单调区间");
        assertThat(gateway.request.supplementaryRequirements()).contains("全新", "学生版");
        assertThat(response.studentId()).isEqualTo("student-a");
        assertThat(response.studentHandoutLatex()).contains("学生题目");
        assertThat(response.errorMessage()).isNull();
    }

    private static final class CapturingGateway implements TargetedPracticeService.PracticeTaskGateway {
        private TeachingTaskRequest request;

        @Override
        public TeachingTaskResponse submit(TeachingTaskRequest request, TeachingRequestContext context) {
            this.request = request;
            return new TeachingTaskResponse(
                    "task-1", request.clientRequestId(), context.tenantId(), "student", context.subjectId(),
                    TeachingTaskStatus.CREATED, request.questionText(), request.learningGoal(), List.of(), List.of(),
                    List.of(), "", "teacher-answer", "学生题目", List.of(), null, List.of(), null, null);
        }

        @Override
        public Optional<TeachingTaskResponse> get(String taskId, TeachingRequestContext context) {
            return Optional.empty();
        }
    }
}
