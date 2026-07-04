package com.doob.mathagent.student;

import static org.assertj.core.api.Assertions.assertThat;

import com.doob.mathagent.memory.service.StudentMemoryEntry;
import com.doob.mathagent.memory.service.StudentMemoryStore;
import com.doob.mathagent.student.dto.StudentDashboardQuery;
import com.doob.mathagent.student.service.StudentDashboardService;
import com.doob.mathagent.student.service.StudentLearningSnapshotRecord;
import com.doob.mathagent.student.service.StudentLearningSnapshotRefreshService;
import com.doob.mathagent.student.service.StudentLearningSnapshotStore;
import com.doob.mathagent.student.vo.StudentDashboardResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class StudentDashboardServiceTest {

    @Test
    void dashboardWithoutSnapshotDoesNotInventDemoData() {
        StudentDashboardService service = dashboardService(emptyMemoryStore());
        StudentDashboardQuery query = new StudentDashboardQuery("tenant-a", "student", "student-001", null);

        StudentDashboardResponse response = service.dashboard(query);

        assertThat(response.tenantId()).isEqualTo("tenant-a");
        assertThat(response.studentId()).isEqualTo("student-001");
        assertThat(response.viewerRole()).isEqualTo("student");
        assertThat(response.knowledgeProgress()).isEmpty();
        assertThat(response.weakPoints()).isEmpty();
        assertThat(response.recentQuestions()).isEmpty();
        assertThat(response.scoreTrend()).isEmpty();
        assertThat(response.resourceScopes()).isEmpty();
        assertThat(response.knowledgeGraph().nodes()).isEmpty();
        assertThat(response.knowledgeGraph().generatedFrom())
                .isEqualTo("student_memory_entry:total=0,private=0,public=0,knowledgePoints=0");
    }

    @Test
    void dashboardRefreshesFromRealMemoryEntriesWhenSnapshotMissing() {
        StudentDashboardService service = dashboardService(memoryStore(List.of(
                memory("memory-1", "tenant-a", "student-001", "private", "函数零点", "零点个数题", "answer"),
                memory("memory-2", "tenant-a", "student-001", "private", "函数零点", "参数分类讨论", "answer"))));
        StudentDashboardQuery query = new StudentDashboardQuery("tenant-a", "student", "student-001", null);

        StudentDashboardResponse response = service.dashboard(query);

        assertThat(response.knowledgeProgress())
                .extracting(StudentDashboardResponse.KnowledgeProgress::knowledgePointName)
                .containsExactly("函数零点");
        assertThat(response.knowledgeProgress().getFirst().progressPercent()).isEqualTo(70);
        assertThat(response.recentQuestions())
                .extracting(StudentDashboardResponse.RecentQuestion::sourceType)
                .containsOnly("student_memory");
        assertThat(response.knowledgeGraph().generatedFrom())
                .isEqualTo("student_memory_entry:total=2,private=2,public=0,knowledgePoints=1");
    }

    @Test
    void dashboardUsesPersistedLearningSnapshotWhenAvailable() {
        StudentDashboardService service = dashboardService(persistedSnapshotStore(), emptyMemoryStore());
        StudentDashboardQuery query = new StudentDashboardQuery("tenant-a", "student", "student-001", null);

        StudentDashboardResponse response = service.dashboard(query);

        assertThat(response.knowledgeProgress()).extracting(StudentDashboardResponse.KnowledgeProgress::knowledgePointId)
                .containsExactly("persisted-vector");
        assertThat(response.knowledgeGraph().generatedFrom()).isEqualTo("mysql_snapshot");
        assertThat(response.weakPoints()).extracting(StudentDashboardResponse.WeakPoint::evidenceSummary)
                .containsExactly("latest snapshot");
        assertThat(response.scoreTrend()).extracting(StudentDashboardResponse.ScorePoint::score)
                .containsExactly(132);
    }

    @Test
    void adminCanInspectSpecifiedStudentWithoutChangingViewerIdentity() {
        StudentDashboardService service = dashboardService(emptyMemoryStore());
        StudentDashboardQuery query = new StudentDashboardQuery("tenant-a", "admin", "admin-001", "student-009");

        StudentDashboardResponse response = service.dashboard(query);

        assertThat(response.viewerRole()).isEqualTo("admin");
        assertThat(response.viewerSubjectId()).isEqualTo("admin-001");
        assertThat(response.studentId()).isEqualTo("student-009");
        assertThat(response.isAdminView()).isTrue();
    }

    private static StudentDashboardService dashboardService(StudentMemoryStore memoryStore) {
        return dashboardService(emptySnapshotStore(), memoryStore);
    }

    private static StudentDashboardService dashboardService(
            StudentLearningSnapshotStore snapshotStore,
            StudentMemoryStore memoryStore) {
        ObjectMapper objectMapper = new ObjectMapper();
        StudentLearningSnapshotRefreshService refreshService =
                new StudentLearningSnapshotRefreshService(memoryStore, snapshotStore, objectMapper);
        return new StudentDashboardService(snapshotStore, refreshService, objectMapper);
    }

    private static StudentLearningSnapshotStore persistedSnapshotStore() {
        return new StudentLearningSnapshotStore() {
            @Override
            public Optional<StudentLearningSnapshotRecord> findLatest(String tenantId, String studentId) {
                return Optional.of(new StudentLearningSnapshotRecord(
                        "snapshot-1",
                        tenantId,
                        studentId,
                        "Senior Grade 2",
                        """
                                [{"knowledgePointId":"persisted-vector","knowledgePointName":"persisted vector method","textbookAnchor":"book/page 35","feishuDocUrl":"https://my.feishu.cn/docx/persisted","progressPercent":91}]
                                """,
                        """
                                {"nodes":[{"knowledgePointId":"persisted-vector","knowledgePointName":"persisted vector method","chapterPath":"book/page 35","masteryPercent":91,"riskLevel":"low","evidenceLinks":[{"sourceType":"textbook","title":"book/page 35","url":"/api/textbooks/search?query=persisted-vector","permissionScope":"PUBLIC_TEXTBOOK"}]}],"edges":[],"generatedFrom":"mysql_snapshot"}
                                """,
                        """
                                [{"knowledgePointId":"persisted-vector","knowledgePointName":"persisted vector method","weaknessLevel":1,"evidenceSummary":"latest snapshot"}]
                                """,
                        """
                                [{"recordId":"question-1","sourceType":"exam_paper","questionTitle":"persisted question","knowledgePointName":"persisted vector method","status":"COMPLETED"}]
                                """,
                        """
                                [{"examName":"persisted exam","score":132,"rankInGrade":12,"extractedWeakPointCount":1}]
                                """,
                        """
                                [{"scopeCode":"PUBLIC_TEXTBOOK","scopeName":"Public textbook","accessPolicy":"public"}]
                                """,
                        "mysql_snapshot"));
            }

            @Override
            public StudentLearningSnapshotRecord save(StudentLearningSnapshotRecord record) {
                return record;
            }
        };
    }

    private static StudentLearningSnapshotStore emptySnapshotStore() {
        return new StudentLearningSnapshotStore() {
            @Override
            public Optional<StudentLearningSnapshotRecord> findLatest(String tenantId, String studentId) {
                return Optional.empty();
            }

            @Override
            public StudentLearningSnapshotRecord save(StudentLearningSnapshotRecord record) {
                return record;
            }
        };
    }

    private static StudentMemoryStore emptyMemoryStore() {
        return memoryStore(List.of());
    }

    private static StudentMemoryStore memoryStore(List<StudentMemoryEntry> entries) {
        return new StudentMemoryStore() {
            @Override
            public StudentMemoryEntry save(StudentMemoryEntry entry) {
                return entry;
            }

            @Override
            public List<StudentMemoryEntry> candidates(String tenantId, String studentId) {
                return entries.stream()
                        .filter(entry -> tenantId.equals(entry.tenantId()))
                        .filter(entry -> "public".equals(entry.memoryScope()) || studentId.equals(entry.studentId()))
                        .toList();
            }
        };
    }

    private static StudentMemoryEntry memory(
            String memoryId,
            String tenantId,
            String studentId,
            String scope,
            String knowledgePoint,
            String question,
            String answer) {
        return new StudentMemoryEntry(
                memoryId,
                tenantId,
                studentId,
                scope,
                knowledgePoint,
                question,
                answer,
                "active",
                Instant.parse("2026-01-01T00:00:00Z"));
    }
}
