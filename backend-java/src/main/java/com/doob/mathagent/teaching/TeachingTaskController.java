package com.doob.mathagent.teaching;

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

    private final TeachingWorkflowService workflowService;

    /**
     * 注入教学编排服务。
     */
    public TeachingTaskController(TeachingWorkflowService workflowService) {
        this.workflowService = workflowService;
    }

    /**
     * 提交教学任务；当前阶段同步完成，后续可切换为异步队列。
     */
    @PostMapping("/api/teaching/tasks")
    public TeachingTaskResponse submit(
            @Valid @RequestBody TeachingTaskRequest request,
            HttpServletRequest httpRequest) {
        return workflowService.submit(request, requestContext(httpRequest));
    }

    /**
     * 按 taskId 查询任务结果；只允许任务归属主体读取。
     */
    @GetMapping("/api/teaching/tasks/{taskId}")
    public TeachingTaskResponse get(
            @PathVariable String taskId,
            HttpServletRequest httpRequest) {
        return workflowService.get(taskId, requestContext(httpRequest))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Teaching task not found"));
    }

    /**
     * 从 HTTP 请求头读取租户、主体和设备信息，用于任务隔离和审计。
     */
    private static TeachingRequestContext requestContext(HttpServletRequest httpRequest) {
        if (httpRequest == null) {
            return TeachingRequestContext.localTeacher();
        }
        return new TeachingRequestContext(
                headerOrDefault(httpRequest, "X-Tenant-Id", "default"),
                headerOrDefault(httpRequest, "X-Subject-Type", "anonymous"),
                headerOrDefault(httpRequest, "X-Subject-Id", "anonymous"),
                headerOrDefault(httpRequest, "X-Device-Id", "unknown-device"));
    }

    /**
     * 读取请求头，空白时使用默认值。
     */
    private static String headerOrDefault(HttpServletRequest request, String name, String defaultValue) {
        String value = request.getHeader(name);
        return value == null || value.isBlank() ? defaultValue : value.strip();
    }
}
