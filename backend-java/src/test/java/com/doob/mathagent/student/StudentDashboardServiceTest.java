package com.doob.mathagent.student;

import static org.assertj.core.api.Assertions.assertThat;

import com.doob.mathagent.student.dto.StudentDashboardQuery;
import com.doob.mathagent.student.service.StudentDashboardService;
import com.doob.mathagent.student.vo.StudentDashboardResponse;
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
}
