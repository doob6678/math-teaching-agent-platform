package com.doob.mathagent.student.controller;

import com.doob.mathagent.infrastructure.security.RequestSubject;
import com.doob.mathagent.infrastructure.security.RequestSubjectResolver;
import com.doob.mathagent.student.dto.StudentDashboardQuery;
import com.doob.mathagent.student.service.StudentDashboardService;
import com.doob.mathagent.student.service.StudentLearningSnapshotCapabilityVerifier;
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

    private static final String REFRESH_ACTION = "student-dashboard:refresh";
    private static final String REFRESH_PATH = "/api/students/dashboard/refresh";

    private final StudentDashboardService dashboardService;
    private final StudentLearningSnapshotRefreshService refreshService;
    private final RequestSubjectResolver subjectResolver;
    private final StudentLearningSnapshotCapabilityVerifier capabilityVerifier;

    /**
     * Injects the student dashboard service.
     *
     * @param dashboardService dashboard service
     * @param refreshService snapshot refresh service
     * @param subjectResolver backend subject resolver
     * @param capabilityVerifier high-value refresh verifier
     */
    public StudentDashboardController(
            StudentDashboardService dashboardService,
            StudentLearningSnapshotRefreshService refreshService,
            RequestSubjectResolver subjectResolver,
            StudentLearningSnapshotCapabilityVerifier capabilityVerifier) {
        this.dashboardService = dashboardService;
        this.refreshService = refreshService;
        this.subjectResolver = subjectResolver;
        this.capabilityVerifier = capabilityVerifier;
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
     * @param httpRequest HTTP request containing tenant, subject, and capability headers
     * @return refreshed student dashboard response
     */
    @PostMapping("/api/students/dashboard/refresh")
    public StudentDashboardResponse refreshDashboard(
            @RequestParam(required = false) String studentId,
            HttpServletRequest httpRequest) {
        RequestSubject subject = subjectResolver.resolve(httpRequest);
        if (!capabilityVerifier.verify(
                headerOrNull(httpRequest, "X-Capability-Token"),
                REFRESH_ACTION,
                REFRESH_PATH,
                headerOrNull(httpRequest, "X-Request-Hash"),
                subject)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Capability token required for dashboard refresh");
        }
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

    /**
     * Reads a non-authoritative request header used for capability token verification.
     *
     * @param request HTTP request
     * @param name header name
     * @return stripped header value or null
     */
    private static String headerOrNull(HttpServletRequest request, String name) {
        if (request == null) {
            return null;
        }
        String value = request.getHeader(name);
        return value == null || value.isBlank() ? null : value.strip();
    }
}
