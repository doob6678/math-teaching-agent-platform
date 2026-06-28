package com.doob.mathagent.student;

import static org.assertj.core.api.Assertions.assertThat;

import com.doob.mathagent.student.controller.StudentDashboardController;
import com.doob.mathagent.infrastructure.security.RequestSubjectResolver;
import com.doob.mathagent.infrastructure.security.RequestSubject;
import com.doob.mathagent.student.service.StudentDashboardService;
import com.doob.mathagent.student.vo.StudentDashboardResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class StudentDashboardControllerTest {

    @Test
    void exposesLocalStudentDashboardContract() {
        StudentDashboardController controller = new StudentDashboardController(
                new StudentDashboardService(),
                RequestSubjectResolver.localDevelopment());

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
                request -> new RequestSubject("school-a", "admin", "admin-local", "device-1"));
        MockHttpServletRequest request = new MockHttpServletRequest();

        StudentDashboardResponse response = controller.getDashboard("student-100", request);

        assertThat(response.studentId()).isEqualTo("student-100");
        assertThat(response.isAdminView()).isTrue();
    }

    @Test
    void studentQueryCannotSelectAnotherStudentIdOrSpoofAdminHeader() {
        StudentDashboardController controller = new StudentDashboardController(
                new StudentDashboardService(),
                request -> new RequestSubject("school-a", "student", "student-real", "device-1"));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Subject-Type", "admin");
        request.addHeader("X-Subject-Id", "student-spoofed");

        StudentDashboardResponse response = controller.getDashboard("student-victim", request);

        assertThat(response.studentId()).isEqualTo("student-real");
        assertThat(response.viewerRole()).isEqualTo("student");
        assertThat(response.viewerSubjectId()).isEqualTo("student-real");
        assertThat(response.isAdminView()).isFalse();
    }
}
