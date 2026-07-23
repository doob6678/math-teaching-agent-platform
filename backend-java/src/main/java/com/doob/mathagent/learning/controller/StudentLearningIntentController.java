package com.doob.mathagent.learning.controller;

import com.doob.mathagent.infrastructure.security.RequestSubject;
import com.doob.mathagent.infrastructure.security.RequestSubjectResolver;
import com.doob.mathagent.learning.dto.StudentLearningIntentRequest;
import com.doob.mathagent.learning.service.StudentLearningIntentService;
import com.doob.mathagent.learning.vo.StudentLearningIntentResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Student-only intent routing boundary for the existing learning workflows. */
@RestController
public class StudentLearningIntentController {
    private final StudentLearningIntentService intentService;
    private final RequestSubjectResolver subjectResolver;

    public StudentLearningIntentController(
            StudentLearningIntentService intentService, RequestSubjectResolver subjectResolver) {
        this.intentService = intentService;
        this.subjectResolver = subjectResolver;
    }

    @PostMapping("/api/students/learning/intent")
    public StudentLearningIntentResponse recognize(
            @Valid @RequestBody StudentLearningIntentRequest request, HttpServletRequest httpRequest) {
        RequestSubject subject = subjectResolver.resolve(httpRequest).normalize();
        try {
            return intentService.recognize(subject, request);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, exception.getMessage(), exception);
        }
    }
}
