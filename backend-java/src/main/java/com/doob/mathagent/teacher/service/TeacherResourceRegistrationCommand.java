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
 */
public record TeacherResourceRegistrationCommand(
        String tenantId,
        String viewerRole,
        String viewerSubjectId,
        String sourceType,
        String title,
        String originalUrl,
        String localPath,
        String permissionScope) {

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
                normalized.permissionScope());
    }

    /**
     * Returns a normalized command with local development defaults for non-web tests.
     *
     * @return normalized command
     */
    public TeacherResourceRegistrationCommand normalize() {
        return new TeacherResourceRegistrationCommand(
                textOrDefault(tenantId, "default"),
                textOrDefault(viewerRole, "teacher").toLowerCase(),
                textOrDefault(viewerSubjectId, "local-teacher-console"),
                textOrDefault(sourceType, "local_path").toLowerCase(),
                textOrDefault(title, "untitled-teacher-resource"),
                blankToNull(originalUrl),
                blankToNull(localPath),
                textOrDefault(permissionScope, "TEACHER_PRIVATE"));
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
     * Converts blank text to null and strips non-blank text.
     *
     * @param value input value
     * @return null or stripped text
     */
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
