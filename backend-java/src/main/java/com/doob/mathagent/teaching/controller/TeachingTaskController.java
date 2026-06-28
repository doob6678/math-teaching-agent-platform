package com.doob.mathagent.teaching.controller;

import com.doob.mathagent.infrastructure.security.RequestSubject;
import com.doob.mathagent.infrastructure.security.RequestSubjectResolver;
import com.doob.mathagent.teaching.TeachingRequestContext;
import com.doob.mathagent.teaching.dto.TeachingTaskRequest;
import com.doob.mathagent.teaching.service.TeachingCapabilityVerifier;
import com.doob.mathagent.teaching.service.TeachingWorkflowService;
import com.doob.mathagent.teaching.vo.TeachingTaskResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 教学任务接口：前端提交任务后可通过 taskId 持续查询结果，支持页面离开后的恢复。
 */
@RestController
public class TeachingTaskController {

    private static final String TEACHING_SUBMIT_ACTION = "teaching:submit";
    private static final String TEACHING_TASKS_PATH = "/api/teaching/tasks";

    private final TeachingWorkflowService workflowService;
    private final RequestSubjectResolver subjectResolver;
    private final TeachingCapabilityVerifier capabilityVerifier;

    /**
     * 注入教学编排服务。
     */
    public TeachingTaskController(
            TeachingWorkflowService workflowService,
            RequestSubjectResolver subjectResolver,
            TeachingCapabilityVerifier capabilityVerifier) {
        this.workflowService = workflowService;
        this.subjectResolver = subjectResolver;
        this.capabilityVerifier = capabilityVerifier;
    }

    /**
     * 提交教学任务；当前阶段同步完成，后续可切换为异步队列。
     */
    @PostMapping("/api/teaching/tasks")
    public TeachingTaskResponse submit(
            @Valid @RequestBody TeachingTaskRequest request,
            HttpServletRequest httpRequest) {
        RequestSubject subject = subjectResolver.resolve(httpRequest);
        if (!capabilityVerifier.verify(
                headerOrNull(httpRequest, "X-Capability-Token"),
                TEACHING_SUBMIT_ACTION,
                TEACHING_TASKS_PATH,
                headerOrNull(httpRequest, "X-Request-Hash"),
                subject)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Capability token required for teaching submit");
        }
        return workflowService.submit(request, requestContext(subject));
    }

    /**
     * 按 taskId 查询任务结果；只允许任务归属主体读取。
     */
    @GetMapping("/api/teaching/tasks/{taskId}")
    public TeachingTaskResponse get(
            @PathVariable String taskId,
            HttpServletRequest httpRequest) {
        return workflowService.get(taskId, requestContext(subjectResolver.resolve(httpRequest)))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Teaching task not found"));
    }

    /**
     * 从后端会话身份读取租户、主体和设备信息，用于任务隔离和审计。
     */
    private static TeachingRequestContext requestContext(RequestSubject subject) {
        RequestSubject normalized = subject.normalize();
        return new TeachingRequestContext(
                normalized.tenantId(),
                normalized.subjectType(),
                normalized.subjectId(),
                normalized.deviceId());
    }

    /**
     * Reads a non-authoritative request header used for capability token verification.
     */
    private static String headerOrNull(HttpServletRequest request, String name) {
        if (request == null) {
            return null;
        }
        String value = request.getHeader(name);
        return value == null || value.isBlank() ? null : value.strip();
    }
}
