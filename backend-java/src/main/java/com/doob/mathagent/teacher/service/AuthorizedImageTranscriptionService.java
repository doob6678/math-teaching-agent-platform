package com.doob.mathagent.teacher.service;

import com.doob.mathagent.agent.service.PythonMigratedWorkloadClient;
import com.doob.mathagent.infrastructure.text.TextEncodingRepair;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 将已授权教师图片转写委托给 Python Worker。
 *
 * <p>Java 在读取文件前复验路径、常规文件、MIME 和大小；Python 只收到受限 data URL，负责模型调用和用量记账。</p>
 */
@Service
public class AuthorizedImageTranscriptionService {

    /** 与 Python 端图片内存限制一致，阻止大文件进入跨进程模型请求。 */
    private static final long MAX_AUTHORIZED_IMAGE_BYTES = 8L * 1024L * 1024L;

    private final PythonMigratedWorkloadClient workloadClient;
    private final boolean enabled;

    public AuthorizedImageTranscriptionService(
            PythonMigratedWorkloadClient workloadClient,
            @Value("${math-agent.teaching.image-transcription.enabled:true}") boolean enabled) {
        this.workloadClient = workloadClient;
        this.enabled = enabled;
    }

    /** 读取经过上游权限检查的图片，并允许 Worker 按其签发路由执行有限 fallback。 */
    public VisionAnalysis analyzeAuthorizedLocalImage(Path authorizedImage, String contentType) {
        return analyzeAuthorizedLocalImage(authorizedImage, contentType, true);
    }

    /** 教师来源转写保留原有调用入口；provider 选择由 Python 的受限路由统一管理。 */
    public VisionAnalysis analyzeAuthorizedLocalImageWithPrimaryProvider(Path authorizedImage, String contentType) {
        return analyzeAuthorizedLocalImage(authorizedImage, contentType, false);
    }

    private VisionAnalysis analyzeAuthorizedLocalImage(Path authorizedImage, String contentType, boolean allowFallback) {
        if (!enabled) {
            return VisionAnalysis.skipped("vision-disabled");
        }
        if (authorizedImage == null || contentType == null
                || !contentType.strip().toLowerCase(java.util.Locale.ROOT).startsWith("image/")) {
            return failed("unsupported-image");
        }
        try {
            Path normalized = authorizedImage.toAbsolutePath().normalize();
            if (!Files.isRegularFile(normalized)) {
                return failed("image-unavailable");
            }
            byte[] image = Files.readAllBytes(normalized);
            if (image.length > MAX_AUTHORIZED_IMAGE_BYTES) {
                return failed("image-too-large");
            }
            String mimeType = contentType.strip().toLowerCase(java.util.Locale.ROOT);
            String dataUrl = "data:" + mimeType + ";base64," + Base64.getEncoder().encodeToString(image);
            PythonMigratedWorkloadClient.TranscriptionResult result = workloadClient.transcribeImage(
                    UUID.randomUUID().toString(), mimeType, dataUrl);
            String problemText = TextEncodingRepair.repairMojibake(result.problemText()).strip();
            return new VisionAnalysis(
                    true,
                    result.completed() && !problemText.isBlank(),
                    result.providerName(),
                    result.modelCode(),
                    problemText,
                    result.confidence(),
                    0,
                    0,
                    0,
                    result.completed() ? "python-vision-json" : "empty-vision-text");
        } catch (IOException exception) {
            return failed(exception.getClass().getSimpleName());
        } catch (RuntimeException exception) {
            return failed(exception.getClass().getSimpleName());
        }
    }

    private VisionAnalysis failed(String message) {
        return new VisionAnalysis(true, false, "", "", "", 0.0, 0, 0, 0, message);
    }

    /** 教师侧安全转写元数据；真实 provider usage 由 Python usage ledger 持久化。 */
    public record VisionAnalysis(
            boolean enabled,
            boolean succeeded,
            String providerName,
            String modelCode,
            String problemText,
            double confidence,
            int promptTokens,
            int completionTokens,
            int totalTokens,
            String message) {

        public static VisionAnalysis skipped(String message) {
            return new VisionAnalysis(false, false, "", "", "", 0.0, 0, 0, 0, message);
        }
    }
}
