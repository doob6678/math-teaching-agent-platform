package com.doob.mathagent.vector.controller;

import com.doob.mathagent.infrastructure.security.RequestSubject;
import com.doob.mathagent.infrastructure.security.RequestSubjectResolver;
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

    private final VectorIndexService service;
    private final RequestSubjectResolver subjectResolver;

    public VectorIndexController(
            VectorIndexService service,
            RequestSubjectResolver subjectResolver) {
        this.service = service;
        this.subjectResolver = subjectResolver;
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

}
