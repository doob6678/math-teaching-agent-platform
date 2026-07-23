package com.doob.mathagent.learning.controller;

import com.doob.mathagent.infrastructure.security.RequestSubject;
import com.doob.mathagent.infrastructure.security.RequestSubjectResolver;
import com.doob.mathagent.learning.dto.TargetedHandoutRequest;
import com.doob.mathagent.learning.service.TargetedLearningHandoutService;
import com.doob.mathagent.teaching.service.TeachingCapabilityVerifier;
import com.doob.mathagent.teaching.vo.TeachingTaskResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Teacher/admin entry point for creating a real, weak-point-driven asynchronous handout task. */
@RestController
public class TargetedLearningHandoutController {
    private static final String ACTION = "teaching:targeted-handout";
    private static final String PATH = "/api/teachers/learning/handout";

    private final TargetedLearningHandoutService handoutService;
    private final RequestSubjectResolver subjectResolver;
    private final TeachingCapabilityVerifier capabilityVerifier;

    public TargetedLearningHandoutController(
            TargetedLearningHandoutService handoutService,
            RequestSubjectResolver subjectResolver,
            TeachingCapabilityVerifier capabilityVerifier) {
        this.handoutService = handoutService;
        this.subjectResolver = subjectResolver;
        this.capabilityVerifier = capabilityVerifier;
    }

    /** Submits only a durable task snapshot; the existing outbox/worker chain performs model generation later. */
    @PostMapping(PATH)
    public TeachingTaskResponse submit(
            @Valid @RequestBody TargetedHandoutRequest request,
            HttpServletRequest httpRequest) {
        RequestSubject subject = subjectResolver.resolve(httpRequest).normalize();
        if (!capabilityVerifier.verify(
                header(httpRequest, "X-Capability-Token"), ACTION, PATH,
                header(httpRequest, "X-Request-Hash"), subject)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Capability token required for targeted handout");
        }
        try {
            return handoutService.submit(request, subject);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
    }

    private static String header(HttpServletRequest request, String name) {
        String value = request == null ? null : request.getHeader(name);
        return value == null || value.isBlank() ? null : value.strip();
    }
}
