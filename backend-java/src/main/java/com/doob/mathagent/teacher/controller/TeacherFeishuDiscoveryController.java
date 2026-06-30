package com.doob.mathagent.teacher.controller;

import com.doob.mathagent.infrastructure.security.RequestSubject;
import com.doob.mathagent.infrastructure.security.RequestSubjectResolver;
import com.doob.mathagent.teacher.service.TeacherFeishuDiscoveryService;
import com.doob.mathagent.teacher.vo.TeacherFeishuDiscoveryResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Teacher Feishu discovery API for listing/searching remote resources before registration or download.
 */
@RestController
public class TeacherFeishuDiscoveryController {

    private final TeacherFeishuDiscoveryService discoveryService;
    private final RequestSubjectResolver subjectResolver;

    /**
     * Creates a Feishu discovery controller.
     *
     * @param discoveryService discovery service
     * @param subjectResolver backend subject resolver
     */
    public TeacherFeishuDiscoveryController(
            TeacherFeishuDiscoveryService discoveryService,
            RequestSubjectResolver subjectResolver) {
        this.discoveryService = discoveryService;
        this.subjectResolver = subjectResolver;
    }

    /**
     * Lists or searches Feishu candidates using only backend-resolved subject identity.
     *
     * @param mode discovery mode, list or search
     * @param query search keyword
     * @param rootUrl root Feishu folder URL
     * @param listDepth list traversal depth
     * @param maxDepth search traversal depth
     * @param httpRequest HTTP request carrying the backend session
     * @return Feishu discovery response
     */
    @GetMapping("/api/teacher/resources/feishu/discovery")
    public TeacherFeishuDiscoveryResponse discover(
            @RequestParam(defaultValue = "list") String mode,
            @RequestParam(defaultValue = "") String query,
            @RequestParam(defaultValue = "") String rootUrl,
            @RequestParam(defaultValue = "1") int listDepth,
            @RequestParam(defaultValue = "5") int maxDepth,
            HttpServletRequest httpRequest) {
        RequestSubject subject = subjectResolver.resolve(httpRequest).normalize();
        try {
            return discoveryService.discover(
                    subject.tenantId(),
                    subject.subjectType(),
                    subject.subjectId(),
                    mode,
                    query,
                    rootUrl,
                    listDepth,
                    maxDepth);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, exception.getMessage(), exception);
        }
    }
}
