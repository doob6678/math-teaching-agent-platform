package com.doob.mathagent.teaching.controller;

import com.doob.mathagent.infrastructure.security.RequestSubject;
import com.doob.mathagent.infrastructure.security.RequestSubjectResolver;
import com.doob.mathagent.teaching.TeachingRequestContext;
import com.doob.mathagent.teaching.service.PythonTeachingHandoutClient;
import com.doob.mathagent.teaching.service.TeachingWorkflowService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 教师/运维侧的模型思考轨迹读边（2026-08-31 reasoning 落盘功能）。
 *
 * <p>落盘写入在 Python 私有 checkpoint；这里只代理有界投影，并且必须先通过任务归属
 * （workflowService.get 按主体隔离）与教师/管理员身份校验。学生版内容、答案与推理轨迹
 * 的隔离合同要求学生角色永远拿不到本端点数据；Python 投影本身已剔除 prompt 与答案原文。</p>
 */
@RestController
public class TeachingModelDiagnosticsController {

    /** 单次返回的 reasoning 截断上限；更大请求按此收窄，防止诊断页变成第二个导出通道。 */
    private static final int MAX_EXCERPT_CHARS = 4_000;

    private final TeachingWorkflowService workflowService;
    private final PythonTeachingHandoutClient handoutClient;
    private final RequestSubjectResolver subjectResolver;

    public TeachingModelDiagnosticsController(
            TeachingWorkflowService workflowService,
            PythonTeachingHandoutClient handoutClient,
            RequestSubjectResolver subjectResolver) {
        this.workflowService = workflowService;
        this.handoutClient = handoutClient;
        this.subjectResolver = subjectResolver;
    }

    /** Returns per-turn model diagnostics (provider, finish reason, reasoning excerpt) for an owned task. */
    @GetMapping("/api/teaching/tasks/{taskId}/model-diagnostics")
    public List<Map<String, Object>> modelDiagnostics(
            @PathVariable String taskId,
            @RequestParam(name = "excerptChars", defaultValue = "1200") int excerptChars,
            HttpServletRequest httpRequest) {
        RequestSubject subject = subjectResolver.resolve(httpRequest);
        RequestSubject normalized = subject.normalize();
        boolean privileged = "teacher".equals(normalized.subjectType()) || "admin".equals(normalized.subjectType());
        if (!privileged) {
            // 学生即使知道 taskId 也只会得到 403；归属校验在下一条仍然生效。
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Model diagnostics are teacher-only");
        }
        TeachingRequestContext context = new TeachingRequestContext(
                normalized.tenantId(), normalized.subjectType(), normalized.subjectId(), normalized.deviceId());
        if (workflowService.get(taskId, context).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Teaching task not found");
        }
        return handoutClient.readModelDiagnostics(taskId, Math.max(0, Math.min(excerptChars, MAX_EXCERPT_CHARS)));
    }
}
