package com.doob.mathagent.student;

import static org.assertj.core.api.Assertions.assertThat;

import com.doob.mathagent.student.service.StudentLearningSnapshotStore;
import com.doob.mathagent.student.service.StudentLearningSnapshotRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.doob.mathagent.student.dto.StudentDashboardQuery;
import com.doob.mathagent.student.service.StudentDashboardService;
import com.doob.mathagent.student.vo.StudentDashboardResponse;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class StudentDashboardServiceTest {

    @Test
    void studentSeesOwnProgressWeaknessesHistoryScoresAndResourceScopes() {
        StudentDashboardService service = new StudentDashboardService();
        StudentDashboardQuery query = new StudentDashboardQuery(
                "tenant-a",
                "student",
                "student-001",
                null);

        StudentDashboardResponse response = service.dashboard(query);

        assertThat(response.tenantId()).isEqualTo("tenant-a");
        assertThat(response.studentId()).isEqualTo("student-001");
        assertThat(response.viewerRole()).isEqualTo("student");
        assertThat(response.knowledgeProgress()).isNotEmpty();
        assertThat(response.knowledgeProgress().getFirst().progressPercent()).isBetween(0, 100);
        assertThat(response.weakPoints()).extracting(StudentDashboardResponse.WeakPoint::knowledgePointName)
                .contains("空间向量数量积");
        assertThat(response.recentQuestions()).extracting(StudentDashboardResponse.RecentQuestion::sourceType)
                .contains("teaching_task");
        assertThat(response.scoreTrend()).extracting(StudentDashboardResponse.ScorePoint::examName)
                .contains("最近一次周测");
        assertThat(response.resourceScopes()).extracting(StudentDashboardResponse.ResourceScope::scopeCode)
                .contains("PUBLIC_TEXTBOOK", "MATH_VIP");
    }

    @Test
    void dashboardContainsKnowledgeGraphNodesEdgesEvidenceAndMastery() {
        StudentDashboardService service = new StudentDashboardService();
        StudentDashboardQuery query = new StudentDashboardQuery(
                "tenant-a",
                "student",
                "student-001",
                null);

        StudentDashboardResponse response = service.dashboard(query);

        assertThat(response.knowledgeGraph()).isNotNull();
        assertThat(response.knowledgeGraph().nodes())
                .extracting(StudentDashboardResponse.KnowledgeGraphNode::knowledgePointId)
                .contains("math-vector-dot-product", "math-solid-geometry");
        assertThat(response.knowledgeGraph().edges())
                .anySatisfy(edge -> {
                    assertThat(edge.sourceKnowledgePointId()).isEqualTo("math-vector-dot-product");
                    assertThat(edge.targetKnowledgePointId()).isEqualTo("math-solid-geometry");
                    assertThat(edge.relationType()).isEqualTo("PREREQUISITE_FOR");
                });
        assertThat(response.knowledgeGraph().nodes())
                .anySatisfy(node -> {
                    assertThat(node.knowledgePointId()).isEqualTo("math-vector-dot-product");
                    assertThat(node.masteryPercent()).isEqualTo(68);
                    assertThat(node.evidenceLinks())
                            .extracting(StudentDashboardResponse.KnowledgeEvidenceLink::sourceType)
                            .contains("textbook", "feishu");
        });
    }

    @Test
    void dashboardUsesPersistedLearningSnapshotWhenAvailable() {
        StudentDashboardService service = new StudentDashboardService(
                persistedSnapshotStore(),
                new ObjectMapper());
        StudentDashboardQuery query = new StudentDashboardQuery(
                "tenant-a",
                "student",
                "student-001",
                null);

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
    void adminCanInspectSpecifiedStudentWithoutChangingStudentOwnership() {
        StudentDashboardService service = new StudentDashboardService();
        StudentDashboardQuery query = new StudentDashboardQuery(
                "tenant-a",
                "admin",
                "admin-001",
                "student-009");

        StudentDashboardResponse response = service.dashboard(query);

        assertThat(response.viewerRole()).isEqualTo("admin");
        assertThat(response.viewerSubjectId()).isEqualTo("admin-001");
        assertThat(response.studentId()).isEqualTo("student-009");
        assertThat(response.isAdminView()).isTrue();
    }

    /**
     * Returns a store with one persisted dashboard snapshot for read-path tests.
     */
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
}
