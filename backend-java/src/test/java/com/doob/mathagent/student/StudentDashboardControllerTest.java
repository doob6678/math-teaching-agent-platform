package com.doob.mathagent.student;

import static org.assertj.core.api.Assertions.assertThat;

import com.doob.mathagent.student.controller.StudentDashboardController;
import com.doob.mathagent.infrastructure.security.RequestSubjectResolver;
import com.doob.mathagent.infrastructure.security.RequestSubject;
import com.doob.mathagent.memory.service.StudentMemoryEntry;
import com.doob.mathagent.memory.service.StudentMemoryStore;
import com.doob.mathagent.student.dto.StudentDashboardQuery;
import com.doob.mathagent.student.service.StudentDashboardService;
import com.doob.mathagent.student.service.StudentLearningSnapshotRefreshService;
import com.doob.mathagent.student.vo.StudentDashboardResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

class StudentDashboardControllerTest {

    @Test
    void exposesLocalStudentDashboardContract() {
        StudentDashboardController controller = new StudentDashboardController(
                new StudentDashboardService(),
                refreshService(),
                RequestSubjectResolver.localDevelopment(),
                (token, action, path, requestHash, subject) -> true);

        StudentDashboardResponse response = controller.getDashboard(null, null);

        assertThat(response.viewerRole()).isEqualTo("student");
        assertThat(response.studentId()).isEqualTo("local-student");
        assertThat(response.knowledgeProgress()).isNotEmpty();
        assertThat(response.recentQuestions()).isNotEmpty();
    }

    @Test
    void adminQueryCanSelectStudentId() {
        StudentDashboardController controller = new StudentDashboardController(
                new StudentDashboardService(),
                refreshService(),
                request -> new RequestSubject("school-a", "admin", "admin-local", "device-1"),
                (token, action, path, requestHash, subject) -> true);
        MockHttpServletRequest request = new MockHttpServletRequest();

        StudentDashboardResponse response = controller.getDashboard("student-100", request);

        assertThat(response.studentId()).isEqualTo("student-100");
        assertThat(response.isAdminView()).isTrue();
    }

    @Test
    void studentQueryCannotSelectAnotherStudentIdOrSpoofAdminHeader() {
        StudentDashboardController controller = new StudentDashboardController(
                new StudentDashboardService(),
                refreshService(),
                request -> new RequestSubject("school-a", "student", "student-real", "device-1"),
                (token, action, path, requestHash, subject) -> true);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Subject-Type", "admin");
        request.addHeader("X-Subject-Id", "student-spoofed");

        StudentDashboardResponse response = controller.getDashboard("student-victim", request);

        assertThat(response.studentId()).isEqualTo("student-real");
        assertThat(response.viewerRole()).isEqualTo("student");
        assertThat(response.viewerSubjectId()).isEqualTo("student-real");
        assertThat(response.isAdminView()).isFalse();
    }

    @Test
    void refreshRequiresCapabilityToken() {
        StudentDashboardController controller = new StudentDashboardController(
                new StudentDashboardService(),
                refreshService(),
                request -> new RequestSubject("school-a", "student", "student-real", "device-1"),
                (token, action, path, requestHash, subject) -> false);

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        controller.refreshDashboard(null, new MockHttpServletRequest()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("403 FORBIDDEN");
    }

    @Test
    void refreshUsesBackendResolvedStudentInsteadOfSpoofedRequestParameter() {
        StudentDashboardController controller = new StudentDashboardController(
                new StudentDashboardService(),
                new StudentLearningSnapshotRefreshService(
                        noOpMemoryStore(),
                        new com.doob.mathagent.student.service.EmptyStudentLearningSnapshotStore(),
                        new com.fasterxml.jackson.databind.ObjectMapper()) {
                    @Override
                    public StudentDashboardResponse refresh(StudentDashboardQuery query) {
                        return new StudentDashboardResponse(
                                query.tenantId(),
                                query.targetStudentId(),
                                query.viewerRole(),
                                query.viewerSubjectId(),
                                query.adminView(),
                                java.util.List.of(),
                                java.util.List.of(),
                                java.util.List.of(),
                                java.util.List.of(),
                                java.util.List.of(),
                                new StudentDashboardResponse.KnowledgeGraph(
                                        java.util.List.of(),
                                        java.util.List.of(),
                                        "test"));
                    }
                },
                request -> new RequestSubject("school-a", "student", "student-real", "device-1"),
                (token, action, path, requestHash, subject) ->
                        "student-dashboard:refresh".equals(action)
                                && "/api/students/dashboard/refresh".equals(path)
                                && "hash-refresh".equals(requestHash)
                                && "token-ok".equals(token));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Capability-Token", "token-ok");
        request.addHeader("X-Request-Hash", "hash-refresh");

        StudentDashboardResponse response = controller.refreshDashboard("student-victim", request);

        assertThat(response.studentId()).isEqualTo("student-real");
        assertThat(response.isAdminView()).isFalse();
    }

    /**
     * Returns a lightweight refresh service for controller tests that do not exercise aggregation.
     */
    private static StudentLearningSnapshotRefreshService refreshService() {
        return new StudentLearningSnapshotRefreshService(
                noOpMemoryStore(),
                new com.doob.mathagent.student.service.EmptyStudentLearningSnapshotStore(),
                new com.fasterxml.jackson.databind.ObjectMapper());
    }

    /**
     * Returns an empty memory store for controller-only tests.
     */
    private static StudentMemoryStore noOpMemoryStore() {
        return new StudentMemoryStore() {
            @Override
            public StudentMemoryEntry save(StudentMemoryEntry entry) {
                return entry;
            }

            @Override
            public List<StudentMemoryEntry> candidates(String tenantId, String studentId) {
                return List.of();
            }
        };
    }
}
