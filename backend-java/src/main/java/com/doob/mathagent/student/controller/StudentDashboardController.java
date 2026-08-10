package com.doob.mathagent.student.controller;

import com.doob.mathagent.infrastructure.security.RequestSubject;
import com.doob.mathagent.infrastructure.security.RequestSubjectResolver;
import com.doob.mathagent.student.dto.StudentDashboardQuery;
import com.doob.mathagent.student.service.StudentDashboardService;
import com.doob.mathagent.student.service.StudentLearningSnapshotRefreshService;
import com.doob.mathagent.student.vo.StudentDashboardResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Student dashboard API for learning progress, historical questions, weak points, and score trend.
 */
@RestController
public class StudentDashboardController {

    private final StudentDashboardService dashboardService;
    private final StudentLearningSnapshotRefreshService refreshService;
    private final RequestSubjectResolver subjectResolver;

    /**
     * Injects the student dashboard service.
     *
     * @param dashboardService dashboard service
     * @param refreshService snapshot refresh service
     * @param subjectResolver backend subject resolver
     */
    public StudentDashboardController(
            StudentDashboardService dashboardService,
            StudentLearningSnapshotRefreshService refreshService,
            RequestSubjectResolver subjectResolver) {
        this.dashboardService = dashboardService;
        this.refreshService = refreshService;
        this.subjectResolver = subjectResolver;
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
        try {
            return dashboardService.dashboard(query(studentId, subjectResolver.resolve(httpRequest)));
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
    }

    /**
     * Refreshes and persists a dashboard snapshot from backend-owned learning signals.
     *
     * @param studentId optional requested student id for admin or teacher views
     * @param httpRequest HTTP request containing tenant and subject session
     * @return refreshed student dashboard response
     */
    @PostMapping("/api/students/dashboard/refresh")
    public StudentDashboardResponse refreshDashboard(
            @RequestParam(required = false) String studentId,
            HttpServletRequest httpRequest) {
        RequestSubject subject = subjectResolver.resolve(httpRequest);
        try {
            return refreshService.refresh(query(studentId, subject));
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
    }

    /**
     * Builds a query from request headers.
     *
     * @param studentId optional requested student id
     * @param httpRequest HTTP request
     * @return normalized dashboard query source
     */
    private static StudentDashboardQuery query(String studentId, RequestSubject subject) {
        RequestSubject normalized = subject.normalize();
        return new StudentDashboardQuery(
                normalized.tenantId(),
                normalized.subjectType(),
                normalized.subjectId(),
                studentId);
    }

}
