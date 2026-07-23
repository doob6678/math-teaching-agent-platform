package com.doob.mathagent.learning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.doob.mathagent.infrastructure.security.RequestSubject;
import com.doob.mathagent.knowledge.service.InMemoryKnowledgeQuestionBankStore;
import com.doob.mathagent.knowledge.service.KnowledgePointRecord;
import com.doob.mathagent.knowledge.service.KnowledgeQuestionBankService;
import com.doob.mathagent.knowledge.service.KnowledgeRelationRecord;
import com.doob.mathagent.learning.service.StudentLearningPathService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Verifies that a student's path is derived from stored mastery and visible graph facts only. */
class StudentLearningPathServiceTest {
    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2026-07-23T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void putsVisiblePrerequisitesBeforeWeakPointAndPreservesMasteryFacts() {
        InMemoryStudentLearningLoopStore learningStore = new InMemoryStudentLearningLoopStore();
        InMemoryKnowledgeQuestionBankStore knowledgeStore = new InMemoryKnowledgeQuestionBankStore();
        seedVisibleGraph(knowledgeStore, false);
        StudentLearningLoopService learning = new StudentLearningLoopService(
                learningStore, new KnowledgeQuestionBankService(knowledgeStore), TEST_CLOCK);
        learning.recordAttempt("tenant-a", "student-a", "student", "q-1", "代数题", List.of("target"), false, 1000);

        StudentLearningPathService pathService = new StudentLearningPathService(
                learning, new KnowledgeQuestionBankService(knowledgeStore));

        var response = pathService.build(new RequestSubject("tenant-a", "student", "student-a", "device-a"));

        assertThat(response.studentId()).isEqualTo("student-a");
        assertThat(response.generatedFrom()).contains("PREREQUISITE_FOR");
        assertThat(response.steps()).extracting(step -> step.knowledgePointId())
                .containsExactly("base", "target");
        assertThat(response.steps().get(0).masteryPercent()).isEqualTo(100);
        assertThat(response.steps().get(0).weaknessLevel()).isZero();
        assertThat(response.steps().get(1).masteryPercent()).isEqualTo(33);
        assertThat(response.steps().get(1).weaknessLevel()).isEqualTo(5);
        assertThat(response.steps().get(0).relationToNext()).isEqualTo("PREREQUISITE_FOR");
        assertThat(response.steps().get(1).recommendation()).contains("教材证据");
    }

    @Test
    void terminatesWhenVisiblePrerequisiteGraphContainsACycle() {
        InMemoryStudentLearningLoopStore learningStore = new InMemoryStudentLearningLoopStore();
        InMemoryKnowledgeQuestionBankStore knowledgeStore = new InMemoryKnowledgeQuestionBankStore();
        seedVisibleGraph(knowledgeStore, true);
        StudentLearningLoopService learning = new StudentLearningLoopService(
                learningStore, new KnowledgeQuestionBankService(knowledgeStore), TEST_CLOCK);
        learning.recordAttempt("tenant-a", "student-a", "student", "q-1", "代数题", List.of("target"), false, 1000);

        var response = new StudentLearningPathService(
                learning, new KnowledgeQuestionBankService(knowledgeStore))
                .build(new RequestSubject("tenant-a", "student", "student-a", "device-a"));

        assertThat(response.steps()).extracting(step -> step.knowledgePointId())
                .containsExactlyInAnyOrder("base", "target");
        assertThat(response.steps()).hasSize(2);
    }

    @Test
    void keepsKnowledgePointWritesTeacherOnlyWhileAllowingStudentGraphReads() {
        KnowledgeQuestionBankService service = new KnowledgeQuestionBankService(new InMemoryKnowledgeQuestionBankStore());

        assertThatThrownBy(() -> service.createKnowledgePoint(
                "tenant-a", "student", "student-a",
                new com.doob.mathagent.knowledge.dto.KnowledgePointCreateRequest(
                        "伪造知识点", "章节", "PUBLIC_TEXTBOOK", "student input")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("teacher or admin");
        assertThat(service.listKnowledgePoints("tenant-a", "student", "student-a")).isEmpty();
    }

    private static void seedVisibleGraph(InMemoryKnowledgeQuestionBankStore store, boolean cycle) {
        store.saveKnowledgePoint(new KnowledgePointRecord(
                "base", "tenant-a", "teacher-a", "PUBLIC_TEXTBOOK", "基础代数", "高中数学", "active", "教材"));
        store.saveKnowledgePoint(new KnowledgePointRecord(
                "target", "tenant-a", "teacher-a", "PUBLIC_TEXTBOOK", "目标代数", "高中数学", "active", "教材"));
        store.saveKnowledgeRelation(new KnowledgeRelationRecord(
                "r-base-target", "tenant-a", "base", "target", "PREREQUISITE_FOR", "教材章节顺序", "active"));
        if (cycle) {
            store.saveKnowledgeRelation(new KnowledgeRelationRecord(
                    "r-target-base", "tenant-a", "target", "base", "PREREQUISITE_FOR", "历史数据环", "active"));
        }
    }
}
