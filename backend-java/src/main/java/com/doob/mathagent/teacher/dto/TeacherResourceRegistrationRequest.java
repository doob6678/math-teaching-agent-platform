package com.doob.mathagent.teacher.dto;

/**
 * Request body used by teachers or admins to register a managed resource source.
 *
 * @param sourceType resource source type, such as feishu, local_path, local_docx, or textbook_md
 * @param title display title shown in teacher resource management pages
 * @param originalUrl original Feishu URL or external source URL
 * @param localPath local folder or file path configured by teacher/admin
 * @param permissionScope resource access scope, such as TEACHER_PRIVATE, MATH_VIP, or PUBLIC_TEXTBOOK
 */
public record TeacherResourceRegistrationRequest(
        String sourceType,
        String title,
        String originalUrl,
        String localPath,
        String permissionScope) {

    /**
     * Returns a normalized request body without adding identity defaults.
     *
     * @return normalized request body
     */
    public TeacherResourceRegistrationRequest normalize() {
        return new TeacherResourceRegistrationRequest(
                textOrDefault(sourceType, "local_path").toLowerCase(),
                textOrDefault(title, "untitled-teacher-resource"),
                blankToNull(originalUrl),
                blankToNull(localPath),
                textOrDefault(permissionScope, "TEACHER_PRIVATE"));
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
}
