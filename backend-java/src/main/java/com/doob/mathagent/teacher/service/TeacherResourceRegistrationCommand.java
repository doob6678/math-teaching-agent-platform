package com.doob.mathagent.teacher.service;

import com.doob.mathagent.teacher.dto.TeacherResourceRegistrationRequest;

/**
 * Server-side command used to register a managed teacher resource.
 *
 * @param tenantId backend resolved tenant id that owns the resource
 * @param viewerRole backend resolved role, usually teacher or admin
 * @param viewerSubjectId backend resolved teacher/admin subject id
 * @param sourceType resource source type, such as feishu, local_path, local_docx, or textbook_md
 * @param title display title shown in teacher resource management pages
 * @param originalUrl original Feishu URL or external source URL
 * @param localPath local folder or file path configured by teacher/admin
 * @param permissionScope resource access scope, such as TEACHER_PRIVATE, MATH_VIP, or PUBLIC_TEXTBOOK
 * @param feishuExportFormat native Feishu export format for Feishu sources; supported values are md, docx, and pdf
 * @param parseMode TEXT for deterministic extraction or AI for higher-cost semantic labeling
 */
public record TeacherResourceRegistrationCommand(
        String tenantId,
        String viewerRole,
        String viewerSubjectId,
        String sourceType,
        String title,
        String originalUrl,
        String localPath,
        String permissionScope,
        String feishuExportFormat,
        String parseMode) {

    public TeacherResourceRegistrationCommand(
            String tenantId,
            String viewerRole,
            String viewerSubjectId,
            String sourceType,
            String title,
            String originalUrl,
            String localPath,
            String permissionScope,
            String feishuExportFormat) {
        this(
                tenantId,
                viewerRole,
                viewerSubjectId,
                sourceType,
                title,
                originalUrl,
                localPath,
                permissionScope,
                feishuExportFormat,
                "TEXT");
    }

    /**
     * Builds a command from backend identity and request body fields.
     *
     * @param tenantId backend resolved tenant id
     * @param viewerRole backend resolved role
     * @param viewerSubjectId backend resolved subject id
     * @param request request body
     * @return server-side registration command
     */
    public static TeacherResourceRegistrationCommand fromRequest(
            String tenantId,
            String viewerRole,
            String viewerSubjectId,
            TeacherResourceRegistrationRequest request) {
        TeacherResourceRegistrationRequest normalized = request.normalize();
        return new TeacherResourceRegistrationCommand(
                tenantId,
                viewerRole,
                viewerSubjectId,
                normalized.sourceType(),
                normalized.title(),
                normalized.originalUrl(),
                normalized.localPath(),
                normalized.permissionScope(),
                normalized.feishuExportFormat(),
                normalized.parseMode());
    }

    /**
     * Returns a normalized command after requiring backend-resolved identity fields.
     *
     * @return normalized command
     */
    public TeacherResourceRegistrationCommand normalize() {
        String normalizedSourceType = textOrDefault(sourceType, "local_path").toLowerCase();
        String normalizedOriginalUrl = blankToNull(originalUrl);
        String normalizedLocalPath = blankToNull(localPath);
        return new TeacherResourceRegistrationCommand(
                requireText(tenantId, "tenantId is required"),
                requireText(viewerRole, "viewerRole is required").toLowerCase(),
                requireText(viewerSubjectId, "viewerSubjectId is required"),
                normalizedSourceType,
                TeacherResourceTitleResolver.resolveOrDefault(
                        title,
                        normalizedSourceType,
                        normalizedOriginalUrl,
                        normalizedLocalPath),
                normalizedOriginalUrl,
                normalizedLocalPath,
                textOrDefault(permissionScope, "TEACHER_PRIVATE"),
                normalizeFeishuExportFormat(normalizedSourceType, feishuExportFormat),
                normalizeParseMode(parseMode));
    }

    /**
     * Returns whether this command points at a local file system path.
     *
     * @return true when localPath is present
     */
    public boolean hasLocalPath() {
        return localPath != null && !localPath.isBlank();
    }

    /**
     * Returns whether this command points at a remote URL.
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
     * Returns stripped text or fails when a backend-owned identity field is missing.
     *
     * @param value input value
     * @param message exception message
     * @return stripped text
     */
    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.strip();
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
        if ("TEXT".equals(normalized) || "AI".equals(normalized)) {
            return normalized;
        }
        throw new IllegalArgumentException("Unsupported teacher resource parse mode: " + value);
    }
}
