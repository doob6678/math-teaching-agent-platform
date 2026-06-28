package com.doob.mathagent.student.controller;

import com.doob.mathagent.student.dto.StudentDashboardQuery;
import com.doob.mathagent.student.service.StudentDashboardService;
import com.doob.mathagent.student.vo.StudentDashboardResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Student dashboard API for learning progress, historical questions, weak points, and score trend.
 */
@RestController
public class StudentDashboardController {

    private final StudentDashboardService dashboardService;

    /**
     * Injects the student dashboard service.
     *
     * @param dashboardService dashboard service
     */
    public StudentDashboardController(StudentDashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    /**
     * Returns a student learning dashboard. Students see their own data; teachers/admins may pass studentId.
     *
     * @param studentId optional requested student id for admin or teacher views
     * @param httpRequest HTTP request containing tenant and subject headers
     * @return student dashboard response
     */
    @GetMapping("/api/students/dashboard")
    public StudentDashboardResponse getDashboard(
            @RequestParam(required = false) String studentId,
            HttpServletRequest httpRequest) {
        return dashboardService.dashboard(query(studentId, httpRequest));
    }

    /**
     * Builds a query from request headers.
     *
     * @param studentId optional requested student id
     * @param httpRequest HTTP request
     * @return normalized dashboard query source
     */
    private static StudentDashboardQuery query(String studentId, HttpServletRequest httpRequest) {
        if (httpRequest == null) {
            return new StudentDashboardQuery("default", "student", "local-student", studentId);
        }
        return new StudentDashboardQuery(
                headerOrDefault(httpRequest, "X-Tenant-Id", "default"),
                headerOrDefault(httpRequest, "X-Subject-Type", "student"),
                headerOrDefault(httpRequest, "X-Subject-Id", "local-student"),
                studentId);
    }

    /**
     * Reads a header and falls back to a default when blank.
     *
     * @param request HTTP request
     * @param name header name
     * @param defaultValue default value
     * @return header value or default
     */
    private static String headerOrDefault(HttpServletRequest request, String name, String defaultValue) {
        String value = request.getHeader(name);
        return value == null || value.isBlank() ? defaultValue : value.strip();
    }
}
