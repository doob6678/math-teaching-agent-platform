package com.doob.mathagent.feishu;

import com.doob.mathagent.infrastructure.security.RequestSubject;
import com.doob.mathagent.infrastructure.security.RequestSubjectResolver;
import com.doob.mathagent.teaching.TeachingRequestContext;
import com.doob.mathagent.teaching.service.TeachingWorkflowService;
import com.doob.mathagent.teaching.vo.TeachingTaskResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 教师/管理员侧的飞书建库与批量上传入口（2026-08-31）。
 *
 * <p>与只读 OAuth 发现链路（feishu 包 discovery）相对，这里全部使用机器人租户身份写入。
 * 版本可见性和任务归属完全复用讲义导出的既有合同：教师版/讲解版只有教师或管理员能传，
 * 学生版人人可传（仅传自己的任务）。</p>
 */
@RestController
public class FeishuHandoutExportController {

    /** 与批量 ZIP 一致的小批次上限：同步接口内逐份渲染 + 上传，不能放大成无界任务。 */
    private static final int MAX_TASKS_PER_BATCH = 20;
    private static final Set<String> ALLOWED_VERSIONS = Set.of("teacher", "student", "lecture");
    private static final Set<String> PRIVILEGED_VERSIONS = Set.of("teacher", "lecture");

    private final RequestSubjectResolver subjectResolver;
    private final TeachingWorkflowService workflowService;
    private final FeishuTenantLibraryService libraries;
    private final FeishuHandoutUploadService uploads;

    public FeishuHandoutExportController(
            RequestSubjectResolver subjectResolver, TeachingWorkflowService workflowService,
            FeishuTenantLibraryService libraries, FeishuHandoutUploadService uploads) {
        this.subjectResolver = subjectResolver; this.workflowService = workflowService;
        this.libraries = libraries; this.uploads = uploads;
    }

    /** 建库请求体可为空；显式端点便于前端在上传前单独确认/预建文件夹。 */
    public record FeishuLibraryResponse(String tenantId, String folderName, String folderToken, boolean created) {}

    @PostMapping("/api/feishu/library")
    public FeishuLibraryResponse createLibrary(HttpServletRequest httpRequest) {
        RequestSubject subject = privilegedSubject(httpRequest);
        FeishuTenantLibraryService.TenantLibrary library = libraries.ensureLibrary(subject.normalize().tenantId());
        return new FeishuLibraryResponse(library.tenantId(), library.folderName(), library.folderToken(), library.created());
    }

    /** 批量上传请求：taskIds 必填且限 20，versions 缺省为 ["student","teacher"]（教师身份）/ ["student"]。 */
    public record FeishuUploadRequest(
            @NotEmpty @Size(max = MAX_TASKS_PER_BATCH) List<String> taskIds,
            @Size(max = 3) List<String> versions) {}

    @PostMapping("/api/feishu/handout/uploads")
    public List<FeishuHandoutUploadService.UploadResult> uploadHandouts(
            @Valid @RequestBody FeishuUploadRequest request, HttpServletRequest httpRequest) {
        RequestSubject subject = privilegedSubject(httpRequest);
        RequestSubject normalized = subject.normalize();
        TeachingRequestContext context = new TeachingRequestContext(
                normalized.tenantId(), normalized.subjectType(), normalized.subjectId(), normalized.deviceId());
        List<String> versions = normalizeVersions(request.versions(), normalized.subjectType());
        List<TeachingTaskResponse> tasks = request.taskIds().stream()
                .map(taskId -> workflowService.get(taskId == null ? "" : taskId.strip(), context)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Teaching task not found")))
                .toList();
        try {
            return uploads.uploadBatch(normalized.tenantId(), normalized.subjectId(), tasks, versions);
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, exception.getMessage(), exception);
        }
    }

    /** 只接受已知版本码；未申报版本时按身份给安全默认。 */
    static List<String> normalizeVersions(List<String> requested, String subjectType) {
        boolean privileged = "teacher".equals(subjectType) || "admin".equals(subjectType);
        if (requested == null || requested.isEmpty()) {
            return privileged ? List.of("student", "teacher") : List.of("student");
        }
        List<String> versions = requested.stream()
                .filter(item -> item != null && !item.isBlank())
                .map(item -> item.strip().toLowerCase(Locale.ROOT))
                .distinct()
                .toList();
        if (versions.stream().anyMatch(item -> !ALLOWED_VERSIONS.contains(item))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported handout version requested");
        }
        if (!privileged && versions.stream().anyMatch(PRIVILEGED_VERSIONS::contains)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Teacher handout versions are teacher-only");
        }
        return versions;
    }

    /** 建库/上传会写入机构空间，只允许教师与管理员触发。 */
    private RequestSubject privilegedSubject(HttpServletRequest httpRequest) {
        RequestSubject subject = subjectResolver.resolve(httpRequest).normalize();
        if (!"teacher".equals(subject.subjectType()) && !"admin".equals(subject.subjectType())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Feishu library export is teacher-only");
        }
        return subject;
    }
}
