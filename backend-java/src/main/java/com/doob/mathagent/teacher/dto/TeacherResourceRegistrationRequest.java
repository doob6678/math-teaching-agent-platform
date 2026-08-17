package com.doob.mathagent.teacher.dto;

import com.doob.mathagent.teacher.support.TeacherResourceSourceTypePolicy;
import com.doob.mathagent.teacher.support.TeacherResourceTitleResolver;

/**
 * Request body used by teachers or admins to register a managed resource source.
 *
 * @param sourceType resource source type, such as feishu, local_path, teacher_resource, qq_bundle, gaokao, or mock_exam
 * @param title display title shown in teacher resource management pages
 * @param originalUrl original Feishu URL or external source URL
 * @param localPath local folder or file path configured by teacher/admin
 * @param permissionScope resource access scope, such as TEACHER_PRIVATE, MATH_VIP, or PUBLIC_TEXTBOOK
 * @param feishuExportFormat Feishu export format: md, docx, or pdf
 * @param parseMode TEXT for deterministic extraction, MARKDOWN_ASSETS for local Markdown image materialization, or AI for semantic labeling
 */
public record TeacherResourceRegistrationRequest(
        String sourceType,
        String title,
        String originalUrl,
        String localPath,
        String permissionScope,
        String feishuExportFormat,
        String parseMode) {

    public TeacherResourceRegistrationRequest(
            String sourceType,
            String title,
            String originalUrl,
            String localPath,
            String permissionScope,
            String feishuExportFormat) {
        this(sourceType, title, originalUrl, localPath, permissionScope, feishuExportFormat, "TEXT");
    }

    /**
     * Returns a normalized request body without adding identity defaults.
     *
     * @return normalized request body
     */
    public TeacherResourceRegistrationRequest normalize() {
        String normalizedSourceType = TeacherResourceSourceTypePolicy.normalizeForRegistration(sourceType);
        String normalizedOriginalUrl = blankToNull(originalUrl);
        String normalizedLocalPath = blankToNull(localPath);
        return new TeacherResourceRegistrationRequest(
                normalizedSourceType,
                TeacherResourceTitleResolver.resolveOrDefault(
                        title,
                        normalizedSourceType,
                        normalizedOriginalUrl,
                        normalizedLocalPath),
                normalizedOriginalUrl,
                normalizedLocalPath,
                // Feishu is a tenant teacher library by default. Other uploads retain their existing student-share
                // default; callers may always choose an explicit publication scope.
                textOrDefault(permissionScope, "feishu".equals(normalizedSourceType) ? "TEACHER_SHARED" : "TENANT_PUBLIC"),
                normalizeFeishuExportFormat(normalizedSourceType, feishuExportFormat),
                normalizeParseMode(parseMode));
    }

    /**
     * Returns whether this request points at a local file system path.
     *
     * @return true when localPath is present
     */
    public boolean hasLocalPath() {
        return localPath != null && !localPath.isBlank();
    }

    /**
     * Returns whether this request points at a remote URL.
     *
     * @return true when originalUrl is present
     */
    public boolean hasOriginalUrl() {
        return originalUrl != null && !originalUrl.isBlank();
    }

    /**
     * Returns a stripped value or a default when blank.
     *
     * @param value input value
     * @param defaultValue fallback value
     * @return normalized text
     */
    private static String textOrDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.strip();
    }

    /**
     * Converts blank text to null and strips non-blank text.
     *
     * @param value input value
     * @return null or stripped text
     */
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    /**
     * Normalizes and validates the selected native Feishu export format.
     *
     * @param sourceType normalized source type
     * @param value requested export format
     * @return md/docx/pdf for Feishu sources, or null for non-Feishu sources
     */
    private static String normalizeFeishuExportFormat(String sourceType, String value) {
        if (!"feishu".equals(sourceType)) {
            return null;
        }
        String normalized = textOrDefault(value, "md").toLowerCase();
        if ("md".equals(normalized) || "docx".equals(normalized) || "pdf".equals(normalized)) {
            return normalized;
        }
        throw new IllegalArgumentException("Unsupported Feishu export format: " + value);
    }

    private static String normalizeParseMode(String value) {
        String normalized = textOrDefault(value, "TEXT").toUpperCase();
        // MARKDOWN_ASSETS keeps deterministic text parsing but makes the image-localization contract explicit.
        if ("TEXT".equals(normalized) || "MARKDOWN_ASSETS".equals(normalized) || "AI".equals(normalized)) {
            return normalized;
        }
        throw new IllegalArgumentException("Unsupported teacher resource parse mode: " + value);
    }
}
