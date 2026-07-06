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

    public static final String GLOBAL_STUDENT_ID = "__all_students__";

    /**
     * Returns a normalized query after requiring backend-resolved identity fields.
     *
     * @return normalized query
     */
    public StudentDashboardQuery normalize() {
        String normalizedTenantId = requireText(tenantId, "tenantId is required");
        String normalizedViewerRole = requireText(viewerRole, "viewerRole is required").toLowerCase();
        String normalizedViewerSubjectId = requireText(viewerSubjectId, "viewerSubjectId is required");
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
        if (normalized.canInspectOtherStudent()) {
            return GLOBAL_STUDENT_ID;
        }
        return normalized.viewerSubjectId;
    }

    /**
     * Returns whether this is a tenant-level dashboard view for teacher/admin users.
     */
    public boolean globalView() {
        StudentDashboardQuery normalized = normalize();
        return normalized.canInspectOtherStudent() && normalized.requestedStudentId == null;
    }

    /**
     * Returns whether the viewer is acting as an admin-style inspector.
     *
     * @return true when admin or teacher selects a different student
     */
    public boolean adminView() {
        StudentDashboardQuery normalized = normalize();
        return normalized.canInspectOtherStudent()
                && (normalized.requestedStudentId == null
                || !normalized.requestedStudentId.equals(normalized.viewerSubjectId));
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
     * Returns stripped text or fails when a backend-owned identity field is missing.
     */
    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.strip();
    }
}
