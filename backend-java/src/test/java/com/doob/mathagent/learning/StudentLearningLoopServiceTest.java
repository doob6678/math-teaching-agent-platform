package com.doob.mathagent.learning;

import static org.assertj.core.api.Assertions.assertThat;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import com.doob.mathagent.knowledge.service.KnowledgeQuestionBankService;
import com.doob.mathagent.knowledge.service.InMemoryKnowledgeQuestionBankStore;
import com.doob.mathagent.knowledge.service.QuestionBankItemRecord;
import org.junit.jupiter.api.Test;

/** Verifies the real scoring rule and tenant/student isolation without replacing the domain with a canned response. */
class StudentLearningLoopServiceTest {
    @Test
    void recomputesMasteryFromAnswerFactsAndExposesWeakPoint() {
        InMemoryStudentLearningLoopStore store = new InMemoryStudentLearningLoopStore();
        StudentLearningLoopService service = new StudentLearningLoopService(store,
                new KnowledgeQuestionBankService(new InMemoryKnowledgeQuestionBankStore()),
                Clock.fixed(Instant.parse("2026-07-23T00:00:00Z"), ZoneOffset.UTC));

        StudentLearningLoopService.AttemptResult first = service.recordAttempt("tenant-a", "student-a", "q-1", "题目",
                List.of("函数单调性"), false, 1200);
        assertThat(first.updatedMastery()).singleElement().satisfies(value -> {
            assertThat(value.masteryPercent()).isEqualTo(33);
            assertThat(value.weaknessLevel()).isEqualTo(5);
            assertThat(value.incorrectCount()).isEqualTo(1);
        });

        StudentLearningLoopService.AttemptResult second = service.recordAttempt("tenant-a", "student-a", "q-2", "题目2",
                List.of("函数单调性"), true, 800);
        assertThat(second.updatedMastery()).singleElement().satisfies(value -> {
            assertThat(value.masteryPercent()).isEqualTo(50);
            assertThat(value.attemptCount()).isEqualTo(2);
            assertThat(value.correctCount()).isEqualTo(1);
        });
        assertThat(service.mastery("tenant-a", "student-b")).isEmpty();
    }

    @Test
    void resolvesMissingKnowledgePointOnlyFromQuestionBankTags() {
        InMemoryStudentLearningLoopStore store = new InMemoryStudentLearningLoopStore();
        InMemoryKnowledgeQuestionBankStore questionStore = new InMemoryKnowledgeQuestionBankStore();
        questionStore.saveQuestion(new QuestionBankItemRecord(
                "q-source", "tenant-a", "teacher-a", "PUBLIC_TEXTBOOK", "单调性", "函数单调性题",
                "{}", "medium", "active", List.of("函数单调性")));
        KnowledgeQuestionBankService questionBank = new KnowledgeQuestionBankService(questionStore);
        StudentLearningLoopService service = new StudentLearningLoopService(store, questionBank,
                Clock.fixed(Instant.parse("2026-07-23T00:00:00Z"), ZoneOffset.UTC));

        StudentLearningLoopService.AttemptResult result = service.recordAttempt(
                "tenant-a", "student-a", "student", "q-1", "函数单调性题", List.of(), false, 1200);

        assertThat(result.attempt().knowledgePointIds()).containsExactly("函数单调性");
        assertThat(result.updatedMastery()).singleElement().extracting(StudentKnowledgeMastery::knowledgePointId)
                .isEqualTo("函数单调性");
    }
}
