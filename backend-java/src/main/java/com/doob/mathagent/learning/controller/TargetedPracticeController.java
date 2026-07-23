package com.doob.mathagent.learning.controller;

import com.doob.mathagent.infrastructure.security.RequestSubject;
import com.doob.mathagent.infrastructure.security.RequestSubjectResolver;
import com.doob.mathagent.learning.dto.TargetedPracticeRequest;
import com.doob.mathagent.learning.service.TargetedPracticeService;
import com.doob.mathagent.learning.vo.StudentPracticeTaskResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Student entry point for creating and reading a safe, weak-point-driven practice task. */
@RestController
public class TargetedPracticeController {
    private final TargetedPracticeService practiceService;
    private final RequestSubjectResolver subjectResolver;

    public TargetedPracticeController(TargetedPracticeService practiceService, RequestSubjectResolver subjectResolver) {
        this.practiceService = practiceService;
        this.subjectResolver = subjectResolver;
    }

    @PostMapping("/api/students/learning/practice")
    public StudentPracticeTaskResponse submit(
            @Valid @RequestBody TargetedPracticeRequest request,
            HttpServletRequest httpRequest) {
        try {
            return practiceService.submit(request, subjectResolver.resolve(httpRequest).normalize());
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
    }

    @GetMapping("/api/students/learning/practice/{taskId}")
    public StudentPracticeTaskResponse get(
            @PathVariable String taskId,
            HttpServletRequest httpRequest) {
        try {
            return practiceService.get(taskId, subjectResolver.resolve(httpRequest).normalize());
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage(), exception);
        }
    }
}
