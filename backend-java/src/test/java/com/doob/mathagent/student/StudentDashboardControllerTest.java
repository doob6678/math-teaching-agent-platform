package com.doob.mathagent.student;

import static org.assertj.core.api.Assertions.assertThat;

import com.doob.mathagent.student.controller.StudentDashboardController;
import com.doob.mathagent.student.service.StudentDashboardService;
import com.doob.mathagent.student.vo.StudentDashboardResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class StudentDashboardControllerTest {

    @Test
    void exposesLocalStudentDashboardContract() {
        StudentDashboardController controller = new StudentDashboardController(new StudentDashboardService());

        StudentDashboardResponse response = controller.getDashboard(null, null);

        assertThat(response.viewerRole()).isEqualTo("student");
        assertThat(response.studentId()).isEqualTo("local-student");
        assertThat(response.knowledgeProgress()).isNotEmpty();
        assertThat(response.recentQuestions()).isNotEmpty();
    }

    @Test
    void adminQueryCanSelectStudentId() {
        StudentDashboardController controller = new StudentDashboardController(new StudentDashboardService());
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Subject-Type", "admin");
        request.addHeader("X-Subject-Id", "admin-local");

        StudentDashboardResponse response = controller.getDashboard("student-100", request);

        assertThat(response.studentId()).isEqualTo("student-100");
        assertThat(response.isAdminView()).isTrue();
    }
}
