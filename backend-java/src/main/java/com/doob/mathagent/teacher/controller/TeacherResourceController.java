package com.doob.mathagent.teacher.controller;

import com.doob.mathagent.teacher.dto.TeacherResourceRegistrationRequest;
import com.doob.mathagent.teacher.service.TeacherResourceService;
import com.doob.mathagent.teacher.vo.TeacherResourceDocumentResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Teacher resource management API for Feishu, local folders, and teacher-owned question banks.
 */
@RestController
public class TeacherResourceController {

    private final TeacherResourceService teacherResourceService;

    /**
     * Creates a teacher resource controller.
     *
     * @param teacherResourceService teacher resource service
     */
    public TeacherResourceController(TeacherResourceService teacherResourceService) {
        this.teacherResourceService = teacherResourceService;
    }

    /**
     * Registers a teacher/admin resource source and returns preview/index status.
     *
     * @param request registration request body
     * @param httpRequest HTTP request containing tenant and subject headers
     * @return registered resource document
     */
    @PostMapping("/api/teacher/resources")
    public TeacherResourceDocumentResponse register(
            @RequestBody TeacherResourceRegistrationRequest request,
            HttpServletRequest httpRequest) {
        return teacherResourceService.register(enrich(request, httpRequest));
    }

    /**
     * Lists active resource sources visible to the current teacher/admin.
     *
     * @param httpRequest HTTP request containing tenant and subject headers
     * @return visible resource documents
     */
    @GetMapping("/api/teacher/resources")
    public List<TeacherResourceDocumentResponse> list(HttpServletRequest httpRequest) {
        return teacherResourceService.list(
                headerOrDefault(httpRequest, "X-Tenant-Id", "default"),
                headerOrDefault(httpRequest, "X-Subject-Type", "teacher"),
                headerOrDefault(httpRequest, "X-Subject-Id", "local-teacher-console"));
    }

    /**
     * Archives a resource source instead of hard-deleting it so old RAG citations remain traceable.
     *
     * @param documentId resource document id
     * @param httpRequest HTTP request containing tenant and subject headers
     * @return archived resource document
     */
    @DeleteMapping("/api/teacher/resources/{documentId}")
    public TeacherResourceDocumentResponse archive(
            @PathVariable String documentId,
            HttpServletRequest httpRequest) {
        return teacherResourceService.archive(
                headerOrDefault(httpRequest, "X-Tenant-Id", "default"),
                headerOrDefault(httpRequest, "X-Subject-Type", "teacher"),
                headerOrDefault(httpRequest, "X-Subject-Id", "local-teacher-console"),
                documentId);
    }

    /**
     * Merges request body fields with authenticated request headers.
     *
     * @param request registration body
     * @param httpRequest HTTP request
     * @return enriched registration request
     */
    private static TeacherResourceRegistrationRequest enrich(
            TeacherResourceRegistrationRequest request,
            HttpServletRequest httpRequest) {
        return new TeacherResourceRegistrationRequest(
                headerOrDefault(httpRequest, "X-Tenant-Id", request.tenantId()),
                headerOrDefault(httpRequest, "X-Subject-Type", request.viewerRole()),
                headerOrDefault(httpRequest, "X-Subject-Id", request.viewerSubjectId()),
                request.sourceType(),
                request.title(),
                request.originalUrl(),
                request.localPath(),
                request.permissionScope());
    }

    /**
     * Reads a request header and returns a fallback when blank.
     *
     * @param request HTTP request
     * @param name header name
     * @param defaultValue fallback value
     * @return header value or fallback
     */
    private static String headerOrDefault(HttpServletRequest request, String name, String defaultValue) {
        if (request == null) {
            return defaultValue;
        }
        String value = request.getHeader(name);
        return value == null || value.isBlank() ? defaultValue : value.strip();
    }
}
