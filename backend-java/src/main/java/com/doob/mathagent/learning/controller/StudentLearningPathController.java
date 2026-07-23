package com.doob.mathagent.learning.controller;

import com.doob.mathagent.infrastructure.security.RequestSubjectResolver;
import com.doob.mathagent.learning.service.StudentLearningPathService;
import com.doob.mathagent.learning.vo.StudentLearningPathResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Student read-only learning-path endpoint derived from mastery and visible prerequisite edges. */
@RestController
public class StudentLearningPathController {
    private final StudentLearningPathService pathService;
    private final RequestSubjectResolver subjectResolver;

    public StudentLearningPathController(StudentLearningPathService pathService, RequestSubjectResolver subjectResolver) {
        this.pathService = pathService;
        this.subjectResolver = subjectResolver;
    }

    @GetMapping("/api/students/learning/path")
    public StudentLearningPathResponse get(HttpServletRequest request) {
        return pathService.build(subjectResolver.resolve(request).normalize());
    }
}
