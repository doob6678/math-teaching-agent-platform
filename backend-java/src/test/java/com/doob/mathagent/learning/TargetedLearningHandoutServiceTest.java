package com.doob.mathagent.learning;

import static org.assertj.core.api.Assertions.assertThat;

import com.doob.mathagent.infrastructure.security.RequestSubject;
import com.doob.mathagent.knowledge.service.KnowledgeQuestionBankService;
import com.doob.mathagent.knowledge.service.InMemoryKnowledgeQuestionBankStore;
import com.doob.mathagent.knowledge.service.QuestionBankItemRecord;
import com.doob.mathagent.learning.dto.TargetedHandoutRequest;
import com.doob.mathagent.learning.service.TargetedLearningHandoutService;
import com.doob.mathagent.teaching.dto.TeachingTaskRequest;
import com.doob.mathagent.teaching.service.LectureTaskSubmissionService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Verifies that targeted handouts are assembled from diagnosis facts and linked question-bank evidence. */
class TargetedLearningHandoutServiceTest {
    @Test
    void submitsRealWeakPointAndQuestionContextToExistingTeachingWorkflow() {
        InMemoryStudentLearningLoopStore store = new InMemoryStudentLearningLoopStore();
        InMemoryKnowledgeQuestionBankStore questionStore = new InMemoryKnowledgeQuestionBankStore();
        questionStore.saveQuestion(new QuestionBankItemRecord(
                "q-bank", "tenant-a", "teacher-a", "TEACHER_PRIVATE", "单调性练习", "求函数的单调区间",
                "{}", "medium", "active", List.of("函数单调性")));
        KnowledgeQuestionBankService questionBank = new KnowledgeQuestionBankService(questionStore);
        CapturingSubmissionService submission = new CapturingSubmissionService();
        StudentLearningLoopService learning = new StudentLearningLoopService(store, questionBank,
                Clock.fixed(Instant.parse("2026-07-23T00:00:00Z"), ZoneOffset.UTC));
        learning.recordAttempt("tenant-a", "student-a", "q-1", "函数单调性题", List.of("函数单调性"), false, 1200);

        TargetedLearningHandoutService service = new TargetedLearningHandoutService(learning, questionBank, submission);
        service.submit(new TargetedHandoutRequest("request-1", "student-a", "函数单调性", 3),
                new RequestSubject("tenant-a", "teacher", "teacher-a", "device-a"));

        TeachingTaskRequest request = submission.request;
        assertThat(request.questionText()).contains("q-bank", "求函数的单调区间");
        assertThat(request.supplementaryRequirements()).contains("教材 RAG 证据", "函数单调性");
        assertThat(request.learningGoal()).contains("student-a");
    }

    private static final class CapturingSubmissionService extends LectureTaskSubmissionService {
        private TeachingTaskRequest request;

        private CapturingSubmissionService() {
            super(null, null);
        }

        @Override
        public com.doob.mathagent.teaching.vo.TeachingTaskResponse submit(
                TeachingTaskRequest request,
                com.doob.mathagent.teaching.TeachingRequestContext context) {
            this.request = request;
            return null;
        }
    }
}
