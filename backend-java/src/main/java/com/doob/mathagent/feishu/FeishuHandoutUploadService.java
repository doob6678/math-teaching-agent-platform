package com.doob.mathagent.feishu;

import com.doob.mathagent.teaching.service.TeachingHandoutPdfExportService;
import com.doob.mathagent.teaching.vo.TeachingTaskResponse;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * 批量上传讲义 PDF 到租户的飞书讲义库。
 *
 * <p>幂等键是 (tenant, task, version) 上的内容哈希：未变化则跳过（SKIPPED 记账，不重复
 * 占用飞书配额）；变化则重传并覆盖行。单个文件失败只记 FAILED 行并继续队列，符合批量
 * 语义——一次网络抖动不能报废整个批。调用方必须已完成任务归属与版本可见性校验。</p>
 */
@Service
public class FeishuHandoutUploadService {
    private static final Logger LOGGER = LoggerFactory.getLogger(FeishuHandoutUploadService.class);

    private final FeishuHandoutUploadMapper mapper;
    private final FeishuTenantLibraryService libraries;
    private final FeishuDriveClient drive;
    private final FeishuTenantTokenService tokens;
    private final TeachingHandoutPdfExportService pdfExportService;

    public FeishuHandoutUploadService(
            FeishuHandoutUploadMapper mapper, FeishuTenantLibraryService libraries, FeishuDriveClient drive,
            FeishuTenantTokenService tokens, TeachingHandoutPdfExportService pdfExportService) {
        this.mapper = mapper; this.libraries = libraries; this.drive = drive; this.tokens = tokens;
        this.pdfExportService = pdfExportService;
    }

    /** One per requested task/version pair. */
    public record UploadResult(String taskId, String version, String status, String fileName, String fileToken, String message) {}

    public List<UploadResult> uploadBatch(String tenantId, String subjectId, List<TeachingTaskResponse> tasks,
            List<String> versions) {
        FeishuTenantLibraryService.TenantLibrary library = libraries.ensureLibrary(tenantId);
        List<UploadResult> results = new ArrayList<>();
        for (TeachingTaskResponse task : tasks) {
            for (String version : versions) {
                results.add(uploadOne(tenantId, subjectId, library, task, version));
            }
        }
        return List.copyOf(results);
    }

    private UploadResult uploadOne(String tenantId, String subjectId,
            FeishuTenantLibraryService.TenantLibrary library, TeachingTaskResponse task, String version) {
        String fileName = fileName(task, version);
        byte[] pdf;
        try {
            pdf = pdfExportService.renderForPublication(task, version).bytes();
        } catch (RuntimeException exception) {
            LOGGER.warn("feishu_upload_render_failed tenantId={} taskId={} version={}", tenantId, task.taskId(), version, exception);
            return new UploadResult(task.taskId(), version, "FAILED", fileName, "", "渲染失败: " + typeOf(exception));
        }
        String hash = sha256(pdf);
        FeishuHandoutUploadEntity row = mapper.find(tenantId, task.taskId(), version);
        if (row != null && "UPLOADED".equals(row.getStatus()) && hash.equals(row.getContentHash())) {
            return new UploadResult(task.taskId(), version, "SKIPPED", row.getFileName(), row.getFileToken(), "内容未变化");
        }
        try {
            String fileToken = drive.uploadFile(tokens.token(), library.folderToken(), fileName, pdf);
            persist(row, tenantId, subjectId, task.taskId(), version, fileName, hash, fileToken, "UPLOADED", "");
            return new UploadResult(task.taskId(), version, "UPLOADED", fileName, fileToken, "");
        } catch (RuntimeException exception) {
            LOGGER.warn("feishu_upload_failed tenantId={} taskId={} version={}", tenantId, task.taskId(), version, exception);
            persist(row, tenantId, subjectId, task.taskId(), version, fileName, hash, "", "FAILED", typeOf(exception));
            return new UploadResult(task.taskId(), version, "FAILED", fileName, "", "上传失败: " + typeOf(exception));
        }
    }

    private void persist(FeishuHandoutUploadEntity row, String tenantId, String subjectId, String taskId,
            String version, String fileName, String hash, String fileToken, String status, String message) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        if (row == null) {
            row = new FeishuHandoutUploadEntity();
            row.setTenantId(tenantId); row.setTaskId(taskId); row.setVersion(version); row.setCreatedAt(now);
        }
        row.setSubjectId(subjectId); row.setFileName(fileName); row.setContentHash(hash);
        row.setFileToken(fileToken); row.setStatus(status); row.setMessage(message); row.setUpdatedAt(now);
        try {
            if (row.getId() == null) mapper.insert(row); else mapper.updateById(row);
        } catch (DuplicateKeyException race) {
            // 同租户并发点了同一任务：唯一键保护后重读，不重复记账。
            FeishuHandoutUploadEntity winner = mapper.find(tenantId, taskId, version);
            if (winner != null && row.getId() == null) {
                winner.setStatus(status); winner.setMessage(message); winner.setUpdatedAt(now);
                mapper.updateById(winner);
            }
        }
    }

    /** 文件名带版本与任务号，教师可核对；非法字符与空格折叠。 */
    static String fileName(TeachingTaskResponse task, String version) {
        String goal = task.learningGoal() == null || task.learningGoal().isBlank() ? "数学讲义" : task.learningGoal().strip();
        String safe = goal.replaceAll("[\\p{Cntrl}\\\\/:*?\"<>|]", "_").replaceAll("\\s+", " ");
        String suffix = switch (version == null ? "" : version.toLowerCase(java.util.Locale.ROOT)) {
            case "student" -> "学生版";
            case "lecture" -> "讲解版";
            default -> "教师版";
        };
        return safe + "-" + suffix + "-" + task.taskId() + ".pdf";
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String typeOf(Throwable throwable) {
        return throwable.getClass().getSimpleName();
    }
}
