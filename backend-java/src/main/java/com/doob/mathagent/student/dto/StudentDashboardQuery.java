package com.doob.mathagent.student.dto;

/**
 * Query context for a student learning dashboard.
 *
 * @param tenantId tenant id used to isolate school or organization data
 * @param viewerRole current viewer role, such as student, teacher, or admin
 * @param viewerSubjectId current viewer subject id
 * @param requestedStudentId optional student id selected by an admin or teacher
 */
public record StudentDashboardQuery(
        String tenantId,
        String viewerRole,
        String viewerSubjectId,
        String requestedStudentId) {

    /**
     * Returns a normalized query with safe defaults for local development.
     *
     * @return normalized query
     */
    public StudentDashboardQuery normalize() {
        String normalizedTenantId = textOrDefault(tenantId, "default");
        String normalizedViewerRole = textOrDefault(viewerRole, "student").toLowerCase();
        String normalizedViewerSubjectId = textOrDefault(viewerSubjectId, "local-student");
        String normalizedRequestedStudentId = requestedStudentId == null || requestedStudentId.isBlank()
                ? null
                : requestedStudentId.strip();
        return new StudentDashboardQuery(
                normalizedTenantId,
                normalizedViewerRole,
                normalizedViewerSubjectId,
                normalizedRequestedStudentId);
    }

    /**
     * Returns the student id that may be inspected by this viewer.
     *
     * @return target student id
     */
    public String targetStudentId() {
        StudentDashboardQuery normalized = normalize();
        if (normalized.canInspectOtherStudent() && normalized.requestedStudentId != null) {
            return normalized.requestedStudentId;
        }
        return normalized.viewerSubjectId;
    }

    /**
     * Returns whether the viewer is acting as an admin-style inspector.
     *
     * @return true when admin or teacher selects a different student
     */
    public boolean adminView() {
        StudentDashboardQuery normalized = normalize();
        return normalized.canInspectOtherStudent()
                && normalized.requestedStudentId != null
                && !normalized.requestedStudentId.equals(normalized.viewerSubjectId);
    }

    /**
     * Returns whether the viewer role can inspect another student's dashboard.
     *
     * @return true for admin and teacher roles
     */
    private boolean canInspectOtherStudent() {
        return "admin".equals(viewerRole) || "teacher".equals(viewerRole);
    }

    /**
     * Returns a stripped value or a default when blank.
     *
     * @param value input value
     * @param defaultValue default value
     * @return normalized text
     */
    private static String textOrDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.strip();
    }
}
