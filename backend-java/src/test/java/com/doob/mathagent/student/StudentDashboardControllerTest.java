package com.doob.mathagent.student;

import static org.assertj.core.api.Assertions.assertThat;

import com.doob.mathagent.infrastructure.security.RequestSubject;
import com.doob.mathagent.infrastructure.security.RequestSubjectResolver;
import com.doob.mathagent.memory.service.StudentMemoryEntry;
import com.doob.mathagent.memory.service.StudentMemoryStore;
import com.doob.mathagent.student.controller.StudentDashboardController;
import com.doob.mathagent.student.dto.StudentDashboardQuery;
import com.doob.mathagent.student.service.StudentDashboardService;
import com.doob.mathagent.student.service.StudentLearningSnapshotRecord;
import com.doob.mathagent.student.service.StudentLearningSnapshotRefreshService;
import com.doob.mathagent.student.service.StudentLearningSnapshotStore;
import com.doob.mathagent.student.vo.StudentDashboardResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

class StudentDashboardControllerTest {

    @Test
    void exposesLocalStudentDashboardContractWithoutDemoData() {
        StudentDashboardController controller = new StudentDashboardController(
                dashboardService(),
                refreshService(),
                request -> new RequestSubject("school-a", "student", "local-student", "device-1"),
                (token, action, path, requestHash, subject) -> true);

        StudentDashboardResponse response = controller.getDashboard(null, new MockHttpServletRequest());

        assertThat(response.viewerRole()).isEqualTo("student");
        assertThat(response.studentId()).isEqualTo("local-student");
        assertThat(response.knowledgeProgress()).isEmpty();
        assertThat(response.recentQuestions()).isEmpty();
    }

    @Test
    void adminQueryCanSelectStudentId() {
        StudentDashboardController controller = new StudentDashboardController(
                dashboardService(),
                refreshService(),
                request -> new RequestSubject("school-a", "admin", "admin-local", "device-1"),
                (token, action, path, requestHash, subject) -> true);

        StudentDashboardResponse response = controller.getDashboard("student-100", new MockHttpServletRequest());

        assertThat(response.studentId()).isEqualTo("student-100");
        assertThat(response.isAdminView()).isTrue();
    }

    @Test
    void studentQueryCannotSelectAnotherStudentIdOrSpoofAdminHeader() {
        StudentDashboardController controller = new StudentDashboardController(
                dashboardService(),
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
                dashboardService(),
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
                dashboardService(),
                new StudentLearningSnapshotRefreshService(
                        emptyMemoryStore(),
                        emptySnapshotStore(),
                        new ObjectMapper()) {
                    @Override
                    public StudentDashboardResponse refresh(StudentDashboardQuery query) {
                        return new StudentDashboardResponse(
                                query.tenantId(),
                                query.targetStudentId(),
                                query.viewerRole(),
                                query.viewerSubjectId(),
                                query.adminView(),
                                List.of(),
                                List.of(),
                                List.of(),
                                List.of(),
                                List.of(),
                                new StudentDashboardResponse.KnowledgeGraph(List.of(), List.of(), "test"));
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

    private static StudentDashboardService dashboardService() {
        StudentLearningSnapshotStore snapshotStore = emptySnapshotStore();
        ObjectMapper objectMapper = new ObjectMapper();
        return new StudentDashboardService(
                snapshotStore,
                new StudentLearningSnapshotRefreshService(emptyMemoryStore(), snapshotStore, objectMapper),
                objectMapper);
    }

    private static StudentLearningSnapshotRefreshService refreshService() {
        return new StudentLearningSnapshotRefreshService(
                emptyMemoryStore(),
                emptySnapshotStore(),
                new ObjectMapper());
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
