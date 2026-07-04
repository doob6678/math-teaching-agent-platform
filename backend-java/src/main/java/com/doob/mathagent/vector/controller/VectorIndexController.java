package com.doob.mathagent.vector.controller;

import com.doob.mathagent.infrastructure.security.RequestSubject;
import com.doob.mathagent.infrastructure.security.RequestSubjectResolver;
import com.doob.mathagent.teacher.service.TeacherResourceCapabilityVerifier;
import com.doob.mathagent.vector.service.VectorIndexRebuildResponse;
import com.doob.mathagent.vector.service.VectorIndexService;
import com.doob.mathagent.vector.service.VectorIndexStatusResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Vector index status and rebuild API.
 */
@RestController
public class VectorIndexController {

    private static final String REBUILD_ACTION = "vector-index:rebuild";
    private static final String TEACHER_RESOURCE_INDEX_PATH = "/api/vector-index/teacher-resources";

    private final VectorIndexService service;
    private final RequestSubjectResolver subjectResolver;
    private final TeacherResourceCapabilityVerifier capabilityVerifier;

    public VectorIndexController(
            VectorIndexService service,
            RequestSubjectResolver subjectResolver,
            TeacherResourceCapabilityVerifier capabilityVerifier) {
        this.service = service;
        this.subjectResolver = subjectResolver;
        this.capabilityVerifier = capabilityVerifier;
    }

    @GetMapping("/api/vector-index/status")
    public VectorIndexStatusResponse status() {
        return service.status();
    }

    @PostMapping("/api/vector-index/teacher-resources/{documentId}/rebuild")
    public VectorIndexRebuildResponse rebuildTeacherResource(
            @PathVariable String documentId,
            HttpServletRequest request) {
        RequestSubject subject = subjectResolver.resolve(request).normalize();
        String path = TEACHER_RESOURCE_INDEX_PATH + "/" + documentId + "/rebuild";
        if (!capabilityVerifier.verify(
                headerOrNull(request, "X-Capability-Token"),
                REBUILD_ACTION,
                path,
                headerOrNull(request, "X-Request-Hash"),
                subject)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Capability token required for vector index rebuild");
        }
        try {
            return service.rebuildTeacherResource(
                    subject.tenantId(),
                    subject.subjectType(),
                    subject.subjectId(),
                    documentId);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }
    }

    private static String headerOrNull(HttpServletRequest request, String name) {
        String value = request.getHeader(name);
        return value == null || value.isBlank() ? null : value;
    }
}
